package com.victorious.comms

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.ByteArrayInputStream
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale
import java.util.zip.ZipInputStream
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLStreamConstants
import javax.xml.stream.XMLStreamReader

/**
 * Importer contract (Victorious Framework). Adding a format means adding one class and registering it in
 * [Importers.all]. Parsers never invent data: a message whose time or text cannot be read becomes a
 * [ParseError], never a guessed record.
 */
interface Importer {
    val id: String
    val label: String

    /** 0 = not this format, 100 = certain. */
    fun sniff(input: ImportInput): Int

    fun parse(input: ImportInput, options: ParseOptions): ParseResult
}

object Timestamps {
    data class Parsed(val ms: Long, val precisionMs: Long, val zoneAssumed: Boolean)

    private val DATE_TIME = Regex(
        """^(\d{1,4})[/.\-](\d{1,2})[/.\-](\d{1,4})(?:,?\s+|T)(\d{1,2}):(\d{2})(?::(\d{2}))?(?:[.,](\d{1,3}))?\s*([AaPp]\.?[Mm]\.?)?\s*(Z|[+-]\d{2}:?\d{2})?$""",
    )
    private val DATE_ONLY = Regex("""^(\d{4})-(\d{2})-(\d{2})$""")
    private val DATE_PREFIX = Regex("""^(\d{1,4})[/.\-](\d{1,2})[/.\-](\d{1,4})""")

    /** Day/month order evidence from a set of date strings: DMY or MDY if one side exceeds 12, null if ambiguous. */
    fun detectOrder(samples: Collection<String>): DateOrder? {
        var dmy = false
        var mdy = false
        for (raw in samples) {
            // Only the date prefix matters, so date-only strings (chat logs split date and time) count as evidence too.
            val m = DATE_PREFIX.find(clean(raw)) ?: continue
            if (m.groupValues[1].length == 4) continue
            val a = m.groupValues[1].toInt()
            val b = m.groupValues[2].toInt()
            if (a > 12) dmy = true
            if (b > 12) mdy = true
        }
        return when {
            dmy && !mdy -> DateOrder.DMY
            mdy && !dmy -> DateOrder.MDY
            else -> null
        }
    }

    private fun clean(s: String) = s.trim().replace(' ', ' ').replace(' ', ' ').replace("‎", "")

    fun parse(raw: String, zone: ZoneId, order: DateOrder): Parsed? {
        val s = clean(raw)
        if (s.isEmpty()) return null
        if (s.all { it.isDigit() }) {
            val n = s.toLongOrNull() ?: return null
            return when {
                s.length >= 12 -> Parsed(n, 1, false)
                s.length in 9..11 -> Parsed(n * 1000, 1000, false)
                else -> null
            }
        }
        if (Regex("""^\d{9,11}\.\d{1,3}$""").matches(s)) {
            val d = s.toBigDecimalOrNull() ?: return null
            return Parsed(d.movePointRight(3).toLong(), 1, false)
        }
        DATE_ONLY.matchEntire(s)?.let {
            val ld = runCatching { java.time.LocalDate.of(it.groupValues[1].toInt(), it.groupValues[2].toInt(), it.groupValues[3].toInt()) }.getOrNull()
                ?: return null
            return Parsed(ld.atStartOfDay(zone).toInstant().toEpochMilli(), 86_400_000, true)
        }
        val m = DATE_TIME.matchEntire(s) ?: return null
        val g = m.groupValues
        val year: Int
        val month: Int
        val day: Int
        if (g[1].length == 4) {
            year = g[1].toInt(); month = g[2].toInt(); day = g[3].toInt()
        } else {
            val a = g[1].toInt()
            val b = g[2].toInt()
            var y = g[3].toInt()
            if (g[3].length <= 2) y += 2000
            year = y
            if (order == DateOrder.DMY) { day = a; month = b } else { month = a; day = b }
        }
        var hour = g[4].toInt()
        val minute = g[5].toInt()
        val second = g[6].takeIf { it.isNotEmpty() }?.toInt() ?: 0
        val millis = g[7].takeIf { it.isNotEmpty() }?.padEnd(3, '0')?.toInt()
        val ampm = g[8].lowercase(Locale.ROOT)
        if (ampm.isNotEmpty()) {
            if (hour !in 1..12) return null
            if (ampm.startsWith("p") && hour != 12) hour += 12
            if (ampm.startsWith("a") && hour == 12) hour = 0
        }
        val ldt = runCatching { LocalDateTime.of(year, month, day, hour, minute, second, (millis ?: 0) * 1_000_000) }.getOrNull()
            ?: return null
        val precision = when {
            millis != null -> 1L
            g[6].isNotEmpty() -> 1000L
            else -> 60_000L
        }
        val offset = g[9]
        if (offset.isNotEmpty()) {
            val off = if (offset == "Z") ZoneOffset.UTC else runCatching { ZoneOffset.of(if (offset.contains(':')) offset else offset.substring(0, 3) + ":" + offset.substring(3)) }.getOrNull()
                ?: return null
            return Parsed(OffsetDateTime.of(ldt, off).toInstant().toEpochMilli(), precision, false)
        }
        return Parsed(ldt.atZone(zone).toInstant().toEpochMilli(), precision, true)
    }
}

