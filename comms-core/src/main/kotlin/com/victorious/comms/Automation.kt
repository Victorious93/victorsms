package com.victorious.comms

import java.time.Instant
import java.time.ZoneId
import java.util.Locale

/** Operating modes a bot can be assigned for one conversation. New rules default to [APPROVAL] and disabled. */
enum class Mode { MANUAL, DRAFT, APPROVAL, AUTO, AUTONOMOUS, AWAY, CUSTOM }

data class AutoRule(
    val id: Long, val conversation: Long, val botRef: String, val mode: Mode, val enabled: Boolean,
    val hoursStart: Int?, val hoursEnd: Int?, val maxPerHour: Int, val minGapMs: Long, val maxConsecutive: Int,
    val disclose: Boolean, val expires: Long?,
)

sealed class GateDecision {
    /** Nothing may happen; [reason] is shown to the owner. */
    data class Skip(val reason: String) : GateDecision()

    /** Not yet: try again at [untilMs]. */
    data class Defer(val untilMs: Long, val reason: String) : GateDecision()

    /** Generate a suggestion and show it; send nothing. */
    object Suggest : GateDecision()

    /** Prepare a draft inside the conversation for manual review; send nothing. */
    object Draft : GateDecision()

    /** Prepare a reply and wait for the owner's approval before anything is sent. */
    object NeedsApproval : GateDecision()

    /** Every guard passed and the owner explicitly authorised automatic replies for this bot and conversation. */
    data class AutoSend(val needsDisclosure: Boolean) : GateDecision()
}

/**
 * Decides, from persistent state alone, whether a newly *live* incoming message may start work for a bot.
 * It is the gateway-side authority (VictorSMS re-checks it even if DroidCommand and VictorBots already did):
 * no model output, bot claim or message text can change a decision here.
 */
class AutomationGate(private val archive: Archive) {
    private val db get() = archive.db

    // ---- rules ----

    fun rule(conversation: Long, botRef: String): AutoRule? =
        db.one("SELECT * FROM auto_rules WHERE conversation=? AND bot_ref=?", conversation, botRef)?.let(::toRule)

    fun activeRule(conversation: Long): AutoRule? =
        db.one("SELECT * FROM auto_rules WHERE conversation=? AND enabled=1", conversation)?.let(::toRule)

    /** Creates or updates a rule. A new rule is [Mode.APPROVAL] and disabled until the owner enables it. */
    fun saveRule(
        conversation: Long, botRef: String, mode: Mode = Mode.APPROVAL, enabled: Boolean = false,
        hoursStart: Int? = null, hoursEnd: Int? = null, maxPerHour: Int = 4, minGapMs: Long = 30_000,
        maxConsecutive: Int = 3, disclose: Boolean = true, expires: Long? = null,
    ): AutoRule {
        require(hoursStart == null == (hoursEnd == null)) { "Give both an hours start and end, or neither." }
        require(hoursStart == null || (hoursStart in 0..23 && hoursEnd!! in 0..23)) { "Hours must be 0-23." }
        require(maxPerHour in 1..60 && maxConsecutive in 1..20 && minGapMs >= 0) { "Rate limits out of range." }
        db.tx {
            if (enabled) {
                val other = db.one("SELECT bot_ref FROM auto_rules WHERE conversation=? AND enabled=1 AND bot_ref<>?", conversation, botRef)
                require(other == null) { "Another bot (${other?.str("bot_ref")}) is already active in this conversation; two bots must never answer the same chat." }
            }
            val existing = db.one("SELECT id FROM auto_rules WHERE conversation=? AND bot_ref=?", conversation, botRef)
            if (existing == null) {
                db.insert(
                    """INSERT INTO auto_rules(conversation,bot_ref,mode,enabled,hours_start,hours_end,max_per_hour,min_gap_ms,max_consecutive,disclose,expires)
                       VALUES(?,?,?,?,?,?,?,?,?,?,?)""",
                    conversation, botRef, mode.name, enabled, hoursStart, hoursEnd, maxPerHour, minGapMs, maxConsecutive, disclose, expires,
                )
            } else {
                db.update(
                    """UPDATE auto_rules SET mode=?,enabled=?,hours_start=?,hours_end=?,max_per_hour=?,min_gap_ms=?,max_consecutive=?,disclose=?,expires=? WHERE id=?""",
                    mode.name, enabled, hoursStart, hoursEnd, maxPerHour, minGapMs, maxConsecutive, disclose, expires, existing.long("id"),
                )
            }
        }
        return rule(conversation, botRef)!!
    }

