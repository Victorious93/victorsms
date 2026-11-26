package com.victorious.sms.bridge

import com.victorious.sms.fleet.FleetEnvelope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class PairingPolicyTest {
    private val cert = "AA:BB:CC"
    private val id = CallerIdentity("ai.droidcommand.app", cert)
    private val approved = PairedApp("ai.droidcommand.app", cert, true, setOf(OpGroup.READ, OpGroup.SEND))

    @Test fun externalControlOffDeniesEverything() {
        val o = PairingPolicy.decide(false, listOf(id), listOf(approved), "sms.status")
        assertEquals(AuthDecision.Denied("external_control_disabled"), o.decision)
        assertTrue(o.toRecordPending.isEmpty())
    }

    @Test fun packageAndCertMustBothMatch() {
        val otherCert = CallerIdentity("ai.droidcommand.app", "DE:AD")
        val o = PairingPolicy.decide(true, listOf(otherCert), listOf(approved), "sms.status")
        assertEquals(AuthDecision.Denied("not_paired"), o.decision)
        assertEquals(listOf(otherCert), o.toRecordPending)
        val otherPkg = CallerIdentity("evil.app", cert)
        assertEquals(AuthDecision.Denied("not_paired"), PairingPolicy.decide(true, listOf(otherPkg), listOf(approved), "sms.status").decision)
    }

    @Test fun unapprovedOrUnknownIsDenied() {
        assertEquals(AuthDecision.Denied("not_paired"),
            PairingPolicy.decide(true, listOf(id), listOf(approved.copy(approved = false)), "sms.status").decision)
        assertEquals(AuthDecision.Denied("not_paired"), PairingPolicy.decide(true, emptyList(), listOf(approved), "sms.status").decision)
    }

    @Test fun groupsGateOps() {
        val readOnly = approved.copy(groups = setOf(OpGroup.READ))
        assertEquals(AuthDecision.Denied("permission_denied"), PairingPolicy.decide(true, listOf(id), listOf(readOnly), "sms.send").decision)
        assertEquals(AuthDecision.Denied("unsupported_operation"), PairingPolicy.decide(true, listOf(id), listOf(approved), "shell.exec").decision)
    }

    @Test fun smsSendAlwaysNeedsConfirm() {
        val d = PairingPolicy.decide(true, listOf(id), listOf(approved), "sms.send").decision as AuthDecision.Allowed
        assertTrue(d.needsUserConfirm)
        val r = PairingPolicy.decide(true, listOf(id), listOf(approved), "sms.status").decision as AuthDecision.Allowed
        assertFalse(r.needsUserConfirm)
    }

    @Test fun archiveReadIsItsOwnGroupAndNeverImpliedByReadOrSend() {
        val ops = com.victorious.comms.ArchiveWire.OPS
        assertTrue(ops.all { opGroupOf(it) == OpGroup.ARCHIVE_READ })
        assertEquals(null, opGroupOf("comms.send_everything"))
        ops.forEach { op ->
            assertEquals(op, AuthDecision.Denied("permission_denied"), PairingPolicy.decide(true, listOf(id), listOf(approved), op).decision)
        }
        val granted = approved.copy(groups = setOf(OpGroup.ARCHIVE_READ))
        ops.forEach { op ->
            val d = PairingPolicy.decide(true, listOf(id), listOf(granted), op).decision as AuthDecision.Allowed
            assertFalse("a read never waits for a tap", d.needsUserConfirm)
        }
        // The archive group does not open anything else.
        assertEquals(AuthDecision.Denied("permission_denied"), PairingPolicy.decide(true, listOf(id), listOf(granted), "sms.send").decision)
        assertEquals(AuthDecision.Denied("permission_denied"), PairingPolicy.decide(true, listOf(id), listOf(granted), "sms.status").decision)
    }

    @Test fun archiveOpsStillNeedExternalControlAndAnApprovedApp() {
        val granted = approved.copy(groups = setOf(OpGroup.ARCHIVE_READ))
        assertEquals(AuthDecision.Denied("external_control_disabled"), PairingPolicy.decide(false, listOf(id), listOf(granted), "comms.conversation.read").decision)
        assertEquals(AuthDecision.Denied("not_paired"), PairingPolicy.decide(true, listOf(id), listOf(granted.copy(approved = false)), "comms.conversation.read").decision)
        assertEquals(AuthDecision.Denied("not_paired"), PairingPolicy.decide(true, listOf(CallerIdentity("evil.app", cert)), listOf(granted), "comms.conversation.read").decision)
    }

    @Test fun aSavedConversationDraftRequiresBothDraftAndArchiveGrants() {
        val op = DraftCapability.OP_FROM_ARCHIVE
        for (group in listOf(OpGroup.DRAFT, OpGroup.ARCHIVE_READ)) {
            val partial = approved.copy(groups = setOf(group))
            assertEquals(AuthDecision.Denied("permission_denied"), PairingPolicy.decide(true, listOf(id), listOf(partial), op).decision)
        }
        val granted = approved.copy(groups = setOf(OpGroup.DRAFT, OpGroup.ARCHIVE_READ))
        assertTrue(PairingPolicy.decide(true, listOf(id), listOf(granted), op).decision is AuthDecision.Allowed)
    }

    @Test fun certFormatNormalized() =
        assertTrue(PairingPolicy.matches(approved, CallerIdentity("ai.droidcommand.app", "aabbcc")))
}

