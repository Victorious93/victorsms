package com.victorious.sms.relay

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.util.Date
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import javax.security.auth.x500.X500Principal

/**
 * Device identity for phone-to-phone relay: an EC P-256 key generated in the Android Keystore
 * (non-exportable) with a self-signed certificate. Peers are pinned by certificate SHA-256.
 * Transport: mutual TLS 1.3 using only java.security / javax.net.ssl (no extra libraries).
 */
object RelayCrypto {
    private const val ALIAS = "victorsms_relay_identity"

    private fun keyStore(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    fun ensureIdentity() {
        val ks = keyStore()
        if (ks.containsAlias(ALIAS)) return
        val gen = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
        gen.initialize(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_NONE)
                .setCertificateSubject(X500Principal("CN=VictorSMS relay"))
                .setCertificateSerialNumber(BigInteger(64, SecureRandom()))
                .setCertificateNotBefore(Date(System.currentTimeMillis() - 86_400_000L))
                .setCertificateNotAfter(Date(System.currentTimeMillis() + 20L * 365 * 86_400_000L))
                .build(),
        )
        gen.generateKeyPair()
    }

    fun myCertificate(): X509Certificate { ensureIdentity(); return keyStore().getCertificate(ALIAS) as X509Certificate }

    fun fingerprint(cert: X509Certificate): String =
        MessageDigest.getInstance("SHA-256").digest(cert.encoded).joinToString("") { "%02x".format(it) }

    fun myFingerprint(): String = fingerprint(myCertificate())

    /** 6-digit comparison code both phones show during pairing (order-independent). */
    fun pairingCode(fpA: String, fpB: String): String {
        val (x, y) = listOf(fpA, fpB).sorted()
        val h = MessageDigest.getInstance("SHA-256").digest("victorsms-pair|$x|$y".toByteArray())
        val n = ((h[0].toInt() and 0xff) shl 16) or ((h[1].toInt() and 0xff) shl 8) or (h[2].toInt() and 0xff)
        return "%06d".format(n % 1_000_000)
    }

    /**
     * TLS context presenting our Keystore identity. [accept] decides on the peer cert fingerprint
     * (pinned peers, or any cert while pairing mode is active; pairing still needs code confirmation).
     */
    fun sslContext(accept: (String) -> Boolean): SSLContext {
        ensureIdentity()
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(keyStore(), null) }
        val tm = object : X509TrustManager {
            private fun check(chain: Array<out X509Certificate>?) {
                val leaf = chain?.firstOrNull() ?: throw java.security.cert.CertificateException("no cert")
                leaf.checkValidity()
                if (!accept(fingerprint(leaf))) throw java.security.cert.CertificateException("peer not paired")
            }
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = check(chain)
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = check(chain)
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        return SSLContext.getInstance("TLSv1.3").apply { init(kmf.keyManagers, arrayOf<TrustManager>(tm), SecureRandom()) }
    }
}
