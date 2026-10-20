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
            val reply = handle(msg.sendingUid, msg.data?.getString("frame").orEmpty())
            val out = Message.obtain(null, msg.what).apply { data = Bundle().apply { putString("frame", reply.toString()) } }
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
                    ConfigCapabilities.export, ConfigCapabilities.import).forEach {
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
                val st = RemoteSmsQueue.status(caller, params.optString("requestId"))
                    ?: return err(id, "not_found")
                ok(id, JSONObject().put("requestId", params.optString("requestId")).put("status", st.name.lowercase()))
            }
            else -> err(id, "not_implemented")
        }
    }

    private fun ok(id: String, result: JSONObject) = JSONObject().put("id", id).put("ok", true).put("result", result)
    private fun err(id: String, code: String) = JSONObject().put("id", id).put("ok", false).put("error", code)

    override fun onCreate() { super.onCreate(); authorizer = BridgeAuthorizer(this) }
    override fun onBind(intent: Intent): IBinder = Messenger(handler).binder
}
