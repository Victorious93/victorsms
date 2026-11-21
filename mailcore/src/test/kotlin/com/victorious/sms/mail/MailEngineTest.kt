package com.victorious.sms.mail

import com.icegreen.greenmail.util.GreenMail
import com.icegreen.greenmail.util.ServerSetup
import org.junit.After
import org.junit.Before
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertIs

class MailEngineTest {
    private lateinit var gm: GreenMail
    private val secrets = MemorySecretStore()
    private val tls = TestTls.policy
    private val smtpSetup = ServerSetup(3465, "127.0.0.1", "smtps")
    private val imapSetup = ServerSetup(3993, "127.0.0.1", "imaps")

    private fun acct(id: String, addr: String, name: String = "User") = MailAccount(
        id, addr, name, addr, ServerConfig("127.0.0.1", 3993, Security.SSL_TLS), ServerConfig("127.0.0.1", 3465, Security.SSL_TLS))

    private val alice = acct("a", "alice@example.com", "Alice")
    private val bob = acct("b", "bob@example.com", "Bob")

    @Before fun up() {
        TestTls.keystore.exists()
        gm = GreenMail(arrayOf(smtpSetup, imapSetup)).also { it.start() }
        gm.setUser("alice@example.com", "alice@example.com", "pw-alice")
        gm.setUser("bob@example.com", "bob@example.com", "pw-bob")
        secrets.put("a", "pw-alice"); secrets.put("b", "pw-bob")
    }
    @After fun down() { gm.stop() }

    private val sender get() = SmtpSender(secrets, tls)
    private fun imap(a: MailAccount) = ImapClient(a.let { it.copy(username = it.address) }, secrets, tls)

    @Test fun `send over authenticated TLS submission and receive over IMAP from two accounts`() {
        assertIs<SendOutcome.Accepted>(sender.send(Outgoing(alice, listOf("bob@example.com"), subject = "Hello Bob", text = "hi from alice")))
        assertIs<SendOutcome.Accepted>(sender.send(Outgoing(bob, listOf("alice@example.com"), subject = "Hello Alice", text = "hi from bob")))
        val bobInbox = imap(bob).sync("INBOX", SyncState())
        assertEquals(1, bobInbox.messages.size)
        assertEquals("Hello Bob", bobInbox.messages[0].subject); assertEquals("hi from alice", bobInbox.messages[0].textBody.trim())
        assertEquals("alice@example.com", bobInbox.messages[0].from)
        val aliceInbox = imap(alice).sync("INBOX", SyncState())
        assertEquals("Hello Alice", aliceInbox.messages.single().subject)
    }

    @Test fun `incremental sync fetches only new mail and tracks uid state`() {
        sender.send(Outgoing(alice, listOf("bob@example.com"), subject = "one", text = "1"))
        val first = imap(bob).sync("INBOX", SyncState())
        assertEquals(1, first.messages.size)
        val none = imap(bob).sync("INBOX", first.state)
        assertEquals(0, none.messages.size)
        sender.send(Outgoing(alice, listOf("bob@example.com"), subject = "two", text = "2"))
        val next = imap(bob).sync("INBOX", first.state)
        assertEquals(listOf("two"), next.messages.map { it.subject })
        assertTrue(next.state.lastUid > first.state.lastUid)
    }

    @Test fun `attachment survives the round trip`() {
        val bytes = ByteArray(5000) { (it % 251).toByte() }
        sender.send(Outgoing(alice, listOf("bob@example.com"), subject = "file", text = "see attached", attachments = listOf(Attachment("data.bin", "application/octet-stream", bytes))))
        val m = imap(bob).sync("INBOX", SyncState()).messages.single()
        assertEquals("see attached", m.textBody.trim())
        assertEquals(1, m.attachments.size); assertEquals("data.bin", m.attachments[0].name); assertTrue(bytes.contentEquals(m.attachments[0].bytes))
    }

    @Test fun `read state is stored on the server`() {
        sender.send(Outgoing(alice, listOf("bob@example.com"), subject = "r", text = "x"))
        val m = imap(bob).sync("INBOX", SyncState()).messages.single()
        assertFalse(m.seen)
        imap(bob).markRead("INBOX", m.uid)
        assertTrue(imap(bob).sync("INBOX", SyncState()).messages.single().seen)
    }

    @Test fun `reply uses the selected account honors Reply-To and keeps threading`() {
        sender.send(Outgoing(alice, listOf("bob@example.com"), subject = "Plan", text = "start", replyTo = "alice-list@example.com"))
        val inbound = imap(bob).sync("INBOX", SyncState()).messages.single()
        val reply = MimeBuilder.reply(inbound, bob, "ok", all = false)
        assertEquals(listOf("alice-list@example.com"), reply.to)            // Reply-To, not From
        assertEquals("Re: Plan", reply.subject); assertEquals(inbound.messageId, reply.inReplyTo)
        assertEquals(bob.address, reply.account.address)                     // the selected identity
        assertIs<SendOutcome.Accepted>(sender.send(reply.copy(to = listOf("alice@example.com"))))   // deliver to a real mailbox for the check below
        val got = imap(alice).sync("INBOX", SyncState()).messages.single()
        assertEquals("bob@example.com", got.from); assertEquals(inbound.messageId, got.inReplyTo)
        assertEquals(inbound.threadRoot, got.threadRoot)
        assertEquals(inbound.messageId, got.references.last())
    }

