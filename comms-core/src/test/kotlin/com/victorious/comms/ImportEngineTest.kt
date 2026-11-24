package com.victorious.comms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.ZoneOffset

class ImportEngineTest {
    private fun req(input: ImportInput, label: String = "TextNow", account: String = "+1 555 010 0100", self: Set<String> = emptySet(), shift: Long = 0, force: Boolean = false) =
        ImportRequest(input, providerLabel = label, account = account, selfSenders = self, timeShiftMs = shift, force = force, options = ParseOptions(zone = ZoneOffset.UTC))

    @Test fun firstImportCreatesConversationAndKeepsTheOriginalFile() {
        val originals = MemoryOriginalStore()
        val (a, _) = newArchive(originals)
        val e = ImportEngine(a)
        val file = Fx.csv(Fx.conversation(40))
        val p = e.preview(req(file))
        assertTrue(p.canCommit)
        assertEquals(1, p.groups.size)
        assertEquals(40, p.groups[0].newMessages)
        assertNull("nothing is written by a preview", a.conversations().firstOrNull())
        val r = e.commit(req(file))
        assertEquals(40, r.groups[0].added)
        assertTrue(r.groups[0].createdConversation)
        val conv = a.conversation(r.groups[0].conversation)!!
        assertEquals(40L, conv.messageCount)
        assertEquals("TextNow", conv.provider)
        val msgs = a.messages(conv.id, 100)
        assertEquals(Fx.conversation(40).count { it.out }, msgs.count { it.direction == Direction.OUT })
        assertTrue("original bytes preserved separately", originals.get(file.sha256)!!.contentEquals(file.bytes))
        assertTrue(e.originalFile(r.groups[0].importId!!)!!.contentEquals(file.bytes))
    }

    @Test fun reimportingTheSameFileChangesNothing() {
        val (a, db) = newArchive()
        val e = ImportEngine(a)
        val file = Fx.csv(Fx.conversation(30))
        e.commit(req(file))
        val before = db.rows("SELECT COUNT(*) n FROM messages")[0]["n"]
        val again = e.commit(req(file))
        assertNotNull(again.skippedReason)
        assertEquals(before, db.rows("SELECT COUNT(*) n FROM messages")[0]["n"])
        assertEquals(1, db.rows("SELECT id FROM imports").size)
        // Even when forced, content matching still adds nothing.
        val forced = e.commit(req(file, force = true))
        assertEquals(0, forced.groups[0].added)
        assertEquals(30, forced.groups[0].matched)
        assertEquals(before, db.rows("SELECT COUNT(*) n FROM messages")[0]["n"])
    }

    @Test fun newerOverlappingExportAddsOnlyTheNewMessagesAndKeepsHistoryIntact() {
        val (a, _) = newArchive()
        val e = ImportEngine(a)
        val all = Fx.conversation(60)
        val first = e.commit(req(Fx.csv(all.take(40))))
        val conv = first.groups[0].conversation
        val idsBefore = a.messages(conv, 100).map { it.id }
        val second = e.commit(req(Fx.csv(all, "conversation-later.csv")))
        val g = second.groups[0]
        assertEquals("matched into the same conversation automatically", conv, g.conversation)
        assertFalse(g.createdConversation)
        assertEquals(20, g.added)
        assertEquals(40, g.matched)
        val after = a.messages(conv, 200)
        assertEquals(60, after.size)
        assertEquals("earlier messages keep their ids", idsBefore, after.take(40).map { it.id })
        val hist = e.history(conv)
        assertEquals(listOf(40, 20), hist.map { it.added })
        assertEquals(listOf(40L, 60L), hist.map { it.totalAfter })
        assertEquals(1, a.conversations().size)
    }

