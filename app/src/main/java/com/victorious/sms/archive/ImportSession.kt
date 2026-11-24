package com.victorious.sms.archive

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import com.victorious.comms.ColumnMapping
import com.victorious.comms.DateOrder
import com.victorious.comms.ImportEngine
import com.victorious.comms.ImportInput
import com.victorious.comms.ImportPreview
import com.victorious.comms.ImportRequest
import com.victorious.comms.ImportResult
import com.victorious.comms.ParseOptions
import java.io.IOException
import java.io.InputStream
import java.time.ZoneId

object ImportLimits {
    /** Refused before it is read into memory: a hostile or accidental huge file must not exhaust the app. */
    const val MAX_BYTES = 64 * 1024 * 1024

    fun readLimited(stream: InputStream, max: Int = MAX_BYTES): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        var total = 0L
        while (true) {
            val n = stream.read(buf)
            if (n < 0) break
            total += n
            if (total > max) throw IOException("That file is larger than ${max / 1_048_576} MB, which is more than this importer will read.")
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }
}

/** A file or text the owner picked or shared in. Read lazily and only on a background thread. */
object ImportSource {
    fun fromUri(resolver: ContentResolver, uri: Uri): ImportInput {
        var name = uri.lastPathSegment ?: "import"
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) name = c.getString(0)
        }
        val bytes = resolver.openInputStream(uri)?.use { ImportLimits.readLimited(it) } ?: throw IOException("That file could not be opened.")
        return ImportInput(name, bytes)
    }

    fun fromText(name: String, text: String): ImportInput {
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (bytes.size > ImportLimits.MAX_BYTES) throw IOException("That text is too large.")
        return ImportInput(name, bytes)
    }
}

/** What another app handed us through the share sheet, held until the import screen consumes it. */
object PendingShare {
    @Volatile var uri: Uri? = null
    @Volatile var text: String? = null

    fun take(): Pair<Uri?, String?> = synchronized(this) { (uri to text).also { uri = null; text = null } }
}

/**
 * The owner's choices for one import, turned into an [ImportRequest]. No Compose, no Android views: testable.
 * Nothing is written until [commit]; [preview] only reads.
 */
class ImportSession(private val engine: ImportEngine, private val zone: ZoneId = ZoneId.systemDefault()) {
    var input: ImportInput? = null
    var providerLabel = ""
    var account = ""
    val selfSenders = linkedSetOf<String>()
    var dateOrder: DateOrder? = null
    var mapping = ColumnMapping()
    var targetConversation: Long? = null
    var createNew = false
    var applyShift = false
    var force = false

    /** The shift the last preview suggested. Kept while it is applied, so un-ticking the box can restore it. */
    var suggestedShift: Long? = null
        private set

    fun request(): ImportRequest? = input?.let {
        ImportRequest(
            input = it,
            providerLabel = providerLabel.takeIf { s -> s.isNotBlank() },
            account = account,
            selfSenders = selfSenders.toSet(),
            targetConversation = targetConversation,
            createNew = createNew,
            timeShiftMs = if (applyShift) suggestedShift ?: 0L else 0L,
            force = force,
            options = ParseOptions(zone = zone, dateOrder = dateOrder, mapping = mapping.takeIf { m -> m != ColumnMapping() }),
        )
    }

    fun preview(): ImportPreview? {
        val req = request() ?: return null
        val p = engine.preview(req)
        if (!applyShift) suggestedShift = p.groups.firstNotNullOfOrNull { it.suggestedTimeShiftMs }
        return p
    }

    fun commit(): ImportResult = engine.commit(request() ?: error("Choose a file first."))
}
