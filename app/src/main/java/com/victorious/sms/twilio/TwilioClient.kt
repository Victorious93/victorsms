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

    /** Sends a text from Don's alias number via the Messaging API. User-initiated only. Returns the message SID. */
    fun sendSms(from: String, to: String, body: String): String =
        call("POST", "$base/Messages.json", mapOf("From" to from, "To" to to, "Body" to body)).optString("sid")
}
