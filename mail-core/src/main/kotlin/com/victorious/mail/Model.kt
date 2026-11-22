package com.victorious.mail

import java.security.cert.X509Certificate

/** Transport of a conversation. A reply always goes out on the same transport AND the same account/SIM/alias it arrived on. */
enum class Transport { SMS, EMAIL, MATRIX, TWILIO }

/**
 * Conversation identity: transport + account (SIM subscription / mail account / Matrix account / Twilio alias) + source thread
 * (SMS thread id / mail thread key / Matrix room id / Twilio conversation). Two people named Jordan have different keys; the same
 * Jordan over SMS and email has two keys and each keeps its own reply route. Nothing ever merges keys or switches transports.
 */
data class ConversationKey(val transport: Transport, val accountId: String, val sourceThread: String) {
    fun encode() = "${transport.name}|${accountId.replace("|", "%7C")}|${sourceThread.replace("|", "%7C")}"
    companion object {
        fun decode(s: String): ConversationKey {
            val p = s.split("|", limit = 3); require(p.size == 3) { "bad key" }
            return ConversationKey(Transport.valueOf(p[0]), p[1].replace("%7C", "|"), p[2].replace("%7C", "|"))
        }
    }
}

enum class Security { SSL_TLS, STARTTLS }   // plaintext is not offered

sealed class Auth {
    data class Password(val username: String, val password: String) : Auth()
    /** OAuth2 (XOAUTH2). [token] is fetched fresh per connection by the app's token store; refresh tokens never reach this module. */
    data class OAuth(val username: String, val token: () -> String) : Auth()
}

data class ServerConfig(val host: String, val port: Int, val security: Security, val auth: Auth,
                        /** SHA-256 of a server certificate the owner explicitly accepted (self-signed lab servers); null = system trust only. */
                        val pinnedCertSha256: String? = null)

data class MailAccount(val id: String, val address: String, val displayName: String, val imap: ServerConfig, val smtp: ServerConfig, val replyTo: String? = null)

data class Addr(val address: String, val name: String? = null) { override fun toString() = if (name.isNullOrBlank()) address else "$name <$address>" }

data class AttachmentData(val filename: String, val mime: String, val bytes: ByteArray) {
    override fun equals(other: Any?) = other is AttachmentData && filename == other.filename && mime == other.mime && bytes.contentEquals(other.bytes)
    override fun hashCode() = filename.hashCode() * 31 + bytes.contentHashCode()
}

data class MailMessage(
    val accountId: String, val folder: String, val uid: Long, val messageId: String?, val inReplyTo: String?, val references: List<String>,
    val from: List<Addr>, val replyTo: List<Addr>, val to: List<Addr>, val cc: List<Addr>, val subject: String, val dateMs: Long,
    val text: String?, val html: String?, val attachments: List<AttachmentData>, val seen: Boolean,
) { val threadKey: String get() = Threading.key(this) }

/** What the owner composed. [from] is the selected sending account's identity; Bcc is only ever put in the envelope. */
data class Draft(
    val id: String, val accountId: String, val to: List<Addr>, val cc: List<Addr> = emptyList(), val bcc: List<Addr> = emptyList(),
    val subject: String, val body: String, val attachments: List<AttachmentData> = emptyList(),
    val inReplyTo: String? = null, val references: List<String> = emptyList(),
    /** Fixed when first queued and reused on any explicit re-send, so a server/recipient can recognise a duplicate. */
    val messageId: String? = null,
)

/** Accepts exactly the certificate the owner pinned (by SHA-256) in addition to normal system validation. */
fun X509Certificate.sha256Hex(): String = java.security.MessageDigest.getInstance("SHA-256").digest(encoded).joinToString("") { "%02x".format(it) }