    @Test fun identicalTextsThatAreLegitimatelyRepeatedAreNotCollapsedOrDuplicated() {
        val (a, _) = newArchive()
        val e = ImportEngine(a)
        val t = Fx.BASE
        // Two "ok" in the same second, then a third arrives in a later export.
        val v1 = listOf(M(true, "ok", t), M(true, "ok", t), M(false, "great", t + 5000))
        val v2 = v1 + M(true, "ok", t + 9000)
        val r1 = e.commit(req(Fx.csv(v1)))
        assertEquals("both identical messages are kept", 3, r1.groups[0].added)
        val r2 = e.commit(req(Fx.csv(v2, "later.csv")))
        assertEquals(1, r2.groups[0].added)
        assertEquals(3, r2.groups[0].matched)
        assertEquals(4L, a.conversation(r2.groups[0].conversation)!!.messageCount)
        // The same pair imported again must not create a third copy.
        val r3 = e.commit(req(Fx.csv(v2, "again.csv"), force = true))
        assertEquals(0, r3.groups[0].added)
    }

    @Test fun aSecondIdenticalMessageInANewerExportIsAddedAndNeverAbsorbedByTheFirst() {
        val (a, _) = newArchive()
        val e = ImportEngine(a)
        val t = Fx.BASE
        val conv = e.commit(req(Fx.csv(listOf(M(true, "ok", t), M(false, "see you", t + 4000))))).groups[0].conversation
        // The newer export holds TWO "ok" in that same second: one is already saved, one is genuinely new.
        val r = e.commit(req(Fx.csv(listOf(M(true, "ok", t), M(true, "ok", t), M(false, "see you", t + 4000)), "later.csv")))
        assertEquals(1, r.groups[0].added)
        assertEquals(2, r.groups[0].matched)
        assertEquals(listOf("ok", "ok", "see you"), a.messages(conv, 10).map { it.body })
    }

    @Test fun messagesMissingFromANewerExportAreNeverDeleted() {
        val (a, _) = newArchive()
        val e = ImportEngine(a)
        val all = Fx.conversation(50)
        val r1 = e.commit(req(Fx.csv(all.take(30))))
        val conv = r1.groups[0].conversation
        // The newer export only covers the later window (the user trimmed it, or the app truncated history).
        e.commit(req(Fx.csv(all.drop(20), "recent-only.csv")))
        val bodies = a.messages(conv, 200)
        assertEquals(50, bodies.size)
        assertEquals(all.map { it.text }, bodies.map { it.body })
    }

    @Test fun exportsWithDifferentTimestampPrecisionMatchInsteadOfDuplicating() {
        val (a, _) = newArchive()
        val e = ImportEngine(a)
        val msgs = Fx.conversation(30, gapSec = 97)
        val r1 = e.commit(req(Fx.csv(msgs, secondsPrecision = true)))
        // Re-export at minute precision (seconds dropped).
        val r2 = e.commit(req(Fx.csv(msgs, "minute.csv", secondsPrecision = false)))
        assertEquals(0, r2.groups[0].added)
        assertEquals(30, r2.groups[0].matched)
        assertEquals(r1.groups[0].conversation, r2.groups[0].conversation)
    }

    @Test fun anExportShiftedByATimeZoneIsDetectedAndNotSilentlyDuplicated() {
        val (a, _) = newArchive()
        val e = ImportEngine(a)
        val msgs = Fx.conversation(40, gapSec = 7200)
        e.commit(req(Fx.csv(msgs)))
        // Same conversation, but the exporter this time wrote local time five hours behind UTC, which we then read as UTC.
        val shifted = Fx.csv(msgs, "shifted.csv", zone = ZoneOffset.ofHours(-5))
        val preview = e.preview(req(shifted))
        val g = preview.groups[0]
        assertNotNull("a constant offset is suggested", g.suggestedTimeShiftMs)
        assertEquals("add five hours to the incoming times to land on the saved ones", 5L * 3_600_000, g.suggestedTimeShiftMs)
        assertTrue(preview.warnings.any { it.contains("offset") })
        // Applying the owner-confirmed shift makes everything match.
        val r = e.commit(req(shifted, shift = g.suggestedTimeShiftMs!!))
        assertEquals(0, r.groups[0].added)
        assertEquals(40, r.groups[0].matched)
    }

