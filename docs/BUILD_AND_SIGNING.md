# Builds and signing

The release package ID remains `com.yazsras.astravar`. This UI reconstruction branch (`feat/astravar-2.0-ui-rebuild`) uses launcher name **Astravar**, version `2.0.1-ui-rebuild`, code `21`, debug package **`com.yazsras.astravar.v2uidebug`**. Original Preview is `com.yazsras.astravar.v2debug`; Inventory Beta is `com.yazsras.astravar.debug`. All have separate Android data sandboxes. Keep the installed working applications.

The prior installed Preview's debug signing key is unavailable here, so this branch uses a separate debug package instead of requiring an uncertain update or uninstall. Validated, user-selected exports are the explicit transfer path. Campaign merge-only restore and weapon replacement restore are different flows; neither contains the fictional sync outbox. Keep the old Preview to preserve its local pending commands and receipts. See [phone installation](ASTRAVAR_2_PHONE_INSTALL.md).

Use the exact commit’s Actions artifact and delivery verification report for UI rebuild build/test/signature evidence. Historical verification reports describe their named earlier APKs.

Use JDK 17 and SDK platform 36 / build tools 35.0.0. Set `ANDROID_HOME` or a local untracked `local.properties`. Run the included genuine Gradle wrapper. Its pinned distribution checksum is `20f1b1176237254a6fc204d8434196fa11a4cfb387567519c61556e8710aed78`.

```sh
python3 -m unittest discover -s scripts/tests -v
python3 scripts/verify_v2_protected_source.py
python3 scripts/verify_ui_foundation.py
timeout -k 10s 1140s ./gradlew --no-daemon :rules:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug -PastravarScreenshots="$PWD/validation/screenshots"
timeout -k 10s 180s ./gradlew --no-daemon :overlay-target:assembleDebug :overlay-target:assembleDebugAndroidTest
timeout -k 5s 35s adb install app/build/outputs/apk/debug/app-debug.apk
timeout -k 10s 300s ./gradlew --no-daemon :overlay-target:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.astravarPackage=com.yazsras.astravar.v2uidebug
```

Install the controlled test APK on a disposable emulator before the connected test. The instrumented test seeds only a labeled demo fixture. Do not run these fixture commands against the owner's phone or real campaign. The ordinary UI-branch CI path uses native Robolectric API 35 rendering; the optional connected commands are not a claim that emulator/device tests ran.

The unchanged live HTTP regression is opt-in and appends one fixed fictional event only:

```sh
ASTRAVAR_LIVE_SANDBOX=true ./gradlew --no-daemon :app:testDebugUnitTest --tests com.yazsras.astravar.data.SandboxLiveSyncTest --rerun-tasks
```

It uses the fixed public Worker origin and isolated sandbox test database, reopens Room, recovers the original command receipt, performs an exact retry and verifies read-back deduplication. This is host Kotlin-client evidence, not physical-device or private MCP-tool verification.

The opted-in `-PastravarRemoteStatTest=true` build keeps `com.yazsras.astravar.v2statdebug`, version `2.0.2-remote-stat-test` (22), also labeled **Astravar**. Labels do not establish update compatibility. See [the permanent update plan and branding provenance](ASTRAVAR_IDENTITY.md).

## Durable owner release identity

UI Rebuild validation builds debug only. The release configuration and commands below are retained from the recovered application as reference; this UI work does not rotate, replace or exercise the owner key. Local and Actions debug certificates may differ. Keep one UI Rebuild signing channel, preserve exports before switching, and never treat a signature mismatch as permission to uninstall a working app.

A distinct RSA-4096 PKCS12 release identity was created for this project. Its encrypted keystore and recovery configuration are preserved in an owner-private recovery artifact, not the repository, public build artifacts, or chat text. Reuse that exact identity for every update. Do not generate a replacement when secrets are missing. Historical release test reports record the public certificate SHA-256 and APK SHA-256 for those earlier signed releases. Current debug-APK checks must be reported separately.

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

## Current UI branch workflow

For `feat/astravar-2.0-ui-rebuild`, the workflow installs the checksum-verified SDK and builds debug only. It runs Python source safety, both preservation gates, rules/Android/UI tests, lint and APK assembly. Native Robolectric captures are collected with the reports. On success, signature, 16 KB alignment, APK metadata, SHA-256 and source commit are packaged into **Astravar-2.0-Stat-Test-apk** alongside **Astravar-2.0-Stat-Test-debug.apk**. Reports and screenshots are in **android-verification**. Artifacts are retained for 14 days; no GitHub Release is published.

Use a successful push run to match `SOURCE_COMMIT.txt` to the branch's published commit. PR runs may use a temporary merge commit. Final results belong in the current UI reconstruction report; legacy `TEST_REPORT` files remain historical.

## Retained legacy release workflow reference

Other legacy branches retain a bounded emulator/release verification path. That path creates a disposable test-only key to exercise an optimized release. It cannot update an owner-signed installation and is not a delivered release identity. Owner secrets are not uploaded to GitHub. Any future authorized production candidate must use the existing owner identity. When re-signing a tested CI APK, every non-signature ZIP entry must remain identical; entry equality and the owner certificate must be verified.

Historical owner release certificate SHA-256: `4c5cb0113cae5d0dee2619ee30f5b3c593d4aa897e1fa38dee7ed5561ec4e847`. Publication to GitHub Releases or Google Play is separate and needs approval. The APK can be delivered privately without either.
