package com.victorious.sms.mail

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SafeHtmlTest {
    private fun r(h: String, remote: Boolean = false) = SafeHtml.render(h, remote)

    @Test fun `scripts handlers frames forms and styles are removed`() {
        val h = r("""<p onclick="x()">hi</p><script>alert(1)</script><iframe src="https://evil"></iframe><form action="https://evil"><input name=p></form>
            <style>body{background:url(https://evil/track)}</style><object data="x"></object><svg onload="x()"></svg><b style="background:url(https://evil)">b</b>""").html
        for (bad in listOf("script", "onclick", "iframe", "<form", "<style", "evil", "<object", "<svg", "onload", "style=")) assertFalse(h.contains(bad, true), "$bad in $h")
        assertTrue(h.contains("hi"))
    }

    @Test fun `remote images are blocked by default and counted`() {
        val out = r("""<img src="https://tracker.example/pixel.gif"><img src="http://x/y.png"><p>text</p>""")
        assertEquals(2, out.blockedRemoteImages); assertFalse(out.html.contains("tracker")); assertFalse(out.html.contains("<img"))
        assertTrue(out.html.contains("remote image blocked"))
    }

    @Test fun `owner can opt in to https images only`() {
        val out = r("""<img src="https://ok.example/a.png"><img src="http://plain.example/b.png">""", remote = true)
        assertTrue(out.html.contains("https://ok.example/a.png")); assertFalse(out.html.contains("plain.example")); assertEquals(1, out.blockedRemoteImages)
    }

    @Test fun `inline cid images are not treated as remote`() {
        val out = r("""<img src="cid:logo@x">""")
        assertEquals(0, out.blockedRemoteImages)
    }

    @Test fun `javascript and data links are dropped and http links get noopener`() {
        val out = r("""<a href="javascript:alert(1)">a</a><a href="data:text/html;base64,AAA">b</a><a href="https://ok.example">c</a><a href="mailto:x@y.z">d</a>""")
        assertFalse(out.html.contains("javascript", true)); assertFalse(out.html.contains("data:", true))
        assertTrue(out.html.contains("""href="https://ok.example"""")); assertTrue(out.html.contains("noopener")); assertEquals(2, out.blockedLinks)
    }

    @Test fun `plain text is escaped`() {
        assertEquals("&lt;script&gt;alert(1)&lt;/script&gt;<br>line2", SafeHtml.renderText("<script>alert(1)</script>\nline2"))
    }
}
