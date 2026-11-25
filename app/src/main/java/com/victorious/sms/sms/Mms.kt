package com.victorious.sms.sms

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.telephony.SmsManager
import androidx.core.content.FileProvider
import java.io.File

/** What an MMS WAP push (m-notification-ind) tells us. Parsed defensively: unknown header layouts yield null, never a guess. */
data class MmsNotification(val transactionId: String, val contentLocation: String)

object MmsPdu {
    private const val H_CONTENT_LOCATION = 0x83; private const val H_TRANSACTION_ID = 0x98; private const val H_MESSAGE_TYPE = 0x8C; private const val TYPE_NOTIFICATION_IND = 0x82

    /** The WSP push body starts after the WSP header; we locate the MMS PDU by its mandatory first header X-Mms-Message-Type = m-notification-ind. */
    fun parseNotification(push: ByteArray): MmsNotification? {
        var start = -1
        for (i in 0 until push.size - 1) if ((push[i].toInt() and 0xff) == H_MESSAGE_TYPE && (push[i + 1].toInt() and 0xff) == TYPE_NOTIFICATION_IND) { start = i + 2; break }
        if (start < 0) return null
        var tid: String? = null; var loc: String? = null
        var i = start
        fun cstr(from: Int): Pair<String, Int>? { var e = from; while (e < push.size && push[e].toInt() != 0) e++; if (e >= push.size) return null; return String(push, from, e - from, Charsets.ISO_8859_1) to e + 1 }
        while (i < push.size && (tid == null || loc == null)) {
            val h = push[i].toInt() and 0xff
            when (h) {
                H_TRANSACTION_ID -> { val (s, n) = cstr(i + 1) ?: return null; tid = s; i = n }
                H_CONTENT_LOCATION -> { val (s, n) = cstr(i + 1) ?: return null; loc = s; i = n }
                else -> i++
            }
        }
        if (tid == null || loc == null || !(loc.startsWith("http://") || loc.startsWith("https://")) || tid.length > 64) return null
        return MmsNotification(tid, loc)
    }
}

/** Receives the MMS WAP push (default-SMS role only) and starts the carrier download. Needs a SIM and an MMS-capable carrier to verify. */
class MmsWapPushReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != "android.provider.Telephony.WAP_PUSH_DELIVER") return
        val n = MmsPdu.parseNotification(intent.getByteArrayExtra("data") ?: return) ?: return
        MmsDownloader.start(context, n)
    }
}

object MmsDownloader {
    const val ACTION_DONE = "com.victorious.sms.MMS_DOWNLOADED"
    fun dir(c: Context) = File(c.filesDir, "mms").also { it.mkdirs() }

    fun start(ctx: Context, n: MmsNotification) {
        val safe = n.transactionId.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val out = File(ctx.cacheDir, "mms-dl").also { it.mkdirs() }.let { File(it, "$safe.dat") }.also { it.writeBytes(ByteArray(0)) }
        val uri: Uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", out)
        ctx.grantUriPermission("com.android.phone", uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        val done = PendingIntent.getBroadcast(ctx, safe.hashCode(), Intent(ACTION_DONE).setPackage(ctx.packageName).putExtra("tid", safe), PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val sms = if (Build.VERSION.SDK_INT >= 31) ctx.getSystemService(SmsManager::class.java) else @Suppress("DEPRECATION") SmsManager.getDefault()
        sms.downloadMultimediaMessage(ctx, n.contentLocation, uri, null, done)
    }
}

/** Download finished: decode the m-retrieve-conf and insert it into the system MMS store. If decoding or the insert fails, the raw PDU is
 *  kept under filesDir/mms for a later retry instead of being lost. Content is never logged. */
class MmsDownloadedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val tid = intent.getStringExtra("tid") ?: return
        val src = File(File(context.cacheDir, "mms-dl"), "$tid.dat")
        if (resultCode == android.app.Activity.RESULT_OK && src.length() in 1..MmsRetrieve.MAX_PDU_BYTES.toLong()) {
            val pdu = src.readBytes()
            val inserted = MmsRetrieve.parse(pdu)?.let { MmsStore.insertInbox(context, it) } != null
            if (!inserted) File(MmsDownloader.dir(context), "$tid.pdu").writeBytes(pdu)
        }
        src.delete()
    }
}