/** RFC 4180 reader: quoted fields, doubled quotes, newlines inside quotes, CRLF/LF, optional BOM already stripped. */
object Csv {
    fun parse(text: String): List<List<String>> {
        val out = ArrayList<List<String>>()
        var row = ArrayList<String>()
        val cell = StringBuilder()
        var inQuotes = false
        var i = 0
        var cellStarted = false
        fun endCell() { row.add(cell.toString()); cell.setLength(0); cellStarted = false }
        fun endRow() {
            endCell()
            if (!(row.size == 1 && row[0].isEmpty())) out.add(row)
            row = ArrayList()
        }
        while (i < text.length) {
            val c = text[i]
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < text.length && text[i + 1] == '"') { cell.append('"'); i++ } else inQuotes = false
                } else cell.append(c)
            } else when (c) {
                '"' -> if (!cellStarted && cell.isEmpty()) { inQuotes = true; cellStarted = true } else cell.append(c)
                ',' -> endCell()
                '\r' -> { if (i + 1 < text.length && text[i + 1] == '\n') i++; endRow() }
                '\n' -> endRow()
                else -> { cell.append(c); cellStarted = true }
            }
            i++
        }
        if (cell.isNotEmpty() || row.isNotEmpty() || cellStarted) endRow()
        return out
    }
}

private val TS_NAMES = listOf("timestamp", "date", "datetime", "date_time", "date/time", "time", "sent", "sent_at", "created", "created_at", "date sent")
private val BODY_NAMES = listOf("body", "message", "text", "content", "msg", "message_text", "message text")
private val SENDER_NAMES = listOf("sender", "from", "author", "name", "contact_name", "contact name")
private val COUNTERPART_NAMES = listOf("address", "phone", "phone_number", "phone number", "number", "to", "recipient", "counterpart", "contact_number")
private val DIRECTION_NAMES = listOf("direction", "type", "sent_or_received", "in_out", "message_type", "box")
private val ID_NAMES = listOf("id", "message_id", "guid", "uuid", "msgid")

private fun parseDirection(v: String?): Direction? = when (v?.trim()?.lowercase(Locale.ROOT)) {
    "sent", "outgoing", "out", "outbound", "2", "s", "sent message" -> Direction.OUT
    "received", "incoming", "in", "inbound", "1", "r", "received message" -> Direction.IN
    else -> null
}

private fun resolveOrder(options: ParseOptions, dates: Collection<String>, warnings: MutableList<String>): DateOrder {
    options.dateOrder?.let { return it }
    Timestamps.detectOrder(dates)?.let { return it }
    val needs = dates.any { d -> Regex("""^\s*\d{1,2}[/.\-]\d{1,2}[/.\-]\d{2,4}""").containsMatchIn(d) }
    if (needs) warnings.add("Day/month order is ambiguous in this file; month-first (MDY) was assumed. Choose day-first if that is wrong.")
    return DateOrder.MDY
}

