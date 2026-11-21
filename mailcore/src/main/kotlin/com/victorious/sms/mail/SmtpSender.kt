package com.victorious.sms.mail

import jakarta.mail.AuthenticationFailedException
import jakarta.mail.MessagingException
import jakarta.mail.SendFailedException
import org.eclipse.angus.mail.util.MailConnectException
import org.eclipse.angus.mail.smtp.SMTPAddressFailedException
import org.eclipse.angus.mail.smtp.SMTPSendFailedException
import org.eclipse.angus.mail.smtp.SMTPTransport
import java.io.IOException
import javax.net.ssl.SSLException

/** What the sender can honestly say. [Uncertain] means the server may have accepted the message. */
sealed interface SendOutcome {
    data object Accepted : SendOutcome
    /** Nothing was transmitted (cannot connect, TLS failure, bad credentials). Safe to retry. */
    data class NotSent(val reason: String) : SendOutcome
    /** The server answered with a permanent/temporary refusal of the message. It did not accept it. */
    data class Rejected(val reason: String) : SendOutcome
    /** The connection broke after the message may have been handed over. NEVER retried automatically. */
    data class Uncertain(val reason: String) : SendOutcome
}

class SmtpSender(
    private val secrets: SecretStore,
    private val tls: TlsPolicy = TlsPolicy(),
) {
    fun send(o: Outgoing): SendOutcome {
        val secret = secrets.get(o.account.id) ?: return SendOutcome.NotSent("no credential stored for this account")
        val session = Sessions.smtp(o.account, tls)
        val mime = try { MimeBuilder.build(o, session) } catch (e: IllegalArgumentException) { return SendOutcome.Rejected(e.message ?: "invalid message") }
        val t = session.getTransport(Sessions.proto(o.account.smtp.security, "smtp")) as SMTPTransport
        try {
            try {
                t.connect(o.account.smtp.host, o.account.smtp.port, o.account.username, secret)
            } catch (e: AuthenticationFailedException) {
                return SendOutcome.NotSent("sign-in refused: check the password or app password")
            } catch (e: MailConnectException) {
                return SendOutcome.NotSent("cannot reach ${o.account.smtp.host}:${o.account.smtp.port}")
            } catch (e: MessagingException) {
                return SendOutcome.NotSent(connectReason(e))
            }
            try {
                t.sendMessage(mime, mime.allRecipients)
                return SendOutcome.Accepted
            } catch (e: SMTPSendFailedException) {
                return SendOutcome.Rejected("server replied ${e.returnCode}: ${e.message?.lines()?.firstOrNull()}")
            } catch (e: SMTPAddressFailedException) {
                return SendOutcome.Rejected("recipient refused: ${e.address}")
            } catch (e: SendFailedException) {
                return SendOutcome.Rejected(e.message ?: "send failed")
            } catch (e: MessagingException) {
                return if (isTransportBreak(e)) SendOutcome.Uncertain("connection lost while sending; the server may have accepted the message")
                else SendOutcome.Rejected(e.message ?: "send failed")
            }
        } finally { runCatching { t.close() } }
    }

    private fun connectReason(e: Throwable): String {
        var c: Throwable? = e
        while (c != null) { if (c is SSLException) return "TLS check failed: ${c.message}"; c = c.cause }
        return e.message ?: "connection failed"
    }

    private fun isTransportBreak(e: Throwable): Boolean {
        var c: Throwable? = e
        while (c != null) { if (c is IOException) return true; c = c.cause }
        return false
    }
}
