package com.victorious.sms.bridge

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import com.victorious.comms.ArchiveWire
import com.victorious.sms.archive.ArchiveRuntime
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * EXTERNAL_NODE Messenger bridge. Request: Message.data "frame" = one JSON object
 * {"protocol_major":1,"id","op","params":{...}}. Reply: {"id","ok","result"?,"error"?}.
 * Caller identity comes ONLY from Message.sendingUid resolved through PackageManager.
 */
class BridgeService : Service() {
    private lateinit var authorizer: BridgeAuthorizer

    /** Archive reads touch SQLite and can take a moment; they run here so the main thread is never blocked. */
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "bridge-archive").apply { isDaemon = true } }

    private val handler = object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            // DCA wire (MessengerWire): what=1, Bundle "request" = snake_case BridgeRequest;
            // reply what=2, Bundle "response". Legacy: Bundle "frame" with {"id","op","params"}.
            // Read the OS-stamped sender here, on the handler thread; a later thread must not ask again.
            val uid = msg.sendingUid
            val replyTo = msg.replyTo
            if (msg.data?.containsKey(DcaWire.KEY_REQUEST) == true) {
                val req = DcaFrames.parseRequest(msg.data.getString(DcaWire.KEY_REQUEST))
                process(uid, req.legacy.toString()) { r ->
                    send(replyTo, Message.obtain(null, DcaWire.WHAT_RESPONSE).apply {
                        data = Bundle().apply { putString(DcaWire.KEY_RESPONSE, DcaFrames.response(req, r).toString()) }
                    })
                }
            } else {
                val what = msg.what
                process(uid, msg.data?.getString("frame").orEmpty()) { r ->
                    send(replyTo, Message.obtain(null, what).apply { data = Bundle().apply { putString("frame", r.toString()) } })
                }
            }
        }
    }

    private fun send(to: Messenger?, reply: Message) {
        try { to?.send(reply) } catch (_: RemoteException) {}
    }

    /** Answers through [deliver], on this thread for every op except the archive reads, which answer from [worker]. */
    private fun process(uid: Int, frame: String, deliver: (JSONObject) -> Unit) {
        // This runs on the main thread: an exception from any op (or from a worker that was already shut down because a
        // stale client still holds our Messenger after onDestroy) must become an error reply, never an app crash.
        val r = try { handle(uid, frame, deliver) } catch (_: java.util.concurrent.RejectedExecutionException) {
            err(runCatching { JSONObject(frame).optString("id").take(64) }.getOrDefault(""), "unavailable")
        } catch (_: Exception) {
            err(runCatching { JSONObject(frame).optString("id").take(64) }.getOrDefault(""), "internal_error")
        }
        r?.let(deliver)
    }

    /** Null means [deliver] will be called later from another thread. */
    private fun handle(uid: Int, frame: String, deliver: (JSONObject) -> Unit): JSONObject? {
        val req = runCatching { JSONObject(frame) }.getOrNull() ?: return err("", "invalid_frame")
        val id = req.optString("id").take(64)
        if (req.optInt("protocol_major", -1) != 1) return err(id, "unsupported_protocol")
        val op = req.optString("op")
        val params = req.optJSONObject("params") ?: JSONObject()
        val caller = when (val d = authorizer.authorize(uid, op)) {
            is AuthDecision.Denied -> return err(id, d.code)
            is AuthDecision.Allowed -> d.caller
        }
        // Status and capabilities reads: one definition shared with the authorizer, so an allowed read is always answered.
        BridgeReads.answer(applicationContext, id, op)?.let { return it }
        return when (op) {
            SmsSendCapability.OP -> {
                val r = RemoteSmsQueue.submit(
                    applicationContext, caller,
                    requestId = params.optString("requestId"),
                    to = params.optString("to"),
                    body = params.optString("body"),
                    nonce = params.optString("nonce"),
                    timestampMs = params.optLong("timestampMs", 0L),
                    ttlMs = if (params.has("ttlMs")) params.optLong("ttlMs") else null,
                )
                when (r) {
                    is RemoteSmsQueue.Result.Accepted ->
                        ok(id, JSONObject().put("requestId", r.requestId).put("status", "pending"))
                    is RemoteSmsQueue.Result.Rejected -> err(id, r.code)
                }
            }
            DraftCapability.OP_DELIVER, DraftCapability.OP_FROM_ARCHIVE, DraftCapability.OP_STATUS -> {
                worker.execute {
                    val reply = try {
                        when (val decision = authorizer.authorize(uid, op)) {
                            is AuthDecision.Allowed -> draftReply(id, decision.caller, op, params)
                            is AuthDecision.Denied -> err(id, decision.code)
                        }
                    } catch (_: Exception) {
                        err(id, "internal_error")
                    }
                    deliver(reply)
                }
                null
            }
            SmsSendCapability.STATUS_OP -> {
                worker.execute {
                    val reply = try {
                        RemoteSmsQueue.ensureLoaded(applicationContext)
                        val state = RemoteSmsQueue.carrierStatus(applicationContext, caller, params.optString("requestId"))
                        if (state == null) err(id, "not_found") else ok(id, state)
                    } catch (_: Exception) {
                        err(id, "internal_error")
                    }
                    deliver(reply)
                }
                null
            }
            ConfigCapabilities.EXPORT ->
                ok(id, ConfigOps.export(packageName, AppConfigStore(applicationContext).snapshot()))
            ConfigCapabilities.IMPORT -> {
                // Same envelope checks as sms.send; the nonce is consumed (and persisted) even for a dry run.
                val ttlMs = if (params.has("ttlMs")) params.optLong("ttlMs") else null
                ReplayGuard.check(params.optString("nonce"), params.optLong("timestampMs", 0L), ttlMs)?.let { return err(id, it) }
                val policy = ConflictPolicy.fromWire(params.optString("policy").ifEmpty { null })
                val store = AppConfigStore(applicationContext)
                when (val r = ConfigOps.prepare(store.snapshot(), ConfigOps.fromJson(params.opt("payload")), policy)) {
                    is ConfigOps.Prepared.Rejected -> err(id, r.code)
                    is ConfigOps.Prepared.Plan -> {
                        val keys = JSONArray(r.changes.keys.toList())
                        // dryRun defaults to true: nothing is ever applied unless explicitly asked AND confirmed on device.
                        if (params.optBoolean("dryRun", true)) ok(id, JSONObject().put("dryRun", true).put("changedKeys", keys))
                        else {
                            val p = ConfigOps.submit(applicationContext, caller.packageName, r.changes, ReplayGuard.effectiveTtl(ttlMs))
                            if (p == null) ok(id, JSONObject().put("status", "no_changes").put("changedKeys", keys))
                            else ok(id, JSONObject().put("status", "pending").put("importId", p.importId).put("changedKeys", keys))
                        }
                    }
                }
            }
            ConfigOps.STATUS_OP -> {
                val st = ConfigOps.status(caller.packageName, params.optString("importId")) ?: return err(id, "not_found")
                ok(id, JSONObject().put("importId", params.optString("importId")).put("status", st.name.lowercase()))
            }
            ArchiveWire.CAPABILITIES, ArchiveWire.LIST, ArchiveWire.READ, ArchiveWire.SEARCH, ArchiveWire.CONTEXT -> {
                // Opened inside the worker: the first call creates and migrates the database.
                worker.execute {
                    deliver(
                        try { ArchiveBridge.reply(ArchiveRuntime.get(applicationContext).archive, id, caller.packageName, op, params) }
                        catch (_: Exception) { err(id, "internal_error") },
                    )
                }
                null
            }
            else -> err(id, "not_implemented")
        }
    }

    private fun draftReply(id: String, caller: CallerIdentity, op: String, params: JSONObject): JSONObject {
        val context = applicationContext
        DraftReview.ensureLoaded(context)
        val now = System.currentTimeMillis()
        if (op == DraftCapability.OP_STATUS) {
            val draftId = params.optString("draftId")
            val state = DraftHandoff.status(caller, draftId, now) ?: return err(id, "not_found")
            return ok(id, JSONObject().put("draftId", draftId).put("state", state.name.lowercase()).apply {
                DraftHandoff.get(caller, draftId)?.detail?.let { put("detail", it.take(200)) }
            })
        }
        val notify = { draft: IncomingDraft -> DraftReview.notify(context, draft) }
        val result = if (op == DraftCapability.OP_FROM_ARCHIVE) {
            DraftHandoff.deliverArchive(ArchiveRuntime.get(context).archive, caller, params, now, notify)
        } else {
            DraftHandoff.deliver(caller, params, now, notify = notify)
        }
        return when (result) {
            is DraftHandoff.Result.Accepted ->
                ok(id, JSONObject().put("draftId", result.draftId).put("state", result.state.name.lowercase()))
            is DraftHandoff.Result.Rejected -> err(id, result.code)
        }
    }

    private fun ok(id: String, result: JSONObject) = JSONObject().put("id", id).put("ok", true).put("result", result)
    private fun err(id: String, code: String) = JSONObject().put("id", id).put("ok", false).put("error", code)

    override fun onCreate() { super.onCreate(); authorizer = BridgeAuthorizer(this); ReplayGuard.ensureLoaded(this) }
    override fun onDestroy() { worker.shutdown(); super.onDestroy() }
    override fun onBind(intent: Intent): IBinder = Messenger(handler).binder
}
