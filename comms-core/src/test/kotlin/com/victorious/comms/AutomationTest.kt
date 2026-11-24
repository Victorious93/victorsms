package com.victorious.comms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.ZoneOffset

class AutomationTest {
    private var now = Fx.BASE // 12:00 UTC
    private val a: Archive = newArchive(clock = { now }).first
    private val gate = AutomationGate(a)
    private val outbox = Outbox(a)
    private val conv = a.createConversation("textnow", "5550100100", "textnow|5550100100|p:5550100101", "Sam", false)

    private fun incoming(body: String = "hello?", at: Long = now) = a.recordLive(conv, Direction.IN, "p:5550100101", "Sam", body, at)
    private fun owner(body: String, author: Author = Author.OWNER) = a.recordLive(conv, Direction.OUT, "self:me", "Me", body, now, author = author)
    private fun sendOne(trigger: Long, bot: String = "bot-1"): OutboxEntry {
        val e = outbox.enqueueReply(trigger, bot)
        outbox.setBody(e.uid, "reply")
        assertTrue(outbox.markSending(e.uid))
        assertTrue(outbox.transition(e.uid, OutState.SENT))
        return outbox.get(e.uid)!!
    }

    @Test fun aNewRuleIsApprovalModeAndDisabledSoNothingHappensUntilTheOwnerTurnsItOn() {
        val rule = gate.saveRule(conv, "bot-1")
        assertEquals(Mode.APPROVAL, rule.mode)
        assertFalse(rule.enabled)
        assertTrue(gate.evaluate(incoming(), now, ZoneOffset.UTC) is GateDecision.Skip)
    }

    @Test fun eachModeMapsToExactlyItsBehaviour() {
        val m = incoming()
        gate.saveRule(conv, "bot-1", Mode.MANUAL, enabled = true)
        assertEquals(GateDecision.Suggest, gate.evaluate(m, now, ZoneOffset.UTC))
        gate.saveRule(conv, "bot-1", Mode.DRAFT, enabled = true)
        assertEquals(GateDecision.Draft, gate.evaluate(m, now, ZoneOffset.UTC))
        gate.saveRule(conv, "bot-1", Mode.APPROVAL, enabled = true)
        assertEquals(GateDecision.NeedsApproval, gate.evaluate(m, now, ZoneOffset.UTC))
        gate.saveRule(conv, "bot-1", Mode.AUTO, enabled = true)
        assertEquals(GateDecision.AutoSend(needsDisclosure = true), gate.evaluate(m, now, ZoneOffset.UTC))
    }

    @Test fun twoBotsCanNeverBeActiveInTheSameConversation() {
        gate.saveRule(conv, "bot-1", Mode.AUTO, enabled = true)
        try { gate.saveRule(conv, "bot-2", Mode.AUTO, enabled = true); fail("competing bots") } catch (e: IllegalArgumentException) { assertTrue(e.message!!.contains("bot-1")) }
        gate.saveRule(conv, "bot-2", Mode.AUTO, enabled = false) // configuring a second bot is fine; activating is not
        gate.saveRule(conv, "bot-1", Mode.AUTO, enabled = false)
        gate.saveRule(conv, "bot-2", Mode.AUTO, enabled = true)
        assertEquals("bot-2", gate.activeRule(conv)!!.botRef)
    }

    @Test fun theEmergencyStopBlocksEverythingAndCancelsQueuedWorkUntilTheOwnerResumes() {
        gate.saveRule(conv, "bot-1", Mode.AUTO, enabled = true)
        val m = incoming()
        val queued = outbox.enqueueReply(m, "bot-1")
        assertEquals(1, gate.killAll())
        assertEquals(OutState.CANCELLED, outbox.get(queued.uid)!!.state)
        assertTrue(gate.evaluate(incoming("again"), now, ZoneOffset.UTC) is GateDecision.Skip)
        assertTrue(gate.killed)
        gate.resume()
        assertTrue(gate.evaluate(incoming("once more"), now, ZoneOffset.UTC) is GateDecision.AutoSend)
    }

