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
- Code: `com.victorious.sms.bridge.ConfigBackup.kt` (`ConfigCapabilities`, `ForbiddenKeyScanner`,
  `ConfigExporter`, `ConfigImporter` skeleton).

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
- State is in memory; it is lost if the process dies (pending requests then read `not_found`).
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
- Pending requests are in memory only. The paired-phones list is in EncryptedSharedPreferences.

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

## 19. Twilio alias number + SIP (texts implemented; SIP calling stubbed; not device-tested)

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
    screen or the "Send from alias" checkbox in an SMS thread).
- **SIP calling: stub (`SipCalling`).** `android.net.sip` is still in SDK 37 but deprecated since
  API 31 and unsupported on most devices, so it is not used. Linphone and PJSIP are GPL and must
  not be linked into this Apache-2.0 app. Options for Don to choose:
  1. **baresip** (BSD-3-Clause) built with the NDK, registering to `<domain>.sip.twilio.com`;
  2. **Twilio Voice Android SDK** (proprietary Twilio licence, uses Twilio Voice instead of plain SIP);
  3. a SIP softphone on the PC over the mesh.
- Costs: alias texts, SIP minutes and numbers are billed by Twilio to Don's account.

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

## Milestones

1. Scaffold (this PR): Compose shell, theme, icon, CI.
2. Matrix login + room list with the Matrix Rust SDK.
3. Bridged-network badges and the unified inbox.
4. EXTERNAL_NODE protocol v1 with external control off by default.
5. Bots and consent-gated offload.