    @Test fun aStableProviderIdMatchesEvenWhenTheTimestampMoved() {
        val (a, db) = newArchive()
        val e = ImportEngine(a)
        val first = ImportInput("a.json", """[{"id":"m1","date":"2026-01-05 12:00:00","direction":"sent","body":"hello","phone":"+15550100101"}]""".toByteArray())
        val second = ImportInput("b.json", """[{"id":"m1","date":"2026-01-05 12:00:30","direction":"sent","body":"hello","phone":"+15550100101"},{"id":"m2","date":"2026-01-05 12:01:00","direction":"received","body":"hi","phone":"+15550100101"}]""".toByteArray())
        e.commit(req(first))
        val r = e.commit(req(second))
        assertEquals(1, r.groups[0].added)
        assertEquals(1, r.groups[0].matched)
        assertEquals(2L, db.rows("SELECT COUNT(*) n FROM messages")[0]["n"]!!.toLong())
    }

    @Test fun editedContentWithTheSameIdBecomesAConflictAndNeverOverwritesSilently() {
        val (a, _) = newArchive()
        val e = ImportEngine(a)
        val v1 = ImportInput("a.json", """[{"id":"m1","date":"2026-01-05 12:00:00","direction":"sent","body":"meet at five","phone":"+15550100101"}]""".toByteArray())
        val v2 = ImportInput("b.json", """[{"id":"m1","date":"2026-01-05 12:00:00","direction":"sent","body":"meet at six","phone":"+15550100101"}]""".toByteArray())
        val conv = e.commit(req(v1)).groups[0].conversation
        val r = e.commit(req(v2))
        assertEquals(1, r.groups[0].conflicts)
        assertEquals("meet at five", a.messages(conv, 10)[0].body)
        val c = a.openConflicts(conv)
        assertEquals(1, c.size)
        a.resolveConflict(c[0].long("id"), Resolution.TAKE_INCOMING)
        assertEquals("meet at six", a.messages(conv, 10)[0].body)
        assertEquals("search index follows the edit", 1, a.search("six", conv).size)
        assertEquals("the replaced text is no longer indexed", 0, a.search("five", conv).size)
        assertTrue(a.openConflicts(conv).isEmpty())
    }

    @Test fun keepBothAddsTheIncomingVersionAsAnotherMessage() {
        val (a, _) = newArchive()
        val e = ImportEngine(a)
        val v1 = ImportInput("a.json", """[{"id":"m1","date":"2026-01-05 12:00:00","direction":"sent","body":"first version","phone":"+15550100101"}]""".toByteArray())
        val v2 = ImportInput("b.json", """[{"id":"m1","date":"2026-01-05 12:00:00","direction":"sent","body":"second version","phone":"+15550100101"}]""".toByteArray())
        val conv = e.commit(req(v1)).groups[0].conversation
        e.commit(req(v2))
        a.resolveConflict(a.openConflicts(conv)[0].long("id"), Resolution.KEEP_BOTH)
        assertEquals(listOf("first version", "second version"), a.messages(conv, 10).map { it.body })
    }

    @Test fun anExportWithoutDirectionAsksWhoTheOwnerIsThenRemembersIt() {
        val (a, _) = newArchive()
        val e = ImportEngine(a)
        val msgs = Fx.conversation(12)
        val file = Fx.csv(msgs, withDirection = false)
        val blocked = e.preview(req(file))
        assertFalse(blocked.canCommit)
        assertEquals(setOf("Don", "Sam"), blocked.unresolvedSenders.toSet())
        try { e.commit(req(file)); fail("must not guess who the owner is") } catch (_: IllegalStateException) { }
        assertEquals("nothing was written", 0, a.conversations().size)
        val r = e.commit(req(file, self = setOf("Don")))
        val conv = r.groups[0].conversation
        assertEquals(msgs.count { it.out }, a.messages(conv, 100).count { it.direction == Direction.OUT })
        // The later export of the same chat does not ask again.
        val later = Fx.csv(Fx.conversation(18), "later.csv", withDirection = false)
        val p2 = e.preview(req(later))
        assertTrue("owner identity remembered for this provider+account", p2.canCommit)
        assertEquals(conv, p2.groups[0].target)
        assertEquals(6, p2.groups[0].newMessages)
    }