    @Test fun `reply-all drops own address and dedupes cc`() {
        val orig = MailMessage("<m@x>", null, emptyList(), "carol@example.com", emptyList(), listOf("bob@example.com", "dave@example.com"),
            listOf("Bob@Example.com", "erin@example.com"), "Topic", 0, "body", null, emptyList(), true)
        val r = MimeBuilder.reply(orig, bob, "hey", all = true)
        assertEquals(listOf("carol@example.com"), r.to)
        assertEquals(listOf("dave@example.com", "erin@example.com"), r.cc)
    }

    @Test fun `To Cc Bcc are delivered and Bcc is not disclosed`() {
        gm.setUser("carol@example.com", "carol@example.com", "pw"); secrets.put("c", "pw")
        gm.setUser("dave@example.com", "dave@example.com", "pw"); secrets.put("d", "pw")
        sender.send(Outgoing(alice, listOf("bob@example.com"), listOf("carol@example.com"), listOf("dave@example.com"), "group", "hello all"))
        val carol = imap(acct("c", "carol@example.com")).sync("INBOX", SyncState()).messages.single()
        assertEquals(listOf("carol@example.com"), carol.cc)
        val dave = imap(acct("d", "dave@example.com")).sync("INBOX", SyncState()).messages.single()
        assertFalse(dave.to.plus(dave.cc).contains("dave@example.com"))
        assertEquals(1, imap(bob).sync("INBOX", SyncState()).messages.size)
    }

    @Test fun `forward keeps attachments and prefixes the subject`() {
        val orig = MailMessage("<m@x>", null, emptyList(), "carol@example.com", emptyList(), listOf("bob@example.com"), emptyList(), "Invoice", 0, "see file", null,
            listOf(Attachment("a.txt", "text/plain", "abc".toByteArray())), true)
        val f = MimeBuilder.forward(orig, bob, listOf("alice@example.com"), "fyi")
        assertEquals("Fwd: Invoice", f.subject); assertEquals(1, f.attachments.size)
        assertIs<SendOutcome.Accepted>(sender.send(f))
        assertEquals("a.txt", imap(alice).sync("INBOX", SyncState()).messages.single().attachments.single().name)
    }

    @Test fun `header injection is refused`() {
        val out = sender.send(Outgoing(alice, listOf("bob@example.com"), subject = "x\r\nBcc: evil@example.com", text = "t"))
        assertIs<SendOutcome.Rejected>(out)
        assertEquals(0, gm.receivedMessages.size)
    }

    @Test fun `wrong password is NotSent and nothing is delivered`() {
        secrets.put("a", "wrong")
        assertIs<SendOutcome.NotSent>(sender.send(Outgoing(alice, listOf("bob@example.com"), subject = "s", text = "t")))
        assertEquals(0, gm.receivedMessages.size)
    }

    @Test fun `unknown certificate is rejected by default trust`() {
        val strict = SmtpSender(secrets, TlsPolicy())                       // platform trust store: our test cert is not in it
        val out = strict.send(Outgoing(alice, listOf("bob@example.com"), subject = "s", text = "t"))
        assertIs<SendOutcome.NotSent>(out); assertTrue(out.reason.contains("TLS") || out.reason.contains("cannot"), out.reason)
        assertEquals(0, gm.receivedMessages.size)
    }

    @Test fun `certificate for another host name is rejected even when the CA is trusted`() {
        val wrongHost = alice.copy(smtp = ServerConfig("localhost2.invalid", 3465, Security.SSL_TLS))
        val out = sender.send(Outgoing(wrongHost, listOf("bob@example.com"), subject = "s", text = "t"))
        assertIs<SendOutcome.NotSent>(out)
    }

    @Test fun `unreachable server is NotSent`() {
        val dead = alice.copy(smtp = ServerConfig("127.0.0.1", 3999, Security.SSL_TLS))
        assertIs<SendOutcome.NotSent>(sender.send(Outgoing(dead, listOf("bob@example.com"), subject = "s", text = "t")))
    }

    @Test fun `containsMessageId finds a delivered message and not a missing one`() {
        val o = Outgoing(alice, listOf("bob@example.com"), subject = "find me", text = "t")
        sender.send(o)
        assertTrue(imap(bob).containsMessageId("INBOX", o.messageId))
        assertFalse(imap(bob).containsMessageId("INBOX", "<nope@x>"))
    }

    @Test fun `lists folders`() {
        assertTrue("INBOX" in imap(alice).listFolders())
    }
}
