package com.victorious.sms.sms

import android.Manifest
import android.app.role.RoleManager
import android.database.ContentObserver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Telephony
import com.victorious.comms.Author
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import java.util.UUID

data class SmsThread(val threadId: Long, val address: String, val snippet: String, val dateMs: Long, val unread: Boolean = false)
data class SmsMessage(
    val id: Long, val address: String, val body: String, val dateMs: Long, val outgoing: Boolean,
    val receipt: SmsSendReceipt? = null,
)

/** Reads SMS threads from the Telephony provider and sends via SmsManager (user-initiated only). */
class SmsRepository(private val context: Context) {

    fun isDefaultSmsApp(): Boolean = Telephony.Sms.getDefaultSmsPackage(context) == context.packageName

    fun canRead(): Boolean = context.checkSelfPermission(Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED

    /** Intent that asks the user to make VictorSMS the default SMS app. */
    fun requestDefaultRoleIntent(): Intent? =
        if (Build.VERSION.SDK_INT >= 29) {
            val rm = context.getSystemService(RoleManager::class.java)
            if (rm.isRoleAvailable(RoleManager.ROLE_SMS) && !rm.isRoleHeld(RoleManager.ROLE_SMS))
                rm.createRequestRoleIntent(RoleManager.ROLE_SMS) else null
        } else {
            @Suppress("DEPRECATION")
            Intent(Telephony.Sms.Intents.ACTION_CHANGE_DEFAULT)
                .putExtra(Telephony.Sms.Intents.EXTRA_PACKAGE_NAME, context.packageName)
        }

    /** Latest message per thread, newest first (scans up to [limit] rows). */
    fun threads(limit: Int = 2000): List<SmsThread> {
        if (!canRead()) return emptyList()
        val seen = LinkedHashMap<Long, SmsThread>()
        context.contentResolver.query(
            Telephony.Sms.CONTENT_URI,
            arrayOf(Telephony.Sms.THREAD_ID, Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.READ, Telephony.Sms.TYPE),
            null, null, "${Telephony.Sms.DATE} DESC",
        )?.use { c ->
            var n = 0
            while (c.moveToNext() && n++ < limit) {
                val tid = c.getLong(0)
                if (tid !in seen) seen[tid] = SmsThread(tid, c.getString(1).orEmpty(), c.getString(2).orEmpty(), c.getLong(3), unread = c.getInt(4) == 0 && c.getInt(5) == Telephony.Sms.MESSAGE_TYPE_INBOX)
            }
        }
        return seen.values.toList()
    }

    fun messages(threadId: Long): List<SmsMessage> {
        if (!canRead()) return emptyList()
        val out = mutableListOf<SmsMessage>()
        context.contentResolver.query(
            Telephony.Sms.CONTENT_URI,
            arrayOf(Telephony.Sms._ID, Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.TYPE),
            "${Telephony.Sms.THREAD_ID} = ?", arrayOf(threadId.toString()), "${Telephony.Sms.DATE} ASC",
        )?.use { c ->
            while (c.moveToNext()) {
                out += SmsMessage(c.getLong(0), c.getString(1).orEmpty(), c.getString(2).orEmpty(), c.getLong(3),
                    c.getInt(4) != Telephony.Sms.MESSAGE_TYPE_INBOX)
            }
        }
        return out
    }

    /** Refreshes a conversation when Android's inbox or a tracked carrier send changes. */
    fun observeMessages(threadId: Long, address: String): Flow<List<SmsMessage>> =
        smsChanges().combine(CarrierSms.revisions) { _, _ -> conversationMessages(threadId, address) }.flowOn(Dispatchers.IO)

    private fun smsChanges(): Flow<Unit> = callbackFlow {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { trySend(Unit) }
        }
        val registered = runCatching {
            context.contentResolver.registerContentObserver(Telephony.Sms.CONTENT_URI, true, observer)
        }.isSuccess
        trySend(Unit)
        awaitClose { if (registered) context.contentResolver.unregisterContentObserver(observer) }
    }.conflate()

    private fun conversationMessages(threadId: Long, address: String): List<SmsMessage> {
        if (address.isBlank()) return emptyList()
        val resolvedThread = if (threadId >= 0) threadId else findThread(address)
        val messages = resolvedThread?.let { runCatching { messages(it) }.getOrDefault(emptyList()) }.orEmpty()
        val tracked = runCatching { CarrierSms.recent(context, address) }.getOrDefault(emptyList())
        val matches = tracked.mapNotNull { receipt -> receipt.providerId?.let { it to receipt } }.toMap()
        val result = messages.map { m ->
            val receipt = matches[m.id]?.takeIf { m.outgoing && m.dateMs == it.createdAt && m.body == it.body }
            m.copy(receipt = receipt)
        }
        val localOnly = tracked.filter { receipt ->
            receipt.providerId == null && messages.none { m ->
                m.outgoing && m.body == receipt.body && m.dateMs in receipt.createdAt..(receipt.createdAt + 15_000)
            }
        }.map { SmsMessage(-it.id, it.address, it.body, it.createdAt, true, it) }
        return (result + localOnly).sortedBy { it.dateMs }
    }

    private fun findThread(address: String): Long? {
        if (!canRead()) return null
        return runCatching {
            val addr = Telephony.Sms.ADDRESS
            context.contentResolver.query(
                Telephony.Sms.CONTENT_URI, arrayOf(Telephony.Sms.THREAD_ID), "$addr=? OR $addr=?",
                arrayOf(address, CarrierSms.normalize(address)), "${Telephony.Sms.DATE} DESC",
            )?.use { if (it.moveToFirst()) it.getLong(0) else null }
        }.getOrNull()
    }

    fun send(
        address: String, body: String, author: Author, requestedSim: Int? = null,
        requestKey: String = UUID.randomUUID().toString(),
    ): SmsSubmission = CarrierSms.submit(context, address, body, author, requestedSim, requestKey)
}
