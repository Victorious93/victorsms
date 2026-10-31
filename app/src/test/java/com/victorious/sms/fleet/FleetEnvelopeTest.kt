package com.victorious.sms.fleet

import com.victorious.sms.bridge.ReplayGuard
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FleetEnvelopeTest {
    private val now = 1_800_000_000_000L

    @Test fun buildHasFrozenFields() {
        val e = FleetEnvelope.build("controller", "act", nowMs = now)
        assertEquals(120_000L, e.getLong("ttlMs"))
        assertEquals(now, e.getLong("timestampMs"))
        assertFalse(e.getBoolean("destructive"))
        assertEquals(32, e.getString("nonce").length)
        assertNull(FleetEnvelope.validate(e, now))
    }

    @Test fun destructiveFlagCarried() {
        assertEquals(true, FleetEnvelope.build("c", "a", destructive = true, nowMs = now).getBoolean("destructive"))
    }

    @Test fun noncesAreFresh() = assertNotEquals(FleetEnvelope.newNonce(), FleetEnvelope.newNonce())

    @Test fun rejectsBadNonce() {
        assertEquals("invalid_nonce", FleetEnvelope.validate(FleetEnvelope.build("c", "a", nowMs = now, nonce = "abc"), now))
        assertEquals("invalid_nonce", FleetEnvelope.validate(FleetEnvelope.build("c", "a", nowMs = now, nonce = "z".repeat(32)), now))
    }

    @Test fun rejectsWrongTtl() {
        val e = FleetEnvelope.build("c", "a", nowMs = now).put("ttlMs", 600_000L)
        assertEquals("invalid_ttl", FleetEnvelope.validate(e, now))
        assertEquals("invalid_ttl", FleetEnvelope.validate(FleetEnvelope.build("c", "a", nowMs = now).apply { remove("ttlMs") }, now))
    }

    @Test fun rejectsMissingTimestampOrDestructive() {
        assertEquals("invalid_timestamp", FleetEnvelope.validate(FleetEnvelope.build("c", "a", nowMs = now).apply { remove("timestampMs") }, now))
        assertEquals("invalid_destructive", FleetEnvelope.validate(FleetEnvelope.build("c", "a", nowMs = now).put("destructive", "false"), now))
    }

    @Test fun expiryWindow() {
        val e = FleetEnvelope.build("c", "a", nowMs = now)
        assertNull(FleetEnvelope.validate(e, now + 120_000L))
        assertEquals("expired", FleetEnvelope.validate(e, now + 120_001L))
        assertEquals("expired", FleetEnvelope.validate(e, now - 30_001L)) // from the future
    }

    @Test fun rejectsMissingRefs() =
        assertEquals("invalid_parameters", FleetEnvelope.validate(JSONObject().put("actionRef", "a"), now))
}

class ReplayGuardTest {
    private val now = 1_800_000_000_000L
    private fun nonce() = FleetEnvelope.newNonce()

    @Test fun acceptsOnceThenReplay() {
        val n = nonce()
        assertNull(ReplayGuard.check(n, now, 120_000L, now))
        assertEquals("replayed_nonce", ReplayGuard.check(n, now, 120_000L, now))
        assertEquals("replayed_nonce", ReplayGuard.check(n.uppercase(), now, 120_000L, now))
    }

    @Test fun invalidNonce() {
        assertEquals("invalid_nonce", ReplayGuard.check("1234", now, null, now))
        assertEquals("invalid_nonce", ReplayGuard.check("", now, null, now))
    }

    @Test fun defaultTtlIs120s() {
        assertEquals(120_000L, ReplayGuard.effectiveTtl(null))
        assertEquals(ReplayGuard.MAX_TTL_MS, ReplayGuard.effectiveTtl(10_000_000L))
        assertEquals(1_000L, ReplayGuard.effectiveTtl(0L))
        assertEquals("expired", ReplayGuard.check(nonce(), now - 120_001L, null, now))
        assertNull(ReplayGuard.check(nonce(), now - 120_000L, null, now))
    }

    @Test fun futureTimestampRejected() {
        assertEquals("expired", ReplayGuard.check(nonce(), now + 30_001L, null, now))
        assertNull(ReplayGuard.check(nonce(), now + 30_000L, null, now))
    }
}

class SmsTriggerGateTest {
    private val wl = setOf("+15551234567")

    @Test fun requiresBothWhitelistAndCodeWord() {
        assertEquals(true, SmsTriggerGate.shouldForward(true, wl, "Zebra9", "+1 (555) 123-4567", "Zebra9 lights on"))
        assertFalse(SmsTriggerGate.shouldForward(true, wl, "Zebra9", "+15550000000", "Zebra9 lights on"))
        assertFalse(SmsTriggerGate.shouldForward(true, wl, "Zebra9", "+15551234567", "lights on"))
    }

    @Test fun offByDefaultAndEmptyNeverMatch() {
        assertFalse(SmsTriggerGate.shouldForward(false, wl, "Zebra9", "+15551234567", "Zebra9 x"))
        assertFalse(SmsTriggerGate.shouldForward(true, wl, "", "+15551234567", "anything"))
        assertFalse(SmsTriggerGate.shouldForward(true, emptySet(), "Zebra9", "+15551234567", "Zebra9"))
        assertFalse(SmsTriggerGate.shouldForward(true, setOf(""), "Zebra9", "", "Zebra9"))
    }

    @Test fun codeWordIsCaseSensitive() = assertFalse(SmsTriggerGate.matchesCodeWord("Zebra9", "zebra9 go"))

    @Test fun codeWordStrippedFromCommand() {
        val t = SmsTriggerGate.commandText("Zebra9", "Zebra9   lights   on")
        assertEquals("lights on", t)
        assertFalse(t.contains("Zebra9"))
    }
}
