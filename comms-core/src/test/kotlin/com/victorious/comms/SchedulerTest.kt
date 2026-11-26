package com.victorious.comms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Victorious Framework — scheduled messages and the provider capability table. */
class SchedulerTest {
    private var now = Fx.BASE
    private val a: Archive = newArchive(clock = { now }).first
    private val s = Scheduler(a)
    private val sms = a.createConversation("sms", "", "sms||p:5550100101", "Sam", false)
    private val wa = a.createConversation("whatsapp", "", "whatsapp||p:5550100102", "Wren", false)
    private val min = 60_000L

    private fun rejects(work: () -> Unit): String = try { work(); fail("expected refusal"); "" } catch (e: IllegalArgumentException) { e.message!! }

    @Test fun aScheduledMessageStaysPendingAndNothingIsClaimedBeforeItsTime() {
        val m = s.schedule(sms, "see you at 5", now + 10 * min)
        assertEquals(SchedState.PENDING, m.state)
        assertEquals(now + 10 * min, s.nextDue())
        assertTrue(s.claimDue().isEmpty())
        now += 9 * min
        assertTrue(s.claimDue().isEmpty())
    }

    @Test fun whenDueItIsClaimedOnceAndALaterClaimGetsNothing() {
        val m = s.schedule(sms, "happy birthday", now + min)
        now += 2 * min
        val first = s.claimDue()
        val second = s.claimDue()
        assertEquals(listOf(m.uid), first.map { it.uid })
        assertEquals(SchedState.SENDING, first.single().state)
        assertTrue("a later claim must get nothing (sequential; not a concurrency test)", second.isEmpty())
        assertTrue(s.markSent(m.uid))
        assertFalse("already finished", s.markSent(m.uid))
        assertEquals(SchedState.SENT, s.get(m.uid)!!.state)
    }

    @Test fun aMessageThePhoneMissedIsMarkedMissedNotSentLate() {
        val m = s.schedule(sms, "call me", now + min)
        now += 3 * 3_600_000L // phone was off for three hours
        assertTrue(s.claimDue().isEmpty())
        assertEquals(SchedState.MISSED, s.get(m.uid)!!.state)
        assertTrue(s.get(m.uid)!!.state.terminal)
    }

    @Test fun aSendInFlightAtARestartBecomesUnknownAndIsNeverResent() {
        val m = s.schedule(sms, "on my way", now + min)
        now += 2 * min
        s.claimDue()
        assertEquals(1, s.recoverAfterRestart())
        assertEquals(SchedState.UNKNOWN, s.get(m.uid)!!.state)
        now += 2 * min
        assertTrue(s.claimDue().isEmpty())
        assertFalse(s.markSent(m.uid)) // the sender cannot overwrite it either
        assertTrue(s.resolveUnknown(m.uid, wasSent = false))
        assertEquals(SchedState.FAILED, s.get(m.uid)!!.state)
    }

    @Test fun cancelOnlyWorksWhilePendingAndRescheduleMovesTheTime() {
        val m = s.schedule(sms, "later", now + 5 * min)
        assertTrue(s.reschedule(m.uid, now + 20 * min))
        assertEquals(now + 20 * min, s.get(m.uid)!!.sendAt)
        assertTrue(s.cancel(m.uid))
        assertFalse(s.cancel(m.uid))
        assertFalse(s.reschedule(m.uid, now + 30 * min))
        now += 25 * min
        assertTrue(s.claimDue().isEmpty())
    }

    @Test fun importOnlyConversationsCannotBeScheduledOn() {
        assertTrue(rejects { s.schedule(wa, "hi", now + min) }.contains("only holds them as history"))
        assertEquals(0, s.list().size)
    }

