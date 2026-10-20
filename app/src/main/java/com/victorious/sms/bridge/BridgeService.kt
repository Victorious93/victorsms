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

/**
 * EXTERNAL_NODE Messenger bridge. Request: Message.data "frame" = one JSON line with "op"/"id".
 * Reply: Message.data "frame" = {"id","ok","error"?}. Caller identity comes ONLY from
 * Message.sendingUid resolved through PackageManager, never from the payload.
 * Skeleton: authorization is real; op handlers are not wired yet, and sends are never executed
 * without on-device confirmation (confirmation UI not built yet).
 */
class BridgeService : Service() {
    private lateinit var authorizer: BridgeAuthorizer

    private val handler = object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            val frame = msg.data?.getString("frame").orEmpty()
            val op = Regex("\"op\"\\s*:\\s*\"([^\"]+)\"").find(frame)?.groupValues?.get(1).orEmpty()
            val id = Regex("\"id\"\\s*:\\s*\"([A-Za-z0-9_-]{0,64})\"").find(frame)?.groupValues?.get(1).orEmpty()
            val error = when (val d = authorizer.authorize(msg.sendingUid, op)) {
                is AuthDecision.Denied -> d.code
                is AuthDecision.Allowed -> if (d.needsUserConfirm) "confirmation_required" else "not_implemented"
            }
            val reply = """{"id":"$id","ok":false,"error":"$error"}"""
            val out = Message.obtain(null, msg.what).apply { data = Bundle().apply { putString("frame", reply) } }
            try { msg.replyTo?.send(out) } catch (_: RemoteException) {}
        }
    }

    override fun onCreate() { super.onCreate(); authorizer = BridgeAuthorizer(this) }
    override fun onBind(intent: Intent): IBinder = Messenger(handler).binder
}
