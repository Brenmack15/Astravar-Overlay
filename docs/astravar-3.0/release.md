# Reforge release readiness

Status: original-owner release candidate built and inspected; isolated in-place update validation and complete CI at verification source `4732d87` passed. Production publication and physical-phone installation remain separate approvals.

The verified source baseline is 2.4.0/code 41. The built Reforge candidate is
`3.0.0-rc1`/code 42, preserving the permanent release package
`com.yazsras.astravar`. The existing debug variant remains a separate QA package.
Neither version metadata nor a successful debug build proves a safe in-place
owner update.

## Android toolchain compatibility

The repository retains compile/target SDK 36, minimum SDK 26, AGP 8.13.2,
Gradle 8.13 and Kotlin 2.2.21. Google's current [AGP 8.13 compatibility
table](https://developer.android.com/build/releases/agp-8-13-0-release-notes)
specifies support through API 36.1 with Gradle 8.13, JDK 17 and build-tools
35.0.0. Android 17's [SDK setup
documentation](https://developer.android.com/about/versions/17/setup-sdk)
identifies API 37. Reforge does not silently opt into its new target behaviors.
Google's [build configuration guide](https://developer.android.com/build)
explains that higher Android versions run lower-target apps using compatibility
behavior. That supports retaining this tested toolchain; it is not evidence of
a successful Android 17 or Samsung installation. Sources inspected 2026-11-07.

The full historical owner certificate fingerprint pinned by existing release
inspection code is:

`4c5cb0113cae5d0dee2619ee30f5b3c593d4aa897e1fa38dee7ed5561ec4e847`

The original private owner-signing recovery material has been recovered outside
the repository. Its private/public key pair and full certificate fingerprint
were checked independently; the fingerprint matches the historical pin above.
No replacement signing identity or CI signing secret was created. Actual release
assembly and APK inspection matched this fingerprint and the preceding owner
APK. Signing material remained outside source control and CI.

The recovered **actual** 2.4.0/code 41 APK was inspected with Android's
`apksigner`, `aapt` and `zipalign`. It is the permanent package, non-debuggable,
and its full certificate matches the pin. Its SHA-256 is
`9b7006a4f2dfbd56a4d9f20e55a17a882fa7468c926a411f4c1b2a39e5baceaa`.
Packaged supplied artwork, retired-provider checks and 16 KB library/alignment
checks passed. This verifies the predecessor artifact; it does not prove what
is currently installed on the physical phone.

## Built candidate

Source: `30f0b9dda4e558246ecc481a4a97b23acf898cf0`; the 307-input build/test
digest is `8b2727f33b809e4b26eb9e945a3a45ce91d39f589c4c9e14dbf4e6a115330945`.
Optimized original-owner `assembleRelease` passed in 2m 44s, 54 tasks
(27 executed/27 up to date). The actual non-debuggable APK is
`com.yazsras.astravar`, `3.0.0-rc1`, versionCode 42.

- APK size: 12,631,215 bytes.
- APK SHA-256: `d46907c39f729145d3b1169090812b054296180cdb47802eef7a0f811746679a`.
- Certificate: exact original owner fingerprint above, also matching code 41.
- Native libraries and APK zip alignment: 16 KB checks passed.
- Supplied launcher artwork: exact approved bytes verified.
- Actual packaged overlay and unused foreground services/permissions: absent.
- Retired Firebase components/resources/dex and new special background permissions: absent.
- R8 mapping size: 77,949,815 bytes; SHA-256 `2fd89761bf7c2664ce794ecf753562be76f8132e60f289f27d1977d5b2a91887`.

The APK and mapping are private review artifacts; no production release was
published and no physical device was installed. The separate QA APK is not an
in-place owner update.

## Remaining release gates

1. Complete device-specific physical-phone verification only after the
   separate installation approval; the isolated AOSP 35 update already passed.
2. Obtain the separate authorization required for publishing/installing a
   production release. Draft source review does not perform either action.

Existing app data must never be cleared or uninstalled to bypass signature or
migration checks. The separately signed debug QA package is not an in-place
owner update.

## Actual isolated in-place update

The software-only AOSP 35 emulator retained the actual original-owner code 41
installation and fictional fixture stores. `adb install -r` of the inspected
owner code 42 returned `Success`; independent PackageManager readback confirmed
code 42 and unchanged appId 10148. No uninstall, clear-data or downgrade was
used. Wi-Fi was disabled, airplane mode enabled and ConnectivityService reported
`Active default network: none` before the real optimized MainActivity launched.

After opening and force-stopping code 42, a complete copy of databases including
WAL/SHM sidecars was independently compared to the settled code 41 snapshot.
All four original databases, fifty original tables and twenty-two rows matched
exactly, including stable identifiers, unknown/zero values, inventory, history,
resource counts and image bytes. All three existing settings XML files matched
byte for byte. Two AndroidX ProfileInstaller bookkeeping files changed, as
expected for an APK update; they are not campaign records. The separate new
Reforge database added six tables including metadata, preserving original
stores. The combined after-snapshot digest is
`acbd4becd471bf59d92c6e1ce782bddcbc382b927bb3b317ba706c05f5997654`.

A clean native frame from the installed optimized owner APK shows the character
entry and standalone navigation. The guest initially displayed a System UI ANR;
the focused component was `com.android.systemui`, while Astravar was top-resumed.
After its Wait action, MainActivity regained input focus and rendered cleanly.
This is a constrained-emulator limitation, not evidence of Samsung performance.
No physical phone, production database or production account was touched.

The installed optimized APK additionally completed actual offline character
review, typed item entry, reversible equip/unequip, calculated player dice and
cold restart. The known-HP fixture changed 40/66 → 49/75 → 40/66 → 49/75 while
retaining 26 points of missing HP and permanent STR 8/CON 14. Cold relaunch
restored STR 21, CON 16, AC 20 and 49/75; the exact saved STR d20 face
19 + 5 = 24 also survived. A separate
adopted fixture retained zero Strength and unknown HP through its own saved
INT roll/restart. These are fictional test records, not changes to the owner
campaign. The verification ledger contains the full state table and limits.

Final test-only backup/notes readiness corrections passed four native cases per
variant in 1m 57s. These changes do not alter the inspected/signed application
artifact.

The final systematic test harness at `4732d87` confines composition resumptions
while preserving actual asynchronous Room executors and all assertions. Its
40-suite local regression passed 191 debug cases and 189 release cases with
two intentional debug-only skips, taking 15m 50s. Independent complete
[CI run 42414763933](https://github.com/Brenmack15/Astravar-Overlay/actions/runs/42414763933)
then passed at this same verification source: rules 163/163, debug 580 passed
with three intentional skips, release 566 passed with seventeen intentional
skips, zero failures or errors. Each Android variant executed 583 cases;
both lints and debug assembly passed. Gradle took 36m 5s with all 105 tasks
executed. Prior failed runs remain disclosed in the ledger.
The 308-input verification digest is
`67c785a6bb64a62556ca68d47475af582a83723f4af9efc19d17c5692cfaf920`;
production/runtime inputs remain byte-identical to the signed candidate.
