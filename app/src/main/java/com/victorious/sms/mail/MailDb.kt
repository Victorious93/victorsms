package com.victorious.sms.mail

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.victorious.mail.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * VictorSMS mail storage (app-private SQLite). Separate from the system SMS provider and the Matrix store, so upgrading an existing
 * inbox never touches them. Schema is versioned: onUpgrade only ever ADDS; user data (accounts, cached mail, drafts, outbox, cursors) survives.
 * Attachment bytes live in files (SQLite cursor windows cap rows at ~2 MB); passwords/tokens are NOT here (see [MailSecrets]).
 */
class MailDb(private val context: Context) : SQLiteOpenHelper(context.applicationContext, "victor_mail.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE accounts(id TEXT PRIMARY KEY, json TEXT NOT NULL)")
        db.execSQL("""CREATE TABLE messages(account_id TEXT, folder TEXT, uid INTEGER, thread_key TEXT, date_ms INTEGER, seen INTEGER, json TEXT NOT NULL, PRIMARY KEY(account_id, folder, uid))""")
        db.execSQL("CREATE INDEX messages_thread ON messages(account_id, thread_key)")
        db.execSQL("CREATE TABLE sync(account_id TEXT, folder TEXT, uidvalidity INTEGER, lastuid INTEGER, PRIMARY KEY(account_id, folder))")
        db.execSQL("CREATE TABLE outbox(draft_id TEXT PRIMARY KEY, account_id TEXT, state TEXT, detail TEXT, attempts INTEGER, updated_ms INTEGER, json TEXT NOT NULL)")
        db.execSQL("CREATE TABLE drafts(draft_id TEXT PRIMARY KEY, account_id TEXT, updated_ms INTEGER, json TEXT NOT NULL)")
    }
    override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) { /* additive migrations only; v1 is the first schema */ }

    private fun attDir(kind: String, id: String) = File(context.filesDir, "mail/$kind/${id.replace(Regex("[^A-Za-z0-9._-]"), "_")}")

    // ---------------------------------------------------------------------------------------------- drafts (+ attachments as files)
    fun draftToJson(kind: String, d: Draft): JSONObject {
        val dir = attDir(kind, d.id).also { it.mkdirs() }
        val atts = JSONArray()
        d.attachments.forEachIndexed { i, a -> val f = File(dir, "$i.bin"); f.writeBytes(a.bytes); atts.put(JSONObject().put("name", a.filename).put("mime", a.mime).put("file", f.name)) }
        fun addrs(l: List<Addr>) = JSONArray(l.map { JSONObject().put("a", it.address).put("n", it.name ?: JSONObject.NULL) })
        return JSONObject().put("id", d.id).put("account", d.accountId).put("to", addrs(d.to)).put("cc", addrs(d.cc)).put("bcc", addrs(d.bcc))
            .put("subject", d.subject).put("body", d.body).put("irt", d.inReplyTo ?: JSONObject.NULL).put("refs", JSONArray(d.references))
            .put("mid", d.messageId ?: JSONObject.NULL).put("atts", atts)
    }

    fun draftFromJson(kind: String, o: JSONObject): Draft {
        fun addrs(a: JSONArray) = (0 until a.length()).map { a.getJSONObject(it).let { x -> Addr(x.getString("a"), x.optString("n").takeIf { n -> n.isNotEmpty() && n != "null" }) } }
        val dir = attDir(kind, o.getString("id")); val ja = o.getJSONArray("atts")
        val atts = (0 until ja.length()).mapNotNull { i -> ja.getJSONObject(i).let { x -> File(dir, x.getString("file")).takeIf { it.exists() }?.let { f -> AttachmentData(x.getString("name"), x.getString("mime"), f.readBytes()) } } }
        return Draft(o.getString("id"), o.getString("account"), addrs(o.getJSONArray("to")), addrs(o.getJSONArray("cc")), addrs(o.getJSONArray("bcc")), o.getString("subject"), o.getString("body"), atts,
            o.optString("irt").takeIf { it.isNotEmpty() && it != "null" }, o.getJSONArray("refs").let { r -> (0 until r.length()).map { r.getString(it) } }, o.optString("mid").takeIf { it.isNotEmpty() && it != "null" })
    }

    // ---------------------------------------------------------------------------------------------------------- outbox store (atomic writes)
    inner class SqlOutboxStore : OutboxStore {
        override fun put(item: OutboxItem) {
            val v = ContentValues().apply {
                put("draft_id", item.draft.id); put("account_id", item.draft.accountId); put("state", item.state.name); put("detail", item.detail); put("attempts", item.attempts)
                put("updated_ms", item.updatedMs); put("json", draftToJson("outbox", item.draft).toString())
            }
            writableDatabase.insertWithOnConflict("outbox", null, v, SQLiteDatabase.CONFLICT_REPLACE)      // one atomic statement: no half-written state
        }
        override fun get(draftId: String): OutboxItem? = readableDatabase.query("outbox", null, "draft_id=?", arrayOf(draftId), null, null, null).use { if (it.moveToFirst()) row(it) else null }
        override fun all(): List<OutboxItem> = readableDatabase.query("outbox", null, null, null, null, null, "updated_ms ASC").use { c -> buildList { while (c.moveToNext()) add(row(c)) } }
        private fun row(c: android.database.Cursor) = OutboxItem(draftFromJson("outbox", JSONObject(c.getString(c.getColumnIndexOrThrow("json")))), OutState.valueOf(c.getString(c.getColumnIndexOrThrow("state"))),
            c.getString(c.getColumnIndexOrThrow("detail")), c.getInt(c.getColumnIndexOrThrow("attempts")), c.getLong(c.getColumnIndexOrThrow("updated_ms")))
    }

    fun saveDraft(d: Draft) { writableDatabase.insertWithOnConflict("drafts", null, ContentValues().apply { put("draft_id", d.id); put("account_id", d.accountId); put("updated_ms", System.currentTimeMillis()); put("json", draftToJson("drafts", d).toString()) }, SQLiteDatabase.CONFLICT_REPLACE) }
    fun drafts(): List<Draft> = readableDatabase.query("drafts", null, null, null, null, null, "updated_ms DESC").use { c -> buildList { while (c.moveToNext()) add(draftFromJson("drafts", JSONObject(c.getString(c.getColumnIndexOrThrow("json"))))) } }
    fun deleteDraft(id: String) { writableDatabase.delete("drafts", "draft_id=?", arrayOf(id)); attDir("drafts", id).deleteRecursively() }

    // ---------------------------------------------------------------------------------------------------------------------- accounts
    fun saveAccount(a: StoredAccount) { writableDatabase.insertWithOnConflict("accounts", null, ContentValues().apply { put("id", a.id); put("json", a.toJson().toString()) }, SQLiteDatabase.CONFLICT_REPLACE) }
    fun accounts(): List<StoredAccount> = readableDatabase.query("accounts", null, null, null, null, null, "id").use { c -> buildList { while (c.moveToNext()) runCatching { add(StoredAccount.fromJson(JSONObject(c.getString(c.getColumnIndexOrThrow("json"))))) } } }
    fun deleteAccount(id: String) { writableDatabase.delete("accounts", "id=?", arrayOf(id)); writableDatabase.delete("messages", "account_id=?", arrayOf(id)); writableDatabase.delete("sync", "account_id=?", arrayOf(id)) }

    // ------------------------------------------------------------------------------------------------------------ cached messages + cursors
    fun syncState(accountId: String, folder: String): SyncState? = readableDatabase.query("sync", null, "account_id=? AND folder=?", arrayOf(accountId, folder), null, null, null).use {
        if (it.moveToFirst()) SyncState(accountId, folder, it.getLong(it.getColumnIndexOrThrow("uidvalidity")), it.getLong(it.getColumnIndexOrThrow("lastuid"))) else null }

    /** Messages + cursor are written in ONE transaction: a crash never leaves a cursor ahead of its messages. */
    fun storeSync(state: SyncState, msgs: List<MailMessage>, resetFolder: Boolean) {
        val db = writableDatabase; db.beginTransaction()
        try {
            if (resetFolder) db.delete("messages", "account_id=? AND folder=?", arrayOf(state.accountId, state.folder))
            msgs.forEach { m -> db.insertWithOnConflict("messages", null, ContentValues().apply {
                put("account_id", m.accountId); put("folder", m.folder); put("uid", m.uid); put("thread_key", m.threadKey); put("date_ms", m.dateMs); put("seen", if (m.seen) 1 else 0); put("json", msgToJson(m).toString()) }, SQLiteDatabase.CONFLICT_REPLACE) }
            db.insertWithOnConflict("sync", null, ContentValues().apply { put("account_id", state.accountId); put("folder", state.folder); put("uidvalidity", state.uidValidity); put("lastuid", state.lastUid) }, SQLiteDatabase.CONFLICT_REPLACE)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun messages(accountId: String? = null, threadKey: String? = null): List<MailMessage> {
        val sel = listOfNotNull(accountId?.let { "account_id=?" }, threadKey?.let { "thread_key=?" }).joinToString(" AND ").ifEmpty { null }
        val args = listOfNotNull(accountId, threadKey).toTypedArray().ifEmpty { null }
        return readableDatabase.query("messages", null, sel, args, null, null, "date_ms ASC").use { c -> buildList { while (c.moveToNext()) runCatching { add(msgFromJson(JSONObject(c.getString(c.getColumnIndexOrThrow("json"))), c.getInt(c.getColumnIndexOrThrow("seen")) == 1)) } } }
    }

    fun markSeen(accountId: String, folder: String, uid: Long) { writableDatabase.execSQL("UPDATE messages SET seen=1 WHERE account_id=? AND folder=? AND uid=?", arrayOf<Any?>(accountId, folder, uid)) }

    private fun msgToJson(m: MailMessage): JSONObject {
        fun addrs(l: List<Addr>) = JSONArray(l.map { JSONObject().put("a", it.address).put("n", it.name ?: JSONObject.NULL) })
        // attachment bytes are not cached in SQLite: re-fetched on demand
        return JSONObject().put("mid", m.messageId ?: JSONObject.NULL).put("irt", m.inReplyTo ?: JSONObject.NULL).put("refs", JSONArray(m.references)).put("from", addrs(m.from)).put("rt", addrs(m.replyTo))
            .put("to", addrs(m.to)).put("cc", addrs(m.cc)).put("subject", m.subject).put("text", m.text ?: JSONObject.NULL).put("html", m.html ?: JSONObject.NULL)
            .put("atts", JSONArray(m.attachments.map { JSONObject().put("name", it.filename).put("mime", it.mime).put("size", it.bytes.size) })).put("date", m.dateMs).put("account", m.accountId).put("folder", m.folder).put("uid", m.uid)
    }

    private fun msgFromJson(o: JSONObject, seen: Boolean): MailMessage {
        fun addrs(a: JSONArray) = (0 until a.length()).map { a.getJSONObject(it).let { x -> Addr(x.getString("a"), x.optString("n").takeIf { n -> n.isNotEmpty() && n != "null" }) } }
        fun s(k: String) = o.optString(k).takeIf { it.isNotEmpty() && it != "null" }
        return MailMessage(o.getString("account"), o.getString("folder"), o.getLong("uid"), s("mid"), s("irt"), o.getJSONArray("refs").let { r -> (0 until r.length()).map { r.getString(it) } },
            addrs(o.getJSONArray("from")), addrs(o.getJSONArray("rt")), addrs(o.getJSONArray("to")), addrs(o.getJSONArray("cc")), o.getString("subject"), o.getLong("date"), s("text"), s("html"), emptyList(), seen)
    }
}
