package com.victorious.sms.archive

import android.database.sqlite.SQLiteDatabase
import com.victorious.comms.Archive
import com.victorious.comms.ColumnMapping
import com.victorious.comms.DateOrder
import com.victorious.comms.ImportEngine
import com.victorious.comms.ImportInput
import com.victorious.comms.MemoryOriginalStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.IOException
import java.time.ZoneOffset

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ImportSessionTest {
    private fun engine(): Pair<Archive, ImportEngine> {
        val a = Archive(AndroidSqlDb(SQLiteDatabase.create(null)), MemoryOriginalStore()) { 1_800_000_000_000L }
        return a to ImportEngine(a)
    }

    private fun file(vararg lines: String, name: String = "chat.csv") = ImportInput(name, ("Date,Sender,Body\n" + lines.joinToString("\n") + "\n").toByteArray())

    @Test fun aFileLargerThanTheLimitIsRefusedWithoutBeingReadWholeIntoMemory() {
        val stream = object : java.io.InputStream() {
            var served = 0L
            override fun read(): Int = 'a'.code.also { served++ }
            override fun read(b: ByteArray, off: Int, len: Int): Int { served += len; java.util.Arrays.fill(b, off, off + len, 'a'.code.toByte()); return len }
        }
        try { ImportLimits.readLimited(stream, max = 1_000_000); fail() } catch (e: IOException) { assertTrue(e.message!!.contains("larger")) }
        assertTrue("it stopped reading soon after the limit (${stream.served})", stream.served < 1_100_000)
        assertEquals(5, ImportLimits.readLimited(ByteArrayInputStream("hello".toByteArray()), 10).size)
    }

    @Test fun nothingIsRequestedUntilAFileIsChosenAndAPreviewNeverWrites() {
        val (a, e) = engine()
        val s = ImportSession(e, ZoneOffset.UTC)
        assertNull(s.request())
        assertNull(s.preview())
        s.input = file("2026-01-05 12:00:00,Sam,hello", "2026-01-05 12:01:00,Don,hi")
        val p = s.preview()!!
        assertEquals(setOf("Don", "Sam"), p.unresolvedSenders.toSet())
        assertFalse(p.canCommit)
        assertEquals("a preview wrote nothing", 0, a.conversations().size)
        try { s.commit(); fail("committing before saying who you are must fail") } catch (_: IllegalStateException) { }
        assertEquals(0, a.conversations().size)
    }

    @Test fun identifyingYourselfMakesItCommittableAndTheChoiceCarriesIntoTheRequest() {
        val (a, e) = engine()
        val s = ImportSession(e, ZoneOffset.UTC)
        s.input = file("2026-01-05 12:00:00,Sam,hello", "2026-01-05 12:01:00,Don,hi")
        s.providerLabel = "TextNow"
        s.account = "5550100100"
        s.selfSenders.add("Don")
        assertEquals(setOf("Don"), s.request()!!.selfSenders)
        assertEquals("TextNow", s.request()!!.providerLabel)
        assertTrue(s.preview()!!.canCommit)
        val r = s.commit()
        assertEquals(2, r.groups[0].added)
        assertEquals(1, a.conversations().size)
    }

    @Test fun aBlankProviderIsLeftToDetectionAndMappingOnlyAppliesWhenSet() {
        val (_, e) = engine()
        val s = ImportSession(e, ZoneOffset.UTC)
        s.input = file("2026-01-05 12:00:00,Sam,hello")
        assertNull(s.request()!!.providerLabel)
        assertNull("no mapping unless the owner set one", s.request()!!.options.mapping)
        s.mapping = ColumnMapping(body = "Body")
        assertEquals("Body", s.request()!!.options.mapping!!.body)
        s.dateOrder = DateOrder.DMY
        assertEquals(DateOrder.DMY, s.request()!!.options.dateOrder)
    }

    @Test fun aSuggestedTimeShiftIsOnlyAppliedWhenTheOwnerTicksItAndSurvivesUnticking() {
        val (_, e) = engine()
        val rows = (0 until 40).map { i -> "2026-01-0${1 + i / 12} ${"%02d".format(8 + (i % 12))}:00:00,${if (i % 2 == 0) "Sam" else "Don"},message number $i about the plan" }
        val s0 = ImportSession(e, ZoneOffset.UTC)
        s0.input = file(*rows.toTypedArray()); s0.selfSenders.add("Don"); s0.providerLabel = "TextNow"; s0.account = "1"
        s0.commit()
        // The same chat exported by a phone five hours behind UTC, read as UTC.
        val shifted = rows.map { r -> val ts = java.time.LocalDateTime.parse(r.substringBefore(',').replace(' ', 'T')).minusHours(5); ts.toString().replace('T', ' ') + ":00".takeIf { ts.second == 0 && ts.toString().length == 16 }.orEmpty() + "," + r.substringAfter(',') }
        val s = ImportSession(e, ZoneOffset.UTC)
        s.input = ImportInput("shifted.csv", ("Date,Sender,Body\n" + shifted.joinToString("\n") + "\n").toByteArray())
        s.selfSenders.add("Don"); s.providerLabel = "TextNow"; s.account = "1"
        val p = s.preview()!!
        assertEquals(5L * 3_600_000, s.suggestedShift)
        assertEquals("not applied until ticked", 0L, s.request()!!.timeShiftMs)
        assertTrue(p.warnings.any { it.contains("offset") })
        s.applyShift = true
        assertEquals(5L * 3_600_000, s.request()!!.timeShiftMs)
        val q = s.preview()!!
        assertEquals("the suggestion is remembered while it is applied", 5L * 3_600_000, s.suggestedShift)
        assertEquals("with the shift, everything matches", 0, q.groups[0].newMessages)
        s.applyShift = false
        assertEquals(0L, s.request()!!.timeShiftMs)
    }
}
