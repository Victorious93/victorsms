package com.victorious.sms.bridge

import android.content.Context
import java.util.concurrent.ConcurrentHashMap

/**
 * Shared fleet-envelope checks: 128-bit hex nonce (single use), timestampMs + ttlMs window.
 * Used nonces are persisted with their expiry (via [PendingStore]) so replay protection survives a
 * process restart. Nonces are random and carry no secrets; they are still never logged.
 */
object ReplayGuard {
    const val DEFAULT_TTL_MS = 120_000L
    const val MAX_TTL_MS = 600_000L
    private val NONCE = Regex("^[0-9a-fA-F]{32}$")
    private val seen = ConcurrentHashMap<String, Long>()
    @Volatile private var store: PendingStore? = null

    /** Attach the durable nonce store once (idempotent). Falls back to memory if the Keystore fails. */
    fun ensureLoaded(context: Context) {
        if (store != null) return
        synchronized(this) {
            if (store != null) return
            attachStore(runCatching<PendingStore> { EncryptedPendingStore(context.applicationContext, "used_nonces") }
                .getOrElse { MemoryPendingStore() })
        }
    }

    /** Test seam: replace the store and reload unexpired nonces from it. */
    @Synchronized
    internal fun attachStore(st: PendingStore, nowMs: Long = System.currentTimeMillis()) {
        seen.clear()
        st.loadAll().forEach { (n, v) ->
            val exp = v.toLongOrNull()
            if (exp == null || !NONCE.matches(n) || nowMs > exp + MAX_TTL_MS) st.remove(n) else seen[n] = exp
        }
        store = st
    }

    /** Returns null if OK (and consumes the nonce), else an error code. */
    @Synchronized
    fun check(nonce: String, timestampMs: Long, ttlMs: Long?, nowMs: Long = System.currentTimeMillis()): String? {
        seen.entries.filter { nowMs > it.value + MAX_TTL_MS }.forEach { seen.remove(it.key); store?.remove(it.key) }
        if (!NONCE.matches(nonce)) return "invalid_nonce"
        val ttl = effectiveTtl(ttlMs)
        if (timestampMs > nowMs + 30_000 || nowMs > timestampMs + ttl) return "expired"
        val key = nonce.lowercase()
        if (seen.putIfAbsent(key, timestampMs + ttl) != null) return "replayed_nonce"
        store?.put(key, (timestampMs + ttl).toString())
        return null
    }

    fun effectiveTtl(ttlMs: Long?) = (ttlMs ?: DEFAULT_TTL_MS).coerceIn(1_000L, MAX_TTL_MS)
}
