package com.victorious.sms.bridge

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

object DraftCapability {
    const val OP_DELIVER = "message.draft.deliver"
    const val OP_STATUS = "message.draft.status"
    val deliver = CapabilityDescriptor(OP_DELIVER, kind = "action", remoteAllowed = true, requiresConfirm = true)
    val status = CapabilityDescriptor(OP_STATUS, kind = "read", remoteAllowed = true, requiresConfirm = false)
}

enum class DraftState { RECEIVED, DECLINED, SUBMITTED, FAILED }

data class IncomingDraft(
    val draftId: String, val caller: CallerIdentity, val channel: String, val to: List<String>, val cc: List<String>, val bcc: List<String>,
    val subject: String, val body: String, val accountHint: String?, val attachments: List<Triple<String, String, String>>,    // name, mime, base64
    val contentHash: String, val origin: String, val expiresAtMs: Long, @Volatile var state: DraftState = DraftState.RECEIVED, @Volatile var detail: String? = null,
)

/**
 * Desktop-prepared drafts (VictorLinux → DroidCommand → here). DELIVERY NEVER SENDS: a draft just appears for review. Only the owner's
 * Send action ([approve]) submits it, at most once per draft id (state machine under a lock); a repeated delivery of the same draft id is
 * idempotent and returns the current state; Decline sends nothing. Mail credentials never leave this phone and the sending identity is the
 * owner's choice at review time. Persisted encrypted so a pending review survives process death.
 */
object DraftHandoff {
    private val drafts = ConcurrentHashMap<String, IncomingDraft>()
    @Volatile private var store: PendingStore? = null
    const val MAX_TTL_MS = 24L * 3600 * 1000

    @Synchronized fun attachStore(st: PendingStore, nowMs: Long = System.currentTimeMillis()) {
        drafts.clear()
        st.loadAll().forEach { (id, json) -> val d = decode(json); if (d == null || key(d.caller, d.draftId) != id || nowMs > d.expiresAtMs + MAX_TTL_MS) st.remove(id) else drafts[id] = d }
        store = st
    }

    internal fun key(caller: CallerIdentity, id: String) = caller.packageName + "|" + id

    sealed class Result { data class Accepted(val draftId: String, val state: DraftState) : Result(); data class Rejected(val code: String) : Result() }

    /** [notify] shows the review prompt. Idempotent per (caller, draftId): a retry returns the current state and notifies nothing. */
    @Synchronized fun deliver(caller: CallerIdentity, p: JSONObject, nowMs: Long, notify: (IncomingDraft) -> Unit): Result {
        val id = p.optString("draftId").take(96)
        if (id.isBlank() || !Regex("^[A-Za-z0-9._:-]{1,96}$").matches(id)) return Result.Rejected("invalid_parameters")
        drafts[key(caller, id)]?.let { return Result.Accepted(id, it.state) }                              // retried hand-off: never re-shown as new, never sent
        val channel = p.optString("channel"); if (channel != "sms" && channel != "email") return Result.Rejected("invalid_parameters")
        fun list(k: String) = p.optJSONArray(k)?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()
        val to = list("to"); if (to.isEmpty() || to.size > 50) return Result.Rejected("invalid_parameters")
        val cc = list("cc"); val bcc = list("bcc")
        if ((to + cc + bcc).any { it.length > 320 || it.contains('\n') || it.contains('\r') }) return Result.Rejected("invalid_parameters")
        val body = p.optString("body"); if (body.length > 100_000 || (channel == "sms" && body.isBlank())) return Result.Rejected("invalid_parameters")
        val subject = p.optString("subject"); if (subject.length > 998 || subject.contains('\n') || subject.contains('\r')) return Result.Rejected("invalid_parameters")
        val atts = p.optJSONArray("attachments")?.let { a -> (0 until a.length()).map { a.getJSONObject(it).let { x -> Triple(x.getString("name"), x.getString("mime"), x.getString("data_b64")) } } } ?: emptyList()
        if (atts.size > 8 || atts.any { !Regex("^[A-Za-z0-9._ -]{1,128}$").matches(it.first) || it.first == ".." }) return Result.Rejected("invalid_parameters")
        val exp = minOf(p.optLong("expiresMs", nowMs + MAX_TTL_MS), nowMs + MAX_TTL_MS); if (exp <= nowMs) return Result.Rejected("expired")
        val d = IncomingDraft(id, caller, channel, to, cc, bcc, subject, body, p.optString("accountHint").takeIf { it.isNotBlank() }, atts, p.optString("contentHash"), p.optString("origin").take(128), exp)
        drafts[key(caller, id)] = d; persist(d); notify(d)
        return Result.Accepted(id, d.state)
    }

