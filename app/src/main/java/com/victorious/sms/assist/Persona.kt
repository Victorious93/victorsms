package com.victorious.sms.assist

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipInputStream

/** Persona text (persona.md + chat samples), stored app-private. Capped to keep prompts small. */
class PersonaStore(private val context: Context) {
    private val file = File(context.filesDir, "persona.txt")
    private val max = 200_000

    fun text(): String = runCatching { file.readText() }.getOrDefault("")
    fun clear() { file.delete() }

    /** Imports .md/.txt/.json/.csv directly, or text-like entries from a .zip. Returns chars added. */
    fun import(uri: Uri): Int {
        val name = displayName(uri).lowercase()
        val added = StringBuilder()
        context.contentResolver.openInputStream(uri)?.use { input ->
            if (name.endsWith(".zip")) {
                ZipInputStream(input).use { z ->
                    var e = z.nextEntry
                    while (e != null && added.length < max) {
                        val n = e.name.lowercase()
                        if (!e.isDirectory && !n.contains("..") && listOf(".md", ".txt", ".json", ".csv").any { n.endsWith(it) }) {
                            added.append("\n\n# ").append(e.name).append('\n')
                            added.append(z.readBytes().toString(Charsets.UTF_8).take(max - added.length))
                        }
                        e = z.nextEntry
                    }
                }
            } else {
                added.append("\n\n# ").append(name).append('\n').append(input.readBytes().toString(Charsets.UTF_8).take(max))
            }
        }
        file.writeText((text() + added).takeLast(max))
        return added.length
    }

    private fun displayName(uri: Uri): String = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment.orEmpty()
}

enum class VoiceOwnership { OWN, CONSENTED }

/** A voice Don declared as his own, or one whose owner gave explicit consent. */
data class ConsentedVoice(
    val pcVoiceId: String,
    val label: String,
    val ownership: VoiceOwnership,
    val consentFrom: String,
    val consentNote: String,
    val declaredAtMs: Long,
)

/** Consent gate: voice notes can only use voices registered here with a declaration. */
class VoiceRegistry(context: Context) {
    private val file = File(context.filesDir, "voices.json")

    fun all(): List<ConsentedVoice> = runCatching {
        val a = JSONArray(file.readText())
        (0 until a.length()).map { a.getJSONObject(it) }.map {
            ConsentedVoice(it.getString("id"), it.getString("label"), VoiceOwnership.valueOf(it.getString("own")),
                it.optString("from"), it.optString("note"), it.optLong("at"))
        }
    }.getOrDefault(emptyList())

    /** Throws unless the declaration is complete. */
    fun add(v: ConsentedVoice) {
        require(v.pcVoiceId.isNotBlank() && v.label.isNotBlank()) { "Voice id and name are required" }
        if (v.ownership == VoiceOwnership.CONSENTED) require(v.consentFrom.isNotBlank() && v.consentNote.isNotBlank()) {
            "Name the person who consented and how they consented"
        }
        save(all().filter { it.pcVoiceId != v.pcVoiceId } + v)
    }

    fun remove(id: String) = save(all().filter { it.pcVoiceId != id })

    private fun save(list: List<ConsentedVoice>) = file.writeText(JSONArray().apply {
        list.forEach { put(JSONObject().put("id", it.pcVoiceId).put("label", it.label).put("own", it.ownership.name)
            .put("from", it.consentFrom).put("note", it.consentNote).put("at", it.declaredAtMs)) }
    }.toString())
}

/** Builds the prompt for a reply draft. The draft is only ever placed in the composer. */
object Drafter {
    fun draft(context: Context, contactLabel: String, history: List<Pair<Boolean, String>>): String {
        val persona = PersonaStore(context).text().takeLast(24_000)
        val sys = "You write short text-message replies AS Don, in his voice. Match his tone from the persona and samples. " +
            "Reply with the message text only. Persona and samples:\n" + persona.ifBlank { "(none imported)" }
        val convo = history.takeLast(20).joinToString("\n") { (mine, t) -> (if (mine) "Don: " else "$contactLabel: ") + t }
        return PcLlm.chat(PcSettings(context), listOf(ChatMsg("system", sys), ChatMsg("user", "Conversation:\n$convo\n\nDraft Don's next reply.")))
    }
}
