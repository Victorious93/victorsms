package com.victorious.sms.sms

import android.app.Activity
import android.telephony.SmsManager
import com.victorious.comms.Author
import com.victorious.comms.Row
import com.victorious.comms.SqlDb
import com.victorious.comms.long
import com.victorious.comms.one
import com.victorious.comms.str

enum class SmsSubmissionState { SENDING, SENT, FAILED, PARTIAL, UNKNOWN }
enum class SmsDelivery { DELIVERED, FAILED, UNCONFIRMED }

data class SmsSendReceipt(
    val id: Long,
    val address: String,
    val body: String,
    val createdAt: Long,
    val sim: SmsSim,
    val providerId: Long?,
    val author: Author,
    val parts: Int,
    val accepted: Int,
    val rejected: Int,
    val delivered: Int,
    val deliveryFailed: Int,
    val errorCode: Int?,
    val uncertainDispatch: Boolean,
) {
    fun state(now: Long = System.currentTimeMillis()): SmsSubmissionState = when {
        accepted == parts -> SmsSubmissionState.SENT
        rejected == parts -> SmsSubmissionState.FAILED
        accepted + rejected == parts -> SmsSubmissionState.PARTIAL
        uncertainDispatch || now - createdAt >= CALLBACK_WAIT_MS -> SmsSubmissionState.UNKNOWN
        else -> SmsSubmissionState.SENDING
    }

    val delivery: SmsDelivery
        get() = when {
            deliveryFailed > 0 -> SmsDelivery.FAILED
            delivered == parts -> SmsDelivery.DELIVERED
            else -> SmsDelivery.UNCONFIRMED
        }

    fun statusLabel(now: Long = System.currentTimeMillis()): String = when (state(now)) {
        SmsSubmissionState.SENDING -> "Sending"
        SmsSubmissionState.SENT -> when (delivery) {
            SmsDelivery.DELIVERED -> "Delivered"
            SmsDelivery.FAILED -> "Sent · Delivery failed"
            SmsDelivery.UNCONFIRMED -> "Sent · Delivery unconfirmed"
        }
        SmsSubmissionState.PARTIAL -> "Partially sent ($accepted of $parts parts)"
        SmsSubmissionState.UNKNOWN -> "Outcome unknown · Check before resending"
        SmsSubmissionState.FAILED -> when (errorCode) {
            SmsManager.RESULT_ERROR_NO_SERVICE -> "Failed · No mobile service"
            SmsManager.RESULT_ERROR_RADIO_OFF -> "Failed · Mobile radio is off"
            SmsManager.RESULT_ERROR_LIMIT_EXCEEDED -> "Failed · Android send limit reached"
            SmsManager.RESULT_ERROR_SHORT_CODE_NOT_ALLOWED, SmsManager.RESULT_ERROR_SHORT_CODE_NEVER_ALLOWED ->
                "Failed · Short-code SMS blocked"
            else -> "Failed to send"
        }
    }

    companion object {
        const val CALLBACK_WAIT_MS = 120_000L
    }
}

/** Keeps one durable operation per send request and one result per modem callback. */
internal class SmsSendStore(private val db: SqlDb) {
    init {
        db.tx {
            db.exec(
                """CREATE TABLE IF NOT EXISTS sms_sends (
                    id INTEGER PRIMARY KEY AUTOINCREMENT, request_key TEXT NOT NULL UNIQUE,
                    address TEXT NOT NULL, body TEXT NOT NULL, created_at INTEGER NOT NULL,
                    sim_id INTEGER NOT NULL, sim_label TEXT NOT NULL, author TEXT NOT NULL,
                    provider_id INTEGER, uncertain_dispatch INTEGER NOT NULL DEFAULT 0
                )""",
            )
            db.exec(
                """CREATE TABLE IF NOT EXISTS sms_parts (
                    send_id INTEGER NOT NULL REFERENCES sms_sends(id), part INTEGER NOT NULL,
                    sent_result INTEGER, delivery_result TEXT,
                    PRIMARY KEY (send_id, part)
                )""",
            )
            db.exec("CREATE INDEX IF NOT EXISTS sms_sends_address ON sms_sends(address, id)")
        }
    }

