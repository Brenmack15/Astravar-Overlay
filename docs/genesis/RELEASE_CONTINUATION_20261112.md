# Genesis release continuation — 12 November 2026

This is a recovery checkpoint, not a completed release. Read it after the original
`docs/genesis/SESSION_RECOVERY.md` and `CRAFTING_CHECKPOINT.md`. Older checkpoints
describe their historical state; the verified facts below supersede their stale
crafting and campaign implementation/test status.

## Exact source and active verification

- PR15 remains open, draft and unmerged, based on `3543e83`. Its B head is
  `7e229a43e4d66e91fa09f35ea39e1453387f38fe`.
- Current C release candidate: `e4ab3e6895300aed66e87cf64fce669252d934b6` on
  `feat/astravar-project-genesis-native-fixes-20261112` and
  `checkpoint/astravar-genesis-crafting-20261112`.
- Exact local equivalent `44e029bbe24b6441ee2ee498990a9e2179acfbed`, tree
  `c0c4634d5f75cc800e4910c59922f20c3c1c6ea5`, in
  `/workspace/scratch/30508fda364e/genesis-crafting-continuation`.
- Current full run: https://github.com/Brenmack15/Astravar-Overlay/actions/runs/43143450644
- Current native run: https://github.com/Brenmack15/Astravar-Overlay/actions/runs/43143450690
- These E4ab runs were in progress at this checkpoint. Inspect actual results.
- Earlier combined C `4c6bdcb45a08496fb8af47313d038d2b8ca2f240` full run
  `43139619440` PASSED; native run `43139619514` failed the two observation
  cases described below. Both artifacts are recovered and source/digest verified.
- Earlier B7e229 full run `43137497126` PASSED; native `43137497151` has the
  same two later failures. Do not sum overlapping variant/rerun counts.

E4ab follows C4c6 through two preserved commits: local69f2831 / remote3fb7799
clarifies actual connected-backup portability limits; local44e029b / remoteE4ab
updates native observations without dropping gameplay assertions. First Back from
companion Combat now explicitly verifies Sheet and HP 6/12, then second Back
verifies the source roster. Initial Sheet capture searches UP for the known
upper Roll dice control rather than exhausting its unchanged 30-second discovery
budget below skills. Native Kotlin compilation passed in 35 seconds.

C4c6's source-review guard passed after exact reversible additions were recorded.
Original source/schema/artwork/signing pins and verifier logic remain intact.

## Verified implementation and tests

Connected campaigns, characters, NPCs, companions, item creation/equipment and
shared-value review use explicit authoritative identities. Horizontal character
paging, saved reading positions, accessible quick-action rearrangement, guided
creation and connected backup are implemented. Existing campaign truth and
accepted mechanics are retained; imported content does not become approved by
being displayed.

Connected crafting now freezes materials, project and actor revisions, explicit
output owner and optional container. One Room transaction consumes materials and
creates the same physical output identity in relevant views. Its retained proof
survives subsequent edits/transfers. Pending command recovery reads the original
exact receipt and never repeats an unacknowledged craft.

- C4c6 full artifact: debug 898 total / 895 passed / 3 skipped; release 898 total /
  881 passed /17 skipped. Rules 166, Python 272 and bridge 134 passed. No test
  failures/errors; lint 0 errors (debug 81 warnings+1 hint, release 83+1).
  123 host captures. Full ZIP SHA256:
  `d15b8b56297291337598a5ccb6484d755e3ddf5d2af90a2ba7eb8ee62df51daf`.
- Both B7e229 and C4c6 actual native upgrades passed seed49, verifyAfterUpgrade
  and canonicalWriteSurvivesRestart. Independent host readback rerun on the
  captured databases confirms unchanged package/UID/first-install time, exact
  additive schema 6→8 row/receipt retention, and the later canonical HP write
  surviving restart with the retained original source untouched.
- Those native runs also passed overlay 3, separate QA SQLite 2 and Visual Rebirth 1
  tests, but failed PlayerFirst after healed-companion Back and Genesis during
  initial Roll dice discovery. Connected backup/crafting and later update/import
  stages did not run. Full native acceptance remains pending on E4ab.
- C targeted crafting/related suite 106/106 and B provenance suite 46/46 passed;
  independent crafting readback's 13 adversarial Python cases also passed. These
  overlaps are not extra unique tests beyond the later full totals.

Evidence remains under `genesis-new-ci-evidence`, `genesis-crafting-second-results`,
`genesis-crafting-reviewed-screenshots` and named logs in the workspace root.
Host screenshots are explicitly distinct from Android emulator screenshots.

## Remaining release gates

1. Resolve any actual current full/native failure without weakening assertions.
   Preserve and publish corrections before testing. Do not rerun stale heads.
2. Only advance PR15 to the combined source after its relevant gates pass, using
   a non-forced expected-head update. Never merge main or unrelated PRs.
3. Obtain the exact source-bound native release APK. Verify artifact digest,
   run HEAD, SOURCE_COMMIT and embedded version-control-info source revision.
