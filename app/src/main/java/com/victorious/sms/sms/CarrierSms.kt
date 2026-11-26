package com.victorious.sms.sms

import android.Manifest
import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.os.Build
import android.provider.Telephony
import android.telephony.SmsManager
import android.telephony.SmsMessage
import com.victorious.comms.Author
import com.victorious.sms.archive.AndroidSqlDb
import com.victorious.sms.archive.LiveArchive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.UUID
import java.util.concurrent.Executors

sealed interface SmsSubmission {
    data class Tracked(val receipt: SmsSendReceipt) : SmsSubmission
    data class Refused(val reason: String) : SmsSubmission
}

/** System sends and callbacks use the same on-device journal as the compose UI. */
object CarrierSms {
    internal const val ACTION_SENT = "com.victorious.sms.CARRIER_SMS_SENT"
    internal const val ACTION_DELIVERY = "com.victorious.sms.CARRIER_SMS_DELIVERY"
    private val addressPattern = Regex("[+]?[0-9]{1,20}")
    private const val DB_NAME = "carrier_sms.db"
    private const val MAX_BODY = 100_000
    private val changes = MutableStateFlow(0L)
    val revisions = changes.asStateFlow()
    @Volatile private var journal: SmsSendStore? = null

    fun submit(
        context: Context,
        address: String,
        body: String,
        author: Author,
        requestedSim: Int? = null,
        requestKey: String = UUID.randomUUID().toString(),
    ): SmsSubmission {
        val app = context.applicationContext
        val to = normalize(address)
        if (!addressPattern.matches(to)) return SmsSubmission.Refused("Enter a valid SMS number.")
        if (body.isBlank() || body.length > MAX_BODY) return SmsSubmission.Refused("Check the length of the text message.")
        val initial = try {
            store(app).byKey(requestKey)
        } catch (_: Exception) {
            return SmsSubmission.Refused("VictorSMS could not open the SMS activity log.")
        }
        if (initial != null) {
            return if (initial.address == to && initial.body == body) SmsSubmission.Tracked(initial)
            else SmsSubmission.Refused("This send request already belongs to another message.")
        }
        if (app.checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            return SmsSubmission.Refused("Allow SMS sending in Android settings.")
        }
        val sim = try {
            SmsSubscriptions(app).resolve(requestedSim)
        } catch (e: IllegalArgumentException) {
            return SmsSubmission.Refused(e.message ?: "Choose an active SIM.")
        }
        val manager = manager(app, sim.id) ?: return SmsSubmission.Refused("Android SMS is unavailable on this device.")
        val parts = runCatching { manager.divideMessage(body) }.getOrNull()?.takeIf { it.isNotEmpty() }
            ?: return SmsSubmission.Refused("Android could not prepare this text.")
        val db = store(app)
        val begin = try {
            db.begin(requestKey, to, body, sim, author, parts.size, System.currentTimeMillis())
        } catch (_: Exception) {
            return SmsSubmission.Refused("VictorSMS could not save this send request.")
        }
        val prepared = checkNotNull(db.byId(begin.id))
        if (!begin.created) {
            return if (prepared.address == to && prepared.body == body) SmsSubmission.Tracked(prepared)
            else SmsSubmission.Refused("This send request already belongs to another message.")
        }
        var snapshot = prepared
        try {
            insertOutboxRow(app, prepared)?.let {
                db.attachProviderRow(begin.id, it)
                snapshot = prepared.copy(providerId = it)
            }
            val sent = ArrayList(parts.indices.map { part -> callback(app, begin.id, part, ACTION_SENT) })
            val delivery = ArrayList(parts.indices.map { part -> callback(app, begin.id, part, ACTION_DELIVERY) })
            manager.sendMultipartTextMessage(to, null, parts, sent, delivery)
        } catch (_: Exception) {
            // An exception around dispatch cannot prove whether Android got the request.
            runCatching { db.markDispatchUnknown(begin.id) }
            snapshot = snapshot.copy(uncertainDispatch = true)
        }
        changes.update { it + 1 }
        return SmsSubmission.Tracked(snapshot)
    }

    fun recent(context: Context, address: String? = null): List<SmsSendReceipt> =
        store(context).recent(address?.let(::normalize))

    fun receipt(context: Context, requestKey: String): SmsSendReceipt? = store(context).byKey(requestKey)

    internal fun normalize(address: String): String =
        SmsSubscriptions.CARRIER_ADDRESS_FORMAT.replace(address.trim(), "")

    private fun store(context: Context): SmsSendStore = journal ?: synchronized(this) {
        journal ?: run {
            val file = context.applicationContext.getDatabasePath(DB_NAME)
            file.parentFile?.mkdirs()
            val db = SQLiteDatabase.openOrCreateDatabase(file, null)
            db.enableWriteAheadLogging()
            SmsSendStore(AndroidSqlDb(db)).also { journal = it }
        }
    }

    @Suppress("DEPRECATION")
    private fun manager(context: Context, id: Int): SmsManager? = runCatching {
        if (Build.VERSION.SDK_INT >= 31) context.getSystemService(SmsManager::class.java)?.createForSubscriptionId(id)
        else SmsManager.getSmsManagerForSubscriptionId(id)
    }.getOrNull()

