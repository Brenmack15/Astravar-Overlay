# Genesis release continuation — 12 November 2026

This is the current recovery handoff, not a completed release. Read the original
`docs/genesis/SESSION_RECOVERY.md` at `ee04d5f` first, then this file and the
linked milestone checkpoints. Later verified facts here supersede older status.

## Execution interruption: recover before doing more work

The execution environment became unavailable after the implementation and test
runs below. Calls reported an exec-server disconnect, a recovery timeout, and
"No usable execution environment is available." No reset, clean, overwrite,
reinitialization or destructive migration was performed. The survival of the
current local worktrees is UNKNOWN; do not describe them as lost without checking.

This is separate from the original interrupted `genesis-authority-wave` tree,
which was unavailable at the start of this session after read-only searches.
Its published history survived and missing follow-up was reconstructed, as
documented in `RECOVERY_20261112.md`. Do not restart that completed recovery.

First attempt to recover these existing directories and commit objects:

| Worktree under /workspace/scratch/30508fda364e/ | Local state last observed | Durable publication |
| --- | --- | --- |
| Astravar-Genesis-Recovered | recovery/astravar-genesis-20261112, a1dc2ff1cab01b5aa2e5ad4b74bd5917308e9e1e | B7e229 below, exact equivalent tree |
| genesis-crafting-continuation | recovery/astravar-genesis-crafting-20261112, 44e029bbe24b6441ee2ee498990a9e2179acfbed, clean | E4ab below, tree c0c4634d5f75cc800e4910c59922f20c3c1c6ea5 |
| genesis-encounter-continuation | recovery/astravar-genesis-encounter-20261112, local803668e, last observed clean | Core/schema through local764cf51 published; later local commits only partly recovered |
| genesis-release-source-4c6bdcb | detached exact4c6, clean | Historical signing-source checkout, superseded |

Inspect status, worktree list and commit objects before checkout or reconstruction.
Immediately preserve/publish surviving missing source, especially local991dd4a,
e023321 and803668e. No force-push or replacement of existing dirty files.

## Published source map and PR boundary

- PR15: https://github.com/Brenmack15/Astravar-Overlay/pull/15
  Still open, draft and unmerged. Base
  `3543e83bfca5e589f1bbedb09964431ce94a2319`; head
  `7e229a43e4d66e91fa09f35ea39e1453387f38fe` (B).
- Latest C routing correction:
  `c66f2738adc354c73911b08712d5161fd55b3684`, parent E4ab, tree
  `9213d24044be7c03b1c5d3cbafbe1f27f48dc6a7`.
  Branches `checkpoint/astravar-genesis-route-recovery-20261112` and
  `feat/astravar-project-genesis-route-fix-20261112`.
  The feature branch was created for full/native verification. Dispatch and final
  results must be checked; no passing result is recorded for this source here.
- Last fully built C:
  `e4ab3e6895300aed66e87cf64fce669252d934b6` on
  `feat/astravar-project-genesis-native-fixes-20261112` and
  `checkpoint/astravar-genesis-crafting-20261112`.
- Earlier combined C:
  `4c6bdcb45a08496fb8af47313d038d2b8ca2f240` on
  `feat/astravar-project-genesis-crafting-verification`.
- Isolated D encounter core/schema:
  `c6dc9a9eec55b5526346f51dd4f091baeb61d155` on
  `checkpoint/astravar-genesis-encounters-20261112`.
- Partial D native recovery:
  `a224a9d691fe97e871dc36395ad3f27e778e59ec` on
  `checkpoint/astravar-genesis-native-recovery-20261112`.
  This is NOT a runnable complete native candidate.

Do not advance PR15 until the selected combined source passes its relevant gates.
Never merge main or unrelated work. Isolated D is not part of the current C APK.

## C implementation and exact verification

Connected campaigns, characters, NPCs, companions, item creation/equipment and
shared-value review use explicit authoritative identities in the existing Room
database. Horizontal character paging, saved reading positions, accessible
quick-action rearrangement, contextual guided creation and connected backup are
implemented. Existing campaign truth and accepted mechanics remain authoritative;
displaying imported content never approves it.

Connected crafting freezes material/project/actor revisions, explicit output
owner and optional container. One transaction consumes materials once and creates
the same physical output identity across views. Retained proof survives later
edits/transfers. Pending-command recovery reads the original receipt without
repeating an unacknowledged craft. Complex unsupported mechanics remain guarded.

### E4ab full verification PASSED

Run: https://github.com/Brenmack15/Astravar-Overlay/actions/runs/43143450644

