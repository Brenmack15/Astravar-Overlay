# Astravar 1.7.0 validation — 2026-10-10

Started from clean `24509e2` (1.6.0/code 9); implementation branch `feat/premium-instrument-ui`. No repository reset, engine replacement, campaign reset, or signing-key rotation. Package remains `com.yazsras.astravar`; versionName 1.7.0, versionCode 10.

## Implementation and preservation

The required reference ZIP, all seven art/mockup images, recording and contact sheet were accessible and reviewed. The existing Compose and native View architectures remain. This release replaces the presentation system with licensed Cinzel/Inter fonts, small original graphite/metal texture tiles, original vector symbols, cached beveled surfaces, engraving, ambient illumination and an optimized transparent derivative of the approved pistol art. Weapon, core/payload inventory, genuine roll results, newest-first History, More, dialogs and native expanded/collapsed/result overlay share these materials and typography.

Main actions and roll modes stay reachable above navigation at 412 dp and 360 dp widths. Core/payload charging, overwrite, purge, crafting, templates, sources, resource recovery and both independent damage sliders remain connected to their existing callbacks. More and the header settings action route to real utilities. Decorative effects are static; no looping animations, image-based fake controls or per-die views were added. Overlay caches contain bounded artwork/material resources, not Activity state.

The domain and storage source files are unchanged. Primary Force remains Nd12; selector Md8; Discharge 3N d12; Shatter 21N d12. Neutral omits selectors; each selected blast element occurs once; ordinary critical dice remain eligible and Direct Impact does not double explosions. Spell payloads, overcharge, saves, unlocks, firing costs and action budgets remain authoritative in the shared engine. Cantrip imbuing remains absent. No ordinary AC prediction or generic encounter-management UI was reintroduced.

Room stays v3; there is no new schema or DataStore migration. Existing campaign state, user artwork, configuration counts, raw historical faces and expressions remain untouched. Persistent MAX_HISTORY_ENTRIES=20, indexed SQL pruning, pre-observation streaming legacy cleanup, bounded transactional writes and bounded backup import/export remain unchanged.

## Executed tests

| Gate | Tests | Failures/errors | Skipped |
| --- | ---: | ---: | ---: |
| Pure Kotlin rules | 104 | 0 | 0 |
| Android debug unit/native rendering | 43 | 0 | 0 |
| Android release unit/native rendering | 43 | 0 | 0 |

The rules suite covers Normal/Advantage/Disadvantage, actual natural 20/1, primary/selector criticals, typed damage, neutral/Dual Resonance, payload spending and isolation, blast formulas/saves, large 84d12/105d12/210d12 pools and immutable old snapshots. Repository tests cover persistence, shared selections, old backup migration, state/resource preservation and bounded histories.

The existing retention suite was executed: counts 0/1/19/20/21/100; exact #21/#22 rotation; 110 attacks and restart; 23-row batches; 50 concurrent mutations; repeated large Shatter results; 10,000 legacy rolls plus 1,000 duplicated events; oversized backup import; stable newest-first ordering; malformed historical items; and preserved resource/count/face snapshots. No OOM was observed in these checks.

Three new presentation tests cover the production payload page and its real selection callback, all five Control room destinations, and the narrow 360 dp Weapon layout. Existing presentation tests were expanded to check genuine Dashboard result flow with reachable Roll Again, both phone widths, visible 48 dp roll-mode targets, the full material system, live core/payload labels, and native overlay results. The native overlay test now exercises 85% scale and verifies the actual physical 48 dp capsule target fits. The History test retains twenty 210d12 snapshots and checks order/details. Capture code validates PNG completion and writes atomically. The controlled-device instrumentation selectors were updated for the new separate result label and compact History breakdown.

Debug/release lint passed with zero errors: 25/20 warnings respectively and one hint each. Warnings concern pinned dependency updates, modifier ordering, unused retained/reference resources, programmatic-only native-view construction, KTX suggestions, existing localization/absolute-position choices and a mutable-int-state hint. No lint suppression or test disabling was used to conceal a failure.

## Build results and diagnosed failures

- Kotlin application/test compilation passed in 47 seconds.
- Focused Weapon/History/native overlay rendering passed twice; the second focused run completed in 55 seconds.
- The full test/lint/build gate completed all 190 test executions, but debug packaging failed after 2m 31s because two stale incremental dex archives both defined generated `androidx.activity.compose.R`.
- Both conflicting generated archives were identified. Only the generated debug project-dex and merge intermediates were removed; source, database, tests and captured evidence were preserved. A narrow retry of debug/release lint, debug app and controlled-overlay app/instrumentation packaging passed in 17 seconds.
- Production `:app:assembleRelease` passed in 1m 6s. Release optimization and resource shrinking remain enabled.
- An initial screenshot was truncated despite the test assertion succeeding. The test-only capture helper now verifies compression and writes atomically. All final thirteen PNGs were independently decoded successfully.

All Gradle invocations have a 600-second timeout and 15-second termination grace; adb and APK tooling use short explicit deadlines. No hanging test was repeatedly run. The earlier raw partial screenshot and first-pass layouts were not accepted as final visual evidence.

## Release verification

The verified artifact is `Astravar-v1.7.0-release.apk`, 2,611,700 bytes, SHA-256 `722d583340d36b7431aefe84a5d51f5a6cb5726c4c3f097449390c00e312caac`.

ZIP integrity, manifest and DEX presence pass. `apksigner verify --verbose --print-certs` passes. Package is unchanged, versionName 1.7.0 and versionCode increased 9 → 10. aapt and apkanalyzer confirm `debuggable=false`. `zipalign -c -P 16 -v 4` passes four-byte and 16-KiB page alignment.

The certificate matches the independently verified v1.6.0 release: SHA-256 `4c5cb0113cae5d0dee2619ee30f5b3c593d4aa897e1fa38dee7ed5561ec4e847`. Owner private signing material stays outside git and is not included in deliverables. No replacement identity was generated.

## Visual evidence and unexecuted device checks

All eight required views and additional actual critical/natural-1, 360 dp and reduced-scale overlay views were rendered and inspected. See VISUAL_ACCEPTANCE.md and the supplied evidence bundle for original screenshots, before/after and reference comparisons, per-screen findings, and the exact rendering boundary. Test fixtures use real engine/model state and production components; their values do not claim to be the user's phone campaign.

No connected adb device and no `/dev/kvm` were available. Physical Samsung installation/upgrade, actual D&D Beyond coexistence, drag/touch pass-through, keyboard, rotation, protected-screen behavior, permission recovery and notification Stop instrumentation were not executed. The controlled test APK compiled successfully. Native rendering and unit/database tests do not establish those device behaviors. Package/certificate/version continuity establishes update identity; it does not claim an actual phone installation. No screen remains a static placeholder, but device-specific visual fidelity and performance remain unverified.
