# Astravar 2.1 development checkpoint

This is a continuation of PR #4 head `28c890a9af83a5e83f33d639f0fed2734af35a0f`
on isolated branch `feat/astravar-2.1-player-first`. PR #4 remains draft/unmerged.
Draft [PR #5](https://github.com/Brenmack15/Astravar-Overlay/pull/5) targets the
existing 2.0 development branch. No production release or real-data cutover is
approved. Inspect the actual Git head, PR and exact-head Actions before resuming;
this checkpoint is not a claim that the whole upgrade is finished.

## Current source and recovery

The transient workspace was lost and recovered by cloning the published repository,
not by recreating the application. Recovery verified published commit
`5d9a5f7292b034b052bc869400f2d02485960073`, tree
`e199397cd5bed1da05cf47ee893a7a7c0638ba9e`, parent
`7b5126d00d53a38430fab6dd3672c827ba2f058f`. Published history is preserved.
Toolchain and verification artifacts were recovered separately. The existing owner
signing identity was privately recovered and reverified; no replacement key was made.
The latest published head verified for this checkpoint is
`414ee61b2696412d77183e8d719b513bc1597789`, tree
`6f3b51d8f3daceb511547c9911d303f5db655171`. It contains the native harness repairs;
the reused product is separately identified below. Earlier local proof files and
the old owner APK identified by prefix `5208` were lost with the workspace. That
APK is not a recoverable download or a final delivery; build and verify a new
candidate from the final source.

After the passing immutable-product native recheck below, local product commit
`69e7eadb97d3dafdbaf989dc95903e5e3a39ad7d` explicitly uses light system-bar icons
against the dark Astravar theme. Its focused application compilation, 60 Python
checks and preservation gates pass. Fresh full CI, matching native visual evidence
and a final owner-signed build remain pending. Large-font navigation wrapping
remains open. An initially blank-looking normal-font Armor preview was a viewer
discrepancy: direct inspection and independent OCR of the recovered PNG show the
full body, so a missing capture is not an established defect. The automated native
pass does not establish final-source visual acceptance.

The supplied handoff's 17 checks were preparation checks, not Android/device proof.
Foundation `af793f8` and guarded UI slice `dbf8292` were reconciled with existing
source. Ten JPEG particle resources use `.jpg` with identical original bytes and
drawable IDs; all fourteen launcher-resource digests remain protected. Private
review JSON, source PDFs, campaign exports, credentials and signing materials do
not belong in this repository.

## Verified evidence and its limits

| Evidence | Verified result | Scope |
|---|---|---|
| Full Gradle stage, run 39163162978 | 124 rules tests; 264 Android tests per variant; both lints and optimized builds pass | Product `1417dd5e045151c8a16b349ccf2c058371c8be16`; the overall run failed its original native harness |
| Android variant totals in that run | Debug 262 pass / 2 gated skips; release 248 pass / 16 gated skips; no failures/errors | Historical exact product, not later edits |
| Fast native run 39172843472 | Seven passing invocations of five distinct native methods | Test source `414ee61b2696412d77183e8d719b513bc1597789`, unchanged older product APKs |
| Independent post-Android history verification | 76 original rows retained: 43 immutable archives + 33 unchanged working rows; 26 preservation triggers present | Fictional emulator fixture; recent view 20 events / 20 rolls; current campaign unchanged |
| Current source checks | 60 Python checks and source/artwork/schema gates pass | Named local verification; fresh final full CI remains required |

The successful [native recheck](https://github.com/Brenmack15/Astravar-Overlay/actions/runs/39172843472)
uses test tree `6f3b51d8f3daceb511547c9911d303f5db655171` and immutable product tree
`f6aea137f996a50082f2ca21045d5c69f2f317fc` from `1417dd5e045151c8a16b349ccf2c058371c8be16`.
Its [artifact 12153247985](https://github.com/Brenmack15/Astravar-Overlay/actions/runs/39172843472/artifacts/12153247985)
is 37,962,839 bytes, SHA-256
`4046da827b8dc8d6c88359fbdd6046cf60aefa5ae9403f1ac11bae42ec29e3b1`.
It does **not** test the later system-bar correction or prove owner signing.

Native checks exercise overlay dragging/outside touch, visible keyboard input,
rotation, protection, notification stop, saved attacks, main History and permission
denial/regrant/restart. The synthetic same-key code 24→25 update preserves every
persisted-store file hash (seven files) before startup and independently reads both saved formulas
and the same unknown-quantity inventory item afterward. Native toolkit search/Copy
passes. Armor/Party captures cover font scales 1.0/1.3/2.0, landscape and 400/360-dp
element-wheel viewports. Fresh XML proves selected routes. Direct PNG inspection
and independent OCR of the artifact's `emulator-evidence/armor-font-1.0.png` show
its full Armor body; an earlier blank-looking viewer preview does not establish
missing rendered content. The confirmed mid-word primary-navigation wrapping at
1.3×/2.0× text still requires correction and fresh visual evidence. Neither automatic
frame equality nor accessibility text alone establishes rendered visual completeness.
No Galaxy S26 physical test occurred.
The seven invocations comprise three original overlay methods, one fixture method,
one update-readback method and two rerun overlay methods after the update; they are
not seven independent test methods. Both lints in the full product run had zero
errors: 48 debug warnings / 50 release warnings and one hint in each variant.

Source gates cover 33 recovered files, 79 foundation files, 14 launcher resources,
ten original particle files and five byte-frozen historical schema JSON files.
Focused Android/Robolectric history verification covers 50 unique cases per variant,
including 14 complete-backup, eight raw-preservation and three restore UI cases.
These counts overlap full suites and must not be added to create a larger total.

Earlier evidence remains historical: on 18 October 2026 published
`e84a3df692480526a0dfe6e000d01906981c1cf1` completed 41 Python checks, 124 rules
tests and 239 Android cases per variant (debug 237 passed / two gated skips;
release 223 passed / 16 gated skips), with zero failures/errors and zero lint
errors (48/50 warnings, one hint each). Run
[39158352867](https://github.com/Brenmack15/Astravar-Overlay/actions/runs/39158352867)
then timed out during its separate code-25 R8 build; native tests did not run.
The later history-correction checkpoint recorded 50 Python checks, including nine
synthetic fixture checks, and the focused Android cases above. These dated scopes
are neither current-source verification nor additional independent coverage.

## Implemented records and play systems

Five native destinations provide Armor, Combat, Party, Campaign and Config. Party
has sourced editable identity/status/abilities and typed companion/character sheets,
with the existing specialized Eek records retained. Shared item IDs, placements,
containers, quantity events, wallets, crafting, checkpoints, audits and idempotent
receipts remain in the existing campaign database. Room migrates additively 1→2→3;
prior schemas, weapon migrations and historical backups remain supported.
Campaign backups are reviewed, merge-only and stale/conflict guarded.
The full authoritative party stat blocks remain unpopulated pending approved source
reconciliation and import. Typed creature and spell entries describe confirmed
capabilities; they do not automatically grant progression, spell slots or resources.

Party progression and equipment remain manually sourced records. Equipping an item
does not infer bonuses, apply effects to the separate weapon profile, invent level-up
features/resources or resolve conflicting homebrew. Only accepted derived rules may
be automated. Roster navigation acceptance is not proof that every sheet/editor and
real tabletop workflow is complete. Config has eleven evergreen searchable prompts
with Read/Copy; those tools do not replace synchronization.
Transfers move whole recorded stacks; split/merge is not implemented. Wallets track
nullable copper, silver and gold independently, without automatic conversion.

The permanent-nexus engine rejects new Shatter and spare-core operations while
preserving historical decoding. Sourced, versioned melee and ten elemental damage
profiles share engine/resource/roll snapshots between the app and overlay. Missing
accepted configuration produces actionable locks rather than guessed values.

## Complete history preservation

The inherited delete-to-20 behavior was corrected. Immutable original bodies,
including malformed legacy records, are preserved before normalization, replacement
or pruning in the existing archives table. Recent display and Undo remain bounded
at 20. Streaming ZIP backup/restore retains raw preservation records; confirmed
legacy JSON imports retain their exact original bytes in deduplicated chunks.
Old command IDs remain recognized beyond the recent journal. Review, backup and
stale-baseline checks precede confirmed restore. See [the preservation contract](HISTORY_PRESERVATION.md).
The earlier local `5aa9f9f` / published `e84a3df` APK is superseded for delivery.

The large legacy fixture that timed out under concurrent-emulator memory pressure
passed without QEMU under its unchanged deadline. No integrity assertion was removed.
The successful native readback additionally verifies old Shatter/event rows, malformed
history and an original legacy archive without importing real campaign data.

## Rules and unresolved sources

Read [the accepted decisions](PLAYER_FIRST_RULES.md) before changing mechanics.
Charged Gauntlet stays locked until its flat-bonus damage type and current melee
profile have accepted sources. The supplied reference establishes STR + DEX + INT
flat damage but does not establish its damage type;
ordinary Guardian attack bonus is explicit, not assumed. Advanced elements retain
accepted level/upgrade gates. Enhanced Neutral Force remains unapproved.

Discharge remains **enabled with inherited accounting**: three times the primary
Force dice, 60-foot targeting, 15-foot radius and 600 recorded in-world seconds of
overload recovery. The later Armor Mk.I reference specifies 12d12 Force, a 1-mile
range, a 20-foot radius and once per Long Rest. Those values and the rest limit are
**not implemented** in the live inherited engine. The conflict does not lock
inherited Discharge; changing it requires adjudication and engineering, including
persistent resource accounting. No real-time
countdown or assumed rest is substituted. Private level-six review references are
not canonical imports; known HP conflicts and unknown resources remain unresolved.

## Connection engineering still required

The foundation implements a campaign-scoped wire protocol, AndroidKeystore vault,
staging outbox/inbox/cursor, verified receipts, conflict/replay checks and an
authenticated synthetic reference service. Config is a fictional acceptance harness;
its generic replacement command is not a typed inventory or currency transaction.

Unfinished implementation includes typed party/item/currency server commands and
real application adapters; atomic domain/outbox writes; owner OAuth identity and
device enrollment; credential renewal and server-enforced revocation; production
native connection UX; and an authenticated MCP/tool adapter to the same authority.
The staging queue is not yet atomic with the real inventory authority, and local
disconnect alone is not server revocation. These are engineering work, not merely
external account approvals. See [the connection contract](OWNER_SYNC.md).

Later acceptance requires authorized hosting, actual native Astravar HTTPS, native
ChatGPT Android tool access and genuine shared-record read→approved write→receipt→
device refresh→independent readback, including offline/conflict/replay/access-denial/
revocation tests. The existing private DM Bridge remains intact and is a different
store. The anonymous Worker must never receive real records. Cleartext is disabled;
no paid service has been activated.

## Signing, migration and next actions

Permanent target is `com.yazsras.astravar`, default `2.1.0` / code 24. Public owner
certificate SHA-256 is
`4c5cb0113cae5d0dee2619ee30f5b3c593d4aa897e1fa38dee7ed5561ec4e847`.
The privately recovered identity matches available historical owner APKs. No key
rotation or CI secret upload occurred. CI-only temporary signatures do not prove
owner compatibility. A final-source owner candidate, metadata inspection and
synthetic owner-key update proof remain required before delivery approval.

The handset's installed package/version/certificate and current data are unobserved.
The `.v2statdebug` experiment is a separate package and cannot be updated in place by
the permanent APK. No existing installation was uninstalled or replaced.

Complete the visual/navigation corrections, freeze the source, then run fresh full
CI and native acceptance against that exact product, inspect the owner-signed APK
and preserve its source/tree/certificate/artifact metadata. Continue unfinished
connection engineering independently of later external access gates. The private
source reconciliation tool supports a non-destructive fingerprinted preview only;
it preserves original IDs, references, unknowns and conflicts and has no cutover
operation. Security/backup/accuracy/integration acceptance and separate owner approval
are required before real import or authoritative cutover. Keep Notion available.

To resume: inspect Git/PRs/Actions/artifacts and persisted handoffs first; recover any
unpublished bundle/patch before editing. Read AGENTS, rules, Party and connection
contracts. Continue the first failed or unverified gate with exact evidence. Never
merge, force-push, rotate keys, import real data, change authority or publish production
without the corresponding owner approval.
