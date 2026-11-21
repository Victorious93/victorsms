package com.victorious.sms.mail

import org.jsoup.Jsoup
import org.jsoup.safety.Safelist

data class RenderedMail(val html: String, val blockedRemoteImages: Int, val blockedLinks: Int)

/**
 * Turns untrusted mail HTML into inert markup. No scripts, styles, forms, frames, event handlers or remote
 * resources survive. Remote images are replaced by a placeholder unless [loadRemoteImages] is true (default false).
 * The result is meant for a WebView with JavaScript disabled and no network access as a second layer.
 */
object SafeHtml {
    private val list = Safelist.relaxed().removeTags("img", "table", "caption", "col", "colgroup", "tbody", "td", "tfoot", "th", "thead", "tr")
        .addTags("table", "tbody", "thead", "tr", "td", "th")
        .preserveRelativeLinks(false)
        .addProtocols("a", "href", "http", "https", "mailto")

    fun render(rawHtml: String, loadRemoteImages: Boolean = false): RenderedMail {
        var blockedImages = 0
        var blockedLinks = 0
        val doc = Jsoup.parse(rawHtml)
        // Count and neutralise remote images before sanitising (the safelist drops <img> entirely).
        doc.select("img").forEach { img ->
            val src = img.attr("src").trim()
            when {
                src.startsWith("cid:", true) -> img.replaceWith(org.jsoup.nodes.TextNode("[inline image]"))
                loadRemoteImages && src.startsWith("https://", true) -> {}
                else -> { blockedImages++; img.replaceWith(org.jsoup.nodes.TextNode("[remote image blocked]")) }
            }
        }
        doc.select("a[href]").forEach { a ->
            val h = a.attr("href").trim().lowercase()
            if (!(h.startsWith("http://") || h.startsWith("https://") || h.startsWith("mailto:"))) { blockedLinks++ }
        }
        val l = if (loadRemoteImages) Safelist.relaxed().addTags("img").addProtocols("img", "src", "https") else list
        val cleaned = Jsoup.clean(doc.body().html(), "", l.addEnforcedAttribute("a", "rel", "noopener noreferrer nofollow"),
            org.jsoup.nodes.Document.OutputSettings().prettyPrint(false))
        return RenderedMail(cleaned, blockedImages, blockedLinks)
    }

    /** Sanitised HTML flattened to text for display without a WebView. */
    fun toPlainText(rawHtml: String): String = Jsoup.parse(render(rawHtml).html).text()

    /** text/plain is escaped, never interpreted. */
    fun renderText(text: String): String = org.jsoup.nodes.Entities.escape(text).replace("\n", "<br>")
}
