package com.victorious.sms.bridge

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class DraftHandoffTest {
    private val dca = CallerIdentity("ai.droidcommand.app", "AA")
    private fun p(id: String = "d1", ch: String = "sms") = JSONObject().put("draftId", id).put("channel", ch).put("to", JSONArray(listOf("+15550100"))).put("body", "hi").put("origin", "victorlinux:owner")
    private fun fresh(): DraftHandoff { DraftHandoff.attachStore(MemoryPendingStore()); return DraftHandoff }

    @Test fun deliveryNeverSends() { val h = fresh(); var sent = 0; var shown = 0
        assertTrue(h.deliver(dca, p("a"), 1000) { shown++ } is DraftHandoff.Result.Accepted); assertEquals(1, shown); assertEquals(0, sent)
        assertEquals(DraftState.RECEIVED, h.status(dca, "a", 1000)) }

    @Test fun retriedHandoffIsIdempotentAndNeverReShown() { val h = fresh(); var shown = 0
        h.deliver(dca, p("b"), 1000) { shown++ }; h.deliver(dca, p("b"), 2000) { shown++ }; h.deliver(dca, p("b"), 3000) { shown++ }; assertEquals(1, shown) }

    @Test fun ownerSendSubmitsAtMostOnce() { val h = fresh(); h.deliver(dca, p("c"), 1000) { }; var n = 0
        repeat(3) { h.approve("c", 1500) { n++; DraftState.SUBMITTED to null } }; assertEquals(1, n); assertEquals(DraftState.SUBMITTED, h.status(dca, "c", 1500)) }

    @Test fun declineSendsNothingAndBlocksALaterApprove() { val h = fresh(); h.deliver(dca, p("d"), 1000) { }; h.decline("d"); var n = 0
        h.approve("d", 1500) { n++; DraftState.SUBMITTED to null }; assertEquals(0, n); assertEquals(DraftState.DECLINED, h.status(dca, "d", 1500)) }

    @Test fun expiredDraftCannotBeSent() { val h = fresh(); h.deliver(dca, p("e").put("expiresMs", 2000), 1000) { }; var n = 0
        h.approve("e", 5000) { n++; DraftState.SUBMITTED to null }; assertEquals(0, n) }

    @Test fun failureIsReportedNotRetried() { val h = fresh(); h.deliver(dca, p("f"), 1000) { }; var n = 0
        assertEquals(DraftState.FAILED, h.approve("f", 1500) { n++; DraftState.FAILED to "no service" }); h.approve("f", 1600) { n++; DraftState.SUBMITTED to null }; assertEquals(1, n) }

    @Test fun invalidInputsRejected() { val h = fresh()
        for (bad in listOf(p("g").put("channel", "fax"), p("../x"), p("h").put("to", JSONArray()), p("i").put("subject", "a\nBcc: x"), p("j").put("to", JSONArray(listOf("a@b\r\nc")))))
            assertTrue(bad.toString(), h.deliver(dca, bad, 1000) { } is DraftHandoff.Result.Rejected) }

    @Test fun statusIsOnlyForTheOriginalCaller() { val h = fresh(); h.deliver(dca, p("k"), 1000) { }; assertNull(h.status(CallerIdentity("evil.app", "BB"), "k", 1000)) }

    @Test fun aDifferentSigningCertificateCannotReadOrReuseTheSameDraft() {
        val h = fresh()
        h.deliver(dca, p("k"), 1000) { }
        val differentCert = CallerIdentity(dca.packageName, "BB")
        assertNull(h.status(differentCert, "k", 1000))
        assertEquals(DraftHandoff.Result.Rejected("not_found"), h.deliver(differentCert, p("k"), 1000) { })
    }

    @Test fun anInterruptedSubmissionRecoversAsUnknownAndCannotSendAgain() {
        val store = MemoryPendingStore()
        DraftHandoff.attachStore(store)
        DraftHandoff.deliver(dca, p("crash"), 1000) { }
        val interruption = runCatching {
            DraftHandoff.approve("crash", 1100) { throw AssertionError("process interrupted") }
        }
        assertTrue(interruption.isFailure)
        DraftHandoff.attachStore(store, 1200)
        assertEquals(DraftState.UNKNOWN, DraftHandoff.status(dca, "crash", 1200))
        assertEquals(DraftState.UNKNOWN, DraftHandoff.approve("crash", 1300) {
            fail("An uncertain submission must not be sent again")
            DraftState.SUBMITTED to null
        })
    }

    @Test fun surviveProcessDeathViaStore() { val st = MemoryPendingStore(); DraftHandoff.attachStore(st); DraftHandoff.deliver(dca, p("m"), 1000) { }
        DraftHandoff.attachStore(st, 1500); assertEquals(DraftState.RECEIVED, DraftHandoff.status(dca, "m", 1500)) }
}
