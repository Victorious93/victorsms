package com.victorious.mail

import org.jsoup.Jsoup
import org.jsoup.safety.Safelist

/**
 * Untrusted mail → safe HTML for a WebView with JavaScript OFF. Allow-list only: no script/style/iframe/form/object/event handlers;
 * links limited to http, https, mailto (rel=noopener nofollow); remote images are BLOCKED by default (tracking pixels) and replaced by a
 * placeholder; `cid:` images (inline attachments) are allowed. [allowRemoteImages] is a per-message owner choice, never a default.
 */
object HtmlSanitizer {
    data class Result(val html: String, val blockedRemoteImages: Int)

    fun sanitize(html: String, allowRemoteImages: Boolean = false): Result {
        val list = Safelist.relaxed().removeTags("img").addTags("img").addAttributes("img", "src", "alt", "width", "height")
            .addProtocols("img", "src", "cid", "http", "https").addProtocols("a", "href", "http", "https", "mailto").addEnforcedAttribute("a", "rel", "noopener noreferrer nofollow")
            .removeAttributes(":all", "style", "class", "id")
        val clean = Jsoup.clean(html, "", list, org.jsoup.nodes.Document.OutputSettings().prettyPrint(false))
        val doc = Jsoup.parseBodyFragment(clean)
        var blocked = 0
        doc.select("img").forEach { img ->
            val src = img.attr("src")
            if (src.startsWith("cid:", true)) return@forEach
            if (allowRemoteImages && (src.startsWith("https://", true))) return@forEach
            blocked++; img.replaceWith(org.jsoup.nodes.TextNode("[remote image blocked${img.attr("alt").takeIf { it.isNotBlank() }?.let { ": $it" } ?: ""}]"))
        }
        doc.select("a[href]").forEach { a -> a.attr("target", "_blank") }
        return Result(doc.body().html(), blocked)
    }
}