4. Privately re-sign that same tested payload with the original owner key; do not
   rebuild a different payload. Compare every non-signature ZIP entry byte for
   byte and validate package/version, non-debuggable status, 16 KiB alignment,
   certificate, checksum, permissions and retained branding.
5. Original owner cert:
   `4c5cb0113cae5d0dee2619ee30f5b3c593d4aa897e1fa38dee7ed5561ec4e847`.
   Trusted owner code49 APK SHA256:
   `45fcd71a2e1294f8865e700b3e8539d8bfc92c15dffe43c26daaaf2a065b3fe5`.
   Its embedded source is 7bb03b5e, direct parent of 3543; app/rules trees are
   identical, with 3543 changing only native instrumentation.
6. A fresh local API 36 software emulator is being prepared for a separate actual
   owner-key 49→50 upgrade. No result exists yet. It runs in one persistent exec
   namespace with a file command queue under `genesis-owner-upgrade-emulator`.
   A newly spawned exec has a separate PID/network namespace and cannot address
   that adb instance directly. Preserve logs if this environment cannot finish.
7. Deliver the APK privately with checksum/signing report, before/after captures,
   actual test results and the remaining scope. No physical Galaxy S26 / Android 17
   testing has occurred. No Genesis owner-signed APK exists at this checkpoint.

Private keys/configuration remain outside Git and CI. Never print, upload or
commit them. Never clear/uninstall Astravar to bypass an upgrade failure.

## Isolated D encounter implementation — preserve separately

Worktree `/workspace/scratch/30508fda364e/genesis-encounter-continuation`, branch
`recovery/astravar-genesis-encounter-20261112`. Published checkpoint:
`checkpoint/astravar-genesis-encounters-20261112` at
`b0fac901ea62f7c296a65adc616f2f97ebd5bf4a` (local `a95bb58`).
Foundation locald271df7 maps to remotef8af7bbc; UI local7ded6af to remote67f9cf9b.
These are exact trees, descended from C4c6, and do not yet include later C fixes.

D adds explicit campaign participants, manual initiative/order, start/next/end
and reopen-setup tracking with immutable history and no automatic gameplay state
changes. Additive Room 8→9 tables and complete-backup integration are implemented;
13 backend, 2 migration and 4 initial UI tests were added but have NOT run.
Generated schema 9, source-review additions, schema/native-helper expectations,
compilation and meaningful tests remain required. Native encounter coverage is
being implemented separately. UI follow-up fixes for Back-handler precedence,
delayed campaign preference loading and first-flow emissions may still be dirty:
inspect and preserve them before any checkout or build. Read ENCOUNTER_CHECKPOINT.

Do not merge D into the C release or cancel C verification to force an enormous
unverified rewrite. Publish D progress before tests. A new source must pass its
own migration/backup/native gates before it can replace the tested C candidate.

The private signing helper is prepared and passed 11 synthetic verification tests:
`genesis-private-recovery/helpers/genesis_sign_verified_payload.py`. It requires a
clean worktree at the exact tested published SHA, not the differing local commit
with an equivalent tree. No signing has been executed. The local API 36 software
emulator has not installed Astravar: two boot attempts hit Android watchdogs
blocked in overlay-filesystem fsync. A preserved copied AVD is now booting from
private tmpfs; no OS watchdog or app assertion was weakened. Current details are
in `genesis-owner-upgrade-emulator/LOCAL_OWNER_UPGRADE_CHECKPOINT.md`.

## Full-project scope still incomplete

- The C release candidate has no encounter lifecycle. Isolated D implementation
  now exists, but remains unverified and must not be described as part of C.
- General class/subclass/multiclass progression; current advancement preserves
  manual accepted gains and the pinned Artificer behavior.
- A unified replacement for every historical project/connection/feature/status
  editor; some current workflows deliberately retain their established forms.
- Complex mechanical crafting materials, automatic recipes/costs/time and
  managed remote acquire-and-assign; unsupported cases remain guarded.
- Large current-roster/wide-row performance. Avoiding unrelated historical
  archive export is verified; all-current-character/placement scans still exist.
- Exhaustive per-control native, gesture-conflict, accessibility and physical
  handset acceptance. Native crafting covers one material/no container; cold
  unacknowledged recovery and other variants have host-test evidence.

The connected backup preserves the supported campaign/playable/authority graph,
source history and Room note recovery drafts. It is an additive missing-record
merge with conflict rejection, not overwrite/rollback. Its limit is 64 MiB.
Weapon backup remains separate, as disclosed in the UI. Preferences/quick-action
layouts, connection setup and other private editor drafts are outside the format.
Externally selected portrait images retain references, not portable image bytes
or URI grants. Preserve original image files for another-device restoration.
This scope is now explicitly described in E4ab's backup page; its fresh release gates are pending.

Resume from these exact checkpoints and any newer guarded descendants. Do not
restart the reconstruction or discard dirty work. The original lost worktree's
bytes were not recovered; surviving published history and reconstructed changes
are distinguished in `RECOVERY_20261112.md`.
