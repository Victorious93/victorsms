package com.victorious.sms.mail

import android.content.Context
import com.victorious.sms.bridge.EncryptedPendingStore
import com.victorious.sms.bridge.MemoryPendingStore
import com.victorious.sms.bridge.PendingStore
import com.victorious.sms.inbox.ConversationKey
import com.victorious.sms.inbox.DraftHandoff
import com.victorious.sms.inbox.InboxRow
import com.victorious.sms.inbox.Transport
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Adapts the app's encrypted [PendingStore] to the mail core's [RecordStore]. */
class EncryptedRecordStore(private val p: PendingStore) : RecordStore {
    override fun all(): Map<String, String> = p.loadAll()
    override fun put(id: String, json: String) = p.put(id, json)
    override fun remove(id: String) = p.remove(id)
}

/** Passwords / OAuth tokens in the Android Keystore-backed encrypted preferences. Never logged, never sent to the PC. */
class EncryptedSecrets(private val p: PendingStore) : SecretStore {
    override fun get(accountId: String): String? = p.loadAll()[accountId]
    override fun put(accountId: String, secret: String) = p.put(accountId, secret)
    override fun remove(accountId: String) = p.remove(accountId)
}

/**
 * Everything the mail experience needs, owned by VictorSMS alone: accounts, secrets, sync positions, a local
 * message cache, drafts, the durable outbox and the desktop-draft review state. Email-only use needs nothing else:
 * no default-SMS role, no Matrix login, no VictorSuite, no PC.
 */
class MailRuntime private constructor(private val ctx: Context) {
    private fun enc(name: String): PendingStore =
        runCatching<PendingStore> { EncryptedPendingStore(ctx, name) }.getOrElse { MemoryPendingStore() }

    private val accountStore = enc("mail_accounts")
    val secrets: SecretStore = EncryptedSecrets(enc("mail_secrets"))
    private val syncStore = enc("mail_sync")
    private val cacheDir = File(ctx.noBackupFilesDir, "mail-cache")      // excluded from backup; protected by file-based encryption
    private val cache = FileRecordStore(cacheDir)

    fun accounts(): List<MailAccount> = accountStore.loadAll().values.mapNotNull(::decodeAccount).sortedBy { it.address }
    fun account(id: String): MailAccount? = accountStore.loadAll()[id]?.let(::decodeAccount)

    fun saveAccount(a: MailAccount, secret: String?) {
        accountStore.put(a.id, encodeAccount(a))
        if (secret != null) secrets.put(a.id, secret)
    }

    fun removeAccount(id: String) {
        accountStore.remove(id); secrets.remove(id)
        syncStore.loadAll().keys.filter { it.startsWith("$id|") }.forEach(syncStore::remove)
        cache.all().keys.filter { it.startsWith("$id~") }.forEach(cache::remove)
    }

    val drafts = DraftStore(FileRecordStore(File(ctx.noBackupFilesDir, "mail-drafts")), ::account)
    val sender = SmtpSender(secrets)
    val outbox = Outbox(FileRecordStore(File(ctx.noBackupFilesDir, "mail-outbox")), ::account, sender::send)
    fun imap(a: MailAccount) = ImapClient(a, secrets)

    /** Pull new mail for one account/folder into the local cache. Returns the number of new messages. */
    fun sync(a: MailAccount, folder: String = "INBOX"): Int {
        val key = "${a.id}|$folder"
        val st = syncStore.loadAll()[key]?.split(",")?.let { SyncState(it[0].toLong(), it[1].toLong()) } ?: SyncState()
        val r = imap(a).sync(folder, st)
        if (r.fullResync) cache.all().keys.filter { it.startsWith("${a.id}~$folder~") }.forEach(cache::remove)
        r.messages.forEach { cache.put("${a.id}~$folder~${it.uid}", encodeMessage(it)) }
        syncStore.put(key, "${r.state.uidValidity},${r.state.lastUid}")
        return r.messages.size
    }

    fun cached(a: MailAccount, folder: String = "INBOX"): List<MailMessage> =
        cache.all().filterKeys { it.startsWith("${a.id}~$folder~") }.values.mapNotNull(::decodeMessage).sortedBy { it.dateMs }

    fun markRead(a: MailAccount, m: MailMessage) {
        imap(a).markRead(m.folder, m.uid)
        cache.put("${a.id}~${m.folder}~${m.uid}", encodeMessage(m.copy(seen = true)))
    }

