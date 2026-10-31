package com.victorious.sms.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FleetThemeCodecTest {
    private val sample = """
        {"schemaVersion":1,"mode":"NEON_FLEET","iconStyle":"SYSTEM","iconPackPackage":"","updatedAtMs":0,
         "palette":{"background":"#FF0B0C12","surface":"#FF161821","primary":"#FF00E5FF","secondary":"#FFE840FF",
         "onBackground":"#FFF5F7FF","muted":"#FF9AA3B5","danger":"#FFFF6B8A","stale":"#FFFFC14D","tile":"#CC1A1D2B"},
         "futureField":42}
    """.trimIndent()

    @Test fun parsesSpecSample() {
        val p = FleetThemeCodec.parseJson(sample)!!
        assertEquals(0xFF0B0C12L, p.background)
        assertEquals(0xFF00E5FFL, p.primary)
        assertEquals(0xCC1A1D2BL, p.tile)
        assertEquals(0xFFFFC14DL, p.stale)
    }

    @Test fun rejectsOtherSchemaVersions() {
        for (v in listOf("0", "2", "-1", "\"1\"x")) {
            assertNull(FleetThemeCodec.parseJson(sample.replace("\"schemaVersion\":1", "\"schemaVersion\":$v")))
        }
        assertNull(FleetThemeCodec.parseJson(sample.replace("\"schemaVersion\":1,", "")))
    }

    @Test fun rejectsGarbage() {
        assertNull(FleetThemeCodec.parseJson(null))
        assertNull(FleetThemeCodec.parseJson(""))
        assertNull(FleetThemeCodec.parseJson("not json"))
        assertNull(FleetThemeCodec.parseJson("""{"schemaVersion":1}"""))
    }

    @Test fun invalidOrMissingKeyOnlyDropsThatKey() {
        val p = FleetThemeCodec.parseJson(
            """{"schemaVersion":1,"palette":{"primary":"#00E5FF","secondary":"red","surface":"","danger":"#12345"}}"""
        )!!
        assertEquals(0xFF00E5FFL, p.primary) // #RRGGBB -> opaque
        assertNull(p.secondary)
        assertNull(p.surface)
        assertNull(p.danger)
        assertNull(p.background)
    }

    @Test fun hexParsing() {
        assertEquals(0xFFABCDEFL, FleetThemeCodec.parseHex("#abcdef"))
        assertEquals(0x80ABCDEFL, FleetThemeCodec.parseHex("#80ABCDEF"))
        assertNull(FleetThemeCodec.parseHex("ABCDEF"))
        assertNull(FleetThemeCodec.parseHex("#GGGGGG"))
        assertNull(FleetThemeCodec.parseHex("#1234567"))
    }

    @Test fun certPinning() {
        // Same values as FleetThemeClient (that object touches android.net.Uri, unavailable in JVM tests).
        val pins = setOf("B9:9A:AF:E2:C1:41:8B:5D:64:AA:0D:4B:AC:61:FC:F7:A7:BA:81:44:35:E0:EF:89:E8:AA:55:BE:56:4E:4A:69")
        val revoked = setOf("1B:06:A8:9E:7F:F5:BA:21:C9:A4:F8:F1:F0:4F:95:E5:62:78:A1:22:18:AD:37:33:28:CE:E7:83:1D:4F:BA:CA")
        assertTrue(FleetThemeCodec.certAllowed(pins.first().lowercase(), pins, revoked))
        assertFalse(FleetThemeCodec.certAllowed(revoked.first(), pins, revoked))
        assertFalse(FleetThemeCodec.certAllowed(revoked.first(), pins + revoked, revoked))
        assertFalse(FleetThemeCodec.certAllowed(null, pins, revoked))
        assertFalse(FleetThemeCodec.certAllowed("", pins, revoked))
    }
}
