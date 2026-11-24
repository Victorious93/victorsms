package com.victorious.comms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.ZoneOffset

class ArchiveTest {
    private val opts = ParseOptions(zone = ZoneOffset.UTC)
    private fun req(i: ImportInput) = ImportRequest(i, providerLabel = "TextNow", account = "5550100100", options = opts)

    private fun seeded(originals: OriginalStore = MemoryOriginalStore()): Triple<Archive, JdbcSqlDb, Long> {
        val (a, db) = newArchive(originals)
        val conv = ImportEngine(a).commit(req(Fx.csv(Fx.conversation(30)))).groups[0].conversation
        return Triple(a, db, conv)
    }

    @Test fun theSchemaMigratesOnceAndRefusesADatabaseFromTheFuture() {
        val db = JdbcSqlDb()
        Archive(db)
        Archive(db) // opening again is a no-op
        assertEquals("1", db.rows("PRAGMA user_version")[0]["user_version"])
        db.exec("PRAGMA user_version = 99")
        try { Archive(db); fail("must not run against a newer schema") } catch (_: IllegalArgumentException) { }
    }

    @Test fun encryptedBackupRestoresEverythingIncludingSearchAndRejectsBadPassphraseOrTampering() {
        val (a, _, conv) = seeded()
        a.addFact(conv, "Sam's birthday is in June", approved = true)
        val blob = a.exportEncrypted("correct horse".toCharArray())
        assertTrue("ciphertext, not JSON", String(blob, Charsets.ISO_8859_1).contains("hahaha").not())

        val (b, _) = newArchive()
        try { b.restoreEncrypted(blob, "wrong".toCharArray()); fail() } catch (_: IllegalArgumentException) { }
        assertEquals("a failed restore changes nothing", 0, b.conversations().size)
        val tampered = blob.clone().also { it[it.size - 5] = (it[it.size - 5].toInt() xor 1).toByte() }
        try { b.restoreEncrypted(tampered, "correct horse".toCharArray()); fail() } catch (_: IllegalArgumentException) { }
        try { b.restoreEncrypted("nope".toByteArray(), "x".toCharArray()); fail() } catch (_: IllegalArgumentException) { }

        b.restoreEncrypted(blob, "correct horse".toCharArray())
        assertEquals(1, b.conversations().size)
        assertEquals(30L, b.conversations()[0].messageCount)
        assertEquals(a.messages(conv, 100).map { it.body }, b.messages(b.conversations()[0].id, 100).map { it.body })
        assertEquals("the index is rebuilt", a.search("lol").size, b.search("lol").size)
        assertEquals("Sam's birthday is in June", b.facts(b.conversations()[0].id)[0]["text"])
    }

    @Test fun deletingAConversationRemovesMessagesIndexAndOriginalsAndHonoursTheDerivedDataChoice() {
        val originals = MemoryOriginalStore()
        val (a, db, conv) = seeded(originals)
        a.addFact(conv, "likes tea", approved = true)
        a.setSummary(conv, Fx.BASE, "they discussed dinner")
        assertTrue(a.search("café", conv).isNotEmpty() || a.search("lol", conv).isNotEmpty())
        a.deleteConversation(conv, deleteFacts = false, deleteSummaries = true, deleteOriginals = true)
        assertNull(a.conversation(conv))
        assertEquals(0L, db.count("SELECT COUNT(*) n FROM messages"))
        assertEquals("no searchable text survives", 0, a.search("lol").size)
        assertEquals(0L, db.count("SELECT COUNT(*) n FROM message_fts"))
        assertEquals(0L, db.count("SELECT COUNT(*) n FROM summaries"))
        assertEquals("the owner chose to keep facts", 1L, db.count("SELECT COUNT(*) n FROM facts"))
        assertEquals(0, originals.size())
        a.deleteDerived(conv, facts = true, summaries = true)
        assertEquals(0L, db.count("SELECT COUNT(*) n FROM facts"))
    }

