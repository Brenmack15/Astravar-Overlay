# Verification ledger

Status: IN PROGRESS. Results below distinguish executed checks from planned checks.

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
| Current Python suite | 146 tests passed, 6.425 seconds |
| Authenticated bridge backend suite | 134 tests passed, zero failures/skips, Node 24.22.1, 2.277 seconds |
| First coherent Reforge rules suite | 159 tests passed, zero failures/errors/skips, 4.207 seconds of JUnit suite time |
| First Reforge Android compilation | Identified and corrected nullable cross-module/saver and dialog-call errors; focused rerun in progress |
| Coherent focused native run | 43 Android tests executed: 41 passed, two test harness scroll failures; 139.359 seconds of JUnit suite time, 7m 3s Gradle wall time |
| Same-fixture baseline native captures | Two baseline-only capture tests passed; 11.414 seconds suite time, 1m 13s Gradle wall time; application source remains commit 852bbd4 |
| Physical Galaxy S26 | No attached device; Android version, installation and phone behavior unverified |
| Accelerated emulator | `/dev/kvm` unavailable in this runtime |
| Owner signing | Original private key recovered outside source; private/public match and exact historical certificate verified; final signed APK inspection pending |

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

## Required final acceptance

An implemented domain model alone does not satisfy a native workflow or
offline-restart acceptance case. The focused run established the following
coverage; final integrated debug/release results remain pending.

| Owner case | Executed evidence and limit |
| --- | --- |
| A — automatic abilities | `ReforgeAcceptanceTest` reverses the accepted synthetic STR/CON equipment fixture and checks permanent INT; duplicate effects and unaccepted claims tested. Pure rules suite passed. |
| B — HP preservation | Rules tests cover 66→75 capacity, retained damage, unequip/re-equip clamp overhang, unknown HP and healing. Native equipment preview/confirmation passed; native name-only setup test needs its inappropriate scroll action corrected. |
| C — damage effects | Rules tests cover qualifying attacks, typed extra dice, critical eligibility, mixed magical resistance rounding, immunity, hit/attempt charge costs, stale previews and unequip. |
| D — attunement | Rules tests cover inactive unattuned benefits, limits, composite exceptions, donor suppression and cyclic composite rejection. |
| E — spells | Rules tests cover legacy Artificer slots, attack/DC, preparation, costs, concentration replacement, eligible rest recovery and sourced spell modifiers. No commercial spell catalogue or automatic spell-effect adjudication is implied. |
| F — conditions | Rules tests cover versioned standard conditions, immunity suppression, exhaustion, explicit duration/rest expiry and reversible projections. |
| G — migration | Historical nine schemas remain exact; original-store adoption/backup tests passed. Full historical Room migration suite remains pending. No actual phone database was accessed. |
| H — rich text | Eight parser cases plus native profile/link cases passed, preserving exact original content while hiding raw mentions/technical identifiers. Actual capture review found a contrast defect; corrected-source recapture is required. |
| I — offline | Disk close/reopen test with actual equip and explicitly initiated SecureRandom roll passed. Unknown/zero, exact faces, revisions, resources and notes drafts persisted. Native process-manager restart remains separate evidence. |
| J — synchronization | 134 backend protocol tests passed. New local-store duplicate/stale/conflicting command tests passed. Full Android connection regression suite remains pending; new Reforge sheet records remain explicitly local-only. |
| K — overlay | Native merged package-manager manifest test passed; final actual APK manifest inspection remains required. |
| L — in-place update | Actual predecessor package/code 41/certificate verified. Final owner-signed code 42 APK and safe package-manager update test remain pending. No physical installation claimed. |

The first broad run was interrupted after compilation when independent review
found two further correctness defects (restored notes during debounce and
incomplete legacy adoption disclosure). Its partial execution is not counted as
a passing regression suite. The final run must begin after those source fixes.
