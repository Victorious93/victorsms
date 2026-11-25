# VictorSMS Architecture and Roadmap

VictorSMS is an Android messenger that uses Matrix as its single internal transport. Every
other network (SMS/RCS, WhatsApp, Signal, Telegram, and so on) shows up as Matrix rooms through bridges.

## 1. Matrix shell

- Client core: the Matrix Rust SDK (Apache-2.0) via its Kotlin bindings, following Element X Android's approach.
- UI/UX patterns borrowed from **Element X Android** and **SchildiChat**
  (unified inbox, room list filters, bridged-network badges).
  - License note: Element X Android is **AGPL-3.0** (newer releases are AGPL-3.0 only, with a
    commercial option from Element). SchildiChat Next is a fork of Element X and inherits AGPL-3.0.
    Copying their *code* would make VictorSMS's distributed builds AGPL. While VictorSMS stays
    Apache-2.0, reuse **patterns and designs only**, and re-implement the code. If we decide to fork code, relicense the
    affected modules to AGPL-3.0 and record it in NOTICE.
  - The Matrix Rust SDK is Apache-2.0 and compatible.
- Every license must be checked again before any code is vendored.

## 2. mautrix bridges

- Server-side bridges on Don's homeserver: mautrix-whatsapp, -signal, -telegram, -meta, -gmessages, etc.
  (mautrix bridges are **AGPL-3.0**. They run as separate services and are not linked into the app.)
- The app treats bridged rooms as regular Matrix rooms and reads the bridge/network metadata
  (`m.bridge` / `uk.half-shot.bridge` state) to show network icons and capabilities.
- Bridge login/management goes through each bridge's bot room or provisioning API.

## 3. EXTERNAL_NODE bridge (Android Messenger)

A local, on-device bridge that lets an external node (for example a companion app or the mesh)
drive phone-native messaging (SMS/MMS) through VictorSMS.

- **Transport:** newline-delimited JSON (one JSON object per line, UTF-8).
- **Handshake:** the first message carries `"protocol_major": 1`. A peer with a different major
  is rejected; minor fields are additive.
- **Android entry point:** intent action `com.victorious.sms.action.BRIDGE`, guarded by a
  signature-level permission. Bound service/socket comes later.
- **External control is OFF by default.** The user has to enable it explicitly in settings, per
  client, and can revoke it at any time. Every action gets an audit log entry.
- **Proposed ops** (draft, subject to change):
  - `sms.list_threads` – list conversations (paged)
  - `sms.read_thread` – messages in one thread (paged)
  - `sms.send` – send SMS to one or more recipients
  - `sms.mark_read` – mark a thread read
  - `sms.subscribe` – stream incoming message events
  - `sms.status` – delivery/send status for a message id
- Example frame:
  ```json
  {"protocol_major":1,"id":"42","op":"sms.send","params":{"to":["+15551234567"],"body":"hi"}}
  ```

## 4. Persona / voice offload

- Heavy persona LLM and voice (STT/TTS) inference can be offloaded to **Don's ROCm PC** over the
  Victorious mesh.
- **Consent only:** offload is opt-in for each feature, nothing is sent without explicit consent, the UI
  shows clearly when offload is active, and if the PC is unreachable the app falls back to local processing or does nothing.
- Traffic stays on the encrypted mesh and never goes to third-party clouds by default.

## 5. In-app bots

Three built-in bots appear as Matrix rooms/users:

1. **Assistant bot:** persona chat, summaries and drafting replies (uses offload with consent).
2. **Bridge bot:** helps connect, monitor and troubleshoot mautrix and EXTERNAL_NODE bridges.
3. **Voice bot:** voice notes, transcription and read-aloud (STT/TTS through offload with consent).

## 6. Phone (planned)

- **Dialer role:** VictorSMS may request `ROLE_DIALER` so it can act as the default phone app
  (in-call UI via `InCallService`). Optional; SMS works without it.
- **Call screening + blocklist:** a `CallScreeningService` (via `ROLE_CALL_SCREENING`) checks
  incoming calls against a local blocklist and can silence, reject, or allow them. The blocklist
  lives on the device only.
- **Caller lookup order:** (1) on-device contacts and the local blocklist; (2) Don's PC over the
  encrypted Victorious mesh, if reachable; (3) an online lookup fallback, **opt-in only** and off
  by default. If a step is unavailable, the app moves on to the next one or shows the raw number.

## 7. In-app Help / Manual

- The app **must** include an in-app Help/Manual screen that shows `docs/MANUAL.md` content.
- **Keeping the manual current is part of done:** any PR that adds or changes a user-visible
  feature, permission or fleet behavior updates `docs/MANUAL.md` in the same PR.

## 8. Remote-command envelope (ratified)

Remote commands from droidcommand-AI use this envelope:

```json
{
  "requestId": "string",
  "targetNodeRef": "string",
  "actionRef": "string",
  "args": {},
  "nonce": "128-bit unique value",
  "timestampMs": 0,
  "ttlMs": 120000,
  "destructive": false,
  "trigger": { "sourceNumber": "...", "matchedKeyword": "...", "rawExtras": {} }
}
```

- `nonce` is 128-bit and unique per request (replay protection). `ttlMs` defaults to `120000`.
- `trigger` is **informational only** and is **not** used for authorization.
- SMS triggers need a whitelisted sender **and** the secret code word; VictorSMS then forwards to
  droidcommand-AI and never executes actions itself.

## 9. `node.status.get`

Spec: `Victorious93/droidcommand-AI` `docs/specs/STATUS_TRAY.md` (frozen contract).

- Advertised in `capabilities.get` as
  `{"name":"node.status.get","kind":"read","remoteAllowed":true,"requiresConfirm":false}`.
- Returns one `NodeStatusSnapshot`:
  `{ nodeId, packageName, connectionClass, updatedAtMs, domains: [{ name, state, detail? }] }`
  with `connectionClass = "EXTERNAL_NODE"`, `packageName = "com.victorious.sms"`.
- Domains: `sms` (ready to **deliver**, not authorization: `ok` when the default SMS role and
  SMS permissions are held, else `degraded`/`down`) and `node_health` (`ok` while the process answers).
- States: `ok | degraded | down | unknown | off`; `detail` max 200 chars.
- **Never** includes message bodies, phone numbers, the code word or the whitelist.
- Uses the same envelope and authorization (pairing, external-control toggle, replay) as remote control.
- Code: `com.victorious.sms.bridge` (`NodeStatusSnapshot`, `StatusDomain`, `NodeStatusProvider`,
  `NodeStatusCapability`). No live bind yet.

