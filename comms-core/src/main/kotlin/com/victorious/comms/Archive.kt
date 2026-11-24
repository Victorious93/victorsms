package com.victorious.comms

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.security.SecureRandom
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

data class ConversationSummary(
    val id: Long, val uid: String, val title: String, val provider: String, val account: String,
    val isGroup: Boolean, val messageCount: Long, val lastTs: Long?, val updated: Long,
)

data class StoredMessage(
    val id: Long, val conversation: Long, val direction: Direction, val sender: String, val body: String,
    val ts: Long, val origin: String, val author: String, val providerId: String?,
)

/** Content-addressed storage for the untouched original export files. */
interface OriginalStore {
    fun put(sha256: String, bytes: ByteArray)
    fun get(sha256: String): ByteArray?
    fun delete(sha256: String)
}

class MemoryOriginalStore : OriginalStore {
    private val map = HashMap<String, ByteArray>()

    @Synchronized override fun put(sha256: String, bytes: ByteArray) { map[sha256] = bytes }

    @Synchronized override fun get(sha256: String) = map[sha256]

    @Synchronized override fun delete(sha256: String) { map.remove(sha256) }

    @Synchronized fun size() = map.size
}

class DirOriginalStore(private val dir: File) : OriginalStore {
    init { dir.mkdirs() }

    private fun file(sha: String): File {
        require(sha.matches(Regex("[0-9a-f]{64}"))) { "bad digest" }
        return File(dir, sha)
    }

    override fun put(sha256: String, bytes: ByteArray) { val f = file(sha256); if (!f.exists()) f.writeBytes(bytes) }

    override fun get(sha256: String): ByteArray? = file(sha256).takeIf { it.exists() }?.readBytes()

    override fun delete(sha256: String) { file(sha256).delete() }
}

enum class Resolution { KEEP_EXISTING, TAKE_INCOMING, KEEP_BOTH }