    @Test fun operatingHoursIncludingAWindowThatWrapsMidnight() {
        gate.saveRule(conv, "bot-1", Mode.AUTO, enabled = true, hoursStart = 22, hoursEnd = 6)
        now = Fx.BASE // 12:00 UTC: outside 22-06
        assertTrue(gate.evaluate(incoming(at = now), now, ZoneOffset.UTC) is GateDecision.Skip)
        now = Fx.BASE + 11 * 3_600_000 // 23:00 UTC
        assertTrue(gate.evaluate(incoming("late", now), now, ZoneOffset.UTC) is GateDecision.AutoSend)
        now = Fx.BASE + 17 * 3_600_000 // 05:00 UTC next day
        assertTrue(gate.evaluate(incoming("early", now), now, ZoneOffset.UTC) is GateDecision.AutoSend)
        assertTrue(AutomationGate.withinHours(Fx.BASE, ZoneOffset.UTC, 5, 5))
    }

    @Test fun anExpiredAuthorisationStopsWorkingAtOnce() {
        gate.saveRule(conv, "bot-1", Mode.AUTO, enabled = true, expires = now + 1000)
        assertTrue(gate.evaluate(incoming(), now, ZoneOffset.UTC) is GateDecision.AutoSend)
        now += 2000
        assertTrue(gate.evaluate(incoming("later"), now, ZoneOffset.UTC) is GateDecision.Skip)
    }

    @Test fun theSameTriggerIsHandledOnceNoMatterHowOftenItIsReplayed() {
        gate.saveRule(conv, "bot-1", Mode.APPROVAL, enabled = true)
        val m = incoming()
        val first = outbox.enqueueReply(m, "bot-1")
        val second = outbox.enqueueReply(m, "bot-1")
        assertEquals(first.uid, second.uid)
        assertEquals(1, outbox.list(conv).size)
        assertTrue(gate.evaluate(m, now, ZoneOffset.UTC) is GateDecision.Skip)
        assertNotEquals("a different bot is a different key", first.uid, outbox.enqueueReply(m, "bot-2").uid)
    }

    @Test fun theHourlyLimitStopsAFlurryOfAutomaticReplies() {
        gate.saveRule(conv, "bot-1", Mode.AUTO, enabled = true, maxPerHour = 2, minGapMs = 0)
        sendOne(incoming("one")); now += 1000
        sendOne(incoming("two")); now += 1000
        val third = gate.evaluate(incoming("three"), now, ZoneOffset.UTC)
        assertTrue(third is GateDecision.Skip && third.reason.contains("Hourly"))
        now += 3_700_000
        assertTrue(gate.evaluate(incoming("an hour later"), now, ZoneOffset.UTC) is GateDecision.AutoSend)
    }

    @Test fun aMinimumGapDefersInsteadOfDropping() {
        gate.saveRule(conv, "bot-1", Mode.AUTO, enabled = true, minGapMs = 60_000)
        sendOne(incoming("one")); now += 10_000
        val d = gate.evaluate(incoming("two"), now, ZoneOffset.UTC)
        assertTrue(d is GateDecision.Defer)
        assertEquals(50_000L, (d as GateDecision.Defer).untilMs - now)
    }

    @Test fun consecutiveAutomaticRepliesWithoutTheOwnerStopTheLoop() {
        gate.saveRule(conv, "bot-1", Mode.AUTO, enabled = true, maxConsecutive = 2, minGapMs = 0, maxPerHour = 50)
        owner("hi it's me")
        owner("auto 1", Author.BOT_AUTO); owner("auto 2", Author.BOT_AUTO)
        val d = gate.evaluate(incoming("still talking"), now, ZoneOffset.UTC)
        assertTrue(d is GateDecision.Skip && d.reason.contains("loop"))
        owner("I'm back")
        assertTrue("a human message resets the count", gate.evaluate(incoming("welcome back"), now, ZoneOffset.UTC) is GateDecision.AutoSend)
    }

