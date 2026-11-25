package com.victorious.sms.archive

import android.content.Context
import android.net.Uri
import com.victorious.comms.ExportFormat
import com.victorious.comms.ExportOptions
import com.victorious.comms.Exporter
import java.io.OutputStream

/**
 * Victorious Framework: writes one saved conversation to a file the owner just chose with the system file picker.
 * Nothing is sent anywhere and nothing is written without that pick. The file is plain text on purpose (the owner asked for a
 * readable export), so it is **not encrypted**; the screen says so before the owner picks a place.
 *
 * Call off the main thread. Returns the number of messages written, or the failure (the file is then incomplete, and the caller
 * says so).
 */
object ConversationExport {
    /** Excel reads UTF-8 CSV as ANSI without this mark and garbles accents and emoji. VictorSMS's own importer strips it. */
    private const val BOM = "﻿"

    fun exportTo(
        context: Context, conversation: Long, format: ExportFormat, options: ExportOptions, target: Uri,
        open: (Uri) -> OutputStream? = { context.contentResolver.openOutputStream(it, "wt") },
    ): Result<Int> = runCatching {
        val archive = ArchiveRuntime.get(context).archive
        val stream = open(target) ?: error("Android could not open that file for writing.")
        stream.bufferedWriter(Charsets.UTF_8).use { w ->
            if (format == ExportFormat.CSV) w.append(BOM)
            Exporter.write(archive, conversation, format, w, options).messages
        }
    }
}
