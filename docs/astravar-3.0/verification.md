# Verification ledger

Status: final local regression, complete CI at `4732d87` and both lint variants passed; original-owner release assembly and isolated package-update verification passed. Results below distinguish executed checks from planned checks.

## Evidence baseline

The implementation starts from commit `852bbd48af9b6c70be5ce44d5f898d1224ca0e7c`
(Astravar 2.4.0/code 41, draft PR #8). An isolated detached worktree is used for
baseline compilation and screenshots so concurrent Reforge edits cannot change
that source. `main` is not the usable application baseline.

## Executed initial checks

| Check | Actual result |
| --- | --- |
| Python baseline suite | 139 tests passed, 5.147 seconds |
| Protected v1.7.0 source gate | 33 files passed; exact historical overlay reverse proof passed |
| UI foundation gate | 82 files and owner-signing configuration passed |
| Approved launcher artwork | All 14 resources passed |
| Approved particle artwork | All 10 resources passed |
| Original Gradle bootstrap | Blocked by direct Java networking; resolved with the environment proxy |
| Original Java runtime | JRE only; resolved by installing checksum-verified Temurin JDK 17 |
| Android SDK availability | Installed Android 36, build-tools 35.0.0 and platform-tools |
| Baseline pure Kotlin rules | 124 tests passed, zero failures/errors/skips |
| Baseline Android Kotlin/Java compilation | Passed; native JVM suite then blocked by Robolectric's separate dependency downloader lacking proxy settings |
| Baseline full Android suite/lint/APK | Interrupted after infrastructure download failures; not claimed passed |
| Current Python suite | 147 tests passed, 4.760 seconds after the final manifest changes |
| Authenticated bridge backend suite | 134 tests passed, zero failures/skips, Node 24.22.1, 2.277 seconds |
| First coherent Reforge rules suite | 159 tests passed, zero failures/errors/skips, 4.207 seconds of JUnit suite time |
| Final debug/release main and test compilation | Passed for source `30f0b9d`, input digest `8b2727f33b809e4b26eb9e945a3a45ce91d39f589c4c9e14dbf4e6a115330945` |
| Coherent focused native run | 43 Android tests executed: 41 passed, two test harness scroll failures; 139.359 seconds of JUnit suite time, 7m 3s Gradle wall time |
| Same-fixture baseline native captures | Two baseline-only capture tests passed; 11.414 seconds suite time, 1m 13s Gradle wall time; application source remains commit 852bbd4 |
| Broad debug regression before final hardening | 579 cases: 568 passed, 8 failed, 3 opt-in tests skipped; 860.468 seconds suite time, 20m 48s Gradle wall time. Historical selectors, a comparison selector ambiguity and eager Room test-service binding were investigated and corrected; subsequent runs are recorded below. |
| Current pure rules suite | 163 passed, zero failures/errors/skips, 5.628 seconds suite time |
| Isolated package-upgrade fixture | Actual owner code 41 installed on software AOSP 35, all four real databases created, 50 tables/22 rows with synthetic edge cases independently read back; all table hashes match after opening the old app |
| Physical Galaxy S26 | No attached device; Android version, installation and phone behavior unverified |
| Emulator execution | No `/dev/kvm`; software AOSP 35 booted in 706.770 seconds. Launcher Quickstep displayed an ANR under TCG; this is not a phone performance measurement. |
| Owner signing | Original private key recovered outside source; private/public match and exact historical certificate verified; optimized assembly passed; actual final APK matches the original full owner fingerprint |
| Final focused native regressions | 12 selected suites/56 cases per variant, zero failures/errors/skips. Debug 123.725 seconds, release 135.412 seconds of JUnit suite time. Combined Gradle wall time 10m 22s; 105 tasks, 38 executed/67 up to date. |
| Final lint | Debug: zero errors, 63 warnings, one hint. Release: zero errors, 65 warnings, one hint. Neither result is presented as warning-free. |
| Clean full CI at product source 30f0b9d | Debug: 583 cases, 579 passed/one failed/three skipped. Release: 583 cases, 566 passed/zero failed/17 skipped. Both lints and debug assembly passed. The sole debug failure was a test readiness race while backup preview was still busy; the control correctly remained disabled. |
| Final async-readiness regressions | Latest test-only head `acf936c`: Backup one case plus Notes three cases per variant, all four passed with zero errors/skips. Debug 21.561 seconds/release 19.585 seconds of JUnit time; combined Gradle 1m 57s, 70 tasks, six executed/64 up to date. Application/build/schema bytes remain identical to owner APK source 30f0b9d. |
| Full CI at `acf936c` | Debug: 583 cases, 580 passed/zero failed/three skipped, 535.461 seconds of JUnit suite time. Release: 583 cases, 565 passed/one failed/17 skipped, 516.011 seconds. The sole failure was Compose test frame application resumed on Room's IO thread; backup/notes cases passed in both variants. |
| Confined-composition pilot | With a queued composition test dispatcher and unchanged asynchronous Room executors, CampaignPages eight cases plus Backup one and Notes three all passed per variant. Debug 29.109 seconds/release 29.843 seconds JUnit; Gradle 2m 21s, 70 tasks, eight executed/62 up to date. |
| Shared test harness verification | Source `4732d87`, all 39 UI suites/162 cases plus the same-basename data HP suite/29 cases: 40 suites/191 cases per variant. Debug 191 passed/zero failures/skips; release 189 passed/zero failures/two debug-only skips. Debug 385.723 seconds/release 387.021 seconds JUnit; combined Gradle 15m 50s, 70 tasks, six executed/64 up to date. No application/build/schema inputs changed; fresh complete CI remains separate. |
| Final complete CI at `4732d87` | [Run 42414763933](https://github.com/Brenmack15/Astravar-Overlay/actions/runs/42414763933) passed. Rules: 163 passed. Debug: 583 cases, 580 passed/three intentional skips, 610.848 seconds JUnit. Release: 583 cases, 566 passed/17 intentional skips, 608.467 seconds. Zero failures/errors; both lints, package inspection and debug assembly passed. CI lint has zero errors, 77/79 warnings and one hint per variant, distinct from the earlier local 63/65 counts. Gradle 36m 5s, all 105 tasks executed. Artifact 13591809354 SHA-256 `8d9994865acf049883509c81c99f7fbbb409891d070f9b90bf1138d23305cda8`. |
| Actual isolated owner update | PackageManager41→42 succeeded with unchanged appId10148; all4 original DBs/50tables/22rows and3existing settings files retained exactly after42launch. New-store tables are additional; ProfileInstaller bookkeeping changed as expected. |
| Actual final owner APK | Optimized assembly passed in 2m 44s. Permanent package/code 42, non-debuggable, exact original owner certificate, approved artwork, retired components and 16 KB checks passed. 12,631,215 bytes; SHA-256 `d46907c39f729145d3b1169090812b054296180cdb47802eef7a0f811746679a`. |
| Earlier local debug APK | Separate QA package/code 42; supplied artwork, absence of retired overlay/foreground components and permissions, native 16 KB alignment verified. 29,843,566 bytes; SHA-256 `d96a7f6f4b59c573055376f792969e8836f4e8e499edbf95e01ec8c3b82161ed`. |
| Final CI debug APK | Separate QA package/code 42; inspected artwork, 16 KB alignment, provider and overlay retirement passed. 29,827,110 bytes; SHA-256 `a86602a421b8e2a83605806f44deb0cbac5543052a18dcabd37006c5454b4825`. Its CI debug signer is not the owner signer and it is not an in-place update artifact. |

Tool downloads were checked against the repository's pinned SHA-256 hashes:
Gradle 8.13 `20f1b1176237254a6fc204d8434196fa11a4cfb387567519c61556e8710aed78`
and Google command-line tools
`7ec965280a073311c339e571cd5de778b9975026cfcbe79f2b1cdcb1e15317ee`.
Temurin `17.0.21+9` was independently checked against its official release metadata
(`8b9b611d917e3dfd8419d3566b5949226f69b052fed7e109c1368add1dfe8711`).

## Evidence collection

`scripts/reforge_evidence.py` indexes actual JUnit XML results, screenshot bytes and
APK bytes, recording the current commit and whether the working tree is dirty.
Missing reports stay missing. Skipped, failed and successful cases are separate.
Its index does not itself prove installation or signer compatibility.

`Astravar 3.0 Reforge verification` runs the exact pushed/PR head, the Python,
historical source, artwork and schema checks, pure rules and Android JVM tests,
both lint variants and a separate-package debug APK. Native Compose captures
come from actual Android drawing under Robolectric. They are not phone screenshots.
The workflow never provisions an owner key or publishes a production release.

Historical source gates retain their original digest values. The owner's
explicit Reforge authorization is represented by unique reversible source
replacements in `scripts/reforge_source_review.json`; the current manifest's
overlay removal must reconstruct the exact pinned baseline. This permits the
authorized retirement while keeping the original source chain, schemas,
artwork and signing configuration protected. Schema 6 is now pinned byte for
byte alongside the eight previously protected schemas.

## Final source association and regression scope

The final local focused run used the same 307 source/build/schema/test input
bytes as commit `30f0b9dda4e558246ecc481a4a97b23acf898cf0`; its source-input
SHA-256 is `8b2727f33b809e4b26eb9e945a3a45ce91d39f589c4c9e14dbf4e6a115330945`.
`validation/reforge-focused-scope.json` records exactly the twelve selected
classes, fresh XML hashes and per-variant counts. Gradle retained 73 unselected
old XML files per variant. Each matched the archived earlier report byte for
byte and was separated from the final result directory before indexing. A
focused run must not be described as a clean full-suite result.

The preceding full3 run remains archived separately: debug 582 cases, 578
passed/one failed/three skipped; release 582 cases, 563 passed/two failed/17
skipped. It took 36m 21s. The failures were the now-corrected restored source
disclosure selector, debug-only diagnostic entry assumption in release, and
the release-lint call to an unreachable archived foreground-service path. The
final selected regression covers these corrected paths in both variants. The
exact pushed-head CI independently executes the full suite. Its result is
recorded in the final complete CI row above.

The 17 intentional release skips consist of three private/live opt-ins and
fourteen debug-only remote-update experiment cases. The debug suite has the
three opt-in skips. These are disclosed exclusions, not executed live tests.

## Required final acceptance

An implemented domain model alone does not satisfy a native workflow or
offline-restart acceptance case. The executed checks establish the following
coverage; a full-suite CI result and release/update evidence remain distinct
from the local selected regression.

| Owner case | Executed evidence and limit |
| --- | --- |
| A — automatic abilities | `ReforgeAcceptanceTest` reverses the accepted synthetic STR/CON equipment fixture and checks permanent INT; duplicate effects and unaccepted claims tested. Pure rules suite passed. |
| B — HP preservation | Rules tests cover 66→75 capacity, retained damage, unequip/re-equip clamp overhang, unknown HP and healing. Native equipment preview/confirmation and name-only setup both passed after correcting the inappropriate test scroll action. |
| C — damage effects | Rules tests cover qualifying attacks, typed extra dice, critical eligibility, mixed magical resistance rounding, immunity, hit/attempt charge costs, stale previews and unequip. |
| D — attunement | Rules tests cover inactive unattuned benefits, limits, composite exceptions, donor suppression and cyclic composite rejection. |
| E — spells | Rules tests cover legacy Artificer slots, attack/DC, preparation, costs, concentration replacement, eligible rest recovery and sourced spell modifiers. No commercial spell catalogue or automatic spell-effect adjudication is implied. |
| F — conditions | Rules tests cover versioned standard conditions, immunity suppression, exhaustion, explicit duration/rest expiry and reversible projections. |
| G — migration | Historical nine schemas remain exact. Adoption/backup and historical Room migrations passed in final complete CI; the ledger also preserves the earlier backup-UI readiness failure. Actual owner 41→42 startup and subsequent native gameplay preserved every original table/row in the isolated fixture. No phone database was accessed. |
| H — rich text | Eight parser cases plus native profile/link cases passed, preserving exact original content while hiding raw mentions/technical identifiers. Actual capture review found and corrected a contrast defect; corrected native rasters and measured contrast regression passed. |
| I — offline | Disk close/reopen tests passed. The actual optimized owner APK also created and edited a fictional actor offline, reviewed typed equipment, equipped/unequipped/re-equipped it and saved an explicitly initiated derived-STR roll. After force-stop, disk readback retained raw abilities, 26 points of missing HP, worn item, exact d20 face 19/modifier +5/total 24 and matching immutable receipt. A separate adopted profile retained unknown HP/STR 0 and its exact INT face 14 + 5 = 19 through restart. |
| J — synchronization | 134 backend protocol tests passed. New local-store duplicate/stale/conflicting command tests passed. Both final selected variants passed the connection regressions; full CI results and intentional private/live exclusions are disclosed separately. New Reforge sheet records remain explicitly local-only. |
| K — overlay | Native merged package-manager tests and inert-service lifecycle test passed in both variants. Actual debug APK confirms no overlay service/permission, unused WorkManager foreground service or generic/special foreground permission. Normal WorkManager job service remains available. |
| L — in-place update | Actual predecessor and code 42 APKs have the same full original certificate. Safe AOSP 35 install-r succeeded, UID unchanged; all four original databases/50 tables/22 rows and three settings files were exactly preserved after code 42 launch and gameplay. No physical installation claimed. |

An earlier broad run was interrupted after independent review found two further
correctness defects (restored notes during debounce and incomplete legacy
adoption disclosure). Its partial execution is not counted as a passing
regression suite. The later full3 results and subsequent selected rerun are
reported explicitly above. Editor draft/recovery fixes, source disclosure,
release-only diagnostics and overlay retirement received actual native tests.
Remaining warnings include legacy KTX/style suggestions, launcher variants,
shared-preference writes, WorkManager job-ID configuration and several Compose
layout/state recommendations; the reports are retained for follow-up.

## Installed owner APK gameplay evidence

The inspected `d46907c3…679a` optimized owner APK was exercised entirely offline
on AOSP 35 after the in-place upgrade. ConnectivityService reported no active
default network. A fictional source profile was explicitly reviewed and adopted;
zero Strength remained zero and unknown HP remained unknown. An initiated INT
check rolled an actual d20 face 14, added the calculated +5 and saved total 19.
After force-stop and relaunch, the selected actor and exact native roll history
were preserved. Stored typed faces matched the immutable roll receipt.

A separate, newly created fictional actor used permanent scores
8/15/14/21/11/12 at level 9, baseline maximum HP 66, current HP 40 and AC 10.
The native equipment editor accepted three explicitly reviewed typed setters:
Strength 21, Constitution 16 and AC 20. The actual installed application
produced the following transitions:

| Native state | STR | CON | AC | Max HP | Current HP | Outstanding damage |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Carried; all effects inactive | 8 | 14 | 10 | 66 | 40 | 26 |
| Equip preview and confirmed worn | 21 | 16 | 20 | 75 | 49 | 26 |
| Confirmed unequip | 8 | 14 | 10 | 66 | 40 | 26 |
| Confirmed re-equip | 21 | 16 | 20 | 75 | 49 | 26 |
| Force-stop and cold relaunch | 21 | 16 | 20 | 75 | 49 | 26 |

Intelligence remained 21. The calculation inspector still showed permanent
Strength 8 and effective 21. An explicitly initiated STR check automatically
used `1d20+5`, rolled face 19 and saved total 24. After the final force-stop and
cold relaunch, the last actor, derived statistics and exact native history
again showed `19 + 5 = 24`.

Stopped disk readback independently confirmed unchanged permanent scores,
baseline maximum HP 66, baseline Constitution modifier +2, current HP 49,
`hpDamage=26`, worn state, exactly three effects, actor revision 5 and the exact
roll receipt. History retained Create 1 → item 2 → equip 3 → unequip 4 → equip 5;
the pure roll kept revision 5. No game time or turn advanced and no rest was
invented. All original fifty tables and three settings files remained exact
even after these new workflows. The combined final gameplay snapshot digest is
`61f475202ffdc606bfc53ff1ab938421dc9e95a10f649f4d06f278ce36c5d439`
(five databases, 56 tables, 36 rows; original tables are a strict retained subset).

The actual runtime service dump contained Room's multi-instance invalidation
service with `startForegroundCount=0`; no overlay service was running. The guest
crash buffer had no Astravar entries. Its initial System UI ANR and Bluetooth
failures, plus a native emulator cleanup fault after successful console shutdown,
remain environment diagnostics. They are not hidden or attributed to Astravar.
