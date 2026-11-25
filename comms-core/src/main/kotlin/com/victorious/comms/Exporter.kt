package com.victorious.comms

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

enum class ExportFormat(val extension: String, val mime: String, val label: String) {
    CSV("csv", "text/csv", "CSV (spreadsheet; VictorSMS can read it back)"),
    JSON("json", "application/json", "JSON (VictorSMS can read it back)"),
    TEXT("txt", "text/plain", "Plain text (to read; not meant for re-import)"),
}

data class ExportOptions(
    /**
     * Prefix a cell that starts with `=`, `+`, `-`, `@`, tab or carriage return with an apostrophe so a spreadsheet shows it as text
     * instead of running it as a formula. Off by default because it changes the stored text: a file written with it on does not
     * round-trip byte for byte.
     */
    val spreadsheetSafe: Boolean = false,
    /** Text a bot drafted and the owner approved is part of the history; turn this off to export only the two people's own words. */
    val includeBotMessages: Boolean = true,
)

data class ExportResult(val messages: Int)

/**
 * Writes one saved conversation out as a file the owner keeps (Victorious Framework).
 *
 * This is VictorSMS's own, documented layout. It is **not** TextNow's export format (TextNow does not publish one, and nothing here
 * pretends otherwise); the importer reads it back through the generic CSV and JSON importers, which is how the round trip is tested.
 *
 * Columns / keys: `timestamp` (ISO-8601 UTC, so no zone is ever assumed), `direction` (`in`/`out`), `sender`, `body`, `id` (the
 * provider's message id when there is one), `written_by` (`owner`, `peer` or a bot label) and `source` (`import` or `live`). The last
 * two are named so an importer cannot mistake them for the sender column.
 *
 * It streams: messages are read in pages and written as they come, so a very large conversation never sits in memory. It only reads.
 * Where the file goes is the caller's business; the app uses the system file picker so the owner chooses.
 */
object Exporter {
    private const val PAGE = 500
    private val TEXT_STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneOffset.UTC)
    private val HEADER = listOf("timestamp", "direction", "sender", "body", "id", "written_by", "source")

    /** No names or numbers: a file name is visible in file managers and share sheets. */
    fun fileName(archive: Archive, conversation: Long, format: ExportFormat, now: Long = archive.clock()): String {
        val provider = archive.conversation(conversation)?.provider.orEmpty().lowercase().filter { it.isLetterOrDigit() }.take(12).ifEmpty { "chat" }
        val day = DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC).format(Instant.ofEpochMilli(now))
        return "victorsms-$provider-$day.${format.extension}"
    }

    fun write(archive: Archive, conversation: Long, format: ExportFormat, out: Appendable, options: ExportOptions = ExportOptions()): ExportResult {
        val conv = archive.conversation(conversation) ?: throw IllegalArgumentException("That conversation does not exist.")
        var count = 0
        when (format) {
            ExportFormat.CSV -> out.append(HEADER.joinToString(",")).append("\r\n")
            ExportFormat.JSON -> {
                out.append("{\"format\":\"victorsms-conversation\",\"version\":1,\"provider\":")
                    .append(JsonPrimitive(conv.provider).toString()).append(",\"messages\":[")
            }
            ExportFormat.TEXT -> Unit
        }
        var afterTs = Long.MIN_VALUE
        var afterId = 0L
        while (true) {
            val page = archive.messagesAfter(conversation, afterTs, afterId, PAGE)
            if (page.isEmpty()) break
            for (m in page) {
                if (!options.includeBotMessages && m.author.startsWith("bot")) continue
                when (format) {
                    ExportFormat.CSV -> out.append(csvRow(m, options)).append("\r\n")
                    ExportFormat.JSON -> out.append(if (count == 0) "" else ",").append(jsonObject(m, options))
                    ExportFormat.TEXT -> out.append(textLine(m, options)).append("\n")
                }
                count++
            }
            val last = page.last()
            afterTs = last.ts; afterId = last.id
            if (page.size < PAGE) break
        }
        if (format == ExportFormat.JSON) out.append("]}\n")
        return ExportResult(count)
    }

    private fun fields(m: StoredMessage, options: ExportOptions): List<String> = listOf(
        Instant.ofEpochMilli(m.ts).toString(), m.direction.db, safe(m.sender, options), safe(m.body, options),
        m.providerId.orEmpty(), m.author, m.origin,
    )

    private fun csvRow(m: StoredMessage, options: ExportOptions) = fields(m, options).joinToString(",") { csvCell(it) }

    private fun csvCell(v: String): String =
        if (v.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + v.replace("\"", "\"\"") + "\"" else v

    private fun jsonObject(m: StoredMessage, options: ExportOptions): String {
        val f = fields(m, options)
        return buildJsonObject { HEADER.forEachIndexed { i, k -> put(k, f[i]) } }.toString()
    }

    private fun textLine(m: StoredMessage, options: ExportOptions): String {
        val who = safe(m.sender.ifBlank { if (m.direction == Direction.OUT) "Me" else "Them" }, options)
        return "[${TEXT_STAMP.format(Instant.ofEpochMilli(m.ts))}] $who: ${safe(m.body, options)}"
    }

    private fun safe(v: String, options: ExportOptions): String =
        if (options.spreadsheetSafe && v.isNotEmpty() && v[0] in "=+-@\t\r") "'$v" else v
}
