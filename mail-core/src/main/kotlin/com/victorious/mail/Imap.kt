package com.victorious.mail

import jakarta.mail.BodyPart
import jakarta.mail.Flags
import jakarta.mail.Folder
import jakarta.mail.Multipart
import jakarta.mail.Part
import jakarta.mail.UIDFolder
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeMessage
import jakarta.mail.internet.MimeUtility
import org.eclipse.angus.mail.imap.IMAPFolder

/** Per-account, per-folder incremental sync cursor. A changed UIDVALIDITY invalidates the cursor (full resync of that folder). */
data class SyncState(val accountId: String, val folder: String, val uidValidity: Long, val lastUid: Long)

class ImapClient(private val account: MailAccount) {
    private fun <T> withStore(block: (jakarta.mail.Store) -> T): T {
        val s = Net.session("imap", account.imap)
        val store = s.getStore(if (account.imap.security == Security.SSL_TLS) "imaps" else "imap")
        store.connect(account.imap.host, account.imap.port, Net.user(account.imap.auth), Net.secret(account.imap.auth))
        try { return block(store) } finally { try { store.close() } catch (_: Exception) { } }
    }

    fun folders(): List<String> = withStore { st -> st.defaultFolder.list("*").filter { it.type and Folder.HOLDS_MESSAGES != 0 }.map { it.fullName } }

    /** Fetch messages newer than [state] (or everything for a new/invalidated cursor). Returns the new cursor and the messages. */
    fun sync(folderName: String, state: SyncState?, limit: Int = 500): Pair<SyncState, List<MailMessage>> = withStore { st ->
        val f = st.getFolder(folderName) as IMAPFolder
        f.open(Folder.READ_ONLY)
        try {
            val validity = f.uidValidity
            val from = if (state != null && state.uidValidity == validity) state.lastUid + 1 else 1L
            val msgs = f.getMessagesByUID(from, UIDFolder.LASTUID).filterNotNull().filter { f.getUID(it) >= from }.takeLast(limit)
            val out = msgs.map { toMail(f, it as MimeMessage) }
            val last = out.maxOfOrNull { it.uid } ?: (state?.takeIf { it.uidValidity == validity }?.lastUid ?: 0L)
            SyncState(account.id, folderName, validity, last) to out
        } finally { try { f.close(false) } catch (_: Exception) { } }
    }

    /** Re-fetch one message including attachment bytes (the app caches text/HTML only). */
    fun fetchOne(folderName: String, uid: Long): MailMessage? = withStore { st ->
        val f = st.getFolder(folderName) as IMAPFolder; f.open(Folder.READ_ONLY)
        try { f.getMessageByUID(uid)?.let { toMail(f, it as MimeMessage) } } finally { try { f.close(false) } catch (_: Exception) { } }
    }

    fun setSeen(folderName: String, uid: Long, seen: Boolean) = withStore { st ->
        val f = st.getFolder(folderName) as IMAPFolder; f.open(Folder.READ_WRITE)
        try { f.getMessageByUID(uid)?.setFlag(Flags.Flag.SEEN, seen) } finally { f.close(false) }
    }

    /** Stores a draft in the server's Drafts folder (APPENDUID semantics not needed). */
    fun saveDraft(draftsFolder: String, draft: Draft) = withStore { st ->
        val f = st.getFolder(draftsFolder); if (!f.exists()) f.create(Folder.HOLDS_MESSAGES)
        f.open(Folder.READ_WRITE)
        try { val m = MimeBuilder.build(Net.session("imap", account.imap), account, draft); m.setFlag(Flags.Flag.DRAFT, true); f.appendMessages(arrayOf(m)) } finally { f.close(false) }
    }

    private fun addrs(a: Array<out jakarta.mail.Address>?) = a.orEmpty().filterIsInstance<InternetAddress>().map { Addr(it.address, it.personal) }

    private fun toMail(f: IMAPFolder, m: MimeMessage): MailMessage {
        val text = StringBuilder(); var html: String? = null; val atts = mutableListOf<AttachmentData>()
        fun walk(p: Part) {
            val c = p.content
            when {
                p.isMimeType("multipart/*") -> { val mp = p.content as Multipart; for (i in 0 until mp.count) walk(mp.getBodyPart(i)) }
                p.isMimeType("message/rfc822") && c is Part -> walk(c)
                Part.ATTACHMENT.equals(p.disposition, true) || (p.fileName != null && !p.isMimeType("text/*")) ->
                    atts += AttachmentData(MimeUtility.decodeText(p.fileName ?: "attachment"), p.contentType.substringBefore(';').trim().lowercase(), p.inputStream.readBytes())
                p.isMimeType("text/plain") -> text.append(c as String)
                p.isMimeType("text/html") -> html = (html ?: "") + (c as String)
                else -> if (p.fileName != null) atts += AttachmentData(MimeUtility.decodeText(p.fileName), p.contentType.substringBefore(';').trim().lowercase(), p.inputStream.readBytes())
            }
        }
        walk(m)
        val refs = m.getHeader("References", " ")?.split(Regex("\\s+"))?.filter { it.startsWith("<") }.orEmpty()
        return MailMessage(account.id, f.fullName, f.getUID(m), m.messageID, m.getHeader("In-Reply-To", null)?.trim(), refs,
            addrs(m.from), if (m.getHeader("Reply-To") != null) addrs(m.replyTo) else emptyList(),
            addrs(m.getRecipients(jakarta.mail.Message.RecipientType.TO)), addrs(m.getRecipients(jakarta.mail.Message.RecipientType.CC)),
            m.subject.orEmpty(), (m.sentDate ?: m.receivedDate)?.time ?: 0L, text.toString().ifEmpty { null }, html, atts, m.flags.contains(Flags.Flag.SEEN))
    }
}