    fun status(caller: CallerIdentity, id: String, nowMs: Long = System.currentTimeMillis()): DraftState? = drafts[key(caller, id)]?.let { d ->
        synchronized(d) { if (d.state == DraftState.RECEIVED && nowMs > d.expiresAtMs) { d.state = DraftState.DECLINED; d.detail = "expired without a decision"; persist(d) } }; d.state }

    fun get(callerPkg: String, id: String) = drafts.entries.firstOrNull { it.key == "$callerPkg|$id" }?.value
    fun find(id: String) = drafts.values.firstOrNull { it.draftId == id }
    fun pending() = drafts.values.filter { it.state == DraftState.RECEIVED }

    /** The owner's Send. [send] runs AT MOST ONCE for a RECEIVED, unexpired draft; the draft is marked before nothing else can run it. */
    fun approve(id: String, nowMs: Long, send: (IncomingDraft) -> Pair<DraftState, String?>): DraftState {
        val d = find(id) ?: return DraftState.FAILED
        synchronized(d) {
            if (d.state != DraftState.RECEIVED) return d.state
            if (nowMs > d.expiresAtMs) { d.state = DraftState.DECLINED; d.detail = "expired"; persist(d); return d.state }
            d.detail = "sending"; persist(d)                                              // crash after this point leaves a non-RECEIVED-looking record: never auto-resent
            val (st, why) = try { send(d) } catch (e: Exception) { DraftState.FAILED to "unexpected ${e.javaClass.simpleName}" }
            d.state = st; d.detail = why; persist(d)
        }
        return d.state
    }

    fun decline(id: String) { find(id)?.let { d -> synchronized(d) { if (d.state == DraftState.RECEIVED) { d.state = DraftState.DECLINED; d.detail = "declined by the owner"; persist(d) } } } }

    internal fun persist(d: IncomingDraft) { store?.put(key(d.caller, d.draftId), encode(d)) }
    internal fun encode(d: IncomingDraft) = JSONObject().put("id", d.draftId).put("pkg", d.caller.packageName).put("cert", d.caller.certSha256).put("channel", d.channel).put("to", JSONArray(d.to)).put("cc", JSONArray(d.cc)).put("bcc", JSONArray(d.bcc))
        .put("subject", d.subject).put("body", d.body).put("hint", d.accountHint ?: JSONObject.NULL).put("atts", JSONArray(d.attachments.map { JSONObject().put("n", it.first).put("m", it.second).put("d", it.third) }))
        .put("hash", d.contentHash).put("origin", d.origin).put("exp", d.expiresAtMs).put("state", d.state.name).put("detail", d.detail ?: JSONObject.NULL).toString()
    internal fun decode(json: String): IncomingDraft? = runCatching {
        val o = JSONObject(json); fun l(k: String) = o.getJSONArray(k).let { a -> (0 until a.length()).map { a.getString(it) } }
        IncomingDraft(o.getString("id"), CallerIdentity(o.getString("pkg"), o.getString("cert")), o.getString("channel"), l("to"), l("cc"), l("bcc"), o.getString("subject"), o.getString("body"),
            o.optString("hint").takeIf { it.isNotEmpty() && it != "null" }, o.getJSONArray("atts").let { a -> (0 until a.length()).map { a.getJSONObject(it).let { x -> Triple(x.getString("n"), x.getString("m"), x.getString("d")) } } },
            o.getString("hash"), o.getString("origin"), o.getLong("exp"), DraftState.valueOf(o.getString("state")), o.optString("detail").takeIf { it.isNotEmpty() && it != "null" })
    }.getOrNull()

    fun sha(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}
