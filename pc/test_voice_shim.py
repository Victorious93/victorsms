import unittest
import voice_shim as v

OWNED = ["+15551230001"]


class T(unittest.TestCase):
    def test_outbound_owned(self):
        x = v.outbound_twiml({"CallerId": "+1 555 123 0001", "To": "+15559990000"}, OWNED)
        self.assertIn('callerId="+15551230001"', x); self.assertIn("<Number>+15559990000</Number>", x)

    def test_outbound_refuses_spoof(self):
        for p in ({"CallerId": "+15557770000", "To": "+15559990000"}, {"To": "+15559990000"},
                  {"CallerId": "+15551230001", "To": "<x>"}):
            self.assertIn("<Hangup/>", v.outbound_twiml(p, OWNED))
        self.assertIn("<Hangup/>", v.outbound_twiml({"CallerId": "+15551230001", "To": "+15559990000"}, []))

    def test_incoming(self):
        self.assertIn("<Client>don</Client>", v.incoming_twiml({"To": "+15551230001"}, OWNED))
        self.assertIn("<Hangup/>", v.incoming_twiml({"To": "+15550000000"}, OWNED))

    def test_signature(self):
        # Twilio scheme: base64(HMAC-SHA1(auth_token, url + concat(sorted key+value))).
        import base64, hashlib, hmac
        url = "https://voice.example.net/voice"
        p = {"To": "+15559990000", "CallerId": "+15551230001", "CallSid": "CA1"}
        expect = base64.b64encode(hmac.new(b"tok", (url + "CallSidCA1" + "CallerId+15551230001" + "To+15559990000").encode(), hashlib.sha1).digest()).decode()
        self.assertEqual(v.twilio_signature("tok", url, p), expect)
        self.assertTrue(v.valid_signature("tok", url, p, expect))
        self.assertFalse(v.valid_signature("tok", url, p, "bad"))
        self.assertFalse(v.valid_signature("", url, p, None))

if __name__ == "__main__":
    unittest.main()
