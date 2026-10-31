package com.victorious.sms.twilio

import android.content.Context
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Don's own Twilio credentials, encrypted at rest. Never logged. */
class TwilioCredentials(context: Context) {
    private val p = EncryptedSharedPreferences.create(
        context, "twilio_credentials",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )
    var accountSid: String get() = p.getString("accountSid", "").orEmpty(); set(v) = p.edit().putString("accountSid", v.trim()).apply()
    var apiKeySid: String get() = p.getString("apiKeySid", "").orEmpty(); set(v) = p.edit().putString("apiKeySid", v.trim()).apply()
    var apiKeySecret: String get() = p.getString("apiKeySecret", "").orEmpty(); set(v) = p.edit().putString("apiKeySecret", v).apply()
    /** The chosen alias number (one of Don's Twilio numbers, E.164). */
    var aliasNumber: String get() = p.getString("alias", "").orEmpty(); set(v) = p.edit().putString("alias", v).apply()
    /** All alias numbers Don picked (multiple allowed). Migrates the old single alias. */
    var aliases: Set<String>
        get() = (p.getStringSet("aliases", null) ?: emptySet()) + listOfNotNull(aliasNumber.takeIf { it.isNotBlank() })
        set(v) { p.edit().putStringSet("aliases", v).putString("alias", "").apply() }
    /** Don's real phone, rung first for masked bridge calls (E.164). */
    var myPhone: String get() = p.getString("myPhone", "").orEmpty(); set(v) = p.edit().putString("myPhone", v.trim()).apply()
    var defaultIdentity: Identity
        get() = Identity.decode(p.getString("identity.default", null)) ?: Identity.Sim
        set(v) = p.edit().putString("identity.default", v.encode()).apply()
    private fun threadKey(address: String) = "identity.thread." + PhoneNumbers.normalize(address)
    fun threadIdentity(address: String): Identity? = Identity.decode(p.getString(threadKey(address), null))
    fun setThreadIdentity(address: String, i: Identity?) =
        p.edit().apply { if (i == null) remove(threadKey(address)) else putString(threadKey(address), i.encode()) }.apply()
    fun resolve(address: String): Identity = IdentityResolver.resolve(threadIdentity(address), defaultIdentity, aliases)
    fun isSet() = accountSid.startsWith("AC") && apiKeySid.isNotBlank() && apiKeySecret.isNotBlank()
    fun clear() = p.edit().clear().apply()
}

data class TwilioNumber(val sid: String, val phoneNumber: String, val friendlyName: String)
data class AvailableNumber(val phoneNumber: String, val locality: String)
data class SipDomain(val sid: String, val domainName: String)

class TwilioException(val status: Int, message: String) : Exception(message)

/** Minimal Twilio REST client (HTTPS + Basic auth with an API key). Call off the main thread. */
class TwilioClient(private val creds: TwilioCredentials) {
    private val base get() = "https://api.twilio.com/2010-04-01/Accounts/${creds.accountSid}"

