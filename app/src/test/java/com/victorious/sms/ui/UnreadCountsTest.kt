package com.victorious.sms.ui

import com.victorious.sms.ui.screens.UnreadCounts
import org.junit.Assert.assertEquals
import org.junit.Test

class UnreadCountsTest {
    @Test fun countsPerSourceAndTotal() {
        val c = UnreadCounts.of(listOf("sim" to true, "sim" to true, "sim" to false, "matrix" to true, "mail" to false))
        assertEquals(2, c["sim"]); assertEquals(1, c["matrix"]); assertEquals(null, c["mail"]); assertEquals(3, c["unread"])
    }
    @Test fun labels() {
        assertEquals("SMS", UnreadCounts.label("SMS", 0)); assertEquals("SMS (4)", UnreadCounts.label("SMS", 4)); assertEquals("SMS (99+)", UnreadCounts.label("SMS", 150))
    }
}
