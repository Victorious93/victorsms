package com.victorious.mail

/** One row of the main inbox. [key] never merges different transports/accounts/threads. [route] is where a reply goes — fixed per row. */
data class InboxRow(
    val key: ConversationKey, val title: String, val snippet: String, val dateMs: Long, val unread: Boolean,
    /** Channel badge text: "SMS", "Email", "Matrix", "Alias". */
    val badge: String,
    /** SIM / mail account / Matrix account / Twilio alias shown next to the badge, so same-named contacts stay distinguishable. */
    val accountLabel: String,
    /** The address behind the title (number, mail address, room id) — also what search matches and what disambiguates two "Jordan"s. */
    val peerAddress: String,
)

enum class InboxFilter { ALL, SMS, EMAIL, MATRIX, ALIAS, UNREAD }

object UnifiedInbox {
    /** Merge, filter, search, order (newest first). Duplicate keys keep the newest row; nothing is merged across transports. */
    fun build(rows: List<InboxRow>, filter: InboxFilter = InboxFilter.ALL, query: String = ""): List<InboxRow> {
        val newest = rows.groupBy { it.key }.map { (_, v) -> v.maxBy { it.dateMs } }
        val q = query.trim().lowercase()
        return newest.filter { r ->
            when (filter) {
                InboxFilter.ALL -> true
                InboxFilter.SMS -> r.key.transport == Transport.SMS
                InboxFilter.EMAIL -> r.key.transport == Transport.EMAIL
                InboxFilter.MATRIX -> r.key.transport == Transport.MATRIX
                InboxFilter.ALIAS -> r.key.transport == Transport.TWILIO
                InboxFilter.UNREAD -> r.unread
            } && (q.isEmpty() || listOf(r.title, r.snippet, r.peerAddress, r.accountLabel, r.badge).any { it.lowercase().contains(q) })
        }.sortedByDescending { it.dateMs }
    }

    /** Mail threads for one account from cached messages (thread = References root). */
    fun mailRows(accountId: String, accountLabel: String, msgs: List<MailMessage>): List<InboxRow> =
        msgs.groupBy { it.threadKey }.map { (tk, ms) ->
            val last = ms.maxBy { it.dateMs }
            val other = last.from.firstOrNull()
            InboxRow(ConversationKey(Transport.EMAIL, accountId, tk), last.subject.ifBlank { "(no subject)" }, other?.let { "${it.name ?: it.address}: " }.orEmpty() + (last.text?.lineSequence()?.firstOrNull { it.isNotBlank() } ?: ""),
                last.dateMs, ms.any { !it.seen }, "Email", accountLabel, other?.address ?: "")
        }

    /** Where a reply from [row] goes. Never changes with failure: a failed send stays on this route and is shown as failed. */
    fun replyRoute(row: InboxRow): ConversationKey = row.key
}
