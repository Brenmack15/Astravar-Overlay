# Astravar v1.4.0 verification — 2026-10-06

## Source and implementation

Started from the clean `feature/configurable-weapon-dice` branch at `c51f3fd` (v1.3.0/build 5). Work continues on `feature/configurable-elemental-selector`, preserving that commit and the earlier automatic-combat, base-slider, emergency-scaling and overlay fixes. Package identity and the existing release keystore are unchanged.

Added `Profile.elementalSelectorDiceCount: Int`, validated from 1 through 10 and defaulting to 2. `Profile.selector` derives the existing `Dice(M, 8)` expression. The value lives alongside the unchanged Force count in Room `astravar.db`, table `campaign`, JSON `body.profile`; it is included in existing JSON backup/export. No Room schema change or destructive migration was introduced.

The full-app control is at **More → Rules, upgrades & provisional choices → Weapon → Upgrades**. `WeaponDamageUpgrades` holds two independent whole-number drag previews and uses the existing Compose Material Slider conventions: range 1f..10f, eight intermediate stops (unit increments), at least 48 dp height, an Nd10/Md8 value label and accessible state description. Each release/accessibility action commits only its corresponding profile field. Both drags immediately update the combined preview from actual Profile Dice expressions. The existing Force, Discharge and Shatter previews remain. No upgrade slider was added to the overlay.

Both combat renderers use `Engine.components` and observe the shared repository. The overlay element picker additionally displays the configured selector expression. Independent selector components preserve damage type, raw faces and critical eligibility.

## Canonical rules and interactions

The repository's existing canonical profile remains N=2, INT +4, calibration +1. A phone's saved Force selection cannot be read here and is preserved by the migration; it is not overwritten with the repository default.

| Component | Canonical expression |
|---|---|
| Base Force | 2d10+5 Force |
| Elemental selector | 2d8 selected element |
| Ordinary Elemental Shot | 2d10+5 Force + 2d8 selected element |
| Critical Elemental Shot | 4d10+5 Force + 4d8 selected element |
| Level-5 Discharge | 10d10 Force |
| Level-5 Shatter | 30d10 Force |

Generic elemental damage is `Nd10 + INT + calibration` Force plus `Md8` per selected element. Criticals use `2N` and `2M`, with flat modifiers once. Confirmed Dual Resonance grants each selected basic element the full M d8 contribution. The average is derived by Dice: M × 4.5.

Neutral Force, Spell-Imbued Shot, Direct Impact, Discharge and Shatter gain no free selector dice. Spell payloads, overcharge, element unlocks, core states, resource costs and save resolution are unchanged. Emergency formulas still depend exclusively on level and N: Discharge = level base + 2 × (N−1); Shatter = level base + 6 × (N−1). A successful save halves the same rolled Force total, rounded down. Cantrip imbuing remains absent.

## Migration and history

Profiles missing only the new setting migrate to M=2 while retaining N and all other data. Missing fields in old committed/historical shot profiles decode to their original 1d8/1d10 rules. Existing explicit modern fields remain exact. Historical expressions, raw faces, results, blast snapshots and event/archive rows are preserved. The old-archive normalization already present in v1.3 remains intact; migrated archives can be exported and reimported without a false conflict. Unrolled committed old elemental shots still roll their original 1d8. A backup missing both upgrade fields is also covered.

## Executed tests and build

Official checksum-verified Gradle 8.13, Temurin JDK 17.0.20.1 and the repository-pinned Android command-line tools were restored because the prior temporary toolchain was absent. Android API 36 and build-tools 35.0.0 were used. TLS validation stayed enabled. All build, SDK, signing and ADB commands had explicit time limits; no command hung. The initial system-package installation was unavailable, so the official portable JDK was used.

Focused rules/migration tests passed after correcting a new test fixture that incorrectly enabled Direct Impact for a regular Spell shot. Focused app tests then passed (11 tests: existing Force slider, new selector UI, new repository tests). An additional combined legacy-migration regression was included in the full run.

