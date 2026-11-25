package com.victorious.comms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset

class ImportersTest {
    private val utc = ParseOptions(zone = ZoneOffset.UTC)
    private fun parse(importer: Importer, name: String, text: String, o: ParseOptions = utc) = importer.parse(ImportInput(name, text.toByteArray()), o)

    @Test fun timestampFormatsAreReadExactlyAndAmbiguityIsNeverSilent() {
        val z = ZoneOffset.UTC
        assertEquals(1_767_614_400_000L, Timestamps.parse("1767614400000", z, DateOrder.MDY)!!.ms)
        assertEquals(1_767_614_400_000L, Timestamps.parse("1767614400", z, DateOrder.MDY)!!.ms)
        assertEquals(1_767_614_400_000L, Timestamps.parse("2026-01-05T12:00:00Z", z, DateOrder.MDY)!!.ms)
        assertEquals("explicit offset wins", 1_767_614_400_000L, Timestamps.parse("2026-01-05T07:00:00-05:00", z, DateOrder.MDY)!!.ms)
        assertEquals(false, Timestamps.parse("2026-01-05T07:00:00-05:00", z, DateOrder.MDY)!!.zoneAssumed)
        assertEquals(true, Timestamps.parse("2026-01-05 12:00:00", z, DateOrder.MDY)!!.zoneAssumed)
        assertEquals("12:00 AM is midnight", 1_767_571_200_000L, Timestamps.parse("1/5/26, 12:00 AM", z, DateOrder.MDY)!!.ms)
        assertEquals("12:30 PM is half past noon", 1_767_616_200_000L, Timestamps.parse("1/5/26, 12:30 PM", z, DateOrder.MDY)!!.ms)
        assertEquals("narrow no-break space before PM", 1_767_616_200_000L, Timestamps.parse("1/5/26, 12:30 PM", z, DateOrder.MDY)!!.ms)
        assertNull("month 13 is not a date", Timestamps.parse("13/13/2026 10:00", z, DateOrder.MDY))
        assertNull(Timestamps.parse("yesterday", z, DateOrder.MDY))
        assertEquals(DateOrder.DMY, Timestamps.detectOrder(listOf("25/12/2025, 10:00", "01/02/2026, 10:00")))
        assertEquals(DateOrder.MDY, Timestamps.detectOrder(listOf("12/25/2025, 10:00")))
        assertNull(Timestamps.detectOrder(listOf("01/02/2026, 10:00")))
        // Same string, two readings, different instants: the caller must be told which was used.
        assertTrue(Timestamps.parse("01/02/2026 10:00", z, DateOrder.MDY)!!.ms != Timestamps.parse("01/02/2026 10:00", z, DateOrder.DMY)!!.ms)
    }

    @Test fun whatsAppAndroidFormatWithMultilineSystemLinesAndAttachments() {
        val text = "1/5/26, 11:59 AM - Messages and calls are end-to-end encrypted.\n" +
            "1/5/26, 12:00 PM - Don: hello there\n" +
            "1/5/26, 12:01 PM - Sam: line one\nline two\n\nline four\n" +
            "1/5/26, 12:02 PM - Sam: <attached: IMG-0001.jpg>\n" +
            "1/5/26, 12:03 PM - Sam changed the subject to \"Plans\"\n"
        val r = parse(WhatsAppTextImporter(), "WhatsApp Chat with Sam.txt", text)
        assertEquals(3, r.messages.size)
        assertEquals("line one\nline two\n\nline four", r.messages[1].body)
        assertEquals("IMG-0001.jpg", r.messages[2].attachments.single().name)
        assertEquals("Sam", r.titleGuess)
        assertTrue(r.warnings.any { it.contains("system lines") })
        assertTrue("ambiguous order and assumed zone are both reported", r.warnings.any { it.contains("ambiguous") } && r.warnings.any { it.contains("time zone") })
    }

    @Test fun whatsAppIosFormatWithInvisibleMarksAndDayFirstDates() {
        val text = "‎[25/12/2025, 21:41:05] Don: merry christmas\n[26/12/2025, 08:00:00] ~ Sam: thanks!\n"
        val r = parse(WhatsAppTextImporter(), "chat.txt", text)
        assertEquals(2, r.messages.size)
        assertEquals("Sam", r.messages[1].sender)
        assertEquals(1000L, r.messages[0].tsPrecisionMs)
        assertTrue("day-first was proven by 25/12, so no ambiguity warning", r.warnings.none { it.contains("ambiguous") })
        assertEquals(1_766_698_865_000L, r.messages[0].ts)
    }

    @Test fun anExplicitDateOrderOverridesTheDefault() {
        val text = "01/02/2026, 10:00 - Don: hi\n"
        val mdy = parse(WhatsAppTextImporter(), "c.txt", text, ParseOptions(zone = ZoneOffset.UTC, dateOrder = DateOrder.MDY)).messages[0].ts
        val dmy = parse(WhatsAppTextImporter(), "c.txt", text, ParseOptions(zone = ZoneOffset.UTC, dateOrder = DateOrder.DMY)).messages[0].ts
        assertTrue(mdy < dmy)
    }

    @Test fun csvHandlesQuotesNewlinesBomCrlfAndReportsBadRowsWithoutDroppingGoodOnes() {
        val csv = "﻿Date,Direction,Body\r\n2026-01-05 12:00:00,sent,\"He said \"\"hi\"\",\nthen left\"\r\nnot-a-date,received,oops\r\n2026-01-05 12:05:00,received,fine\r\n"
        val r = CsvImporter().parse(ImportInput("x.csv", csv.toByteArray()), utc)
        assertEquals(2, r.messages.size)
        assertEquals("He said \"hi\",\nthen left", r.messages[0].body)
        assertEquals(1, r.errors.size)
        assertTrue(r.errors[0].message.contains("not-a-date"))
        assertEquals(Direction.OUT, r.messages[0].direction)
    }