## 10. `config.export` / `config.import`

Spec: `Victorious93/droidcommand-AI` `docs/specs/UNIFIED_BACKUP.md` (DCA #236).

- Capabilities: `config.export` `{kind:"action", remoteAllowed:false, requiresConfirm:false}`;
  `config.import` `{kind:"action", remoteAllowed:false, requiresConfirm:true}`.
- Export entry: `{ packageName, schemaVersion, payload }` inside the controller's bundle (envelope `version` 1).
- **Never exported:** code word, sender whitelist, Matrix tokens, phone numbers, message bodies (also
  passwords, keys, cookies, API tokens). A forbidden-name scan (case-insensitive substrings, `_`/`-`
  ignored) refuses export **and** import with `invalid_parameters` instead of silently stripping.
- Import: `dryRun` (validate and report only), `conflictPolicy` `skip` (default) | `overwrite` |
  `merge-by-id`; requires on-device confirmation; **never** auto-enables `remote.command` / external control.
  Protected keys (`remote.command*`, `remote_command*`, `externalControlEnabled`) are stripped at every depth.
- `merge-by-id` (implemented): maps merge key by key; lists whose elements are all maps with an `id`
  merge element-wise (incoming wins per id, ids only on the device are kept, device order first, new
  ids appended); anything else is replaced by the incoming value.
- Code: `com.victorious.sms.bridge.ConfigBackup.kt` (`ConfigCapabilities`, `ForbiddenKeyScanner`,
  `ConfigExporter`, `ConfigImporter`). Policy logic is unit-tested; the live `config.*` ops and the
  confirm UI are still not wired.

## 11. Matrix client choice (implemented, not device-tested)

- **Library:** `org.matrix.rustcomponents:sdk-android` **26.10.15** from Maven Central (official
  Matrix Rust SDK Kotlin/UniFFI bindings, **Apache-2.0**). Pulls JNA (Apache-2.0 / LGPL-2.1 dual,
  used under Apache-2.0) and kotlinx-coroutines (Apache-2.0). The AAR is ~105 MB because it bundles
  native libraries for all ABIs; ABI splits are a follow-up.
- Chosen over a hand-written REST client because it resolved and gives E2EE, sliding sync and a
  timeline model for free.
- Sync uses native simplified sliding sync (`DISCOVER_NATIVE`). The homeserver must support it
  (recent Synapse / Conduit). Servers without it will log in but not sync.
- Other deps: `androidx.navigation:navigation-compose` 2.10.2, `androidx.security:security-crypto`
  1.1.0 (EncryptedSharedPreferences, key in Android Keystore), `kotlinx-coroutines-android` 1.11.0.
  All Apache-2.0.

### Multi-account

- Several Matrix accounts run at once. Each account has its own homeserver, its own encrypted
  session file (`matrix_session_<account>`, AES-256-GCM, Keystore master key) and its own SDK
  store directory. An encrypted `matrix_accounts` list keeps the ids and the current account.
- Tokens are never logged; login errors only show the exception type.
- The room list top bar has an account switcher with **Add account** and **Remove / log out**.
  Logging out affects only the current account and deletes only its session and store.

## 12. Fleet app pairing (EXTERNAL_NODE Messenger bridge)

- Exported `BridgeService` (action `com.victorious.sms.action.BRIDGE`) speaks `Messenger`. Fleet
  apps (e.g. `com.victorious.launch`, `com.victorious.mesh`, `com.victorious.matchvictorious`)
  connect here.
- **Identity:** `Message.sendingUid` → `PackageManager.getPackagesForUid` → signing cert SHA-256
  (`GET_SIGNING_CERTIFICATES`; multi-signer packages rejected). Never self-reported in the payload.
- **Registry:** encrypted `PairingRegistry` keyed by package + cert SHA-256. An unknown caller gets
  `not_paired` and is recorded as a pending entry so Don can review it. A different cert for the
  same package is a different (unapproved) entry.
- **Order of checks:** external-control switch (OFF by default → `external_control_disabled`) →
  paired + approved (`not_paired`) → op group granted (`permission_denied`) → sends always require
  on-device confirmation (`confirmation_required`).
- **Op groups:** `READ` (capabilities, `node.status.get`, thread list/read, status) and `SEND`
  (`sms.send`). Nothing is granted by default.
- **Settings → Connected apps:** approve or revoke each app individually, tick op groups, or forget it.
- Current state: authorization is real; op handlers return `not_implemented` and the send
  confirmation dialog is not built yet.

## 13. Remote `sms.send` (implemented, not device-tested)

- Capability: `sms.send` `{kind:"action", remoteAllowed:true, requiresConfirm:true}`;
  `sms.send.status` `{kind:"read", remoteAllowed:true, requiresConfirm:false}`. Both need the
  `SEND` op group.
- Request `params`: `{ to, body, requestId, nonce, timestampMs, ttlMs }`.
  - `nonce`: 128-bit, 32 hex chars, single use. A seen nonce → `replayed_nonce`.
  - `ttlMs`: default 120000, clamped to 1 s–10 min. Stale or future (>30 s skew) `timestampMs` → `expired`.
  - `to` must look like a phone number; `body` 1–1600 chars; duplicate `requestId` → `duplicate_request`.
- Gates (in order): external-control switch ON → paired + approved app (package + cert) → `SEND`
  group granted → valid envelope → **on-device approval**.
- Accepted requests return `{requestId, status:"pending"}` and post a high-priority notification.
  Tapping it opens `ApproveSendActivity` (not exported) with app, recipient and full body;
  **Approve** sends via `SmsManager`, **Deny** rejects. **Never auto-sent.**
- `sms.send.status {requestId}` → `pending | sent | denied | expired | failed`, only for the app
  that created the request (`not_found` otherwise). Unapproved requests become `expired` after `ttlMs`.
- Requests are persisted in EncryptedSharedPreferences (`pending_sms`, AES-256-GCM, Keystore master key)
  with their own expiry, so the approval notification still works after process death. Expired
  records are marked `expired` and purged `ttlMs + 600 s` later (on load and on every access). Nonces
  already seen are not persisted; a restored `requestId` still blocks duplicates.
- `sent` means handed to `SmsManager`; delivery reports are a follow-up.

## 14. Native SMS and unified inbox (implemented, not device-tested)

- **Default SMS role:** `RoleManager.ROLE_SMS` on Android 10+, `ACTION_CHANGE_DEFAULT` before.
- **Required components:** `SmsDeliverReceiver` (`SMS_DELIVER`, `BROADCAST_SMS`; writes to the
  Inbox provider), `MmsWapPushReceiver` (`WAP_PUSH_DELIVER`, `BROADCAST_WAP_PUSH`; stub, MMS
  download planned), `HeadlessSmsSendService` (`RESPOND_VIA_MESSAGE`,
  `SEND_RESPOND_VIA_MESSAGE`), `ComposeSmsActivity` (`SEND`/`SENDTO` for sms/smsto/mms/mmsto).
- **Read:** `SmsRepository` queries `Telephony.Sms` (latest row per `thread_id`, polled every 5 s).
- **Send:** `SmsManager.sendMultipartTextMessage`, then written to `Sms.Sent`. Sends are always
  user-initiated (Send button, respond-via-message, or an approved remote `sms.send`).
- **Unified inbox:** SMS threads plus Matrix rooms from every account, with a source badge.
  Bridged-network detection is a hero-user-id prefix heuristic (`BridgeDetector`); reading
  `m.bridge` state is planned. Matrix rooms are not yet ordered by recency.
- Bridges are self-hosted (AGPL-3.0, server-side only); see `docs/BRIDGES.md`.
- The fleet SMS trigger (whitelist + code word → droidcommand-AI) is still planned and is not in
  `SmsDeliverReceiver`.

## 15. Per-ABI APKs

- `splits.abi` builds `arm64-v8a`, `armeabi-v7a` and `x86_64` debug APKs, with `universalApk false`.
  CI uploads one artifact per ABI (`victorsms-debug-<abi>`).

## 16. Phone-to-phone relay (implemented, not device-tested)

Two phones running VictorSMS pair with each other. One can then ask the other to send an SMS or
place a call through its SIM, or receive a mirror of the other's incoming texts.

- **Identity:** an EC P-256 key generated in the **Android Keystore** (cannot be exported), with a
  self-signed certificate. A peer is identified by its certificate SHA-256 (pinned).
- **Transport:** **mutual TLS 1.3** (`javax.net.ssl`, no extra library), port **47320**. Both
  sides present their Keystore certs, and each side accepts only pinned peers. One JSON request
  line and one JSON reply line per connection:
  `{"protocol_major":1,"id","op","params":{requestId, nonce, timestampMs, ttlMs, ...}}`.
- **Pairing (numeric comparison):** phone B taps *Wait for pairing* (a 5-minute window in which
  unknown certs may complete TLS but may only call `relay.pair.hello`). Phone A enters B's mesh
  address and taps *Pair*. Both show a 6-digit code = SHA-256 over both cert fingerprints. Don
  confirms on **each** phone only if the codes match. That stops a man-in-the-middle, because an
  attacker's cert would produce a different code. QR exchange is planned (it would need a QR
  library such as ZXing, Apache-2.0).