    @Test fun incomingMessagesNeverCountAsTheOwnersWritingStyleSamples() {
        val (a, _) = newArchive()
        val e = ImportEngine(a)
        val msgs = Fx.conversation(45)
        val conv = e.commit(req(Fx.csv(msgs))).groups[0].conversation
        val sample = a.outgoingForStyle(listOf(conv))
        assertEquals(msgs.count { it.out }, sample.size)
        assertTrue(sample.all { it.direction == Direction.OUT })
        assertTrue("no incoming text leaks into the sample", sample.none { it.body.startsWith("Hey, are you coming") })
    }

    @Test fun rollbackRemovesOnlyWhatTheImportAloneAdded() {
        val originals = MemoryOriginalStore()
        val (a, _) = newArchive(originals)
        val e = ImportEngine(a)
        val all = Fx.conversation(60)
        val r1 = e.commit(req(Fx.csv(all.take(40))))
        val conv = r1.groups[0].conversation
        val f2 = Fx.csv(all, "later.csv")
        val r2 = e.commit(req(f2))
        e.rollback(r2.groups[0].importId!!)
        val left = a.messages(conv, 200)
        assertEquals("only the 20 messages unique to the second import were removed", 40, left.size)
        assertEquals(all.take(40).map { it.text }, left.map { it.body })
        assertTrue("its original file is gone", originals.get(f2.sha256) == null)
        assertTrue("an earlier import's file stays", originals.size() == 1)
        assertEquals(listOf("committed", "rolled_back"), e.history(conv).map { it.status })
        assertTrue("search no longer finds removed text", a.search(all[55].text, conv).none { it.id !in left.map { m -> m.id } })
        // The earlier import can still be rolled back, which removes the conversation it created.
        e.rollback(r1.groups[0].importId!!)
        assertNull(a.conversation(conv))
    }

    @Test fun aMessageSharedByTwoImportsSurvivesRollingBackTheFirst() {
        val (a, _) = newArchive()
        val e = ImportEngine(a)
        val all = Fx.conversation(30)
        val r1 = e.commit(req(Fx.csv(all.take(20))))
        e.commit(req(Fx.csv(all, "later.csv")))
        e.rollback(r1.groups[0].importId!!)
        assertEquals("everything the later export also contained stays", 30, a.messages(r1.groups[0].conversation, 100).size)
    }

    @Test fun importedHistoryCanNeverTriggerAnAutomaticReply() {
        val (a, _) = newArchive()
        val e = ImportEngine(a)
        val conv = e.commit(req(Fx.csv(Fx.conversation(10)))).groups[0].conversation
        val imported = a.messages(conv, 20).first { it.direction == Direction.IN }
        assertEquals("import", imported.origin)
        val gate = AutomationGate(a)
        gate.saveRule(conv, "bot-1", Mode.AUTO, enabled = true)
        assertTrue(gate.evaluate(imported.id) is GateDecision.Skip)
        try { Outbox(a).enqueueReply(imported.id, "bot-1"); fail("imported history must not queue a reply") } catch (_: IllegalArgumentException) { }
    }

    @Test fun unicodeEmojiAndMultilineTextRoundTripUnchangedAndSearchable() {
        val (a, _) = newArchive()
        val e = ImportEngine(a)
        val msgs = listOf(
            M(true, "café ☕ 😂👍 日本語テスト", Fx.BASE), M(false, "Line one\nLine two\n\nLine four", Fx.BASE + 60_000),
            M(true, "\"quoted\", with, commas", Fx.BASE + 120_000), M(true, "ÀÉÎÕÜ ñ ß", Fx.BASE + 180_000),
        )
        val conv = e.commit(req(Fx.csv(msgs))).groups[0].conversation
        assertEquals(msgs.map { it.text }, a.messages(conv, 10).map { it.body })
        assertEquals(1, a.search("café", conv).size)
        assertEquals(1, a.search("日本語テスト", conv).size)
        assertEquals(1, a.search("CAFÉ", conv).size)
        assertTrue("operators in user text cannot break the query", a.search("\"; DROP TABLE messages; --", conv).isEmpty())
        assertEquals(4L, a.conversation(conv)!!.messageCount)
    }

