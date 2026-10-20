package com.victorious.sms.assist

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** PC endpoints over the Victorious Mesh. All empty (off) by default. */
class PcSettings(context: Context) {
    private val p = context.getSharedPreferences("pc_endpoints", Context.MODE_PRIVATE)
    /** Ollama-compatible base URL, e.g. http://100.64.0.2:11434 */
    var llmUrl: String get() = p.getString("llmUrl", "").orEmpty(); set(v) = p.edit().putString("llmUrl", v.trim().trimEnd('/')).apply()
    var llmModel: String get() = p.getString("llmModel", "llama3.1").orEmpty(); set(v) = p.edit().putString("llmModel", v.trim()).apply()
    /** TTS / voice-clone endpoint, e.g. a Chatterbox wrapper: POST {"text","voice"} -> audio bytes */
    var ttsUrl: String get() = p.getString("ttsUrl", "").orEmpty(); set(v) = p.edit().putString("ttsUrl", v.trim()).apply()
}

data class ChatMsg(val role: String, val content: String)

/** Ollama-compatible chat: POST {base}/api/chat {model, messages, stream:false} -> message.content. */
object PcLlm {
    fun chat(s: PcSettings, messages: List<ChatMsg>): String {
        require(s.llmUrl.startsWith("http")) { "Set the PC LLM URL in Settings → Assistant & persona" }
        val body = JSONObject().put("model", s.llmModel).put("stream", false)
            .put("messages", JSONArray().apply { messages.forEach { put(JSONObject().put("role", it.role).put("content", it.content)) } })
        val c = URL(s.llmUrl + "/api/chat").openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.doOutput = true; c.connectTimeout = 5_000; c.readTimeout = 120_000
        c.setRequestProperty("Content-Type", "application/json")
        try {
            c.outputStream.use { it.write(body.toString().toByteArray()) }
            if (c.responseCode != 200) throw IllegalStateException("LLM endpoint returned HTTP ${c.responseCode}")
            val j = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
            return j.optJSONObject("message")?.optString("content").orEmpty().trim()
        } finally { c.disconnect() }
    }
}

/** TTS: POST {ttsUrl} {"text","voice"} -> audio (wav/mp3). Saved to files/voice_notes/. */
object PcTts {
    fun synthesize(context: Context, s: PcSettings, voice: ConsentedVoice, text: String): File {
        require(s.ttsUrl.startsWith("http")) { "Set the PC voice URL in Settings → Assistant & persona" }
        val c = URL(s.ttsUrl).openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.doOutput = true; c.connectTimeout = 5_000; c.readTimeout = 180_000
        c.setRequestProperty("Content-Type", "application/json")
        try {
            c.outputStream.use { it.write(JSONObject().put("text", text).put("voice", voice.pcVoiceId).toString().toByteArray()) }
            if (c.responseCode != 200) throw IllegalStateException("Voice endpoint returned HTTP ${c.responseCode}")
            val ext = if (c.contentType?.contains("mpeg") == true) "mp3" else "wav"
            val dir = File(context.filesDir, "voice_notes").apply { mkdirs() }
            val f = File(dir, "note_${System.currentTimeMillis()}.$ext")
            c.inputStream.use { inp -> f.outputStream().use { inp.copyTo(it) } }
            return f
        } finally { c.disconnect() }
    }
}
