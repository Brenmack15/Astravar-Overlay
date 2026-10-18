# Astravar 2.1 development checkpoint

Work continues from PR #4 head `28c890a9af83a5e83f33d639f0fed2734af35a0f`,
on isolated branch `feat/astravar-2.1-player-first`. PR #4 stays draft/unmerged.
The isolated upgrade is draft [PR #5](https://github.com/Brenmack15/Astravar-Overlay/pull/5),
targeting the existing 2.0 development branch rather than the default branch.
Read this document together with the current Git head, PR and exact-head Actions
results; a historical checkpoint is not evidence that a later build passed.

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

Measured local results before the final audit fixes: 124 Kotlin rules tests and
41 Python checks passed; both Android variants compiled, both lint tasks passed
without errors, and debug/optimized owner-signed APK assembly passed. The full
rerun at local `7c64a37` executed 225 Android tests per variant and exposed one
real Eek navigation failure in each variant (2 debug / 16 release tests skipped
under their explicit optional-live/debug-only gates). The bounded native-sheet
fix is committed as `31f7a23`; it and the subsequent audit corrections require
their coordinated rerun. Do not report the preceding full run as green.

The final audit also corrects advanced single-element selection with Dual
Resonance, overlay payload mode preservation, modal validation/retry behavior,
audit pagination, stable create-command identities and complete backup review.
Consult the exact-head PR checks and final evidence for their actual outcomes;
source implementation is not a test result. Initial lint reports contained
45 debug / 46 release warnings, including retained dependency/artwork/tooling
advice and new owner-sync advice about synchronous credential persistence.
Those warnings are not all legacy findings, and none was suppressed to obtain
a pass.
The source gates retain original artwork and owner signing-block hashes, with
explicit reviewed 2.1 source changes recorded separately.

The two historical source manifests now record each authorized 2.1 modification
with its previous pinned digest, current digest and reason (18 foundation and
14 recovered-source entries). This is an explicit source evolution, not a claim
that the engine and campaign implementation are unchanged. The independent
upgrade gate pins five old schema JSON files byte-for-byte and excludes private
review, signing and distribution artifacts from source control.

The cloud software API 36 emulator completed an actual Android boot. App runtime
and in-place update acceptance remain separate from that boot result. The
software rendering captures were reviewed for Armor, Combat, element alignment
and Config. Standalone Party fixtures initially omitted the application's
content-color Surface; they are being corrected before further visual claims.

Required final checks: rules and Android unit/regression tests, Room 1→2→3,
backup/restore, transactions/replay/conflicts, lint, optimized release assembly,
native emulator overlay/navigation/large-font/landscape, same-key two-version
data-preserving update, and APK identity/certificate verification. A software
rendering fixture is not a real Android emulator or physical Galaxy S26 test.

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
