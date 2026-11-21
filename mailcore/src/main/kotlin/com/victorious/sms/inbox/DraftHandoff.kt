package com.victorious.sms.inbox

import com.victorious.sms.mail.RecordStore
import com.victorious.sms.mail.SendOutcome
import org.json.JSONArray
import org.json.JSONObject

/** A draft prepared on VictorLinux (fleet op `message.draft`). It is only ever a proposal. */
data class IncomingDraft(
    val draftId: String,
    val kind: String,                 // "sms" | "email"
    val to: List<String>,
    val cc: List<String>,
    val bcc: List<String>,
    val subject: String,
    val body: String,
    val identityHint: String,
)

enum class HandoffState { RECEIVED, SUBMITTED, DECLINED, FAILED, UNCERTAIN }

sealed interface ReceiveResult {
    data class Shown(val draft: IncomingDraft) : ReceiveResult
    /** The same draft_id arrived again (retry, replay). It is NOT shown as sendable again. */
    data class AlreadySeen(val state: HandoffState) : ReceiveResult
    data class Refused(val reason: String) : ReceiveResult
}

/** What the owner sees on the review screen before pressing Send. */
data class ReviewModel(val kind: String, val recipients: List<String>, val sendingIdentity: String, val subject: String, val body: String)

/**
 * Phone side of the desktop draft hand-off. The only transition to SUBMITTED is [ownerSend], which the review screen
 * calls from the owner's Send button. Receiving, re-receiving, notifications or any remote message cannot send.
 * Seen draft ids persist (so a re-delivered assignment cannot send twice), as do their outcomes.
 */
class DraftHandoff(
    private val store: RecordStore,
    private val submit: (IncomingDraft, String) -> SendOutcome,
    private val clock: () -> Long = System::currentTimeMillis,
    private val maxAgeMs: Long = 60 * 60 * 1000L,
) {
    fun receive(d: IncomingDraft): ReceiveResult {
        existing(d.draftId)?.let { return ReceiveResult.AlreadySeen(it.first) }
        if (d.kind != "sms" && d.kind != "email") return ReceiveResult.Refused("unknown kind")
        if (d.to.isEmpty() || d.body.isBlank()) return ReceiveResult.Refused("empty recipients or body")
        if (d.kind == "email" && d.subject.isBlank()) return ReceiveResult.Refused("email needs a subject")
        write(d, HandoffState.RECEIVED, clock(), "")
        return ReceiveResult.Shown(d)
    }

    fun review(draftId: String, identity: String): ReviewModel? {
        val (st, d) = existing(draftId) ?: return null
        if (st != HandoffState.RECEIVED) return null
        return ReviewModel(d.kind, d.to.map { "To: $it" } + d.cc.map { "Cc: $it" } + d.bcc.map { "Bcc: $it" }, identity, d.subject, d.body)
    }

    /** The owner pressed Send. Valid exactly once per draft. */
    fun ownerSend(draftId: String, identity: String): HandoffState {
        val (st, d) = existing(draftId) ?: return HandoffState.FAILED
        if (st != HandoffState.RECEIVED) return st                         // already resolved: never submit twice
        if (clock() - created(draftId) > maxAgeMs) { write(d, HandoffState.FAILED, created(draftId), "expired before review"); return HandoffState.FAILED }
        val outcome = try { submit(d, identity) } catch (t: Throwable) { SendOutcome.Uncertain("unexpected error") }
        val next = when (outcome) {
            SendOutcome.Accepted -> HandoffState.SUBMITTED
            is SendOutcome.NotSent, is SendOutcome.Rejected -> HandoffState.FAILED
            is SendOutcome.Uncertain -> HandoffState.UNCERTAIN
        }
        write(d, next, created(draftId), (outcome as? SendOutcome.NotSent)?.reason ?: (outcome as? SendOutcome.Rejected)?.reason ?: (outcome as? SendOutcome.Uncertain)?.reason ?: "")
        return next
    }

    fun ownerDecline(draftId: String): HandoffState {
        val (st, d) = existing(draftId) ?: return HandoffState.FAILED
        if (st != HandoffState.RECEIVED) return st
        write(d, HandoffState.DECLINED, created(draftId), ""); return HandoffState.DECLINED
    }

    fun state(draftId: String): HandoffState? = existing(draftId)?.first

    /** Fleet protocol payloads (see VictorLinux fleet/fixtures/v1/message_draft_*). No body, recipients or credentials. */
    fun fleetMessage(draftId: String, jobId: String, attempt: Int): Pair<String, JSONObject>? {
        val st = state(draftId) ?: return null
        val base = JSONObject().put("job_id", jobId).put("attempt", attempt)
        return when (st) {
            HandoffState.RECEIVED -> "progress" to base.put("note", "draft_received")
            HandoffState.SUBMITTED -> "complete" to base.put("result", JSONObject().put("draft_id", draftId).put("status", "submitted"))
            HandoffState.DECLINED -> "complete" to base.put("result", JSONObject().put("draft_id", draftId).put("status", "declined"))
            HandoffState.UNCERTAIN -> "fail" to base.put("error", JSONObject().put("code", "deadline_exceeded").put("message", "send outcome uncertain")).put("side_effects", "possible")
            HandoffState.FAILED -> "fail" to base.put("error", JSONObject().put("code", "executor_unavailable").put("message", "draft was not sent")).put("side_effects", "none")
        }
    }

    // --- persistence -------------------------------------------------------------------
    private fun created(id: String): Long = JSONObject(store.all().getValue(id)).getLong("created")

    private fun existing(id: String): Pair<HandoffState, IncomingDraft>? = store.all()[id]?.let {
        val j = JSONObject(it); val d = j.getJSONObject("draft")
        fun arr(k: String) = d.getJSONArray(k).let { a -> (0 until a.length()).map { i -> a.getString(i) } }
        HandoffState.valueOf(j.getString("state")) to IncomingDraft(id, d.getString("kind"), arr("to"), arr("cc"), arr("bcc"), d.getString("subject"), d.getString("body"), d.getString("hint"))
    }

    private fun write(d: IncomingDraft, st: HandoffState, created: Long, reason: String) {
        // Once resolved, the body is dropped from storage; only the id and outcome stay (replay guard).
        val keep = st == HandoffState.RECEIVED
        store.put(d.draftId, JSONObject().put("state", st.name).put("created", created).put("reason", reason).put("draft", JSONObject()
            .put("kind", d.kind).put("to", JSONArray(if (keep) d.to else d.to.map { "" })).put("cc", JSONArray(if (keep) d.cc else emptyList<String>()))
            .put("bcc", JSONArray(if (keep) d.bcc else emptyList<String>())).put("subject", if (keep) d.subject else "").put("body", if (keep) d.body else "")
            .put("hint", d.identityHint)).toString())
    }
}