/** Persistent conversation archive. VictorSMS is the authoritative owner of communication history. */
class Archive(
    internal val db: SqlDb,
    internal val originals: OriginalStore = MemoryOriginalStore(),
    internal val clock: () -> Long = System::currentTimeMillis,
) {
    init { Schema.migrate(db) }

    // ---- conversations ----

    fun conversations(): List<ConversationSummary> = db.rows(
        """SELECT c.*, (SELECT COUNT(*) FROM messages m WHERE m.conversation=c.id) AS n,
                  (SELECT MAX(ts) FROM messages m WHERE m.conversation=c.id) AS last_ts
           FROM conversations c ORDER BY c.updated DESC, c.id DESC""",
    ).map(::summary)

    fun conversation(id: Long): ConversationSummary? = db.one(
        """SELECT c.*, (SELECT COUNT(*) FROM messages m WHERE m.conversation=c.id) AS n,
                  (SELECT MAX(ts) FROM messages m WHERE m.conversation=c.id) AS last_ts
           FROM conversations c WHERE c.id=?""", id,
    )?.let(::summary)

    private fun summary(r: Row) = ConversationSummary(
        r.long("id"), r.str("uid"), r.str("title"), r.str("provider"), r.str("account"), r.long("is_group") == 1L,
        r.long("n"), r["last_ts"]?.toLongOrNull(), r.long("updated"),
    )

    fun participants(conversation: Long): List<Row> =
        db.rows("SELECT * FROM participants WHERE conversation=? ORDER BY id", conversation)

    internal fun createConversation(provider: String, account: String, matchKey: String, title: String, isGroup: Boolean): Long {
        val now = clock()
        return db.insert(
            "INSERT INTO conversations(uid,title,provider,account,match_key,is_group,created,updated) VALUES(?,?,?,?,?,?,?,?)",
            newUid(), title, provider, account, matchKey, isGroup, now, now,
        )
    }

    internal fun findByMatchKey(key: String): Long? =
        db.one("SELECT id FROM conversations WHERE match_key=? ORDER BY updated DESC, id DESC LIMIT 1", key)?.long("id")

    internal fun upsertParticipant(conversation: Long, key: String, display: String, phone: String?, isSelf: Boolean): Long {
        val existing = db.one("SELECT id, display, is_self FROM participants WHERE conversation=? AND key=?", conversation, key)
        if (existing != null) {
            if (isSelf && existing.long("is_self") == 0L) db.update("UPDATE participants SET is_self=1 WHERE id=?", existing.long("id"))
            if (existing.str("display").isEmpty() && display.isNotEmpty()) db.update("UPDATE participants SET display=? WHERE id=?", display, existing.long("id"))
            return existing.long("id")
        }
        return db.insert("INSERT INTO participants(conversation,key,display,phone,is_self) VALUES(?,?,?,?,?)", conversation, key, display, phone, isSelf)
    }

    fun mergeParticipants(conversation: Long, keepKey: String, dropKey: String) = db.tx {
        val keep = db.one("SELECT id FROM participants WHERE conversation=? AND key=?", conversation, keepKey)?.long("id")
        val drop = db.one("SELECT id FROM participants WHERE conversation=? AND key=?", conversation, dropKey)?.long("id")
        require(keep != null && drop != null && keep != drop) { "Both participants must exist in this conversation." }
        db.update("UPDATE messages SET sender=? WHERE sender=?", keep, drop)
        db.update("DELETE FROM participants WHERE id=?", drop)
    }

    fun renameConversation(id: Long, title: String) { db.update("UPDATE conversations SET title=?, updated=? WHERE id=?", title, clock(), id) }

    // ---- messages ----

    private val msgSelect = """SELECT m.id, m.conversation, m.direction, m.body, m.ts, m.origin, m.author, m.provider_id,
        COALESCE(p.display,'') AS sender_name FROM messages m LEFT JOIN participants p ON p.id=m.sender"""

    private fun stored(r: Row) = StoredMessage(
        r.long("id"), r.long("conversation"), Direction.fromDb(r.str("direction")), r.str("sender_name"),
        r.str("body"), r.long("ts"), r.str("origin"), r.str("author"), r["provider_id"],
    )

    /** Oldest-first page ending at [beforeTs] (exclusive) when given; most recent [limit] otherwise. */
    fun messages(conversation: Long, limit: Int = 200, beforeTs: Long? = null): List<StoredMessage> {
        val rows = if (beforeTs == null) db.rows("$msgSelect WHERE m.conversation=? ORDER BY m.ts DESC, m.id DESC LIMIT ?", conversation, limit)
        else db.rows("$msgSelect WHERE m.conversation=? AND m.ts<? ORDER BY m.ts DESC, m.id DESC LIMIT ?", conversation, beforeTs, limit)
        return rows.map(::stored).reversed()
    }

    fun message(id: Long): StoredMessage? = db.one("$msgSelect WHERE m.id=?", id)?.let(::stored)

    fun messageCount(conversation: Long): Long = db.count("SELECT COUNT(*) n FROM messages WHERE conversation=?", conversation)

    /**
     * The owner's own words, for writing-style analysis. Incoming messages never appear. Bot text appears only
     * if it was edited by the owner ([Author.BOT_EDITED]); unapproved or auto-sent bot text never reinforces a style.
     */
    fun outgoingForStyle(
        conversations: Collection<Long>? = null,
        limit: Int = 2000,
        authors: Set<Author> = setOf(Author.OWNER, Author.BOT_EDITED),
    ): List<StoredMessage> {
        val a = authors.joinToString(",") { "'${it.db}'" }
        val scope = conversations?.takeIf { it.isNotEmpty() }?.joinToString(",") { it.toLong().toString() }
        val sql = "$msgSelect WHERE m.direction='out' AND m.author IN ($a)" + (if (scope != null) " AND m.conversation IN ($scope)" else "") +
            " ORDER BY m.ts DESC, m.id DESC LIMIT ?"
        return db.rows(sql, limit).map(::stored).reversed()
    }

    /** Full-text search over message bodies. Tokens are quoted, so user text cannot inject FTS operators. */
    fun search(query: String, conversation: Long? = null, limit: Int = 25): List<StoredMessage> {
        val match = ftsQuery(query) ?: return emptyList()
        val rows = if (conversation == null)
            db.rows("$msgSelect JOIN message_fts ON message_fts.rowid=m.id WHERE message_fts MATCH ? ORDER BY m.ts DESC LIMIT ?", match, limit)
        else
            db.rows("$msgSelect JOIN message_fts ON message_fts.rowid=m.id WHERE message_fts MATCH ? AND m.conversation=? ORDER BY m.ts DESC LIMIT ?", match, conversation, limit)
        return rows.map(::stored)
    }

    internal fun indexMessage(id: Long, body: String) { db.insert("INSERT INTO message_fts(rowid, body) VALUES(?,?)", id, body) }

    fun rebuildIndex() = db.tx {
        db.exec("DELETE FROM message_fts")
        db.rows("SELECT id, body FROM messages").forEach { indexMessage(it.long("id"), it.str("body")) }
    }

    // ---- live messages (the only writer of origin = 'live') ----

    /**
     * Records a message that actually arrived or was sent now. Idempotent on [providerId]. Only live messages can
     * ever trigger automation; see [AutomationGate].
     */
    fun recordLive(
        conversation: Long, direction: Direction, senderKey: String, senderDisplay: String, body: String,
        ts: Long = clock(), providerId: String? = null, author: Author = Author.OWNER,
    ): Long = db.tx {
        if (providerId != null) db.one("SELECT id FROM messages WHERE conversation=? AND provider_id=?", conversation, providerId)?.let { return@tx it.long("id") }
        val sender = upsertParticipant(conversation, senderKey, senderDisplay, null, direction == Direction.OUT)
        val id = db.insert(
            """INSERT INTO messages(conversation,sender,direction,body,ts,ts_precision,provider_id,fp,origin,author,created)
               VALUES(?,?,?,?,?,?,?,?,'live',?,?)""",
            conversation, sender, direction.db, body, ts, 1, providerId, Text.contentFingerprint(direction, body),
            if (direction == Direction.IN) "peer" else author.db, clock(),
        )
        indexMessage(id, body)
        db.update("UPDATE conversations SET updated=? WHERE id=?", clock(), conversation)
        id
    }

    // ---- facts and summaries (approved memory) ----

    fun addFact(conversation: Long, text: String, approved: Boolean, source: String = "user"): Long =
        db.insert("INSERT INTO facts(conversation,text,approved,source,created) VALUES(?,?,?,?,?)", conversation, text.trim(), approved, source, clock())

    fun approveFact(id: Long) { db.update("UPDATE facts SET approved=1 WHERE id=?", id) }

    fun facts(conversation: Long, approvedOnly: Boolean = true): List<Row> =
        db.rows("SELECT * FROM facts WHERE conversation=?" + (if (approvedOnly) " AND approved=1" else "") + " ORDER BY id", conversation)

    fun setSummary(conversation: Long, upToTs: Long, text: String) {
        db.insert("INSERT INTO summaries(conversation,up_to_ts,text,created) VALUES(?,?,?,?)", conversation, upToTs, text, clock())
    }

    fun latestSummary(conversation: Long): Row? = db.one("SELECT * FROM summaries WHERE conversation=? ORDER BY id DESC LIMIT 1", conversation)

    // ---- conflicts ----

    fun openConflicts(conversation: Long): List<Row> =
        db.rows("SELECT * FROM conflicts WHERE conversation=? AND status='open' ORDER BY id", conversation)

    fun resolveConflict(id: Long, resolution: Resolution) = db.tx {
        val c = db.one("SELECT * FROM conflicts WHERE id=? AND status='open'", id) ?: error("No open conflict $id.")
        val msgId = c.long("message")
        val msg = db.one("SELECT * FROM messages WHERE id=?", msgId) ?: error("The conflicting message no longer exists.")
        when (resolution) {
            Resolution.KEEP_EXISTING -> Unit
            Resolution.TAKE_INCOMING -> {
                db.insert("INSERT INTO message_revisions(message,body,import_id,replaced_at) VALUES(?,?,?,?)", msgId, msg.str("body"), c.long("import_id"), clock())
                val body = c.str("incoming_body")
                val dir = Direction.fromDb(msg.str("direction"))
                db.update("UPDATE messages SET body=?, fp=? WHERE id=?", body, Text.contentFingerprint(dir, body), msgId)
                db.update("DELETE FROM message_fts WHERE rowid=?", msgId)
                indexMessage(msgId, body)
            }
            Resolution.KEEP_BOTH -> {
                val body = c.str("incoming_body")
                val dir = Direction.fromDb(msg.str("direction"))
                val id2 = db.insert(
                    """INSERT INTO messages(conversation,sender,direction,body,ts,ts_precision,provider_id,fp,origin,author,first_import,created)
                       VALUES(?,?,?,?,?,?,NULL,?,'import',?,?,?)""",
                    msg.long("conversation"), msg.long("sender"), dir.db, body, c.long("incoming_ts"), msg.long("ts_precision"),
                    Text.contentFingerprint(dir, body), msg.str("author"), c.long("import_id"), clock(),
                )
                indexMessage(id2, body)
                db.insert("INSERT INTO message_sources(message,import_id) VALUES(?,?)", id2, c.long("import_id"))
            }
        }
        db.update("UPDATE conflicts SET status=? WHERE id=?", resolution.name.lowercase(Locale.ROOT), id)
    }

    // ---- deletion (selective) ----

    /** Removes one conversation. Messages, their search index, participants, rules and outbox always go with it. */
    fun deleteConversation(id: Long, deleteFacts: Boolean = true, deleteSummaries: Boolean = true, deleteOriginals: Boolean = true) = db.tx {
        val shas = db.rows("SELECT DISTINCT file_sha256 FROM imports WHERE conversation=?", id).map { it.str("file_sha256") }
        db.exec("DELETE FROM message_fts WHERE rowid IN (SELECT id FROM messages WHERE conversation=$id)")
        db.update("DELETE FROM message_sources WHERE message IN (SELECT id FROM messages WHERE conversation=?)", id)
        db.update("DELETE FROM message_revisions WHERE message IN (SELECT id FROM messages WHERE conversation=?)", id)
        db.update("DELETE FROM messages WHERE conversation=?", id)
        db.update("DELETE FROM conflicts WHERE conversation=?", id)
        db.update("DELETE FROM imports WHERE conversation=?", id)
        db.update("DELETE FROM participants WHERE conversation=?", id)
        db.update("DELETE FROM auto_rules WHERE conversation=?", id)
        db.update("DELETE FROM outbox WHERE conversation=?", id)
        if (deleteFacts) db.update("DELETE FROM facts WHERE conversation=?", id)
        if (deleteSummaries) db.update("DELETE FROM summaries WHERE conversation=?", id)
        db.update("DELETE FROM conversations WHERE id=?", id)
        if (deleteOriginals) shas.forEach { sha -> if (db.count("SELECT COUNT(*) n FROM imports WHERE file_sha256=?", sha) == 0L) originals.delete(sha) }
        // Derived data the owner chose to keep stays addressable only by its (now historical) conversation id.
    }

    fun deleteDerived(conversation: Long, facts: Boolean = true, summaries: Boolean = true) = db.tx {
        if (facts) db.update("DELETE FROM facts WHERE conversation=?", conversation)
        if (summaries) db.update("DELETE FROM summaries WHERE conversation=?", conversation)
    }

    // ---- encrypted backup ----

    private val tables = listOf(
        "conversations", "participants", "imports", "messages", "message_sources", "message_revisions",
        "conflicts", "facts", "summaries", "auto_rules", "outbox",
    )

    /** AES-256-GCM, key from the passphrase with PBKDF2-HMAC-SHA256. The settings table (kill switch) is not exported. */
    fun exportEncrypted(passphrase: CharArray): ByteArray {
        val root = buildJsonObject {
            put("format", "victorsms-archive")
            put("schema", Schema.VERSION)
            tables.forEach { t ->
                put(t, JsonArray(db.rows("SELECT * FROM $t").map { r -> JsonObject(r.mapValues { JsonPrimitive(it.value) }) }))
            }
        }
        val plain = Json.encodeToString(JsonObject.serializer(), root).toByteArray(Charsets.UTF_8)
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key(passphrase, salt), GCMParameterSpec(128, iv))
        return MAGIC + salt + iv + c.doFinal(plain)
    }

    /** Replaces the whole archive. A wrong passphrase or any tampering fails authentication and changes nothing. */
    fun restoreEncrypted(blob: ByteArray, passphrase: CharArray) {
        require(blob.size > MAGIC.size + 28 && blob.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) { "Not a VictorSMS archive backup." }
        val salt = blob.copyOfRange(MAGIC.size, MAGIC.size + 16)
        val iv = blob.copyOfRange(MAGIC.size + 16, MAGIC.size + 28)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(passphrase, salt), GCMParameterSpec(128, iv))
        val plain = try { c.doFinal(blob, MAGIC.size + 28, blob.size - MAGIC.size - 28) } catch (e: Exception) {
            throw IllegalArgumentException("Wrong passphrase, or the backup was altered.")
        }
        val root = Json.parseToJsonElement(String(plain, Charsets.UTF_8)) as JsonObject
        require((root["schema"] as? JsonPrimitive)?.content?.toIntOrNull() == Schema.VERSION) { "Backup schema is not supported by this build." }
        db.tx {
            (tables.reversed()).forEach { db.exec("DELETE FROM $it") }
            db.exec("DELETE FROM message_fts")
            tables.forEach { t ->
                val known = db.rows("PRAGMA table_info($t)").map { it.str("name") }.toSet()
                (root[t] as? JsonArray)?.forEach { el ->
                    val o = el as JsonObject
                    val cols = o.keys.filter { it in known }
                    if (cols.isEmpty()) return@forEach
                    db.insert(
                        "INSERT INTO $t(${cols.joinToString(",")}) VALUES(${cols.joinToString(",") { "?" }})",
                        *cols.map { (o[it] as? JsonPrimitive)?.takeIf { p -> p !is JsonNull }?.content }.toTypedArray(),
                    )
                }
            }
            rebuildIndex()
        }
    }

    private fun key(pass: CharArray, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(pass, salt, 210_000, 256)
        return SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded, "AES")
    }

    // ---- settings ----

    fun setting(key: String): String? = db.one("SELECT value FROM settings WHERE key=?", key)?.get("value")

    fun putSetting(key: String, value: String) {
        db.update("INSERT OR REPLACE INTO settings(key,value) VALUES(?,?)", key, value)
    }

    private fun newUid(): String = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }

    companion object {
        private val MAGIC = "VSMSBK1\u0000".toByteArray(Charsets.ISO_8859_1)

        /** Quoted OR-query from free text; null when there is nothing searchable. Unicode letters and digits only. */
        fun ftsQuery(text: String): String? {
            val tokens = Regex("[\\p{L}\\p{N}]{2,}").findAll(text).map { it.value }.distinct().take(12).toList()
            return tokens.takeIf { it.isNotEmpty() }?.joinToString(" OR ") { "\"${it.replace("\"", "\"\"")}\"" }
        }
    }
}
