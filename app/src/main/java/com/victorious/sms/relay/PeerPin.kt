package com.victorious.sms.relay

import java.security.MessageDigest

/**
 * Certificate pinning for the phone relay. After pairing, every outbound connection is pinned to
 * that phone's cert SHA-256 and any other cert is refused, even if it belongs to another paired phone.
 */
object PeerPin {
    private val HEX64 = Regex("^[0-9a-f]{64}$")

    fun normalize(fp: String?): String? = fp?.replace(":", "")?.trim()?.lowercase()?.takeIf { HEX64.matches(it) }

    /** Constant-time comparison of two SHA-256 fingerprints. Malformed/empty input never matches. */
    fun matches(expected: String?, actual: String?): Boolean {
        val e = normalize(expected) ?: return false
        val a = normalize(actual) ?: return false
        return MessageDigest.isEqual(e.toByteArray(), a.toByteArray())
    }

    /**
     * Trust decision for an outbound TLS handshake. Pairing hello (no pin yet) accepts any cert, because
     * pairing is confirmed afterwards by the 6-digit code. Every other op requires [pinnedFp] to match.
     */
    fun acceptOutbound(op: String, pinnedFp: String?, actualFp: String): Boolean =
        if (op == "relay.pair.hello" && pinnedFp == null) true else matches(pinnedFp, actualFp)

    /** Server-side TLS: paired certs always; unknown certs only during the pairing window (hello only). */
    fun acceptInbound(actualFp: String, pairedFps: Collection<String>, pairingOpen: Boolean): Boolean =
        pairedFps.any { matches(it, actualFp) } || pairingOpen
}
