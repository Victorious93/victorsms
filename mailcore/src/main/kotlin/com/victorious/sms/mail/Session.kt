package com.victorious.sms.mail

import jakarta.mail.Session
import java.util.Properties
import javax.net.ssl.SSLSocketFactory

/**
 * TLS policy. Production uses the platform trust store and checks the server identity. A custom
 * [socketFactory] exists so tests (and a user who deliberately pins a private CA) can supply their own trust;
 * there is no "trust everything" switch in production code.
 */
data class TlsPolicy(val socketFactory: SSLSocketFactory? = null)

internal object Sessions {
    fun imap(a: MailAccount, tls: TlsPolicy, secret: String): Session {
        val p = Properties()
        val proto = if (a.imap.security == Security.SSL_TLS) "imaps" else "imap"
        p["mail.store.protocol"] = proto
        p["mail.$proto.host"] = a.imap.host
        p["mail.$proto.port"] = a.imap.port.toString()
        p["mail.$proto.connectiontimeout"] = "15000"
        p["mail.$proto.timeout"] = "30000"
        p["mail.$proto.ssl.checkserveridentity"] = "true"
        p["mail.$proto.ssl.protocols"] = "TLSv1.2 TLSv1.3"
        if (a.imap.security == Security.SSL_TLS) p["mail.$proto.ssl.enable"] = "true"
        else { p["mail.$proto.starttls.enable"] = "true"; p["mail.$proto.starttls.required"] = "true" }
        tls.socketFactory?.let { p["mail.$proto.ssl.socketFactory"] = it }
        if (a.auth == Auth.OAuth) { p["mail.$proto.auth.mechanisms"] = "XOAUTH2"; p["mail.$proto.auth.xoauth2.disable"] = "false" }
        return Session.getInstance(p)
    }

    fun smtp(a: MailAccount, tls: TlsPolicy): Session {
        val p = Properties()
        val proto = if (a.smtp.security == Security.SSL_TLS) "smtps" else "smtp"
        p["mail.transport.protocol"] = proto
        p["mail.$proto.host"] = a.smtp.host
        p["mail.$proto.port"] = a.smtp.port.toString()
        p["mail.$proto.connectiontimeout"] = "15000"
        p["mail.$proto.timeout"] = "30000"
        p["mail.$proto.writetimeout"] = "30000"
        p["mail.$proto.auth"] = "true"                    // authenticated submission only
        p["mail.$proto.ssl.checkserveridentity"] = "true"
        p["mail.$proto.ssl.protocols"] = "TLSv1.2 TLSv1.3"
        if (a.smtp.security == Security.SSL_TLS) p["mail.$proto.ssl.enable"] = "true"
        else { p["mail.$proto.starttls.enable"] = "true"; p["mail.$proto.starttls.required"] = "true" }
        tls.socketFactory?.let { p["mail.$proto.ssl.socketFactory"] = it }
        if (a.auth == Auth.OAuth) p["mail.$proto.auth.mechanisms"] = "XOAUTH2"
        return Session.getInstance(p)
    }

    fun proto(sec: Security, plain: String) = if (sec == Security.SSL_TLS) plain + "s" else plain
}
