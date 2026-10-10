# Astravar 1.6.0 validation — 2026-10-10

Started from clean `13a3afd`, application 1.5.1/code 8, branch `fix/bounded-roll-history`. Implementation is on `feat/weapon-focused-interface`. No repository reset or unrelated mechanics rollback. Package remains `com.yazsras.astravar`; this release is 1.6.0/code 9.

## Scope and compatibility

Four primary destinations: Weapon, Cores & Payloads, History, More. New rolls never adjudicate an ordinary hit against AC. Natural 20/1 behavior is preserved; potential damage and typed results are explicit. Enemy-defense pickers and the encounter dashboard are removed. Weapon-specific budgets, cooldowns, charging costs, elapsed recovery time and provisional limits remain. Confirming the next weapon action window resets only action counters, never game time or resources.

The only active preference migration removes `armor_class`. Current configurations, image selection, panel placement, roll modes, payload selection and campaign data remain. Compatibility-only historical AC/defense fields are still decoded and validated; old expressions, faces and recorded outcomes are unchanged. Room remains v3 with explicit migrations; no destructive fallback or new history cache was introduced.

Primary Force remains Nd12, selector Md8, Discharge 3N d12, Shatter 21N d12. Selectors enter explosions once; direct-impact criticals never double the blast. Cantrip imbuing remains absent. Both existing discrete count sliders are preserved.

## Tests

Five new pure Kotlin workflow tests cover historical AC snapshot preservation versus new non-adjudicated attacks; resetting only weapon budgets; the two-attack restriction; spell spending on normal/natural-1/natural-20 rolls; and DM-supplied Direct Impact outcomes with save resolution.

Five new Android tests cover the four destinations and reachable bottom Roll control; ordinary/critical/natural-1 result presentation; real core names/readiness with collapsed setup controls; actual native service view construction at compact dimensions; and real DataStore migration/reopen preserving all non-AC values.

Updated tests: legacy AC expectations in DiceRoller/selector tests, the historical Force migration fixture, repository preference sharing, expanded Shatter result presentation, and the existing controlled-overlay instrumentation selectors. The controlled instrumentation APK is compiled; see execution limits below.

The existing retention suite is included: boundary counts 0/1/19/20/21/100; exact #21/#22 rotation; 110 attacks and restart; 23-row batches; 50 concurrent mutations; repeated 84d12/105d12/210d12 Shatter results; 10,000 legacy rolls plus 1,000 duplicated events; oversized backup import; newest-first ordering; malformed historical items; and preserved resource/count/face snapshots. The original OOM allocation pattern remains eliminated by indexed SQL pruning before observation and streamed JSON cleanup, with one newest-20 roll source. No OOM was observed in executed checks.

Final validation: **104/104 rules tests passed**, **40/40 Android debug tests passed**, **40/40 Android release tests passed**, zero skipped/failed tests. Debug and release lint both passed with zero errors (11 and 10 warnings respectively, plus one informational item each). Warnings concern pinned dependency updates, modifier-parameter ordering, existing mutable-int state/URI helpers, and native-view localization/absolute window positioning.

The full gate built the debug app, controlled overlay target and its instrumentation APK. Gradle reported `BUILD SUCCESSFUL in 2m 26s`, 169 actionable tasks (38 executed, 131 up-to-date). The client log stopped forwarding near the end; the successful completion was recovered from the current daemon log. Test XML totals and lint XML were checked independently.

Production `:app:assembleRelease` passed in 1m 3s (54 actionable tasks, 13 executed and 41 up-to-date). The verified artifact is `Astravar-v1.6.0-release.apk`, 2,319,447 bytes. ZIP integrity, Android manifest and DEX presence pass. `apksigner verify --verbose --print-certs` passes. The package remains `com.yazsras.astravar`; versionName is 1.6.0 and versionCode increased from 8 to 9. Both aapt and apkanalyzer confirm the release is not debuggable. `zipalign -c -P 16 -v 4` passes.

The signing certificate matches the independently verified v1.5.1 APK: SHA-256 `4c5cb0113cae5d0dee2619ee30f5b3c593d4aa897e1fa38dee7ed5561ec4e847`. No signing identity was generated or changed. APK SHA-256: `cc751690206ae3ec91fd5c5fa0eb08dc0a403f15df0453bd107492b5c683b771`.

## Visual inspection

Robolectric API 35 native graphics rendered the actual Compose Weapon, ordinary/critical result, cores and History screens, plus the native collapsed/expanded/result overlay. Reviewed for readable controls, typed damage, natural indicators, retained weapon image, absence of AC fields and hidden detailed dice. Screenshot capture uses the native view canvas in the headless runner; it is not a physical-device screenshot.

The expanded native weapon panel is 300×364 dp; the collapsed bar is 174×52 dp at default scale. Secondary/result pages scroll within 420 dp. The existing display clamp and scaling remain. The main Roll control stays above navigation while configuration content scrolls. Asset storage uses the owner's supplied 1536×1152 JPEG with its correct extension; the upload's misleading PNG filename was corrected without altering image bytes.

## Diagnosed intermediate failures

- A legacy migration fixture had relied on generating an old AC hit through the new roller. It now explicitly creates an old immutable snapshot; preservation passes.
- JUnit required a void-return UI test method; the test signature was corrected.
- Compose PixelCopy capture timed out in the headless runner; direct native canvas capture succeeded without product changes.
- The first aggregate preference migration fixture reused an already-open test singleton. A separate file/closed store now tests the same production migration and a subsequent reopen.
- A Shatter UI assertion matched both the explosion total and the target's full-save-failure total. It now checks both expected nodes.
- Release AAPT rejected JPEG bytes under a PNG resource name. The resource extension now matches the supplied bytes.

No test was disabled to conceal a product failure. All Gradle commands use a 600-second limit and 15-second termination grace. adb/APK verification use separate short explicit deadlines.

## Device execution limits

No connected adb device and no `/dev/kvm` were available. Actual on-device instrumentation, install-over smoke test, physical Samsung/D&D Beyond interaction, protected-screen behavior and gesture/keyboard/rotation checks were not executed in this session. The controlled instrumentation was updated for the new UI and compiled. Rendering and unit/database migration tests do not establish physical device behavior. Package/version/certificate checks establish update identity, not a claim that an installation was performed.
