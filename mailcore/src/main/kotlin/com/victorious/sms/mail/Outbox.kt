package com.victorious.sms.mail

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** Durable key/value records. Android: backed by encrypted storage; JVM tests: a directory. */
interface RecordStore {
    fun all(): Map<String, String>
    fun put(id: String, json: String)
    fun remove(id: String)
}

class MemoryRecordStore : RecordStore {
    private val m = ConcurrentHashMap<String, String>()
    override fun all(): Map<String, String> = HashMap(m)
    override fun put(id: String, json: String) { m[id] = json }
    override fun remove(id: String) { m.remove(id) }
}

/** One file per record, written atomically. Survives process death. */
class FileRecordStore(private val dir: File) : RecordStore {
    init { dir.mkdirs() }
    // Reversible file names: every byte outside [A-Za-z0-9.-] becomes _XX, so ids such as "uuid@host" round-trip exactly.
    private fun enc(id: String): String = buildString { for (b in id.toByteArray()) { val c = b.toInt().toChar(); if (c.isLetterOrDigit() && c.code < 128 || c == '.' || c == '-') append(c) else append("_%02x".format(b)) } }
    private fun dec(name: String): String {
        val out = java.io.ByteArrayOutputStream(); var i = 0
        while (i < name.length) { if (name[i] == '_') { out.write(name.substring(i + 1, i + 3).toInt(16)); i += 3 } else { out.write(name[i].code); i++ } }
        return out.toString(Charsets.UTF_8)
    }
    private fun f(id: String) = File(dir, enc(id) + ".json")
    override fun all(): Map<String, String> = dir.listFiles { x -> x.name.endsWith(".json") && !x.name.startsWith(".") }.orEmpty()
        .associate { dec(it.name.removeSuffix(".json")) to it.readText() }
    override fun put(id: String, json: String) {
        val tmp = File(dir, ".${f(id).name}.tmp"); tmp.writeText(json)
        if (!tmp.renameTo(f(id))) { f(id).writeText(json); tmp.delete() }
    }
    override fun remove(id: String) { f(id).delete() }
}

enum class OutboxState { QUEUED, SENDING, SENT, FAILED, UNCERTAIN }

data class OutboxItem(
    val id: String,                 // == Message-ID without brackets: the one identity of this send
    val accountId: String,
    val state: OutboxState,
    val reason: String,
    val attempts: Int,
    val createdMs: Long,
    val payload: Outgoing,
)

/**
 * Durable send queue with duplicate protection.
 *
 * Rules: one Message-ID per logical send, assigned at enqueue; only QUEUED items are ever transmitted; an item
 * found in SENDING after a restart becomes UNCERTAIN (it may have reached the server); UNCERTAIN is never retried by
 * the app: the owner resolves it by checking the Sent folder, resending as a NEW message, or discarding.
 */
