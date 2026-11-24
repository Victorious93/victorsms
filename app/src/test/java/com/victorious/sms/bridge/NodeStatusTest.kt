package com.victorious.sms.bridge

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Victorious Framework: the status and capabilities reads droidcommand-AI's fleet screens ask for. They used to pass
 * authorization and then fall through to `not_implemented`; the authorizer and the dispatcher now share one definition.
 * The dispatcher itself is not run here (it is a bound service).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NodeStatusTest {
    private val cert = "AA:BB:CC"
    private val id = CallerIdentity("ai.droidcommand.app", cert)
    private fun app(vararg groups: OpGroup) = PairedApp("ai.droidcommand.app", cert, true, groups.toSet())

    @Test fun everyOpTheDispatcherAnswersForStatusAndCapabilitiesIsAuthorizedAsAReadAndNothingElseIs() {
        val all = BridgeReads.STATUS + BridgeReads.CAPABILITIES
        assertEquals(setOf("node.status.get", "sms.status", "capabilities.get", "sms.capabilities.get"), all)
        all.forEach { assertEquals(it, OpGroup.READ, opGroupOf(it)) }
        // The names droidcommand-AI's fleet screens actually send.
        assertEquals(OpGroup.READ, opGroupOf("sms.status"))
        assertEquals(OpGroup.READ, opGroupOf("sms.capabilities.get"))
    }

    @Test fun everyAuthorizedStatusOrCapabilitiesReadIsActuallyAnswered() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        (BridgeReads.STATUS + BridgeReads.CAPABILITIES).forEach { op ->
            val r = BridgeReads.answer(ctx, "req-1", op)
            assertTrue("$op must be answered, not fall through to not_implemented", r != null && r.getBoolean("ok") && r.getString("id") == "req-1")
        }
        val caps = BridgeReads.answer(ctx, "r", "sms.capabilities.get")!!.getJSONObject("result").getJSONArray("ops")
        val names = (0 until caps.length()).map { caps.getJSONObject(it).getString("name") }
        assertTrue("node.status.get" in names && "sms.send" in names)
        assertEquals("both capability names answer identically", BridgeReads.answer(ctx, "r", "capabilities.get").toString(), BridgeReads.answer(ctx, "r", "sms.capabilities.get").toString())
        assertEquals("not a read this object owns", null, BridgeReads.answer(ctx, "r", "sms.send"))
        assertEquals(null, BridgeReads.answer(ctx, "r", "comms.conversation.read"))
    }

    @Test fun aReadGrantAllowsThemAndNoGrantDoesNot() {
        BridgeReads.STATUS.plus(BridgeReads.CAPABILITIES).forEach { op ->
            val ok = PairingPolicy.decide(true, listOf(id), listOf(app(OpGroup.READ)), op).decision as AuthDecision.Allowed
            assertFalse("a status read never waits for a tap: $op", ok.needsUserConfirm)
            assertEquals(op, AuthDecision.Denied("permission_denied"), PairingPolicy.decide(true, listOf(id), listOf(app(OpGroup.SEND)), op).decision)
            assertEquals(op, AuthDecision.Denied("external_control_disabled"), PairingPolicy.decide(false, listOf(id), listOf(app(OpGroup.READ)), op).decision)
        }
    }

    @Test fun theSnapshotIsStatesOnlyWithAClosedSetOfKeys() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val json = NodeStatusProvider(ctx, "victorsms-0123456789abcdef0123456789abcdef").snapshot(nowMs = 1_700_000_000_000L).toJson()
        assertEquals(setOf("nodeId", "packageName", "connectionClass", "updatedAtMs", "domains"), json.keys().asSequence().toSet())
        assertEquals("victorsms-0123456789abcdef0123456789abcdef", json.getString("nodeId"))
        assertEquals("EXTERNAL_NODE", json.getString("connectionClass"))
        assertEquals(1_700_000_000_000L, json.getLong("updatedAtMs"))
        val domains = json.getJSONArray("domains")
        val names = (0 until domains.length()).map { domains.getJSONObject(it).getString("name") }
        assertEquals(listOf("sms", "node_health"), names)
        for (i in 0 until domains.length()) {
            val d = domains.getJSONObject(i)
            assertTrue(d.keys().asSequence().all { it in setOf("name", "state", "detail") })
            assertTrue(d.getString("state") in setOf("ok", "degraded", "down"))
        }
    }

    @Test fun aPhoneThatIsNotTheDefaultSmsAppReportsDownNotOk() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val sms = NodeStatusProvider(ctx, "victorsms-x").snapshot().domains.first { it.name == "sms" }
        assertEquals("down", sms.state)
        assertEquals("not default SMS app", sms.detail)
    }
}
