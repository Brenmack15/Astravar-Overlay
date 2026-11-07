# Reforge local data and migration boundary

## Storage contract

Reforge adds `astravar-reforge.db` (Room version 1) inside the existing application sandbox. Opening this database does not seed a character, infer a rest, recover a resource, change an ability, migrate an equipment assignment or touch an existing campaign/weapon database. Existing `CampaignLedgerDb` version 6, its migrations 1–6, `AstravarDb`, preference names, historical receipts, source identities, images and old backup contracts remain intact.

The Reforge database has four tables:

| Table | Responsibility |
| --- | --- |
| `reforge_characters` | Stable profile identity and versioned, serializable `ReforgeCharacter` aggregate; local revision; creation/update times; preserved source snapshot |
| `reforge_changes` | Append-only command identity, request fingerprint, operation, before/after snapshots and exact original result |
| `reforge_rolls` | Explicitly initiated result bound to the character revision, formula, every face/sign/kept flag, modifiers, manual status and dice ownership |
| `reforge_note_drafts` | Recoverable unsaved notes with the exact character revision and guarded draft timestamp |

A whole character is the transaction boundary. Its nested effect, spell, item-definition and owned-instance contracts are typed and versioned; the JSON payload is an authoritative local aggregate rather than a manually maintained display summary. This initial schema does not normalize each nested effect into a Room table. Queryable per-character rows and separately indexed audit/roll tables keep current workflows simple without rewriting established stores. Large cross-character mechanical queries would justify additional additive indexed tables later.

`ReforgeSavedCharacter.saveState` says **Saved on this device · not synchronized**. Existing Cloudflare campaign scopes, records, immutable receipts and pending queues describe the original campaign domain. They do not grant a new Reforge synchronization scope. This build neither uploads Reforge state nor claims that the ChatGPT mirror is its authoritative store.

## Reviewed adoption

1. Read an internally consistent snapshot through the existing local campaign-export transaction.
2. Select the existing profile, ability records, typed party sheet and inventory assigned to that exact profile identity. Record all source content and attribution in `ReforgeOrigin`.
3. Present a draft and review warnings. Nothing persists during preview.
4. The user may confirm or correct the native draft before adoption. The action preserves the profile identity.
5. Re-read the original local source and require the same selected-record fingerprint and profile revision.
6. Create the Reforge aggregate and immutable initial receipt atomically. Never overwrite an existing Reforge aggregate or original campaign row.

This is an explicit local copy, not an authoritative campaign cutover or continuous link. The source recheck rejects an observed intervening change, but the two independent databases do not provide a cross-database serializable transaction. The retained source snapshot identifies exactly what was reviewed even if the original changes immediately afterward.

Important boundaries:

- Unknown abilities and current/temporary HP remain `null`; score zero remains zero.
- Unknown original level appears as an explicitly warned level-1 draft requiring review. No background adoption occurs.
- Recorded AC and maximum HP are copied as recorded baselines. The preview warns that armor may already have contributed; the user must reconcile the baseline before activating equivalent structured effects.
- A legacy separate magical modifier is preserved in the source snapshot and original records. It is not guessed to mean an ability-score increase or spread across unrelated rolls.
- Narrative item properties, spellcasting text, unverified claims and proposed inventions never become active mechanical effects.
- Only known-quantity, available items explicitly assigned to the profile can become native instances. Unknown quantities stay in the original inventory and are called out in preview warnings.
- Owned item and definition identities remain the original item ID. No duplicate physical item is written into the original inventory.
- Companion records are handled by the same explicit identity-based workflow; no ordinary monster replacement or homebrew reset is applied.

## Saving and retries

`save` requires the exact observed revision in both its argument and aggregate. A stale action fails without changing data. Successful saves increment the revision and write before/after snapshots plus an immutable outcome in the same Room transaction. Clock corrections cannot make a later edit's update time move backward.

A command identity may retry the identical fingerprint and receive its original saved outcome, even after the current character has advanced. Reusing that identity for different content rejects. `commitRoll` writes a single fingerprinted typed action/roll outcome and `saveWithRoll` provides a transaction wrapper; both commit the actual initiated roll and mechanical state together; failure leaves neither partial state nor a partial roll. Plain rolls leave HP, slots, charges and character revision untouched. Hit confirmations may use a stable command identity tied to the initiating roll so resource consumption cannot silently repeat.

No observer, lifecycle event, app update, adoption, equipment change or editor opening performs a rest. Recovery remains an explicitly confirmed engine operation.

## Backups and restoration

`astravar-reforge` schema 1 is a separate complete backup format. It includes typed character aggregates, source snapshots, immutable changes, all stored rolls and note recovery drafts. Drafts never automatically replace committed notes; stale drafts retain their original expected revision. Concurrent draft edits and deletes use the exact observed update timestamp, and committed character saves still require the actual character revision. A stale draft can be retargeted only through the explicit side-by-side recovery review action: `reviewNoteDraft` checks both the observed draft timestamp and latest character revision, retains the text, and updates only its recovery envelope. It does not apply the text or silently rebase a gameplay operation. Existing campaign and weapon exports keep their historical formats and continue to work.

The decoder enforces a size budget, strict known fields, supported format/schema, duplicate-member rejection, unique identities, valid mechanical contracts, referenced characters, revision continuity and matching before/after snapshot chains. A declared revision cannot allocate an unbounded history range. Invalid input cannot partially restore.

Restore is merge-only. A preview lists counts and conflicts and fingerprints both incoming content and current local state. Existing different characters, commands or rolls are never overwritten. Commit rechecks the fingerprint, current-state snapshot and conflicts inside a transaction, inserts only absent identical-domain rows and appends an immutable restore receipt. A changed local state requires a new preview. A repeated identical restore command returns its original receipt.

More → Character backup exposes the native backup workflow through Android Storage Access Framework. Files are limited to 8 MiB for local document transfer; no large JSON enters the clipboard or saved-instance Bundle. Export freezes its data and state fingerprint in one database transaction, streams UTF-8 to the chosen file, reopens it independently and verifies its hash before claiming success. The restore button requires a verified current backup whose state fingerprint matches the reviewed restore snapshot. A later local edit invalidates the repository commit guard. The source Campaign and Weapon backups remain separate. Automated document/UI evidence is reported in verification; a real document-provider or physical-device result must not be inferred from a simulated picker test.

## Rollback and release boundary

Returning to an older APK leaves the new separate database unused; no existing schema was upgraded by Reforge. The new database is retained in the permanent app sandbox. Android still requires the same package and original owner signing certificate plus increasing versionCode for an in-place update. This storage design does not prove an installed update, a usable owner signing key or physical device preservation.

Never uninstall, clear data or replace the signing identity to test compatibility. An emulator acceptance must populate a previous build, install the newer build with the same disposable test signer and compare independently read database records before claiming in-place preservation. Owner installation and authoritative backend cutover remain separate approval gates.

## Verification scope

Added fictional Robolectric tests cover no seeding, offline close/reopen, unknown versus zero, revision rejection, exact retries, command collisions, immutable roll breakdowns, stale rolls, complete guarded restore, incomplete history, huge revision rejection, invalid containers, explicit adoption/source preservation, stale source rejection and atomic roll/state rollback, durable notes draft recovery, explicit stale-draft review and a native file-based backup/merge flow with picker selection simulated. Execution results belong to `verification.md`; test source alone is not passing evidence.
