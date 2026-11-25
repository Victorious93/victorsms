package com.victorious.comms

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.ZoneOffset

/** Victorious Framework — export a saved conversation to a file, and prove the importers read it back. */
class ExporterTest {
    private fun tricky(): List<M> = Fx.conversation(60) + listOf(
        M(true, "has, a comma and a \"quote\"", Fx.BASE + 10_000_000),
        M(false, "=SUM(1,2) looks like a formula", Fx.BASE + 10_100_000),
        M(true, "two\nlines\r\nand a third", Fx.BASE + 10_200_000),
        M(false, "emoji 👍 and café ☕ and 日本語", Fx.BASE + 10_300_000),
        M(true, "ok", Fx.BASE + 10_400_000), M(true, "ok", Fx.BASE + 10_460_000),
    )

    private fun importInto(a: Archive, input: ImportInput): Long {
        val r = ImportEngine(a).commit(ImportRequest(input, providerLabel = "TextNow", options = ParseOptions(zone = ZoneOffset.UTC)))
        return r.groups.single().conversation
    }

    private fun export(a: Archive, c: Long, f: ExportFormat, o: ExportOptions = ExportOptions()): String =
        StringBuilder().also { Exporter.write(a, c, f, it, o) }.toString()

    private fun view(a: Archive, c: Long) = a.messages(c, 10_000).map { Triple(it.direction, it.body, it.ts) }

    @Test fun csvAndJsonRoundTripEveryMessageExactly() {
        for (f in listOf(ExportFormat.CSV, ExportFormat.JSON)) {
            val (a, _) = newArchive()
            val src = importInto(a, Fx.csv(tricky()))
            val file = export(a, src, f)
            val (b, _) = newArchive()
            val back = importInto(b, ImportInput("out.${f.extension}", file.toByteArray()))
            assertEquals("$f: every message, in order, same direction/text/time", view(a, src), view(b, back))
            assertEquals(66L, b.messageCount(back))
        }
    }

    @Test fun jsonIsValidAndCsvHasTheDocumentedHeader() {
        val (a, _) = newArchive()
        val c = importInto(a, Fx.csv(tricky()))
        val json = Json.parseToJsonElement(export(a, c, ExportFormat.JSON)).jsonObject
        assertEquals("victorsms-conversation", json["format"]!!.jsonPrimitive.content)
        val msgs: JsonArray = json["messages"]!!.jsonArray
        assertEquals(66, msgs.size)
        assertEquals(setOf("timestamp", "direction", "sender", "body", "id", "written_by", "source"), (msgs[0] as JsonObject).keys)
        assertTrue(export(a, c, ExportFormat.CSV).startsWith("timestamp,direction,sender,body,id,written_by,source\r\n"))
    }

    @Test fun pagingNeverSkipsOrRepeatsMessagesThatShareATimestamp() {
        val (a, _) = newArchive()
        val conv = LiveIngest(a).run { record("+1 (555) 010-0150", "seed", Fx.BASE, Direction.IN); a.conversations().single().id }
        val n = 1_203 // more than two pages, all at one instant
        repeat(n) { a.recordLive(conv, Direction.IN, "p:5550100150", "Sam", "m$it", Fx.BASE) }
        val rows = Csv.parse(export(a, conv, ExportFormat.CSV)).drop(1)
        assertEquals(n + 1, rows.size)
        assertEquals("every body exactly once", (0 until n).map { "m$it" }.toSet(), rows.map { it[3] }.filter { it != "seed" }.toSet())
        assertEquals("and in insertion order", (0 until n).map { "m$it" }, rows.map { it[3] }.filter { it != "seed" })
    }

    @Test fun spreadsheetSafeIsOffByDefaultAndPrefixesFormulaLookingCellsWhenOn() {
        val (a, _) = newArchive()
        val c = importInto(a, Fx.csv(tricky()))
        val plain = Csv.parse(export(a, c, ExportFormat.CSV)).drop(1).map { it[3] }
        assertTrue(plain.contains("=SUM(1,2) looks like a formula"))
        val safe = Csv.parse(export(a, c, ExportFormat.CSV, ExportOptions(spreadsheetSafe = true))).drop(1).map { it[3] }
        assertTrue(safe.contains("'=SUM(1,2) looks like a formula"))
        assertFalse(safe.contains("=SUM(1,2) looks like a formula"))
        assertTrue("ordinary text is untouched", safe.contains("has, a comma and a \"quote\""))
    }

    @Test fun botTextCanBeLeftOutAndIsLabelledWhenKept() {
        val (a, _) = newArchive()
        val conv = LiveIngest(a).run { record("+1 (555) 010-0151", "hi", Fx.BASE, Direction.IN); a.conversations().single().id }
        LiveIngest(a).record("+1 (555) 010-0151", "from me", Fx.BASE + 1000, Direction.OUT, Author.OWNER)
        LiveIngest(a).record("+1 (555) 010-0151", "from a bot", Fx.BASE + 2000, Direction.OUT, Author.BOT_APPROVED)
        val all = Csv.parse(export(a, conv, ExportFormat.CSV)).drop(1)
        assertEquals(listOf("peer", "owner", "bot_approved"), all.map { it[5] })
        val noBot = Csv.parse(export(a, conv, ExportFormat.CSV, ExportOptions(includeBotMessages = false))).drop(1)
        assertEquals(listOf("hi", "from me"), noBot.map { it[3] })
        assertEquals("a live message says so", "live", all[0][6])
    }

    @Test fun theFileNameCarriesNoNameOrNumber() {
        val (a, _) = newArchive()
        val conv = LiveIngest(a).run { record("+1 (555) 010-0152", "hi", Fx.BASE, Direction.IN); a.conversations().single().id }
        val name = Exporter.fileName(a, conv, ExportFormat.CSV, now = Fx.BASE)
        assertEquals("victorsms-sms-20260105.csv", name)
        assertFalse(name.contains("555"))
    }

    @Test fun plainTextIsReadableAndAnEmptyConversationStillProducesAValidFile() {
        val (a, _) = newArchive()
        val c = importInto(a, Fx.csv(listOf(M(false, "hello there", Fx.BASE), M(true, "hi!", Fx.BASE + 60_000))))
        val lines = export(a, c, ExportFormat.TEXT).trim().lines()
        assertEquals(2, lines.size)
        assertTrue(lines[0].startsWith("[2026-01-05 12:00:00] "))
        assertTrue(lines[0].endsWith("hello there"))
        assertTrue(lines[1].endsWith(": hi!"))
        a.db.update("DELETE FROM messages WHERE conversation=?", c)
        assertEquals(0, Json.parseToJsonElement(export(a, c, ExportFormat.JSON)).jsonObject["messages"]!!.jsonArray.size)
        assertEquals(1, Csv.parse(export(a, c, ExportFormat.CSV)).size) // header only
    }

    @Test fun anUnknownConversationIsRefused() {
        val (a, _) = newArchive()
        try { Exporter.write(a, 99, ExportFormat.CSV, StringBuilder()); fail("should refuse") } catch (e: IllegalArgumentException) { assertTrue(e.message!!.contains("does not exist")) }
    }
}
