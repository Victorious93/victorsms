package com.victorious.sms.mail

import com.victorious.sms.voip.CertPin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyStore
import java.security.cert.X509Certificate
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket

/** Server certificate pinning for the SIP edition. Real TLS over loopback with throwaway test keys; no Linphone, no device. */
class CertPinTest {
    private fun load(res: String): KeyStore = KeyStore.getInstance("PKCS12").also { ks -> javaClass.classLoader!!.getResourceAsStream(res)!!.use { ks.load(it, "changeit".toCharArray()) } }
    private fun cert(res: String) = load(res).getCertificate(load(res).aliases().nextElement()) as X509Certificate
    private val good by lazy { cert("pin-test.p12") }
    private val other by lazy { cert("pin-other.p12") }

    /** Loopback TLS server presenting [res]'s certificate; returns its port. Accepts connections until the test ends. */
    private fun server(res: String): Int {
        val ks = load(res)
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(ks, "changeit".toCharArray()) }
        val ctx = SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, null, null) }
        val ss = ctx.serverSocketFactory.createServerSocket(0, 5, java.net.InetAddress.getLoopbackAddress()) as SSLServerSocket
        Thread { while (!ss.isClosed) runCatching { (ss.accept() as javax.net.ssl.SSLSocket).use { it.startHandshake() } } }.apply { isDaemon = true; start() }
        return ss.localPort
    }

    @Test fun theCertificateFingerprintAndTheKeyFingerprintBothMatchAndNothingElseDoes() {
        val (der, spki) = CertPin.fingerprints(good)
        assertEquals(0, CertPin.matchIndex(der, listOf(good)))
        assertEquals(0, CertPin.matchIndex(spki, listOf(good)))
        assertEquals("colon-separated, upper case is the same pin", 0, CertPin.matchIndex(der.uppercase().chunked(2).joinToString(":"), listOf(good)))
        assertEquals(-1, CertPin.matchIndex(der, listOf(other)))
        assertEquals("any certificate in the chain may carry the pin", 1, CertPin.matchIndex(der, listOf(other, good)))
    }

    @Test fun aMalformedOrEmptyPinMatchesNothing() {
        listOf("", "zz", "abc", "0".repeat(63), "0".repeat(65), "g".repeat(64)).forEach {
            assertEquals(it, -1, CertPin.matchIndex(it, listOf(good)))
            assertTrue(CertPin.verify(it, listOf(good)) is CertPin.Verdict.Refused)
        }
    }

    @Test fun verifyFailsClosedOnAMismatchAndOnNoChain() {
        val pinOfGood = CertPin.fingerprints(good)[0]
        assertTrue(CertPin.verify(pinOfGood, listOf(other)) is CertPin.Verdict.Refused)
        assertTrue(CertPin.verify(pinOfGood, emptyList()) is CertPin.Verdict.Refused)
        val ok = CertPin.verify(pinOfGood, listOf(good)) as CertPin.Verdict.Pinned
        assertTrue(ok.trustPem.startsWith("-----BEGIN CERTIFICATE-----") && ok.trustPem.trim().endsWith("-----END CERTIFICATE-----"))
    }

    @Test fun refusalReasonsNeverContainThePinOrCertificateBytes() {
        val pin = CertPin.fingerprints(good)[0]
        val r = (CertPin.verify(pin, listOf(other)) as CertPin.Verdict.Refused).reason
        assertFalse(r.contains(pin)); assertFalse(r.lowercase().contains(CertPin.fingerprints(other)[0]))
    }

    @Test fun aSelfSignedServerIsAcceptedOnlyWhenItsPinMatches() {
        val port = server("pin-test.p12")
        val pin = CertPin.fingerprints(good)[0]
        val v = CertPin.check("127.0.0.1", port, pin) as CertPin.Verdict.Pinned
        assertTrue("the PEM handed to the engine is exactly the server's certificate", v.trustPem == CertPin.toPem(listOf(good)))
        assertTrue(CertPin.check("127.0.0.1", port, CertPin.fingerprints(good)[1]) is CertPin.Verdict.Pinned)
    }

    @Test fun aServerWithAnotherCertificateIsRefusedEvenThoughItHandshakes() {
        val port = server("pin-other.p12")                                   // an impostor presenting its own self-signed certificate
        val v = CertPin.check("127.0.0.1", port, CertPin.fingerprints(good)[0])
        assertTrue(v is CertPin.Verdict.Refused)
    }

    @Test fun anUnreachableOrNonTlsServerIsRefusedNotTrusted() {
        val dead = java.net.ServerSocket(0).use { it.localPort }              // closed port
        assertTrue(CertPin.check("127.0.0.1", dead, CertPin.fingerprints(good)[0], timeoutMs = 2_000) is CertPin.Verdict.Refused)
        val plain = java.net.ServerSocket(0, 5, java.net.InetAddress.getLoopbackAddress())   // accepts TCP but never speaks TLS
        Thread { runCatching { plain.accept().use { Thread.sleep(3_000) } } }.apply { isDaemon = true; start() }
        assertTrue(CertPin.check("127.0.0.1", plain.localPort, CertPin.fingerprints(good)[0], timeoutMs = 1_000) is CertPin.Verdict.Refused)
        plain.close()
    }
}