class CsvImporter : Importer {
    override val id = "csv"
    override val label = "CSV (comma-separated, with a header row)"

    override fun sniff(input: ImportInput): Int {
        val first = input.text.lineSequence().firstOrNull()?.lowercase(Locale.ROOT) ?: return 0
        if (!first.contains(',')) return 0
        val cols = first.split(',').map { it.trim().trim('"') }
        val hasBody = cols.any { it in BODY_NAMES }
        val hasTs = cols.any { it in TS_NAMES }
        return when {
            hasBody && hasTs -> 70
            hasBody || hasTs -> 25
            else -> 5
        }
    }

    override fun parse(input: ImportInput, options: ParseOptions): ParseResult {
        val rows = Csv.parse(input.text)
        val warnings = ArrayList<String>()
        if (rows.isEmpty()) return ParseResult(id, "csv", null, emptyList(), listOf(ParseError(0, "The file is empty.")), warnings)
        val header = rows[0].map { it.trim() }
        fun col(mapped: String?, names: List<String>): Int =
            if (mapped != null) header.indexOfFirst { it.equals(mapped, true) } else names.firstNotNullOfOrNull { n -> header.indexOfFirst { it.equals(n, true) }.takeIf { it >= 0 } } ?: -1
        val m = options.mapping
        val iTs = col(m?.timestamp, TS_NAMES)
        val iBody = col(m?.body, BODY_NAMES)
        val iSender = col(m?.sender, SENDER_NAMES)
        val iCounter = col(m?.counterpart, COUNTERPART_NAMES)
        val iDir = col(m?.direction, DIRECTION_NAMES)
        val iId = col(m?.id, ID_NAMES)
        val missing = buildList { if (iTs < 0) add("timestamp"); if (iBody < 0) add("body") }
        if (missing.isNotEmpty()) {
            return ParseResult(
                id, "csv", null, emptyList(),
                listOf(ParseError(1, "Could not find a ${missing.joinToString(" and ")} column. Header was: ${header.joinToString(", ").take(200)}")),
                warnings, needsMapping = missing,
            )
        }
        val order = resolveOrder(options, rows.drop(1).mapNotNull { it.getOrNull(iTs) }, warnings)
        val msgs = ArrayList<RawMessage>()
        val errors = ArrayList<ParseError>()
        var zoneAssumed = false
        rows.drop(1).forEachIndexed { idx, r ->
            val line = idx + 2
            val body = r.getOrNull(iBody)
            val tsRaw = r.getOrNull(iTs)
            if (body == null || tsRaw == null) { errors.add(ParseError(line, "Row has too few columns.")); return@forEachIndexed }
            val ts = Timestamps.parse(tsRaw, options.zone, order)
            if (ts == null) { errors.add(ParseError(line, "Unreadable timestamp '${tsRaw.take(40)}'.")); return@forEachIndexed }
            if (ts.zoneAssumed) zoneAssumed = true
            msgs.add(
                RawMessage(
                    sender = r.getOrNull(iSender).orEmpty().trim(),
                    direction = parseDirection(r.getOrNull(iDir)),
                    body = body,
                    ts = ts.ms,
                    tsPrecisionMs = ts.precisionMs,
                    providerId = r.getOrNull(iId)?.trim()?.takeIf { it.isNotEmpty() },
                    counterpart = r.getOrNull(iCounter)?.trim()?.takeIf { it.isNotEmpty() },
                    sourceLine = line,
                ),
            )
        }
        if (zoneAssumed) warnings.add("Timestamps carry no time zone; ${options.zone} was assumed.")
        return ParseResult(id, "csv", null, msgs, errors, warnings)
    }
}

class JsonImporter : Importer {
    override val id = "json"
    override val label = "JSON (array of messages, or an object with a messages array)"
    private val json = Json { isLenient = false }

