package com.victorious.mail

import java.util.UUID

enum class OutState { QUEUED, SENDING, SENT, FAILED, UNCERTAIN }

data class OutboxItem(val draft: Draft, val state: OutState, val detail: String?, val attempts: Int, val updatedMs: Long)

/** Durable storage. The Android implementation is SQLite; every transition below is one atomic write so process death never loses a state. */
interface OutboxStore {
    fun put(item: OutboxItem)
    fun get(draftId: String): OutboxItem?
    fun all(): List<OutboxItem>
}

class InMemoryOutboxStore : OutboxStore {
    private val m = LinkedHashMap<String, OutboxItem>()
    @Synchronized override fun put(item: OutboxItem) { m[item.draft.id] = item }
    @Synchronized override fun get(draftId: String) = m[draftId]
    @Synchronized override fun all() = m.values.toList()
}

/**
 * Offline-safe outbox with duplicate protection:
 *  - A draft gets its Message-ID once, when queued.
 *  - SENDING is persisted BEFORE the connection is used. If the process dies, or the connection drops after data may have gone out, the
 *    item is UNCERTAIN. UNCERTAIN is never retried automatically; only an explicit owner action ([resendAnyway]) re-sends, with the SAME
 *    Message-ID, or the owner confirms it arrived ([markSent]).
 *  - NotAttempted (offline, TLS/auth failure before data) stays QUEUED and is retried freely: nothing could have been sent.
 *  - A definitive server rejection is FAILED; the owner edits and re-queues (new item), never a silent retry.
 *  - Channel is fixed by the draft's account; a failure never moves a message to another account or transport.
 */
class Outbox(private val store: OutboxStore, private val accounts: (String) -> MailAccount?, private val sender: SmtpSender = SmtpSender(), private val clock: () -> Long = System::currentTimeMillis) {
    @Synchronized fun enqueue(d: Draft): OutboxItem {
        require(accounts(d.accountId) != null) { "unknown account" }
        val withId = d.copy(id = d.id.ifBlank { UUID.randomUUID().toString() }, messageId = d.messageId ?: ("<" + UUID.randomUUID() + "@" + accounts(d.accountId)!!.address.substringAfter('@') + ">"))
        store.get(withId.id)?.let { return it }                       // idempotent enqueue
        return OutboxItem(withId, OutState.QUEUED, null, 0, clock()).also(store::put)
    }

    /** Call once at app start: anything left SENDING when the process died may have been accepted. */
    @Synchronized fun recoverAfterRestart() {
        store.all().filter { it.state == OutState.SENDING }.forEach { store.put(it.copy(state = OutState.UNCERTAIN, detail = "the app stopped while sending; it may or may not have been delivered", updatedMs = clock())) }
    }

    /** Sends every QUEUED item (never UNCERTAIN/FAILED). Returns the items processed. */
    fun flush(): List<OutboxItem> = store.all().filter { it.state == OutState.QUEUED }.map { attempt(it.draft.id) }

    private fun attempt(id: String): OutboxItem {
        val item = synchronized(this) {
            val cur = store.get(id) ?: error("no such item")
            check(cur.state == OutState.QUEUED) { "only queued items are sent" }
            cur.copy(state = OutState.SENDING, attempts = cur.attempts + 1, updatedMs = clock()).also(store::put)   // persisted BEFORE any network use
        }
        val acct = accounts(item.draft.accountId)
        val outcome = if (acct == null) SendOutcome.Rejected("account was removed") else try { sender.send(acct, item.draft) } catch (e: Exception) { SendOutcome.Uncertain("unexpected ${e.javaClass.simpleName}") }
        val next = when (outcome) {
            is SendOutcome.Accepted -> item.copy(state = OutState.SENT, detail = null)
            is SendOutcome.Rejected -> item.copy(state = OutState.FAILED, detail = outcome.reason)
            is SendOutcome.Uncertain -> item.copy(state = OutState.UNCERTAIN, detail = outcome.reason + " — it may have been delivered; check Sent before sending again")
            is SendOutcome.NotAttempted -> item.copy(state = OutState.QUEUED, detail = "waiting: ${outcome.reason}")
        }.copy(updatedMs = clock())
        synchronized(this) { store.put(next) }
        return next
    }

    /** Explicit owner decision after UNCERTAIN: send again (same Message-ID). */
    fun resendAnyway(id: String): OutboxItem {
        synchronized(this) { val c = store.get(id) ?: error("no such item"); check(c.state == OutState.UNCERTAIN) { "only an uncertain send can be re-sent this way" }; store.put(c.copy(state = OutState.QUEUED, detail = "re-sent by the owner", updatedMs = clock())) }
        return attempt(id)
    }

    /** Explicit owner decision: it did arrive. */
    @Synchronized fun markSent(id: String) { val c = store.get(id) ?: return; if (c.state == OutState.UNCERTAIN) store.put(c.copy(state = OutState.SENT, detail = "confirmed by the owner", updatedMs = clock())) }
    @Synchronized fun discard(id: String) { val c = store.get(id) ?: return; if (c.state != OutState.SENDING) store.put(c.copy(state = OutState.FAILED, detail = "discarded by the owner", updatedMs = clock())) }
}