    private fun callback(context: Context, id: Long, part: Int, action: String): PendingIntent {
        val data = Uri.Builder().scheme("victorsms").authority("carrier-sms").appendPath(id.toString())
            .appendPath(part.toString()).build()
        val intent = Intent(context, SmsCallbackReceiver::class.java).setAction(action).setData(data)
        val flags = if (action == ACTION_SENT) PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_ONE_SHOT
        else PendingIntent.FLAG_MUTABLE // Android fills the status-report PDU and format.
        return PendingIntent.getBroadcast(context, 0, intent, flags)
    }

    private fun insertOutboxRow(context: Context, receipt: SmsSendReceipt): Long? = runCatching {
        if (Telephony.Sms.getDefaultSmsPackage(context) != context.packageName) return null
        context.contentResolver.insert(Telephony.Sms.Outbox.CONTENT_URI, ContentValues().apply {
            put(Telephony.Sms.ADDRESS, receipt.address)
            put(Telephony.Sms.BODY, receipt.body)
            put(Telephony.Sms.DATE, receipt.createdAt)
            put(Telephony.Sms.READ, 1)
            put(Telephony.Sms.SUBSCRIPTION_ID, receipt.sim.id)
            put(Telephony.Sms.STATUS, Telephony.Sms.STATUS_PENDING)
        })?.let(ContentUris::parseId)
    }.getOrNull()

    internal fun handle(context: Context, intent: Intent, result: Int) {
        val data = intent.data?.takeIf { it.scheme == "victorsms" && it.authority == "carrier-sms" } ?: return
        val path = data.pathSegments
        if (path.size != 2) return
        val id = path[0].toLongOrNull()?.takeIf { it > 0 } ?: return
        val part = path[1].toIntOrNull()?.takeIf { it >= 0 } ?: return
        val updated = when (intent.action) {
            ACTION_SENT -> store(context).sent(id, part, result)
            ACTION_DELIVERY -> {
                val status = readDelivery(intent, result) ?: return
                store(context).delivered(id, part, status)
            }
            else -> return
        } ?: return
        val receipt = updated.current
        updateProviderRow(context, receipt)
        if (receipt.accepted == receipt.parts && updated.previous.accepted < receipt.parts) {
            LiveArchive.recordOutgoing(context, receipt.address, receipt.body, receipt.createdAt, receipt.author)
        }
        changes.update { it + 1 }
    }

    private fun updateProviderRow(context: Context, receipt: SmsSendReceipt) = runCatching {
        val providerId = receipt.providerId ?: return@runCatching
        if (Telephony.Sms.getDefaultSmsPackage(context) != context.packageName) return@runCatching
        val type = when (receipt.state()) {
            SmsSubmissionState.SENT -> Telephony.Sms.MESSAGE_TYPE_SENT
            SmsSubmissionState.FAILED, SmsSubmissionState.PARTIAL -> Telephony.Sms.MESSAGE_TYPE_FAILED
            else -> return@runCatching
        }
        val status = when {
            type == Telephony.Sms.MESSAGE_TYPE_FAILED || receipt.delivery == SmsDelivery.FAILED -> Telephony.Sms.STATUS_FAILED
            receipt.delivery == SmsDelivery.DELIVERED -> Telephony.Sms.STATUS_COMPLETE
            else -> Telephony.Sms.STATUS_PENDING
        }
        val uri = ContentUris.withAppendedId(Telephony.Sms.CONTENT_URI, providerId)
        context.contentResolver.update(uri, ContentValues().apply {
            put(Telephony.Sms.TYPE, type)
            put(Telephony.Sms.STATUS, status)
        }, "${Telephony.Sms.DATE}=? AND ${Telephony.Sms.ADDRESS}=? AND ${Telephony.Sms.BODY}=?",
            arrayOf(receipt.createdAt.toString(), receipt.address, receipt.body))
    }

    private fun readDelivery(intent: Intent, result: Int): SmsDelivery? {
        if (result != Activity.RESULT_OK) return null
        val pdu = intent.getByteArrayExtra("pdu")?.takeIf { it.size in 1..512 } ?: return null
        val format = intent.getStringExtra("format")?.takeIf { it == "3gpp" || it == "3gpp2" } ?: return null
        val message = runCatching { SmsMessage.createFromPdu(pdu, format) }.getOrNull() ?: return null
        return if (message.isStatusReportMessage) deliveryStatus(format, message.status) else null
    }

    internal fun deliveryStatus(format: String, status: Int): SmsDelivery? = when {
        format == "3gpp" && status == 0 -> SmsDelivery.DELIVERED
        format == "3gpp2" && status == (2 shl 16) -> SmsDelivery.DELIVERED
        format == "3gpp" && status in 0x40..0x7f -> SmsDelivery.FAILED
        format == "3gpp2" && (status ushr 24) and 3 == 3 -> SmsDelivery.FAILED
        else -> null
    }
}

class SmsCallbackReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val result = resultCode
        executor.execute {
            try {
                CarrierSms.handle(context.applicationContext, intent, result)
            } catch (_: Exception) {
                // No callback failure may cause a retransmission.
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private val executor = Executors.newSingleThreadExecutor()
    }
}
