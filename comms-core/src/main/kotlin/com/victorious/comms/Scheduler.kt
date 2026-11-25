package com.victorious.comms

import java.security.SecureRandom

enum class SchedState {
    PENDING, SENDING, SENT, FAILED, UNKNOWN, CANCELLED, MISSED;

    val terminal get() = this != PENDING && this != SENDING
}

data class ScheduledMessage(
    val uid: String, val conversation: Long, val body: String, val sendAt: Long,
    val state: SchedState, val detail: String, val created: Long, val updated: Long,
)

/**
 * Owner-written messages that go out later (Victorious Framework). This is not automation: every body is typed by the
 * owner, so [AutomationGate] and [Outbox] are not involved and the bot rules do not apply. What it shares with them is
 * the discipline about *uncertain* sends:
 *  - [claimDue] runs in one transaction and flips PENDING to SENDING with a state-guarded UPDATE, so a message is handed out once.
 *    The guard is defense in depth: the tests only prove sequential claims, not two connections racing;
 *  - a message whose time passed by more than [graceMs] while the phone was off becomes [SchedState.MISSED] and is
 *    shown to the owner; it is never sent late without them seeing it;
 *  - a send in flight when the process died becomes [SchedState.UNKNOWN] and is never resent automatically;
 *  - the provider must be able to send ([Providers]) and the body must fit, checked when scheduling and again when due.
 *
 * Pure storage and state. The caller (an Android alarm + sender) performs the actual send and reports back through
 * [markSent] / [markFailed]; nothing here sends.
 */
class Scheduler(private val archive: Archive, private val graceMs: Long = DEFAULT_GRACE_MS) {
    private val db get() = archive.db

    fun schedule(conversation: Long, body: String, sendAt: Long): ScheduledMessage = db.tx {
        val conv = archive.conversation(conversation) ?: throw IllegalArgumentException("That conversation does not exist.")
        Providers.refusal(conv.provider, body)?.let { throw IllegalArgumentException(it) }
        val now = archive.clock()
        checkTime(sendAt, now)
        require(db.count("SELECT COUNT(*) n FROM scheduled WHERE state='PENDING'") < MAX_PENDING) { "Too many scheduled messages ($MAX_PENDING). Cancel some first." }
        val uid = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        db.insert(
            "INSERT INTO scheduled(uid,conversation,body,send_at,state,created,updated) VALUES(?,?,?,?,?,?,?)",
            uid, conversation, body, sendAt, SchedState.PENDING.name, now, now,
        )
        get(uid)!!
    }

    fun get(uid: String): ScheduledMessage? = db.one("SELECT * FROM scheduled WHERE uid=?", uid)?.let(::toMsg)

    fun list(conversation: Long? = null, limit: Int = 100): List<ScheduledMessage> =
        (if (conversation == null) db.rows("SELECT * FROM scheduled ORDER BY send_at, id LIMIT ?", limit)
        else db.rows("SELECT * FROM scheduled WHERE conversation=? ORDER BY send_at, id LIMIT ?", conversation, limit)).map(::toMsg)

    /** When the next alarm should fire, or null if nothing is pending. Includes overdue items (they resolve on the next claim). */
    fun nextDue(): Long? = db.one("SELECT MIN(send_at) t FROM scheduled WHERE state='PENDING'")?.get("t")?.toLongOrNull()

    /** Change the time of a pending message. False if it is no longer pending. */
    fun reschedule(uid: String, sendAt: Long): Boolean = db.tx {
        checkTime(sendAt, archive.clock())
        db.update("UPDATE scheduled SET send_at=?, updated=? WHERE uid=? AND state='PENDING'", sendAt, archive.clock(), uid) == 1
    }

    fun cancel(uid: String): Boolean =
        db.update("UPDATE scheduled SET state='CANCELLED', detail='Cancelled by the owner.', updated=? WHERE uid=? AND state='PENDING'", archive.clock(), uid) == 1

    fun cancelAllPending(reason: String): Int =
        db.update("UPDATE scheduled SET state='CANCELLED', detail=?, updated=? WHERE state='PENDING'", reason.take(300), archive.clock())

    /**
     * Messages the caller may send now. Each returned message is already SENDING for exactly this caller. Late ones are
     * marked MISSED instead of returned; ones whose conversation can no longer send are FAILED with the reason.
     */
    fun claimDue(limit: Int = 20): List<ScheduledMessage> = db.tx {
        val now = archive.clock()
        db.update(
            "UPDATE scheduled SET state='MISSED', detail=?, updated=? WHERE state='PENDING' AND send_at < ?",
            "The phone was not able to send this at its time; it was not sent late.", now, now - graceMs,
        )
        val out = ArrayList<ScheduledMessage>()
        for (r in db.rows("SELECT * FROM scheduled WHERE state='PENDING' AND send_at <= ? ORDER BY send_at, id LIMIT ?", now, limit)) {
            val m = toMsg(r)
            val conv = archive.conversation(m.conversation)
            val refusal = if (conv == null) "The conversation was deleted." else Providers.refusal(conv.provider, m.body)
            if (refusal != null) {
                db.update("UPDATE scheduled SET state='FAILED', detail=?, updated=? WHERE uid=? AND state='PENDING'", refusal.take(300), now, m.uid)
                continue
            }
            if (db.update("UPDATE scheduled SET state='SENDING', updated=? WHERE uid=? AND state='PENDING'", now, m.uid) == 1) out.add(m.copy(state = SchedState.SENDING, updated = now))
        }
        out
    }

    fun markSent(uid: String, detail: String = ""): Boolean = finish(uid, SchedState.SENT, detail)

    fun markFailed(uid: String, detail: String): Boolean = finish(uid, SchedState.FAILED, detail)

    /** Call once at startup. A send in flight at a restart is UNKNOWN: the carrier may or may not have it. Never resent. */
    fun recoverAfterRestart(): Int = db.update(
        "UPDATE scheduled SET state='UNKNOWN', detail='The app stopped while sending; the message may or may not have been sent.', updated=? WHERE state='SENDING'",
        archive.clock(),
    )

    /** Owner confirms what really happened to an UNKNOWN message. */
    fun resolveUnknown(uid: String, wasSent: Boolean): Boolean = db.update(
        "UPDATE scheduled SET state=?, detail=?, updated=? WHERE uid=? AND state='UNKNOWN'",
        if (wasSent) SchedState.SENT.name else SchedState.FAILED.name, "Confirmed by the owner.", archive.clock(), uid,
    ) == 1

    private fun finish(uid: String, to: SchedState, detail: String): Boolean =
        db.update("UPDATE scheduled SET state=?, detail=?, updated=? WHERE uid=? AND state='SENDING'", to.name, detail.take(300), archive.clock(), uid) == 1

    private fun checkTime(sendAt: Long, now: Long) {
        require(sendAt >= now + MIN_LEAD_MS) { "Pick a time at least ${MIN_LEAD_MS / 1000} seconds from now." }
        require(sendAt <= now + MAX_AHEAD_MS) { "Scheduling is limited to one year ahead." }
    }

    private fun toMsg(r: Row) = ScheduledMessage(
        r.str("uid"), r.long("conversation"), r.str("body"), r.long("send_at"),
        SchedState.valueOf(r.str("state")), r.str("detail"), r.long("created"), r.long("updated"),
    )

    companion object {
        const val MIN_LEAD_MS = 10_000L
        const val MAX_AHEAD_MS = 365L * 24 * 3_600_000
        const val DEFAULT_GRACE_MS = 15L * 60_000
        const val MAX_PENDING = 200L
    }
}