class ConfigBackupTest {
    @Test fun scannerFindsNestedSecrets() {
        assertEquals("codeWord", ForbiddenKeyScanner.findForbidden(mapOf("a" to mapOf("codeWord" to "x"))))
        assertEquals("sender_whitelist", ForbiddenKeyScanner.findForbidden(mapOf("l" to listOf(mapOf("sender_whitelist" to 1)))))
        for (k in listOf("matrix-token", "phoneNumber", "messageBody", "API_KEY", "twilioSid", "signingKey")) {
            assertTrue(k, ForbiddenKeyScanner.isForbidden(k))
        }
        assertNull(ForbiddenKeyScanner.findForbidden(mapOf("theme" to "neon", "list" to listOf(mapOf("id" to 1)))))
    }

    @Test fun exporterRefusesSecrets() {
        try { ConfigExporter().export("com.victorious.sms", mapOf("bridge" to mapOf("token" to "t"))); fail() }
        catch (e: ForbiddenKeyException) { assertEquals("token", e.key); assertFalse(e.message!!.contains("\"t\"")) }
    }

    @Test(expected = ForbiddenKeyException::class)
    fun importerRefusesSecrets() { ConfigImporter { true }.import(mutableMapOf(), mapOf("secret" to "x"), dryRun = true) }

    @Test fun importNeverEnablesRemoteCommand() {
        val cur = mutableMapOf<String, Any?>("remote.command.enabled" to false, "externalControlEnabled" to false)
        val incoming = mapOf("remote.command.enabled" to true, "externalControlEnabled" to true,
            "fleet" to mapOf("remote.command.enabled" to true, "x" to 1), "theme" to "neon")
        for (policy in ConflictPolicy.entries) {
            val c = HashMap(cur)
            ConfigImporter { true }.import(c, incoming, dryRun = false, policy = policy)
            assertEquals(false, c["remote.command.enabled"]); assertEquals(false, c["externalControlEnabled"])
            assertEquals(mapOf("x" to 1), c["fleet"])
        }
    }

    @Test fun importNeedsOnDeviceConfirmAndDryRunChangesNothing() {
        val c = mutableMapOf<String, Any?>()
        assertFalse(ConfigImporter { true }.import(c, mapOf("theme" to "neon"), dryRun = true).applied)
        assertFalse(ConfigImporter { false }.import(c, mapOf("theme" to "neon"), dryRun = false).applied)
        assertTrue(c.isEmpty())
    }

    @Test fun skipPolicyKeepsExisting() {
        val c = mutableMapOf<String, Any?>("theme" to "dark")
        val p = ConfigImporter { true }.import(c, mapOf("theme" to "neon", "font" to 2), dryRun = false)
        assertEquals(listOf("font"), p.changedKeys); assertEquals("dark", c["theme"])
    }

