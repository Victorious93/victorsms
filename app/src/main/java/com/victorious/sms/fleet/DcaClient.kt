package com.victorious.sms.fleet

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import com.victorious.sms.bridge.BridgeSettings
import com.victorious.sms.bridge.CallerVerifier
import com.victorious.sms.bridge.DcaWire
import com.victorious.sms.bridge.PairingRegistry
import org.json.JSONObject
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** This node's frozen id, `victorsms-<32 hex>`, generated once. */
object NodeId {
    fun get(context: Context): String {
        val p = context.getSharedPreferences("node_identity", Context.MODE_PRIVATE)
        p.getString("nodeId", null)?.let { return it }
        val id = "victorsms-" + ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        p.edit().putString("nodeId", id).apply()
        return id
    }
}

/**
 * Client for droidcommand-AI's inbound bridge (`ai.droidcommand.app.action.BRIDGE`, MessengerWire:
 * what=1 "request" / what=2 "response", snake_case BridgeRequest). Gates: external-control switch ON
 * and droidcommand-AI approved in Connected apps (package + cert). The reply sender uid must belong
 * to ai.droidcommand.app. DCA decides and asks for approval; VictorSMS never executes anything.
 */
class DcaClient(private val context: Context) {
    sealed class Result {
        data class Ok(val response: JSONObject) : Result()
        data class Blocked(val reason: String) : Result()
    }

    fun gate(): String? {
        if (!BridgeSettings(context).externalControlEnabled) return "External control is off (Settings)."
        val uid = runCatching { context.packageManager.getPackageUid(DcaWire.DCA_PACKAGE, 0) }.getOrNull()
            ?: return "droidcommand-AI is not installed."
        val ids = CallerVerifier.identify(context, uid).filter { it.packageName == DcaWire.DCA_PACKAGE }
        val app = ids.firstNotNullOfOrNull { PairingRegistry(context).find(it) }
        if (app == null) { ids.forEach { PairingRegistry(context).recordPending(it) }; return "Approve droidcommand-AI in Settings → Connected apps first." }
        if (!app.approved) return "droidcommand-AI is not approved in Connected apps."
        return null
    }

    /** Builds the frozen envelope parameters (nonce 128-bit hex, timestampMs, ttlMs 120000). */
    fun envelope(targetNodeRef: String, actionRef: String, args: JSONObject = JSONObject(), destructive: Boolean = false): JSONObject =
        JSONObject().put("targetNodeRef", targetNodeRef).put("actionRef", actionRef).put("args", args)
            .put("nonce", ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) })
            .put("timestampMs", System.currentTimeMillis()).put("ttlMs", 120_000L).put("destructive", destructive)

    /** Blocking request/response. Call off the main thread. */
    fun call(operation: String, targetNodeId: String, parameters: JSONObject, timeoutMs: Long = 10_000): Result {
        gate()?.let { return Result.Blocked(it) }
        val req = JSONObject().put("protocol_major", 1).put("request_id", UUID.randomUUID().toString())
            .put("target_node_id", targetNodeId).put("operation", operation).put("timeout_ms", timeoutMs).put("parameters", parameters)
        val dcaUid = runCatching { context.packageManager.getPackageUid(DcaWire.DCA_PACKAGE, 0) }.getOrNull()
            ?: return Result.Blocked("droidcommand-AI is not installed.")
        val thread = HandlerThread("dca-reply").apply { start() }
        val done = CountDownLatch(1)
        var response: JSONObject? = null
        val replyTo = Messenger(object : Handler(thread.looper) {
            override fun handleMessage(m: Message) {
                if (m.what != DcaWire.WHAT_RESPONSE || m.sendingUid != dcaUid) return // identity: OS-stamped sender
                response = runCatching { JSONObject(m.data?.getString(DcaWire.KEY_RESPONSE)?.take(1_048_576).orEmpty()) }.getOrNull()
                done.countDown()
            }
        })
        var bound = false
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                runCatching {
                    Messenger(binder).send(Message.obtain(null, DcaWire.WHAT_REQUEST).apply {
                        data = Bundle().apply { putString(DcaWire.KEY_REQUEST, req.toString()) }; this.replyTo = replyTo
                    })
                }.onFailure { done.countDown() }
            }
            override fun onServiceDisconnected(name: ComponentName?) { done.countDown() }
        }
        try {
            bound = context.bindService(Intent(DcaWire.DCA_ACTION).setPackage(DcaWire.DCA_PACKAGE), conn, Context.BIND_AUTO_CREATE)
            if (!bound) return Result.Blocked("droidcommand-AI did not accept the connection (its inbound bridge may not be live yet).")
            done.await(timeoutMs + 2_000, TimeUnit.MILLISECONDS)
            return response?.let { Result.Ok(it) } ?: Result.Blocked("No reply from droidcommand-AI (timeout).")
        } finally {
            if (bound) runCatching { context.unbindService(conn) }
            thread.quitSafely()
        }
    }
}
