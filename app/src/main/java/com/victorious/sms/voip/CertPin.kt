package com.victorious.sms.voip

import java.io.IOException
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SNIHostName
import javax.net.ssl.SNIServerName
import javax.net.ssl.X509TrustManager

/**
 * Server certificate pinning for the SIP edition. Pure JVM (no Linphone), so it is unit-tested and sits in the shared sources;
 * the GPLv3 engine only calls it.
 *
 * What is pinned: the SHA-256 the owner provisioned in [SipConfig.serverCertSha256]. A certificate matches when the pin equals
 * the SHA-256 of its DER encoding (what `openssl x509 -fingerprint -sha256` prints) OR of its SubjectPublicKeyInfo (survives
 * certificate renewal with the same key). Any certificate in the chain the server presents may carry the pin.
 *
 * Fails closed: no chain, no match, a malformed pin, a handshake error or a timeout all mean "do not register". A pin match is
 * the ONLY thing that allows a self-signed server certificate; there is no "trust on first use" and no fallback to the system
 * trust store. Nothing here logs; failure reasons are fixed strings that never contain the pin, hostname or certificate bytes.
 */
object CertPin {
    private val HEX64 = Regex("^[0-9a-f]{64}$")

    fun normalizePin(pin: String): String? = pin.replace(":", "").trim().lowercase().takeIf { HEX64.matches(it) }

    private fun sha256(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b)

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    /** Both fingerprints of [c], lowercase hex: certificate (DER), then public key (SPKI). */
    fun fingerprints(c: X509Certificate): List<String> = listOf(hex(sha256(c.encoded)), hex(sha256(c.publicKey.encoded)))

    /** Index of the first certificate in [chain] that carries [pin], or -1. Constant-time comparison. */
    fun matchIndex(pin: String, chain: List<X509Certificate>): Int {
        val want = normalizePin(pin)?.toByteArray() ?: return -1
        chain.forEachIndexed { i, c ->
            val ok = runCatching { fingerprints(c).fold(false) { acc, f -> MessageDigest.isEqual(f.toByteArray(), want) or acc } }.getOrDefault(false)
            if (ok) return i
        }
        return -1
    }

    sealed class Verdict {
        /** [trustPem] is the presented chain in PEM: give it to the engine as its ONLY trusted roots, so the engine itself rejects any other certificate on the real connection. */
        data class Pinned(val trustPem: String) : Verdict()
        data class Refused(val reason: String) : Verdict()
    }

    fun verify(pin: String, chain: List<X509Certificate>): Verdict {
        if (normalizePin(pin) == null) return Verdict.Refused("the certificate pin is not valid")
        if (chain.isEmpty()) return Verdict.Refused("the server presented no certificate")
        if (matchIndex(pin, chain) < 0) return Verdict.Refused("the server certificate does not match the pin")
        return Verdict.Pinned(toPem(chain))
    }

    fun toPem(chain: List<X509Certificate>): String = chain.joinToString("") {
        "-----BEGIN CERTIFICATE-----\n" + java.util.Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(it.encoded) + "\n-----END CERTIFICATE-----\n"
    }

    /**
     * Reads the certificate chain the server presents. A trust-everything manager is used here ONLY to read the certificates;
     * no application data is sent, and the result is never used until [verify] has matched it against the pin. Blocking:
     * call off the main thread.
     */
    fun fetchChain(host: String, port: Int, timeoutMs: Int = 8_000): Result<List<X509Certificate>> = try {
        val readOnly = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = throw CertificateException("client certificates are not used")
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}   // matched against the pin afterwards
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val ctx = SSLContext.getInstance("TLS").apply { init(null, arrayOf(readOnly), null) }
        val raw = java.net.Socket()
        raw.connect(java.net.InetSocketAddress(host, port), timeoutMs)
        raw.soTimeout = timeoutMs
        val s = ctx.socketFactory.createSocket(raw, host, port, true) as SSLSocket
        s.use {
            val p = it.sslParameters
            p.protocols = it.supportedProtocols.filter { v -> v == "TLSv1.3" || v == "TLSv1.2" }.toTypedArray()
            if (!host.all { c -> c.isDigit() || c == '.' }) p.serverNames = listOf<SNIServerName>(SNIHostName(host))
            it.sslParameters = p
            it.startHandshake()
            Result.success(it.session.peerCertificates.filterIsInstance<X509Certificate>())
        }
    } catch (e: IOException) {
        Result.failure(IOException("could not reach the server over TLS"))
    } catch (e: Exception) {
        Result.failure(IOException("TLS handshake failed"))
    }

    /** Connect, read the presented chain, check it against [pin]. Never throws. */
    fun check(host: String, port: Int, pin: String, timeoutMs: Int = 8_000): Verdict {
        if (normalizePin(pin) == null) return Verdict.Refused("the certificate pin is not valid")
        val chain = fetchChain(host, port, timeoutMs).getOrElse { return Verdict.Refused(it.message ?: "TLS failed") }
        return verify(pin, chain)
    }
}
