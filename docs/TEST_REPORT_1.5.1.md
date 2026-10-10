# Astravar 1.5.1 history stability validation

Validated 2026-10-07 (America/New_York), beginning with clean commit `613cd67`, branch `feature/primary-force-d12`, version 1.5.0/code 7. Changes are on `fix/bounded-roll-history`. No reset, discarded work, signing-key replacement or combat-rules rollback occurred.

## Root cause evidence

At the inspected source revision, `StateDao.observeEvents()` selected every event in descending revision order without a LIMIT. `Repository.history` eagerly decoded every row into a StateFlow collected by the main app even when History was not open. Every mutation serialized a HistoryEntry containing full before/after Campaign objects, each containing the entire ever-growing `pending` roll list. Engine commits appended shots indefinitely. `recentRolls().take(20)` only limited presentation. Thus after n attacks, the event journal contained approximately n² duplicated shot snapshots, as well as the unbounded current campaign JSON. Undo loaded the full event list; export and imports added more complete archived copies. Large Shatter faces amplified this allocation pattern.

This mechanism is established by the source and seeded database measurements, not an invented device stack trace. The user's phone database/heap dump was unavailable. We did not deliberately crash the old app with OOM; the fixed path was exercised against oversized source data under bounded tests.

## Storage and ordering

- Authoritative limit: `HistoryRetention.MAX_HISTORY_ENTRIES = 20`.
- Room schema v3 adds `rolls(id, sequence, body)` with an index on sequence DESC, id DESC. Each body contains only one immutable PendingShot rule/result snapshot, including raw faces, types, modifiers, attack mode, natural roll, timestamp and critical state.
- Repository mutations save resources, the newest 20 shots and accounting changes in one Room transaction. SQLite AFTER INSERT triggers and explicit SQL pruning enforce the same cap, including direct/batched writes.
- Reads use ORDER BY sequence DESC, id DESC LIMIT 20. The stored sequence preserves the retained campaign's insertion order; ID breaks ties deterministically. Resolving an existing shot does not change its creation order. The user-facing list remains newest-first.
- Campaign JSON contains no roll list. Accounting events are also limited to 20, indexed by revision DESC/id DESC, and their Undo snapshots omit roll lists. UI accounting observation fetches metadata only. Undo stores only resolution-change metadata, never another set of individual dice. The existing app/overlay receive one shared, transactionally assembled campaign view.
- History navigation and cards remain. Large dice details remain collapsed until requested. The current-result card and shared overlay result path remain intact.
- Retention permanently deletes older rolls, including older unresolved entries in the existing History model; Undo cannot resurrect expired rolls. No core, payload, inventory, settings, encounter, slot, profile or other active resource is deleted by retention.

## Safe legacy cleanup

Explicit Room migration 2→3 runs before normal observations. It creates the ordering indexes, prunes the old event journal directly in SQL, then reads retained records individually. Large campaign/event fields are read in 8 KiB SQLite BLOB substrings through a UTF-8 Reader, avoiding an oversized CursorWindow or whole-text allocation. The streaming JSON filter retains only the latest 20 valid roll records while scanning the old pending array; it never builds all prior roll objects first. Current resource fields are preserved and validated.

Malformed historical record contents are isolated and logged; they do not reset the campaign. Structurally broken import files or invalid active campaign resources fail safely without replacement. Normal player-facing errors are concise; technical exceptions are logged. Duplicate archive journals are deleted under the new retention policy. There is no destructive Room fallback. Legacy 1→2→3 migration is covered.

## Backups

Export includes the current campaign and at most 20 combat snapshots, plus the bounded accounting journal without duplicated dice. No legacy archive copies are exported. Imports read the SAF stream on IO and retain only the bounded decoded preview, rather than both the original huge file string and its object tree. Old files containing thousands of rolls retain only the newest 20. Duplicate legacy accounting archives are discarded; the old History screen's authoritative source was campaign.pending.

Input scanning is bounded to 512 Mi characters, nesting to 64, each roll JSON record to 1 Mi characters and retained normalized data to 8 Mi characters. These are parser/storage safety envelopes, not changes to legitimate dice counts. Existing 210d12 Shatter and independent d8 selector pools remain supported.

## Executed tests

| Suite | Tests | Failures | Errors | Skipped |
| --- | ---: | ---: | ---: | ---: |
| Rules | 99 | 0 | 0 | 0 |
| Android debug | 35 | 0 | 0 | 0 |
| Android release | 35 | 0 | 0 | 0 |

