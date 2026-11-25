<div align="center">

<img src="assets/logo.png" width="200" alt="VictorSMS logo"/>

# VictorSMS

**Matrix and native SMS/phone messaging for the Victorious fleet.**

<p>
  <a href="https://github.com/Victorious93/victorsms/actions/workflows/android.yml"><img src="https://img.shields.io/github/actions/workflow/status/Victorious93/victorsms/android.yml?branch=main&style=flat-square&labelColor=0B0F1A&logo=githubactions&logoColor=00E5FF&label=CI" alt="CI"/></a>
  <a href="https://github.com/Victorious93/victorsms/releases"><img src="https://img.shields.io/github/v/release/Victorious93/victorsms?include_prereleases&style=flat-square&labelColor=0B0F1A&color=00E5FF&label=release" alt="Release"/></a>
  <img src="https://img.shields.io/badge/license-Apache--2.0-FF2BD6?style=flat-square&labelColor=0B0F1A" alt="License"/>
  <img src="https://img.shields.io/badge/platform-Android-00E5FF?style=flat-square&labelColor=0B0F1A" alt="Platform"/>
  <a href="https://victorious-framework.pages.dev"><img src="https://img.shields.io/badge/Victorious-Framework-FF2BD6?style=flat-square&labelColor=0B0F1A" alt="Victorious Framework"/></a>
</p>

<p><a href="https://github.com/Victorious93/victorsms/releases">Releases</a> · <a href="https://victorious-framework.pages.dev">Victorious Framework</a></p>

</div>

---

Matrix and native SMS/phone messaging for the Victorious fleet: dialer, spam blocking, Twilio alias, persona assist, conversation archive and a droidcommand-AI fleet bridge.

> **Status:** CI-built and extensively unit-tested, but **not device-tested**. Debug-signed pre-releases only. Package id: `com.victorious.sms`.

## Features

See the table below for what is actually in the code. Highlights: multi-account Matrix shell, native SMS, a `ROLE_DIALER` dialer with call screening, Twilio alias numbers (SMS and calls), a mail client core (IMAP/SMTP, outbox, unified inbox), an opt-in conversation archive with an import wizard, and an optional paired bridge to droidcommand-AI.

## Install

