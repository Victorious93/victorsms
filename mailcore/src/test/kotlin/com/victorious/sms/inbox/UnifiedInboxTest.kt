package com.victorious.sms.inbox

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class UnifiedInboxTest {
    private fun row(t: Transport, acct: String, thread: String, title: String, who: String, time: Long, unread: Int = 0, snippet: String = "hi", label: String = acct) =
        InboxRow(ConversationKey(t, acct, thread), title, who, label, snippet, time, unread)

    private val jordanSms = row(Transport.SMS, "sim1", "t7", "Jordan", "+15550001", 100, 1, "lunch?", "SIM 1")
    private val jordanMail = row(Transport.EMAIL, "work@example.com", "<root-1@x>", "Jordan", "jordan@corp.example", 200, 0, "Q3 report", "work@example.com")
    private val otherJordan = row(Transport.SMS, "sim1", "t9", "Jordan", "+15550002", 50, 2, "wrong number")
    private val room = row(Transport.MATRIX, "@me:hs", "!room:hs", "Team", "!room:hs", 150, 0, "standup")
    private val twilio = row(Transport.TWILIO, "alias-+1555", "CH1", "Pat", "+15559999", 20, 0, "alias text")

    @Test fun `two people called Jordan stay distinguishable`() {
        assertNotEquals(jordanSms.key.id, otherJordan.key.id)
        val merged = UnifiedInbox.merge(listOf(jordanSms, otherJordan))
        assertEquals(2, merged.size)
        assertEquals(setOf("+15550001", "+15550002"), merged.map { it.counterpart }.toSet())
    }

    @Test fun `one Jordan on two channels keeps two rows and two reply routes`() {
        val merged = UnifiedInbox.merge(listOf(jordanSms), listOf(jordanMail))
        assertEquals(2, merged.size)
        val smsRoute = UnifiedInbox.replyRoute(merged.first { it.key.transport == Transport.SMS })
        val mailRoute = UnifiedInbox.replyRoute(merged.first { it.key.transport == Transport.EMAIL })
        assertEquals(Transport.SMS, smsRoute.transport); assertEquals("sim1", smsRoute.account); assertEquals("+15550001", smsRoute.to)
        assertEquals(Transport.EMAIL, mailRoute.transport); assertEquals("work@example.com", mailRoute.account); assertEquals("jordan@corp.example", mailRoute.to)
    }

    @Test fun `a failed send keeps its route and never switches channel`() {
        val route = UnifiedInbox.replyRoute(jordanMail)
        val st = UnifiedInbox.failed(route, "SMTP down")
        assertTrue(st is OutgoingState.Failed && st.route == route && st.route.transport == Transport.EMAIL)
    }

    @Test fun `All view merges every source newest first`() {
        val all = UnifiedInbox.merge(listOf(jordanSms, otherJordan), listOf(jordanMail), listOf(room), listOf(twilio))
        assertEquals(listOf(200L, 150L, 100L, 50L, 20L), all.map { it.timeMs })
    }

    @Test fun `filters by channel account unread and search`() {
        val all = UnifiedInbox.merge(listOf(jordanSms, otherJordan), listOf(jordanMail), listOf(room), listOf(twilio))
        assertEquals(2, UnifiedInbox.filter(all, InboxQuery(ChannelFilter.SMS)).size)
        assertEquals(1, UnifiedInbox.filter(all, InboxQuery(ChannelFilter.EMAIL)).size)
        assertEquals(1, UnifiedInbox.filter(all, InboxQuery(ChannelFilter.MATRIX)).size)
        assertEquals(1, UnifiedInbox.filter(all, InboxQuery(ChannelFilter.TWILIO)).size)
        assertEquals(2, UnifiedInbox.filter(all, InboxQuery(unreadOnly = true)).size)
        assertEquals(listOf("t7"), UnifiedInbox.filter(all, InboxQuery(account = "sim1", text = "LUNCH")).map { it.key.sourceThread })
        assertEquals(3, UnifiedInbox.unreadTotal(all))
        assertEquals(1, UnifiedInbox.filter(all, InboxQuery(text = "corp.example")).size)
    }

    @Test fun `same key from two sources keeps the newest`() {
        val newer = jordanSms.copy(snippet = "newer", timeMs = 999)
        val merged = UnifiedInbox.merge(listOf(jordanSms), listOf(newer))
        assertEquals(1, merged.size); assertEquals("newer", merged[0].snippet)
    }

    @Test fun `email only and sms only inboxes work without the other`() {
        assertEquals(1, UnifiedInbox.merge(emptyList(), listOf(jordanMail), emptyList()).size)
        assertEquals(1, UnifiedInbox.merge(listOf(jordanSms)).size)
    }
}