- Debug app: 898 total, 895 passed, 3 skipped.
- Release app: 898 total, 881 passed, 17 skipped.
- Rules166, Python272 and bridge134 passed; no failures/errors.
- Lint and QA inspection passed.
- Post-interruption confirmation uses CI logs/index/API, not a newly downloaded
  XML verification. Artifact13912164377 is49,044,749bytes; API SHA256
  `5426c92b27de9d69627653cfdcff8f2f3e66a417d843458e4e4b1a73a204ec60`.
  It was not locally downloaded/hashed after the interruption.

Skips include the absent private archive fixture and opt-in live HTTPS cases;
release additionally excludes12 debug-only RemoteStatUpdates and2 UI cases.
Do not sum variants, focused reruns or different source revisions.

### E4ab native verification FAILED: product routing race

Run: https://github.com/Brenmack15/Astravar-Overlay/actions/runs/43143450690

Passed seed49, verifyAfterUpgrade, canonicalWriteSurvivesRestart, overlay3,
separate QA SQLite2, PlayerFirst1 and VisualRebirth1. The previous Back observation
and Roll dice search failures were corrected without removing gameplay checks.

Genesis then failed at line220, `tap("Edit")`, after Inventory → Open hero's item.
The captured screen showed the correct Genesis Native Hero and Compass on
Equipment with "That saved sheet is unavailable or archived. No other character
was opened." The inspector never opened. This is a product failure; do not bypass
it by tapping again or deleting the Edit assertion. Connected backup/crafting
and subsequent reinstall/import/rollback stages did not execute.

Native artifact13912128421 is75,210,580bytes; ZIP SHA256
`09eb94752996baf659aa14c7de0da1ca4bfe5207efa3b1c2f8695a2a1a6cd170`.
The ZIP was downloaded and hashed, SOURCE_COMMIT and embedded APK revision were
verified, and the failure screenshot was visually inspected before interruption.
A further independent copied-database rerun did not execute after interruption.

### c66 route correction: source preserved, runtime verification pending

