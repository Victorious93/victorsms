package com.victorious.sms.sms

import android.Manifest
import android.app.role.RoleManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Telephony
import android.telephony.SmsManager

data class SmsThread(val threadId: Long, val address: String, val snippet: String, val dateMs: Long)
data class SmsMessage(val id: Long, val address: String, val body: String, val dateMs: Long, val outgoing: Boolean)

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
            arrayOf(Telephony.Sms.THREAD_ID, Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE),
            null, null, "${Telephony.Sms.DATE} DESC",
        )?.use { c ->
            var n = 0
            while (c.moveToNext() && n++ < limit) {
                val tid = c.getLong(0)
                if (tid !in seen) seen[tid] = SmsThread(tid, c.getString(1).orEmpty(), c.getString(2).orEmpty(), c.getLong(3))
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

    /** User-initiated send. Writes the message to the Sent box (required for the default app). */
    fun send(address: String, body: String): Boolean {
        if (context.checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) return false
        return try {
            val sms = if (Build.VERSION.SDK_INT >= 31) context.getSystemService(SmsManager::class.java)
            else @Suppress("DEPRECATION") SmsManager.getDefault()
            sms.sendMultipartTextMessage(address, null, sms.divideMessage(body), null, null)
            if (isDefaultSmsApp()) {
                context.contentResolver.insert(Telephony.Sms.Sent.CONTENT_URI, ContentValues().apply {
                    put(Telephony.Sms.ADDRESS, address); put(Telephony.Sms.BODY, body)
                    put(Telephony.Sms.DATE, System.currentTimeMillis()); put(Telephony.Sms.READ, 1)
                })
            }
            true
        } catch (e: Exception) { false }
    }
}
