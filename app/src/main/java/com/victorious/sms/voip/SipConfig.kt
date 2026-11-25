package com.victorious.sms.voip

import org.json.JSONObject

/** One SIP account provisioned by `victor-voip add-device`. Always TLS + required SRTP; the server certificate is PINNED (self-hosted servers). */
data class SipConfig(val domain: String, val port: Int, val username: String, val password: String, val displayName: String, val serverCertSha256: String) {
    companion object {
        private val HOST = Regex("^[A-Za-z0-9.-]{1,253}$")
        /** Strict parse: anything that would weaken security (non-TLS, SRTP not required, no pin) is REFUSED, not downgraded. */
        fun parse(json: String): Result<SipConfig> = runCatching {
            val o = JSONObject(json)
            require(o.optString("transport") == "tls") { "only TLS is supported" }
            require(o.optString("srtp") == "required") { "SRTP must be required" }
            val domain = o.getString("domain"); require(HOST.matches(domain)) { "bad domain" }
            val port = o.getInt("port"); require(port in 1..65535) { "bad port" }
            val user = o.getString("username"); require(user.matches(Regex("^[A-Za-z0-9._-]{1,64}$"))) { "bad username" }
            val pw = o.getString("password"); require(pw.length >= 16) { "password too weak" }
            val pin = o.getString("server_cert_sha256").replace(":", "").lowercase(); require(Regex("^[0-9a-f]{64}$").matches(pin)) { "bad certificate pin" }
            SipConfig(domain, port, user, pw, o.optString("display_name", user).take(64), pin)
        }
    }
}

/** Platform-independent call engine contract. The Linphone implementation lives in the `voip` flavor source set (GPLv3). */
interface SipEngine {
    fun register(c: SipConfig)
    fun unregister()
    fun call(number: String): Boolean
    fun answer(); fun hangup()
    var listener: Listener?
    interface Listener { fun onRegistration(state: String, reason: String?); fun onCall(state: String, peer: String?) }
}
