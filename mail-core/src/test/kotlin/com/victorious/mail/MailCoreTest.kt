package com.victorious.mail

import com.icegreen.greenmail.util.GreenMail
import com.icegreen.greenmail.util.ServerSetupTest
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.security.KeyStore
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket

/** Real IMAPS/SMTPS wire tests against a controlled server (GreenMail) with a pinned self-signed certificate. */
class MailCoreTest {
    private lateinit var gm: GreenMail
    private var pin = ""

    @Before fun start() {
        gm = GreenMail(arrayOf(ServerSetupTest.SMTPS, ServerSetupTest.IMAPS)); gm.start()
        pin = learnPin(ServerSetupTest.SMTPS.port)
    }
    @After fun stop() { gm.stop() }

    private fun learnPin(port: Int): String {
        val ctx = SSLContext.getInstance("TLS").apply { init(null, arrayOf(object : javax.net.ssl.X509TrustManager {
            override fun checkClientTrusted(c: Array<java.security.cert.X509Certificate>, a: String) {}
            override fun checkServerTrusted(c: Array<java.security.cert.X509Certificate>, a: String) {}
            override fun getAcceptedIssuers() = arrayOf<java.security.cert.X509Certificate>() }), null) }
        val s = ctx.socketFactory.createSocket("127.0.0.1", port) as javax.net.ssl.SSLSocket; s.startHandshake()
        return (s.session.peerCertificates[0] as java.security.cert.X509Certificate).sha256Hex().also { s.close() }
    }

    private fun account(id: String, addr: String, replyTo: String? = null, pinned: String? = pin): MailAccount {
        gm.setUser(addr, addr, "pw-$id")
        val a = Auth.Password(addr, "pw-$id")
        return MailAccount(id, addr, id.uppercase(), ServerConfig("127.0.0.1", ServerSetupTest.IMAPS.port, Security.SSL_TLS, a, pinned),
            ServerConfig("127.0.0.1", ServerSetupTest.SMTPS.port, Security.SSL_TLS, a, pinned), replyTo)
    }
    private fun draft(a: MailAccount, to: String, subject: String, body: String, id: String = subject, atts: List<AttachmentData> = emptyList()) =
        Draft(id, a.id, listOf(Addr(to)), subject = subject, body = body, attachments = atts, messageId = "<$id@test>")
    private fun ok(o: SendOutcome) = assertTrue(o.toString(), o is SendOutcome.Accepted)
    private fun inbox(a: MailAccount) = ImapClient(a).sync("INBOX", null).second

    @Test fun untrustedCertificateRefusedAndWrongPinRefused() {
        val none = account("n", "n@example.test", pinned = null); val wrong = account("w", "w@example.test", pinned = "00".repeat(32))
        val o1 = SmtpSender().send(none, draft(none, "n@example.test", "s", "b")); val o2 = SmtpSender().send(wrong, draft(wrong, "w@example.test", "s", "b"))
        assertTrue(o1.toString(), o1 is SendOutcome.NotAttempted && (o1 as SendOutcome.NotAttempted).reason.contains("Could not connect"))   // TLS handshake refused
        assertTrue(o2.toString(), o2 is SendOutcome.NotAttempted)
        assertEquals(0, gm.receivedMessages.size)
        assertThrows(Exception::class.java) { ImapClient(none).folders() }
    }

