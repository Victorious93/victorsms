package com.victorious.mail

import jakarta.mail.Message
import jakarta.mail.Session
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeBodyPart
import jakarta.mail.internet.MimeMessage
import jakarta.mail.internet.MimeMultipart
import jakarta.mail.util.ByteArrayDataSource
import java.util.Date
import java.util.UUID

object MimeBuilder {
    /** Header-injection-safe: addresses and subject are validated as single lines; Bcc goes only to the envelope (never a header). */
    fun build(session: Session, account: MailAccount, d: Draft): MimeMessage {
        fun ia(a: Addr) = InternetAddress(a.address, a.name, "UTF-8").also { require(!a.address.contains('\n') && !a.address.contains('\r')) { "bad address" } }
        require(!d.subject.contains('\n') && !d.subject.contains('\r')) { "subject must be one line" }
        require(d.to.isNotEmpty() || d.cc.isNotEmpty() || d.bcc.isNotEmpty()) { "no recipients" }
        val mid = d.messageId ?: ("<" + UUID.randomUUID() + "@" + account.address.substringAfter('@') + ">")
        val m = object : MimeMessage(session) { override fun updateMessageID() { setHeader("Message-ID", mid) } }
        m.setFrom(ia(Addr(account.address, account.displayName)))
        account.replyTo?.let { m.setReplyTo(arrayOf(InternetAddress(it))) }
        m.setRecipients(Message.RecipientType.TO, d.to.map(::ia).toTypedArray())
        if (d.cc.isNotEmpty()) m.setRecipients(Message.RecipientType.CC, d.cc.map(::ia).toTypedArray())
        if (d.bcc.isNotEmpty()) m.setRecipients(Message.RecipientType.BCC, d.bcc.map(::ia).toTypedArray())
        m.setSubject(d.subject, "UTF-8"); m.sentDate = Date()
        d.inReplyTo?.let { m.setHeader("In-Reply-To", it) }
        if (d.references.isNotEmpty()) m.setHeader("References", d.references.joinToString(" "))
        if (d.attachments.isEmpty()) m.setText(d.body, "UTF-8")
        else {
            val mp = MimeMultipart("mixed")
            mp.addBodyPart(MimeBodyPart().also { it.setText(d.body, "UTF-8") })
            d.attachments.forEach { a -> mp.addBodyPart(MimeBodyPart().also { it.dataHandler = jakarta.activation.DataHandler(ByteArrayDataSource(a.bytes, a.mime)); it.fileName = a.filename; it.disposition = "attachment" }) }
            m.setContent(mp)
        }
        m.saveChanges()
        return m
    }
}
