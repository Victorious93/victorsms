package com.victorious.sms.mail

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.victorious.mail.*
import org.json.JSONObject
import java.util.UUID

/** Account configuration WITHOUT secrets. The password / OAuth token is in [MailSecrets] under the same id. */
data class StoredAccount(
    val id: String, val address: String, val displayName: String, val username: String,
    val imapHost: String, val imapPort: Int, val imapSecurity: Security, val smtpHost: String, val smtpPort: Int, val smtpSecurity: Security,
    val replyTo: String? = null, val imapPin: String? = null, val smtpPin: String? = null, val oauth: Boolean = false,
) {
    fun toJson() = JSONObject().put("id", id).put("address", address).put("name", displayName).put("user", username).put("ih", imapHost).put("ip", imapPort).put("is", imapSecurity.name)
        .put("sh", smtpHost).put("sp", smtpPort).put("ss", smtpSecurity.name).put("rt", replyTo ?: JSONObject.NULL).put("ipin", imapPin ?: JSONObject.NULL).put("spin", smtpPin ?: JSONObject.NULL).put("oauth", oauth)
    companion object {
        fun fromJson(o: JSONObject): StoredAccount { fun s(k: String) = o.optString(k).takeIf { it.isNotEmpty() && it != "null" }
            return StoredAccount(o.getString("id"), o.getString("address"), o.getString("name"), o.getString("user"), o.getString("ih"), o.getInt("ip"), Security.valueOf(o.getString("is")),
                o.getString("sh"), o.getInt("sp"), Security.valueOf(o.getString("ss")), s("rt"), s("ipin"), s("spin"), o.optBoolean("oauth")) }
    }
}

/** Passwords and OAuth tokens: AES-256-GCM EncryptedSharedPreferences, key in the Android Keystore. Never logged, never in SQLite, never in backups (allowBackup=false). */
class MailSecrets(context: Context) {
    private val prefs = EncryptedSharedPreferences.create(context.applicationContext, "mail_secrets", MasterKey.Builder(context.applicationContext).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV, EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
    fun put(accountId: String, secret: String) { prefs.edit().putString(accountId, secret).apply() }
    fun get(accountId: String): String? = prefs.getString(accountId, null)
    fun remove(accountId: String) { prefs.edit().remove(accountId).apply() }
}

/** OAuth2 needs this project's own client registration per provider. None is configured in this build: providers that require OAuth are not offered; manual IMAP/SMTP is. */
object OAuthRegistry { val configuredProviders: Set<String> = emptySet() }

class MailRepository private constructor(private val context: Context) {
    val db = MailDb(context)
    private val secrets = MailSecrets(context)
    val outbox = Outbox(db.SqlOutboxStore(), { id -> account(id) })

    fun storedAccounts() = db.accounts()
    fun account(id: String): MailAccount? = db.accounts().firstOrNull { it.id == id }?.let { toAccount(it) }

    private fun toAccount(s: StoredAccount): MailAccount? {
        val secret = secrets.get(s.id) ?: return null
        val auth: Auth = if (s.oauth) Auth.OAuth(s.username) { secret } else Auth.Password(s.username, secret)
        return MailAccount(s.id, s.address, s.displayName, ServerConfig(s.imapHost, s.imapPort, s.imapSecurity, auth, s.imapPin), ServerConfig(s.smtpHost, s.smtpPort, s.smtpSecurity, auth, s.smtpPin), s.replyTo)
    }

    fun addAccount(a: StoredAccount, secret: String): StoredAccount {
        val withId = if (a.id.isBlank()) a.copy(id = UUID.randomUUID().toString()) else a
        secrets.put(withId.id, secret); db.saveAccount(withId); return withId
    }

    fun removeAccount(id: String) { secrets.remove(id); db.deleteAccount(id) }

    /** Connect + list folders; returns null on success or a short reason (never contains the password). */
    fun test(a: StoredAccount, secret: String): String? = try {
        val auth = Auth.Password(a.username, secret)
        val acc = MailAccount(a.id, a.address, a.displayName, ServerConfig(a.imapHost, a.imapPort, a.imapSecurity, auth, a.imapPin), ServerConfig(a.smtpHost, a.smtpPort, a.smtpSecurity, auth, a.smtpPin), a.replyTo)
        ImapClient(acc).folders(); null
    } catch (e: Exception) { "${e.javaClass.simpleName}: ${e.message?.take(160)}" }

    /** Incremental sync of INBOX for every account. A failure in one account never blocks the others or the outbox. */
    fun syncAll(): Map<String, String?> = storedAccounts().associate { s ->
        s.id to try {
            val acc = toAccount(s) ?: return@associate s.id to "no stored password"
            val prev = db.syncState(s.id, "INBOX")
            val (state, msgs) = ImapClient(acc).sync("INBOX", prev)
            db.storeSync(state, msgs, resetFolder = prev != null && prev.uidValidity != state.uidValidity); null
        } catch (e: Exception) { "${e.javaClass.simpleName}: ${e.message?.take(120)}" }
    }

    fun flushOutbox() = outbox.flush()
    fun recoverOnStart() = outbox.recoverAfterRestart()

    companion object { @Volatile private var inst: MailRepository? = null
        fun get(c: Context) = inst ?: synchronized(this) { inst ?: MailRepository(c.applicationContext).also { inst = it; it.recoverOnStart() } } }
}
