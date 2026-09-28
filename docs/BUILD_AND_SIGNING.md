# Builds and signing

The package ID is `com.yazsras.astravar`. Debug is `com.yazsras.astravar.debug` and has a different signing identity. JSON export/import is the explicit way to move confirmed state between them; no silent data migration occurs.

Use JDK 17 and SDK platform 36 / build tools 35.0.0. Set `ANDROID_HOME` or a local untracked `local.properties`. Run the included genuine Gradle wrapper. Its pinned distribution checksum is `20f1b1176237254a6fc204d8434196fa11a4cfb387567519c61556e8710aed78`.

```sh
timeout -k 10s 540s ./gradlew --no-daemon :rules:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
timeout -k 10s 180s ./gradlew --no-daemon :overlay-target:assembleDebug :overlay-target:assembleDebugAndroidTest
timeout -k 5s 35s adb install app/build/outputs/apk/debug/app-debug.apk
timeout -k 10s 300s ./gradlew --no-daemon :overlay-target:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.astravarPackage=com.yazsras.astravar.debug
```

Install the controlled test APK on the emulator before the connected test. The instrumented test seeds only a labeled demo fixture. Never run fixture seeding against a real campaign without a backup.

## Durable owner release identity

A distinct RSA-4096 PKCS12 release identity was created for this project. Its encrypted keystore and recovery configuration are preserved in an owner-private recovery artifact, not the repository, public build artifacts, or chat text. Reuse that exact identity for every update. Do not generate a replacement when secrets are missing. The final test report records the public certificate SHA-256 and APK SHA-256.

Secure environment variables:

| Variable | Purpose |
|---|---|
| `ASTRAVAR_KEYSTORE` | Private local path to the recovered PKCS12 file |
| `ASTRAVAR_STORE_PASSWORD` | Keystore password from private recovery |
| `ASTRAVAR_KEY_PASSWORD` | Key password from private recovery |
| `ASTRAVAR_KEY_ALIAS` | `astravar` |
| `ASTRAVAR_VERSION_CODE` | Positive integer, increase for every delivered update |
| `ASTRAVAR_VERSION_NAME` | Human-readable release version |

```sh
timeout -k 10s 540s ./gradlew --no-daemon :app:assembleRelease :app:testReleaseUnitTest :app:lintRelease
timeout -k 10s 180s ./gradlew --no-daemon :overlay-target:assembleDebug :overlay-target:assembleDebugAndroidTest
timeout -k 5s 35s adb install app/build/outputs/apk/release/app-release.apk
timeout -k 10s 300s ./gradlew --no-daemon :overlay-target:connectedDebugAndroidTest
```

The release is non-debuggable and minified. Packaging fails if signing variables are absent. Black-box instrumentation runs in the independent `overlay-target` process against the separately installed release APK. It does not inject a test runner into the minified product. The default tested package is `com.yazsras.astravar`; the debug command above overrides it. These commands are for the build environment, not the owner's phone. Full notification testing requires System UI; the suite fails if it is absent. Use `scripts/ci-emulator.sh` on a disposable accelerated API 36 emulator host.

## Cloud release workflow

The feature-branch workflow has explicit job, step, command, boot and instrumentation time limits. It verifies the SDK archive checksum, uses consistent SDK/AVD paths, and captures emulator/logcat/screenshot evidence even on failure. See TEST_REPORT for actual execution.

CI creates a disposable test-only key to exercise the optimized release. That key cannot update an owner-signed installation and is never the delivered signing identity. Owner secrets are not uploaded to GitHub. The candidate is signed privately with the existing owner identity. When re-signing a tested CI APK, every non-signature ZIP entry must remain identical; entry equality and the owner certificate are verified.

Public owner certificate SHA-256: `4c5cb0113cae5d0dee2619ee30f5b3c593d4aa897e1fa38dee7ed5561ec4e847`. Publication to GitHub Releases or Google Play is separate and needs approval. The APK can be delivered privately without either.
