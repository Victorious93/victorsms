package com.victorious.sms.block

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

enum class BlockAction { BLOCK, SILENCE, ALLOW }

/** A spam pattern: number prefix/wildcard (e.g. "+1900*") or an SMS keyword. */
data class SpamPattern(val pattern: String, val isKeyword: Boolean, val action: BlockAction = BlockAction.BLOCK)

/**
 * Local-only blocklist and spam patterns (encrypted). Decision order for a number:
 * explicit ALLOW > explicit BLOCK/SILENCE > number pattern > (SMS) keyword pattern > allow.
 */
class Blocklist(context: Context) {
    private val prefs = EncryptedSharedPreferences.create(
        context, "blocklist",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    fun numbers(): Map<String, BlockAction> = prefs.all.filterKeys { it.startsWith("n:") }
        .mapNotNull { (k, v) -> runCatching { k.removePrefix("n:") to BlockAction.valueOf(v as String) }.getOrNull() }.toMap()

    fun set(number: String, action: BlockAction) = prefs.edit().putString("n:" + normalize(number), action.name).apply()
    fun remove(number: String) = prefs.edit().remove("n:" + normalize(number)).apply()

    fun patterns(): List<SpamPattern> = runCatching {
        val a = JSONArray(prefs.getString("patterns", "[]"))
        (0 until a.length()).map { a.getJSONObject(it) }
            .map { SpamPattern(it.getString("p"), it.optBoolean("k"), BlockAction.valueOf(it.optString("a", "BLOCK"))) }
    }.getOrDefault(emptyList())

    fun setPatterns(list: List<SpamPattern>) = prefs.edit().putString("patterns", JSONArray().apply {
        list.forEach { put(JSONObject().put("p", it.pattern).put("k", it.isKeyword).put("a", it.action.name)) }
    }.toString()).apply()

    fun decideNumber(number: String): BlockAction {
        val n = normalize(number)
        numbers()[n]?.let { return it }
        patterns().filter { !it.isKeyword }.firstOrNull { matchNumber(it.pattern, n) }?.let { return it.action }
        return BlockAction.ALLOW
    }

    fun decideSms(number: String, body: String): BlockAction {
        val byNumber = decideNumber(number)
        if (byNumber != BlockAction.ALLOW || numbers()[normalize(number)] == BlockAction.ALLOW) return byNumber
        val lower = body.lowercase()
        return patterns().filter { it.isKeyword && it.pattern.isNotBlank() }
            .firstOrNull { lower.contains(it.pattern.lowercase()) }?.action ?: BlockAction.ALLOW
    }

    companion object {
        fun normalize(n: String) = n.filter { it.isDigit() || it == '+' }
        /** "*" matches any digits, e.g. "+1900*". Without "*" the match is exact. */
        fun matchNumber(pattern: String, number: String): Boolean {
            val clean = pattern.filter { it.isDigit() || it == '+' || it == '*' }
            if (clean.isEmpty() || clean == "*") return false
            val regex = Regex("^" + clean.split("*").joinToString("[0-9]*") { Regex.escape(it) } + "$")
            return regex.matches(number)
        }
    }
}

data class SpamMessage(val address: String, val body: String, val dateMs: Long)

/** Spam folder: filtered SMS are kept in an app-private file, not in the system inbox. */
class SpamFolder(context: Context) {
    private val file = File(context.filesDir, "spam_sms.json")

    @Synchronized fun all(): List<SpamMessage> = runCatching {
        val a = JSONArray(file.readText())
        (0 until a.length()).map { a.getJSONObject(it) }.map { SpamMessage(it.getString("a"), it.getString("b"), it.getLong("d")) }
    }.getOrDefault(emptyList())

    @Synchronized fun add(m: SpamMessage) = write((listOf(m) + all()).take(500))
    @Synchronized fun remove(m: SpamMessage) = write(all() - m)
    @Synchronized fun clear() = write(emptyList())

    private fun write(list: List<SpamMessage>) = file.writeText(JSONArray().apply {
        list.forEach { put(JSONObject().put("a", it.address).put("b", it.body).put("d", it.dateMs)) }
    }.toString())
}