    @Test fun smsBackupXmlMapsTypesAndSkipsDraftsAndFailedMessages() {
        val xml = """<?xml version="1.0"?><smses count="4">
            <sms address="+15550100101" date="1767614400000" type="1" body="in" />
            <sms address="+15550100101" date="1767614460000" type="2" body="out &amp; about" />
            <sms address="+15550100101" date="1767614520000" type="3" body="draft" />
            <sms address="+15550100101" date="1767614580000" type="5" body="failed" />
            </smses>"""
        val r = parse(SmsBackupXmlImporter(), "b.xml", xml)
        assertEquals(listOf("in", "out & about"), r.messages.map { it.body })
        assertEquals(listOf(Direction.IN, Direction.OUT), r.messages.map { it.direction })
        assertTrue(r.warnings.any { it.contains("2 drafts") })
    }

    @Test fun anXmlExternalEntityIsNeverResolved() {
        val xml = """<?xml version="1.0"?><!DOCTYPE smses [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
            <smses count="1"><sms address="1" date="1767614400000" type="1" body="&xxe;" /></smses>"""
        val r = parse(SmsBackupXmlImporter(), "evil.xml", xml)
        assertTrue("file contents must never reach a message", r.messages.none { it.body.contains("root:") || it.body.contains("/bin/") })
    }

    @Test fun jsonAcceptsArrayOrWrappedObjectAndNestedSenders() {
        val a = parse(JsonImporter(), "a.json", """[{"timestamp":1767614400,"sender":{"name":"Sam"},"text":"hi","type":"received"}]""")
        assertEquals("Sam", a.messages[0].sender)
        assertEquals(1_767_614_400_000L, a.messages[0].ts)
        val b = parse(JsonImporter(), "b.json", """{"messages":[{"date":"2026-01-05T12:00:00Z","body":"yo","direction":"outgoing"}]}""")
        assertEquals(Direction.OUT, b.messages[0].direction)
        val bad = parse(JsonImporter(), "c.json", "{not json")
        assertEquals(0, bad.messages.size)
        assertEquals(1, bad.errors.size)
    }

    @Test fun zipArchivesAreReadInMemoryAndMediaIsOnlyCounted() {
        val chat = "1/5/26, 12:00 PM - Don: hello\n1/5/26, 12:01 PM - Sam: hi\n".toByteArray()
        val zip = Fx.zipOf("_chat.txt" to chat, "IMG-0001.jpg" to ByteArray(10), "../../evil.txt" to "1/5/26, 12:09 PM - X: path\n".toByteArray())
        val r = ZipImporter().parse(zip, utc)
        assertTrue("traversal-named entries are parsed as data only, never written anywhere", r.messages.size >= 2)
        assertTrue(r.warnings.any { it.contains("non-text files") })
        assertEquals("whatsapp", r.providerGuess)
    }

    @Test fun largeMediaInAZipIsSkippedWithoutBufferingAndTheChatStillImports() {
        // Regression (review Nov 24): media entries used to be read fully into memory (and counted against the 256 MB
        // expansion limit) before the name check, so a big "export with media" could exhaust the heap or abort the import.
        val bos = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(bos).use { z ->
            z.putNextEntry(java.util.zip.ZipEntry("VID-0001.mp4"))
            val chunk = ByteArray(1 shl 20)
            repeat(257) { z.write(chunk) }                                           // 257 MB of zeros: tiny compressed, huge expanded
            z.closeEntry()
            z.putNextEntry(java.util.zip.ZipEntry("_chat.txt"))
            z.write("1/5/26, 12:00 PM - Don: hello\n1/5/26, 12:01 PM - Sam: hi\n".toByteArray()); z.closeEntry()
        }
        val r = ZipImporter().parse(ImportInput("export.zip", bos.toByteArray()), utc)
        assertEquals(2, r.messages.size)
        assertTrue(r.errors.isEmpty())
        assertTrue(r.warnings.any { it.contains("non-text files") })
    }

    @Test fun detectionPicksTheRightImporterAndRefusesUnknownFormats() {
        assertEquals("sms-backup-xml", Importers.detect(Fx.smsXml(Fx.conversation(3))).first().first.id)
        assertEquals("csv", Importers.detect(Fx.csv(Fx.conversation(3))).first().first.id)
        assertEquals("whatsapp-txt", Importers.detect(Fx.whatsApp(Fx.conversation(5))).first().first.id)
        assertEquals("zip", Importers.detect(Fx.zipOf("a.txt" to "x".toByteArray())).first().first.id)
        assertTrue(Importers.detect(ImportInput("x.html", "<html><body>hi</body></html>".toByteArray())).isEmpty())
        assertTrue(Importers.detect(ImportInput("empty.txt", ByteArray(0))).isEmpty())
        assertNotNull(Importers.byId("json"))
    }

    @Test fun phoneAndNameKeysAreStableAcrossFormatting() {
        assertEquals(Keys.participant("+1 (555) 010-0101"), Keys.participant("5550100101"))
        assertEquals(Keys.participant("Sam"), Keys.participant(" sam "))
        assertTrue(Keys.participant("Sam") != Keys.participant("Samuel"))
        assertEquals("n:12345", Keys.participant("12345"))
    }
}
