package com.victorious.mail

object Threading {
    private val PREFIX = Regex("^(\\s*(re|fwd?|aw|sv)\\s*(\\[\\d+])?\\s*:\\s*)+", RegexOption.IGNORE_CASE)
    fun normSubject(s: String) = s.replace(PREFIX, "").trim().lowercase()

    /** Thread = root of the References chain, else In-Reply-To, else own Message-ID; subject only as a last resort for broken clients. */
    fun key(m: MailMessage): String =
        m.references.firstOrNull()?.trim() ?: m.inReplyTo?.trim() ?: m.messageId?.trim() ?: ("subj:" + normSubject(m.subject))

    /** Headers for a reply so the recipient's client threads it: In-Reply-To = parent, References = parent's references + parent id. */
    fun replyHeaders(parent: MailMessage): Pair<String?, List<String>> {
        val refs = (parent.references + listOfNotNull(parent.messageId)).distinct().takeLast(20)
        return parent.messageId to refs
    }
}
