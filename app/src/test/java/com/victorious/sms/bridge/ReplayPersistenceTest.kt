package com.victorious.sms.bridge

import com.victorious.sms.fleet.FleetEnvelope
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplayPersistenceTest {
    private val now = 1_800_000_000_000L

    @After fun reset() = ReplayGuard.attachStore(MemoryPendingStore(), now)

    @Test fun usedNonceSurvivesRestart() {
        val disk = MemoryPendingStore()
        ReplayGuard.attachStore(disk, now)
        val n = FleetEnvelope.newNonce()
        assertNull(ReplayGuard.check(n, now, 120_000L, now))
        assertTrue(disk.map.containsKey(n))
        // "restart": a fresh in-memory state loaded from the same store
        ReplayGuard.attachStore(MemoryPendingStore(), now)
        ReplayGuard.attachStore(disk, now + 1_000L)
        assertEquals("replayed_nonce", ReplayGuard.check(n, now, 120_000L, now + 1_000L))
    }

    @Test fun expiredNoncesArePrunedOnLoadAndCheck() {
        val disk = MemoryPendingStore()
        val old = FleetEnvelope.newNonce()
        disk.put(old, (now - ReplayGuard.MAX_TTL_MS - 1).toString())
        disk.put("garbage", "x")
        ReplayGuard.attachStore(disk, now)
        assertFalse(disk.map.containsKey(old)); assertFalse(disk.map.containsKey("garbage"))
        val n = FleetEnvelope.newNonce()
        assertNull(ReplayGuard.check(n, now, 1_000L, now))
        ReplayGuard.check(FleetEnvelope.newNonce(), now + 1_000L + ReplayGuard.MAX_TTL_MS + 1, 1_000L, now + 1_000L + ReplayGuard.MAX_TTL_MS + 1)
        assertFalse(disk.map.containsKey(n))
    }

    @Test fun rejectedNoncesAreNotStored() {
        val disk = MemoryPendingStore()
        ReplayGuard.attachStore(disk, now)
        ReplayGuard.check("bad", now, null, now)
        ReplayGuard.check(FleetEnvelope.newNonce(), now - 200_000L, null, now)
        assertTrue(disk.map.isEmpty())
    }
}
