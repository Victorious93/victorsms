package com.victorious.sms.sms

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream

class MmsRetrieveTest {
    private fun ByteArrayOutputStream.b(vararg x: Int) = x.forEach { write(it) }
    private fun ByteArrayOutputStream.cstr(s: String) { write(s.toByteArray(Charsets.ISO_8859_1)); write(0) }
    private fun uintvar(v: Int): ByteArray { val out = mutableListOf(v and 0x7f); var x = v ushr 7; while (x > 0) { out.add(0, (x and 0x7f) or 0x80); x = x ushr 7 }; return out.map { it.toByte() }.toByteArray() }

    private fun part(ct: ByteArray, extraHeaders: ByteArray, data: ByteArray) = ByteArrayOutputStream().apply {
        write(uintvar(ct.size + extraHeaders.size)); write(uintvar(data.size)); write(ct); write(extraHeaders); write(data) }.toByteArray()

    private fun sample(subjectUtf8: String = "Hi ✓"): ByteArray {
        val o = ByteArrayOutputStream()
        o.b(0x8C, 0x84)                                      // m-retrieve-conf
        o.b(0x98); o.cstr("T123")                            // transaction id
        o.b(0x8D, 0x92)                                      // version 1.2 (skipped generically)
        o.b(0x8B); o.cstr("mid-1")                           // message id
        o.b(0x85, 0x04, 0x65, 0x00, 0x00, 0x00)              // date long-int 4 bytes
        val fromAddr = "+15551234567/TYPE=PLMN".toByteArray()
        o.b(0x89, fromAddr.size + 2, 0x80); o.write(fromAddr); o.b(0)
        o.b(0x97); o.cstr("+15557654321/TYPE=PLMN")
        val subj = subjectUtf8.toByteArray(Charsets.UTF_8)
        o.b(0x96, subj.size + 2, 0xEA); o.write(subj); o.b(0)   // value-length, charset UTF-8 (106 = 0x6A|0x80)
        o.b(0x88, 0x81)                                      // a header this decoder does not use: skipped by encoding class
        o.b(0x84); val ctParams = byteArrayOf(0xB3.toByte(), 0x8A.toByte()) + "<smil>".toByteArray() + byteArrayOf(0)
        o.b(ctParams.size); o.write(ctParams)               // multipart.related; start=<smil>
        o.write(uintvar(2))
        // part 1: text/plain charset utf-8, content-location
        val ct1 = byteArrayOf(3, 0x83.toByte(), 0x81.toByte(), 0xEA.toByte())
        val h1 = byteArrayOf(0x8E.toByte()) + "text.txt".toByteArray() + byteArrayOf(0)
        o.write(part(ct1, h1, "hello ☺".toByteArray(Charsets.UTF_8)))
        // part 2: image/jpeg name=a.jpg, content-id
        val ct2 = byteArrayOf(8, 0x9E.toByte(), 0x85.toByte()) + "a.jpg".toByteArray() + byteArrayOf(0)
        val h2 = byteArrayOf(0xC0.toByte(), 0x22) + "<img1>".toByteArray() + byteArrayOf(0)
        o.write(part(ct2, h2, ByteArray(300) { it.toByte() }))
        return o.toByteArray()
    }

    @Test fun decodesHeadersAndParts() {
        val m = MmsRetrieve.parse(sample())!!
        assertEquals("+15551234567", m.from); assertEquals(listOf("+15557654321"), m.to)
        assertEquals("Hi ✓", m.subject); assertEquals(0x65000000L, m.dateSec)
        assertEquals("mid-1", m.messageId); assertEquals("T123", m.transactionId)
        assertEquals("application/vnd.wap.multipart.related", m.contentType)
        assertEquals(2, m.parts.size)
        val t = m.parts[0]; assertEquals("text/plain", t.contentType); assertEquals(106, t.charset); assertEquals("text.txt", t.contentLocation); assertEquals("hello ☺", t.text())
        val img = m.parts[1]; assertEquals("image/jpeg", img.contentType); assertEquals("a.jpg", img.name); assertEquals("<img1>", img.contentId)
        assertEquals(300, img.data.size); assertNull(img.text())
    }

    @Test fun refusesWrongMessageType() {
        val p = sample(); p[1] = 0x82.toByte()           // m-notification-ind is not a retrieved message
        assertNull(MmsRetrieve.parse(p))
    }

    @Test fun truncationAtEveryLengthIsRejectedNotGuessed() {
        val p = sample()
        for (n in 0 until p.size) { val m = MmsRetrieve.parse(p.copyOf(n)); if (m != null) fail("accepted a PDU truncated to $n of ${p.size} bytes") }
    }

    @Test fun refusesTooManyParts() {
        val o = ByteArrayOutputStream(); o.b(0x8C, 0x84, 0x84, 0xA3); o.write(uintvar(MmsRetrieve.MAX_PARTS + 1))
        assertNull(MmsRetrieve.parse(o.toByteArray()))
    }

    @Test fun oversizedPartLengthIsRejected() {
        val o = ByteArrayOutputStream(); o.b(0x8C, 0x84, 0x84, 0xA3); o.write(uintvar(1)); o.write(uintvar(1)); o.write(uintvar(Int.MAX_VALUE / 2)); o.b(0x83)
        assertNull(MmsRetrieve.parse(o.toByteArray()))
    }
}
