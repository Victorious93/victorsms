package com.victorious.sms.relay

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.victorious.sms.bridge.ReplayGuard
import java.util.concurrent.ConcurrentHashMap

enum class CallState { PENDING, DIALING, DENIED, EXPIRED }

data class PendingCall(val requestId: String, val peerName: String, val peerFp: String, val to: String,
                       val expiresAtMs: Long, @Volatile var state: CallState = CallState.PENDING)

/**
 * relay.call.place: every call needs an on-device Approve. On approve we open the system dialer
 * pre-filled (ACTION_DIAL); audio bridging to the requesting phone is a documented stub.
 */
object RemoteCallQueue {
    private const val CHANNEL = "remote_call_approval"
    private val calls = ConcurrentHashMap<String, PendingCall>()
    private val PHONE = Regex("^\\+?[0-9]{3,20}$")

    @Synchronized
    fun submit(context: Context, peer: PairedPhone, requestId: String, to: String,
               nonce: String, timestampMs: Long, ttlMs: Long?): String? {
        if (requestId.isBlank() || requestId.length > 64 || calls.containsKey(requestId)) return "invalid_parameters"
        if (!PHONE.matches(to.replace(" ", "").replace("-", ""))) return "invalid_parameters"
        ReplayGuard.check(nonce, timestampMs, ttlMs)?.let { return it }
        val c = PendingCall(requestId, peer.name, peer.fingerprint, to, timestampMs + ReplayGuard.effectiveTtl(ttlMs))
        calls[requestId] = c
        notify(context, c)
        return null
    }

    fun get(id: String) = calls[id]

    fun status(peerFp: String, id: String): CallState? {
        val c = calls[id] ?: return null
        if (c.peerFp != peerFp) return null
        if (c.state == CallState.PENDING && System.currentTimeMillis() > c.expiresAtMs) c.state = CallState.EXPIRED
        return c.state
    }

    fun approve(context: Context, id: String) {
        val c = calls[id] ?: return
        synchronized(c) {
            if (c.state != CallState.PENDING) return
            if (System.currentTimeMillis() > c.expiresAtMs) { c.state = CallState.EXPIRED; return }
            c.state = CallState.DIALING
        }
        context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(c.to))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        NotificationManagerCompat.from(context).cancel(id.hashCode())
    }

    fun deny(context: Context, id: String) {
        calls[id]?.let { synchronized(it) { if (it.state == CallState.PENDING) it.state = CallState.DENIED } }
        NotificationManagerCompat.from(context).cancel(id.hashCode())
    }

    private fun notify(context: Context, c: PendingCall) {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel(CHANNEL, "Relay call approvals", NotificationManager.IMPORTANCE_HIGH))
        val pi = PendingIntent.getActivity(context, c.requestId.hashCode(),
            Intent(context, ApproveCallActivity::class.java).putExtra(ApproveCallActivity.EXTRA_ID, c.requestId).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(context, CHANNEL).setSmallIcon(android.R.drawable.sym_action_call)
            .setContentTitle("${c.peerName} wants this phone to call").setContentText(c.to)
            .setPriority(NotificationCompat.PRIORITY_HIGH).setContentIntent(pi).setAutoCancel(true)
            .setTimeoutAfter((c.expiresAtMs - System.currentTimeMillis()).coerceAtLeast(1_000L)).build()
        if (Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
            NotificationManagerCompat.from(context).notify(c.requestId.hashCode(), n)
    }
}
