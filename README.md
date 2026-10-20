# VictorSMS

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
