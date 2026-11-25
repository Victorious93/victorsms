package com.victorious.sms.sms

/**
 * Decoder for a downloaded MMS (m-retrieve-conf, OMA MMS Encapsulation 1.3 + WSP multipart). Pure Kotlin so it is unit-tested on the
 * JVM. Defensive: any malformed length, unknown required structure or size over the caps returns null — never a partial guess.
 * Content is returned to the caller only; nothing here logs it.
 */
data class MmsPart(val contentType: String, val name: String?, val contentLocation: String?, val contentId: String?, val charset: Int?, val data: ByteArray) {
    val isText get() = contentType.startsWith("text/") && contentType != "text/x-vcard"
    fun text(): String? = if (!isText) null else String(data, charsetFor(charset))
    override fun equals(other: Any?) = other is MmsPart && contentType == other.contentType && name == other.name && data.contentEquals(other.data)
    override fun hashCode() = contentType.hashCode() * 31 + data.contentHashCode()
}

data class MmsMessage(
    val from: String?, val to: List<String>, val subject: String?, val dateSec: Long?, val messageId: String?, val transactionId: String?,
    val contentType: String, val parts: List<MmsPart>,
)

internal fun charsetFor(mib: Int?): java.nio.charset.Charset = when (mib) {
    3 -> Charsets.US_ASCII; 4 -> Charsets.ISO_8859_1; 1000 -> Charsets.UTF_16; 1015 -> Charsets.UTF_16
    else -> Charsets.UTF_8   // 106 = UTF-8 and the sensible default for anything unknown
}

object MmsRetrieve {
    const val MAX_PDU_BYTES = 8 * 1024 * 1024
    const val MAX_PARTS = 64

    private const val TYPE_RETRIEVE_CONF = 0x84

    private val WELL_KNOWN_MEDIA = mapOf(
        0x00 to "*/*", 0x01 to "text/*", 0x02 to "text/html", 0x03 to "text/plain", 0x06 to "text/x-vcalendar", 0x07 to "text/x-vcard",
        0x08 to "text/x-wml", 0x1D to "image/gif", 0x1E to "image/jpeg", 0x1F to "image/tiff", 0x20 to "image/png", 0x21 to "image/vnd.wap.wbmp",
        0x22 to "application/vnd.wap.multipart.*", 0x23 to "application/vnd.wap.multipart.mixed", 0x26 to "application/vnd.wap.multipart.alternative",
        0x33 to "application/vnd.wap.multipart.related", 0x3E to "application/vnd.wap.mms-message",
    )

    private class Reader(val b: ByteArray, var i: Int = 0, val end: Int = b.size) {
        fun has(n: Int = 1) = i + n <= end
        fun peek(): Int = if (i < end) b[i].toInt() and 0xff else throw Bad()
        fun u8(): Int = peek().also { i++ }
        fun uintvar(): Long {
            var v = 0L
            repeat(5) { val o = u8(); v = (v shl 7) or (o and 0x7f).toLong(); if (o and 0x80 == 0) return v }
            throw Bad()
        }
        fun valueLength(): Int { val o = u8(); return when { o <= 30 -> o; o == 31 -> uintvar().toIntChecked(); else -> throw Bad() } }
        fun text(): String {
            if (peek() == 0x7F) i++   // quote
            var e = i; while (e < end && b[e].toInt() != 0) e++
            if (e >= end) throw Bad()
            return String(b, i, e - i, Charsets.ISO_8859_1).also { i = e + 1 }
        }
        fun bytes(n: Int): ByteArray { if (n < 0 || !has(n)) throw Bad(); return b.copyOfRange(i, i + n).also { i += n } }
        fun longInt(): Long { val n = u8(); if (n !in 1..8) throw Bad(); var v = 0L; repeat(n) { v = (v shl 8) or u8().toLong() }; return v }
        fun integer(): Long { val o = peek(); return if (o >= 0x80) (u8() and 0x7f).toLong() else longInt() }
        /** Encoded-string-value: Text-string or Value-length Char-set Text-string. */
        fun encodedString(): String {
            if (peek() > 31) return text()
            val len = valueLength(); val stop = i + len; if (stop > end) throw Bad()
            val cs = integer().toInt(); val start = i
            var e = start; while (e < stop && b[e].toInt() != 0) e++
            var s0 = start; if (s0 < e && (b[s0].toInt() and 0xff) == 0x7F) s0++
            return String(b, s0, e - s0, charsetFor(cs)).also { i = stop }
        }
        /** Skip one WSP header value of unknown meaning, using only its encoding class. */
        fun skipValue() { val o = peek(); when { o >= 0x80 -> i++; o <= 30 -> { i++; i += o; if (i > end) throw Bad() }; o == 31 -> { i++; val n = uintvar().toIntChecked(); i += n; if (i > end) throw Bad() }; else -> text() } }
    }