The clean full Gradle run completed in **5m 39s**, within its 12-minute timeout, executing `clean`, `:rules:test`, `:app:testDebugUnitTest`, `:app:testReleaseUnitTest`, `:app:lintDebug`, `:app:lintRelease`, `:app:assembleDebug`, `:app:assembleRelease`, `:overlay-target:assembleDebug` and `:overlay-target:assembleDebugAndroidTest`.

| Check | Result |
|---|---|
| Rules tests | 80 passed; 0 failures, errors or skips |
| Debug app/persistence/Compose tests | 24 passed; 0 failures, errors or skips |
| Release app/persistence/Compose tests | 24 passed; 0 failures, errors or skips |
| Debug and release lint | Passed; 0 errors, 6 warnings per variant |
| Clean debug and signed production release builds | Passed |
| Overlay target and instrumentation APK compilation | Passed |

Added 13 rules/migration tests and 8 app/UI/repository tests. Updated existing elemental fixtures to expect the canonical 2d8 and adapted the three Force-slider UI tests to the combined control.

Coverage includes all 100 independent N/M pairs; each independent d10/d8 face; the requested 7/4 Force and 6/2/8 Lightning example; selector criticals; modifier applied once; Dual Resonance components; unchanged unlocks; no selectors on Neutral/Spell/Direct Impact/emergencies; payload/overcharge and resources; unchanged emergency expressions across levels 5–20; invalid JSON/count rejection; all ten UI stops, fractional/out-of-range input, disabled controls, live touch preview and independent commit; M=3/4/7/10 database reopen/shared observers; M=8 backup restore; old backup migration; and exact historical snapshots/unchanged stored event/archive rows. Existing Normal/Advantage/Disadvantage/natural-1/20, saves, Force progression, core, history, backup and accounting regressions passed.

Lint warnings concern existing dependency-update guidance, URI extensions, overlay text localization and RTL gravity. They do not originate in the new slider or rules implementation.

## Verified release APK

| Property | Result |
|---|---|
| Application | Astravar Overlay |
| Package | `com.yazsras.astravar` — unchanged |
| Version | 1.4.0, code 6 — previous code 5 |
| File | `Astravar-v1.4.0-release.apk` |
| Size | 4,106,699 bytes |
| Signature | `apksigner verify --verbose --print-certs`: passed, v2 signature |
| Certificate continuity | Exact match with v1.3.0; same keystore also matched v1.2.0 and v1.1.0 |
| Alignment | `zipalign -c -P 16 -v 4`: passed |
| Manifest | `aapt` package/version verified; `apkanalyzer manifest debuggable`: false |
| ZIP integrity | Passed; manifest and classes.dex present |
| ABIs | arm64-v8a, armeabi-v7a, x86, x86_64 |
| Optimization | Release minification and resource shrinking retained |

APK SHA-256: `566b9295c1ae0aaeeaa97d9d5522bbc22cc954b709458af42a81880dbd386d0c`

Signing certificate SHA-256 (public fingerprint): `4c5cb0113cae5d0dee2619ee30f5b3c593d4aa897e1fa38dee7ed5561ec4e847`

## Device and publication boundaries

A bounded ADB scan found no connected device; its server was stopped. No emulator or /dev/kvm was available. Device installation, upgrade over v1.3.0, real app launch, end-to-end live overlay smoke tests and instrumentation execution were **not run**. No physical Samsung Galaxy or D&D Beyond coverage is claimed. Local Compose and Room reopen/observer tests do not substitute for those device checks.

The verified signed APK is the direct-download deliverable. GitHub's app connection required reauthentication. The requested GitHub browser page was opened and showed the owner already signed in as Brenmack15; its releases page showed only v1.1.0. No GitHub source push, tag, merge or release was created during this update. Source changes remain committed on the local feature branch.
