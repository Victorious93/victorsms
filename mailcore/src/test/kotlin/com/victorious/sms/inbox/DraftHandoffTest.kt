package com.victorious.sms.inbox

import com.victorious.sms.mail.MemoryRecordStore
import com.victorious.sms.mail.SendOutcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DraftHandoffTest {
    private val draft = IncomingDraft("dr-1", "email", listOf("jordan@example.com"), listOf("c@example.com"), emptyList(), "Hello", "SECRET BODY", "work@example.com")
    private val sms = IncomingDraft("dr-2", "sms", listOf("+15551234567"), emptyList(), emptyList(), "", "hi there", "SIM 1")

    private fun handoff(store: MemoryRecordStore = MemoryRecordStore(), now: () -> Long = { 1000L }, calls: MutableList<Pair<IncomingDraft, String>> = ArrayList(),
                        outcome: () -> SendOutcome = { SendOutcome.Accepted }) =
        DraftHandoff(store, { d, id -> calls += d to id; outcome() }, now)

    @Test fun `receiving a draft sends nothing and the review shows recipients identity and contents`() {
        val calls = ArrayList<Pair<IncomingDraft, String>>(); val h = handoff(calls = calls)
        assertIs<ReceiveResult.Shown>(h.receive(draft))
        assertEquals(0, calls.size)
        val r = h.review("dr-1", "work@example.com")!!
        assertEquals(listOf("To: jordan@example.com", "Cc: c@example.com"), r.recipients); assertEquals("work@example.com", r.sendingIdentity)
        assertEquals("Hello", r.subject); assertEquals("SECRET BODY", r.body)
    }

    @Test fun `approve sends exactly once even if Send is pressed twice`() {
        val calls = ArrayList<Pair<IncomingDraft, String>>(); val h = handoff(calls = calls)
        h.receive(draft)
        assertEquals(HandoffState.SUBMITTED, h.ownerSend("dr-1", "work@example.com"))
        assertEquals(HandoffState.SUBMITTED, h.ownerSend("dr-1", "work@example.com"))
        assertEquals(1, calls.size); assertEquals("work@example.com", calls[0].second)
    }

    @Test fun `declining sends nothing and later Send is impossible`() {
        val calls = ArrayList<Pair<IncomingDraft, String>>(); val h = handoff(calls = calls)
        h.receive(draft)
        assertEquals(HandoffState.DECLINED, h.ownerDecline("dr-1"))
        assertEquals(HandoffState.DECLINED, h.ownerSend("dr-1", "work@example.com"))
        assertEquals(0, calls.size); assertNull(h.review("dr-1", "x"))
    }

    @Test fun `retrying or replaying the handoff cannot send it`() {
        val calls = ArrayList<Pair<IncomingDraft, String>>(); val store = MemoryRecordStore(); val h = handoff(store, calls = calls)
        h.receive(draft); h.ownerSend("dr-1", "work@example.com")
        val again = handoff(store, calls = calls).receive(draft)                 // coordinator redelivers after a restart
        assertEquals(ReceiveResult.AlreadySeen(HandoffState.SUBMITTED), again)
        assertEquals(1, calls.size)
        val replayPending = handoff(store, calls = calls)
        replayPending.receive(sms); replayPending.receive(sms)                   // delivered twice before review: one proposal, still unsent
        assertEquals(1, calls.size); assertEquals(HandoffState.RECEIVED, replayPending.state("dr-2"))
    }

    @Test fun `failure and uncertainty are reported distinctly and uncertain is not retried by Send`() {
        var out: SendOutcome = SendOutcome.NotSent("offline"); val calls = ArrayList<Pair<IncomingDraft, String>>()
        val h = handoff(calls = calls) { out }
        h.receive(draft); assertEquals(HandoffState.FAILED, h.ownerSend("dr-1", "a"))
        out = SendOutcome.Uncertain("dropped"); h.receive(sms); assertEquals(HandoffState.UNCERTAIN, h.ownerSend("dr-2", "SIM 1"))
        assertEquals(HandoffState.UNCERTAIN, h.ownerSend("dr-2", "SIM 1")); assertEquals(2, calls.size)
    }

    @Test fun `body and recipients are dropped from storage once the draft is resolved`() {
        val store = MemoryRecordStore(); val h = handoff(store)
        h.receive(draft)
        assertTrue(store.all().getValue("dr-1").contains("SECRET BODY"))
        h.ownerSend("dr-1", "work@example.com")
        val after = store.all().getValue("dr-1")
        assertFalse(after.contains("SECRET BODY")); assertFalse(after.contains("jordan@example.com"))
    }

    @Test fun `expired drafts cannot be sent`() {
        var t = 1000L; val calls = ArrayList<Pair<IncomingDraft, String>>(); val h = handoff(now = { t }, calls = calls)
        h.receive(draft); t += 2 * 60 * 60 * 1000L
        assertEquals(HandoffState.FAILED, h.ownerSend("dr-1", "x")); assertEquals(0, calls.size)
    }

    @Test fun `invalid drafts are refused`() {
        val h = handoff()
        assertIs<ReceiveResult.Refused>(h.receive(draft.copy(draftId = "x1", to = emptyList())))
        assertIs<ReceiveResult.Refused>(h.receive(draft.copy(draftId = "x2", subject = " ")))
        assertIs<ReceiveResult.Refused>(h.receive(draft.copy(draftId = "x3", kind = "shell")))
    }

    @Test fun `fleet status messages match the contract and carry no content`() {
        val h = handoff(); h.receive(draft)
        val (t1, p1) = h.fleetMessage("dr-1", "plan-m.draft", 1)!!
        assertEquals("progress", t1); assertEquals("draft_received", p1.getString("note"))
        h.ownerSend("dr-1", "a")
        val (t2, p2) = h.fleetMessage("dr-1", "plan-m.draft", 1)!!
        assertEquals("complete", t2); assertEquals("submitted", p2.getJSONObject("result").getString("status"))
        assertFalse(p2.toString().contains("SECRET")); assertFalse(p2.toString().contains("jordan"))
        h.receive(sms); h.ownerDecline("dr-2")
        assertEquals("declined", h.fleetMessage("dr-2", "j", 1)!!.second.getJSONObject("result").getString("status"))
    }
}
