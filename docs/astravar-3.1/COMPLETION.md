# Astravar 3.1 completion candidate

Target: `3.1.0-rc1`, build `45`, continuing PR #10 and its original branch/history.
This is a validation candidate, not a declaration of release acceptance.

## Implementation checklist

- [x] Recover remote PR/head, all branch refs, prior CI failures and artifact evidence.
- [x] Correct variant-specific QA labels while retaining strict permanent-package,
  release label, certificate, original artwork and native-alignment verification.
- [x] Modernize native routes and replace retired floating-overlay tests with
  standalone weapon, keyboard, rotation, font-scale, history and retirement checks.
- [x] Add character archive/restore without deleting source records or history.
- [x] Expose existing spellcasting ability, attunement capacity and armor Dexterity configuration.
- [x] Add playable item stack splitting, reviewed removal and atomic transfer.
- [x] Add relationship edit/remove and companion relationship authoring with actual
  names/types, stale-revision protection and retained removal receipts.
- [x] Add arbitrary sourced companion forms with unknown-capable HP, AC and stat blocks.
- [x] Let fresh playable characters explicitly create a reviewed name-only campaign
  identity for relationship selectors, with immutable proof, collision/stale guards,
  serialized creation, recreation-safe approval, and dual-store recovery tests.
- [x] Fix companion attack-review Bonus action capitalization and archived-sheet activation.
- [x] Independently read back campaign backup bytes and bind restore protection to
  the exact current snapshot instead of a session-wide success flag.
- [x] Keep diagnostics and independent recovery tools reachable after weapon-store startup failure.
- [x] Make switch labels and their complete 48dp rows interactive and accessible.
- [x] Keep focused fields visible after keyboard resizing and text changes;
  bound multiline editors without truncating their saved text.
- [x] Restore armor-source reference navigation through Tools.
- [x] Show import-verification progress, success and actual differences beside
  the acted-on receipt, with accessibility announcements and read-only regression coverage.
- [x] Wrap header actions at large text sizes, retaining full labels and touch targets.
- [x] Keep alternate-form payloads local to the device and backups; exclude them from
  direct, scoped and chunked audit publication and reject unsupported remote form edits.
- [x] Add real-emulator journeys for character create/edit/advance, reviewed gear
  equip/restart/unequip, generic companion HP and relationship create/edit/remove.
- [x] Add native isolated SQLite >32-million-character selection/adoption regression.
- [x] Add native disk SQLite invalid/duplicate/stale-command checks, interrupted
  receipt-write rollback, same-command recovery, and backup restore after reopen.
- [ ] Pass final exact-commit full automated and native workflows.
- [ ] Verify the final candidate APK and retain exact source/hash evidence.
- [ ] Original-owner signing and physical Galaxy S26 acceptance.

## Data protection

No historical Room schema changes or destructive fallback. Campaign remains
schema 6, weapon schema 3, playable Reforge schema 1. New forms/archived status use
backward-readable defaulted JSON fields. Existing rows are not rewritten merely
by opening them. Original immutable receipts remain unchanged.

Archive is reversible and keeps the exact character/item/source identities,
unknown values, resources, origins, notes, drafts and complete history. Transfers
save both playable profiles and their receipts in one Room transaction; stale
receivers roll back the sender. Transferred gear arrives carried and unattuned.
Container/composite/attack/resource dependencies require explicit resolution.

Unlink retains the link's endpoints plus a new revision/audit receipt. Active
views omit that removed revision; complete backups retain all records and audits.
Scoped character reads omit unrelated archive/history payloads and permit only
correlated link-removal metadata checks. Source adoption remains explicit and
revision/fingerprint checked. The real campaign is never disposable test data.

## Verification evidence

Before publication, the Python suite passed 157 tests and all six existing
foundation/schema/branding/asset checks passed. The prior code44 QA APK was
independently downloaded and passed the corrected full package inspector,
including original artwork, disposable-signature classification, 16KB native
alignment and packaged overlay retirement. These results do not validate the
new Kotlin implementation.

Local Gradle cannot resolve `org.jetbrains.kotlin.android:2.2.21` from the pinned
repositories; it fails before source compilation. Required new Kotlin, Compose,
Room, debug/release, lint and native tests must execute in hosted CI. The existing private owner-signing recovery artifact was subsequently recovered
outside Git/CI. Its private/public pair and certificate match the historical pin
and actual code43 owner APK. Final owner signing still follows successful final
validation and exact non-signature entry comparison with the tested optimized APK.
No replacement identity or CI owner secret was created.

Hosted native testing uses an isolated Android 16/API36 emulator and disposable
CI certificates. The optimized release and debug QA SQLite fixture run in separate
packages. The reinstall test checks same-version/same-test-key byte preservation;
it does not establish original-owner key or physical handset compatibility.

## Remaining product boundaries

- Playable Reforge sheets and original campaign records remain separate stores.
  Reviewed copies are one-way; no bidirectional synchronization or canonical
  campaign cutover is performed.
- Companion source records require reviewed playable adoption before rules-engine
  equipment/attacks. New forms retain their independent sourced values; changing
  a form does not automatically transform, spend resources or swap effects. Form
  payloads are local to this device and full backups; existing connection grants
  cannot publish or modify them.
- Structured relationships use saved campaign identities. Character → Campaign
  connections can create an explicitly reviewed name-only identity for a fresh
  playable sheet. HP, level, abilities and resources remain unknown in that source
  counterpart; gameplay stays on the playable sheet. Later names are not mirrored.
  An existing unrelated source identity is never aliased or overwritten.
- Complete campaign JSON backup retains its explicit 32-million-character/32MiB
  document limits. Fixing character selection does not remove full-backup limits.
- Training, resources, spells and accepted homebrew still require explicit review
  when adopting an original profile. Unknown statistics are never initialized by
  illustrative fixture values. Full D&D Beyond parity is not claimed.
- No production merge/deployment, physical installation, real campaign import,
  signing rotation or private-data upload is authorized or performed here.

Owner acceptance requires a verified current backup, the existing owner-signed
package with increased build number, explicit approval to install, then real S26
profile/adoption/readback and everyday-workflow checks. Never uninstall or clear
owner data to make a QA package install.
