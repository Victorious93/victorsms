package com.victorious.sms.mail

import jakarta.mail.Message
import jakarta.mail.Session
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeBodyPart
import jakarta.mail.internet.MimeMessage
import jakarta.mail.internet.MimeMultipart
import jakarta.mail.util.ByteArrayDataSource
import java.util.Date
import java.util.Properties

object MimeBuilder {
    private val plain: Session = Session.getInstance(Properties())

    /** Header-injection guard: no CR/LF in anything that becomes a header. */
    private fun clean(s: String): String { require('\r' !in s && '\n' !in s) { "line break in header value" }; return s }

    fun build(o: Outgoing, session: Session = plain): MimeMessage {
        require(o.to.isNotEmpty() || o.cc.isNotEmpty() || o.bcc.isNotEmpty()) { "no recipients" }
        val m = object : MimeMessage(session) { override fun updateMessageID() { setHeader("Message-ID", o.messageId) } }
        m.setFrom(InternetAddress(clean(o.account.address), clean(o.account.displayName), "UTF-8"))
        o.to.forEach { m.addRecipient(Message.RecipientType.TO, InternetAddress(clean(it), true)) }
        o.cc.forEach { m.addRecipient(Message.RecipientType.CC, InternetAddress(clean(it), true)) }
        o.bcc.forEach { m.addRecipient(Message.RecipientType.BCC, InternetAddress(clean(it), true)) }
        o.replyTo?.let { m.replyTo = arrayOf(InternetAddress(clean(it), true)) }
        m.setSubject(clean(o.subject), "UTF-8")
        m.sentDate = Date()
        o.inReplyTo?.let { m.setHeader("In-Reply-To", clean(it)) }
        if (o.references.isNotEmpty()) m.setHeader("References", clean(o.references.joinToString(" ")))
        if (o.attachments.isEmpty()) {
            m.setText(o.text, "UTF-8")
        } else {
            val mp = MimeMultipart("mixed")
            mp.addBodyPart(MimeBodyPart().apply { setText(o.text, "UTF-8") })
            for (a in o.attachments) mp.addBodyPart(MimeBodyPart().apply {
                dataHandler = jakarta.activation.DataHandler(ByteArrayDataSource(a.bytes, a.mimeType))
                fileName = clean(a.name); disposition = "attachment"
            })
            m.setContent(mp)
        }
        m.saveChanges()
        return m
    }

    /** Reply / reply-all honoring Reply-To and keeping threading headers. Own addresses are never recipients. */
    fun reply(original: MailMessage, account: MailAccount, text: String, all: Boolean): Outgoing {
        val me = account.address.lowercase()
        val primary = original.replyTo.ifEmpty { listOf(original.from) }
        val to = primary.filter { it.lowercase() != me }.distinctBy { it.lowercase() }
        val cc = if (all) (original.to + original.cc).filter { it.lowercase() != me && it.lowercase() !in to.map { t -> t.lowercase() } }.distinctBy { it.lowercase() } else emptyList()
        val subject = if (original.subject.startsWith("Re:", true)) original.subject else "Re: ${original.subject}"
        val quoted = text + "\n\nOn ${Date(original.dateMs)}, ${original.from} wrote:\n" + original.textBody.lines().joinToString("\n") { "> $it" }
        return Outgoing(account, to, cc, emptyList(), subject, quoted, null, original.messageId, original.references + original.messageId)
    }

    fun forward(original: MailMessage, account: MailAccount, to: List<String>, note: String): Outgoing {
        val subject = if (original.subject.startsWith("Fwd:", true)) original.subject else "Fwd: ${original.subject}"
        val body = note + "\n\n---------- Forwarded message ----------\nFrom: ${original.from}\nSubject: ${original.subject}\n\n" + original.textBody
        return Outgoing(account, to, emptyList(), emptyList(), subject, body, attachments = original.attachments)
    }
}
