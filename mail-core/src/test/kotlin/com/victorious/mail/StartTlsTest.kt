package com.victorious.mail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.net.ServerSocket
import java.net.Socket
import java.security.KeyStore
import java.util.Collections
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket

/**
 * Real-wire STARTTLS submission (port-587 style) against a controlled server that upgrades the plain connection with a
 * self-signed certificate. Proves: the upgrade happens and the message is accepted over TLS; nothing secret is sent before
 * the upgrade; a server that does not offer STARTTLS is refused (no plaintext fallback); and a wrong pin is refused at upgrade.
 */
class StartTlsTest {
    private class Server(val offerStartTls: Boolean) {
        val plainCommands: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val tlsCommands: MutableList<String> = Collections.synchronizedList(mutableListOf())
        @Volatile var accepted = false
        val ss = ServerSocket(0)
        private val ctx: SSLContext
        val pin: String

        init {
            val dir = java.nio.file.Files.createTempDirectory("stls").toFile()
            val ks = File(dir, "k.p12")
            ProcessBuilder("keytool", "-genkeypair", "-alias", "k", "-keyalg", "EC", "-groupname", "secp256r1", "-dname", "CN=127.0.0.1", "-ext", "san=ip:127.0.0.1",
                "-validity", "3", "-storetype", "PKCS12", "-keystore", ks.path, "-storepass", "changeit").redirectErrorStream(true).start().also { it.inputStream.readBytes() }.waitFor()
            val k = KeyStore.getInstance("PKCS12").apply { load(ks.inputStream(), "changeit".toCharArray()) }
            pin = (k.getCertificate("k") as java.security.cert.X509Certificate).sha256Hex()
            ctx = SSLContext.getInstance("TLS").apply { init(KeyManagerFactory.getInstance("SunX509").apply { init(k, "changeit".toCharArray()) }.keyManagers, null, null) }
            Thread { while (true) { val s = try { ss.accept() } catch (e: Exception) { return@Thread }; Thread { serve(s) }.start() } }.apply { isDaemon = true }.start()
        }

        private fun serve(plain: Socket) {
            var sock: Socket = plain
            var r: BufferedReader = plain.getInputStream().bufferedReader()
            var w: BufferedWriter = plain.getOutputStream().bufferedWriter()
            var tls = false
            fun say(x: String) { w.write(x + "\r\n"); w.flush() }
            try {
                say("220 fake ESMTP")
                while (true) {
                    val l = r.readLine() ?: break
                    (if (tls) tlsCommands else plainCommands) += l.substringBefore(' ').uppercase()
                    when {
                        l.startsWith("EHLO", true) -> say(if (!tls && offerStartTls) "250-fake\r\n250 STARTTLS" else if (!tls) "250-fake\r\n250 AUTH PLAIN LOGIN" else "250-fake\r\n250 AUTH PLAIN LOGIN")
                        l.startsWith("STARTTLS", true) && !tls && offerStartTls -> {
                            say("220 go ahead")
                            val t = ctx.socketFactory.createSocket(plain, "127.0.0.1", plain.port, true) as SSLSocket
                            t.useClientMode = false; t.startHandshake()
                            sock = t; r = t.inputStream.bufferedReader(); w = t.outputStream.bufferedWriter(); tls = true
                        }
                        l.startsWith("AUTH", true) -> say("235 ok")
                        l.startsWith("MAIL", true) || l.startsWith("RCPT", true) -> say("250 ok")
                        l.startsWith("DATA", true) -> { say("354 go"); while (r.readLine() != ".") { }; accepted = tls; say("250 queued") }
                        l.startsWith("QUIT", true) -> { say("221 bye"); break }
                        else -> say("250 ok")
                    }
                }
            } catch (_: Exception) { } finally { try { sock.close() } catch (_: Exception) { } }
        }
    }

    private fun account(port: Int, pin: String): MailAccount {
        val a = Auth.Password("u@example.test", "secret-pw")
        return MailAccount("s", "u@example.test", "U", ServerConfig("127.0.0.1", 1, Security.STARTTLS, a, pin), ServerConfig("127.0.0.1", port, Security.STARTTLS, a, pin))
    }

    private fun draft() = Draft("d1", "s", listOf(Addr("b@example.test")), subject = "hi", body = "body", messageId = "<d1@test>")

    @Test fun startTlsUpgradesBeforeAuthAndTheMessageIsAcceptedOverTls() {
        val srv = Server(offerStartTls = true)
        val o = SmtpSender().send(account(srv.ss.localPort, srv.pin), draft())
        assertTrue(o.toString(), o is SendOutcome.Accepted)
        assertTrue(srv.accepted)
        assertEquals(listOf("EHLO", "STARTTLS"), srv.plainCommands.toList())      // nothing but EHLO + STARTTLS in the clear
        assertTrue(srv.tlsCommands.toString(), "AUTH" in srv.tlsCommands && "DATA" in srv.tlsCommands)
    }

    @Test fun aServerWithoutStartTlsIsRefusedAndNoCredentialsAreSent() {
        val srv = Server(offerStartTls = false)
        val o = SmtpSender().send(account(srv.ss.localPort, srv.pin), draft())
        assertFalse(o.toString(), o is SendOutcome.Accepted)
        assertFalse(srv.accepted)
        assertFalse(srv.plainCommands.toString(), "AUTH" in srv.plainCommands || "MAIL" in srv.plainCommands || "DATA" in srv.plainCommands)
    }

    @Test fun aWrongPinIsRefusedAtTheUpgrade() {
        val srv = Server(offerStartTls = true)
        val o = SmtpSender().send(account(srv.ss.localPort, "00".repeat(32)), draft())
        assertFalse(o.toString(), o is SendOutcome.Accepted)
        assertFalse(srv.accepted)
        assertTrue(srv.tlsCommands.isEmpty())
    }
}