    /** One inbox row per (account, thread root). The key carries transport + mailbox + thread; the title never does. */
    fun inboxRows(): List<InboxRow> = accounts().flatMap { a ->
        cached(a).groupBy { it.threadRoot }.map { (root, msgs) ->
            val last = msgs.maxBy { it.dateMs }
            val other = msgs.map { it.from }.firstOrNull { !it.equals(a.address, true) } ?: last.from
            InboxRow(ConversationKey(Transport.EMAIL, a.address, root), other.substringBefore('@'), other, a.address,
                last.subject.ifBlank { last.textBody.take(80) }, last.dateMs, msgs.count { !it.seen })
        }
    }

    /** The draft-review state shared with the desktop hand-off. Bodies are dropped from storage once resolved. */
    val handoff: DraftHandoff by lazy {
        DraftHandoff(EncryptedRecordStore(enc("draft_handoff")), submit = { d, identity -> submitDraft(d, identity) })
    }
    val draftOwners: PendingStore by lazy { enc("draft_owners") }

    private fun submitDraft(d: com.victorious.sms.inbox.IncomingDraft, identity: String): SendOutcome =
        if (d.kind == "sms") {
            if (com.victorious.sms.sms.SmsRepository(ctx).send(d.to.first(), d.body)) SendOutcome.Accepted else SendOutcome.NotSent("SMS could not be handed to the radio (permission or SIM)")
        } else {
            val a = accounts().firstOrNull { it.address.equals(identity, true) || it.id == identity }
                ?: return SendOutcome.NotSent("no mail account named $identity on this phone")
            val item = outbox.enqueue(Outgoing(a, d.to, d.cc, d.bcc, d.subject, d.body))
            outbox.processQueued()
            when (outbox.get(item.id)?.state) {
                OutboxState.SENT -> SendOutcome.Accepted
                OutboxState.UNCERTAIN -> SendOutcome.Uncertain(outbox.get(item.id)?.reason ?: "uncertain")
                else -> SendOutcome.NotSent(outbox.get(item.id)?.reason ?: "not sent")
            }
        }

    // ---- encoding -------------------------------------------------------------------------------
    private fun encodeAccount(a: MailAccount) = JSONObject().put("id", a.id).put("address", a.address).put("name", a.displayName).put("user", a.username)
        .put("imap", server(a.imap)).put("smtp", server(a.smtp)).put("auth", if (a.auth == Auth.OAuth) "oauth" else "password").toString()

    private fun server(s: ServerConfig) = JSONObject().put("host", s.host).put("port", s.port).put("sec", s.security.name)
    private fun parseServer(o: JSONObject) = ServerConfig(o.getString("host"), o.getInt("port"), Security.valueOf(o.getString("sec")))

    private fun decodeAccount(json: String): MailAccount? = runCatching {
        val o = JSONObject(json)
        MailAccount(o.getString("id"), o.getString("address"), o.getString("name"), o.getString("user"), parseServer(o.getJSONObject("imap")),
            parseServer(o.getJSONObject("smtp")), if (o.optString("auth") == "oauth") Auth.OAuth else Auth.Password)
    }.getOrNull()

    private fun encodeMessage(m: MailMessage) = JSONObject().put("id", m.messageId).put("irt", m.inReplyTo ?: JSONObject.NULL).put("refs", JSONArray(m.references))
        .put("from", m.from).put("replyTo", JSONArray(m.replyTo)).put("to", JSONArray(m.to)).put("cc", JSONArray(m.cc)).put("subject", m.subject)
        .put("date", m.dateMs).put("text", m.textBody).put("html", m.htmlBody ?: JSONObject.NULL).put("seen", m.seen).put("folder", m.folder).put("uid", m.uid)
        .put("atts", JSONArray(m.attachments.map { it.name })).toString()

    private fun decodeMessage(json: String): MailMessage? = runCatching {
        val o = JSONObject(json)
        fun arr(k: String) = o.getJSONArray(k).let { a -> (0 until a.length()).map { a.getString(it) } }
        MailMessage(o.getString("id"), o.optString("irt", "").ifEmpty { null }, arr("refs"), o.getString("from"), arr("replyTo"), arr("to"), arr("cc"),
            o.getString("subject"), o.getLong("date"), o.getString("text"), o.optString("html", "").ifEmpty { null }, emptyList(), o.getBoolean("seen"),
            o.getString("folder"), o.getLong("uid"))
    }.getOrNull()

    companion object {
        @Volatile private var inst: MailRuntime? = null
        fun get(context: Context): MailRuntime = inst ?: synchronized(this) { inst ?: MailRuntime(context.applicationContext).also { inst = it } }
    }
}
