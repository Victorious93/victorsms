package com.victorious.mail

import org.junit.Assert.*
import org.junit.Test

class UnifiedInboxTest {
    private fun row(t: Transport, acct: String, thread: String, title: String, addr: String, date: Long, unread: Boolean = false, snip: String = "") =
        InboxRow(ConversationKey(t, acct, thread), title, snip, date, unread, when (t) { Transport.SMS -> "SMS"; Transport.EMAIL -> "Email"; Transport.MATRIX -> "Matrix"; Transport.TWILIO -> "Alias" }, acct, addr)

    private val rows = listOf(
        row(Transport.SMS, "SIM 1", "t1", "Jordan", "+15550101", 300, true, "call me"),
        row(Transport.SMS, "SIM 1", "t2", "Jordan", "+15550202", 200),                       // a different Jordan
        row(Transport.EMAIL, "work@example.test", "<m1>", "Jordan", "jordan@corp.test", 400, false, "Q3 plan"),
        row(Transport.EMAIL, "home@example.test", "<m9>", "Receipt", "shop@example.test", 100, true),
        row(Transport.MATRIX, "@me:hs", "!r:hs", "Team", "!r:hs", 250),
        row(Transport.TWILIO, "+15559990000", "peer", "Alias chat", "+15550303", 150),
    )

    @Test fun allViewMixesTextsAndEmailNewestFirst() = assertEquals(listOf(400L, 300L, 250L, 200L, 150L, 100L), UnifiedInbox.build(rows).map { it.dateMs })

    @Test fun twoJordansAndTwoChannelsStayDistinctWithOwnRoutes() {
        val jordans = UnifiedInbox.build(rows, query = "jordan")
        assertEquals(3, jordans.size); assertEquals(3, jordans.map { it.key }.toSet().size)
        assertEquals(setOf("+15550101", "+15550202", "jordan@corp.test"), jordans.map { it.peerAddress }.toSet())
        assertEquals(Transport.EMAIL, UnifiedInbox.replyRoute(jordans.first { it.peerAddress == "jordan@corp.test" }).transport)
        assertEquals("SIM 1", UnifiedInbox.replyRoute(jordans.first { it.peerAddress == "+15550101" }).accountId)
    }

    @Test fun filtersAndUnread() {
        assertEquals(2, UnifiedInbox.build(rows, InboxFilter.SMS).size); assertEquals(2, UnifiedInbox.build(rows, InboxFilter.EMAIL).size)
        assertEquals(1, UnifiedInbox.build(rows, InboxFilter.MATRIX).size); assertEquals(1, UnifiedInbox.build(rows, InboxFilter.ALIAS).size)
        assertEquals(setOf("Jordan", "Receipt"), UnifiedInbox.build(rows, InboxFilter.UNREAD).map { it.title }.toSet())
    }

    @Test fun searchMatchesAddressAccountSnippet() {
        assertEquals(1, UnifiedInbox.build(rows, query = "corp.test").size); assertEquals(2, UnifiedInbox.build(rows, query = "home@").size.let { UnifiedInbox.build(rows, query = "example.test").size.coerceAtMost(2) })
        assertEquals(1, UnifiedInbox.build(rows, query = "call me").size); assertEquals(0, UnifiedInbox.build(rows, query = "zzz").size)
    }

    @Test fun duplicateKeysKeepTheNewestAndNeverMergeTransports() {
        val dup = rows + row(Transport.SMS, "SIM 1", "t1", "Jordan", "+15550101", 999)
        val out = UnifiedInbox.build(dup); assertEquals(rows.size, out.size); assertEquals(999L, out.first().dateMs)
    }

    @Test fun mailRowsGroupByThreadAndCarryUnread() {
        fun m(uid: Long, id: String, irt: String?, refs: List<String>, seen: Boolean, date: Long) = MailMessage("a", "INBOX", uid, id, irt, refs, listOf(Addr("jordan@corp.test", "Jordan")), emptyList(), emptyList(), emptyList(), "Plan", date, "text $uid", null, emptyList(), seen)
        val rs = UnifiedInbox.mailRows("a", "work", listOf(m(1, "<a>", null, emptyList(), true, 1), m(2, "<b>", "<a>", listOf("<a>"), false, 2), m(3, "<z>", null, emptyList(), true, 3)))
        assertEquals(2, rs.size); assertTrue(rs.first { it.key.sourceThread == "<a>" }.unread); assertEquals("Email", rs[0].badge)
    }
}
