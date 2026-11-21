package com.victorious.mail

/** Builds reply / reply-all / forward drafts. Replies use the SELECTED account as sender and honour Reply-To. */
object Compose {
    fun reply(parent: MailMessage, account: MailAccount, body: String, all: Boolean, id: String): Draft {
        val (irt, refs) = Threading.replyHeaders(parent)
        val me = account.address.lowercase()
        val primary = (parent.replyTo.ifEmpty { parent.from })          // Reply-To wins over From
        val others = if (all) (parent.to + parent.cc + (if (parent.replyTo.isNotEmpty()) parent.from else emptyList())) else emptyList()
        val to = primary.distinctBy { it.address.lowercase() }.filter { it.address.lowercase() != me }
        val cc = others.distinctBy { it.address.lowercase() }.filter { it.address.lowercase() != me && to.none { t -> t.address.equals(it.address, true) } }
        return Draft(id, account.id, to.ifEmpty { primary }, cc, emptyList(), rePrefix(parent.subject), body, emptyList(), irt, refs)
    }

    fun forward(parent: MailMessage, account: MailAccount, to: List<Addr>, note: String, id: String, includeAttachments: Boolean = true): Draft {
        val quoted = buildString {
            append(note).append("\n\n---------- Forwarded message ----------\n")
            append("From: ").append(parent.from.joinToString()).append("\nDate: ").append(java.util.Date(parent.dateMs)).append("\nSubject: ").append(parent.subject)
            append("\nTo: ").append(parent.to.joinToString()).append("\n\n").append(parent.text ?: "")
        }
        // A forward starts a new conversation on purpose: no In-Reply-To/References.
        return Draft(id, account.id, to, emptyList(), emptyList(), fwdPrefix(parent.subject), quoted, if (includeAttachments) parent.attachments else emptyList())
    }

    fun rePrefix(s: String) = if (Regex("^\\s*re\\s*:", RegexOption.IGNORE_CASE).containsMatchIn(s)) s else "Re: $s"
    fun fwdPrefix(s: String) = if (Regex("^\\s*fwd?\\s*:", RegexOption.IGNORE_CASE).containsMatchIn(s)) s else "Fwd: $s"
}