    override fun sniff(input: ImportInput): Int {
        val t = input.text.trimStart()
        if (!(t.startsWith("[") || t.startsWith("{"))) return 0
        return if (t.take(4000).let { s -> BODY_NAMES.any { s.contains("\"$it\"", true) } }) 65 else 15
    }

    override fun parse(input: ImportInput, options: ParseOptions): ParseResult {
        val warnings = ArrayList<String>()
        val root = try { json.parseToJsonElement(input.text) } catch (e: Exception) {
            return ParseResult(id, "json", null, emptyList(), listOf(ParseError(0, "Not valid JSON: ${e.message?.take(120)}")), warnings)
        }
        val arr: JsonArray = when (root) {
            is JsonArray -> root
            is JsonObject -> listOf("messages", "data", "items", "conversation", "records").firstNotNullOfOrNull { k ->
                root.entries.firstOrNull { it.key.equals(k, true) }?.value as? JsonArray
            } ?: return ParseResult(id, "json", null, emptyList(), listOf(ParseError(0, "No messages array found.")), warnings)
            else -> return ParseResult(id, "json", null, emptyList(), listOf(ParseError(0, "Unexpected JSON shape.")), warnings)
        }
        val m = options.mapping
        val objs = arr.mapNotNull { it as? JsonObject }
        val tsValues = objs.mapNotNull { it.field(TS_NAMES, m?.timestamp).text() }
        val order = resolveOrder(options, tsValues, warnings)
        val msgs = ArrayList<RawMessage>()
        val errors = ArrayList<ParseError>()
        var zoneAssumed = false
        arr.forEachIndexed { idx, el ->
            val line = idx + 1
            val o = el as? JsonObject
            if (o == null) { errors.add(ParseError(line, "Element is not an object.")); return@forEachIndexed }
            val body = o.field(BODY_NAMES, m?.body).text()
            val tsRaw = o.field(TS_NAMES, m?.timestamp).text()
            if (body == null || tsRaw == null) { errors.add(ParseError(line, "Missing body or timestamp.")); return@forEachIndexed }
            val ts = Timestamps.parse(tsRaw, options.zone, order)
            if (ts == null) { errors.add(ParseError(line, "Unreadable timestamp '${tsRaw.take(40)}'.")); return@forEachIndexed }
            if (ts.zoneAssumed) zoneAssumed = true
            msgs.add(
                RawMessage(
                    sender = o.field(SENDER_NAMES, m?.sender).text().orEmpty().trim(),
                    direction = parseDirection(o.field(DIRECTION_NAMES, m?.direction).text()),
                    body = body, ts = ts.ms, tsPrecisionMs = ts.precisionMs,
                    providerId = o.field(ID_NAMES, m?.id).text()?.takeIf { it.isNotBlank() },
                    counterpart = o.field(COUNTERPART_NAMES, m?.counterpart).text()?.takeIf { it.isNotBlank() },
                    sourceLine = line,
                ),
            )
        }
        if (zoneAssumed) warnings.add("Timestamps carry no time zone; ${options.zone} was assumed.")
        return ParseResult(id, "json", null, msgs, errors, warnings)
    }

    private fun JsonObject.field(names: List<String>, mapped: String?): JsonElement? {
        if (mapped != null) return entries.firstOrNull { it.key.equals(mapped, true) }?.value
        for (n in names) entries.firstOrNull { it.key.equals(n, true) }?.let { return it.value }
        return null
    }

    private fun JsonElement?.text(): String? = when (this) {
        null, JsonNull -> null
        is JsonPrimitive -> content
        is JsonObject -> field(listOf("name", "display_name", "number", "address"), null).text()
        else -> null
    }
}

/** "SMS Backup & Restore" style XML: `<smses><sms address= date= type= body=/><mms .../></smses>`. */
class SmsBackupXmlImporter : Importer {
    override val id = "sms-backup-xml"
    override val label = "SMS backup XML (SMS Backup & Restore format)"

