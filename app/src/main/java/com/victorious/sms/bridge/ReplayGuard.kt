package com.victorious.sms.bridge

import java.util.concurrent.ConcurrentHashMap

/** Shared fleet-envelope checks: 128-bit hex nonce (single use), timestampMs + ttlMs window. */
object ReplayGuard {
    const val DEFAULT_TTL_MS = 120_000L
    const val MAX_TTL_MS = 600_000L
    private val NONCE = Regex("^[0-9a-fA-F]{32}$")
    private val seen = ConcurrentHashMap<String, Long>()

    /** Returns null if OK (and consumes the nonce), else an error code. */
    @Synchronized
    fun check(nonce: String, timestampMs: Long, ttlMs: Long?, nowMs: Long = System.currentTimeMillis()): String? {
        seen.entries.removeIf { nowMs > it.value + MAX_TTL_MS }
        if (!NONCE.matches(nonce)) return "invalid_nonce"
        val ttl = effectiveTtl(ttlMs)
        if (timestampMs > nowMs + 30_000 || nowMs > timestampMs + ttl) return "expired"
        if (seen.putIfAbsent(nonce.lowercase(), timestampMs + ttl) != null) return "replayed_nonce"
        return null
    }

    fun effectiveTtl(ttlMs: Long?) = (ttlMs ?: DEFAULT_TTL_MS).coerceIn(1_000L, MAX_TTL_MS)
}
