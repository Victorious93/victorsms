package com.victorious.sms.inbox

/** Where a conversation lives. A reply always leaves through the same transport and account it arrived on. */
enum class Transport { SMS, MMS, EMAIL, MATRIX, TWILIO }

/**
 * Identity of one conversation: transport + account/SIM/alias + the source's own thread id
 * (Telephony thread_id, email thread root Message-ID, Matrix room id, Twilio conversation SID).
 * Display names are never part of the key, so two people called Jordan stay two conversations, and
 * one Jordan reached by SMS and by email stays two conversations with two reply routes.
 */
data class ConversationKey(val transport: Transport, val account: String, val sourceThread: String) {
    val id: String get() = "${transport.name}|$account|$sourceThread"
}

data class InboxRow(
    val key: ConversationKey,
    val title: String,            // display name, may collide between rows
    val counterpart: String,      // address / number / mxid, shown to disambiguate
    val accountLabel: String,     // SIM slot, mailbox address or alias shown on the row
    val snippet: String,
    val timeMs: Long,
    val unread: Int,
)

/** Where a reply to a row goes. Derived only from the row's key; there is no fallback API. */
data class ReplyRoute(val transport: Transport, val account: String, val sourceThread: String, val to: String)

sealed interface OutgoingState {
    data class Queued(val route: ReplyRoute) : OutgoingState
    data class Sent(val route: ReplyRoute) : OutgoingState
    /** A failed send stays failed on its route. Retrying is an explicit user action on the same route. */
    data class Failed(val route: ReplyRoute, val reason: String) : OutgoingState
    /** The transport may have accepted it. Never retried automatically. */
    data class Uncertain(val route: ReplyRoute, val reason: String) : OutgoingState
}

enum class ChannelFilter { ALL, SMS, EMAIL, MATRIX, TWILIO }

data class InboxQuery(
    val channel: ChannelFilter = ChannelFilter.ALL,
    val account: String? = null,
    val unreadOnly: Boolean = false,
    val text: String = "",
)

object UnifiedInbox {
    /** Merge row lists from every source. The same key from two sources keeps the newest row. */
    fun merge(vararg sources: List<InboxRow>): List<InboxRow> {
        val byKey = LinkedHashMap<String, InboxRow>()
        for (list in sources) for (r in list) {
            val cur = byKey[r.key.id]
            if (cur == null || r.timeMs >= cur.timeMs) byKey[r.key.id] = r
        }
        return byKey.values.sortedWith(compareByDescending<InboxRow> { it.timeMs }.thenBy { it.key.id })
    }

    fun filter(rows: List<InboxRow>, q: InboxQuery): List<InboxRow> {
        val needle = q.text.trim().lowercase()
        return rows.filter { r ->
            val channelOk = when (q.channel) {
                ChannelFilter.ALL -> true
                ChannelFilter.SMS -> r.key.transport == Transport.SMS || r.key.transport == Transport.MMS
                ChannelFilter.EMAIL -> r.key.transport == Transport.EMAIL
                ChannelFilter.MATRIX -> r.key.transport == Transport.MATRIX
                ChannelFilter.TWILIO -> r.key.transport == Transport.TWILIO
            }
            channelOk &&
                (q.account == null || r.key.account == q.account) &&
                (!q.unreadOnly || r.unread > 0) &&
                (needle.isEmpty() || listOf(r.title, r.counterpart, r.snippet, r.accountLabel).any { it.lowercase().contains(needle) })
        }
    }

    fun unreadTotal(rows: List<InboxRow>): Int = rows.sumOf { it.unread }

    fun replyRoute(row: InboxRow): ReplyRoute =
        ReplyRoute(row.key.transport, row.key.account, row.key.sourceThread, row.counterpart)

    /** A failure keeps the original route. This function deliberately takes no alternative routes. */
    fun failed(route: ReplyRoute, reason: String): OutgoingState = OutgoingState.Failed(route, reason)
}
