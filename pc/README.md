# PC shim area

## `voice_shim.py` – TwiML webhooks for alias calls

Python 3 standard library only. Twilio calls these endpoints; the phone does not.

| Endpoint | Used as | Returns |
|---|---|---|
| `POST /voice` | TwiML App Voice URL | `<Dial callerId=CallerId><Number>To</Number></Dial>` only if `CallerId` is one of your Twilio numbers (fetched live, cached 60 s); otherwise a spoken refusal + hangup |
| `POST /incoming` | Voice URL of an owned alias | `<Dial><Client>don</Client></Dial>` if `To` is owned |

Requests without a valid `X-Twilio-Signature` get 403. Request bodies are never logged.

```sh
export TWILIO_ACCOUNT_SID=AC... TWILIO_API_KEY_SID=SK... TWILIO_API_KEY_SECRET=...   # from your password manager
export TWILIO_AUTH_TOKEN=...                       # signature check
export SHIM_PUBLIC_URL=https://voice.example.net   # exact public https URL Twilio uses
python3 pc/voice_shim.py                           # listens on 127.0.0.1:8787
```

Expose it over https (your reverse proxy or a tunnel). Never commit these values.
No token server is needed: the app mints its own short-lived Voice access token.

Tests: `cd pc && python3 -m unittest test_voice_shim`.
