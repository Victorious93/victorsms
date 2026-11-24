package com.victorious.comms

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Victorious Framework: the read-only archive operations VictorSMS offers a paired assistant app (droidcommand-AI)
 * over its EXTERNAL_NODE bridge. This class is the second gate. The first is the bridge's `ARCHIVE_READ` group, which
 * the owner grants per app. This one is the owner's per-conversation switch (`assistant_access`, OFF by default):
 * a conversation that is not switched on does not exist as far as any op here is concerned.
 *
 * Nothing here writes message data, sends anything, or returns more than [ArchiveLimits] allows. Every answer is
 * plain data; the caller must treat message text as untrusted quoted material, never as instructions.
 */
object ArchiveWire {
    const val VERSION = 1
    const val CAPABILITIES = "comms.capabilities"
    const val LIST = "comms.conversations.list"
    const val READ = "comms.conversation.read"
    const val SEARCH = "comms.messages.search"
    const val CONTEXT = "comms.context.retrieve"
    val OPS: List<String> = listOf(CAPABILITIES, LIST, READ, SEARCH, CONTEXT)

    fun isArchiveOp(op: String) = op in OPS
}

data class ArchiveLimits(
    val defaultLimit: Int = 50,
    val maxLimit: Int = 200,
    /** Longer bodies are clipped and flagged `truncated`. */
    val maxBodyChars: Int = 4_000,
    /**
     * Hard cap on the *serialized* characters of the `messages` array in one answer (field names and quotes count, not just
     * text). The assistant's broker refuses results over 60,000 characters, so this leaves room for the envelope.
     */
    val maxResultChars: Int = 45_000,
    val maxQueryChars: Int = 200,
    val defaultContextChars: Int = 6_000,
    val maxContextChars: Int = 12_000,
    /** Extra rows fetched so a run of messages with one timestamp is never split across two pages. */
    val groupSlack: Int = 200,
)

sealed class ArchiveReply {
    data class Ok(val result: JsonObject) : ArchiveReply()

    /** [code] is a stable machine value; [message] is for people. */
    data class Error(val code: String, val message: String) : ArchiveReply()
}

class ArchiveOps(private val archive: Archive, private val limits: ArchiveLimits = ArchiveLimits()) {
    /** [caller] is the verified package name of the paired app. It is only written to the access log. */
    fun call(caller: String, op: String, params: JsonObject): ArchiveReply {
        val reply = try {
            dispatch(op, params)
        } catch (e: Exception) {
            ArchiveReply.Error("internal_error", "The archive could not answer.")
        }
        if (op != ArchiveWire.CAPABILITIES) {
            val uid = (params["conversation"] as? JsonPrimitive)?.content?.take(64)
            val (returned, outcome) = when (reply) {
                is ArchiveReply.Ok -> count(reply.result) to "ok"
                is ArchiveReply.Error -> 0 to reply.code
            }
            runCatching { archive.logAccess(caller, op, uid, returned, outcome) }
        }
        return reply
    }

    private fun dispatch(op: String, p: JsonObject): ArchiveReply = when (op) {
        ArchiveWire.CAPABILITIES -> capabilities(p)
        ArchiveWire.LIST -> list(p)
        ArchiveWire.READ -> read(p)
        ArchiveWire.SEARCH -> search(p)
        ArchiveWire.CONTEXT -> context(p)
        else -> ArchiveReply.Error("unsupported_operation", "Unknown archive operation.")
    }

    // ---- ops ----

    private fun capabilities(p: JsonObject): ArchiveReply {
        unknown(p, emptySet())?.let { return it }
        return ArchiveReply.Ok(
            buildJsonObject {
                put("version", ArchiveWire.VERSION)
                put("ops", JsonArray(ArchiveWire.OPS.map { JsonPrimitive(it) }))
                put("access", "per_conversation")
                put("shared_conversations", archive.assistantConversations().size)
                put("max_limit", limits.maxLimit)
                put("max_body_chars", limits.maxBodyChars)
            },
        )
    }

