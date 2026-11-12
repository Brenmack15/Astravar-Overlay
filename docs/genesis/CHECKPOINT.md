# Project Genesis

## Recovered baseline

- Branch: `feat/astravar-project-genesis`, based on the verified head of PR #14, `3543e83bfca5e589f1bbedb09964431ce94a2319`.
- PR #14 and all earlier stacked PRs remain unmerged and unchanged.
- Baseline product: `com.yazsras.astravar`, 3.2.0-rc1, code49.
- Independently recovered baseline APK SHA-256: `45fcd71a2e1294f8865e700b3e8539d8bfc92c15dffe43c26daaaf2a065b3fe5`.
- Independently inspected certificate SHA-256: `4c5cb0113cae5d0dee2619ee30f5b3c593d4aa897e1fa38dee7ed5561ec4e847`.

## Audit checkpoint, not completion

`INTERACTION_AUDIT.md` maps the existing routes and workflow dependencies.
`control-inventory.csv` records 1,173 static control/helper sites against the
baseline. These are inspection coverage, not 1,173 successful runtime checks.
`DATA_AUDIT.md` explains the separate authorities, source-copy behavior and
physical-item duplication defect. Baseline screenshots and the prior release
report are historical evidence; a fresh runtime audit is pending.

No source-of-truth cutover, destructive migration or rules conversion is
authorized by an engineering shortcut. Preserve all source records, reviews,
unknown values, original identities and owner grants. New conflict handling must
leave existing ambiguous records recoverable.

## Published implementation checkpoints

Branch: `feat/astravar-project-genesis`. Draft PR: https://github.com/Brenmack15/Astravar-Overlay/pull/15

| Commit | Recoverable work |
| --- | --- |
| `f5281372e417abd433745203c6512a9d5c72bd8a` | Baseline recovery, static interaction inventory, architecture audit |
| `ba0ed2d39d54a70f22bdb8eab4aebb1e49319261` | Physical-item ownership guards, reviewed source proofs, backup recovery guard, atomic companion ownership |
| `7100f86f2c8129bb8a0277e8f5998882f2ab247a` | Native pager, compact header, Features page, customizable quick actions, guided editors, consolidated inventory, exact-record search routes |
| `a3f35400e911690fa70e37c6c72dc209172d4581` | Native gesture journeys, frozen code49-to-code50 upgrade fixtures, hosted verification gates |
| `e539b9d216899c65922711525ec35544e96d8e4e` | Verified interaction/recovery fixes; preserved on `checkpoint/astravar-genesis-interactions` |

Version marker is now `4.0.0-genesis1`, code50. This is a development checkpoint,
not a completed release. Original package and signing configuration are unchanged.

## Verification in this execution

- Pure Kotlin rules: 166 tests passed; owner bridge: 134 tests passed.
- Python checks: 157 existing tests and 6 new upgrade-readback fixture tests passed.
- Protected source, UI foundation, upgrade integrity, branding and particle
  verification passed. The authorized legacy adapters have reversible source
  proofs; the historical protection pins were not replaced.
- Initial focused Android run: 115 tests. Five obsolete character UI assumptions
  were corrected without removing their ownership, text or touch-target checks.
  The final UI regression run passed all 41 tests across guided creation, paging,
  full application navigation, character gameplay, draft restoration and item
  transfer. It includes two new stack-splitting/equipment journeys and actual
  dialog font scaling at 200%.
- The expanded acquisition suite passed all 15 tests, including both character-
  and UTF-8-byte-limit rollback. Search deletion regression tests, companion
  creation/backup tests, exact-record routing, inventory projection, quick-action
  persistence and remaining focused repository suites also passed. These are
  targeted results, not a claim that the complete app regression gate passed.
- Production, unit-test and native instrumentation Kotlin compiled locally.
  Compilation is not evidence of native test execution.
- Native software-emulator boot succeeded locally, but baseline installation
  timed out. Hosted API36 run `42931499050` built the candidate and frozen code49
  baseline and checked their temporary CI signing pair. Its actual baseline UI
  seed failed because it tried to equip a stack of three; the rules correctly
  rejected that action. The fixture now preserves one equipped compass and a
  separate carried stack of three, with six helper tests passing. The corrected
  native upgrade has not executed. No candidate-upgrade or handset result is
  established by this run.
- Historical hosted full gate `42931499054` on `a3f3540` completed: debug 720
  passed, 6 failed, 3 skipped; release 707 passed, 5 failed, 17 skipped (729 tests
  each). Five failures were the already-corrected old character UI assumptions;
  the additional debug-only stale-route observer test was corrected and passed
  in the integrated local run. Lint reported zero errors. Intentional skips were
  the absent private archive, opt-in live-network cases, and release-only skips
  for debug sandbox cases. These are not skipped coverage claimed as passing.
  The next workflow splits debug/rules from release/lint for earlier results.
