package com.victorious.mail

import jakarta.mail.MessagingException
import org.eclipse.angus.mail.smtp.SMTPAddressFailedException
import org.eclipse.angus.mail.smtp.SMTPSendFailedException
import org.eclipse.angus.mail.smtp.SMTPTransport

/**
 * Outcome of one submission attempt.
 *  ACCEPTED  – the server answered 250 to end-of-data: it took responsibility for the message.
 *  REJECTED  – the server answered with an error before/at the end of data: definitively NOT accepted.
 *  UNCERTAIN – connection lost / timed out after the message may have been transmitted: it MAY have been accepted. Never auto-retried.
 *  NOT_ATTEMPTED – failed before any message data was sent (connect/TLS/auth): safe to retry.
 */
sealed class SendOutcome {
    object Accepted : SendOutcome()
    data class Rejected(val reason: String) : SendOutcome()
    data class Uncertain(val reason: String) : SendOutcome()
    data class NotAttempted(val reason: String) : SendOutcome()
}

open class SmtpSender {
    open fun send(account: MailAccount, draft: Draft): SendOutcome {
        val session = Net.session("smtp", account.smtp)
        val msg = MimeBuilder.build(session, account, draft)
        val t = session.getTransport(if (account.smtp.security == Security.SSL_TLS) "smtps" else "smtp") as SMTPTransport
        try {
            t.connect(account.smtp.host, account.smtp.port, Net.user(account.smtp.auth), Net.secret(account.smtp.auth))
        } catch (e: MessagingException) { return SendOutcome.NotAttempted("${e.javaClass.simpleName}: ${e.message}") }
        catch (e: java.io.IOException) { return SendOutcome.NotAttempted(e.message ?: "I/O") }
        return try {
            t.sendMessage(msg, msg.allRecipients)
            SendOutcome.Accepted
        } catch (e: SMTPSendFailedException) {
            // The server spoke: 5xx/4xx. A reply code means a definitive answer (not "lost").
            SendOutcome.Rejected("${e.returnCode} ${e.message}")
        } catch (e: SMTPAddressFailedException) { SendOutcome.Rejected("${e.returnCode} ${e.address}")
        } catch (e: MessagingException) { SendOutcome.Uncertain("connection problem while sending: ${e.javaClass.simpleName}")
        } catch (e: java.io.IOException) { SendOutcome.Uncertain("connection problem while sending: ${e.javaClass.simpleName}")
        } finally { try { t.close() } catch (_: Exception) { } }
    }
}