    override fun sniff(input: ImportInput): Int {
        val head = input.text.take(2000)
        return if (head.contains("<smses") ) 90 else 0
    }

    override fun parse(input: ImportInput, options: ParseOptions): ParseResult {
        val warnings = ArrayList<String>()
        val errors = ArrayList<ParseError>()
        val msgs = ArrayList<RawMessage>()
        var skipped = 0
        val factory = XMLInputFactory.newFactory().apply {
            // XXE hardening: no DTDs, no external entities (a backup file is untrusted input).
            setProperty(XMLInputFactory.SUPPORT_DTD, false)
            setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false)
            setProperty(XMLInputFactory.IS_REPLACING_ENTITY_REFERENCES, true)
        }
        try {
            val r = factory.createXMLStreamReader(ByteArrayInputStream(input.bytes))
            var mms: MutableMap<String, String>? = null
            val mmsText = StringBuilder()
            val mmsAtt = ArrayList<AttachmentMeta>()
            var n = 0
            while (r.hasNext()) {
                val ev = r.next()
                if (ev == XMLStreamConstants.START_ELEMENT) {
                    when (r.localName) {
                        "sms" -> {
                            n++
                            val a = attrs(r)
                            val type = a["type"]?.toIntOrNull()
                            val date = a["date"]?.toLongOrNull()
                            val addr = a["address"].orEmpty()
                            if (type != 1 && type != 2) { skipped++; continue }
                            if (date == null || a["body"] == null) { errors.add(ParseError(n, "sms element without date or body.")); continue }
                            msgs.add(
                                RawMessage(
                                    sender = if (type == 1) addr else "", direction = if (type == 1) Direction.IN else Direction.OUT,
                                    body = a["body"]!!, ts = date, tsPrecisionMs = 1, counterpart = addr, sourceLine = n,
                                ),
                            )
                        }
                        "mms" -> { n++; mms = attrs(r).toMutableMap(); mmsText.setLength(0); mmsAtt.clear() }
                        "part" -> if (mms != null) {
                            val a = attrs(r)
                            val ct = a["ct"].orEmpty()
                            if (ct == "text/plain") { a["text"]?.let { if (mmsText.isNotEmpty()) mmsText.append('\n'); mmsText.append(it) } }
                            else if (ct != "application/smil") mmsAtt.add(AttachmentMeta(a["name"] ?: a["cl"], ct.ifEmpty { null }, a["data"]?.length?.let { (it * 3L) / 4 }))
                        }
                    }
                } else if (ev == XMLStreamConstants.END_ELEMENT && r.localName == "mms") {
                    val a = mms
                    mms = null
                    if (a != null) {
                        val box = a["msg_box"]?.toIntOrNull()
                        val date = a["date"]?.toLongOrNull()
                        val addr = a["address"].orEmpty().replace("~", ",")
                        if ((box != 1 && box != 2) || date == null) { skipped++; continue }
                        // MMS `date` is in seconds in this format; SMS `date` is milliseconds.
                        msgs.add(
                            RawMessage(
                                sender = if (box == 1) addr else "", direction = if (box == 1) Direction.IN else Direction.OUT,
                                body = mmsText.toString(), ts = date * 1000, tsPrecisionMs = 1000, attachments = mmsAtt.toList(),
                                counterpart = addr, sourceLine = n,
                            ),
                        )
                    }
                }
            }
        } catch (e: Exception) {
            errors.add(ParseError(0, "XML could not be read: ${e.message?.take(120)}"))
        }
        if (skipped > 0) warnings.add("$skipped drafts, queued, failed or unsupported entries were skipped (they were never delivered or are not conversation history).")
        // "sms" is the service; "sms-backup" is only the file format. Live recording uses the same label so the two meet in one conversation.
        return ParseResult(id, "sms", null, msgs, errors, warnings)
    }

    private fun attrs(r: XMLStreamReader): Map<String, String> =
        (0 until r.attributeCount).associate { r.getAttributeLocalName(it) to r.getAttributeValue(it) }
}

