# Astravar v1.2.0 verification

## Build and automated tests

The clean Gradle run completed successfully with Java 17 and Android API 36/build-tools 35.0.0. It ran `clean`, `:rules:test`, `:app:testDebugUnitTest`, `:app:testReleaseUnitTest`, `:app:lintDebug`, `:app:lintRelease`, `:app:assembleDebug`, `:app:assembleRelease`, `:overlay-target:assembleDebug`, and `:overlay-target:assembleDebugAndroidTest`.

| Check | Result |
|---|---:|
| Rules tests | 59 passed, 0 failed |
| App debug/Robolectric tests | 11 passed, 0 failed |
| App release/Robolectric tests | 11 passed, 0 failed |
| Debug and release lint | Passed |
| Signed release APK build | Passed |
| Overlay app and instrumentation APK compilation | Passed |

The new tests cover configured 1–4d10 rolls and criticals, flat modifiers, typed Force damage, independent elemental/payload/overcharge dice, Direct Impact, unchanged Discharge/Shatter explosions, profile persistence, old-state/backup migration, and preservation of historical one-die roll snapshots.

Cantrip imbuing is not implemented in this repository, so no cantrip behavior was added or tested.

## Release APK checks

- Application: Astravar Overlay (`com.yazsras.astravar`)
- Version: 1.2.0 (version code 4)
- APK size: 4,106,699 bytes
- APK SHA-256: `347c1df706f0a6129212db0fda53de6d874ce69071453fd5dcab56bf04fb1dcb`
- `apksigner verify --verbose --print-certs`: passed; APK uses v2 signing
- Owner signing certificate SHA-256 matches v1.1.0: `4c5cb0113cae5d0dee2619ee30f5b3c593d4aa897e1fa38dee7ed5561ec4e847`
- `zipalign -c -v 4`: passed
- `aapt dump badging`: package and version values match the release metadata

## Device coverage boundary

No Android device was attached, no emulator was installed, and `/dev/kvm` was unavailable. A bounded ADB device-list check returned an empty device list. The overlay instrumentation APK compiled, but instrumentation execution, installation, launch, upgrade-over-v1.1.0, overlay permission interaction, and on-device combat smoke checks were not run.
