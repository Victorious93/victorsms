<!-- victorious-fleet-readme-refresh:start -->
<p align="center">
  <img src="assets/logo.png" alt="VictorSMS logo" width="200">
</p>

# VictorSMS

Matrix + native SMS/phone messaging for the Victorious fleet — dialer, spam blocking, Twilio alias, persona assist, and a DCA fleet bridge.

**Package id:** `com.victorious.sms`

**Status:** CI green / feature-rich scaffold *(not device-tested)* — see [fleet-manifest ROADMAP](https://github.com/Victorious93/fleet-manifest/blob/main/ROADMAP.md)

## Install / build

```sh
./gradlew assembleDebug
```

Kotlin + Jetpack Compose. minSdk 26, compileSdk/targetSdk 36. JDK 17. CI (`.github/workflows/android.yml`) builds the debug APK and uploads it as an artifact.

_Steps above are taken from this repo’s build files / existing docs; anything not exercised in this refresh is unlabeled as verified on a device._

## Victorious fleet

- Monorepo: [https://github.com/Victorious93/victorious-fleet](https://github.com/Victorious93/victorious-fleet)
- Fleet site: [https://victorious-framework.pages.dev](https://victorious-framework.pages.dev)
- Docs: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) · [docs/BRIDGES.md](docs/BRIDGES.md) · [docs/MANUAL.md](docs/MANUAL.md)

<!-- victorious-fleet-readme-refresh:end -->

## What is in the code (as of Oct 27, 2026)

Present under `app/src/main/java/com/victorious/sms/` (do not invent beyond this list):

| Area | Package / files | What it does |
|------|-----------------|--------------|
| Matrix messaging | `matrix/` | Multi-account Matrix shell (Rust SDK), session store, timeline, bridge detector |
| Native SMS | `sms/` | Native SMS components + repository; unified inbox screens |
| Phone dialer | `phone/` | `ROLE_DIALER` keypad, `InCallService` + in-call screen, call log, caller lookup |
| Spam blocking | `block/` | Call screening service, blocklist, patterns, Spam folder UI |
| Twilio alias | `twilio/` | Encrypted creds, numbers / SIP domains, cost-confirmed buy, alias SMS; **SIP calling is a stub** |
| Persona / assist | `assist/` | Persona drafter + voice notes with a consent gate (PC LLM/TTS over mesh — endpoints in `PcEndpoints.kt`) |
| Bots | `ui/screens/BotScreens.kt` | Summarizer / triage bot + droidcommand-AI fleet command bot |
| SMS remote trigger | `fleet/SmsRemoteTrigger.kt` | Whitelist + code word → `sms.remote_command.forward` (**forwards only; never executes**) |
| Phone-to-phone relay | `relay/` | Paired phones, encrypted relay node, remote calls with approve UI |
| Fleet bridge | `bridge/` | Pairing, node status, config backup, remote SMS with approve-send, replay guard, DCA wire |
| UI | `ui/screens/` | SMS, dialer settings, blocking, Twilio, bots, assistant, trigger, paired phones |

## Signing

- **Debug:** project-owned debug key at `app/debug.keystore` (alias `androiddebugkey`, store/key password `android`) so every machine and CI produce identically signed debug builds. Never use it for release.
- **Release:** keys are never committed. Release signingConfig is only created when `VICTORSMS_RELEASE_STORE_FILE`, `VICTORSMS_RELEASE_STORE_PASSWORD`, `VICTORSMS_RELEASE_KEY_ALIAS`, and `VICTORSMS_RELEASE_KEY_PASSWORD` are all set. Otherwise release builds are unsigned.

## License

Apache-2.0. See [LICENSE](LICENSE).
