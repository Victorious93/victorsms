package com.victorious.sms.mail

import com.victorious.sms.voip.SipConfig
import org.junit.Assert.*
import org.junit.Test

class SipConfigTest {
    private fun doc(vararg over: Pair<String, Any>) = org.json.JSONObject(mapOf("domain" to "sip.example.org", "port" to 5061, "transport" to "tls", "username" to "1001", "password" to "A".repeat(24),
        "display_name" to "pixel", "srtp" to "required", "server_cert_sha256" to "AB:".repeat(31) + "AB") + over).toString()
    @Test fun acceptsProvisioningFromVictorVoip() { val c = SipConfig.parse(doc()).getOrThrow(); assertEquals("sip.example.org", c.domain); assertEquals(64, c.serverCertSha256.length) }
    @Test fun refusesAnythingThatWeakensSecurity() {
        for (d in listOf(doc("transport" to "udp"), doc("srtp" to "optional"), doc("password" to "short"), doc("server_cert_sha256" to "00"), doc("domain" to "a b;c"), doc("port" to 0)))
            assertTrue(d, SipConfig.parse(d).isFailure)
    }
}
