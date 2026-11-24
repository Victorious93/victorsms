package com.victorious.comms

import java.security.MessageDigest
import java.text.Normalizer
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale

enum class Direction(val db: String) {
    IN("in"),
    OUT("out"),
    ;

    companion object {
        fun fromDb(s: String) = if (s == "out") OUT else IN
    }
}

/** Who wrote a message. Only [OWNER] and [BOT_EDITED] text may teach a writing style. */
enum class Author(val db: String) {
    OWNER("owner"),
    BOT_AUTO("bot_auto"),
    BOT_APPROVED("bot_approved"),
    BOT_EDITED("bot_edited"),
}

data class AttachmentMeta(val name: String?, val mime: String?, val bytes: Long?)

/** One message exactly as an exporter described it. Nothing here is guessed: unknown means null/empty. */
data class RawMessage(
    /** Sender as the export names it (name or number); empty when the export does not say. */
    val sender: String,
    /** Set only when the source states direction (SMS backup `type`, a CSV direction column). */
    val direction: Direction?,
    val body: String,
    /** Epoch milliseconds, UTC. */
    val ts: Long,
    /** How precise [ts] really is (1 = ms, 1000 = s, 60000 = minute). Drives duplicate tolerance. */
    val tsPrecisionMs: Long,
    val providerId: String? = null,
    val attachments: List<AttachmentMeta> = emptyList(),
    /** For multi-thread files (SMS backups): the other party's address. */
    val counterpart: String? = null,
    val sourceLine: Int = 0,
)

data class ParseError(val line: Int, val message: String)

data class ParseResult(
    val importerId: String,
    val providerGuess: String,
    val accountGuess: String?,
    val messages: List<RawMessage>,
    val errors: List<ParseError>,
    val warnings: List<String>,
    /** Optional conversation name the export carried (WhatsApp file name, a title column). */
    val titleGuess: String? = null,
    /** Roles ("timestamp", "body") that could not be found; the UI then asks for an explicit [ColumnMapping]. */
    val needsMapping: List<String> = emptyList(),
)

enum class DateOrder { MDY, DMY }

/** Maps CSV/JSON field names to roles when automatic detection is wrong or ambiguous. */
data class ColumnMapping(
    val timestamp: String? = null,
    val sender: String? = null,
    val direction: String? = null,
    val body: String? = null,
    val counterpart: String? = null,
    val id: String? = null,
)

data class ParseOptions(
    /** Zone used only when the source carries none; the preview always says so. */
    val zone: ZoneId = ZoneOffset.UTC,
    val dateOrder: DateOrder? = null,
    val mapping: ColumnMapping? = null,
)

/** Raw bytes of an uploaded or pasted export. */
class ImportInput(val name: String, val bytes: ByteArray) {
    val sha256: String by lazy { Hashing.sha256Hex(bytes) }

    /** UTF-8 text with a BOM stripped. Invalid sequences become U+FFFD, never an exception. */
    val text: String by lazy {
        val s = String(bytes, Charsets.UTF_8)
        if (s.isNotEmpty() && s[0] == '﻿') s.substring(1) else s
    }
}

object Hashing {
    fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    fun sha256Hex(s: String): String = sha256Hex(s.toByteArray(Charsets.UTF_8))
}

object Text {
    /** Normalisation used for fingerprints only. Stored text is never altered, so emoji and spelling survive. */
    fun fingerprintForm(s: String): String =
        Normalizer.normalize(s.replace("\r\n", "\n").replace('\r', '\n'), Normalizer.Form.NFC).trim()

    fun contentFingerprint(direction: Direction, body: String): String =
        Hashing.sha256Hex(direction.db + "\u0000" + fingerprintForm(body))
}

object Phones {
    fun digits(s: String) = s.filter { it.isDigit() }

    /** Last ten digits: tolerant of `+1`, brackets and dashes between exports. Null when too short to be a number. */
    fun suffixKey(raw: String): String? = digits(raw).takeIf { it.length >= 7 }?.takeLast(10)

    fun looksLikePhone(raw: String): Boolean =
        raw.count { it.isDigit() } >= 7 && raw.all { it.isDigit() || it in "+-() . " }
}

object Keys {
    /** Stable identity for a participant: a phone suffix when it is a number, else the case-folded name. */
    fun participant(raw: String): String {
        val t = raw.trim()
        if (Phones.looksLikePhone(t)) Phones.suffixKey(t)?.let { return "p:$it" }
        return "n:" + Text.fingerprintForm(t).lowercase(Locale.ROOT)
    }
}