- Compose/Robolectric screenshots were visually inspected. This caught a missing
  canvas behind sheet navigation and a font-scale fixture that did not reach its
  separate dialog window. The fixture now checks the rendered text's actual
  scale. The remaining text overflow flags were traced to Compose reporting
  unused paragraph width; checks now verify actual text extents, complete text,
  ellipsis, font scale and touch bounds. Do not describe these captures as
  physical-device or emulator evidence.

## Storage cutover A integrated

Schema 7 now co-locates the four unchanged playable tables with the campaign
tables. The original database remains a read-only cutover-time archive; the
installation receipt prevents replay over later edits. Large historical values
are copied through bounded reads, and original/canonical corruption handlers
retain damaged data rather than deleting it. Recovery keeps campaign backup,
weapon backup and diagnostics available. Read `STORAGE_CUTOVER.md` before editing
this boundary or making a release claim.

Storage implementation testing passed 80 initial cases. After corruption handling
was added, 47 other selected current-product regressions and all 17 final migration
cases passed, along with lint and source/schema guards. The first combined UI/data
run compiled the product and native instrumentation and passed 64 of 65 cases; its
remaining navigation fixture tapped a control while the inventory loading layout
was being replaced. The fixture now waits for the loaded view; both navigation
tests passed on the final rerun (2 tests, 0 failures, 0 skipped). No production
behavior or original navigation assertions were removed for that correction.

Native proof tooling now requires exact schema 6-to-7 preservation, unchanged
original payload files, copied playable tables and the matching authority receipt.
A separate native heal/restart step verifies later writes in the canonical store.
Twenty strict upgrade-helper tests and 102 native import/readback tests passed
against the generated schema 7. Actual native execution on this integrated source
and a fresh complete debug/release gate remain required.

Co-location does **not** reconcile conflicting source and playable fields. Reviewed
item acquisition now reads and writes inside one production Room transaction, but
the two logical representations still have independent edit paths. Gameplay
authority, projection updates and a portable combined backup remain wave B work.
Do not describe a shared SQLite file as completed gameplay unification.

## Outstanding product work

- Complete authoritative campaign/character/companion/item reconciliation,
  transactional projections and combined recovery. Storage co-location is done;
  gameplay authority is not. Do not introduce a third database.
- First-class campaigns, campaign membership and encounter creation are still
  absent. Guided character creation does not provide full class-compendium or
  automatic progression parity.
- Companion status, typed source sheets, counters and playable mechanics still
  require integration; accepted unknown values and custom abilities must survive.
- Broaden runtime coverage beyond the implemented journeys. The static inventory
  does not prove that every control, empty state, gesture conflict or error path
  works.
- Verify large-text/landscape layouts, performance, process recreation, complete
  backup limits and realistic retained databases with direct evidence.
- Finish release validation, owner signing, APK checksum/certificate comparison,
  upgrade verification and before/after screenshot review. No Genesis APK has yet
  been accepted for delivery; physical Galaxy S26 testing has not occurred.

## Resume without restarting

1. Fetch the latest head of PR #15 and inspect its checks before modifying code.
   Preserve the checkpoints above, PR #14 and earlier stacked work.
2. Read this file, `DATA_AUDIT.md`, `CREATION.md` and `NATIVE_PLAN.md`. Use the
   existing coverage matrix; do not regenerate the 1,173-site inventory as if no
   audit exists.
3. Resolve actual focused-test and hosted-workflow failures, retaining assertions.
   Treat successful build/fixtures as evidence only for the tested behavior.
4. Read `STORAGE_CUTOVER.md`, preserve schema 7 and its retained original, and
   continue the missing architecture and product work above, then complete the
   requested QA and owner-compatible release. Never call the current projection
   or copy workflow a completed source-of-truth unification.

## Implementation sequence

1. Guard physical item acquisition at the repository, expose explicit ownership
   conflicts and preserve reviewed source proof. Build tests before a wider
   database cutover. Do not describe a one-way copy as synchronization.
2. Replace character content swapping with native paging, stable per-character
   page state, compact persistent status, customizable quick actions and search
   links to actual destinations.
3. Replace long creation forms with focused sections, context, review, safe
   cancellation and persistent draft state without inventing statistics.
4. Complete canonical record/membership reconciliation and remaining campaign,
   companion, encounter and creation integration. Existing legacy surfaces remain
   reachable until their useful behavior is fully replaced.
5. Run end-to-end native journeys, regression, upgrade preservation, visual and
   accessibility checks; then build and validate an original-owner-signed APK.

## Acceptance boundaries

The complete requested reconstruction is not yet implemented or verified. No
Genesis release APK exists at this checkpoint. A passing build, a rendered
screenshot, and historical code49 results cannot establish Genesis acceptance.
Physical Galaxy S26 / Android 17 acceptance requires actual handset evidence.
