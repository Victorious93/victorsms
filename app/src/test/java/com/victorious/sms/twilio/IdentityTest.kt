package com.victorious.sms.twilio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IdentityTest {
    private val a1 = "+15551230001"
    private val a2 = "+15551230002"
    private val aliases = setOf(a1, a2)

    @Test fun defaultsToSimWhenNothingSet() =
        assertEquals(Identity.Sim, IdentityResolver.resolve(null, null, aliases))

    @Test fun usesDefaultWhenNoThreadChoice() =
        assertEquals(Identity.Alias(a1), IdentityResolver.resolve(null, Identity.Alias(a1), aliases))

    @Test fun threadChoiceOverridesDefault() {
        assertEquals(Identity.Alias(a2), IdentityResolver.resolve(Identity.Alias(a2), Identity.Alias(a1), aliases))
        assertEquals(Identity.Sim, IdentityResolver.resolve(Identity.Sim, Identity.Alias(a1), aliases))
    }

    @Test fun unknownAliasFallsBackToDefaultThenSim() {
        assertEquals(Identity.Alias(a1), IdentityResolver.resolve(Identity.Alias("+15559999999"), Identity.Alias(a1), aliases))
        assertEquals(Identity.Sim, IdentityResolver.resolve(Identity.Alias("+15559999999"), Identity.Alias("+15558888888"), aliases))
        assertEquals(Identity.Sim, IdentityResolver.resolve(Identity.Alias(a1), null, emptySet()))
    }

    @Test fun aliasMatchIgnoresFormatting() =
        assertEquals(Identity.Alias(a1), IdentityResolver.resolve(Identity.Alias("+1 (555) 123-0001"), null, aliases))

    @Test fun encodeDecodeRoundTrip() {
        assertEquals(Identity.Sim, Identity.decode(Identity.Sim.encode()))
        assertEquals(Identity.Alias(a1), Identity.decode(Identity.Alias(a1).encode()))
        assertNull(Identity.decode("garbage")); assertNull(Identity.decode("alias:")); assertNull(Identity.decode(null))
    }

    @Test fun labelMasksAllButLastFour() {
        assertEquals("Alias …0001", IdentityResolver.label(Identity.Alias(a1)))
        assertFalse(IdentityResolver.label(Identity.Alias(a1)).contains("555"))
    }
}

class FromNumberGuardTest {
    private val owned = listOf("+15551230001", "+15551230002")

    @Test fun ownedNumberAccepted() {
        assertEquals("+15551230001", FromNumberGuard.check("+15551230001", owned).getOrThrow())
        assertEquals("+15551230002", FromNumberGuard.check("+1 555-123-0002", owned).getOrThrow())
    }

    @Test fun anythingElseRejected() {
        listOf("+15559999999", "15551230001", "", "+1", "abc", "+15551230001;+15559999999").forEach {
            val r = FromNumberGuard.check(it, owned)
            assertTrue("should reject", r.isFailure)
            assertTrue(r.exceptionOrNull() is NotOwnedNumberException)
        }
    }

    @Test fun emptyOwnedListRejectsEverything() =
        assertTrue(FromNumberGuard.check("+15551230001", emptyList()).isFailure)

    @Test fun twimlEscapesAndUsesAliasAsCallerId() {
        val x = TwiML.bridge("+15551230001", "+15550000000\"><Hangup/>")
        assertTrue(x.contains("callerId=\"+15551230001\""))
        assertFalse(x.contains("<Hangup/>"))
    }
}
