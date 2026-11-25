package com.victorious.sms.archive

import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.victorious.comms.Direction
import com.victorious.comms.ExportFormat
import com.victorious.comms.ExportOptions
import com.victorious.comms.ImportEngine
import com.victorious.comms.ImportInput
import com.victorious.comms.ImportRequest
import com.victorious.comms.LiveIngest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream

/**
 * Victorious Framework: the Android export path on Android's SQLite under Robolectric. The system file picker and a real
 * document provider are NOT exercised; the output stream is a fake.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ConversationExportTest {
    private val ctx get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val target = Uri.parse("content://fake/export")

    @Before fun fresh() {
        ctx.getDatabasePath(ArchiveRuntime.DB_NAME).delete()
        ArchiveRuntime.resetForTests()
    }

    private val archive get() = ArchiveRuntime.get(ctx).archive

    private fun seed(): Long {
        val live = LiveIngest(archive)
        live.record("+1 (555) 010-0160", "Dinner at 7? café ☕", 1_767_614_400_000L, Direction.IN)
        live.record("+1 (555) 010-0160", "yes, \"see you\"", 1_767_614_460_000L, Direction.OUT)
        return archive.conversations().single().id
    }

    @Test fun csvStartsWithAByteOrderMarkAndTheImporterReadsItBack() {
        val c = seed()
        val out = ByteArrayOutputStream()
        val r = ConversationExport.exportTo(ctx, c, ExportFormat.CSV, ExportOptions(), target) { out }
        assertEquals(2, r.getOrThrow())
        val bytes = out.toByteArray()
        assertEquals(listOf(0xEF, 0xBB, 0xBF), bytes.take(3).map { it.toInt() and 0xFF })
        // A fresh install's importer accepts the file it just wrote (a restore): both messages arrive as new.
        val fresh = com.victorious.comms.Archive(AndroidSqlDb(SQLiteDatabase.create(null)))
        val prev = ImportEngine(fresh).preview(ImportRequest(ImportInput("x.csv", bytes)))
        assertTrue(prev.canCommit)
        assertEquals(2, prev.groups.single().newMessages)
    }

    @Test fun jsonHasNoByteOrderMark() {
        val out = ByteArrayOutputStream()
        ConversationExport.exportTo(ctx, seed(), ExportFormat.JSON, ExportOptions(), target) { out }.getOrThrow()
        assertEquals('{', out.toString(Charsets.UTF_8.name())[0])
    }

    @Test fun aFileAndroidCannotOpenIsAFailureNotACrash() {
        val r = ConversationExport.exportTo(ctx, seed(), ExportFormat.CSV, ExportOptions(), target) { null }
        assertTrue(r.isFailure)
        assertTrue(r.exceptionOrNull()!!.message!!.contains("could not open"))
    }

    @Test fun aWriteThatFailsHalfWayIsReportedAndTheStreamIsStillClosed() {
        var closed = false
        val broken = object : OutputStream() {
            override fun write(b: Int) = throw IOException("disk full")
            override fun write(b: ByteArray, off: Int, len: Int) = throw IOException("disk full")
            override fun close() { closed = true }
        }
        val r = ConversationExport.exportTo(ctx, seed(), ExportFormat.CSV, ExportOptions(), target) { broken }
        assertTrue(r.isFailure)
        assertTrue(r.exceptionOrNull()!!.message!!.contains("disk full"))
        assertTrue(closed)
    }
}