Download the latest APK from [Releases](https://github.com/Victorious93/victorsms/releases) (currently **v0.2.0**, a debug-signed pre-release). Releases are published manually by the `Release` workflow, signed with the project's public debug key (never a release key), and are not run on a device or emulator before publishing. Debug builds are also uploaded by CI as an artifact. minSdk 26, targetSdk 36.

## Editions

| Edition | Build task | Contains | Binary licence |
|---|---|---|---|
| **standard** | `assembleStandardRelease` | Apache-2.0 sources, Twilio Voice SDK (alias calls), Firebase push; no SIP engine | Apache-2.0 for our code; Twilio Voice SDK under Twilio's terms |
| **voip** (`.voip` app id) | `assembleVoipRelease` | Same sources plus the Linphone SDK (own SIP server calling); no Twilio Voice SDK, no Firebase | GPLv3 ([LICENSE-GPL-3.0](LICENSE-GPL-3.0)) because it links Linphone |

The editions install side by side. The `standard` edition builds and is what releases ship. The `voip` edition is still a draft: per [docs/EDITIONS.md](docs/EDITIONS.md) it could not be built in the environment that wrote it, and CI only assembles it as an informational (non-blocking) job. Have the licences reviewed before publishing a voip binary.

## What is in the code

Under `app/src/main/java/com/victorious/sms/` (plus the `comms-core` and `mail-core` modules):

| Area | Package / files | What it does |
|------|-----------------|--------------|
| Matrix messaging | `matrix/` | Multi-account Matrix shell (Rust SDK), session store, timeline, bridge detector |
| Native SMS | `sms/` | Native SMS components + repository; unified inbox screens |
| Phone dialer | `phone/` | `ROLE_DIALER` keypad, `InCallService` + in-call screen, call log, caller lookup |
| Spam blocking | `block/` | Call screening service, blocklist, patterns, Spam folder UI |
| Twilio alias | `twilio/` | Encrypted creds, numbers / SIP domains, cost-confirmed buy, alias SMS, alias calls over the Twilio Voice SDK (`pc/voice_shim.py`) |
| Persona / assist | `assist/` | Persona drafter + voice notes with a consent gate (PC LLM/TTS over mesh — endpoints in `PcEndpoints.kt`) |
| Bots | `ui/screens/BotScreens.kt` | Summarizer / triage bot + droidcommand-AI fleet command bot |
| SMS remote trigger | `fleet/SmsRemoteTrigger.kt` | Whitelist + code word → `sms.remote_command.forward` (**forwards only; never executes**) |
| Phone-to-phone relay | `relay/` | Paired phones, encrypted relay node, remote calls with approve UI |
| Fleet bridge | `bridge/` | Pairing, node status, config backup, remote SMS with approve-send, replay guard, DCA wire |
| Conversation archive / import | `comms-core/` + `archive/` + `ui/screens/ImportScreens.kt` | Import wizard, saved conversations, Update, Undo, conflicts, delete, share target: **built and tested (JVM, Robolectric, Compose-on-JVM); never run on a device**. **Assistant connector:** droidcommand-AI can read, search and get reply context from conversations you switch on one by one (separate *Archive read* permission, off by default; built and tested, never run against the real app or on a device). New texts can optionally be saved too (off by default; the receiver and sender hooks have never run on a device); automation has no caller. See `docs/ARCHITECTURE.md` §24 |
| UI | `ui/screens/` | SMS, dialer settings, blocking, Twilio, bots, assistant, trigger, paired phones |

## Build from source

JDK 17 and the Android SDK.

```sh
git clone https://github.com/Victorious93/victorsms.git
cd victorsms
./gradlew assembleStandardDebug
```

## Tests

```sh
./gradlew :mail-core:test :comms-core:test :app:testStandardDebugUnitTest
```

`:comms-core` runs on any JDK 17+ with real SQLite (no device). The app tests need the Android SDK. The PC shim has its own tests under `pc/`.

## Permissions

Declared in the manifest: `SEND_SMS`, `RECEIVE_SMS`, `READ_SMS`, `RECEIVE_MMS`, `RECEIVE_WAP_PUSH`, `CALL_PHONE`, `READ_CALL_LOG`, `READ_CONTACTS`, `MANAGE_OWN_CALLS`, `RECORD_AUDIO`, `MODIFY_AUDIO_SETTINGS`, `POST_NOTIFICATIONS`, `FOREGROUND_SERVICE` (special use), `INTERNET`, `ACCESS_NETWORK_STATE` and `CHANGE_NETWORK_STATE`. To act as the default dialer or SMS app you grant the matching Android roles at runtime. No root is required.

## Optional PC AI helper

`pc/` contains a one-command setup for a PC-side helper that backs **Draft reply** and **Voice note** (LLM and voice over your own mesh network). It is written and statically checked but **has not been run on the real PC yet**. See [pc/README.md](pc/README.md). The app works without it.

## Signing

- **Debug:** a project-owned debug key at `app/debug.keystore` (alias `androiddebugkey`, store/key password `android`) gives identical signatures on every machine and in CI. Never use it for release.
- **Release:** keys are never committed. A release `signingConfig` is only created when `VICTORSMS_RELEASE_STORE_FILE`, `VICTORSMS_RELEASE_STORE_PASSWORD`, `VICTORSMS_RELEASE_KEY_ALIAS` and `VICTORSMS_RELEASE_KEY_PASSWORD` are all set; otherwise release builds are unsigned.

## Documentation

[Manual](docs/MANUAL.md) · [Architecture](docs/ARCHITECTURE.md) · [Bridges](docs/BRIDGES.md) · [Editions](docs/EDITIONS.md) · [Changelog](CHANGELOG.md)

---

## Victorious fleet

VictorSMS is part of the [Victorious Framework](https://victorious-framework.pages.dev), a family of Android and PC tools by Don Victor. **Every app works standalone; fleet integrations are optional** and never required.

Related: [droidcommand-AI](https://github.com/Victorious93/droidcommand-AI) (fleet controller), [VictorLaunch](https://github.com/Victorious93/victorlaunch) (fleet launcher and theme provider), [victorious-fleet](https://github.com/Victorious93/victorious-fleet) (index of all fleet apps). The full roster lives in [victorious-fleet](https://github.com/Victorious93/victorious-fleet), and live status is in [fleet-manifest](https://github.com/Victorious93/fleet-manifest).

## Contributing

Issues and pull requests are welcome. Keep changes focused, match the existing code style, never commit secrets or release keys, and keep any fleet integration optional so the app still works on its own. Open a PR against the default branch and wait for CI to pass.

## License

Apache-2.0 for the project sources; see [LICENSE](LICENSE). The optional **voip** edition links the Linphone SDK, so its distributed binary is GPLv3 ([LICENSE-GPL-3.0](LICENSE-GPL-3.0)). See [docs/EDITIONS.md](docs/EDITIONS.md).
