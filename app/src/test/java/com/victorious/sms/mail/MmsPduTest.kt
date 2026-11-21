package com.victorious.sms.mail

import com.victorious.sms.sms.MmsPdu
import org.junit.Assert.*
import org.junit.Test

class MmsPduTest {
    private fun pdu(tid: String, loc: String) = byteArrayOf(0x01, 0x06, 0x22) + byteArrayOf(0x8C.toByte(), 0x82.toByte(), 0x98.toByte()) + tid.toByteArray() + 0 + byteArrayOf(0x8D.toByte(), 0x90.toByte()) + byteArrayOf(0x83.toByte()) + loc.toByteArray() + 0
    @Test fun parsesTransactionAndLocation() { val n = MmsPdu.parseNotification(pdu("T123", "http://mmsc.example/m?id=9"))!!; assertEquals("T123", n.transactionId); assertEquals("http://mmsc.example/m?id=9", n.contentLocation) }
    @Test fun rejectsGarbageAndNonHttpLocations() { assertNull(MmsPdu.parseNotification(byteArrayOf(1, 2, 3))); assertNull(MmsPdu.parseNotification(pdu("T", "file:///etc/passwd"))); assertNull(MmsPdu.parseNotification(ByteArray(0))) }
}
