# Project Genesis — execution interruption and exact resume state

The execution environment became unavailable on 2026-11-11. Terminal recovery timed out and no further filesystem access was available. No final Genesis APK was signed or delivered. This is a recovery checkpoint, not completion of Project Genesis.

## Durable repository state

- Draft PR15: https://github.com/Brenmack15/Astravar-Overlay/pull/15, branch `feat/astravar-project-genesis`, remote head `2c4dafca4fe8c82055e09c1f2e87caa717175ffb` at interruption.
- Original baseline: PR14 / `3543e83bfca5e589f1bbedb09964431ce94a2319`, package `com.yazsras.astravar`, code49.
- Published parser checkpoint: `504b2a608bd5ead9fafe5e37f2316301f07d1ec5` on `checkpoint/astravar-genesis-package-proof`.
- Published connected-data foundation: `aa61cee60deebfdb1ee0fc4946cdd5f0263a92b3`, tree `512ee6df38ac3dfe8b6bb2571c0dfe17800af38e`, parent504. This branch now also contains this handoff. Application code at the foundation checkpoint is not the latest working tree and is not release-approved.
- The foundation was verified to match all35 committed files of local backend `9849449`, cherry-picked as `75ec0cc`, then published as `aa61cee`. No unrelated PR was merged or overwritten.

## Recover local work first

Root checkout: `/workspace/scratch/30508fda364e/Astravar-Overlay`, branch `feat/astravar-project-genesis`, clean HEAD `aa61cee` before the environment failed.

Active implementation worktree: `/workspace/scratch/30508fda364e/genesis-authority-wave`, branch `feat/astravar-project-genesis-authority`, HEAD `9849449` plus substantial UNCOMMITTED backend, UI, test, native-harness and documentation changes. Those later changes were successfully written locally but could not be committed or uploaded after the environment disappeared. This handoff preserves their description, not their file bytes. Recover that working tree if the environment can be reattached. Do not reset, clean, recreate it, or assume the published foundation contains those changes. If it cannot be recovered, explicitly acknowledge the missing work and reconstruct the listed follow-up against the published checkpoints instead of claiming it survived remotely.

The active worktree began from local `4c6674e` (published equivalent `c3c8fee`). Root additionally retains the published NavigationV2Test readiness correction and DATA_AUDIT/CHECKPOINT updates from2c4 and the package-parser fix504. Integrate individual B commits onto root; do not merge the old parallel ancestry or discard these corrections.

## Verified evidence

Exact published source2c4 passed full hosted run42942460278: rules166 passed; debug755 passed/3 intentional skips; release741 passed/17 intentional skips; Python201 passed; bridge134 passed. Lint had zero errors. Artifact13823222487 SHA256 `877e64dcf8a4c92660287dd48b710ebac38761c94ba33c1c08c73940e46d30c7`. The screenshots from that artifact are host-rendered Compose/Robolectric captures, not physical-device evidence.

Native run42942460297 built the candidate and frozen49 baseline, successfully seeded fictional49 records and installed50 with ordinary adb install-r. Pre-open file hashes, UID and first-install time matched. The harness then stopped BEFORE candidate launch because Android16 reports appId rather than its expected userId. Parser504 fixes that strict proof;14 helper tests and rechecking the actual dumps passed. Do not claim native migration, first-open, gameplay, gesture or owner-device acceptance from that run.

The latest completed B batch executed181 app tests:174 passed,7 failed; separate unfiltered rules166/166 passed. This is not a green B gate. Results were preserved locally under `genesis-authority-181-results`; log `genesis-authority-verification.log`. Earlier89/41 and48/7 runs diagnosed the small-row SQLite TEXT/getBlob trailing-NUL bug, fixed by explicit CAST to BLOB with scalar/Unicode/NUL regressions. Native-test Kotlin compiled before the final uncompiled follow-ups. The latest199 Python schema8 upgrade/import/connected-record/package helper checks passed. No physical Galaxy S26/Android17 test occurred.

All seven last B failures were diagnosed and fixes written but NOT rerun:
1. Two source-item acquisition journeys rejected a newly connected actor via the old name-copy identity guard. New inspectManagedCampaignIdentity requires the same Room database, exact current reviewed state, verified explicit activation receipt, unchanged origin/creation identity and current projections.
2. One fixture incorrectly expected each item to duplicate its actor enrollment proof; enrollment correctly references the immutable parent activation receipt. Its assertion was corrected.
3. Two UI selectors matched a retained background control and a modal control; selectors now explicitly target the dialog, retaining all journey assertions.
4. The compact Character controls button measured40dp; its product modifier now explicitly measures48dp.
5. The fitting-table fixture used unsupported one-column Markdown. It now uses two columns and asserts zero scroll distance before testing gesture capture.

## Uncommitted implementation to retain

