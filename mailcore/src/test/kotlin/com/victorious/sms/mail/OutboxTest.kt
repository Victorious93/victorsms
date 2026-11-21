package com.victorious.sms.mail

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import java.security.KeyStore
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class OutboxTest {
    private val acct = MailAccount("a", "alice@example.com", "Alice", "alice@example.com",
        ServerConfig("127.0.0.1", 1, Security.SSL_TLS), ServerConfig("127.0.0.1", 2, Security.SSL_TLS))
    private val lookup: (String) -> MailAccount? = { if (it == "a") acct else null }
    private fun msg(subject: String = "s") = Outgoing(acct, listOf("bob@example.com"), subject = subject, text = "body")

    @Test fun `queued items are sent once and marked SENT`() {
        val calls = ArrayList<String>()
        val ob = Outbox(MemoryRecordStore(), lookup, { calls += it.messageId; SendOutcome.Accepted })
        val item = ob.enqueue(msg())
        ob.processQueued(); ob.processQueued()
        assertEquals(1, calls.size)
        assertEquals(OutboxState.SENT, ob.get(item.id)!!.state)
    }

    @Test fun `offline send stays visible as FAILED and an explicit retry reuses the same Message-ID`() {
        var online = false; val ids = ArrayList<String>()
        val ob = Outbox(MemoryRecordStore(), lookup, { ids += it.messageId; if (online) SendOutcome.Accepted else SendOutcome.NotSent("offline") })
        val i = ob.enqueue(msg())
        ob.processQueued()
        assertEquals(OutboxState.FAILED, ob.get(i.id)!!.state)
        ob.processQueued(); assertEquals(1, ids.size)                           // FAILED is not retried by itself
        online = true; ob.retryFailed(i.id); ob.processQueued()
        assertEquals(OutboxState.SENT, ob.get(i.id)!!.state); assertEquals(ids[0], ids[1])
    }

    @Test fun `uncertain is never retried automatically`() {
        var calls = 0
        val ob = Outbox(MemoryRecordStore(), lookup, { calls++; SendOutcome.Uncertain("link dropped") })
        val i = ob.enqueue(msg())
        ob.processQueued(); ob.processQueued(); ob.processQueued()
        assertEquals(1, calls); assertEquals(OutboxState.UNCERTAIN, ob.get(i.id)!!.state)
        assertEquals(OutboxState.UNCERTAIN, ob.retryFailed(i.id)!!.state)       // retryFailed only touches FAILED
    }

    @Test fun `process death during sending becomes UNCERTAIN after restart and is not re-sent`() {
        val dir = kotlin.io.path.createTempDirectory("ob").toFile()
        var calls = 0
        val first = Outbox(FileRecordStore(dir), lookup, { calls++; throw OutOfMemoryError("process killed") })
        val i = first.enqueue(msg("survive"))
        try { first.processQueued() } catch (_: Throwable) {}
        // simulate the process dying inside the sender: the record on disk says SENDING or UNCERTAIN, never QUEUED
        val restarted = Outbox(FileRecordStore(dir), lookup, { calls++; SendOutcome.Accepted })
        restarted.processQueued()
        assertEquals(1, calls)
        assertEquals(OutboxState.UNCERTAIN, restarted.get(i.id)!!.state)
    }

    @Test fun `a record stuck in SENDING on disk is recovered as UNCERTAIN`() {
        val dir = kotlin.io.path.createTempDirectory("ob").toFile(); val store = FileRecordStore(dir)
        val a = Outbox(store, lookup, { SendOutcome.Accepted })
        val i = a.enqueue(msg())
        store.put(i.id, Outbox.toJson(i.copy(state = OutboxState.SENDING)).toString())
        val b = Outbox(FileRecordStore(dir), lookup, { error("must not send") })
        assertEquals(OutboxState.UNCERTAIN, b.get(i.id)!!.state)
        assertEquals(0, b.processQueued().size)
    }

    @Test fun `queued mail and attachments survive restart`() {
        val dir = kotlin.io.path.createTempDirectory("ob").toFile()
        val o = msg("persist").copy(attachments = listOf(Attachment("a.bin", "application/octet-stream", byteArrayOf(1, 2, 3))))
        Outbox(FileRecordStore(dir), lookup, { SendOutcome.Accepted }).enqueue(o)
        val sent = ArrayList<Outgoing>()
        Outbox(FileRecordStore(dir), lookup, { sent += it; SendOutcome.Accepted }).processQueued()
        assertEquals(1, sent.size); assertEquals("persist", sent[0].subject); assertTrue(byteArrayOf(1, 2, 3).contentEquals(sent[0].attachments[0].bytes))
    }

    @Test fun `uncertain resolves to SENT only when the Sent folder holds the Message-ID`() {
        val ob = Outbox(MemoryRecordStore(), lookup, { SendOutcome.Uncertain("x") })
        val i = ob.enqueue(msg()); ob.processQueued()
        assertEquals(OutboxState.UNCERTAIN, ob.resolveUncertain(i.id) { _, _ -> false }!!.state)
        assertEquals(OutboxState.SENT, ob.resolveUncertain(i.id) { _, id -> id == i.payload.messageId }!!.state)
    }

    @Test fun `resend as new is explicit and gets a new Message-ID`() {
        val ob = Outbox(MemoryRecordStore(), lookup, { SendOutcome.Uncertain("x") })
        val i = ob.enqueue(msg()); ob.processQueued()
        val fresh = ob.resendAsNew(i.id)!!
        assertNotEquals(i.id, fresh.id); assertEquals(OutboxState.QUEUED, fresh.state); assertEquals(null, ob.get(i.id))
    }

    @Test fun `drafts persist across restart`() {
        val dir = kotlin.io.path.createTempDirectory("ob").toFile()
        DraftStore(FileRecordStore(dir), lookup).save("d1", msg("draft subject").copy(cc = listOf("c@example.com")))
        val d = DraftStore(FileRecordStore(dir), lookup).load("d1")!!
        assertEquals("draft subject", d.subject); assertEquals(listOf("c@example.com"), d.cc)
    }

    // ---- real SMTP connection that breaks after DATA starts: the sender must say Uncertain, not Rejected/NotSent ----
    @Test fun `connection dropped during DATA is reported as Uncertain by the real sender`() {
        val ks = KeyStore.getInstance("PKCS12").apply { TestTls.keystore.inputStream().use { load(it, TestTls.PASSWORD.toCharArray()) } }
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(ks, TestTls.PASSWORD.toCharArray()) }
        val ctx = SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, null, null) }
        val server = ctx.serverSocketFactory.createServerSocket(3466, 5, java.net.InetAddress.getByName("127.0.0.1"))
        val t = thread {
            server.accept().use { s ->
                val r = BufferedReader(InputStreamReader(s.getInputStream())); val w = s.getOutputStream().bufferedWriter()
                fun say(x: String) { w.write(x + "\r\n"); w.flush() }
                say("220 test ESMTP")
                while (true) {
                    val line = r.readLine() ?: return@use
                    when {
                        line.startsWith("EHLO") -> { w.write("250-test\r\n250 AUTH PLAIN LOGIN\r\n"); w.flush() }
                        line.startsWith("AUTH") -> say("235 ok")
                        line.startsWith("MAIL") || line.startsWith("RCPT") -> say("250 ok")
                        line.startsWith("DATA") -> { say("354 go"); r.readLine(); return@use }    // read a line of the body, then drop the TCP connection
                        else -> say("250 ok")
                    }
                }
            }
        }
        val secrets = MemorySecretStore().also { it.put("a", "pw") }
        val dropAcct = acct.copy(smtp = ServerConfig("127.0.0.1", 3466, Security.SSL_TLS))
        val out = SmtpSender(secrets, TestTls.policy).send(Outgoing(dropAcct, listOf("bob@example.com"), subject = "s", text = "x".repeat(2000)))
        server.close(); t.join(2000)
        assertIs<SendOutcome.Uncertain>(out, out.toString())
    }
}
