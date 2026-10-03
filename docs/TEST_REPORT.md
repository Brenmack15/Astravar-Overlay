# Astravar v1.3.0 verification — 2026-10-03

## Build and automated tests

Started from clean branch `feature/configurable-weapon-dice` at `91f444d`, preserving the completed v1.2.0 implementation and earlier overlay fixes. The clean Gradle build completed in **2m 26s** with Java 17, Android API 36 and build-tools 35.0.0. All build/test invocations had explicit timeouts (focused runs 4–6 minutes, clean full run 12 minutes). No command hung.

The full run executed `clean`, `:rules:test`, `:app:testDebugUnitTest`, `:app:testReleaseUnitTest`, `:app:lintDebug`, `:app:lintRelease`, `:app:assembleDebug`, `:app:assembleRelease`, `:overlay-target:assembleDebug`, and `:overlay-target:assembleDebugAndroidTest`.

| Check | Result |
|---|---:|
| Rules tests | 67 passed, 0 failures/errors/skips |
| Debug app tests (13 repository + 3 Compose UI) | 16 passed, 0 failures/errors/skips |
| Release app tests (13 repository + 3 Compose UI) | 16 passed, 0 failures/errors/skips |
| Debug and release lint | Passed |
| Clean signed release APK build | Passed |
| Overlay target and instrumentation APK compilation | Passed |

### Added and updated coverage

- `EmergencyScalingTest`: every level 5–20 and count 1–10; both emergency formulas, averages, existing range/radius/action costs, explicit spell-level override and saved blast expressions.
- `BaseDamageUpgradeTest`: actual Compose Slider semantics and touch input; endpoints 1/10, eight intermediate stops (unit increments), every integer label and all three previews, fractional/out-of-range input, live drag preview and one integer commit on release.
- `EngineTest` / `DiceRollerTest`: independent direct d10 faces including N=10, criticals 2N with one flat modifier, typed selectors/payloads/overcharge unchanged, scaled Direct Impact with noncritical blasts/save payloads, and both emergency saves halving the same full roll rounded down. Existing advantage/disadvantage, natural 1/20, resource and action tests passed.
- `BackupCodecTest`: old direct shots keep 1d10; pre-v1.3 blasts retain original expressions/raw faces/results through campaign/history/archive snapshots; unresolved legacy blasts and overrides retain their old rules. N=7/10 backups round-trip. Fractional counts and invalid snapshots are rejected.
- `RepositoryTest`: N=7/10 survive database close/reopen, drive the shared main/overlay repositories and new automatic attacks, derive level-5 emergency values 20/60 and 26/78, and restore from backup. Reimporting migrated archives succeeds without rewriting existing archive rows.

The targeted migration fix compares normalized archive content before reporting a conflict; old archive rows remain intact. Room schema and existing campaign data are retained. Cantrip imbuing is absent and was not added.

## Release APK verification

- Application: **Astravar Overlay**
- Package: `com.yazsras.astravar` (unchanged)
- Version: **1.3.0**, version code **5** (previous delivered v1.2.0 code 4)
- File: `Astravar-v1.3.0-release.apk`
- Size: **4,106,699 bytes**
- APK SHA-256: `39ff4f71e4322dc16f3c0627a94fe277ee915ffe20c99e7c101aaa323a1f89cf`
- ZIP integrity, manifest and classes.dex presence: passed
- `apksigner verify --verbose --print-certs`: passed, APK v2 signature
- Certificate continuity: exact SHA-256 match against both v1.2.0 and v1.1.0 APKs: `4c5cb0113cae5d0dee2619ee30f5b3c593d4aa897e1fa38dee7ed5561ec4e847`
- `zipalign -c -P 16 -v 4`: passed
- `aapt dump badging`: package, label, version and SDK values verified
- `apkanalyzer manifest debuggable`: **false**
- Release minification/resource shrinking enabled; owner signing configuration preserved
- Universal APK includes arm64-v8a, armeabi-v7a, x86 and x86_64; min SDK 26, target SDK 36

## Device coverage boundary

A bounded ADB check returned no connected devices; its server was stopped afterward. No emulator or `/dev/kvm` is available. Device installation, upgrade-over-previous-release, launch, overlay permission interaction, end-to-end overlay synchronization and device instrumentation execution were **not run**. No physical Samsung/D&D Beyond coverage is claimed. Shared repository synchronization and process-style database persistence were tested locally; these do not substitute for an on-device smoke test.

## Download and publication

The verified APK is the downloadable deliverable. Existing GitHub tags/releases were inspected: only v1.1.0 was published. A repository write through the GitHub connection was rejected with `403 Resource not accessible by integration`, so no v1.3.0 GitHub release or tag was created and no published history was replaced.