- Schema8 campaign/actor/item authority and backup follow-up: targeted new-command/affected-record validation replaces whole-campaign export checks. Unrelated archives above64MiB must not block ordinary creation/membership. Managed acquisition retains the original8M-character/8MiB playable recovery guard without adding a whole-source-archive dependency. Explicit Complete backup still has a64MiB document cap.
- Request-ID collision guards prevent ordinary source/playable commands from reusing immutable authority parent IDs. New generated campaign/actor IDs conform to the existing sync protocol. Existing/provided IDs are retained.
- Same-transaction HP, conditions, configured resources, ownership, quantity, equipment/container projections; exact immutable resource bindings; removed configured counters remain retained source history with blocked independent spending. Role changes take precedence over retained companion history in roster/search/inventory.
- Connected character/item/companion creation; campaign/NPC creation; selected campaign preference; membership and frozen source review; Complete backup UI; source/playable archive recovery boundaries. Source-only actors retain unknown statistics and are not silently made playable.
- Known leveled spells offer Prepare where the unchanged engine rejects casting. Inactive historical companion counters remain readable and cannot be edited as active pools, including Eek tools.
- Adaptive short-window character chrome, rich-table gesture protection, haptics preference, compact48dp control target, and full-scaffold true200% landscape tests. CampaignHub now owns its dark background; the first capture exposed a white transparent header and the fix still needs a refreshed capture.
- Native journeys exercise campaign/NPC creation/restart, connected item editing, source/playable companion HP agreement, swipes, quick-action drag/tap persistence, rotation,200% landscape controls, actual document-picker complete-backup save/review/merge, and independent SQLite/file readback. These new native journeys have NOT executed successfully.

Important local artifacts if recovered: `genesis-connected-screenshots`, `genesis-screenshots`, `release_recovery`, `genesis-schema8-python.log`. New native helper files include `scripts/genesis-connected-readback.py` and strict schema8 extensions of existing upgrade/import helpers. Root also added the package-parser504 patch to B. Preserve it when resolving the CI script.

## Incomplete modifier integration — finish before building or publishing

New `GenesisLegacyModifierReview.kt` and19 tests were added. They require explicit unselected effect choices and a declaration that an old modifier summarized existing effects. Proof requires accepted active effects, exact physical effect references, and recovery of BOTH original ability score and modifier after disabling selected effects. No new effects, automatic approvals, true flat-bonus conversion or CON/HP baseline are invented. The pure/helper tests passed in the181-test batch; the subsequently added integration hooks did not run.

Successfully written AFTER that batch's compilation:
- Optional `legacyModifierResolutions` on the review DTO with EncodeDefault.NEVER so empty legacy review bytes/fingerprints remain stable.
- Pending initial previews, complete validation of nonempty proof lists, mandatory acceptance validation and fresh rereview with identical choices.
- A frozen pre-field serializer/byte/fingerprint/retry regression.
- `ui/GenesisLegacyModifierReviewUi.kt`, its GenesisCampaigns review/acceptance gates and contextual owner labels. Exact IDs are shown only for otherwise ambiguous owner choices. These UI hooks are UNCOMPILED.

NOT applied when the environment failed:
1. `GenesisAuthorityValidation.kt` must validate modifier resolutions against the receipt's frozen `review.playable.character` and `review.source.abilities`, not today's later character.
2. Add acceptance -> unequip -> complete backup/restore regression, unchanged original source/origin, tampered/missing/stale proof rejection and same-command request binding. Existing backup test count remains20; these integration tests do not exist yet.
3. Add the four planned GenesisCampaignUiTest cases: unchecked candidate/global acknowledgment alone cannot accept; explicit valid STR8/+5 summarized by accepted SET19 persists original proof and unchanged effects; unknown score stays unresolved without mutation; proposed effect is not selectable; verify contextual owner labels without IDs except real ambiguity. The attempted test patch did not execute.

Do not expose or sign this partial modifier integration before completing the historical validator and its integration tests.

## Next execution order

1. Recover local working tree, inspect status/diffs and current remote refs. Preserve all original signing/data/source pins and unmerged work. Use existing audit and coverage matrix; do not restart or regenerate the1173-site static inventory as runtime proof.
2. Finish the three modifier integration items above and any missing identity-adapter regression. Revalidate corrected acquisition, >64MiB unrelated archive handling and original recovery bounds.
3. Run only affected suites plus newly integrated modifier tests, then the remaining lintDebug/assembleDebug/native-test Kotlin checks. The app filter must not hide the rules suite. The last181 results justify focused reruns; broaden if another failure or changed dependency warrants it.
4. Inspect refreshed campaign/compact-modal screenshots. Commit backend, UI and native/evidence changes separately; regenerate exact reversible source review where required. Integrate onto root afteraa61 and publish the actual candidate head toPR15 with a guarded ref update.
5. Run fresh full and native workflows on the actual candidate. Fix real failures without weakening preservation assertions. Obtain first-open schema6->8, later canonical writes, connected journeys, restart, import/rollback and file/SQLite proof.
6. Privately re-sign the exact verified native CI release payload with the original owner key, proving unchanged non-signature ZIP entries and embedded source SHA. Verify package/code50, nondebuggable, minSdk26/target36, full original cert `4c5cb0113cae5d0dee2619ee30f5b3c593d4aa897e1fa38dee7ed5561ec4e847`,16KiB alignment, APK hash and artifact/source binding. Owner-key materials stay private and never enter CI or Git. No new signing identity. Report CI signer migration, static owner-certificate compatibility, owner-key emulator installation and physical-device acceptance separately.

## Remaining full-project gaps

Even a passing B candidate is not the completed Genesis definition of done. Encounter creation/lifecycle, full class/progression parity, parts of historical creation/editor modernization, true unmapped legacy modifiers, managed crafting consumption/output, remote acquire-and-assign, exhaustive per-control native coverage and performance measurement remain. Complete backup has the explicit64MiB limit. Physical-device acceptance is unperformed.

Managed crafting needs a reviewed transaction spanning legacy craft consumption, affected canonical actors and one same-ID output with explicit destination/provenance; simply removing its guard or consuming again through changeQuantity would corrupt consistency. Preserve the historical source-only craft workflow until the connected path is implemented and tested.

Resume from this evidence. Do not call the unavailable working tree, a passing earlier checkpoint, or successful compilation a completed release.