    @Test fun twoAccountsReceiveAndReplyFromTheSelectedAccountHonouringReplyTo() {
        val a = account("a", "alice@example.test"); val b = account("b", "bob@example.test", replyTo = "bob-replies@example.test")
        ok(SmtpSender().send(a, draft(a, "bob@example.test", "Hello", "hi bob")))
        val got = inbox(b).single(); assertEquals("hi bob", got.text?.trim()); assertEquals("alice@example.test", got.from.single().address)
        // Bob replies from account b; Alice's message had no Reply-To so reply goes to From
        val rb = Compose.reply(got, b, "hi alice", all = false, id = "r1")
        assertEquals("bob@example.test", b.address); assertEquals(listOf("alice@example.test"), rb.to.map { it.address }); assertEquals("Re: Hello", rb.subject)
        assertEquals(got.messageId, rb.inReplyTo); assertTrue(got.messageId in rb.references)
        ok(SmtpSender().send(b, rb.copy(messageId = "<r1@test>")))
        val back = inbox(a).single { it.subject == "Re: Hello" }
        assertEquals("bob@example.test", back.from.single().address); assertEquals(listOf("bob-replies@example.test"), back.replyTo.map { it.address })   // account's Reply-To header is sent
        assertEquals(got.messageId, back.inReplyTo); assertEquals(got.threadKey, back.threadKey)                                                          // threads with the original
        // Alice replies to a message that carries Reply-To: goes to Reply-To, not From
        val toReplyTo = Compose.reply(back, a, "ok", false, "r2"); assertEquals(listOf("bob-replies@example.test"), toReplyTo.to.map { it.address })
    }

    @Test fun replyAllForwardCcBccAndAttachments() {
        val a = account("a", "alice@example.test"); val b = account("b", "bob@example.test"); val c = account("c", "carol@example.test"); val d = account("d", "dave@example.test")
        val png = AttachmentData("pic.bin", "application/octet-stream", ByteArray(5000) { (it * 7).toByte() })
        val m = Draft("m1", a.id, listOf(Addr("bob@example.test")), cc = listOf(Addr("carol@example.test")), bcc = listOf(Addr("dave@example.test")), subject = "Plan", body = "see attached", attachments = listOf(png), messageId = "<m1@test>")
        ok(SmtpSender().send(a, m))
        val atB = inbox(b).single(); assertEquals(1, atB.attachments.size); assertEquals("pic.bin", atB.attachments[0].filename); assertEquals(png.mime, atB.attachments[0].mime); assertArrayEquals(png.bytes, atB.attachments[0].bytes); assertTrue(atB.cc.any { it.address == "carol@example.test" })
        assertNull("Bcc must never appear in headers", atB.to.plus(atB.cc).firstOrNull { it.address == "dave@example.test" })
        assertEquals("dave got it silently", 1, inbox(d).size)
        val all = Compose.reply(atB, b, "thanks all", all = true, id = "ra")
        assertEquals(listOf("alice@example.test"), all.to.map { it.address }); assertEquals(listOf("carol@example.test"), all.cc.map { it.address })
        val fwd = Compose.forward(atB, b, listOf(Addr("dave@example.test")), "fyi", "f1")
        assertEquals("Fwd: Plan", fwd.subject); assertNull(fwd.inReplyTo); assertEquals(listOf(png), fwd.attachments)
        ok(SmtpSender().send(b, fwd.copy(messageId = "<f1@test>")))
        assertEquals(listOf(png), inbox(d).single { it.subject == "Fwd: Plan" }.attachments)
    }

    @Test fun incrementalSyncUsesPerAccountCursorAndReadStateRoundTrips() {
        val a = account("a", "alice@example.test"); val b = account("b", "bob@example.test")
        SmtpSender().send(a, draft(a, "bob@example.test", "one", "1")); SmtpSender().send(a, draft(a, "bob@example.test", "two", "2"))
        val (s1, first) = ImapClient(b).sync("INBOX", null); assertEquals(2, first.size); assertTrue(first.none { it.seen })
        SmtpSender().send(a, draft(a, "bob@example.test", "three", "3"))
        val (s2, second) = ImapClient(b).sync("INBOX", s1); assertEquals(listOf("three"), second.map { it.subject }); assertTrue(s2.lastUid > s1.lastUid)
        ImapClient(b).setSeen("INBOX", first[0].uid, true)
        assertTrue(inbox(b).single { it.uid == first[0].uid }.seen)
        assertTrue("UIDVALIDITY change must force a resync", ImapClient(b).sync("INBOX", s2.copy(uidValidity = s2.uidValidity + 1)).second.size == 3)
        assertTrue("INBOX" in ImapClient(b).folders())
    }

    @Test fun draftsAreSavedToTheServerDraftsFolder() {
        val a = account("a", "alice@example.test")
        ImapClient(a).saveDraft("Drafts", draft(a, "x@example.test", "wip", "half written"))
        val (_, m) = ImapClient(a).sync("Drafts", null); assertEquals("wip", m.single().subject)
    }