/**
 * Line-oriented chat logs: WhatsApp "Export chat" text (Android `12/31/23, 9:41 PM - Name: text`, iOS
 * `[31/12/2023, 21:41:05] Name: text`) and pasted conversations that use the same shape.
 */
open class ChatLogImporter(override val id: String, override val label: String, private val provider: String, private val baseScore: Int) : Importer {
    private val android = Regex("""^[‎‏]*(\d{1,4}[/.\-]\d{1,2}[/.\-]\d{1,4}),?\s+(\d{1,2}:\d{2}(?::\d{2})?(?:\s?[AaPp]\.?[Mm]\.?)?)\s+-\s+(.*)$""")
    private val ios = Regex("""^[‎‏]*\[(\d{1,4}[/.\-]\d{1,2}[/.\-]\d{1,4}),?\s+(\d{1,2}:\d{2}(?::\d{2})?(?:\s?[AaPp]\.?[Mm]\.?)?)]\s+(.*)$""")
    private val iso = Regex("""^[‎‏]*\[?(\d{4}-\d{2}-\d{2})[ T](\d{1,2}:\d{2}(?::\d{2})?)]?\s+-?\s*(.*)$""")
    private val attached = Regex("""<attached:\s*([^>]+)>""")

    override fun sniff(input: ImportInput): Int {
        val lines = input.text.lineSequence().take(40).toList()
        val hits = lines.count { match(it) != null }
        return if (hits >= 2 || (hits == 1 && lines.size <= 3)) baseScore else 0
    }

    private fun match(line: String): Triple<String, String, String>? {
        val m = android.matchEntire(line) ?: ios.matchEntire(line) ?: iso.matchEntire(line) ?: return null
        return Triple(m.groupValues[1], m.groupValues[2], m.groupValues[3])
    }

    override fun parse(input: ImportInput, options: ParseOptions): ParseResult {
        val warnings = ArrayList<String>()
        val errors = ArrayList<ParseError>()
        val lines = input.text.split("\n").map { it.trimEnd('\r') }
        val matches = lines.map { match(it) }
        val order = resolveOrder(options, matches.mapNotNull { it?.first }, warnings)
        val msgs = ArrayList<RawMessage>()
        var cur: RawMessage? = null
        var system = 0
        var zoneAssumed = false
        fun flush() { cur?.let { msgs.add(it) }; cur = null }
        lines.forEachIndexed { idx, line ->
            val m = matches[idx]
            if (m != null) {
                flush()
                val (date, time, rest) = m
                val ts = Timestamps.parse("$date $time", options.zone, order)
                if (ts == null) { errors.add(ParseError(idx + 1, "Unreadable timestamp '${date.take(12)} ${time.take(12)}'.")); return@forEachIndexed }
                if (ts.zoneAssumed) zoneAssumed = true
                val cut = rest.indexOf(": ")
                if (cut <= 0) { system++; return@forEachIndexed }
                val sender = rest.substring(0, cut).trim().removePrefix("~").trim()
                val body = rest.substring(cut + 2)
                val att = attached.find(body)?.let { listOf(AttachmentMeta(it.groupValues[1].trim(), null, null)) } ?: emptyList()
                cur = RawMessage(sender, null, body, ts.ms, ts.precisionMs, attachments = att, sourceLine = idx + 1)
            } else {
                val c = cur
                if (c != null) cur = c.copy(body = c.body + "\n" + line)
                else if (line.isNotBlank()) errors.add(ParseError(idx + 1, "Line has no timestamp and no message above it; each message needs a date and time."))
            }
        }
        flush()
        if (zoneAssumed) warnings.add("Timestamps carry no time zone; ${options.zone} was assumed.")
        if (system > 0) warnings.add("$system system lines (joins, encryption notices, subject changes) were skipped.")
        val title = Regex("""(?i)whatsapp chat (?:with|-)\s*(.+?)(?:\.txt)?$""").find(input.name)?.groupValues?.get(1)?.trim()
        return ParseResult(id, provider, null, msgs, errors, warnings, titleGuess = title)
    }
}

