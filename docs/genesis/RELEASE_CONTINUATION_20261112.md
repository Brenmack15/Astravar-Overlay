# Genesis release continuation — 12 November 2026

This is a recovery checkpoint, not a completed release. Read it after the original
`docs/genesis/SESSION_RECOVERY.md` and `CRAFTING_CHECKPOINT.md`. Older checkpoints
describe their historical state; the verified facts below supersede their stale
crafting and campaign implementation/test status.

## Exact source and active verification

- PR15 remains open, draft and unmerged, based on `3543e83`. Its B head is
  `7e229a43e4d66e91fa09f35ea39e1453387f38fe`.
- The combined B+C candidate is `4c6bdcb45a08496fb8af47313d038d2b8ca2f240` on
  `feat/astravar-project-genesis-crafting-verification` and
  `checkpoint/astravar-genesis-crafting-20261112`.
- Combined local equivalent: `0c157b485d4670ecce4d928c317a23fae1438329`, exact tree
  `ef3358982c3cde31532e8cd70b0e6786ea5adf8e`, in
  `/workspace/scratch/30508fda364e/genesis-crafting-continuation`.
- The combined candidate includes B's null-provenance preservation fix and native
  tab observer correction through an actual merge that retains both histories.
- Current C full run: https://github.com/Brenmack15/Astravar-Overlay/actions/runs/43139619440
- Current C native run: https://github.com/Brenmack15/Astravar-Overlay/actions/runs/43139619514
- Separate B full/native runs: `43137497126` / `43137497151`.
- These runs were still in progress when this checkpoint was written. Inspect
  their actual final results; never infer a pass from the existence of a run.

The previous combined run on `9c3aa1a2` passed 272 Python tests, then rejected four
missing source-review helper/query additions. Commit 4c6 records only exact
reversible transformations; the original source/schema/artwork/signing pins and
verifiers remain intact. The source-preservation step now passed on 4c6.

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

- C targeted suite: 106/106 passed, 0 skipped; production/native Kotlin compiled;
  QA APK assembled; debug lint 0 errors, 68 warnings, 1 hint.
- B provenance regression suite: 46/46 passed, including two successive mixed
  acquisitions and exact backup/restore preservation.
- C independent crafting readback: 13 adversarial Python cases passed, plus 17
  existing connected-readback cases.
- Earlier B 9f646 full run 43132381262 passed: debug 868 passed / 3 skipped and
  release 854 passed / 17 skipped (871 cases per variant), rules 166, Python 259,
  bridge 134. Lint had 0 errors. These are earlier-source results, not substitutes
  for current source verification. Do not sum overlapping variants/reruns.
- Earlier B 9f646 native upgraded 49→50 with the same UID/first-install time and
  displayed retained HP. It stopped at a test observer selecting the quick-action
  row instead of section tabs. The observer is fixed; no later readback/journey
  from that failed run can be claimed as passing.

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

## Full-project scope still incomplete

- Encounter creation, initiative roster and multi-actor encounter lifecycle.
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
Clarifying this scope in the backup-page copy remains a small follow-up.

Resume from these exact checkpoints and any newer guarded descendants. Do not
restart the reconstruction or discard dirty work. The original lost worktree's
bytes were not recovered; surviving published history and reconstructed changes
are distinguished in `RECOVERY_20261112.md`.