    // ------------------------------------------------------------------------------------------------ outbox
    class FlakySender(var outcome: SendOutcome) : SmtpSender() { var calls = 0; override fun send(account: MailAccount, draft: Draft): SendOutcome { calls++; return outcome } }

    @Test fun offlineDraftsQueueThenSendWhenTheServerIsBackAndNeverTwice() {
        val a = account("a", "alice@example.test"); val b = account("b", "bob@example.test")
        val store = InMemoryOutboxStore(); val ob = Outbox(store, { if (it == "a") a else null })
        gm.stop()                                                                     // offline
        val item = ob.enqueue(draft(a, "bob@example.test", "queued", "later"))
        ob.flush(); assertEquals(OutState.QUEUED, store.get(item.draft.id)!!.state)  // nothing could have been sent: stays queued
        gm.start(); gm.setUser("alice@example.test", "alice@example.test", "pw-a"); gm.setUser("bob@example.test", "bob@example.test", "pw-b")
        val r = ob.flush().single(); assertEquals(OutState.SENT, r.state)
        assertTrue(ob.flush().isEmpty())                                              // already sent: a second flush sends nothing
        assertEquals(1, inbox(b).size)
    }

    @Test fun lostConnectionAfterDataIsUncertainAndNeverAutoRetried() {
        val a = account("a", "alice@example.test"); val store = InMemoryOutboxStore()
        val fs = FlakySender(SendOutcome.Uncertain("dropped")); val ob = Outbox(store, { a }, fs)
        val i = ob.enqueue(draft(a, "bob@example.test", "maybe", "x"))
        assertEquals(OutState.UNCERTAIN, ob.flush().single().state); assertEquals(1, fs.calls)
        repeat(3) { ob.flush() }; assertEquals("no automatic duplicate", 1, fs.calls)
        fs.outcome = SendOutcome.Accepted
        val re = ob.resendAnyway(i.draft.id)!!; assertEquals(OutState.SENT, re.state); assertEquals(2, fs.calls)
        assertEquals("same Message-ID on explicit re-send", i.draft.messageId, re.draft.messageId)
    }

    /** Sender that blocks inside send() until released, so a second caller runs while the first is mid-send. */
    class GateSender : SmtpSender() {
        val entered = java.util.concurrent.CountDownLatch(1); val release = java.util.concurrent.CountDownLatch(1)
        val calls = java.util.concurrent.atomic.AtomicInteger()
        override fun send(account: MailAccount, draft: Draft): SendOutcome { calls.incrementAndGet(); entered.countDown(); release.await(); return SendOutcome.Accepted }
    }

    /** A store whose list() can return a stale snapshot: what a second flush sees if it listed just before the first one claimed. */
    class StaleListStore : OutboxStore {
        val inner = InMemoryOutboxStore(); @Volatile var staleAll: List<OutboxItem>? = null
        override fun put(item: OutboxItem) = inner.put(item); override fun get(draftId: String) = inner.get(draftId)
        override fun all() = staleAll ?: inner.all()
    }

    @Test fun concurrentFlushesSendOnceAndNeverThrow() {
        // Regression (review Nov 24): a second flush that saw the item as QUEUED used to hit check() and throw
        // IllegalStateException, which crashed the Send button / draft review coroutine.
        val a = account("a", "alice@example.test"); val store = StaleListStore(); val gs = GateSender()
        val ob = Outbox(store, { a }, gs)
        val i = ob.enqueue(draft(a, "bob@example.test", "race", "x"))
        var first: List<OutboxItem>? = null
        val t = Thread { first = ob.flush() }.apply { start() }
        assertTrue(gs.entered.await(10, java.util.concurrent.TimeUnit.SECONDS))
        // While the first flush is mid-send, a second caller that listed the item as QUEUED just before the claim.
        store.staleAll = listOf(store.get(i.draft.id)!!.copy(state = OutState.QUEUED))
        val second = ob.flush(); store.staleAll = null
        gs.release.countDown(); t.join(10_000)
        assertTrue(second.isEmpty()); assertEquals(1, first!!.size); assertEquals(1, gs.calls.get())
        assertEquals(OutState.SENT, store.get(i.draft.id)!!.state)
    }

