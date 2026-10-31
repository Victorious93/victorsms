package com.victorious.sms.fleet

import com.victorious.sms.bridge.ReplayGuard
import org.json.JSONObject
import java.security.SecureRandom

/**
 * Frozen fleet envelope (targetNodeRef, actionRef, args, nonce, timestampMs, ttlMs, destructive).
 * Pure JVM code so it can be unit-tested; [DcaClient.envelope] delegates here.
 */
object FleetEnvelope {
    const val TTL_MS = 120_000L
    private val NONCE = Regex("^[0-9a-f]{32}$")
    private val rng = SecureRandom()

    fun newNonce(random: java.util.Random = rng): String =
        ByteArray(16).also { random.nextBytes(it) }.joinToString("") { "%02x".format(it) }

    fun build(
        targetNodeRef: String, actionRef: String, args: JSONObject = JSONObject(), destructive: Boolean = false,
        nowMs: Long = System.currentTimeMillis(), nonce: String = newNonce(),
    ): JSONObject = JSONObject().put("targetNodeRef", targetNodeRef).put("actionRef", actionRef).put("args", args)
        .put("nonce", nonce).put("timestampMs", nowMs).put("ttlMs", TTL_MS).put("destructive", destructive)

    /**
     * Structural validation of an envelope we built or received. Returns null if OK, else an error code.
     * Does not consume the nonce; use [ReplayGuard.check] for single-use enforcement.
     */
    fun validate(e: JSONObject, nowMs: Long = System.currentTimeMillis()): String? {
        if (e.optString("targetNodeRef").isBlank() || e.optString("actionRef").isBlank()) return "invalid_parameters"
        if (!NONCE.matches(e.optString("nonce"))) return "invalid_nonce"
        val ts = e.opt("timestampMs") as? Number ?: return "invalid_timestamp"
        val ttl = e.opt("ttlMs") as? Number ?: return "invalid_ttl"
        if (ttl.toLong() != TTL_MS) return "invalid_ttl"
        if (e.opt("destructive") !is Boolean) return "invalid_destructive"
        if (ts.toLong() > nowMs + 30_000 || nowMs > ts.toLong() + ttl.toLong()) return "expired"
        return null
    }
}