class WhatsAppTextImporter : ChatLogImporter("whatsapp-txt", "WhatsApp exported chat (.txt)", "whatsapp", 85)

class PlainTextImporter : ChatLogImporter("text", "Plain text / pasted conversation ('[date time] Name: message')", "paste", 40)

/** A ZIP is opened in memory; each text entry is parsed by the matching importer. Media entries are only counted. */
class ZipImporter : Importer {
    override val id = "zip"
    override val label = "ZIP archive containing supported exports"

    override fun sniff(input: ImportInput): Int =
        if (input.bytes.size > 4 && input.bytes[0] == 0x50.toByte() && input.bytes[1] == 0x4B.toByte() && input.bytes[2] == 3.toByte()) 95 else 0

    override fun parse(input: ImportInput, options: ParseOptions): ParseResult {
        val warnings = ArrayList<String>()
        val errors = ArrayList<ParseError>()
        val msgs = ArrayList<RawMessage>()
        var provider = "zip"
        var title: String? = null
        var skipped = 0
        var entries = 0
        var total = 0L
        try {
            ZipInputStream(ByteArrayInputStream(input.bytes)).use { zin ->
                while (true) {
                    val e = zin.nextEntry ?: break
                    if (e.isDirectory) continue
                    if (++entries > MAX_ENTRIES) { errors.add(ParseError(0, "Archive has more than $MAX_ENTRIES entries.")); break }
                    val buf = java.io.ByteArrayOutputStream()
                    val chunk = ByteArray(8192)
                    while (true) {
                        val n = zin.read(chunk)
                        if (n < 0) break
                        total += n
                        if (total > MAX_BYTES) { errors.add(ParseError(0, "Archive expands past the ${MAX_BYTES / 1_048_576} MB safety limit.")); return ParseResult(id, provider, null, msgs, errors, warnings) }
                        buf.write(chunk, 0, n)
                    }
                    val name = e.name.substringAfterLast('/')
                    val lower = name.lowercase(Locale.ROOT)
                    if (!(lower.endsWith(".txt") || lower.endsWith(".csv") || lower.endsWith(".json") || lower.endsWith(".xml"))) { skipped++; continue }
                    val inner = ImportInput(name, buf.toByteArray())
                    val best = Importers.detect(inner).firstOrNull { it.first !is ZipImporter }
                    if (best == null) { warnings.add("'$name' was not recognised and was skipped."); continue }
                    val r = best.first.parse(inner, options)
                    if (msgs.isEmpty()) { provider = r.providerGuess; title = r.titleGuess }
                    msgs.addAll(r.messages); errors.addAll(r.errors.map { it.copy(message = "$name: ${it.message}") }); warnings.addAll(r.warnings.map { "$name: $it" })
                }
            }
        } catch (e: Exception) {
            errors.add(ParseError(0, "Archive could not be read: ${e.message?.take(120)}"))
        }
        if (skipped > 0) warnings.add("$skipped non-text files in the archive (media, attachments) were not imported.")
        return ParseResult(id, provider, null, msgs, errors, warnings.distinct(), titleGuess = title)
    }

    private companion object {
        const val MAX_ENTRIES = 500
        const val MAX_BYTES = 256L * 1024 * 1024
    }
}

object Importers {
    val all: List<Importer> = listOf(
        ZipImporter(), SmsBackupXmlImporter(), WhatsAppTextImporter(), JsonImporter(), CsvImporter(), PlainTextImporter(),
    )

    /** Importers that recognise the input, best first. Empty means unsupported (for example HTML exports). */
    fun detect(input: ImportInput): List<Pair<Importer, Int>> =
        all.map { it to it.sniff(input) }.filter { it.second > 0 }.sortedByDescending { it.second }

    fun byId(id: String): Importer? = all.firstOrNull { it.id == id }
}
