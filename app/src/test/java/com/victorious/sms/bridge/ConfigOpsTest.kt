package com.victorious.sms.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigOpsTest {
    private val now = 1_800_000_000_000L
    private val cur = mapOf<String, Any?>("assistant.llmUrl" to "http://a", "assistant.llmModel" to "m")

    @Test fun schemaExcludesSecretsAndSwitches() {
        val f = ConfigSchema.filter(mapOf("assistant.llmUrl" to "http://b", "remote.command.enabled" to true,
            "externalControlEnabled" to true, "lookup.online" to true, "unknown" to "x", "assistant.ttsUrl" to 5))
        assertEquals(mapOf("assistant.llmUrl" to "http://b"), f)
        ConfigSchema.types.keys.forEach { assertFalse(it, ForbiddenKeyScanner.isForbidden(it)) }
    }

    @Test fun prepareComputesChangesOnly() {
        val r = ConfigOps.prepare(cur, mapOf("assistant.llmUrl" to "http://b", "assistant.llmModel" to "m",
            "remote.command.enabled" to true), ConflictPolicy.OVERWRITE) as ConfigOps.Prepared.Plan
        assertEquals(mapOf("assistant.llmUrl" to "http://b"), r.changes)
    }

    @Test fun prepareRejectsSecrets() {
        assertEquals(ConfigOps.Prepared.Rejected("invalid_parameters"),
            ConfigOps.prepare(cur, mapOf("codeWord" to "x"), ConflictPolicy.OVERWRITE))
        assertEquals(ConfigOps.Prepared.Rejected("invalid_parameters"), ConfigOps.prepare(cur, "nope", ConflictPolicy.SKIP))
    }

    @Test fun importNeedsDeviceApproval() {
        var notified = 0
        val p = ConfigOps.submitInternal("com.dca", mapOf("assistant.llmUrl" to "http://b"), 120_000L, now) { notified++ }!!
        assertEquals(1, notified)
        assertEquals(ImportState.PENDING, ConfigOps.status("com.dca", p.importId, now))
        assertNull(ConfigOps.status("com.other", p.importId, now))
        val applied = mutableMapOf<String, Any?>()
        assertEquals(ImportState.APPLIED, ConfigOps.decide(p.importId, true, now + 1) { applied.putAll(it) })
        assertEquals(mapOf("assistant.llmUrl" to "http://b"), applied)
        // second decision does nothing
        assertEquals(ImportState.APPLIED, ConfigOps.decide(p.importId, true, now + 2) { applied["x"] = 1 })
        assertFalse(applied.containsKey("x"))
    }

    @Test fun deniedAndExpiredApplyNothing() {
        val a = ConfigOps.submitInternal("c", mapOf("assistant.llmUrl" to "http://z"), 1_000L, now) {}!!
        val b = ConfigOps.submitInternal("c", mapOf("assistant.llmUrl" to "http://z"), 1_000L, now) {}!!
        var called = false
        assertEquals(ImportState.DENIED, ConfigOps.decide(a.importId, false, now) { called = true })
        assertEquals(ImportState.EXPIRED, ConfigOps.decide(b.importId, true, now + 1_001L) { called = true })
        assertFalse(called)
        assertNull(ConfigOps.submitInternal("c", emptyMap(), 1_000L, now) {})
    }

    @Test fun applyRefiltersProtectedKeys() {
        val p = ConfigOps.submitInternal("c", mapOf("assistant.llmUrl" to "http://b", "remote.command.enabled" to true), 60_000L, now) {}!!
        val applied = mutableMapOf<String, Any?>()
        ConfigOps.decide(p.importId, true, now) { applied.putAll(it) }
        assertTrue(applied.keys == setOf("assistant.llmUrl"))
    }

    @Test fun exportHasNoSecrets() {
        val j = ConfigOps.export("com.victorious.sms", mapOf("assistant.llmUrl" to "http://a", "codeWord" to "x"))
        assertEquals("com.victorious.sms", j.getString("packageName"))
        assertFalse(j.toString().contains("codeWord"))
    }
}