    // ---- kill switch ----

    val killed: Boolean get() = archive.setting(KILL_KEY) == "1"

    /** Stops every automatic reply immediately and cancels everything not yet sent. Returns how many queued items were cancelled. */
    fun killAll(): Int = db.tx {
        archive.putSetting(KILL_KEY, "1")
        Outbox(archive).cancelPending("Stopped by the owner (emergency stop).")
    }

    /** Only an explicit owner action clears the stop; rules stay exactly as they were. */
    fun resume() = archive.putSetting(KILL_KEY, "0")

    // ---- decision ----

    fun evaluate(triggerMessage: Long, now: Long = archive.clock(), zone: ZoneId = ZoneId.systemDefault()): GateDecision {
        if (killed) return GateDecision.Skip("All automatic communications are stopped.")
        val m = db.one("SELECT * FROM messages WHERE id=?", triggerMessage) ?: return GateDecision.Skip("Message not found.")
        if (m.str("origin") != "live") return GateDecision.Skip("Imported history never triggers replies.")
        if (m.str("direction") != "in") return GateDecision.Skip("Only incoming messages trigger replies.")
        val conv = m.long("conversation")
        val rule = activeRule(conv) ?: return GateDecision.Skip("No bot is enabled for this conversation.")
        if (db.one("SELECT 1 AS x FROM outbox WHERE idem_key=?", Outbox.idemKey(triggerMessage, rule.botRef)) != null) {
            return GateDecision.Skip("This message was already handled.")
        }
        if (rule.expires != null && now >= rule.expires) return GateDecision.Skip("This authorisation expired.")
        if (rule.hoursStart != null && !withinHours(now, zone, rule.hoursStart, rule.hoursEnd!!)) return GateDecision.Skip("Outside the bot's operating hours.")
        if (looksAutomated(m.str("body"))) return GateDecision.Skip("The sender looks automated; replying could start a loop.")
        val recentIn = db.count("SELECT COUNT(*) n FROM messages WHERE conversation=? AND direction='in' AND origin='live' AND ts>?", conv, now - 60_000)
        if (recentIn > FLOOD_PER_MINUTE) return GateDecision.Skip("Too many incoming messages in a minute; pausing to avoid a flood.")
        when (rule.mode) {
            Mode.MANUAL -> return GateDecision.Suggest
            Mode.DRAFT -> return GateDecision.Draft
            Mode.APPROVAL, Mode.CUSTOM -> return GateDecision.NeedsApproval
            else -> Unit
        }
        // Automatic modes only: the loop, rate and spacing guards.
        val sinceHuman = db.one(
            """SELECT COUNT(*) n FROM messages WHERE conversation=? AND direction='out' AND author='bot_auto'
               AND id>COALESCE((SELECT MAX(id) FROM messages WHERE conversation=? AND direction='out' AND author='owner' AND origin='live'),0)""", conv, conv,
        )?.long("n") ?: 0L
        if (sinceHuman >= rule.maxConsecutive) return GateDecision.Skip("${rule.maxConsecutive} automatic replies in a row without you; pausing to avoid a loop.")
        val lastHour = db.count("SELECT COUNT(*) n FROM outbox WHERE conversation=? AND state IN ('SENDING','SENT','DELIVERED','UNKNOWN') AND updated>?", conv, now - 3_600_000)
        if (lastHour >= rule.maxPerHour) return GateDecision.Skip("Hourly reply limit (${rule.maxPerHour}) reached.")
        val last = db.one("SELECT MAX(updated) AS t FROM outbox WHERE conversation=? AND state IN ('SENDING','SENT','DELIVERED','UNKNOWN')", conv)?.get("t")?.toLongOrNull()
        if (last != null && now - last < rule.minGapMs) return GateDecision.Defer(last + rule.minGapMs, "Waiting before the next automatic reply.")
        val disclosedAt = archive.setting("disclosed:$conv")?.toLongOrNull()
        return GateDecision.AutoSend(needsDisclosure = rule.disclose && (disclosedAt == null || now - disclosedAt > 24 * 3_600_000))
    }