    @Test fun badInputIsRefusedWithAnExplanation() {
        assertTrue(rejects { s.schedule(sms, "   ", now + min) }.contains("empty"))
        assertTrue(rejects { s.schedule(sms, "x".repeat(1_601), now + min) }.contains("1600"))
        assertTrue(rejects { s.schedule(sms, "hi", now + 1_000) }.contains("at least"))
        assertTrue(rejects { s.schedule(sms, "hi", now - min) }.contains("at least"))
        assertTrue(rejects { s.schedule(sms, "hi", now + 400L * 24 * 3_600_000) }.contains("one year"))
        assertTrue(rejects { s.schedule(9999, "hi", now + min) }.contains("does not exist"))
    }

    @Test fun theConversationBecomingUnsendableFailsTheMessageInsteadOfSendingIt() {
        val m = s.schedule(sms, "hi", now + min)
        a.db.update("UPDATE conversations SET provider='whatsapp' WHERE id=?", sms)
        now += 2 * min
        assertTrue(s.claimDue().isEmpty())
        assertEquals(SchedState.FAILED, s.get(m.uid)!!.state)
    }

    @Test fun theCapAndEmergencyCancelWork() {
        repeat(Scheduler.MAX_PENDING.toInt()) { s.schedule(sms, "m$it", now + min) }
        assertTrue(rejects { s.schedule(sms, "one too many", now + min) }.contains("Too many"))
        assertEquals(Scheduler.MAX_PENDING.toInt(), s.cancelAllPending("owner stop"))
        assertNotNull(s.schedule(sms, "fine again", now + min))
    }

    @Test fun deletingAConversationRemovesItsScheduledMessages() {
        val m = s.schedule(sms, "bye", now + min)
        a.deleteConversation(sms)
        assertNull(s.get(m.uid))
    }

    @Test fun claimsComeOutInTimeOrder() {
        val late = s.schedule(sms, "second", now + 2 * min)
        val early = s.schedule(sms, "first", now + min)
        now += 3 * min
        assertEquals(listOf(early.uid, late.uid), s.claimDue().map { it.uid })
    }

    @Test fun providerTableIsConservative() {
        assertTrue(Providers.of("SMS").canSend)
        assertTrue("legacy label is SMS", Providers.of("sms-backup").canSend)
        assertFalse(Providers.of("textnow").canSend)
        assertFalse("unknown labels get nothing", Providers.of("signal").canSend)
        assertEquals(0, Providers.of("signal").maxBodyChars)
        assertNull(Providers.refusal("sms", "ok"))
    }

    @Test fun smsRecipientIsTheOneOtherNumberAndNothingAmbiguous() {
        val live = LiveIngest(a)
        live.record("+1 (555) 010-0177", "hi", now, Direction.IN)
        live.record("+1 (555) 010-0177", "yo", now + 1, Direction.OUT)
        val c = a.conversations().single { it.title.startsWith("+1") }.id
        assertEquals("+1 (555) 010-0177", a.smsRecipient(c))
        a.upsertParticipant(c, "p:5550100199", "Extra", "5550100199", false) // now a group of two others
        assertNull(a.smsRecipient(c))
        val named = a.createConversation("sms", "", "sms||n:sam", "Sam", false)
        a.upsertParticipant(named, "n:sam", "Sam", null, false) // a name with no number
        assertNull(a.smsRecipient(named))
        assertNull(a.smsRecipient(9999))
    }

    @Test fun aGroupOrHistoryOnlyProviderCannotResolveASmsRecipient() {
        val group = a.createConversation("sms", "", "group", "Group", true)
        val imported = a.createConversation("textnow", "", "history", "TextNow history", false)
        for (conversation in listOf(group, imported)) {
            a.upsertParticipant(conversation, "p:5550100199", "Peer", "5550100199", false)
            assertNull(a.smsRecipient(conversation))
        }
    }

    @Test fun activeTextsAreVisibleAheadOfOlderHistory() {
        val done = s.schedule(sms, "old", now + min)
        s.cancel(done.uid)
        val active = s.schedule(sms, "upcoming", now + 2 * min)
        assertEquals(listOf(active.uid), s.list(limit = 1).map { it.uid })
        assertEquals(listOf(active.uid), s.list(conversation = sms, limit = 1).map { it.uid })
    }
}
