# Astravar 1.5.0 validation and release report

Validated 2026-10-07 from the exact clean v1.4.0 source commit `c4149d1`, on `feature/primary-force-d12`. No reset, architecture replacement, campaign-data replacement, signing-key rotation, remote force push or merge was performed.

## Current rules and UI

- `baseDamageDiceCount: Int` remains the independent Primary Force count, 1–10. `elementalSelectorDiceCount: Int` remains the independent d8 count, 1–10. Neither is reset by this update.
- `PrimaryForceRules.DIE_SIDES = 12` is the authoritative current Force die size, supplied through `Profile.primaryForceDieSides`. `forceRulesVersion = 2` is serialized with the rules profile in the existing Room campaign JSON and backups.
- Primary Force: Nd12 + INT + calibration. Discharge: 3N d12. Shatter: 7 × Discharge = 21N d12. Explosions receive no weapon flat modifier, level progression or additive upgrade formula.
- Each active selector contributes Md8 once to either explosion. Neutral contributes none; eligible Dual Resonance elements each receive their full Md8. Successful saves halve each rolled Force/selector component separately, rounded down. Spell payload/overcharge still deals zero on successful emergency saves.
- Ordinary direct criticals double eligible weapon/selector/payload/overcharge dice and apply flats once. Direct Impact criticals double only direct weapon dice; explosions and their selectors never critical. Payload/overcharge die sizes and resources are unchanged. Cantrip imbuing is not implemented and was not added.
- Both discrete sliders remain in More → Rules, upgrades & provisional choices → Weapon → Upgrades. Primary labels now use d12; selector labels retain d8. Compose uses range 1f..10f and 8 intermediate stops, giving unit increments. Live neutral/elemental, Discharge and Shatter previews use shared Engine components and the active element selection. The floating overlay shares persisted profile/selection and gains a Neutral selection option; no upgrade slider is added there.
- Repository canonical defaults remain N=2/M=2: 2d12+5 Force, 2d8 selector, 6d12 Discharge, 42d12 Shatter. The phone's actual saved counts are not remotely readable; its selections are preserved by migration.
- Required examples confirmed: 4d12 → 12d12 → 84d12; 5d12 → 15d12 → 105d12; 10d12 → 30d12 → 210d12.

## Migration and historical accuracy

Active legacy profiles migrate to Force rules v2 on Room read, backup import and mutations, including Undo into an old profile. Both selected counts, inventory, resources, game clock, notes, crafting/encounter state and preferences remain intact. Historical event/archive profiles and committed shot profiles retain legacy rules v1, d10 faces, original selector count/eligibility and `blastDiceCount`. Pre-v1.3 shots missing a blast snapshot reconstruct the former level-only formula solely in migration. Old event/archive database rows are not rewritten. Re-export/re-import is tested without false archive conflicts. New backups preserve the rules version and both counts. Room stays schema version 2, with no destructive migration.

## Executed automated validation

| Suite | Tests | Failures | Errors | Skipped |
| --- | ---: | ---: | ---: | ---: |
| Rules | 93 | 0 | 0 | 0 |
| Android debug | 28 | 0 | 0 | 0 |
| Android release | 28 | 0 | 0 | 0 |

Added 13 rules/migration tests (`PrimaryForceTest`, `ForceMigrationTest`) and 4 Android repository/UI tests (`ForceRulesRepositoryTest`, `ForcePresentationTest`). Updated six existing rules test files and four existing Android test files for d12 expressions, emergency formulas and accurate legacy fixtures.

Coverage includes all levels 5–20 and all Primary counts 1–10; explicit 1/2/3/4/5/10 emergency examples; independent selector values and averages; exactly N/3N/21N d12 requests; requested 38 Force example; typed 71 Force +9 Lightning saves becoming 35 Force +4 Lightning; critical impact versus unchanged explosion/selector dice; neutral and Dual Resonance; d10 spell payload isolation; normal/advantage/disadvantage/natural 1/20 regressions; core/payload/slot accounting; idempotent firing; both sliders' touch/semantics/rounding/persistence; old and modern backups; shared Room observers, reopen, Undo, and immutable archived rows.