    @Test fun doubleTapOnSendAgainAnywaySendsOnce() {
        val a = account("a", "alice@example.test"); val store = InMemoryOutboxStore(); val gs = GateSender()
        val ob = Outbox(store, { a }, gs)
        val i = ob.enqueue(draft(a, "bob@example.test", "maybe", "x"))
        store.put(store.get(i.draft.id)!!.copy(state = OutState.UNCERTAIN))
        val t = Thread { ob.resendAnyway(i.draft.id) }.apply { start() }
        assertTrue(gs.entered.await(10, java.util.concurrent.TimeUnit.SECONDS))
        val second = ob.resendAnyway(i.draft.id)                                      // used to throw IllegalStateException
        assertEquals(OutState.SENDING, second!!.state)
        gs.release.countDown(); t.join(10_000)
        assertEquals(1, gs.calls.get()); assertEquals(OutState.SENT, store.get(i.draft.id)!!.state)
        assertEquals(null, ob.resendAnyway("no-such-id"))
    }

    @Test fun processDeathWhileSendingBecomesUncertain() {
        val a = account("a", "alice@example.test"); val store = InMemoryOutboxStore()
        val d = Outbox(store, { a }).enqueue(draft(a, "bob@example.test", "dead", "x"))
        store.put(store.get(d.draft.id)!!.copy(state = OutState.SENDING))             // process killed mid-send
        val ob2 = Outbox(store, { a }, FlakySender(SendOutcome.Accepted)); ob2.recoverAfterRestart()
        assertEquals(OutState.UNCERTAIN, store.get(d.draft.id)!!.state); assertTrue(ob2.flush().isEmpty())
    }

    @Test fun rejectionIsFailedAndFailureNeverSwitchesAccounts() {
        val a = account("a", "alice@example.test"); val other = account("o", "other@example.test")
        val fs = FlakySender(SendOutcome.Rejected("550 no")); val ob = Outbox(InMemoryOutboxStore(), { if (it == "a") a else other }, fs)
        ob.enqueue(draft(a, "bob@example.test", "nope", "x")); val r = ob.flush().single()
        assertEquals(OutState.FAILED, r.state); assertEquals("a", r.draft.accountId); assertEquals(1, fs.calls); assertTrue(ob.flush().isEmpty())
    }

