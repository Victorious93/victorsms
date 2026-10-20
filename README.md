<!-- victorious-fleet-readme-refresh:start -->
<p align="center">
  <img src="assets/logo.png" alt="VictorSMS logo" width="200">
</p>

# VictorSMS

Universal messaging app (Matrix-based) with voice cloning and persona learning.

**Package id:** `com.victorious.sms`

**Status:** scaffold / blocked *(from [fleet-manifest ROADMAP](https://github.com/Victorious93/fleet-manifest/blob/main/ROADMAP.md))*

## Install / build

Kotlin Gradle (`app/build.gradle.kts`). Typical: `./gradlew :app:assembleDebug`.

_Steps above are taken from this repo’s build files / existing docs; anything not exercised in this refresh is unlabeled as verified on a device._

## Victorious fleet

- Monorepo: [https://github.com/Victorious93/victorious-fleet](https://github.com/Victorious93/victorious-fleet)
- Fleet site: [https://victorious-framework.pages.dev](https://victorious-framework.pages.dev)

<!-- victorious-fleet-readme-refresh:end -->

Android, Matrix-based universal messenger (Kotlin + Jetpack Compose).

- Package / applicationId: `com.victorious.sms`
- minSdk 26, compileSdk/targetSdk 36
- Roadmap: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)

## Build

```sh
./gradlew assembleDebug
```

CI (`.github/workflows/android.yml`) runs the same on JDK 17 and uploads the debug APK as an artifact.

## Signing

- **Debug:** a project-owned debug key lives at `app/debug.keystore`
  (alias `androiddebugkey`, store/key password `android`). It's public on purpose so every
  machine and CI produce identically signed debug builds. Never use it for release.
- **Release:** keys are never committed. The release signingConfig is only created when these
  environment variables (or CI secrets) are all set:
  `VICTORSMS_RELEASE_STORE_FILE`, `VICTORSMS_RELEASE_STORE_PASSWORD`,
  `VICTORSMS_RELEASE_KEY_ALIAS`, `VICTORSMS_RELEASE_KEY_PASSWORD`.
  Otherwise release builds are unsigned.

## License

Apache-2.0. See [LICENSE](LICENSE).