    @Test fun retrievalReturnsBoundedRelevantContextAndOnlyApprovedFacts() {
        val (a, _) = newArchive()
        val conv = a.createConversation("textnow", "5550100100", "k", "Sam", false)
        for (i in 0 until 300) a.recordLive(conv, if (i % 2 == 0) Direction.IN else Direction.OUT, if (i % 2 == 0) "p:1" else "self:me", "x", "filler message number $i about nothing", Fx.BASE + i * 1000L)
        a.recordLive(conv, Direction.IN, "p:1", "Sam", "Please resend the invoice for the garage repair", Fx.BASE - 500_000)
        a.addFact(conv, "prefers email for invoices", approved = true)
        a.addFact(conv, "UNAPPROVED guess about their job", approved = false)
        val ctx = ContextRetriever(a).retrieve(conv, "can you resend that invoice?", ContextBudget(recent = 12, relevant = 4, maxChars = 100_000))
        assertEquals(12, ctx.recent.size)
        assertTrue("an old relevant message is found by keyword", ctx.relevant.any { it.body.contains("invoice") })
        assertTrue("relevant never repeats a recent message", ctx.relevant.none { r -> ctx.recent.any { it.id == r.id } })
        assertEquals(listOf("prefers email for invoices"), ctx.facts)
        assertTrue("far fewer messages than the archive", ctx.recent.size + ctx.relevant.size + ctx.examples.size < 40)

        val tight = ContextRetriever(a).retrieve(conv, "invoice", ContextBudget(maxChars = 300))
        assertTrue(tight.truncated)
        assertTrue("budget is a hard cap: ${tight.chars}", tight.chars <= 300)
    }

    @Test fun styleSamplesExcludeUnapprovedBotTextButKeepOwnerEditedText() {
        val (a, _) = newArchive()
        val conv = a.createConversation("textnow", "5550100100", "k", "Sam", false)
        a.recordLive(conv, Direction.OUT, "self:me", "Me", "typed by me", Fx.BASE, author = Author.OWNER)
        a.recordLive(conv, Direction.OUT, "self:me", "Me", "sent by the bot on its own", Fx.BASE + 1000, author = Author.BOT_AUTO)
        a.recordLive(conv, Direction.OUT, "self:me", "Me", "bot draft I approved unedited", Fx.BASE + 2000, author = Author.BOT_APPROVED)
        a.recordLive(conv, Direction.OUT, "self:me", "Me", "bot draft I rewrote", Fx.BASE + 3000, author = Author.BOT_EDITED)
        a.recordLive(conv, Direction.IN, "p:1", "Sam", "their message", Fx.BASE + 4000)
        assertEquals(listOf("typed by me", "bot draft I rewrote"), a.outgoingForStyle(listOf(conv)).map { it.body })
    }

    @Test fun mergingTwoParticipantsMovesTheirMessages() {
        val (a, _, conv) = seeded()
        a.upsertParticipant(conv, "n:sam smith", "Sam Smith", null, false)
        val before = a.messages(conv, 100).count { it.sender.isNotEmpty() }
        val keys = a.participants(conv).filter { it["is_self"] == "0" }.map { it["key"]!! }
        assertNotNull(keys.firstOrNull())
        a.mergeParticipants(conv, keys.first { it != "n:sam smith" }, "n:sam smith")
        assertEquals(before, a.messages(conv, 100).count { it.sender.isNotEmpty() })
        assertTrue(a.participants(conv).none { it["key"] == "n:sam smith" })
    }

    @Test fun aSearchBoxCanRequireEveryWordWhileRetrievalKeepsAnyWord() {
        val (a, _) = newArchive()
        val conv = a.createConversation("textnow", "5550100100", "k", "Sam", false)
        a.recordLive(conv, Direction.IN, "p:1", "Sam", "dinner on friday at luigi", Fx.BASE)
        a.recordLive(conv, Direction.IN, "p:1", "Sam", "lunch on monday", Fx.BASE + 1000)
        a.recordLive(conv, Direction.IN, "p:1", "Sam", "friday standup", Fx.BASE + 2000)
        assertEquals("any word is the default (context recall)", 3, a.search("friday monday dinner", conv).size)
        assertEquals("every word for a search box", 1, a.search("dinner friday", conv, matchAll = true).size)
        assertEquals(0, a.search("dinner monday", conv, matchAll = true).size)
        assertTrue("operators stay inert in both modes", a.search("friday\" OR \"x", conv, matchAll = true).isEmpty())
    }

    @Test fun directoryOriginalStoreRefusesPathTricks() {
        val dir = java.nio.file.Files.createTempDirectory("orig").toFile()
        val s = DirOriginalStore(dir)
        val sha = Hashing.sha256Hex("x")
        s.put(sha, "x".toByteArray())
        assertEquals("x", String(s.get(sha)!!))
        s.delete(sha)
        assertNull(s.get(sha))
        try { s.get("../../etc/passwd"); fail() } catch (_: IllegalArgumentException) { }
        dir.deleteRecursively()
    }
}