    data class Begin(val id: Long, val created: Boolean)
    data class Change(val previous: SmsSendReceipt, val current: SmsSendReceipt)

    fun begin(key: String, address: String, body: String, sim: SmsSim, author: Author, parts: Int, now: Long): Begin = db.tx {
        require(parts > 0)
        val existing = db.one("SELECT id FROM sms_sends WHERE request_key=?", key)
        if (existing != null) return@tx Begin(existing.long("id"), false)
        val id = db.insert(
            "INSERT INTO sms_sends(request_key,address,body,sim_id,sim_label,author,created_at) VALUES(?,?,?,?,?,?,?)",
            key, address, body, sim.id, sim.label, author.name, now,
        )
        repeat(parts) { part -> db.insert("INSERT INTO sms_parts(send_id,part) VALUES(?,?)", id, part) }
        Begin(id, true)
    }

    fun byKey(key: String): SmsSendReceipt? = db.one("$SELECT WHERE s.request_key=? GROUP BY s.id", key)?.let(::decode)

    fun byId(id: Long): SmsSendReceipt? = db.one("$SELECT WHERE s.id=? GROUP BY s.id", id)?.let(::decode)

    fun recent(address: String? = null, limit: Int = 100): List<SmsSendReceipt> =
        (if (address == null) db.rows("$SELECT GROUP BY s.id ORDER BY s.id DESC LIMIT ?", limit)
        else db.rows("$SELECT WHERE s.address=? GROUP BY s.id ORDER BY s.id DESC LIMIT ?", address, limit)).map(::decode)

    fun attachProviderRow(id: Long, providerId: Long) {
        db.update("UPDATE sms_sends SET provider_id=? WHERE id=?", providerId, id)
    }

    fun markDispatchUnknown(id: Long) {
        db.update("UPDATE sms_sends SET uncertain_dispatch=1 WHERE id=?", id)
    }

    fun sent(id: Long, part: Int, result: Int): Change? = update(id) {
        db.update("UPDATE sms_parts SET sent_result=? WHERE send_id=? AND part=? AND sent_result IS NULL", result, id, part)
    }

    fun delivered(id: Long, part: Int, result: SmsDelivery): Change? {
        if (result == SmsDelivery.UNCONFIRMED) return null
        return update(id) {
            db.update(
                "UPDATE sms_parts SET delivery_result=? WHERE send_id=? AND part=? AND delivery_result IS NULL",
                result.name, id, part,
            )
        }
    }

    private inline fun update(id: Long, crossinline write: () -> Int): Change? = db.tx {
        val previous = byId(id) ?: return@tx null
        if (write() != 1) return@tx null
        Change(previous, checkNotNull(byId(id)))
    }

    private fun decode(row: Row) = SmsSendReceipt(
        id = row.long("id"), address = row.str("address"), body = row.str("body"), createdAt = row.long("created_at"),
        sim = SmsSim(row.long("sim_id").toInt(), row.str("sim_label")), providerId = row["provider_id"]?.toLongOrNull(),
        author = Author.valueOf(row.str("author")), parts = row.long("parts").toInt(), accepted = row.long("accepted").toInt(),
        rejected = row.long("rejected").toInt(), delivered = row.long("delivered").toInt(),
        deliveryFailed = row.long("delivery_failed").toInt(), errorCode = row["error_code"]?.toIntOrNull(),
        uncertainDispatch = row.long("uncertain_dispatch") != 0L,
    )

    companion object {
        private val SELECT = """SELECT s.*, COUNT(p.part) AS parts,
            SUM(CASE WHEN p.sent_result=${Activity.RESULT_OK} THEN 1 ELSE 0 END) AS accepted,
            SUM(CASE WHEN p.sent_result!=${Activity.RESULT_OK} THEN 1 ELSE 0 END) AS rejected,
            SUM(CASE WHEN p.delivery_result='DELIVERED' THEN 1 ELSE 0 END) AS delivered,
            SUM(CASE WHEN p.delivery_result='FAILED' THEN 1 ELSE 0 END) AS delivery_failed,
            MIN(CASE WHEN p.sent_result!=${Activity.RESULT_OK} THEN p.sent_result ELSE NULL END) AS error_code
            FROM sms_sends s JOIN sms_parts p ON p.send_id=s.id"""
    }
}
