#!/usr/bin/env python3
"""VictorSMS voice shim: TwiML webhooks for alias calls over the Twilio Voice SDK.

Runs on Don's PC (Python 3 standard library only). Twilio calls it, never the phone.

  POST /voice     TwiML App Voice URL. Outbound SDK call: dial params To with callerId = CallerId,
                  but ONLY if CallerId is one of Don's own Twilio numbers (fetched live). Never spoofs.
  POST /incoming  Voice URL of an owned alias number: ring the app (client identity "don").

Every request must carry a valid X-Twilio-Signature. Secrets come from the environment only:
  TWILIO_ACCOUNT_SID, TWILIO_API_KEY_SID, TWILIO_API_KEY_SECRET  (REST lookups of owned numbers)
  TWILIO_AUTH_TOKEN                                             (webhook signature check)
  SHIM_PUBLIC_URL   e.g. https://voice.example.net  (the URL Twilio uses; needed for signatures)
  SHIM_PORT         default 8787
No token server: the phone mints its own short-lived access token (see docs/ARCHITECTURE.md).
"""
import base64, hashlib, hmac, json, os, re, time, urllib.parse, urllib.request
from http.server import BaseHTTPRequestHandler, HTTPServer
from xml.sax.saxutils import escape, quoteattr

E164 = re.compile(r"^\+[1-9][0-9]{6,14}$")
CLIENT_IDENTITY = "don"


def normalize(n):
    t = (n or "").strip()
    d = "".join(c for c in t if c.isdigit())
    return "+" + d if t.startswith("+") else d


def twilio_signature(auth_token, url, params):
    s = url + "".join(k + params[k] for k in sorted(params))
    return base64.b64encode(hmac.new(auth_token.encode(), s.encode(), hashlib.sha1).digest()).decode()


def valid_signature(auth_token, url, params, header):
    return bool(auth_token and header) and hmac.compare_digest(twilio_signature(auth_token, url, params), header)


def reject(msg="Call refused."):
    return "<Response><Say>%s</Say><Hangup/></Response>" % escape(msg)


def outbound_twiml(params, owned):
    caller = normalize(params.get("CallerId", ""))
    to = normalize(params.get("To", ""))
    match = next((o for o in owned if normalize(o) == caller), None)
    if not E164.match(caller) or match is None:
        return reject("That caller I D is not one of your numbers.")
    if not E164.match(to) or to == caller:
        return reject("Invalid number.")
    return "<Response><Dial callerId=%s><Number>%s</Number></Dial></Response>" % (quoteattr(match), escape(to))


def incoming_twiml(params, owned):
    to = normalize(params.get("To", ""))
    if not any(normalize(o) == to for o in owned):
        return reject()
    return "<Response><Dial><Client>%s</Client></Dial></Response>" % CLIENT_IDENTITY


class Owned:
    """Owned numbers from Twilio IncomingPhoneNumbers, cached for 60 s."""
    def __init__(self, fetch=None, ttl=60):
        self.fetch, self.ttl, self.at, self.val = fetch or self._fetch, ttl, 0, []

    def get(self):
        if time.time() - self.at > self.ttl:
            self.val, self.at = self.fetch(), time.time()
        return self.val

    @staticmethod
    def _fetch():
        sid, key, sec = (os.environ[k] for k in ("TWILIO_ACCOUNT_SID", "TWILIO_API_KEY_SID", "TWILIO_API_KEY_SECRET"))
        req = urllib.request.Request("https://api.twilio.com/2010-04-01/Accounts/%s/IncomingPhoneNumbers.json?PageSize=100" % sid)
        req.add_header("Authorization", "Basic " + base64.b64encode(("%s:%s" % (key, sec)).encode()).decode())
        with urllib.request.urlopen(req, timeout=10) as r:
            return [n["phone_number"] for n in json.load(r).get("incoming_phone_numbers", [])]


def make_handler(owned, auth_token, public_url):
    class H(BaseHTTPRequestHandler):
        def log_message(self, *a):  # never log request bodies (phone numbers)
            pass

        def do_POST(self):
            path = self.path.split("?")[0]
            if path not in ("/voice", "/incoming"):
                self.send_response(404); self.end_headers(); return
            body = self.rfile.read(int(self.headers.get("Content-Length", 0) or 0)).decode()
            params = {k: v[0] for k, v in urllib.parse.parse_qs(body, keep_blank_values=True).items()}
            if not valid_signature(auth_token, public_url.rstrip("/") + self.path, params, self.headers.get("X-Twilio-Signature")):
                self.send_response(403); self.end_headers(); return
            try:
                nums = owned.get()
            except Exception:
                nums = []
            xml = (outbound_twiml if path == "/voice" else incoming_twiml)(params, nums)
            out = xml.encode()
            self.send_response(200)
            self.send_header("Content-Type", "text/xml"); self.send_header("Content-Length", str(len(out)))
            self.end_headers(); self.wfile.write(out)
    return H


if __name__ == "__main__":
    port = int(os.environ.get("SHIM_PORT", "8787"))
    h = make_handler(Owned(), os.environ["TWILIO_AUTH_TOKEN"], os.environ["SHIM_PUBLIC_URL"])
    print("voice shim on 127.0.0.1:%d (put an https tunnel/reverse proxy in front)" % port)
    HTTPServer(("127.0.0.1", port), h).serve_forever()