Added 13 tests: six pure rules/streaming retention tests, six Room repository/migration tests, and one Compose History/current-result presentation test. Updated existing backup/migration expectations to the explicitly requested removal of duplicate archives and before/after roll lists. Updated restart fixture teardown to await observer cancellation before closing/reopening Room.

Coverage includes 0, 1, 19, 20, 21 and 100 records; exact attack #21 and #22 rotation; 110 attacks with restart; newest-first ordering; deterministic tied SQL keys; 23-row batch pruning; 50 concurrent mutations; corrupt historical records; current result; a 10,000-roll legacy campaign; 1,000 duplicated event rows; new and old backup export/import; resources/count preservation; Undo; and repeated 84d12/105d12/210d12 results with every face and typed total.

Measured database stress fixture:

```text
Legacy fixture: 10000 rolls, 1000 duplicated events; campaign JSON 16737653 chars, event JSON 169490 chars each. Retained total: 76196 chars.
```

The migration kept the newest 20 exact shot snapshots, reduced both persisted row counts to 20, removed duplicate archives, used the event ordering index, preserved unrelated state and allowed another combat roll afterward. Repeated large-pool histories opened in the Compose test, stayed summarized by default and exposed complete detail on expansion. No OOM occurred in these fixed-path tests. Host results do not establish performance on a physical phone.

A separate 10,000-large-roll streaming backup test passed with the rules JVM restricted to **64 MiB maximum heap** (120-second command timeout; the test itself has a 30-second timeout).

The first broad pass exposed two test teardown races: fixtures closed Room immediately after requesting coroutine cancellation. Awaiting cancellation before closing fixed the affected reopen tests; their focused recheck passed, followed by the final full suites above. Early legacy fixture assertions expecting unlimited duplicate archives were updated to the new retention policy. No remaining test failures.

Final clean build: **BUILD SUCCESSFUL in 4m 4s**, bounded by 600 seconds plus 15-second termination grace:

```text
clean :rules:test :app:testDebugUnitTest :app:testReleaseUnitTest
:app:lintDebug :app:lintRelease :app:assembleDebug :app:assembleRelease
:overlay-target:assembleDebug :overlay-target:assembleDebugAndroidTest
```

- debug: 0 errors, 8 warnings (GradleDependency, NewerVersionAvailable, RtlHardcoded, SetTextI18n, UseKtx).
- release: 0 errors, 8 warnings (GradleDependency, NewerVersionAvailable, RtlHardcoded, SetTextI18n, UseKtx).

Existing combat regression tests passed: d12 Force counts, d8 selector range and independence, normal/advantage/disadvantage, natural 1/20, criticals, neutral/elemental/spell shots, Dual Resonance, Direct Impact, 3N Discharge, 21N Shatter, independent selector-on-blast saves, core/payload/slot accounting, both sliders, persisted shared state and historical d10 expressions. Cantrip imbuing remains absent. No combat engine or dice roller implementation changed.

## Release verification

| Check | Result |
| --- | --- |
| APK | Astravar-v1.5.1-release.apk |
| Package | com.yazsras.astravar (unchanged) |
| versionName | 1.5.1 |
| versionCode | 8 (previous 7) |
| APK size | 4,123,083 bytes |
| ZIP/DEX/manifest integrity | Passed |
| apksigner | Verified, v2 signature scheme |
| Certificate continuity | Exact match with signed v1.5.0 |
| zipalign | Passed, 4-byte and 16 KiB checks |
| Debuggable | false, independently checked by aapt and apkanalyzer |

APK SHA-256: `a27b37ffd8891fcd659616b09add2f3783ccc41174313bc8c96e24afc2811d3b`

Public certificate SHA-256: `4c5cb0113cae5d0dee2619ee30f5b3c593d4aa897e1fa38dee7ed5561ec4e847`

The existing owner signing key was used privately; no replacement key or signing secrets are included in source or deliverables.

## Not run

ADB reported no connected devices and no /dev/kvm was available. Actual install-over-v1.5.0 on the phone, physical startup/OOM reproduction, live service-overlay smoke testing and instrumentation execution were not run. The controlled target and instrumentation APKs compiled. Package/certificate continuity and higher versionCode verify update prerequisites; the seeded Room migration validates data handling, but does not substitute for a physical update test.

The verified release APK is supplied as a downloadable conversation artifact. No GitHub Release, remote tag or main-branch merge was created. Install over the existing app without uninstalling it.