See [ROUTE_RECOVERY_20261112.md](https://github.com/Brenmack15/Astravar-Overlay/blob/c66f2738adc354c73911b08712d5161fd55b3684/docs/genesis/ROUTE_RECOVERY_20261112.md).

Two delegated state pairs in ReforgeCharacterSheet now become immutable values
per composition. An asynchronous effect previously combined a new loaded=true
value with its older captured empty actor list and consumed a valid route.
The correction captures readiness, records and selected actor coherently while
retaining absent/archived/detail checks and actor isolation.

New GenesisInventoryNavigationUiTest exercises the actual shell: create item,
open exact owner's item, Edit, close, repeat, recreate Activity from parcelled
state, return Equipment → Sheet, verify stable item/owner and unchanged unrelated
actor/weapon. Exactly3 files changed from E4ab (product, test, handoff), reviewed
through GitHub. No local compilation/test/signing was possible for c66.

### Earlier evidence retained

C4c6 full43139619440 PASSED with the same898 app totals,166 rules,272 Python,
134 bridge,0 lint errors,123 host captures; locally verified ZIP SHA256
`d15b8b56297291337598a5ccb6484d755e3ddf5d2af90a2ba7eb8ee62df51daf`.
Lint: debug81 warnings+1hint, release83+1. Native43139619514 failed the two
observer cases subsequently fixed in E4ab.

B7e229 full43137497126 PASSED: debug869/872 (3skip), release855/872 (17skip),
rules166, Python259, bridge134,0 lint errors,120 host captures. Native43137497151
failed the same two earlier observations.

Independent host readbacks on actual B7e229 and C4c6 captured databases PASSED:
unchanged package/UID/first-install time; additive schema6→8 exact raw row/receipt
retention; later canonical HP write persisted after restart while original source
remained unchanged. These are CI test-signer upgrades, not original-owner-key
installation acceptance. Targeted C106/106 and provenance46/46 overlap later totals.

Local evidence was under `genesis-new-ci-evidence`,
`genesis-crafting-second-results`, `genesis-crafting-reviewed-screenshots`
and named build logs. Current availability is unknown. Host-rendered captures
must remain distinct from native emulator and physical-device evidence.

## D encounter implementation: core and schema safely published

D descends from C4c6; merge the later verified C fixes without discarding D history.
Read `ENCOUNTER_CHECKPOINT.md` and the partial native handoff linked below.

Implemented: campaign participants by authoritative actor ID, manual initiative
and explicit order, reviewed start/next/end/reopen-setup, immutable history,
campaign context and direct playable-actor combat/return. Source-only actors keep
unknown mechanics. No automatic HP/resource/rest/time/weapon turn-budget changes.
Additive Room8→9 introduces only encounter and participant tables; complete backup
retains the graph/proofs and omits empty new fields for older wire compatibility.

Published ancestry (local equivalent → GitHub):

| Local | Published |
| --- | --- |
| d271df748e8743f773051aeaca180fb509b28ff5 | f8af7bbc9119ff6d6d2f8a1f8d6a9e9ba2670fef |
| 7ded6af51d0e679bdc250f794cdbe067cee94b46 | 67f9cf9b752f265ef8b328d8f1943d24e5c359f9 |
| a95bb585bae39fbbe57970c8180cf9d274a7989d | b0fac901ea62f7c296a65adc616f2f97ebd5bf4a |
| 50c140348687acd3e136bd4832c0e313cbc876cd | 3b45a570a1243e6042784eeea8209ba8f7e5f47b |
| f4f830d | 01c1ac916290b5bb3cb0e8005faaac3caef17b80 |
| 764cf51 | 62e182afc0ce58cfec7c26ad2d05f3fd5792606f |
| 3856bb5 (exact3-line Unit patch reconstructed after outage) | c6dc9a9eec55b5526346f51dd4f091baeb61d155 |

Schema9 is unedited Room output: SHA256
`268860d5fc88e9f2a79406649230bc9fc71a59e19aefefcdec9a2a4330286111`,
Room identity `bde66989e7d5999afcd1960e94e04758`,42 entities.
All40 schema8 definitions unchanged. The additive guard passed12 historical pins,
actual8→9 DDL comparison, nullable initiative, restrictive FKs and unique order.
Nine adversarial guard tests passed. Historical source pins/verifier are intact.

D focused verification before outage:

- First batch: rules166 passed, production and unit-test Kotlin compiled.
  Gradle daemon then vanished before app XML; this is not an app assertion result.
  Concurrent software emulator activity stopped; OOM was not proven.
- Second batch:92 reported tests,90 passed,2 failed.
- New13 backend encounter cases did not execute: JUnit class initialization
  rejected3 inferred non-Unit coroutine methods. Exact3-line Unit fix is now
  published at c6dc but has NOT been rerun.
- GenesisEncounterNavigationUiTest timed out opening Hero actions after successful
  companion recreation/Back. Investigate using the common C snapshot correction.
- Passing suites: CampaignArchiveMigration1, ArchiveSchemaCompatibility4,
  Currency4, CampaignMigration1, ChatGptPromptRepository14, AuthorityBackup21,
  Crafting16, EncounterMigration2, StorageMigration17, PartyMigration1,
  CharacterRouting4 and EncounterUi5.
- Lint/debug assemble were not reached because tests failed.
- XML/logs last saved under `genesis-encounters-second-results`,
  `genesis-encounters-focused1.log`, `genesis-encounters-focused2.log`.

Another D product issue is identified but UNFIXED: the saved encounter editor
mixes live actorState readiness with captured actors in its initialization effect.
Freeze one composition Pair and add an existing-saved-setup edit/revision regression.
Current5 UI tests do not establish that case. Do not invent a passing result.

## Missing local native work: partial source recovery

[Native recovery handoff](https://github.com/Brenmack15/Astravar-Overlay/blob/a224a9d691fe97e871dc36395ad3f27e778e59ec/docs/genesis/NATIVE_ENCOUNTER_RECOVERY_20261112.md)

Local991dd4a changed10 files (642 additions/33 deletions). Full SHA/blobs are
inaccessible. Recovery branch a224 is based on62e182, not the later Unit fix.
Three complete reconstructed files are preserved and fetched back text-equal:

- GenesisEncounterInstrumentationTest.kt:148 lines.
- scripts/genesis-encounter-readback.py:177 lines.
- scripts/ci-emulator.sh:complete recorded diff applied to exact base; new blob
  e926af21e3af5df584891606aeb2f35057c968a4 matches recorded original prefix e926af2.

This proves the reconstructed source survived on GitHub, not full original-commit
identity. Seven files remain to recover or rebuild:
scripts/tests/test_genesis_encounter_readback.py;
scripts/genesis-connected-readback.py; scripts/genesis-crafting-readback.py;
scripts/genesis-upgrade-readback.py; scripts/native-campaign-import-readback.py;
scripts/native_recheck.py; scripts/tests/test_native_campaign_import_readback.py.

The recovered CI stage asks for schema9, but these dependency changes are missing.
Do NOT dispatch that partial branch as a complete native candidate.
Before outage222 Python cases passed together, then17 encounter cases including
2 new adversarial proofs passed:224 unique observed passes across runs, not one
final combined224 run. Shell syntax/diff passed. Native Kotlin was not compiled.

Local e023321/803668e changed only GenesisEncounterNavigationUiTest diagnostics:
separate Character/Combat waits; retain capture Activity; on any Throwable during
15s selection wait dump all unmerged semantics, optionally capture
genesis-encounter-route-failure-$tag, throw tagged AssertionError with original
cause. 803668e widened catch AssertionError to Throwable to include ComposeTimeout.
No product changes in those two commits. Recover exact blobs if possible.

## Signing, APK and original-owner installation boundary

No Genesis owner-signed APK has been produced. No physical Galaxy S26 / Android17
acceptance exists. No owner data was used in the fictional tests.

- Application ID remains `com.yazsras.astravar`.
- Original owner cert SHA256:
  `4c5cb0113cae5d0dee2619ee30f5b3c593d4aa897e1fa38dee7ed5561ec4e847`.
- Trusted owner code49 APK SHA256:
  `45fcd71a2e1294f8865e700b3e8539d8bfc92c15dffe43c26daaaf2a065b3fe5`.
  Embedded source7bb03b5e is parent of3543; app/rules trees identical.
- Trusted APK and existing private owner key were recovered privately and checked
  against full cert/repository records. Private durable originals remain the
  recovery source if local copies are unavailable. Never publish keys/configs,
  credentials or private recovery identifiers in Git or CI.
- Local helper `genesis-private-recovery/helpers/genesis_sign_verified_payload.py`
  passed11 synthetic checks before interruption, but its source was not durably
  recovered. Availability unknown; do not assume signing capability from this note.
- Sign ONLY the exact passing native CI payload, bound to exact source SHA,
  using the original key. Do not rebuild a different payload to sign.
  Compare every non-signature ZIP entry; validate package/version, non-debuggable,
 16KiB alignment, full cert, checksum, permissions and branding.
- Three local API36 software-emulator boot attempts failed with Android watchdogs
  and slow filesystem fsync, including a tmpfs retry. No Astravar APK was installed
  in those attempts. No OS watchdog/SELinux/app assertion was weakened.
  Processes stopped. Latest tmpfs disk copy-back was not verified.
  See local `genesis-owner-upgrade-emulator/LOCAL_OWNER_UPGRADE_CHECKPOINT.md`
  if recoverable. Do not run Gradle concurrently with this software emulator.
- Native CI test-signer upgrade results do not substitute for owner-key49→50.
  Never clear/uninstall the owner's application to bypass upgrade errors.

## Exact next steps

1. Recover the existing execution environment/worktrees/objects read-only first;
   preserve missing local commits before any new implementation or build.
2. Inspect c66 full/native runs on its feature branch. Resolve genuine product
   or test-harness failures without dropping gameplay, identity or retention checks.
   Preserve each correction before running on its exact new source.
3. Preserve/recover D991/e023/803. If unavailable, use the explicit partial handoff
   to reconstruct only missing files, label them reconstructed and verify them.
   Combine the guarded D core/Unit fix, completed native work and verified C fixes
   on a new recoverable checkpoint; do not overwrite either history.
4. Fix the pending D editor snapshot issue; run focused backend13, migration2,
   encounter UI/navigation and saved-setup regression, then full/native9 gates.
5. Only when relevant exact-source gates pass, advance PR15 by guarded non-force
   update. Preserve base3543 and unrelated branches/PRs.
6. Privately recover original signer, verify exact source artifact, sign tested
   payload, perform actual owner-key in-place upgrade and data readback. Deliver
   checksum/full-cert report plus before/after captures and honest limitations.
7. Update this handoff with every new commit, run and blocker. Never claim all of
   Genesis complete from successful compilation or partial milestone acceptance.

The user's existing implementation/testing/compatible-signing authorization
persists. The blocker is unavailable execution plus unpassed gates, not a new
permission requirement. No new approval flow is implied by this handoff.

## Remaining full-project scope

General class/subclass/multiclass progression; remaining historical
project/connection/feature/status editors; complex mechanical crafting and
automatic recipes/costs/time; managed remote acquire-and-assign; large current
roster/wide-row performance; exhaustive per-control gesture/accessibility/native
coverage; physical handset acceptance. D is manual encounter tracking, not a full
automatic combat engine. C has no D encounter lifecycle.

Connected backup is an additive missing-record merge with conflict rejection,
not overwrite/rollback, with64MiB limit. It preserves supported campaign/playable/
authority/source history and Room note drafts. Weapon backup is separate.
Preferences, quick-action layout, connection setup, other editor drafts and
external portrait bytes/URI grants are outside the format, explicitly disclosed
in the current UI. Preserve original external images and existing backups.