    @Test fun smsBackupWithSeveralThreadsBecomesSeveralConversations() {
        val (a, _) = newArchive()
        val e = ImportEngine(a)
        val one = Fx.conversation(8)
        val two = Fx.conversation(5, start = Fx.BASE + 1_000_000)
        val xml = String(Fx.smsXml(one, "+1 (555) 010-0101").bytes).replace("</smses>", "") + String(Fx.smsXml(two, "5550100202").bytes).substringAfter("<smses count=\"5\">")
        val r = e.commit(ImportRequest(ImportInput("all.xml", xml.toByteArray()), providerLabel = "Android SMS", options = ParseOptions(zone = ZoneOffset.UTC)))
        assertEquals(2, r.groups.size)
        assertEquals(setOf(8L, 5L), a.conversations().map { it.messageCount }.toSet())
    }

    @Test fun anInexactMatchIsNeverMergedAutomaticallyButCanBeMergedManually() {
        val (a, _) = newArchive()
        val e = ImportEngine(a)
        val first = Fx.csv(Fx.conversation(10)).let { ImportInput(it.name, String(it.bytes).replace("+1 555 010 0101", "Sam").toByteArray()) }
        val c1 = e.commit(req(first)).groups[0].conversation
        // Same person, now saved under a different identifier: automatic matching must not guess.
        val renamed = Fx.csv(Fx.conversation(14), "renamed.csv").let { ImportInput(it.name, String(it.bytes).replace("+1 555 010 0101", "Samuel").toByteArray()) }
        val p = e.preview(req(renamed))
        assertNull("no silent merge on an inexact match", p.groups[0].target)
        val manual = e.commit(req(renamed).copy(targetConversation = c1))
        assertEquals(c1, manual.groups[0].conversation)
        assertEquals(4, manual.groups[0].added)
    }

    @Test fun unsupportedHtmlIsRefusedClearlyAndWritesNothing() {
        val (a, _) = newArchive()
        val e = ImportEngine(a)
        val p = e.preview(ImportRequest(ImportInput("chat.html", "<html><body><div>hi</div></body></html>".toByteArray())))
        assertFalse(p.canCommit)
        assertTrue(p.blockedReason!!.contains("HTML"))
        assertEquals(0, a.conversations().size)
    }

    @Test fun csvWithoutRecognisableColumnsAsksForAMapping() {
        val (a, _) = newArchive()
        val e = ImportEngine(a)
        val odd = ImportInput("odd.csv", "when,who,what\n2026-01-05 12:00:00,Sam,hello\n".toByteArray())
        val p = e.preview(ImportRequest(odd, importerId = "csv"))
        assertFalse(p.canCommit)
        assertTrue(p.needsMapping.contains("body"))
        val mapped = e.commit(ImportRequest(odd, importerId = "csv", selfSenders = setOf("Don"), options = ParseOptions(mapping = ColumnMapping(timestamp = "when", sender = "who", body = "what"))))
        assertEquals(1, mapped.groups[0].added)
        assertEquals(1, a.conversations().size)
    }

    @Test fun aLargeArchiveReimportsQuicklyAndExactly() {
        val (a, db) = newArchive()
        val e = ImportEngine(a)
        val big = Fx.conversation(20_000, gapSec = 31)
        val t0 = System.nanoTime()
        e.commit(req(Fx.csv(big)))
        val grown = big + Fx.conversation(500, start = big.last().tsMs + 31_000, gapSec = 31)
        val r = e.commit(req(Fx.csv(grown, "grown.csv")))
        val secs = (System.nanoTime() - t0) / 1e9
        assertEquals(500, r.groups[0].added)
        assertEquals(20_000, r.groups[0].matched)
        assertEquals(20_500L, db.rows("SELECT COUNT(*) n FROM messages")[0]["n"]!!.toLong())
        assertTrue("took ${secs}s", secs < 90)
    }
}
