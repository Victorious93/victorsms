package com.victorious.sms.mail

enum class Security { SSL_TLS, STARTTLS }

/** Plain-text connections do not exist in this model. */
data class ServerConfig(val host: String, val port: Int, val security: Security) {
    init { require(host.isNotBlank() && port in 1..65535) { "invalid server" } }
}

sealed interface Auth {
    /** Password lives in a [SecretStore], never in this object. */
    data object Password : Auth
    /** XOAUTH2 with a token from the app's own OAuth registration (provider-specific, see docs/MAIL.md). */
    data object OAuth : Auth
}

data class MailAccount(
    val id: String,
    val address: String,
    val displayName: String,
    val username: String,
    val imap: ServerConfig,
    val smtp: ServerConfig,
    val auth: Auth = Auth.Password,
)

/** Secrets (passwords, OAuth refresh/access tokens). Android: Keystore-backed; JVM tests: in memory. */
interface SecretStore {
    fun get(accountId: String): String?
    fun put(accountId: String, secret: String)
    fun remove(accountId: String)
}

class MemorySecretStore : SecretStore {
    private val m = HashMap<String, String>()
    override fun get(accountId: String) = m[accountId]
    override fun put(accountId: String, secret: String) { m[accountId] = secret }
    override fun remove(accountId: String) { m.remove(accountId) }
}

data class Attachment(val name: String, val mimeType: String, val bytes: ByteArray) {
    override fun equals(other: Any?) = other is Attachment && name == other.name && mimeType == other.mimeType && bytes.contentEquals(other.bytes)
    override fun hashCode() = name.hashCode() * 31 + bytes.contentHashCode()
}

data class MailMessage(
    val messageId: String,
    val inReplyTo: String?,
    val references: List<String>,
    val from: String,
    val replyTo: List<String>,
    val to: List<String>,
    val cc: List<String>,
    val subject: String,
    val dateMs: Long,
    val textBody: String,
    val htmlBody: String?,
    val attachments: List<Attachment>,
    val seen: Boolean,
    val folder: String = "INBOX",
    val uid: Long = 0,
) {
    /** Thread root: first References entry, else In-Reply-To, else this message. Stable across replies. */
    val threadRoot: String get() = references.firstOrNull() ?: inReplyTo ?: messageId
}

data class Outgoing(
    val account: MailAccount,
    val to: List<String>,
    val cc: List<String> = emptyList(),
    val bcc: List<String> = emptyList(),
    val subject: String,
    val text: String,
    val replyTo: String? = null,
    val inReplyTo: String? = null,
    val references: List<String> = emptyList(),
    val attachments: List<Attachment> = emptyList(),
    val messageId: String = newMessageId(account.address),
)

fun newMessageId(address: String): String =
    "<${java.util.UUID.randomUUID()}@${address.substringAfter('@', "localhost")}>"
