package com.victorious.mail

import jakarta.mail.Session
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.util.Properties
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * TLS policy: system trust + hostname verification by default. The ONLY alternative is an owner-pinned certificate hash for one server
 * (self-signed lab/home servers): then exactly that certificate is accepted (identity = the pin) and any other certificate is refused.
 */
internal object Net {
    fun sslContext(pin: String?): SSLContext {
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).also { it.init(null as KeyStore?) }
        val system = tmf.trustManagers.filterIsInstance<X509TrustManager>().first()
        val tm = object : X509TrustManager {
            override fun checkClientTrusted(c: Array<X509Certificate>, a: String) = system.checkClientTrusted(c, a)
            override fun checkServerTrusted(chain: Array<X509Certificate>, a: String) {
                if (pin != null) {          // owner-pinned server: exactly this certificate, nothing else (the pin IS the identity)
                    if (chain.isNotEmpty() && chain[0].sha256Hex().equals(pin.replace(":", ""), true)) return
                    throw java.security.cert.CertificateException("server certificate does not match the pinned fingerprint")
                }
                system.checkServerTrusted(chain, a)
            }
            override fun getAcceptedIssuers(): Array<X509Certificate> = system.acceptedIssuers
        }
        return SSLContext.getInstance("TLS").apply { init(null, arrayOf<TrustManager>(tm), null) }
    }

    /** Jakarta Mail reads `mail.<protocol>.*` for the protocol actually used: imaps/smtps for implicit TLS, imap/smtp for STARTTLS. */
    fun props(base: String, c: ServerConfig, timeoutMs: Int = 20_000): Properties = Properties().apply {
        val proto = if (c.security == Security.SSL_TLS) base + "s" else base
        val sf = sslContext(c.pinnedCertSha256).socketFactory
        put("mail.$proto.host", c.host); put("mail.$proto.port", c.port.toString())
        put("mail.$proto.connectiontimeout", timeoutMs.toString()); put("mail.$proto.timeout", timeoutMs.toString()); put("mail.$proto.writetimeout", timeoutMs.toString())
        put("mail.$proto.ssl.checkserveridentity", if (c.pinnedCertSha256 == null) "true" else "false")   // pinned: identity = the pinned certificate
        put("mail.$proto.ssl.socketFactory", sf); put("mail.$proto.peek", "true")   // reading never marks messages as seen
        if (c.security == Security.SSL_TLS) put("mail.$proto.ssl.enable", "true")
        else { put("mail.$proto.starttls.enable", "true"); put("mail.$proto.starttls.required", "true") }   // never silently downgrade to plaintext
        if (c.auth is Auth.OAuth) { put("mail.$proto.auth.mechanisms", "XOAUTH2"); put("mail.$proto.sasl.enable", "false"); put("mail.$proto.auth.login.disable", "true"); put("mail.$proto.auth.plain.disable", "true") }
    }

    fun session(proto: String, c: ServerConfig): Session = Session.getInstance(props(proto, c))
    fun user(a: Auth) = when (a) { is Auth.Password -> a.username; is Auth.OAuth -> a.username }
    fun secret(a: Auth) = when (a) { is Auth.Password -> a.password; is Auth.OAuth -> a.token() }
}