    @Test fun mergeById() {
        val c = mutableMapOf<String, Any?>("bots" to listOf(mapOf("id" to "a", "on" to true, "n" to 1), mapOf("id" to "b", "on" to false)))
        val p = ConfigImporter { true }.import(c,
            mapOf("bots" to listOf(mapOf("id" to "b", "on" to true), mapOf("id" to "c", "on" to false))),
            dryRun = false, policy = ConflictPolicy.MERGE_BY_ID)
        assertTrue(p.applied)
        assertEquals(listOf(mapOf("id" to "a", "on" to true, "n" to 1), mapOf("id" to "b", "on" to true), mapOf("id" to "c", "on" to false)), c["bots"])
    }

    @Test fun mergeByIdFallsBackToReplaceForPlainValues() {
        assertEquals(listOf(1, 2), ConfigImporter.mergeById(listOf(3), listOf(1, 2)))
        assertEquals(mapOf("a" to 1, "b" to 3), ConfigImporter.mergeById(mapOf("a" to 1, "b" to 2), mapOf("b" to 3)))
    }
}

class RemoteSmsApprovalTest {
    private val now = 1_800_000_000_000L
    private val caller = CallerIdentity("ai.droidcommand.app", "AA")
    private lateinit var store: MemoryPendingStore

    @Before fun setUp() { store = MemoryPendingStore(); RemoteSmsQueue.attachStore(store, now) }

    private fun submit(id: String, notified: MutableList<PendingSend> = mutableListOf()) =
        RemoteSmsQueue.submitInternal(caller, id, "+15551234567", "hi", FleetEnvelope.newNonce(), now, 120_000L, now) { notified += it }

    @Test fun submitOnlyQueuesAndNotifiesNeverSends() {
        val notified = mutableListOf<PendingSend>()
        assertEquals(RemoteSmsQueue.Result.Accepted("r1"), submit("r1", notified))
        assertEquals(1, notified.size)
        assertEquals(SendState.PENDING, RemoteSmsQueue.status(caller, "r1", now + 60_000))
        assertEquals(SendState.EXPIRED, RemoteSmsQueue.status(caller, "r1", now + 120_001))
    }

    @Test fun approvalSendsExactlyOnce() {
        submit("r2"); var sends = 0
        assertEquals(SendState.SENT, RemoteSmsQueue.decide("r2", now + 1) { sends++; SendState.SENT })
        assertEquals(SendState.SENT, RemoteSmsQueue.decide("r2", now + 2) { sends++; SendState.SENT })
        assertEquals(1, sends)
    }

    @Test fun expiredOrDeniedNeverSends() {
        submit("r3"); submit("r4")
        assertEquals(SendState.EXPIRED, RemoteSmsQueue.decide("r3", now + 120_001) { fail(); SendState.SENT })
        RemoteSmsQueue.denyInternal("r4")
        assertEquals(SendState.DENIED, RemoteSmsQueue.decide("r4", now + 1) { fail(); SendState.SENT })
    }

    @Test fun replayAndDuplicateRejected() {
        val n = FleetEnvelope.newNonce()
        RemoteSmsQueue.submitInternal(caller, "r5", "+15551234567", "hi", n, now, null, now) {}
        assertEquals(RemoteSmsQueue.Result.Rejected("replayed_nonce"),
            RemoteSmsQueue.submitInternal(caller, "r6", "+15551234567", "hi", n, now, null, now) {})
        assertEquals(RemoteSmsQueue.Result.Rejected("duplicate_request"), submit("r5"))
    }

    @Test fun statusOnlyForOriginalCaller() {
        submit("r7")
        assertNull(RemoteSmsQueue.status(CallerIdentity("ai.droidcommand.app", "BB"), "r7", now))
    }

    @Test fun pendingRequestsPersistAndExpire() {
        submit("r8")
        assertTrue(store.map.containsKey("r8"))
        RemoteSmsQueue.attachStore(store, now + 1) // simulated process restart
        assertEquals(SendState.PENDING, RemoteSmsQueue.get("r8")?.state)
        RemoteSmsQueue.attachStore(store, now + 120_000 + ReplayGuard.MAX_TTL_MS + 1)
        assertNull(RemoteSmsQueue.get("r8"))
        assertFalse(store.map.containsKey("r8"))
    }

    @Test fun codecRoundTrip() {
        val p = PendingSend("x", caller, "+1555", "body", 5L, SendState.DENIED)
        assertEquals(p.copy(), RemoteSmsQueue.decode(RemoteSmsQueue.encode(p)))
        assertNull(RemoteSmsQueue.decode("not json"))
    }
}
