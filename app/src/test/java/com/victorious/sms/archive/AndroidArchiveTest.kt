package com.victorious.sms.archive

import android.database.sqlite.SQLiteDatabase
import com.victorious.comms.Archive
import com.victorious.comms.AutomationGate
import com.victorious.comms.Direction
import com.victorious.comms.GateDecision
import com.victorious.comms.ImportEngine
import com.victorious.comms.ImportInput
import com.victorious.comms.ImportRequest
import com.victorious.comms.MemoryOriginalStore
import com.victorious.comms.Mode
import com.victorious.comms.Outbox
import com.victorious.comms.ParseOptions
import com.victorious.comms.Resolution
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.ZoneOffset

/**
 * Victorious Framework. Runs the real archive on Android's SQLite (Robolectric native mode, API 34). This catches dialect
 * and binding differences from the JDBC tests in `comms-core` (FTS4 + unicode61, partial unique indexes, string-bound
 * queries, typed writes, nested transactions). It is NOT a device or emulator run.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AndroidArchiveTest {
    private lateinit var sqlite: SQLiteDatabase
    private lateinit var db: AndroidSqlDb
    private lateinit var archive: Archive
    private lateinit var engine: ImportEngine
    private val originals = MemoryOriginalStore()
    private var now = 1_800_000_000_000L

    @Before fun open() {
        sqlite = SQLiteDatabase.create(null)
        db = AndroidSqlDb(sqlite)
        archive = Archive(db, originals) { now }
        engine = ImportEngine(archive)
    }

    @After fun close() { sqlite.close() }

    private fun csv(vararg rows: Triple<String, Boolean, String>, name: String = "chat.csv"): ImportInput {
        val sb = StringBuilder("Date,Direction,Body,Phone\n")
        for ((ts, out, body) in rows) sb.append("$ts,${if (out) "sent" else "received"},\"${body.replace("\"", "\"\"")}\",+1 555 010 0101\n")
        return ImportInput(name, sb.toString().toByteArray())
    }

    private fun req(i: ImportInput, force: Boolean = false) =
        ImportRequest(i, providerLabel = "TextNow", account = "5550100100", force = force, options = ParseOptions(zone = ZoneOffset.UTC))

    private val base = listOf(
        Triple("2026-01-05 12:00:00", false, "Hey, café at 5? ☕"),
        Triple("2026-01-05 12:01:10", true, "yeah lol 😂"),
        Triple("2026-01-05 12:02:20", true, "ok"),
        Triple("2026-01-05 12:02:20", true, "ok"),
        Triple("2026-01-05 12:03:30", false, "Line one\nLine two"),
    )

    @Test fun theSchemaMigratesOnAndroidSqliteAndTheVersionIsOurs() {
        assertEquals("2", db.rows("PRAGMA user_version")[0]["user_version"])
        Archive(db) // reopening is a no-op
        assertEquals("2", db.rows("PRAGMA user_version")[0]["user_version"])
        val tables = db.rows("SELECT name FROM sqlite_master WHERE type='table'").map { it["name"] }
        assertTrue(tables.containsAll(listOf("conversations", "messages", "message_fts", "imports", "outbox")))
    }

    @Test fun importUpdateSearchAndRollbackWorkOnAndroidsSqlite() {
        val first = engine.commit(req(csv(*base.take(3).toTypedArray())))
        val conv = first.groups[0].conversation
        assertEquals(3, first.groups[0].added)
        val later = engine.commit(req(csv(*base.toTypedArray(), name = "later.csv")))
        assertEquals("the same conversation, found by string-bound queries", conv, later.groups[0].conversation)
        assertEquals(2, later.groups[0].added)
        assertEquals(3, later.groups[0].matched)
        assertEquals("both identical 'ok' kept, none duplicated", 5L, archive.messageCount(conv))
        val bodies = archive.messages(conv, 20).map { it.body }
        assertEquals(base.map { it.third }, bodies)
        assertEquals("unicode61 folds case and accents' case", 1, archive.search("CAFÉ", conv).size)
        assertEquals(1, archive.search("lol", conv).size)
        assertTrue("operators in user text cannot break the query", archive.search("\"; DROP TABLE messages; --", conv).isEmpty())
        engine.rollback(later.groups[0].importId!!)
        assertEquals(3L, archive.messageCount(conv))
        assertEquals("index follows the rollback", 0, archive.search("Line", conv).size)
        assertEquals(listOf("committed", "rolled_back"), engine.history(conv).map { it.status })
    }

    @Test fun anExceptionInsideATransactionRollsEverythingBack() {
        try {
            db.tx {
                db.insert("INSERT INTO settings(key,value) VALUES(?,?)", "k", "v")
                db.tx { db.insert("INSERT INTO settings(key,value) VALUES(?,?)", "k2", "v2") }
                error("boom")
            }
            fail()
        } catch (_: IllegalStateException) { }
        assertTrue(db.rows("SELECT * FROM settings").isEmpty())
        db.tx { db.insert("INSERT INTO settings(key,value) VALUES(?,?)", "k", "v") }
        assertEquals(1, db.rows("SELECT * FROM settings").size)
    }

    @Test fun typedWritesBindBooleansIntegersNullsAndLongsCorrectly() {
        val conv = engine.commit(req(csv(*base.take(1).toTypedArray()))).groups[0].conversation
        val rule = AutomationGate(archive).saveRule(conv, "bot-1", Mode.AUTO, enabled = true, hoursStart = 22, hoursEnd = 6, expires = 1_900_000_000_000L)
        assertTrue(rule.enabled)
        assertEquals(22, rule.hoursStart)
        assertEquals(1_900_000_000_000L, rule.expires)
        val noHours = AutomationGate(archive).saveRule(conv, "bot-2", Mode.APPROVAL, enabled = false)
        assertEquals(null, noHours.hoursStart)
        assertEquals("a boolean written as 0 reads back false", false, noHours.enabled)
        assertEquals("the partial unique index (one active bot) holds on Android", "bot-1", AutomationGate(archive).activeRule(conv)!!.botRef)
        try { AutomationGate(archive).saveRule(conv, "bot-2", Mode.AUTO, enabled = true); fail("two active bots") } catch (_: IllegalArgumentException) { }
        try {
            db.insert("INSERT INTO auto_rules(conversation,bot_ref,mode,enabled) VALUES(?,?,?,?)", conv, "bot-3", "AUTO", true)
            fail("the database itself must refuse a second active bot")
        } catch (_: android.database.sqlite.SQLiteConstraintException) { }
    }

    @Test fun liveMessagesGateAndOutboxBehaveTheSameOnAndroid() {
        val conv = engine.commit(req(csv(*base.toTypedArray()))).groups[0].conversation
        val gate = AutomationGate(archive)
        gate.saveRule(conv, "bot-1", Mode.AUTO, enabled = true)
        val imported = archive.messages(conv, 10).first { it.direction == Direction.IN }
        assertTrue("imported history never triggers", gate.evaluate(imported.id, now, ZoneOffset.UTC) is GateDecision.Skip)
        val live = archive.recordLive(conv, Direction.IN, "p:5550100101", "Sam", "are you there?", now, "sms-1")
        assertEquals("redelivery is idempotent", live, archive.recordLive(conv, Direction.IN, "p:5550100101", "Sam", "are you there?", now, "sms-1"))
        assertTrue(gate.evaluate(live, now, ZoneOffset.UTC) is GateDecision.AutoSend)
        val outbox = Outbox(archive)
        val e = outbox.enqueueReply(live, "bot-1")
        outbox.setBody(e.uid, "yes")
        assertTrue(outbox.markSending(e.uid))
        assertFalse("compare-and-set holds on Android", outbox.markSending(e.uid))
        assertEquals(1, outbox.recoverAfterRestart())
        assertEquals("unknown", outbox.get(e.uid)!!.state.name.lowercase())
    }

    @Test fun conflictsAndTheirResolutionWorkOnAndroid() {
        val a = ImportInput("a.json", """[{"id":"m1","date":"2026-01-05 12:00:00","direction":"sent","body":"meet at five","phone":"+15550100101"}]""".toByteArray())
        val b = ImportInput("b.json", """[{"id":"m1","date":"2026-01-05 12:00:00","direction":"sent","body":"meet at six","phone":"+15550100101"}]""".toByteArray())
        val conv = engine.commit(req(a)).groups[0].conversation
        assertEquals(1, engine.commit(req(b)).groups[0].conflicts)
        archive.resolveConflict(archive.openConflicts(conv)[0]["id"]!!.toLong(), Resolution.TAKE_INCOMING)
        assertEquals(1, archive.search("six", conv).size)
        assertEquals(0, archive.search("five", conv).size)
    }

    @Test fun encryptedBackupRestoresIntoAFreshAndroidDatabase() {
        val conv = engine.commit(req(csv(*base.toTypedArray()))).groups[0].conversation
        archive.addFact(conv, "likes tea", approved = true)
        val blob = archive.exportEncrypted("correct horse".toCharArray())
        val other = SQLiteDatabase.create(null)
        try {
            val restored = Archive(AndroidSqlDb(other), MemoryOriginalStore()) { now }
            try { restored.restoreEncrypted(blob, "wrong".toCharArray()); fail() } catch (_: IllegalArgumentException) { }
            assertEquals(0, restored.conversations().size)
            restored.restoreEncrypted(blob, "correct horse".toCharArray())
            val c = restored.conversations().single()
            assertEquals(5L, c.messageCount)
            assertEquals(1, restored.search("café", c.id).size)
            assertEquals("likes tea", restored.facts(c.id)[0]["text"])
        } finally {
            other.close()
        }
    }

    @Test fun deletingAConversationLeavesNoSearchableText() {
        val conv = engine.commit(req(csv(*base.toTypedArray()))).groups[0].conversation
        archive.deleteConversation(conv)
        assertEquals(0, archive.search("café").size)
        assertEquals(0L, db.rows("SELECT COUNT(*) n FROM message_fts")[0]["n"]!!.toLong())
        assertEquals("the original export file went with it", 0, originals.size())
    }

    @Test fun aLargerArchiveImportsAndReimportsOnAndroidSqlite() {
        val rows = (0 until 3000).map { Triple("2026-01-05 %02d:%02d:%02d".format(8 + it / 3600, it / 60 % 60, it % 60), it % 3 != 0, "message number $it about nothing") }
        val first = engine.commit(req(csv(*rows.toTypedArray())))
        assertEquals(3000, first.groups[0].added)
        val again = engine.commit(req(csv(*rows.toTypedArray(), name = "again.csv"), force = true))
        assertEquals(0, again.groups[0].added)
        assertEquals(3000, again.groups[0].matched)
        assertEquals("search-box mode needs every word", 1, archive.search("number 2999", matchAll = true).size)
        assertEquals("retrieval mode matches any word", 25, archive.search("number 2999").size)
    }
}