    private fun list(p: JsonObject): ArchiveReply {
        unknown(p, setOf("limit", "provider"))?.let { return it }
        val limit = intParam(p, "limit", limits.defaultLimit, 1, limits.maxLimit) ?: return bad("limit")
        val provider = strParam(p, "provider", 40) ?: return bad("provider")
        val all = archive.assistantConversations().filter { provider.isEmpty() || it.provider.equals(provider, ignoreCase = true) }
        val shown = all.take(limit)
        return ArchiveReply.Ok(
            buildJsonObject {
                put("conversations", JsonArray(shown.map(::conversationJson)))
                put("count", shown.size)
                put("truncated", all.size > shown.size)
            },
        )
    }

    private fun read(p: JsonObject): ArchiveReply {
        unknown(p, setOf("conversation", "limit", "before_ts", "direction"))?.let { return it }
        val conv = shared(strParam(p, "conversation", 64).orEmpty()) ?: return notFound()
        val limit = intParam(p, "limit", limits.defaultLimit, 1, limits.maxLimit) ?: return bad("limit")
        val beforeTs = longParam(p, "before_ts") ?: return bad("before_ts")
        val dir = when (strParam(p, "direction", 8)) {
            null -> return bad("direction")
            "", "any" -> null
            "in" -> Direction.IN
            "out" -> Direction.OUT
            else -> return bad("direction")
        }
        val fetched = archive.messages(conv.id, limit + limits.groupSlack + 1, beforeTs.takeIf { it >= 0 }, dir).asReversed()
        val page = takePage(fetched, limit) { messageJson(it, null).toString().length }
        val more = page.size < fetched.size || fetched.size >= limit + limits.groupSlack + 1
        val kept = page.asReversed()
        return ArchiveReply.Ok(
            buildJsonObject {
                put("conversation", conv.uid)
                put("title", conv.title)
                put("messages", JsonArray(kept.map { messageJson(it, null) }))
                put("count", kept.size)
                put("has_more", more)
                if (more && kept.isNotEmpty()) put("next_before_ts", kept.first().ts) else put("next_before_ts", JsonNull)
            },
        )
    }

    /**
     * Newest-first [rows] to one page of at most about [limit] messages. Whole runs of one timestamp are taken or left,
     * because the cursor is a timestamp: splitting a run would silently skip the rest of it on the next page.
     */
    private fun takePage(rows: List<StoredMessage>, limit: Int, cost: (StoredMessage) -> Int): List<StoredMessage> {
        val out = ArrayList<StoredMessage>()
        var chars = 0
        var i = 0
        while (i < rows.size) {
            var j = i
            while (j < rows.size && rows[j].ts == rows[i].ts) j++
            val group = rows.subList(i, j)
            val groupChars = group.sumOf(cost)
            if (out.isNotEmpty() && (out.size + group.size > limit || chars + groupChars > limits.maxResultChars)) break
            out.addAll(group)
            chars += groupChars
            i = j
        }
        return out
    }

    private fun search(p: JsonObject): ArchiveReply {
        unknown(p, setOf("query", "conversation", "limit"))?.let { return it }
        val query = strParam(p, "query", limits.maxQueryChars).orEmpty()
        if (query.isBlank()) return bad("query")
        val limit = intParam(p, "limit", 25, 1, limits.maxLimit) ?: return bad("limit")
        val scope = strParam(p, "conversation", 64) ?: return bad("conversation")
        val only = if (scope.isEmpty()) null else (shared(scope) ?: return notFound())
        fun run(all: Boolean) = if (only == null) archive.searchShared(query, limit, all) else archive.search(query, only.id, limit, all)
        // Every word first (what a person typing a few words means); then any word, because a model often sends a whole sentence.
        var matchMode = "all"
        var hits = run(true)
        if (hits.isEmpty()) { matchMode = "any"; hits = run(false) }
        val meta = HashMap<Long, ConversationSummary>()
        fun metaOf(id: Long) = meta.getOrPut(id) { archive.conversation(id)!! }
        var chars = 0
        val shown = ArrayList<JsonElement>()
        for (m in hits) {
            val c = metaOf(m.conversation)
            val el = messageJson(m, c)
            val size = el.toString().length
            if (shown.isNotEmpty() && chars + size > limits.maxResultChars) break
            chars += size
            shown.add(el)
        }
        return ArchiveReply.Ok(
            buildJsonObject {
                put("messages", JsonArray(shown))
                put("count", shown.size)
                put("match", matchMode)
                put("truncated", shown.size < hits.size)
            },
        )
    }

