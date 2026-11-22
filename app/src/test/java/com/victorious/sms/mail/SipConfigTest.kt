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

class SipRulesTest {
    @org.junit.Test fun dialableAcceptsExtensionsAndE164Only() {
        org.junit.Assert.assertEquals("1002", com.victorious.sms.voip.SipRules.dialable(" 1002 "))
        org.junit.Assert.assertEquals("+15551234567", com.victorious.sms.voip.SipRules.dialable("+1 555-123-4567"))
        for (bad in listOf("sip:x@evil", "1002;header=x", "1\r\nVia: x", "abc", "", "+", "1"))
            org.junit.Assert.assertNull(bad, com.victorious.sms.voip.SipRules.dialable(bad))
    }
    @org.junit.Test fun engineStatesMapToPhases() {
        val P = com.victorious.sms.voip.SipRules.Phase.entries.associateBy { it.name }
        org.junit.Assert.assertEquals(P.getValue("ACTIVE"), com.victorious.sms.voip.SipRules.phase("StreamsRunning"))
        org.junit.Assert.assertEquals(P.getValue("RINGING"), com.victorious.sms.voip.SipRules.phase("OutgoingRinging"))
        org.junit.Assert.assertEquals(P.getValue("ENDED"), com.victorious.sms.voip.SipRules.phase("Released"))
        org.junit.Assert.assertEquals(P.getValue("FAILED"), com.victorious.sms.voip.SipRules.phase("Error"))
    }
}