class Outbox(
    private val store: RecordStore,
    private val accounts: (String) -> MailAccount?,
    private val sender: (Outgoing) -> SendOutcome,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    init { recoverAfterRestart() }

    private fun key(messageId: String) = messageId.trim('<', '>')

    fun enqueue(o: Outgoing): OutboxItem {
        val item = OutboxItem(key(o.messageId), o.account.id, OutboxState.QUEUED, "", 0, clock(), o)
        save(item); return item
    }

    fun list(): List<OutboxItem> = store.all().values.mapNotNull { parse(it) }.sortedBy { it.createdMs }
    fun get(id: String): OutboxItem? = store.all()[id]?.let { parse(it) }

    /** Transmit queued items oldest first. Returns the items touched. */
    fun processQueued(): List<OutboxItem> {
        val out = ArrayList<OutboxItem>()
        for (item in list().filter { it.state == OutboxState.QUEUED }) {
            val sending = item.copy(state = OutboxState.SENDING, attempts = item.attempts + 1)
            save(sending)                                   // persisted BEFORE the network call
            val result = try { sender(sending.payload) } catch (t: Throwable) { SendOutcome.Uncertain("unexpected error: ${t.javaClass.simpleName}") }
            val done = when (result) {
                SendOutcome.Accepted -> sending.copy(state = OutboxState.SENT, reason = "")
                is SendOutcome.NotSent -> sending.copy(state = OutboxState.FAILED, reason = result.reason)
                is SendOutcome.Rejected -> sending.copy(state = OutboxState.FAILED, reason = result.reason)
                is SendOutcome.Uncertain -> sending.copy(state = OutboxState.UNCERTAIN, reason = result.reason)
            }
            save(done); out += done
        }
        return out
    }

    /** Explicit owner action on a FAILED item (nothing was accepted): same message, same Message-ID. */
    fun retryFailed(id: String): OutboxItem? {
        val i = get(id) ?: return null
        if (i.state != OutboxState.FAILED) return i
        return i.copy(state = OutboxState.QUEUED).also(::save)
    }

    /** Resolve UNCERTAIN by asking the server's Sent folder whether it holds this Message-ID. */
    fun resolveUncertain(id: String, inSentFolder: (MailAccount, String) -> Boolean): OutboxItem? {
        val i = get(id) ?: return null
        if (i.state != OutboxState.UNCERTAIN) return i
        val acct = accounts(i.accountId) ?: return i
        return if (inSentFolder(acct, i.payload.messageId)) i.copy(state = OutboxState.SENT, reason = "found in Sent folder").also(::save) else i
    }

    /** Explicit owner action: the owner accepts the risk of a duplicate. A NEW Message-ID makes the new send its own record. */
    fun resendAsNew(id: String): OutboxItem? {
        val i = get(id) ?: return null
        if (i.state != OutboxState.UNCERTAIN) return null
        val fresh = i.payload.copy(messageId = newMessageId(i.payload.account.address))
        remove(id)
        return enqueue(fresh)
    }

    fun remove(id: String) { store.remove(id) }

    private fun recoverAfterRestart() {
        for (i in list().filter { it.state == OutboxState.SENDING })
            save(i.copy(state = OutboxState.UNCERTAIN, reason = "the app stopped while this was being sent; it may have been delivered"))
    }

    private fun save(i: OutboxItem) = store.put(i.id, toJson(i).toString())

    companion object {
        fun toJson(i: OutboxItem): JSONObject = JSONObject()
            .put("id", i.id).put("account", i.accountId).put("state", i.state.name).put("reason", i.reason)
            .put("attempts", i.attempts).put("created", i.createdMs).put("payload", payloadJson(i.payload))

        internal fun payloadJson(o: Outgoing) = JSONObject()
            .put("to", JSONArray(o.to)).put("cc", JSONArray(o.cc)).put("bcc", JSONArray(o.bcc)).put("subject", o.subject)
            .put("text", o.text).put("replyTo", o.replyTo ?: JSONObject.NULL).put("inReplyTo", o.inReplyTo ?: JSONObject.NULL)
            .put("references", JSONArray(o.references)).put("messageId", o.messageId)
            .put("attachments", JSONArray(o.attachments.map {
                JSONObject().put("name", it.name).put("mime", it.mimeType).put("b64", java.util.Base64.getEncoder().encodeToString(it.bytes))
            }))
    }

    private fun parse(json: String): OutboxItem? = try {
        val j = JSONObject(json); val p = j.getJSONObject("payload")
        val acct = accounts(j.getString("account")) ?: return null
        fun arr(k: String) = p.getJSONArray(k).let { a -> (0 until a.length()).map { a.getString(it) } }
        val atts = p.getJSONArray("attachments").let { a -> (0 until a.length()).map { n ->
            val o = a.getJSONObject(n); Attachment(o.getString("name"), o.getString("mime"), java.util.Base64.getDecoder().decode(o.getString("b64"))) } }
        OutboxItem(j.getString("id"), acct.id, OutboxState.valueOf(j.getString("state")), j.getString("reason"), j.getInt("attempts"), j.getLong("created"),
            Outgoing(acct, arr("to"), arr("cc"), arr("bcc"), p.getString("subject"), p.getString("text"),
                p.optString("replyTo", "").ifEmpty { null }, p.optString("inReplyTo", "").ifEmpty { null }, arr("references"), atts, p.getString("messageId")))
    } catch (e: Exception) { null }
}

/** Drafts survive restarts. Autosave replaces the record; sending a draft goes through [Outbox], then the draft is removed. */
class DraftStore(private val store: RecordStore, private val accounts: (String) -> MailAccount?) {
    fun save(id: String, o: Outgoing) {
        store.put(id, JSONObject().put("account", o.account.id).put("payload", Outbox.payloadJson(o)).toString())
    }
    fun load(id: String): Outgoing? = store.all()[id]?.let { parse(it) }
    fun ids(): List<String> = store.all().keys.sorted()
    fun delete(id: String) = store.remove(id)

    private fun parse(json: String): Outgoing? = try {
        val j = JSONObject(json); val p = j.getJSONObject("payload"); val acct = accounts(j.getString("account")) ?: return null
        fun arr(k: String) = p.getJSONArray(k).let { a -> (0 until a.length()).map { a.getString(it) } }
        val atts = p.getJSONArray("attachments").let { a -> (0 until a.length()).map { n ->
            val o = a.getJSONObject(n); Attachment(o.getString("name"), o.getString("mime"), java.util.Base64.getDecoder().decode(o.getString("b64"))) } }
        Outgoing(acct, arr("to"), arr("cc"), arr("bcc"), p.getString("subject"), p.getString("text"),
            p.optString("replyTo", "").ifEmpty { null }, p.optString("inReplyTo", "").ifEmpty { null }, arr("references"), atts, p.getString("messageId"))
    } catch (e: Exception) { null }
}
