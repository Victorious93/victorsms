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

## Milestones

1. Scaffold (this PR): Compose shell, theme, icon, CI.
2. Matrix login + room list with the Matrix Rust SDK.
3. Bridged-network badges and the unified inbox.
4. EXTERNAL_NODE protocol v1 with external control off by default.
5. Bots and consent-gated offload.