    private class Bad : Exception()
    private fun Long.toIntChecked(): Int = if (this in 0..MAX_PDU_BYTES) toInt() else throw Bad()

    /** Content-type value: constrained (short-int or text) or general (value-length, media, params). Returns type + params. */
    private fun contentType(r: Reader): Triple<String, Map<String, String>, Int?> {
        val o = r.peek()
        if (o >= 0x80) return Triple(WELL_KNOWN_MEDIA[r.u8() and 0x7f] ?: "application/octet-stream", emptyMap(), null)
        if (o > 31) return Triple(r.text(), emptyMap(), null)
        val len = r.valueLength(); val stop = r.i + len; if (stop > r.end) throw Bad()
        val media = if (r.peek() >= 0x80) WELL_KNOWN_MEDIA[r.u8() and 0x7f] ?: "application/octet-stream" else r.text()
        val params = mutableMapOf<String, String>(); var charset: Int? = null
        while (r.i < stop) {
            when (val p = r.peek()) {
                0x81 -> { r.u8(); charset = r.integer().toInt() }                 // Charset
                0x85, 0x97 -> { r.u8(); params["name"] = r.text() }              // Name (1.1 / 1.4)
                0x89 -> { r.u8(); params["type"] = if (r.peek() >= 0x80) (WELL_KNOWN_MEDIA[r.u8() and 0x7f] ?: "") else r.text() }   // Type (multipart/related root)
                0x8A -> { r.u8(); params["start"] = r.text() }                   // Start
                else -> if (p >= 0x80) { r.u8(); r.skipValue() } else { r.text(); r.skipValue() }   // unknown typed or untyped parameter
            }
        }
        r.i = stop
        return Triple(media, params, charset)
    }

    fun parse(pdu: ByteArray): MmsMessage? = try {
        if (pdu.isEmpty() || pdu.size > MAX_PDU_BYTES) null else parseOrThrow(pdu)
    } catch (_: Bad) { null } catch (_: IndexOutOfBoundsException) { null }

    private fun parseOrThrow(pdu: ByteArray): MmsMessage? {
        val r = Reader(pdu)
        if (r.u8() != 0x8C || r.u8() != TYPE_RETRIEVE_CONF) return null      // must start with X-Mms-Message-Type = m-retrieve-conf
        var from: String? = null; val to = mutableListOf<String>(); var subject: String? = null; var date: Long? = null
        var mid: String? = null; var tid: String? = null; var ctype: String? = null
        while (r.has() && ctype == null) {
            val h = r.u8()
            if (h < 0x80) { r.i--; r.text(); r.skipValue(); continue }        // application header (name text + value)
            when (h) {
                0x89 -> { val len = r.valueLength(); val stop = r.i + len; if (stop > r.end) throw Bad()   // From
                    if (r.peek() == 0x80) { r.u8(); from = r.encodedString().substringBefore("/TYPE=") }; r.i = stop }
                0x97 -> to += r.encodedString().substringBefore("/TYPE=")                                   // To
                0x96 -> subject = r.encodedString()                                                           // Subject
                0x85 -> date = r.longInt()                                                                    // Date
                0x8B -> mid = r.text()                                                                        // Message-ID
                0x98 -> tid = r.text()                                                                        // Transaction-ID
                0x84 -> ctype = contentType(r).first                                                          // Content-Type: last header
                else -> r.skipValue()
            }
        }
        val type = ctype ?: return null
        val parts = mutableListOf<MmsPart>()
        if (type.startsWith("application/vnd.wap.multipart")) {
            val n = r.uintvar(); if (n > MAX_PARTS) return null
            repeat(n.toInt()) {
                val hl = r.uintvar().toIntChecked(); val dl = r.uintvar().toIntChecked()
                val hStop = r.i + hl; if (hStop > r.end) throw Bad()
                val hr = Reader(pdu, r.i, hStop)
                val (pt, params, cs) = contentType(hr)
                var loc: String? = null; var cid: String? = null
                while (hr.i < hStop) {
                    val h = hr.u8()
                    when {
                        h == 0x8E -> loc = hr.text()                                   // Content-Location
                        h == 0xC0 -> cid = hr.text().removePrefix("\"")                // Content-ID (Quoted-string: leading 0x22)
                        h >= 0x80 -> hr.skipValue()
                        else -> { hr.i--; hr.text(); hr.skipValue() }
                    }
                }
                r.i = hStop
                parts += MmsPart(pt, params["name"], loc, cid, cs, r.bytes(dl))
            }
        } else {
            parts += MmsPart(type, null, null, null, null, r.bytes(r.end - r.i))
        }
        return MmsMessage(from, to, subject, date, mid, tid, type, parts)
    }
}
