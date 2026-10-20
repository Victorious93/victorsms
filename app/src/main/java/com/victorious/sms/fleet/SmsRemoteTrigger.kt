package com.victorious.sms.fleet

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray
import org.json.JSONObject
import com.victorious.sms.block.Blocklist

/**
 * Ratified SMS remote-command trigger. OFF by default.
 * When an SMS arrives from a whitelisted sender AND contains the user-set code word,
 * VictorSMS forwards sms.remote_command.forward to droidcommand-AI and never executes anything.
 * Whitelist and code word are encrypted and never logged.
 */
class SmsRemoteTriggerSettings(context: Context) {
    private val p = EncryptedSharedPreferences.create(
        context, "sms_remote_trigger",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    var enabled: Boolean get() = p.getBoolean("enabled", false); set(v) = p.edit().putBoolean("enabled", v).apply()

    /** Never log this. Empty = never matches. */
    var codeWord: String get() = p.getString("codeWord", "").orEmpty(); set(v) = p.edit().putString("codeWord", v).apply()

    fun whitelist(): Set<String> = p.getStringSet("whitelist", emptySet())!!.map { Blocklist.normalize(it) }.filter { it.isNotBlank() }.toSet()
    fun setWhitelist(numbers: Set<String>) = p.edit().putStringSet("whitelist", numbers.map { Blocklist.normalize(it) }.filter { it.isNotBlank() }.toSet()).apply()

    fun isWhitelisted(number: String) = Blocklist.normalize(number) in whitelist()

    /** Case-sensitive substring of the body. Empty code word never matches. */
    fun matchesCodeWord(body: String): Boolean {
        val w = codeWord
        return w.isNotEmpty() && body.contains(w)
    }
}

/**
 * Builds sms.remote_command.forward and sends it to DCA. Returns a status string for the UI only;
 * never includes the code word, whitelist, or message body.
 */
object SmsRemoteTrigger {
    const val OP = "sms.remote_command.forward"

    fun maybeForward(context: Context, sourceNumber: String, body: String): String? {
        val s = SmsRemoteTriggerSettings(context)
        if (!s.enabled) return null
        if (!s.isWhitelisted(sourceNumber) || !s.matchesCodeWord(body)) return null
        // Strip the code word from the command text so DCA never needs it.
        val commandText = body.replace(s.codeWord, " ").trim().replace(Regex("\\s+"), " ")
        val actionRef = commandText.substringBefore(' ').ifBlank { "sms-command" }
        val args = JSONObject().put("smsCommandText", commandText).put("smsCommandId", actionRef)
        val client = DcaClient(context)
        client.gate()?.let { return "SMS trigger gated: $it" }
        val params = client.envelope("controller", actionRef, args).put(
            "trigger",
            JSONObject()
                .put("sourceNumber", sourceNumber)
                .put("matchedKeyword", true)
                .put("rawExtras", JSONObject().put("nodeId", NodeId.get(context))),
        )
        return when (val r = client.call(OP, "controller", params)) {
            is DcaClient.Result.Blocked -> "SMS trigger blocked: ${r.reason}"
            is DcaClient.Result.Ok -> if (r.response.optBoolean("ok")) "SMS trigger forwarded." else "SMS trigger: DCA refused."
        }
    }
}