    fun markDisclosed(conversation: Long, now: Long = archive.clock()) = archive.putSetting("disclosed:$conversation", now.toString())

    private fun toRule(r: Row) = AutoRule(
        r.long("id"), r.long("conversation"), r.str("bot_ref"), runCatching { Mode.valueOf(r.str("mode")) }.getOrDefault(Mode.APPROVAL),
        r.long("enabled") == 1L, r["hours_start"]?.toIntOrNull(), r["hours_end"]?.toIntOrNull(), r.long("max_per_hour").toInt(),
        r.long("min_gap_ms"), r.long("max_consecutive").toInt(), r.long("disclose") == 1L, r["expires"]?.toLongOrNull(),
    )

    companion object {
        const val KILL_KEY = "automation.killed"
        private const val FLOOD_PER_MINUTE = 10
        private val AUTOMATED = Regex("""(?i)\b(auto[- ]?reply|automated (message|response|reply)|this is an automated|do not reply to this|no[- ]?reply|\[auto\])""")

        fun looksAutomated(body: String) = AUTOMATED.containsMatchIn(body)

        /** Window may wrap midnight (22 to 6). End is exclusive; start == end means all day. */
        fun withinHours(nowMs: Long, zone: ZoneId, start: Int, end: Int): Boolean {
            val h = Instant.ofEpochMilli(nowMs).atZone(zone).hour
            return when {
                start == end -> true
                start < end -> h in start until end
                else -> h >= start || h < end
            }
        }
    }
}

enum class OutState {
    QUEUED, GENERATING, AWAITING_APPROVAL, DRAFTED, SENDING, SENT, DELIVERED, FAILED, UNKNOWN, CANCELLED;

    val terminal get() = this == DELIVERED || this == FAILED || this == CANCELLED || this == DRAFTED
}

data class OutboxEntry(
    val uid: String, val conversation: Long, val botRef: String, val triggerMessage: Long?, val body: String,
    val provider: String, val state: OutState, val detail: String, val created: Long, val updated: Long,
)

/**
 * Persistent, idempotent send queue. Properties (each tested):
 *  - one entry per (trigger message, bot): a retry or replay returns the existing entry, never a second send;
 *  - only *live* messages can be a trigger;
 *  - [markSending] is an atomic compare-and-set, so two workers can never both send the same entry;
 *  - a send that was in flight when the process died becomes [OutState.UNKNOWN] and is never resent automatically.
 */
class Outbox(private val archive: Archive) {
    private val db get() = archive.db

    fun enqueueReply(triggerMessage: Long, botRef: String): OutboxEntry = db.tx {
        val m = db.one("SELECT conversation, origin, direction FROM messages WHERE id=?", triggerMessage) ?: throw IllegalArgumentException("Trigger message not found.")
        require(m.str("origin") == "live") { "Imported history cannot trigger a reply." }
        val key = idemKey(triggerMessage, botRef)
        val existing = db.one("SELECT * FROM outbox WHERE idem_key=?", key)
        if (existing != null) return@tx toEntry(existing)
        val now = archive.clock()
        val uid = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        db.insert(
            "INSERT INTO outbox(uid,idem_key,conversation,bot_ref,trigger_message,state,created,updated) VALUES(?,?,?,?,?,?,?,?)",
            uid, key, m.long("conversation"), botRef, triggerMessage, OutState.QUEUED.name, now, now,
        )
        get(uid)!!
    }

