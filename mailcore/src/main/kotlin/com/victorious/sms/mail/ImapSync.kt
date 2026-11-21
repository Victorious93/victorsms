package com.victorious.sms.mail

import jakarta.mail.Address
import jakarta.mail.BodyPart
import jakarta.mail.FetchProfile
import jakarta.mail.Flags
import jakarta.mail.Folder
import jakarta.mail.Message
import jakarta.mail.Multipart
import jakarta.mail.Part
import jakarta.mail.Store
import jakarta.mail.UIDFolder
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeMessage
import jakarta.mail.internet.MimeUtility
import jakarta.mail.search.HeaderTerm
import org.eclipse.angus.mail.imap.IMAPFolder

/** Per-account, per-folder sync position. Persist it; a changed UIDVALIDITY means the old UIDs mean nothing. */
data class SyncState(val uidValidity: Long = 0, val lastUid: Long = 0)

data class SyncResult(val state: SyncState, val messages: List<MailMessage>, val fullResync: Boolean)

class ImapClient(
    private val account: MailAccount,
    private val secrets: SecretStore,
    private val tls: TlsPolicy = TlsPolicy(),
) {
    private fun <T> withStore(block: (Store) -> T): T {
        val secret = secrets.get(account.id) ?: error("no credential stored for ${account.id}")
        val session = Sessions.imap(account, tls, secret)
        val store = session.getStore(Sessions.proto(account.imap.security, "imap"))
        store.connect(account.imap.host, account.imap.port, account.username, secret)
        try { return block(store) } finally { runCatching { store.close() } }
    }

    fun listFolders(): List<String> = withStore { s ->
        s.defaultFolder.list("*").filter { it.type and Folder.HOLDS_MESSAGES != 0 }.map { it.fullName }
    }

    /** Fetch messages newer than [state]. Safe to call repeatedly; it never re-downloads what [state] already covers. */
    fun sync(folder: String, state: SyncState, limit: Int = 200): SyncResult = withStore { s ->
        val f = s.getFolder(folder) as IMAPFolder
        f.open(Folder.READ_ONLY)
        try {
            val validity = f.uidValidity
            val full = state.uidValidity != 0L && state.uidValidity != validity
            val from = if (full || state.uidValidity == 0L) 1L else state.lastUid + 1
            // RFC 3501: "UID n:*" always includes the highest-UID message even when it is below n, so filter explicitly.
            val msgs = f.getMessagesByUID(from, UIDFolder.LASTUID).filterNotNull().filter { f.getUID(it) >= from }
            val fp = FetchProfile().apply { add(FetchProfile.Item.ENVELOPE); add(FetchProfile.Item.FLAGS); add(FetchProfile.Item.CONTENT_INFO); add(UIDFolder.FetchProfileItem.UID) }
            val take = msgs.takeLast(limit).toTypedArray()
            f.fetch(take, fp)
            val out = take.map { toMailMessage(it as MimeMessage, folder, f.getUID(it)) }
            val maxUid = (out.maxOfOrNull { it.uid } ?: state.lastUid).coerceAtLeast(if (full) 0 else state.lastUid)
            SyncResult(SyncState(validity, maxUid), out, full)
        } finally { runCatching { f.close(false) } }
    }

    fun markRead(folder: String, uid: Long, read: Boolean = true) = withStore { s ->
        val f = s.getFolder(folder) as IMAPFolder
        f.open(Folder.READ_WRITE)
        try { f.getMessageByUID(uid)?.setFlag(Flags.Flag.SEEN, read) } finally { runCatching { f.close(false) } }
    }

    /** Is a message with this Message-ID in [folder]? Used to resolve an uncertain send via the Sent folder. */
    fun containsMessageId(folder: String, messageId: String): Boolean = withStore { s ->
        val f = s.getFolder(folder)
        f.open(Folder.READ_ONLY)
        try { f.search(HeaderTerm("Message-ID", messageId)).isNotEmpty() } finally { runCatching { f.close(false) } }
    }

    /** Append a copy to a folder (e.g. Sent) when the server does not file submitted mail itself. */
    fun append(folder: String, msg: MimeMessage) = withStore { s ->
        val f = s.getFolder(folder)
        f.open(Folder.READ_WRITE)
        try { f.appendMessages(arrayOf(msg)) } finally { runCatching { f.close(false) } }
    }

    private fun addrs(a: Array<Address>?): List<String> = a?.map { (it as? InternetAddress)?.address ?: it.toString() } ?: emptyList()

    private fun toMailMessage(m: MimeMessage, folder: String, uid: Long): MailMessage {
        val text = StringBuilder(); var html: String? = null; val atts = ArrayList<Attachment>()
        fun walk(p: Part) {
            when {
                p.isMimeType("multipart/*") -> { val mp = p.content as Multipart; for (i in 0 until mp.count) walk(mp.getBodyPart(i)) }
                Part.ATTACHMENT.equals(p.disposition, true) || (p.fileName != null && !p.isMimeType("text/*")) ->
                    atts += Attachment(MimeUtility.decodeText(p.fileName ?: "attachment"), p.contentType.substringBefore(';').trim(), p.inputStream.readBytes())
                p.isMimeType("text/plain") -> text.append(p.content as String)
                p.isMimeType("text/html") -> html = (html ?: "") + (p.content as String)
                p.isMimeType("message/rfc822") -> {}
            }
        }
        walk(m)
        val refs = m.getHeader("References")?.firstOrNull()?.split(Regex("\\s+"))?.filter { it.startsWith("<") } ?: emptyList()
        return MailMessage(
            messageId = m.messageID ?: "<no-id-${folder}-$uid@local>",
            inReplyTo = m.getHeader("In-Reply-To")?.firstOrNull()?.trim(),
            references = refs,
            from = addrs(m.from).firstOrNull().orEmpty(),
            replyTo = addrs(m.replyTo).filter { it != addrs(m.from).firstOrNull() || m.getHeader("Reply-To") != null },
            to = addrs(m.getRecipients(Message.RecipientType.TO)),
            cc = addrs(m.getRecipients(Message.RecipientType.CC)),
            subject = m.subject.orEmpty(),
            dateMs = (m.sentDate ?: m.receivedDate)?.time ?: 0L,
            textBody = text.toString().ifEmpty { html?.let { org.jsoup.Jsoup.parse(it).text() }.orEmpty() },
            htmlBody = html,
            attachments = atts,
            seen = m.flags.contains(Flags.Flag.SEEN),
            folder = folder,
            uid = uid,
        )
    }
}