- **Victorious Mesh integration point:** the relay only needs an IP-reachable `host:port`. Today it
  is typed in (the screen lists this phone's IPv4 addresses). Later, Victorious Mesh should provide
  the peer address (for example via a `mesh.peers` query or a Headscale MagicDNS name) and keep it
  updated. The pinned cert, not the address, is the identity, so a changed address is harmless.
- **Ops** (every one validates the fleet envelope: 128-bit nonce single use, `timestampMs`, `ttlMs`
  default 120000, via the shared `ReplayGuard`):
  - `relay.sms.send {to, body}` → approval screen on the receiving phone (reuses
    `ApproveSendActivity`) → sends via its SIM only on Approve. Poll with `relay.sms.send.status`.
  - `relay.sms.inbox {from, body}` → pushed **only** by a phone whose user enabled *Mirror my
    incoming texts* for that peer. Shown as a notification on the receiving phone.
  - `relay.call.place {to}` → approval screen (`ApproveCallActivity`) → on Approve, opens the system
    dialer pre-filled (`ACTION_DIAL`; no `CALL_PHONE` permission, so Don still taps call). Poll
    with `relay.call.status` (`pending | dialing | denied | expired`).
- **Permissions per paired phone** (Settings → Paired phones): *allow SMS*, *allow calls*,
  *mirror inbox*. All are **off** after pairing. *Revoke pairing* deletes the pin. The listener
  runs only while the *Phone relay* switch is on (a foreground service, `specialUse`).
- **Call audio bridging is a stub.** The call happens on the remote phone. Options for the
  future: (1) a Twilio SIP plugin (paid; audio leaves the mesh through Twilio); (2) WebRTC over
  the mesh, capturing the remote call audio. Android restricts call-audio capture for third-party
  apps, so this likely needs the default dialer role plus `InCallService` and is not guaranteed on
  every device.
- **Cert pinning after pairing (`PeerPin`):** every outbound request to a paired phone is pinned to
  that phone's own cert SHA-256 (constant-time compare). A different cert, even one belonging to
  another paired phone, fails the TLS handshake and is re-checked after it (`peer_cert_mismatch`).
  Only the first `relay.pair.hello` runs unpinned; non-hello ops without a pin are refused
  (`not_paired`). Re-pairing a different cert at the address of an already-paired phone is refused;
  revoke the old pairing first. Inbound: paired certs always, unknown certs only in the pairing window
  and only for `relay.pair.hello`.
- Pending relay SMS requests use `pending_sms` (above); pending calls use `pending_calls`
  (EncryptedSharedPreferences, same expiry rules). The paired-phones list is in EncryptedSharedPreferences.

## Unit tests (JVM, `app/src/test`)

`./gradlew testDebugUnitTest` covers: fleet envelope build/validation (nonce, `ttlMs` 120000,
timestamp window, `destructive`) and `ReplayGuard`; caller pairing by package + cert
(`PairingPolicy`); `ForbiddenKeyScanner`, import never enabling `remote.command`, merge-by-id;
`sms.remote_command.forward` gates (`SmsTriggerGate`: whitelist AND code word, off by default, code
word stripped); remote `sms.send` approval (never sent without Approve, exactly once, expiry, replay,
persistence); relay `PeerPin` and pending-call persistence; Twilio identity/`FromNumberGuard`; Twilio
Voice access tokens and the owned-number call plan (`VoiceTokenTest`). Android glue (Keystore, TLS sockets,
notifications, `SmsManager`) is not covered and is still not device-tested.

## 17. Spam call and text blocking (implemented, not device-tested)

- `Blocklist` (EncryptedSharedPreferences, local only): per-number action `BLOCK | SILENCE |
  ALLOW`, plus spam patterns. A pattern is either a number pattern (`*` = any digits, e.g. `+1900*`)
  or a text keyword.
- Decision order: explicit per-number entry (ALLOW wins over patterns) → number patterns → for
  SMS, keyword patterns → allow.
- Calls: `ScreeningService` (`CallScreeningService`, `BIND_SCREENING_SERVICE`, needs
  `ROLE_CALL_SCREENING`). BLOCK = disallow + reject, no notification (still in the call log);
  SILENCE = ring silently.
- Texts: in `SmsDeliverReceiver`, BLOCK → app-private **Spam folder** (`spam_sms.json`, max 500),
  not written to the system inbox and not mirrored; SILENCE → inbox, marked read.
- UI: Settings → Blocking & spam (role request, numbers, patterns), Settings → Spam folder
  (Not spam = move to inbox + allow sender; Delete), and **Block** in an SMS thread.
- No online spam database is used.

## 18. Phone dialer (implemented, not device-tested)

- **Role:** `ROLE_DIALER`. Requirements met: `DialerActivity` handles `DIAL` and `DIAL`/`VIEW tel:`;
  `VictorInCallService` (`BIND_INCALL_SERVICE`, `IN_CALL_SERVICE_UI` + `IN_CALL_SERVICE_RINGING`).
- **Keypad + recents:** `DialerActivity` (keypad, `TelecomManager.placeCall`, needs `CALL_PHONE`)
  and a call log from `CallLog.Calls` (`READ_CALL_LOG`).
- **In-call screen:** `InCallActivity` shows over the lock screen. It offers answer/decline, mute
  (`setMuted`), speaker/earpiece (`setAudioRoute`), hold/resume, a DTMF keypad and end. State comes
  from `CallManager` (one active call; call waiting/conference are not handled yet).
- **Caller lookup order (`CallerLookup`):**
  1. device contacts (`PhoneLookup`, `READ_CONTACTS`) and local lists (blocklist labels);
  2. Don's PC over the mesh: `GET {pcUrl}?number=…` → `{"name":"…"}`. The URL is configurable
     in Settings → Caller lookup and empty (off) by default. 2.5 s timeout. Stub contract only; no
     PC server ships with the app;
  3. an optional online provider (`OnlineLookupProvider`). **Off by default and none is
     configured.** A paid provider needs Don's approval before it is added.
- Cleartext HTTP is allowed (`usesCleartextTraffic`) so mesh endpoints like `http://100.64.x.x`
  work. The mesh is WireGuard-encrypted; use https for anything off the mesh.

## 19. Twilio alias number + SIP (implemented; not device-tested)

- This is a **real second number owned by Don** in his own Twilio account, not spoofing. Texts
  and calls carry that Twilio number as the sender.
- **Credentials:** Account SID, API key SID and API key secret in EncryptedSharedPreferences
  (`twilio_credentials`). They are never logged; errors show only Twilio's message. HTTPS with
  Basic auth (API key).
- **REST (`TwilioClient`):**
  - list numbers: `GET /IncomingPhoneNumbers.json`;
  - list SIP domains: `GET /SIP/Domains.json`;
  - create a SIP domain + credential list + credential, then map it for calls and registrations;
  - search: `GET /AvailablePhoneNumbers/US/Local.json`; price: Pricing API
    `/v1/PhoneNumbers/Countries/US` (local);
  - **buy:** `POST /IncomingPhoneNumbers.json`, called only from the explicit cost-confirmation
    dialog. Never automatic;
  - **alias SMS:** `POST /Messages.json` (`From` = chosen alias). User-initiated only (Twilio
    screen or the identity picker in an SMS thread, see 19a).
- **Calling: Twilio Voice Android SDK** (see 19b). `android.net.sip` is deprecated since API 31 and
  not used; Linphone and PJSIP (GPL) are not used.
- Costs: alias texts, SIP minutes and numbers are billed by Twilio to Don's account.

### 19b. Alias calls over Twilio Voice (implemented; unit-tested; not device-tested; Twilio not tested live)

- **Stack choice:** `com.twilio:voice-android` 6.10.5 (Maven Central). Licence per its POM:
  `TWILIO-TOS` (Twilio Terms of Service, https://www.twilio.com/legal/tos); bundled third-party
  code is listed in the artifact's `ACKNOWLEDGEMENTS.md`. It is proprietary but **not GPL**, so it
  can ship in this Apache-2.0 app as a separate dependency (it is not relicensed). baresip (BSD) via
  NDK was rejected as unrealistic here: no maintained Android build, own audio/ICE/TLS work, and
  Twilio SIP domains would still be needed. Push uses `com.google.firebase:firebase-messaging`
  (Apache-2.0) initialised at runtime from Don's own project settings (no `google-services.json`
  in the repo; auto-init and analytics disabled in the manifest).
- **Tokens:** `VoiceToken` mints the Twilio access token (JWT HS256, `cty twilio-fpa;v=1`, Voice
  grant: incoming allowed, `outgoing.application_sid`, `push_credential_sid`, 1 h TTL) on the phone
  from the API key already stored encrypted for REST. So **no token server** is needed. Trade-off:
  Twilio recommends server-side minting; here the phone already holds the key, so nothing new is
  exposed. A scoped key for the phone is a possible later hardening.
- **Caller ID / no spoofing:** `VoiceCalling.placeCall` fetches
  `IncomingPhoneNumbers` live, `VoiceCallPlan.outbound` runs `FromNumberGuard` and E.164 checks,
  `TwilioConnectionService` re-checks against that snapshot, and `pc/voice_shim.py /voice`
  re-checks against its own live list before returning `<Dial callerId=alias>`. `/incoming` only
  rings `<Client>don</Client>` when `To` is an owned number. Both endpoints require a valid
  `X-Twilio-Signature`.
- **Telecom:** `TwilioConnectionService` registers PhoneAccount `twilio-alias`
  (`CAPABILITY_CALL_PROVIDER`). Outgoing: `TelecomManager.placeCall` → `Voice.connect`. Incoming:
  FCM → `VoicePushService` → `Voice.handleMessage` → `addNewIncomingCall` → `CallInvite.accept` on
  answer. Hold/mute/DTMF/disconnect map onto the Twilio `Call`; the existing `VictorInCallService`
  and `InCallActivity` render it.
- **Paid provisioning is never automatic:** SIP domain, TwiML App and number Voice URL changes each
  sit behind a confirmation dialog stating the price (or that Twilio exposes no price API and where
  to look); every call shows the Pricing API per-minute rate first. Push credentials and Firebase are
  created by Don in the consoles, not by the app.
- **Tests:** `VoiceTokenTest` (JWT structure, HMAC signature, input checks, owned-number-only plan),
  `pc/test_voice_shim.py` (TwiML, spoof refusal, incoming gating, signature check).
- **Unverified:** no live Twilio call, no device test, no FCM push received, Telecom behaviour of a
  managed call-provider account on specific OEMs, and audio routing between Telecom and the SDK.

### 19a. Second number / identity picker (implemented; unit-tested; not device-tested; Twilio not tested live)

- **No spoofing, ever.** Every alias send or call first calls `TwilioClient.requireOwned(from)`,
  which fetches `GET /IncomingPhoneNumbers.json` and runs `FromNumberGuard.check` (pure Kotlin):
  the from-number must be valid E.164 and in that freshly fetched owned list, else
  `NotOwnedNumberException`. Unit tests: `FromNumberGuardTest`.
- **Identity model** (`twilio/Identity.kt`): `Identity.Sim | Identity.Alias(number)`, stored as
  `sim` / `alias:+1…`. `IdentityResolver.resolve(threadChoice, default, aliases)`: thread override
  → default → SIM; an alias no longer configured falls back (never sends from an unknown number).
  Tests: `IdentityTest`.
- **Storage:** `TwilioCredentials` (EncryptedSharedPreferences `twilio_credentials`) now holds
  `aliases` (set; the old single `alias` key is migrated), `myPhone`, `identity.default` and
  `identity.thread.<normalized address>`.
- **Outbound SMS/MMS:** `sendMessage(from, to, body, mediaUrl?)` → `POST /Messages.json`
  (`MediaUrl` only for public https URLs). The composer shows the identity in use and requires a
  confirmation dialog with the price from Pricing `GET /v1/Messaging/Countries/US`.
- **Masked call (no-SIP fallback):** `bridgeCall(alias, myPhone, callee)` → `POST /Calls.json`
  with `From`=alias, `To`=Don's phone, inline `Twiml` =
  `<Dial callerId="alias"><Number>callee</Number></Dial>` (XML-escaped). Caller ID is an owned
  Twilio number, which Twilio permits. Confirmation dialog shows Pricing
  `GET /v2/Voice/Numbers/{callee}` per-minute price; two legs are billed.
- **Inbound:** polling (no webhook server): inbox every 60 s `GET /Messages.json?To=alias`, grouped
  by (alias, peer) as `InboxEntry.Alias` with an "Alias …1234" badge and an All/SIM/Alias filter.
  A thread fetches both directions for that peer every 30 s and merges them into the SIM thread.
  Only the last 50 messages per query; MMS media is shown as a count only.
- **Logging:** no `Log`/`println` in Twilio code; UI shows only Twilio's error message and the last
  4 digits of an alias.
- **Compliance:** US A2P 10DLC registration (Brand + Campaign, or Sole Proprietor) is required
  for 10DLC texting; toll-free needs verification. STOP/HELP handled by Twilio Advanced Opt-Out by
  default. The app does not register anything or buy anything automatically.
- **Not done / limits:** no push for inbound (polling only), no local MMS attachment upload
  (needs a public URL), message history is not written to the Telephony provider, no SIP.

## 20. Persona drafter + voice notes (implemented, not device-tested; PC side not included)

- **PC endpoints** (`PcSettings`, all empty = off; reached over the mesh):
  - LLM: Ollama-compatible `POST {llmUrl}/api/chat {model, messages, stream:false}` →
    `message.content` (for example Ollama on Don's ROCm PC). The model name is configurable.
  - Voice: `POST {ttsUrl} {"text","voice"}` → audio bytes (wav, or mp3 by content type). This is
    **our contract**: Chatterbox (MIT) has no standard HTTP API, so the PC needs a small wrapper.
- **Persona import** (`PersonaStore`): SAF picker for `.md`/`.txt`/`.json`/`.csv`, or a `.zip`
  containing them (path-traversal entries skipped). App-private `persona.txt`, capped at 200 KB;
  the last 24 KB goes into the prompt.
- **Draft reply** (`Drafter`): system prompt = persona + samples; user prompt = the last 20
  messages. The result is **placed in the composer only**. Don reviews, edits and taps Send.
  **No autosend.** Shown in SMS threads and Matrix rooms.
- **Consent gate** (`VoiceRegistry`): a voice can be added only with a declaration, either
  "my own voice" or "someone else who consented" (who + how/when required). Voice notes can only
  use registered voices (currently the first one). Removing a voice removes it from the app.
  The reference audio lives on the PC.
- **Voice notes:** synthesized from the current draft into `files/voice_notes/`, played locally
  and shared by Don through the Android share sheet (`FileProvider`). Sending them automatically
  as MMS or Matrix audio is not built.
- Privacy: tapping Draft sends the persona text and the last 20 messages of that thread to Don's
  PC URL. Nothing is sent otherwise.

## 21. Assistant bots: summarizer + fleet (implemented, not device-tested)

- Two always-pinned inbox rows: **Summarizer / triage** and **Fleet (droidcommand-AI)**.
- **Summarizer:** in-app chat. Each message is sent to the PC LLM (`PcLlm` /
  Settings → Assistant & persona). The system prompt asks for a summary, action items and
  urgency flags. Same endpoint as the persona drafter.
- **Fleet bot:** in-app chat. Format: `<actionRef> [json-args]`. Calls droidcommand-AI over
  EXTERNAL_NODE (`DcaClient`):
  - Action `ai.droidcommand.app.action.BRIDGE`, package `ai.droidcommand.app`
    (queried in the manifest).
  - Wire: MessengerWire (`what=1` Bundle `request` / `what=2` Bundle `response`,
    snake_case `BridgeRequest`: `protocol_major`, `request_id`, `target_node_id`,
    `operation`, `parameters`).
  - Operation: `remote.command` with frozen envelope parameters (`targetNodeRef`,
    `actionRef`, `args`, `nonce`, `timestampMs`, `ttlMs`, `destructive`).
  - **Local gates:** External control ON, and `ai.droidcommand.app` approved (package +
    cert) in Connected apps. The reply's `sendingUid` must belong to that package.
  - VictorSMS **never executes**; DCA decides and may ask for confirmation.
- This node's id is `victorsms-<32 hex>` (`NodeId`), generated once and stored locally.
- **Bridge inbound dual-wire:** `BridgeService` still accepts the legacy `frame` Bundle and
  also the DCA `request`/`response` keys, so either side can talk.
- Honest limit: if droidcommand-AI's inbound receiver is not exported/live yet (the DCA
  docs still mark that as pending), the fleet bot reports that the connection was refused.

## 22. SMS remote-command trigger (implemented, not device-tested)

- Ratified contract (`droidcommand-AI` `docs/specs/REMOTE_CONTROL.md`): inbound SMS from a
  **whitelisted sender AND containing the user-set code word** → VictorSMS forwards
  `sms.remote_command.forward` to droidcommand-AI. **VictorSMS never executes.**
- **Settings** (`SmsRemoteTriggerSettings`, EncryptedSharedPreferences `sms_remote_trigger`):
  enable switch (**OFF by default**), code word, sender whitelist. Empty code word or empty
  whitelist → nothing matches. Code-word match is a case-sensitive substring of the body.
- **Forward** (`SmsRemoteTrigger.maybeForward`, called from `SmsDeliverReceiver` on a
  background thread after spam filtering): gates through `DcaClient` (External control ON +
  droidcommand-AI approved). Envelope: frozen parameters + informational
  `trigger:{sourceNumber, matchedKeyword:true, rawExtras:{nodeId}}`. The code word is stripped
  from the command text before forwarding. Status strings never include the code word, whitelist
  or body.
- **Secrets:** the code word and whitelist are never logged and are covered by the
  `ForbiddenKeyScanner` (export/import refuse). They are not part of `config.export`.
- Live DCA inbound for `sms.remote_command.forward` may still be pending on the DCA side; a
  refused connection is reported without leaking secrets.
## 23. Fleet theme client (implemented, not device-tested)

Contract: VictorLaunch `docs/specs/FLEET_THEME.md`, **schemaVersion 1**. Code:
`ui/theme/FleetThemeClient.kt` (`FleetThemeCodec`, `FleetThemeClient`, `FleetThemeChangedReceiver`)
and `ui/theme/Theme.kt`.

- **Read path:** `VictorSMSTheme` calls `FleetThemeClient.start()` (idempotent) which queries
  `content://com.victorious.launch.theme/current` on a background executor and registers a
  `ContentObserver` on that URI. An exported receiver for
  `com.victorious.launch.action.FLEET_THEME_CHANGED` triggers the same re-query. Broadcast extras
  are a wake-up only and are never trusted.
- **Provider pinning:** before querying, the authority must resolve to package
  `com.victorious.launch` and that package's signing-cert SHA-256 must be in `PINNED_CERTS`
  (spec "current" debug cert) and not in `REVOKED_CERTS`. "next" and "release" are unpublished, so
  nothing else authorizes. Multi-signer packages are refused.
- **Parsing:** `json` column; `schemaVersion` column and JSON must both equal 1, otherwise rejected.
  Palette is applied **per key**: an absent/blank/invalid hex keeps the neon default for its roles.
  Unknown fields are ignored.
- **Mapping (matches VictorLaunch):** background → background, onPrimary, onSecondary, onError;
  surface → surface; primary → primary; secondary → secondary; onBackground → onBackground,
  onSurface; muted → onSurfaceVariant, outline; danger → error; tile → surfaceVariant; stale is
  parsed but not mapped to an M3 role.
- **Failure:** `SecurityException` (our package/cert not pinned by VictorLaunch) or any other
  error publishes `FleetPalette.NONE` → built-in neon scheme.
- **Never writes:** no insert/update/delete calls exist.
- **Tests:** `app/src/test/.../FleetThemeCodecTest.kt` covers hex parsing, schema rejection, per-key
  fallback, and cert pin/revocation logic. Provider/receiver behaviour is not device-tested.

## 24. Conversation archive, import and automation gate — `comms-core` + Android glue (Victorious Framework)

Added 2026-11-24. New pure Kotlin/JVM module `comms-core` (package `com.victorious.comms`, no `android.*`). **Status: BUILT and TESTED
(59 JVM tests against real SQLite at first; 74 with the assistant connector below). The import side is now wired into `:app`** (see "Android glue" below): Settings → *Saved conversations
(import)*. **Never run on a device or emulator** (none was available; verification is JVM, Robolectric and CI compile). **Live texts are recorded only if the owner switches that on** (opt-in, off by default; see "Live recording" below); the
automation gate and outbox still have no caller. VictorSMS is the authoritative owner of communication history; droidcommand-AI only retrieves
budgeted context from it, and only from conversations the owner switched on (see "Assistant connector").

- **Schema** (`Schema.v1` + `Schema.v2`, `PRAGMA user_version`; a newer database is refused; a fresh database runs both, so the upgrade path is always exercised): conversations, participants, messages (content fingerprint
  without timestamp, `origin` import|live, `author` owner|peer|bot_*), imports (history, hashes, counts), message_sources (per-message
  provenance), revisions, conflicts, facts (approved only are used), summaries, auto_rules, outbox, settings; FTS4 `unicode61` index.
- **Importers** (`Importers.all`; add a format by adding one class): CSV, JSON, SMS Backup XML (XXE-hardened), WhatsApp-style chat logs, pasted
  text, ZIP (in memory; entry and size limits). **HTML exports are unsupported. There is no TextNow-specific parser**: TextNow documents Export
  Conversations on Android only and not its file format; supply a CSV/JSON/text export and map columns, or send us a redacted sample.
  Ambiguous day/month order and assumed time zones are reported as warnings, never silently resolved.
- **Incremental update** (`ImportEngine`): preview writes nothing; commit is one transaction. Matching order: stable provider id (a changed body
  becomes an open *conflict*, never an overwrite) then content fingerprint plus time proximity, paired one-to-one so legitimate repeated texts
  ("ok") are neither collapsed nor duplicated. Timestamp precision differences between exports are tolerated. A constant time-zone offset between
  exports is *suggested* in the preview and applied only if the owner passes it. Messages missing from a newer export are never deleted. The
  original file is stored content-addressed and separately. Rollback removes only messages whose sole provenance is that import.
- **Historical import never triggers automation**: imported rows are `origin='import'`; `AutomationGate` and `Outbox.enqueueReply` both refuse them.
- **Automation gate** (`AutomationGate`, `Outbox`): rules default to Approval mode and disabled; one active bot per conversation (unique index);
  emergency stop; operating hours (may wrap midnight); hourly cap; minimum gap (defers); consecutive-auto-reply loop guard; flood guard; refuses
  to answer auto-responders; disclosure once a day. The outbox is idempotent per (trigger message, bot), `markSending` is an atomic
  compare-and-set, and a send in flight at a restart becomes `UNKNOWN` and is never resent.
- **Retrieval/backup/deletion**: `ContextRetriever` returns recent turns, FTS-relevant older turns, approved facts, latest summary and the
  owner's own examples under a hard character budget. Backup is AES-256-GCM with a PBKDF2-HMAC-SHA256 key; wrong passphrase or tampering changes
  nothing. Deleting a conversation removes messages, index entries and (optionally) facts, summaries and original files.
- **Android glue** (`app/.../archive`, `ui/screens/ImportScreens.kt`): `AndroidSqlDb` adapts `SQLiteDatabase` to `SqlDb` (owns `user_version`, typed
  writes, thread-correct nested transactions); `ArchiveRuntime` opens the single archive in app-private storage (`allowBackup` is false app-wide);
  `ImportSession` turns the owner's choices into an `ImportRequest` (file size capped at 64 MB, read lazily off the main thread); screens: saved
  conversations list, import wizard (preview, who-is-me, provider/account, date order, column mapping, manual conversation choice, time-shift
  opt-in), conversation view with **Update conversation**, search (every word), import history with **Undo**, conflict resolution, and delete with
  per-item choices. **Share target:** a shared *file* (WhatsApp "Export chat" shares `text/plain`; CSV, JSON, ZIP, XML) opens the wizard; shared
  *text only* now opens a new SMS with that text. Nothing is read into the archive until the owner confirms the preview.
- **Android verification actually run:** 17 tests: `AndroidArchiveTest` (9, Android's SQLite code via Robolectric native mode, API 34: import,
  update, FTS4 search, rollback, conflicts, partial unique index, typed writes, gate/outbox, encrypted backup, deletion), `ImportSessionTest` (5),
  `ImportScreensTest` (3, Compose rendered on the JVM). **These are not an emulator or device run.** Writing the UI tests exposed one real defect,
  now fixed: only the checkbox, not its label, was tappable.
- **Assistant connector (read-only, schema v2; Victorious Framework, added 2026-11-24c).** Status: **Implemented, Built, Tested (JVM + Robolectric); NEVER RUN on a
  device or against the real droidcommand-AI.** droidcommand-AI can ask this app for saved-conversation text over the existing EXTERNAL_NODE Messenger bridge.
  Two gates, both the owner's, both off by default:
  1. **Op group `ARCHIVE_READ`** (`OpGroup`, granted per app under Settings → Connected apps, behind External control and app approval). It is separate from
     `READ` because message text crosses the bridge. It opens only the five ops below; it does not open `sms.send` or anything else.
  2. **Per-conversation switch** `conversations.assistant_access` (default 0 for every existing and new conversation; an upgrade and a restore of an older backup
     leave everything off): *Let my assistant (droidcommand-AI) read this conversation* on the conversation screen. A conversation that is not switched on answers
     exactly like one that does not exist (`not_found`), so existence cannot be probed.
  Ops (`ArchiveWire`/`ArchiveOps` in `comms-core`, strict parameters): `comms.capabilities`, `comms.conversations.list`, `comms.conversation.read` (cursor paging that never
  splits a run of one timestamp), `comms.messages.search` (every word, then any word, reported as `match`), `comms.context.retrieve` (the same `ContextRetriever`
  budget used for replies). Limits: 200 rows, 4,000 characters per body (clipped, flagged), 45,000 serialized characters of messages per answer (the other side
  refuses over 60,000). No phone number or account is returned in a list. Nothing writes, sends or deletes.
  **Transparency:** every request except `comms.capabilities` writes one `access_log` row (app, operation, conversation id, count, outcome, time; never text). The
  conversation screen shows the latest five; the log keeps 500 rows, is not backed up, and is removed with the conversation. The conversation list marks shared ones.
  **Threading:** archive ops run on a single worker thread and answer asynchronously; every other op still answers on the main thread as before.
  **Wire fix (`DcaFrames`):** replies on the droidcommand-AI wire now carry `node_id` (an echo of the request's `target_node_id`, for correlation only, **not** an
  identity proof: identity is `Message.sendingUid` -> package + signing certificate) and errors as `{category, message, code}`. Before this, that app's strict
  validator rejected every VictorSMS reply (missing `node_id`; undecodable error body), so the existing `sms.send` path could not have validated either. The reply
  frames are pinned as literal copies in `ArchiveBridgeTest`/`DcaFramesTest` and in droidcommand-AI's `VictorSmsFramesContractTest`; change one, copy to the other.
  **Run here:** `comms-core` 74 tests (15 new, real SQLite; 12 deliberate mutations all caught after one test was strengthened), `:app` 125 tests (17 new:
  `ArchiveBridgeTest` 6, `DcaFramesTest` 6, 2 pairing-policy cases, 3 `ImportScreensTest`; 6 mutations all caught), `assembleStandardDebug` (three ABIs).
  **Not verified:** a real bind from droidcommand-AI, the worker-thread reply path on a device, access-log display on a real screen.
- **Status and capabilities reads (fix, 2026-11-24e).** droidcommand-AI's *Check paired apps* and status tray ask VictorSMS for `sms.status` and
  `sms.capabilities.get`. Before this fix `sms.status` and `node.status.get` passed authorization (group `READ`) and then fell through the dispatcher to
  `not_implemented` (`NodeStatusProvider` was written but never called), and `sms.capabilities.get` was not authorized at all, so a correctly paired and approved
  VictorSMS looked unhealthy. `BridgeReads` now defines these ops once and both the authorizer and the dispatcher use it, so an allowed read is always answered;
  `NodeStatusSnapshot.toJson()` is states only (no text, numbers, code word or whitelist). Not touched: `sms.list_threads` / `sms.read_thread` are still
  authorized and still answer `not_implemented`; the READ permission label now says so. **Tested:** `NodeStatusTest` (5, Robolectric; 4 mutations caught). The
  service dispatcher itself is not run in a test.
- **Live recording (opt-in; Victorious Framework, added 2026-11-24d).** Status: **Implemented, Built, Tested on the JVM and under Robolectric; NEVER RUN on a
  device, and the receiver and sender hook sites themselves have not been exercised** (they need a real telephony stack). Settings → Saved conversations → *Also save
  new texts here as they arrive and are sent*, **off by default**. When on, `SmsDeliverReceiver` (after the text is stored and the fleet trigger started),
  `SmsRepository.send` and the on-device-approved remote send call `LiveArchive`, which writes through `LiveIngest` in `comms-core`.
  - *Same conversation as an import:* provider `sms`, no account, other party keyed by `Keys.participant(address)`, so a later SMS Backup import **merges** instead of
    duplicating (tested: 4 recorded texts, then a backup of 7 -> 4 matched, 3 added, one conversation; recorded texts stay `live`). The SMS Backup importer's provider
    guess changed from `sms-backup` (a file format) to `sms`; conversations already saved under the old label are still found by both live recording and new imports.
  - *Never delays or fails delivery:* background thread, `goAsync()` held only until the write finishes, every failure swallowed.
  - *Recording is not automation:* no rule, no outbox row, no reply; a new conversation is **not** shared with any assistant app (that is still the owner's
    per-conversation switch, off by default). Nothing here calls `AutomationGate`, so the "never auto-sent" rule is untouched.
  - *Secrets and spam:* a text containing the fleet code word is never recorded, in either direction; if the code word cannot be read, nothing is recorded (fail
    closed). Spam the blocklist stops never reaches this code.
  - *Authorship:* `SmsRepository.send` takes a mandatory `Author`. The in-app composer and the quick-reply service record `OWNER`; PC drafts the owner approved and
    remote sends approved on the device record `BOT_APPROVED`, which can never teach the owner's writing style. The author at each call site was checked by reading,
    not by a test. Twilio alias sends and MMS are not recorded.
  - *Limits:* "sent" means handed to the phone's SMS sender (delivery status is not tracked, same as the system Sent box); received MMS, group texts and attachments are
    not recorded; texts already in the phone's SMS database are not back-filled (import an SMS Backup for history); there is no de-duplication beyond what imports do.
  - *Privacy:* once on, one-time codes and other private texts also land in the archive's SQLite file (app-private, not separately encrypted). An assistant app can read
    only conversations you switch on, one by one.
  - **Run here:** `LiveIngestTest` 10 (real SQLite; 8 mutations all caught), `LiveArchiveTest` 7 and the new `ImportScreensTest` switch case (Robolectric). Full suites:
    `mail-core` 19, `comms-core` 84, `:app` 133, all passing; `assembleStandardDebug` builds three ABIs. App-layer mutation checks: 6 mutations of the hook and the switch, all caught; one ("recording on by default") survived at first because the tests forced the setting off instead of using the shipped default, and the setup now resets stored state instead.
- **Not at-rest encrypted**: the SQLite file is app-private storage like the rest of the app. Not built: embeddings, scheduling, voice.
- **Reverses a documented rule on purpose, but only behind owner action:** §13/§20 say remote and drafted sends are "never auto-sent".
  `AutomationGate` can return `AutoSend` only for a rule the owner enabled for one bot in one conversation. Nothing calls it yet (no live-message ingest exists).

## Milestones

1. Scaffold (this PR): Compose shell, theme, icon, CI.
2. Matrix login + room list with the Matrix Rust SDK.
3. Bridged-network badges and the unified inbox.
4. EXTERNAL_NODE protocol v1 with external control off by default.
5. Bots and consent-gated offload.

- **VoIP certificate pin and archive capabilities (2026-11-25).** `CertPin` (shared sources, pure JVM) reads the chain the SIP server presents and accepts it only if some
  certificate's SHA-256 (DER or SPKI) equals `SipConfig.serverCertSha256`; every other outcome (no chain, mismatch, bad pin, handshake error, timeout) refuses. `LinphoneSipEngine`
  runs it off the main thread before registering and gives Linphone only the pinned chain as `rootCa`/`rootCaData` (CN check off; the pin identifies the server), so Linphone itself
  rejects any other certificate. Refusal text is fixed and never contains the pin. `capabilities.get` / `sms.capabilities.get` now also list the five `comms.*` archive ops (kind
  `read`, no confirm tap); listing is not access, the `ARCHIVE_READ` grant and the per-conversation switch still apply. Not run on a device.

- **Scheduled messages and provider capabilities (Victorious Framework, 2026-11-25; schema v3).** Status: **core Implemented and JVM-tested (12 tests); NOT wired into `:app`; never run on a device.**
  `Providers` (`Providers.kt`) says which provider labels can send; unknown labels can do nothing. `Scheduler` (`Scheduler.kt`) is storage and state for owner-typed messages: PENDING -> SENDING ->
  SENT|FAILED|UNKNOWN, or CANCELLED|MISSED. Scheduling checks the provider, the length, a 10-second minimum lead, a one-year maximum and a cap of 200 pending. `claimDue()` marks anything more
  than 15 minutes overdue MISSED instead of returning it, re-checks the provider, and hands out the rest as SENDING. `recoverAfterRestart()` turns SENDING into UNKNOWN (never resent);
  `resolveUnknown()` records what the owner says happened. This is not automation: no `AutomationGate`, no bot rules. **Still to build (needs the Android SDK):** an `AlarmManager` trigger
  (exact-alarm permission), a sender that calls `claimDue()` then `markSent`/`markFailed` and records the sent text through `LiveArchive`, and a screen. Scheduled rows are not part of the
  encrypted backup, on purpose (a restore must not send old messages).

- **Scheduled texts on Android (2026-11-25b).** Status: **engine Implemented and Robolectric-tested (8 tests, fake sender); NO screen; never run on a device; a real alarm has never fired.**
  `ScheduledSms` (`sms/ScheduledSms.kt`) wraps `Scheduler`: `schedule`/`cancel`/`list`, `runDue` (once per process it turns an interrupted SENDING into UNKNOWN, then claims what is due, resolves the
  number with `Archive.smsRecipient`, sends through `SmsRepository.send(..., Author.OWNER)`, and records SENT or FAILED) and `arm` (one inexact alarm for the earliest pending message).
  `ScheduledSmsReceiver` (not exported) handles the alarm, `BOOT_COMPLETED` and `MY_PACKAGE_REPLACED`; `VictorApp` also re-arms at start, but only if the archive already exists. Because the alarm is
  inexact, Android may deliver it late; past the 15-minute grace the text is MISSED and the owner sees it. `SmsRepository.send` returns false on any exception, including one after the radio accepted the
  text, so FAILED here means "check the thread before sending again", not "definitely not sent". **Still to build:** a screen to write, list and cancel scheduled texts, and a visible place for MISSED/UNKNOWN items.