    @Test fun anotherAutoResponderIsNeverAnswered() {
        gate.saveRule(conv, "bot-1", Mode.AUTO, enabled = true)
        for (t in listOf("This is an automated message. We will reply soon.", "Auto-reply: I'm out of office", "Do not reply to this number")) {
            assertTrue(t, gate.evaluate(incoming(t), now, ZoneOffset.UTC) is GateDecision.Skip)
        }
    }

    @Test fun aFloodOfIncomingMessagesPausesTheBot() {
        gate.saveRule(conv, "bot-1", Mode.AUTO, enabled = true)
        repeat(12) { incoming("spam $it") }
        assertTrue(gate.evaluate(incoming("one more"), now, ZoneOffset.UTC) is GateDecision.Skip)
    }

    @Test fun disclosureIsRequiredOncePerDay() {
        gate.saveRule(conv, "bot-1", Mode.AUTO, enabled = true)
        assertEquals(GateDecision.AutoSend(true), gate.evaluate(incoming("a"), now, ZoneOffset.UTC))
        gate.markDisclosed(conv, now)
        now += 3_600_000
        assertEquals(GateDecision.AutoSend(false), gate.evaluate(incoming("b"), now, ZoneOffset.UTC))
        now += 25L * 3_600_000
        assertEquals(GateDecision.AutoSend(true), gate.evaluate(incoming("c"), now, ZoneOffset.UTC))
    }

    @Test fun onlyOneWorkerCanEverStartSendingAnEntry() {
        val e = outbox.enqueueReply(incoming(), "bot-1")
        assertFalse("no body, nothing to send", outbox.markSending(e.uid))
        outbox.setBody(e.uid, "on my way")
        assertTrue(outbox.markSending(e.uid))
        assertFalse("a second worker must lose", outbox.markSending(e.uid))
    }

    @Test fun anInterruptedSendBecomesUnknownAndIsNeverResentAutomatically() {
        val e = outbox.enqueueReply(incoming(), "bot-1")
        outbox.setBody(e.uid, "on my way")
        assertTrue(outbox.markSending(e.uid))
        assertEquals(1, outbox.recoverAfterRestart())
        val after = outbox.get(e.uid)!!
        assertEquals(OutState.UNKNOWN, after.state)
        assertFalse("never auto-resend an uncertain send", outbox.markSending(e.uid))
        assertFalse("not even by walking the state machine back", outbox.transition(e.uid, OutState.QUEUED))
        assertTrue("only a provider report may settle it", outbox.transition(e.uid, OutState.DELIVERED, "provider confirmed"))
    }

    @Test fun generationThatNeverFinishedIsRequeuedAfterARestart() {
        val e = outbox.enqueueReply(incoming(), "bot-1")
        assertTrue(outbox.transition(e.uid, OutState.GENERATING))
        outbox.recoverAfterRestart()
        assertEquals(OutState.QUEUED, outbox.get(e.uid)!!.state)
    }

    @Test fun theStateMachineRejectsImpossibleMoves() {
        val e = outbox.enqueueReply(incoming(), "bot-1")
        assertFalse(outbox.transition(e.uid, OutState.DELIVERED))
        assertFalse(outbox.transition(e.uid, OutState.SENT))
        assertTrue(outbox.transition(e.uid, OutState.AWAITING_APPROVAL))
        assertTrue(outbox.transition(e.uid, OutState.CANCELLED))
        assertFalse("terminal states are final", outbox.transition(e.uid, OutState.QUEUED))
    }

    @Test fun aLiveMessageIsRecordedOnceEvenIfTheProviderRedelivers() {
        val first = a.recordLive(conv, Direction.IN, "p:5550100101", "Sam", "ping", now, "sms-777")
        val again = a.recordLive(conv, Direction.IN, "p:5550100101", "Sam", "ping", now, "sms-777")
        assertEquals(first, again)
        assertEquals(1L, a.messageCount(conv))
        assertEquals("live", a.message(first)!!.origin)
    }
}