    fun get(uid: String): OutboxEntry? = db.one("SELECT * FROM outbox WHERE uid=?", uid)?.let(::toEntry)

    fun list(conversation: Long? = null, limit: Int = 50): List<OutboxEntry> =
        (if (conversation == null) db.rows("SELECT * FROM outbox ORDER BY id DESC LIMIT ?", limit)
        else db.rows("SELECT * FROM outbox WHERE conversation=? ORDER BY id DESC LIMIT ?", conversation, limit)).map(::toEntry)

    fun setBody(uid: String, body: String, provider: String = "") {
        db.update("UPDATE outbox SET body=?, provider=?, updated=? WHERE uid=? AND state IN ('QUEUED','GENERATING','AWAITING_APPROVAL')", body, provider, archive.clock(), uid)
    }

    fun transition(uid: String, to: OutState, detail: String = ""): Boolean {
        val cur = get(uid) ?: return false
        if (to !in ALLOWED[cur.state].orEmpty()) return false
        return db.update("UPDATE outbox SET state=?, detail=?, updated=? WHERE uid=? AND state=?", to.name, detail.take(300), archive.clock(), uid, cur.state.name) == 1
    }

    /** Atomic: true for exactly one caller. Requires a body. Anything else must not send. */
    fun markSending(uid: String): Boolean = db.update(
        "UPDATE outbox SET state='SENDING', updated=? WHERE uid=? AND state IN ('QUEUED','AWAITING_APPROVAL','GENERATING') AND body<>''", archive.clock(), uid,
    ) == 1

    /** Call once at startup. In-flight sends become UNKNOWN (never resent); generation that never finished is re-queued. */
    fun recoverAfterRestart(): Int = db.tx {
        val unknown = db.update("UPDATE outbox SET state='UNKNOWN', detail='The app stopped while sending; the provider may or may not have sent it.', updated=? WHERE state='SENDING'", archive.clock())
        db.update("UPDATE outbox SET state='QUEUED', updated=? WHERE state='GENERATING'", archive.clock())
        unknown
    }

    fun cancelPending(reason: String): Int = db.update(
        "UPDATE outbox SET state='CANCELLED', detail=?, updated=? WHERE state IN ('QUEUED','GENERATING','AWAITING_APPROVAL')", reason.take(300), archive.clock(),
    )

    private fun toEntry(r: Row) = OutboxEntry(
        r.str("uid"), r.long("conversation"), r.str("bot_ref"), r["trigger_message"]?.toLongOrNull(), r.str("body"), r.str("provider"),
        OutState.valueOf(r.str("state")), r.str("detail"), r.long("created"), r.long("updated"),
    )

    companion object {
        fun idemKey(triggerMessage: Long, botRef: String) = "reply:$triggerMessage:${botRef.lowercase(Locale.ROOT)}"

        private val ALLOWED: Map<OutState, Set<OutState>> = mapOf(
            OutState.QUEUED to setOf(OutState.GENERATING, OutState.AWAITING_APPROVAL, OutState.DRAFTED, OutState.CANCELLED, OutState.FAILED),
            OutState.GENERATING to setOf(OutState.QUEUED, OutState.AWAITING_APPROVAL, OutState.DRAFTED, OutState.CANCELLED, OutState.FAILED),
            OutState.AWAITING_APPROVAL to setOf(OutState.CANCELLED, OutState.FAILED),
            OutState.SENDING to setOf(OutState.SENT, OutState.FAILED, OutState.UNKNOWN),
            OutState.SENT to setOf(OutState.DELIVERED, OutState.FAILED),
            OutState.UNKNOWN to setOf(OutState.SENT, OutState.DELIVERED, OutState.FAILED),
        )
    }
}
