package com.victorious.comms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset

class LiveIngestTest {
    private val a: Archive = newArchive().first
    private val live = LiveIngest(a)
    private val engine = ImportEngine(a)
    private val peer = "+1 (555) 010-0101"

    private fun recordAll(msgs: List<M>, address: String = peer) =
        msgs.map { live.record(address, it.text, it.tsMs, if (it.out) Direction.OUT else Direction.IN)!! }

    private fun importXml(msgs: List<M>, label: String? = null, force: Boolean = false) =
        engine.commit(ImportRequest(Fx.smsXml(msgs, peer), providerLabel = label, force = force, options = ParseOptions(zone = ZoneOffset.UTC)))

    @Test fun aReceivedTextBecomesALiveMessageInAnSmsConversationNobodyCanSeeYet() {
        val id = live.record(peer, "Running late, sorry!", Fx.BASE, Direction.IN)!!
        val conv = a.conversations().single()
        assertEquals("sms", conv.provider)
        assertEquals("", conv.account)
        assertEquals(peer, conv.title)
        assertFalse("a new conversation is never shared with an assistant app", conv.assistantAccess)
        val m = a.message(id)!!
        assertEquals("live", m.origin)
        assertEquals(Direction.IN, m.direction)
        assertEquals("peer", m.author)
        assertEquals("Running late, sorry!", m.body)
    }

    @Test fun theSameNumberWrittenInDifferentWaysIsOneConversationButDifferentSendersAreNot() {
        live.record("+1 (555) 010-0101", "one", Fx.BASE, Direction.IN)
        live.record("5550100101", "two", Fx.BASE + 1000, Direction.IN)
        live.record("+15550100101", "three", Fx.BASE + 2000, Direction.OUT)
        assertEquals(1, a.conversations().size)
        assertEquals(3L, a.messageCount(a.conversations().single().id))
        live.record("12345", "short code", Fx.BASE, Direction.IN)
        live.record("AMAZON", "alphanumeric sender", Fx.BASE, Direction.IN)
        assertEquals(3, a.conversations().size)
    }

    @Test fun ownTextsAreOutgoingAndOnlyTheOwnersWordsCanTeachAStyle() {
        live.record(peer, "omw", Fx.BASE, Direction.OUT, Author.OWNER)
        live.record(peer, "I will be there at 5 pm sharp.", Fx.BASE + 1000, Direction.OUT, Author.BOT_APPROVED)
        val conv = a.conversations().single()
        assertEquals(listOf("omw"), a.outgoingForStyle().map { it.body })
        assertEquals(2, a.messages(conv.id).count { it.direction == Direction.OUT })
        assertTrue(a.participants(conv.id).any { it["is_self"] == "1" && it["display"] == "Me" })
        assertTrue("the other party exists even though they never wrote", a.participants(conv.id).any { it["is_self"] == "0" && it["key"] == "p:5550100101" && it["phone"] == peer })
    }

    @Test fun nothingIsRecordedWithoutAnAddressOrABody() {
        assertNull(live.record("", "hello", Fx.BASE, Direction.IN))
        assertNull(live.record("   ", "hello", Fx.BASE, Direction.IN))
        assertNull(live.record(peer, "", Fx.BASE, Direction.IN))
        assertNull(live.record(peer, "  \n ", Fx.BASE, Direction.OUT))
        assertEquals(0, a.conversations().size)
    }

    @Test fun recordingNeverQueuesAReplyOrCreatesAnAutomationRule() {
        live.record(peer, "are you there?", Fx.BASE, Direction.IN)
        val conv = a.conversations().single()
        assertEquals(0, Outbox(a).list(conv.id).size)
        assertEquals(0L, a.db.count("SELECT COUNT(*) n FROM auto_rules"))
        assertEquals(0L, a.db.count("SELECT COUNT(*) n FROM outbox"))
    }

    @Test fun aLaterSmsBackupImportMergesWithWhatWasRecordedLive() {
        val all = Fx.conversation(7)
        val liveIds = recordAll(all.take(4))
        val conv = a.conversations().single().id
        val result = importXml(all)
        val g = result.groups.single()
        assertEquals("the import found the live conversation", conv, g.conversation)
        assertFalse(g.createdConversation)
        assertEquals("the four recorded texts are recognised, not duplicated", 4, g.matched)
        assertEquals(3, g.added)
        assertEquals(1, a.conversations().size)
        assertEquals(7L, a.messageCount(conv))
        assertTrue("recorded texts stay live", liveIds.all { a.message(it)!!.origin == "live" })
        assertEquals(3, a.messages(conv, 50).count { it.origin == "import" })
    }

    @Test fun aConversationAnEarlierImportSavedUnderTheOldSmsBackupLabelIsReused() {
        val first = importXml(Fx.conversation(3), label = "sms-backup").groups.single().conversation
        live.record(peer, "a new text", Fx.BASE + 10_000_000L, Direction.IN)
        assertEquals("no second conversation for the same person", 1, a.conversations().size)
        assertEquals(4L, a.messageCount(first))
    }

    @Test fun aNewBackupImportFindsAConversationSavedUnderTheOldLabel() {
        val first = importXml(Fx.conversation(3), label = "sms-backup").groups.single().conversation
        val again = importXml(Fx.conversation(6)).groups.single()
        assertEquals(first, again.conversation)
        assertFalse(again.createdConversation)
        assertEquals(3, again.matched)
        assertEquals(1, a.conversations().size)
    }

    @Test fun theSmsBackupImporterNowNamesTheServiceNotTheFileFormat() {
        val r = SmsBackupXmlImporter().parse(Fx.smsXml(Fx.conversation(2)), ParseOptions(zone = ZoneOffset.UTC))
        assertEquals("sms", r.providerGuess)
        assertNotNull(Importers.byId("sms-backup-xml"))
    }

    @Test fun twoTextsWithTheSameWordsAtTheSameInstantAreBothKept() {
        live.record(peer, "ok", Fx.BASE, Direction.OUT)
        live.record(peer, "ok", Fx.BASE, Direction.OUT)
        assertEquals(2L, a.messageCount(a.conversations().single().id))
    }
}
