# Astravar 2.1 development checkpoint

Work continues from PR #4 head `28c890a9af83a5e83f33d639f0fed2734af35a0f`,
on isolated branch `feat/astravar-2.1-player-first`. PR #4 stays draft/unmerged.
The isolated upgrade is draft [PR #5](https://github.com/Brenmack15/Astravar-Overlay/pull/5),
targeting the existing 2.0 development branch rather than the default branch.
Read this document together with the current Git head, PR and exact-head Actions
results; a historical checkpoint is not evidence that a later build passed.

## Final audit correction: complete history preservation

The inherited weapon repository pruned older rolls/events and deleted archives at
startup; bounded backup decoding also discarded older history. The candidate
built at local `5aa9f9f` / published `e84a3df` is superseded for delivery. Historical
Shatter decoding alone did not satisfy the owner's preserve-all requirement.

The correction is implemented: immutable original bodies, including malformed
legacy records, are retained before normalization, replacement or pruning in the
existing archives table. The interactive and Undo view remains at 20. Complete
streaming ZIP export/restore retains all raw preservation records; confirmed
legacy JSON imports retain their exact original bytes in deduplicated chunks.
Old command IDs remain recognized outside the recent journal. See
[the preservation contract and limits](HISTORY_PRESERVATION.md).

Focused Android/Robolectric verification now passes 50 unique cases per variant,
including 14 complete-backup, eight raw-preservation and three restore UI cases.
The boundary fixture crosses both 64 KiB original-file chunks and 1 MiB database
appends. A large legacy debug fixture timed out during concurrent-emulator memory
pressure; without QEMU it passes in 46.086 seconds debug / 46.939 seconds release,
with its original deadline unchanged. The release comparison also passed. No
integrity assertion was relaxed and no production data was used.

This checkpoint is written before the final clean full build. The final delivery
report, embedded APK revision, matching published Git tree and exact-head Actions
run must establish the actual final-source results. Do not reuse an earlier APK
or infer native update/physical-device acceptance from these focused tests.

## Recovery and scope

The supplied Player First handoff was inspected in full. Its 17 passing checks
were synthetic preparation checks, not Android compilation or device evidence.
Local foundation commit `af793f8` applies its guarded resource repair; `dbf8292`
applies the guarded UI slice. The second guard was reconciled for the preceding
particle manifest path change only. Ten JPEG-encoded particle resources now use
`.jpg`; their original bytes and Android drawable IDs remain identical. The
supplied launcher portrait and all fourteen launcher-resource digests remain
protected. No private review JSON, source PDF, live campaign export, credentials
or signing materials belong in this repository.

## Implemented source awaiting final verification

- Five native destinations: Armor, Combat, Party, Campaign and Config.
- Reusable sourced Party identity/status/abilities plus typed sheets, editable
  capabilities, attacks, movement, defenses, skills and spells. Unknown values
  remain unknown. Existing specialized Eek records remain reachable.
- Additive campaign Room migration 2→3, one shared inventory identity, versioned
  placement/ownership, container and installed-item commands, quantity changes,
  wallets, checkpoints, source references, immutable audit and command receipts.
- Campaign backup schema 3 includes the new records and preserves merge-only,
  stale-preview and conflict checks. Earlier schemas and weapon Room migrations
  remain supported. Review/rollback commands are explicit, not data deletion.
- Shared engine guards retire new Shatter and spare-core operations. Legacy
  enum values, serialized plans, history and backup decoding remain supported.
- Sourced, versioned melee and individual elemental formulas. Gauntlet,
  spell-imbued gauntlet, Bonus Action charge and ordinary Guardian fallback
  use the same app/overlay engine, dice, resources and retained snapshots.
- Config contains eleven searchable evergreen ChatGPT prompts with Read/Copy.
  Armor displays independent configured weapon values and character-source
  conflicts without silently replacing either source.
- Owner-sync protocol, encrypted device-credential vault, offline queue,
  verified receipts, independent readback and revocation tests. The shipped
  connection page is a **fictional acceptance harness**, not live campaign sync.
- Offline source reconciliation tool produces a private, fingerprinted preview.
  It preserves original IDs, references, unknowns, proposals and source conflicts;
  it has no commit/cutover operation.

## Rules decisions and unresolved sources

Read `PLAYER_FIRST_RULES.md` before changing mechanics. The accepted armor
reference supplies melee rules, but current charged-bonus damage type requires
an explicitly sourced ruling. Missing configuration locks that operation and
explains why. An existing Discharge formula/range conflict is retained rather
than resolved by guessing. Recovery uses player-recorded in-world time. No
real-time countdown or unapproved enhanced Neutral Force is introduced.

The private three-member level-six review is not imported or canonical. Known
HP source conflicts remain unresolved. No character level-up or resources are
inferred from those references.

## Verification ledger

The exact earlier published head `e84a3df692480526a0dfe6e000d01906981c1cf1`
completed 41 Python checks, 124 rules tests and 239 Android tests per variant:
debug 237 passed / two explicitly gated skips; release 223 passed / 16 explicitly
gated skips; zero failures or errors. Both lints passed with zero errors (48 debug
warnings / 50 release warnings and one hint each). The Eek navigation failure
caught earlier was fixed before that independent full-suite pass.

Its workflow https://github.com/Brenmack15/Astravar-Overlay/actions/runs/39158352867
ultimately failed at the 25-minute combined-step deadline during the separate
code-25 R8 build, after the first full Gradle verification succeeded in 22m21s.
The native stage did not run. The corrected workflow keeps every check and gives
both optimized builds a bounded 40-minute step within an 80-minute job.
CI APKs retain their conspicuous temporary-signer labels.

After the history correction, all 124 rules tests pass. The focused Android runs
cover 50 unique cases per variant, including the 14-case complete-backup class,
eight preservation cases, three narrow/large-font restore UI cases and affected
repository/migration/formula regressions. All passed. The first test compilation
exposed a missing Compose semantics extension import, fixed without weakening its
assertions. The former memory-pressure timeout was rerun without the emulator and
passes under its unchanged limit. Final full-suite results must come from the
fresh clean-head verification, not a sum of stale XML files.

The Python suite now contains 50 checks, including nine synthetic native-history
fixture checks. Source gates verify 33 recovered files, 79 foundation files, 14
launcher resources, ten original particle files and five byte-frozen historical
schema JSON files. Explicit source evolution records 20 reviewed foundation and
16 recovered-source entries, preserving their earlier pinned hashes and reasons.
The original owner signing block remains unchanged. No blanket lint baseline or
suppression was introduced; new OwnerSync synchronous-persistence/KTX advice is
reported separately from inherited resource/dependency advice.

Android UI captures and form tests use Robolectric native graphics. They do not
prove external SAF chooser behavior, TalkBack traversal or physical Samsung use.
Activity Bundle recovery is distinct from a force-stop-resistant unsaved-draft
journal. The local software-emulator attempts encountered platform ANRs/startup
and memory pressure; at this checkpoint no native overlay/update pass is claimed.
The isolated final native path verifies an old owner24 to fixed-source25 update,
compares persisted-store hashes before startup, then independently checks more
than 20 old Shatter/event rows, malformed history, a legacy archive and current
native readback. It also rechecks overlay notification/history/permission behavior
on the updated source. The fixture is strictly fictional and emulator-only.

Required remaining final checks: fresh full Android suites, both lints, optimized
owner24/proof25 APKs and metadata, exact published-tree equality, exact-head CI,
native runtime/update and visual review. Record their final outcomes in the
external delivery report, draft PR and project documentation without changing a
frozen artifact's source merely to append its results.

## Signing, connection and migration gates

The existing private owner recovery artifact was found outside this repository.
Its public certificate matches SHA-256
`4c5cb0113cae5d0dee2619ee30f5b3c593d4aa897e1fa38dee7ed5561ec4e847`.
No key was generated, replaced or sent to GitHub. An owner-signed candidate is
authorized for preparation; public production release still requires final
owner approval. Target permanent package is `com.yazsras.astravar`, version
`2.1.0` code 24. Verify the actual APK instead of assuming these build defaults.
CI-only artifacts use a temporary signer and must never be called permanent.

The installed handset package, version, certificate and data are unobserved.
The previous `.v2statdebug` experiment is a different package; a permanent APK
cannot update it in place. No existing apps were uninstalled or replaced.

Authenticated production hosting, owner enrollment, revocable campaign scope,
actual native ChatGPT Android access and native Astravar device read→approved
write→receipt→refresh→independent readback remain acceptance gates. The existing
private DM Bridge is preserved; the anonymous Worker cannot receive real data.
Main/release includes HTTPS networking for the explicitly authorized owner
connection architecture. Cleartext is disabled; production activation and real
campaign adapters remain absent. No paid service was activated.

Real campaign import/canonical cutover remains disabled pending security,
backup, accuracy and integration acceptance and separate owner approval. The
Notion ledger remains available. A private read-only migration preview never
confers authority to import or overwrite it.

## Resume development

Inspect the current branch/working tree, commits, draft PRs, Actions head SHA,
downloadable artifacts and this checkpoint before editing. Recover any unpushed
bundle/patch first. Read AGENTS, current rules decisions, Party data docs and
owner-sync contract. Verify source priorities and existing tests; do not reuse
historical pass counts. Preserve signing identity, stable IDs, unknown values,
source conflicts, historical snapshots and private data boundaries. Continue
the first failed or unverified acceptance gate, recording exact results. Do not
merge, force-push, rotate keys, import real campaign data, change the authoritative
store or publish production without the corresponding owner approval.