    private fun context(p: JsonObject): ArchiveReply {
        unknown(p, setOf("conversation", "query", "max_chars"))?.let { return it }
        val conv = shared(strParam(p, "conversation", 64).orEmpty()) ?: return notFound()
        val query = strParam(p, "query", limits.maxQueryChars) ?: return bad("query")
        val maxChars = intParam(p, "max_chars", limits.defaultContextChars, 200, limits.maxContextChars) ?: return bad("max_chars")
        val ctx = ContextRetriever(archive).retrieve(conv.id, query, ContextBudget(maxChars = maxChars))
        val turns = (ctx.recent.map { it to false } + ctx.relevant.map { it to true }).sortedBy { it.first.ts }
        return ArchiveReply.Ok(
            buildJsonObject {
                put("conversation", conv.uid)
                put(
                    "messages",
                    JsonArray(
                        turns.map { (m, relevant) ->
                            buildJsonObject {
                                put("from_owner", m.direction == Direction.OUT)
                                put("text", m.body)
                                put("ts", m.ts)
                                put("relevant", relevant)
                            }
                        },
                    ),
                )
                put("facts", JsonArray(ctx.facts.map { JsonPrimitive(it) }))
                if (ctx.summary != null) put("summary", ctx.summary) else put("summary", JsonNull)
                put("owner_examples", JsonArray(ctx.examples.map { JsonPrimitive(it.body) }))
                put("truncated", ctx.truncated)
                put("chars", ctx.chars)
            },
        )
    }

    // ---- helpers ----

    /** A conversation the owner shared, or null. A missing conversation and an unshared one are indistinguishable on purpose. */
    private fun shared(uid: String): ConversationSummary? =
        if (uid.isEmpty()) null else archive.conversationByUid(uid)?.takeIf { it.assistantAccess }

    private fun notFound() = ArchiveReply.Error("not_found", "No such conversation is shared with the assistant.")

    private fun bad(name: String) = ArchiveReply.Error("invalid_parameters", "Parameter '$name' is not valid.")

    private fun unknown(p: JsonObject, allowed: Set<String>): ArchiveReply.Error? =
        (p.keys - allowed).firstOrNull()?.let { ArchiveReply.Error("invalid_parameters", "Unknown parameter '${it.take(40)}'.") }

    /** Null when present but malformed; [default] when absent. Accepts a JSON number or a numeric string. */
    private fun intParam(p: JsonObject, name: String, default: Int, min: Int, max: Int): Int? {
        val raw = p[name] ?: return default
        val n = (raw as? JsonPrimitive)?.content?.toLongOrNull() ?: return null
        return if (n in min..max) n.toInt() else null
    }

    /** -1 when absent; null when malformed. */
    private fun longParam(p: JsonObject, name: String): Long? {
        val raw = p[name] ?: return -1L
        val n = (raw as? JsonPrimitive)?.content?.toLongOrNull() ?: return null
        return if (n >= 0) n else null
    }

    /** "" when absent; null when it is not a string or is too long. */
    private fun strParam(p: JsonObject, name: String, max: Int): String? {
        val raw = p[name] ?: return ""
        val s = (raw as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
        return if (s.length <= max) s else null
    }

    private fun conversationJson(c: ConversationSummary) = buildJsonObject {
        put("id", c.uid)
        put("title", c.title)
        put("provider", c.provider)
        put("is_group", c.isGroup)
        put("message_count", c.messageCount)
        if (c.lastTs != null) put("last_ts", c.lastTs) else put("last_ts", JsonNull)
    }

    private fun messageJson(m: StoredMessage, conversation: ConversationSummary?) = buildJsonObject {
        if (conversation != null) {
            put("conversation", conversation.uid)
            put("title", conversation.title)
        }
        put("id", m.id)
        put("ts", m.ts)
        put("direction", m.direction.db)
        put("sender", m.sender)
        val clipped = m.body.length > limits.maxBodyChars
        put("body", if (clipped) m.body.take(limits.maxBodyChars) else m.body)
        if (clipped) put("truncated", true)
        put("origin", m.origin)
        put("author", m.author)
    }

    private fun count(result: JsonObject): Int =
        (result["count"] as? JsonPrimitive)?.content?.toIntOrNull()
            ?: (result["messages"] as? JsonArray)?.size
            ?: 0
}
