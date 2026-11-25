package com.victorious.sms.sms

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.Telephony

/**
 * Writes a decoded MMS into the system provider (Telephony.Mms inbox + parts + addresses) so it shows in every SMS app.
 * Only the default SMS app may write here; the provider refuses anyone else. Returns the new message id or null. Never logs content.
 */
object MmsStore {
    private const val PDU_FROM = 137; private const val PDU_TO = 151; private const val UTF8 = 106

    fun insertInbox(ctx: Context, m: MmsMessage, nowMs: Long = System.currentTimeMillis()): Long? {
        val cr = ctx.contentResolver
        val v = ContentValues().apply {
            put(Telephony.Mms.MESSAGE_BOX, Telephony.Mms.MESSAGE_BOX_INBOX)
            put(Telephony.Mms.MESSAGE_TYPE, 132)                       // m-retrieve-conf
            put(Telephony.Mms.MMS_VERSION, 0x12)
            put(Telephony.Mms.DATE, m.dateSec ?: (nowMs / 1000))
            put(Telephony.Mms.DATE_SENT, m.dateSec ?: 0L)
            put(Telephony.Mms.READ, 0); put(Telephony.Mms.SEEN, 0)
            put(Telephony.Mms.CONTENT_TYPE, m.contentType)
            m.subject?.let { put(Telephony.Mms.SUBJECT, it); put(Telephony.Mms.SUBJECT_CHARSET, UTF8) }
            m.messageId?.let { put(Telephony.Mms.MESSAGE_ID, it) }
            m.transactionId?.let { put(Telephony.Mms.TRANSACTION_ID, it) }
            m.from?.let { put(Telephony.Mms.THREAD_ID, Telephony.Threads.getOrCreateThreadId(ctx, setOf(it) + m.to.toSet())) }
        }
        val msgUri = runCatching { cr.insert(Telephony.Mms.Inbox.CONTENT_URI, v) }.getOrNull() ?: return null
        val id = msgUri.lastPathSegment?.toLongOrNull() ?: return null
        val partUri = Uri.parse("content://mms/$id/part")
        m.parts.forEachIndexed { i, p ->
            val pv = ContentValues().apply {
                put(Telephony.Mms.Part.MSG_ID, id); put(Telephony.Mms.Part.SEQ, i)
                put(Telephony.Mms.Part.CONTENT_TYPE, p.contentType)
                p.name?.let { put(Telephony.Mms.Part.NAME, it); put(Telephony.Mms.Part.FILENAME, it) }
                p.contentLocation?.let { put(Telephony.Mms.Part.CONTENT_LOCATION, it) }
                p.contentId?.let { put(Telephony.Mms.Part.CONTENT_ID, it) }
                p.charset?.let { put(Telephony.Mms.Part.CHARSET, it) }
                p.text()?.let { put(Telephony.Mms.Part.TEXT, it) }
            }
            val u = runCatching { cr.insert(partUri, pv) }.getOrNull() ?: return@forEachIndexed
            if (!p.isText && p.contentType != "application/smil") runCatching { cr.openOutputStream(u)?.use { it.write(p.data) } }
        }
        val addrUri = Uri.parse("content://mms/$id/addr")
        fun addr(a: String, type: Int) = runCatching { cr.insert(addrUri, ContentValues().apply { put(Telephony.Mms.Addr.ADDRESS, a); put(Telephony.Mms.Addr.TYPE, type); put(Telephony.Mms.Addr.CHARSET, UTF8) }) }
        m.from?.let { addr(it, PDU_FROM) }
        m.to.forEach { addr(it, PDU_TO) }
        return id
    }
}
