package com.victorious.sms.twilio

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class VoiceTokenTest {
    private val ac = "AC" + "0".repeat(32)
    private val sk = "SK" + "1".repeat(32)
    private val secret = "test-secret-not-real"

    private fun part(t: String, i: Int) = JSONObject(String(Base64.getUrlDecoder().decode(t.split(".")[i])))

    @Test fun mintsValidSignedJwt() {
        val t = VoiceToken.mint(ac, sk, secret, "don", "AP" + "2".repeat(32), "CR" + "3".repeat(32), nowS = 1000)
        val parts = t.split(".")
        assertEquals(3, parts.size)
        assertEquals("twilio-fpa;v=1", part(t, 0).getString("cty"))
        val p = part(t, 1)
        assertEquals(sk, p.getString("iss")); assertEquals(ac, p.getString("sub")); assertEquals(4600L, p.getLong("exp"))
        val g = p.getJSONObject("grants")
        assertEquals("don", g.getString("identity"))
        assertEquals("AP" + "2".repeat(32), g.getJSONObject("voice").getJSONObject("outgoing").getString("application_sid"))
        assertEquals("CR" + "3".repeat(32), g.getJSONObject("voice").getString("push_credential_sid"))
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(secret.toByteArray(), "HmacSHA256")) }
        val sig = Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal((parts[0] + "." + parts[1]).toByteArray()))
        assertEquals(sig, parts[2])
        assertFalse(t.contains(secret))
    }

    @Test fun rejectsBadInput() {
        assertTrue(runCatching { VoiceToken.mint("XX", sk, secret, "don", null, null) }.isFailure)
        assertTrue(runCatching { VoiceToken.mint(ac, sk, secret, "bad id!", null, null) }.isFailure)
        assertTrue(runCatching { VoiceToken.mint(ac, sk, secret, "don", "nope", null) }.isFailure)
    }

    @Test fun planOnlyAllowsOwnedCallerId() {
        val owned = listOf("+15551230001")
        val ok = VoiceCallPlan.outbound("+1 555 123 0001", "+1 (555) 999-0000", owned).getOrThrow()
        assertEquals("+15551230001", ok[VoiceCallPlan.PARAM_CALLER_ID]); assertEquals("+15559990000", ok[VoiceCallPlan.PARAM_TO])
        assertTrue(VoiceCallPlan.outbound("+15557770000", "+15559990000", owned).isFailure)
        assertTrue(VoiceCallPlan.outbound("+15551230001", "5559990000", owned).isFailure)
        assertTrue(VoiceCallPlan.outbound("+15551230001", "+15559990000", emptyList()).isFailure)
    }
}
