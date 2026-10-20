package com.victorious.sms.bridge

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SmsManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import java.util.concurrent.ConcurrentHashMap

object SmsSendCapability {
    const val OP = "sms.send"
    const val STATUS_OP = "sms.send.status"
    val descriptor = CapabilityDescriptor(OP, kind = "action", remoteAllowed = true, requiresConfirm = true)
    val statusDescriptor = CapabilityDescriptor(STATUS_OP, kind = "read", remoteAllowed = true, requiresConfirm = false)
}

enum class SendState { PENDING, SENT, DENIED, EXPIRED, FAILED }

data class PendingSend(
    val requestId: String,
    val caller: CallerIdentity,
    val to: String,
    val body: String,
    val expiresAtMs: Long,
    @Volatile var state: SendState = SendState.PENDING,
)

/**
 * Remote sms.send requests. Each one waits for an on-device Approve; nothing is ever auto-sent.
 * Replayed nonces are rejected. Requests expire after ttlMs.
 */
object RemoteSmsQueue {
    const val MAX_TTL_MS = ReplayGuard.MAX_TTL_MS
    private const val CHANNEL = "remote_sms_approval"
    private val requests = ConcurrentHashMap<String, PendingSend>()
    private val PHONE = Regex("^\\+?[0-9]{3,20}$")

    sealed class Result {
        data class Accepted(val requestId: String) : Result()
        data class Rejected(val code: String) : Result()
    }

    @Synchronized
    fun submit(
        context: Context, caller: CallerIdentity, requestId: String, to: String, body: String,
        nonce: String, timestampMs: Long, ttlMs: Long?, nowMs: Long = System.currentTimeMillis(),
    ): Result {
        sweep(nowMs)
        if (requestId.isBlank() || requestId.length > 64) return Result.Rejected("invalid_parameters")
        if (!PHONE.matches(to.replace(" ", "").replace("-", ""))) return Result.Rejected("invalid_parameters")
        if (body.isEmpty() || body.length > 1600) return Result.Rejected("invalid_parameters")
        if (requests.containsKey(requestId)) return Result.Rejected("duplicate_request")
        ReplayGuard.check(nonce, timestampMs, ttlMs, nowMs)?.let { return Result.Rejected(it) }
        val ttl = ReplayGuard.effectiveTtl(ttlMs)
        val p = PendingSend(requestId, caller, to, body, timestampMs + ttl)
        requests[requestId] = p
        notifyApproval(context, p)
        return Result.Accepted(requestId)
    }

    /** Status for the caller that created the request only. */
    fun status(caller: CallerIdentity, requestId: String, nowMs: Long = System.currentTimeMillis()): SendState? {
        sweep(nowMs)
        val p = requests[requestId] ?: return null
        return if (p.caller == caller) p.state else null
    }

    fun get(requestId: String): PendingSend? = requests[requestId]

    /** Called only from the on-device approval UI. */
    fun approve(context: Context, requestId: String, nowMs: Long = System.currentTimeMillis()): SendState {
        val p = requests[requestId] ?: return SendState.EXPIRED
        synchronized(p) {
            if (p.state != SendState.PENDING) return p.state
            if (nowMs > p.expiresAtMs) { p.state = SendState.EXPIRED; return p.state }
            p.state = try {
                if (context.checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
                    SendState.FAILED
                } else {
                    val sms = if (Build.VERSION.SDK_INT >= 31) context.getSystemService(SmsManager::class.java)
                    else @Suppress("DEPRECATION") SmsManager.getDefault()
                    val parts = sms.divideMessage(p.body)
                    sms.sendMultipartTextMessage(p.to, null, parts, null, null)
                    SendState.SENT
                }
            } catch (e: Exception) { SendState.FAILED }
        }
        cancelNotification(context, requestId)
        return p.state
    }

    fun deny(context: Context, requestId: String) {
        requests[requestId]?.let { synchronized(it) { if (it.state == SendState.PENDING) it.state = SendState.DENIED } }
        cancelNotification(context, requestId)
    }

    private fun sweep(nowMs: Long) {
        requests.values.forEach { if (it.state == SendState.PENDING && nowMs > it.expiresAtMs) it.state = SendState.EXPIRED }
        // Keep finished requests for a while so callers can poll the outcome.
        requests.entries.removeIf { nowMs > it.value.expiresAtMs + MAX_TTL_MS }
    }

    private fun notifyApproval(context: Context, p: PendingSend) {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Remote SMS approvals", NotificationManager.IMPORTANCE_HIGH),
            )
        }
        val intent = Intent(context, ApproveSendActivity::class.java)
            .putExtra(ApproveSendActivity.EXTRA_REQUEST_ID, p.requestId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pi = PendingIntent.getActivity(
            context, p.requestId.hashCode(), intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentTitle("${p.caller.packageName} wants to send an SMS")
            .setContentText("To ${p.to}: ${p.body.take(80)}")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setTimeoutAfter((p.expiresAtMs - System.currentTimeMillis()).coerceAtLeast(1_000L))
            .build()
        if (Build.VERSION.SDK_INT < 33 ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        ) {
            NotificationManagerCompat.from(context).notify(p.requestId.hashCode(), n)
        }
    }

    private fun cancelNotification(context: Context, requestId: String) =
        NotificationManagerCompat.from(context).cancel(requestId.hashCode())
}
