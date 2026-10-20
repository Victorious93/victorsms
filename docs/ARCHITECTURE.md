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

## Milestones

1. Scaffold (this PR): Compose shell, theme, icon, CI.
2. Matrix login + room list with the Matrix Rust SDK.
3. Bridged-network badges and the unified inbox.
4. EXTERNAL_NODE protocol v1 with external control off by default.
5. Bots and consent-gated offload.
