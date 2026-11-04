package com.victorious.sms.twilio

import org.json.JSONObject
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Twilio Access Token (JWT, HS256) with a Voice grant, minted ON THE PHONE from Don's own API key
 * (the same encrypted key the REST client already uses), so no token server is needed.
 * Pure JVM code: unit-tested. Short TTL; never logged.
 */
object VoiceToken {
    const val DEFAULT_TTL_S = 3600L

    fun mint(
        accountSid: String, apiKeySid: String, apiKeySecret: String, identity: String,
        outgoingAppSid: String?, pushCredentialSid: String?, nowS: Long = System.currentTimeMillis() / 1000, ttlS: Long = DEFAULT_TTL_S,
    ): String {
        require(accountSid.startsWith("AC")) { "Account SID must start with AC" }
        require(apiKeySid.startsWith("SK")) { "API key SID must start with SK" }
        require(apiKeySecret.isNotBlank()) { "API key secret missing" }
        require(identity.matches(Regex("^[A-Za-z0-9_]{1,121}$"))) { "Identity must be letters, digits or _" }
        require(ttlS in 60..86_400) { "TTL out of range" }
        val voice = JSONObject()
        voice.put("incoming", JSONObject().put("allow", true))
        if (!outgoingAppSid.isNullOrBlank()) {
            require(outgoingAppSid.startsWith("AP")) { "TwiML App SID must start with AP" }
            voice.put("outgoing", JSONObject().put("application_sid", outgoingAppSid))
        }
        if (!pushCredentialSid.isNullOrBlank()) {
            require(pushCredentialSid.startsWith("CR")) { "Push credential SID must start with CR" }
            voice.put("push_credential_sid", pushCredentialSid)
        }
        val header = JSONObject().put("typ", "JWT").put("alg", "HS256").put("cty", "twilio-fpa;v=1")
        val payload = JSONObject()
            .put("jti", "$apiKeySid-$nowS").put("iss", apiKeySid).put("sub", accountSid)
            .put("iat", nowS).put("nbf", nowS).put("exp", nowS + ttlS)
            .put("grants", JSONObject().put("identity", identity).put("voice", voice))
        val signingInput = b64(header.toString().toByteArray()) + "." + b64(payload.toString().toByteArray())
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(apiKeySecret.toByteArray(), "HmacSHA256")) }
        return signingInput + "." + b64(mac.doFinal(signingInput.toByteArray()))
    }

    private fun b64(b: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(b)
}

/** Outbound call parameters sent to the TwiML App (and re-checked by pc/voice_shim.py). */
object VoiceCallPlan {
    const val PARAM_TO = "To"
    const val PARAM_CALLER_ID = "CallerId"

    /** [owned] must be freshly fetched from Twilio. Refuses any caller ID Don does not own. */
    fun outbound(alias: String, callee: String, owned: Collection<String>): Result<Map<String, String>> {
        val from = FromNumberGuard.check(alias, owned).getOrElse { return Result.failure(it) }
        val to = PhoneNumbers.normalize(callee)
        if (!PhoneNumbers.isE164(to)) return Result.failure(IllegalArgumentException("Callee must be in +E.164 format."))
        if (PhoneNumbers.same(to, from)) return Result.failure(IllegalArgumentException("Cannot call the alias itself."))
        return Result.success(mapOf(PARAM_TO to to, PARAM_CALLER_ID to from))
    }
}
