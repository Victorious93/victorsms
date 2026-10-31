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

    fun isWhitelisted(number: String) = SmsTriggerGate.isWhitelisted(whitelist(), number)

    /** Case-sensitive substring of the body. Empty code word never matches. */
    fun matchesCodeWord(body: String): Boolean = SmsTriggerGate.matchesCodeWord(codeWord, body)
}

/** Pure gate logic (unit-tested). Forward only if enabled AND sender whitelisted AND code word present. */
object SmsTriggerGate {
    fun isWhitelisted(whitelist: Set<String>, number: String): Boolean {
        val n = Blocklist.normalize(number)
        return n.isNotBlank() && whitelist.any { Blocklist.normalize(it) == n }
    }

    fun matchesCodeWord(codeWord: String, body: String): Boolean = codeWord.isNotEmpty() && body.contains(codeWord)

    fun shouldForward(enabled: Boolean, whitelist: Set<String>, codeWord: String, sender: String, body: String): Boolean =
        enabled && isWhitelisted(whitelist, sender) && matchesCodeWord(codeWord, body)

    /** Command text with the code word stripped, so DCA never sees it. */
    fun commandText(codeWord: String, body: String): String =
        body.replace(codeWord, " ").trim().replace(Regex("\\s+"), " ")
}

/**
 * Builds sms.remote_command.forward and sends it to DCA. Returns a status string for the UI only;
 * never includes the code word, whitelist, or message body.
 */
object SmsRemoteTrigger {
    const val OP = "sms.remote_command.forward"

    fun maybeForward(context: Context, sourceNumber: String, body: String): String? {
        val s = SmsRemoteTriggerSettings(context)
        val codeWord = s.codeWord
        if (!SmsTriggerGate.shouldForward(s.enabled, s.whitelist(), codeWord, sourceNumber, body)) return null
        // Strip the code word from the command text so DCA never needs it.
        val commandText = SmsTriggerGate.commandText(codeWord, body)
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
