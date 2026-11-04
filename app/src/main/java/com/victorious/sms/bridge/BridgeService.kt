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
import org.json.JSONArray
import org.json.JSONObject

/**
 * EXTERNAL_NODE Messenger bridge. Request: Message.data "frame" = one JSON object
 * {"protocol_major":1,"id","op","params":{...}}. Reply: {"id","ok","result"?,"error"?}.
 * Caller identity comes ONLY from Message.sendingUid resolved through PackageManager.
 */
class BridgeService : Service() {
    private lateinit var authorizer: BridgeAuthorizer

    private val handler = object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            // DCA wire (MessengerWire): what=1, Bundle "request" = snake_case BridgeRequest;
            // reply what=2, Bundle "response". Legacy: Bundle "frame" with {"id","op","params"}.
            val dca = msg.data?.containsKey(DcaWire.KEY_REQUEST) == true
            val out = if (dca) {
                val req = runCatching { JSONObject(msg.data.getString(DcaWire.KEY_REQUEST).orEmpty()) }.getOrNull()
                val legacy = JSONObject()
                    .put("protocol_major", req?.optInt("protocol_major", -1) ?: -1)
                    .put("id", req?.optString("request_id").orEmpty())
                    .put("op", req?.optString("operation").orEmpty())
                    .put("params", (req?.optJSONObject("parameters") ?: JSONObject()).also {
                        if (!it.has("requestId")) it.put("requestId", req?.optString("request_id").orEmpty())
                    })
                val r = handle(msg.sendingUid, legacy.toString())
                val resp = JSONObject().put("protocol_major", 1).put("request_id", r.optString("id")).put("ok", r.optBoolean("ok"))
                if (r.has("result")) resp.put("result", r.get("result"))
                if (r.has("error")) resp.put("error", JSONObject().put("code", r.optString("error")))
                Message.obtain(null, DcaWire.WHAT_RESPONSE).apply { data = Bundle().apply { putString(DcaWire.KEY_RESPONSE, resp.toString()) } }
            } else {
                val reply = handle(msg.sendingUid, msg.data?.getString("frame").orEmpty())
                Message.obtain(null, msg.what).apply { data = Bundle().apply { putString("frame", reply.toString()) } }
            }
            try { msg.replyTo?.send(out) } catch (_: RemoteException) {}
        }
    }

    private fun handle(uid: Int, frame: String): JSONObject {
        val req = runCatching { JSONObject(frame) }.getOrNull() ?: return err("", "invalid_frame")
        val id = req.optString("id").take(64)
        if (req.optInt("protocol_major", -1) != 1) return err(id, "unsupported_protocol")
        val op = req.optString("op")
        val params = req.optJSONObject("params") ?: JSONObject()
        val caller = when (val d = authorizer.authorize(uid, op)) {
            is AuthDecision.Denied -> return err(id, d.code)
            is AuthDecision.Allowed -> d.caller
        }
        return when (op) {
            "capabilities.get" -> ok(id, JSONObject().put("ops", JSONArray().apply {
                listOf(NodeStatusCapability.descriptor, SmsSendCapability.descriptor, SmsSendCapability.statusDescriptor,
                    ConfigCapabilities.export, ConfigCapabilities.import, ConfigOps.statusDescriptor).forEach {
                    put(JSONObject().put("name", it.name).put("kind", it.kind)
                        .put("remoteAllowed", it.remoteAllowed).put("requiresConfirm", it.requiresConfirm))
                }
            }))
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
            SmsSendCapability.STATUS_OP -> {
                RemoteSmsQueue.ensureLoaded(applicationContext)
                val st = RemoteSmsQueue.status(caller, params.optString("requestId"))
                    ?: return err(id, "not_found")
                ok(id, JSONObject().put("requestId", params.optString("requestId")).put("status", st.name.lowercase()))
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
            else -> err(id, "not_implemented")
        }
    }

    private fun ok(id: String, result: JSONObject) = JSONObject().put("id", id).put("ok", true).put("result", result)
    private fun err(id: String, code: String) = JSONObject().put("id", id).put("ok", false).put("error", code)

    override fun onCreate() { super.onCreate(); authorizer = BridgeAuthorizer(this); ReplayGuard.ensureLoaded(this) }
    override fun onBind(intent: Intent): IBinder = Messenger(handler).binder
}