    private fun call(method: String, url: String, form: Map<String, String> = emptyMap()): JSONObject {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 10_000; c.readTimeout = 15_000
        val auth = Base64.encodeToString("${creds.apiKeySid}:${creds.apiKeySecret}".toByteArray(), Base64.NO_WRAP)
        c.setRequestProperty("Authorization", "Basic $auth")
        if (method == "POST") {
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            val body = form.entries.joinToString("&") { URLEncoder.encode(it.key, "UTF-8") + "=" + URLEncoder.encode(it.value, "UTF-8") }
            c.outputStream.use { it.write(body.toByteArray()) }
        }
        try {
            val code = c.responseCode
            val text = (if (code < 400) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            val json = runCatching { JSONObject(text) }.getOrDefault(JSONObject())
            // Only Twilio's own error message is surfaced, never the request or credentials.
            if (code >= 400) throw TwilioException(code, json.optString("message", "HTTP $code"))
            return json
        } finally { c.disconnect() }
    }

    fun listNumbers(): List<TwilioNumber> {
        val a = call("GET", "$base/IncomingPhoneNumbers.json?PageSize=100").optJSONArray("incoming_phone_numbers") ?: return emptyList()
        return (0 until a.length()).map { a.getJSONObject(it) }.map { TwilioNumber(it.optString("sid"), it.optString("phone_number"), it.optString("friendly_name")) }
    }

    fun listSipDomains(): List<SipDomain> {
        val a = call("GET", "$base/SIP/Domains.json").optJSONArray("domains") ?: return emptyList()
        return (0 until a.length()).map { a.getJSONObject(it) }.map { SipDomain(it.optString("sid"), it.optString("domain_name")) }
    }

    /** Creates a SIP domain (name.sip.twilio.com), a credential list with one credential, and maps it for calls. */
    fun createSipDomainWithCredentials(domainPrefix: String, username: String, password: String): SipDomain {
        val d = call("POST", "$base/SIP/Domains.json", mapOf("DomainName" to "$domainPrefix.sip.twilio.com",
            "FriendlyName" to "VictorSMS $domainPrefix", "SipRegistration" to "true"))
        val cl = call("POST", "$base/SIP/CredentialLists.json", mapOf("FriendlyName" to "VictorSMS $domainPrefix"))
        val clSid = cl.getString("sid")
        call("POST", "$base/SIP/CredentialLists/$clSid/Credentials.json", mapOf("Username" to username, "Password" to password))
        val dSid = d.getString("sid")
        call("POST", "$base/SIP/Domains/$dSid/Auth/Calls/CredentialListMappings.json", mapOf("CredentialListSid" to clSid))
        call("POST", "$base/SIP/Domains/$dSid/Auth/Registrations/CredentialListMappings.json", mapOf("CredentialListSid" to clSid))
        return SipDomain(dSid, d.optString("domain_name"))
    }

    fun searchAvailable(country: String, areaCode: String): List<AvailableNumber> {
        val q = if (areaCode.isNotBlank()) "?AreaCode=" + URLEncoder.encode(areaCode, "UTF-8") + "&SmsEnabled=true&VoiceEnabled=true" else "?SmsEnabled=true&VoiceEnabled=true"
        val a = call("GET", "$base/AvailablePhoneNumbers/$country/Local.json$q").optJSONArray("available_phone_numbers") ?: return emptyList()
        return (0 until a.length()).map { a.getJSONObject(it) }.map { AvailableNumber(it.optString("phone_number"), it.optString("locality")) }
    }

    /** Monthly price for a local number in [country] from the Twilio Pricing API, e.g. "1.15 USD". */
    fun localNumberMonthlyPrice(country: String): String? = runCatching {
        val j = call("GET", "https://pricing.twilio.com/v1/PhoneNumbers/Countries/$country")
        val prices = j.optJSONArray("phone_number_prices") ?: return null
        (0 until prices.length()).map { prices.getJSONObject(it) }.firstOrNull { it.optString("number_type") == "local" }
            ?.let { it.optString("current_price") + " " + j.optString("price_unit") }
    }.getOrNull()

    /** Buys a number. ONLY call this after the user confirmed the cost dialog. */
    fun buyNumber(phoneNumber: String): TwilioNumber {
        val j = call("POST", "$base/IncomingPhoneNumbers.json", mapOf("PhoneNumber" to phoneNumber))
        return TwilioNumber(j.optString("sid"), j.optString("phone_number"), j.optString("friendly_name"))
    }

    /** Fetches Don's owned numbers from Twilio and rejects [from] unless it is one of them. */
    fun requireOwned(from: String): String =
        FromNumberGuard.check(from, listNumbers().map { it.phoneNumber }).getOrThrow()

    /**
     * Sends SMS (or MMS when [mediaUrl] is a public https URL) from Don's alias via the Messaging API.
     * User-initiated only, after the price dialog. The from-number is checked against the owned list.
     */
    fun sendMessage(from: String, to: String, body: String, mediaUrl: String? = null): String {
        val owned = requireOwned(from)
        val form = mutableMapOf("From" to owned, "To" to to, "Body" to body)
        if (!mediaUrl.isNullOrBlank()) {
            require(mediaUrl.startsWith("https://")) { "MMS media must be a public https URL." }
            form["MediaUrl"] = mediaUrl
        }
        return call("POST", "$base/Messages.json", form).optString("sid")
    }

    /**
     * Masked bridge call (no-SIP fallback): Twilio rings [myPhone] from the alias; when Don answers,
     * TwiML dials [callee] with callerId = alias (a Twilio number Don owns). Returns the call SID.
     */
    fun bridgeCall(alias: String, myPhone: String, callee: String): String {
        val owned = requireOwned(alias)
        require(PhoneNumbers.isE164(myPhone) && PhoneNumbers.isE164(callee)) { "Numbers must be in +E.164 format." }
        return call("POST", "$base/Calls.json", mapOf("From" to owned, "To" to myPhone, "Twiml" to TwiML.bridge(owned, callee))).optString("sid")
    }

    /** Outbound SMS price for [country] (first listed carrier), e.g. "0.0083 USD". Null if unavailable. */
    fun smsPrice(country: String = "US"): String? = runCatching {
        val j = call("GET", "https://pricing.twilio.com/v1/Messaging/Countries/$country")
        val c = j.optJSONArray("outbound_sms_prices")?.optJSONObject(0) ?: return null
        val p = c.optJSONArray("prices")?.optJSONObject(0) ?: return null
        p.optString("current_price") + " " + j.optString("price_unit") + " per segment"
    }.getOrNull()

    /** Outbound voice price per minute to [number] (Voice Pricing v2). Null if unavailable. */
    fun voicePricePerMinute(number: String): String? = runCatching {
        val j = call("GET", "https://pricing.twilio.com/v2/Voice/Numbers/" + URLEncoder.encode(number, "UTF-8"))
        val p = j.optJSONArray("outbound_call_prices")?.optJSONObject(0) ?: return null
        p.optString("current_price") + " " + j.optString("price_unit") + "/min"
    }.getOrNull()

    /** Recent messages to or from [alias] (both directions), newest first. Optionally only with [peer]. */
    fun aliasMessages(alias: String, peer: String? = null, pageSize: Int = 50): List<AliasMessage> {
        fun q(k1: String, v1: String, k2: String?, v2: String?) = "$base/Messages.json?PageSize=$pageSize&" +
            "$k1=" + URLEncoder.encode(v1, "UTF-8") + (if (k2 != null && v2 != null) "&$k2=" + URLEncoder.encode(v2, "UTF-8") else "")
        val inbound = call("GET", q("To", alias, "From", peer)).optJSONArray("messages")
        val outbound = if (peer != null) call("GET", q("From", alias, "To", peer)).optJSONArray("messages") else null
        return listOfNotNull(inbound, outbound).flatMap { a -> (0 until a.length()).map { a.getJSONObject(it) } }
            .map { AliasMessage.fromJson(alias, it) }
            .distinctBy { it.sid }.sortedByDescending { it.dateMs }
    }
}

data class AliasMessage(val sid: String, val alias: String, val peer: String, val body: String, val dateMs: Long, val outgoing: Boolean, val numMedia: Int) {
    companion object {
        private val fmt get() = java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z", java.util.Locale.US)
        fun fromJson(alias: String, j: JSONObject): AliasMessage {
            val outgoing = j.optString("direction").startsWith("outbound")
            val date = j.optString("date_sent").ifBlank { j.optString("date_created") }
            return AliasMessage(j.optString("sid"), alias, if (outgoing) j.optString("to") else j.optString("from"),
                j.optString("body"), runCatching { fmt.parse(date)!!.time }.getOrDefault(0L), outgoing, j.optString("num_media").toIntOrNull() ?: 0)
        }
    }
}