Large-pool tests deterministically roll 84/105/210 d12s, verify each face and total (maximum 210d12 = 2520), and round-trip complete history. A bounded UI test confirms the 210-die pool starts collapsed and expands as one text node per damage component rather than hundreds of per-die views. These are host tests, not physical-device performance measurements.

Focused rules/migration suite: 17/17 passed before broader validation. The first focused run exposed a missing known-AC value in a historical-impact test fixture; corrected. The first broad app run exposed an older archive fixture using the current rules version; corrected to true legacy data and the focused archive recheck passed. No remaining test failure.

Clean final Gradle run: **BUILD SUCCESSFUL in 9m 47s**. Command used a 720-second timeout with a 15-second termination grace:

```text
clean :rules:test :app:testDebugUnitTest :app:testReleaseUnitTest
:app:lintDebug :app:lintRelease :app:assembleDebug :app:assembleRelease
:overlay-target:assembleDebug :overlay-target:assembleDebugAndroidTest
```

- Debug lint: 0 errors, 7 warnings: GradleDependency, NewerVersionAvailable, RtlHardcoded, SetTextI18n, UseKtx.
- Release lint: 0 errors, 8 warnings: GradleDependency, NewerVersionAvailable, RtlHardcoded, SetTextI18n, UseKtx.

No dependency or toolchain version was changed. JDK 17, Gradle 8.13, API 36 and build tools 35.0.0 were restored from verified official downloads. An initial dependency-resolution failure came from stale environment proxy options; using the current environment's proxy resolved it without disabling TLS verification. Android host tests use Robolectric API 35. All build/install-related commands were bounded; no stalled instrumentation was repeatedly retried.

## Signed APK verification

| Check | Result |
| --- | --- |
| Artifact | Astravar-v1.5.0-release.apk |
| Package ID | com.yazsras.astravar (unchanged) |
| versionName | 1.5.0 |
| versionCode | 7 (previous 6) |
| Size | 4,106,699 bytes |
| APK ZIP integrity | Passed; manifest and DEX present |
| apksigner verification | Passed, APK Signature Scheme v2 |
| Certificate continuity | Exact match with owner-signed v1.4.0 APK |
| zipalign | Passed, 4-byte and 16 KiB native alignment check |
| Debuggable | false, verified by apkanalyzer and aapt |
| Release configuration | Minification and resource shrinking enabled |

APK SHA-256: `8b132d0914f3e07995ac8a40ac09eb3f6091c36b4733fb410da910b3572b6fb0`

Public signing certificate SHA-256: `4c5cb0113cae5d0dee2619ee30f5b3c593d4aa897e1fa38dee7ed5561ec4e847`

The existing owner PKCS12 key and privately provisioned credentials were used. No private key, password or signing-recovery file is tracked in the repo or included in public source/artifacts. No replacement signing identity was generated. Public GitHub release inventory contained only v1.1.0 when checked; v1.5.0 is the next unused local release after v1.4.0/code 6.

## Not executed / release limits

ADB reported no connected devices; no hardware-accelerated emulator is available. Actual APK update installation, launch/restart on a device, live service-overlay behavior, API 36 instrumentation execution, and physical Samsung Galaxy S26/D&D Beyond smoke tests were **not run**. The controlled target and instrumentation APKs compiled successfully. Database migration/reopen/shared-state and Compose behavior passed the host suites above. Matching package, certificate and higher versionCode satisfy the verified update prerequisites, but they are not a substitute for a device upgrade test.

The signed APK is delivered as a downloadable conversation artifact. No GitHub Release, remote tag or main-branch merge was created. Install over the existing app; do not uninstall a real campaign to update.