    // ------------------------------------------------------------------- real wire: ambiguous submission
    private fun fakeSmtp(afterData: String): Pair<Int, String> {   // returns port + pin; behaviour after DATA: "drop" or "reject"
        val dir = java.nio.file.Files.createTempDirectory("fk").toFile(); val ks = File(dir, "k.p12")
        ProcessBuilder("keytool", "-genkeypair", "-alias", "k", "-keyalg", "EC", "-groupname", "secp256r1", "-dname", "CN=127.0.0.1", "-ext", "san=ip:127.0.0.1", "-validity", "3", "-storetype", "PKCS12", "-keystore", ks.path, "-storepass", "changeit")
            .redirectErrorStream(true).start().also { it.inputStream.readBytes() }.waitFor()
        val k = KeyStore.getInstance("PKCS12").apply { load(ks.inputStream(), "changeit".toCharArray()) }
        val kmf = KeyManagerFactory.getInstance("SunX509").apply { init(k, "changeit".toCharArray()) }
        val ctx = SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, null, null) }
        val ss = ctx.serverSocketFactory.createServerSocket(0) as SSLServerSocket
        Thread {
            while (true) {
                val s = try { ss.accept() } catch (e: Exception) { return@Thread }
                Thread {
                    try {
                        val r = s.getInputStream().bufferedReader(); val w = s.getOutputStream().bufferedWriter()
                        fun say(x: String) { w.write(x + "\r\n"); w.flush() }
                        say("220 fake ESMTP")
                        while (true) {
                            val l = r.readLine() ?: break
                            when {
                                l.startsWith("EHLO", true) -> say("250-fake\r\n250 AUTH PLAIN LOGIN")
                                l.startsWith("AUTH", true) -> say("235 ok")
                                l.startsWith("MAIL") || l.startsWith("RCPT") -> say("250 ok")
                                l.startsWith("DATA") -> { say("354 go"); while (r.readLine() != ".") { }; if (afterData == "drop") { s.close(); return@Thread } else say("550 rejected after data") }
                                l.startsWith("QUIT") -> { say("221 bye"); break }
                                else -> say("250 ok")
                            }
                        }
                    } catch (_: Exception) { } finally { try { s.close() } catch (_: Exception) { } }
                }.start()
            }
        }.start()
        val pin = learnPin(ss.localPort); return ss.localPort to pin
    }

    private fun fakeAccount(port: Int, pin: String): MailAccount { val a = Auth.Password("u", "p"); return MailAccount("f", "u@example.test", "U", ServerConfig("127.0.0.1", 1, Security.SSL_TLS, a, pin), ServerConfig("127.0.0.1", port, Security.SSL_TLS, a, pin)) }

    @Test fun realWireDropAfterDataIsUncertainAndServerRejectionIsRejected() {
        val (p1, pin1) = fakeSmtp("drop"); val acct1 = fakeAccount(p1, pin1)
        val o1 = SmtpSender().send(acct1, draft(acct1, "b@example.test", "x", "y")); assertTrue(o1.toString(), o1 is SendOutcome.Uncertain)
        val (p2, pin2) = fakeSmtp("reject"); val acct2 = fakeAccount(p2, pin2)
        val o2 = SmtpSender().send(acct2, draft(acct2, "b@example.test", "x", "y")); assertTrue(o2.toString(), o2 is SendOutcome.Rejected)
    }

    // ------------------------------------------------------------------------------------------ safety
    @Test fun headerInjectionIsRefused() {
        val a = account("a", "alice@example.test")
        assertThrows(IllegalArgumentException::class.java) { MimeBuilder.build(Net.session("smtp", a.smtp), a, draft(a, "b@example.test", "x\r\nBcc: evil@x", "b")) }
    }

    @Test fun conversationKeysKeepPeopleAndChannelsApart() {
        val sms = ConversationKey(Transport.SMS, "sim-1", "thread-42"); val mail = ConversationKey(Transport.EMAIL, "work@example.test", "<root@x>")
        val otherJordan = ConversationKey(Transport.SMS, "sim-1", "thread-77"); val simTwo = ConversationKey(Transport.SMS, "sim-2", "thread-42")
        assertEquals(4, setOf(sms, mail, otherJordan, simTwo).size); assertEquals(mail, ConversationKey.decode(mail.encode()))
        assertEquals(ConversationKey(Transport.EMAIL, "a|b", "t|u"), ConversationKey.decode(ConversationKey(Transport.EMAIL, "a|b", "t|u").encode()))
    }

    @Test fun htmlIsSanitisedAndRemoteImagesBlockedByDefault() {
        val evil = """<p onclick="x()">hi<script>alert(1)</script><iframe src="http://e"></iframe><img src="https://t.example/p.gif" alt="track"><img src="cid:logo"><a href="javascript:alert(1)">j</a><a href="https://ok.example">ok</a><form action="http://e"><input></form><style>*{}</style></p>"""
        val r = HtmlSanitizer.sanitize(evil)
        assertFalse(r.html.contains("script", true)); assertFalse(r.html.contains("iframe")); assertFalse(r.html.contains("onclick")); assertFalse(r.html.contains("javascript:"))
        assertFalse(r.html.contains("<form")); assertFalse(r.html.contains("t.example")); assertTrue(r.html.contains("cid:logo")); assertTrue(r.html.contains("https://ok.example")); assertEquals(1, r.blockedRemoteImages)
        assertTrue(HtmlSanitizer.sanitize(evil, allowRemoteImages = true).html.contains("t.example"))
        assertFalse(HtmlSanitizer.sanitize("""<img src="http://t.example/p.gif">""", true).html.contains("http://t.example"))   // plaintext http images never allowed
    }
}
