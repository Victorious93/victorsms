package com.victorious.sms.relay

import com.victorious.sms.bridge.MemoryPendingStore
import com.victorious.sms.bridge.ReplayGuard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PeerPinTest {
    private val a = "ab".repeat(32)
    private val b = "cd".repeat(32)

    @Test fun matchesOnlySameFingerprint() {
        assertTrue(PeerPin.matches(a, a))
        assertTrue(PeerPin.matches(a, a.uppercase().chunked(2).joinToString(":")))
        assertFalse(PeerPin.matches(a, b))
        assertFalse(PeerPin.matches(null, a)); assertFalse(PeerPin.matches("", "")); assertFalse(PeerPin.matches("ab", "ab"))
    }

    @Test fun outboundRequiresPinExceptFirstHello() {
        assertTrue(PeerPin.acceptOutbound("relay.pair.hello", null, b))
        assertFalse(PeerPin.acceptOutbound("relay.sms.send", null, a))
        assertTrue(PeerPin.acceptOutbound("relay.sms.send", a, a))
        assertFalse(PeerPin.acceptOutbound("relay.sms.send", a, b)) // another (even paired) cert is refused
        assertFalse(PeerPin.acceptOutbound("relay.pair.hello", a, b))
    }

    @Test fun inbound() {
        assertTrue(PeerPin.acceptInbound(a, listOf(a), pairingOpen = false))
        assertFalse(PeerPin.acceptInbound(b, listOf(a), pairingOpen = false))
        assertTrue(PeerPin.acceptInbound(b, listOf(a), pairingOpen = true))
    }
}

class RemoteCallPersistenceTest {
    @Test fun persistsAndExpires() {
        val now = System.currentTimeMillis()
        val store = MemoryPendingStore()
        val c = PendingCall("c1", "Pixel", "ab".repeat(32), "+15551234567", now + 120_000)
        store.put("c1", RemoteCallQueue.encode(c))
        store.put("bad", "{}")
        RemoteCallQueue.attachStore(store, now)
        assertEquals(c, RemoteCallQueue.get("c1"))
        assertFalse(store.map.containsKey("bad"))
        RemoteCallQueue.attachStore(store, now + 120_000 + ReplayGuard.MAX_TTL_MS + 1)
        assertNull(RemoteCallQueue.get("c1"))
    }
}
