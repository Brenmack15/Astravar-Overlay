# Genesis storage cutover A

This checkpoint moves the existing playable tables into the campaign SQLite database. It does **not** reconcile conflicting gameplay facts or make legacy campaign fields authoritative projections. Existing source proof, pending reviews, mechanics and record identities remain unchanged.

## Storage and recovery

Campaign schema 7 adds unchanged `reforge_characters`, `reforge_changes`, `reforge_rolls`, and `reforge_note_drafts` table definitions plus `genesis_storage_receipt`. Historical campaign schemas 1–6 and original Reforge schema 1 remain intact.

On first playable access, the application opens `astravar-reforge.db` read-only, verifies its supported table shape, copies raw column values, and compares ordered, typed SHA-256 row digests and table counts. Bounded cursor queries return SQL types, byte lengths and at most 32 KiB per TEXT/BLOB column; larger fields are read in chunks. UTF-8 decoding is strict: malformed text stops recovery without replacement characters. Character JSON, origin JSON, receipt outcomes, roll-component JSON and drafts are not parsed and re-encoded during copying. Unknown historical payloads remain present verbatim.

Both original-file opening and every production campaign database opening override Android’s default destructive corruption handler. The campaign helper delegates Room’s creation, configuration, migration and opening callbacks, while corruption fails closed and automatic data-loss recovery is disabled. Corruption must never delete the destination receipt and trigger replay of a stale original.

Copying and the installation-local authority receipt commit in one destination transaction. An exception rolls both back. Existing identical destination rows may be completed; different rows or unmatched destination identities stop recovery. Unknown source schemas/tables and orphan recovery files stop recovery. Nothing is silently overwritten or initialized as an empty roster.

Once the receipt exists, subsequent launches use campaign storage exclusively and never replay the old source over newer edits. The original database and WAL/journal recovery payloads are not deleted or replaced. SQLite may rebuild its derived SHM lock/index file while opening a WAL database; that file is not a backup of committed records. Production code no longer opens the old file for writes. A current character that this reader cannot decode remains preserved and produces a recovery screen.

The original file is a **cutover-time recovery archive**, not an ongoing backup. Installing an older application version would read that stale source. Recovery should use a compatible forward repair build and the current backups; this checkpoint does not claim downgrade compatibility.

## Transactions

`GenesisApplicationStorage` owns the application Room instance and both repositories. Every production `ReforgeRepository.open(context)` resolves to that holder. Playable reads/writes wait for migration completion. Independently owned connection clients retain their existing database lifetime and close behavior.

Reviewed campaign-item acceptance checks source revisions and writes the playable item/receipt inside that shared Room transaction. Passing a different canonical Room instance rejects the operation. Historical isolated `ReforgeDb` test/adapter repositories retain their explicitly separate-store behavior. This change does not add automatic gameplay synchronization.

## Backup implications

Existing character backups still export/restore all four playable tables through the same repository API, now against campaign storage. Existing campaign backups retain their campaign-only meaning and remain readable. Preserve both backups for complete recovery at this stage.

The storage receipt is installation-local: portable character and campaign backups intentionally do not import it. A fresh installation first establishes its own empty storage authority, then restores user-selected backups through the existing conflict-checked merge paths. Restoring a character backup never causes the old source file to be recopied. A full database recovery must preserve the destination receipt with its data; a missing receipt with unexplained destination rows stops for review.

Before adding **gameplay authority** receipts or writable legacy projections in wave B, extend portable backups to cover those semantic records. The local storage receipt introduced here is not a gameplay approval and cannot substitute for that work.

## Verification

Tests cover raw historical payloads, transaction interruption, restart after later canonical edits, identical-row completion, conflicts, absent/unsupported sources, retained originals, and shared campaign/playable transactions. Explicit historical migration test chains now include schema 7. Compilation, full regression and device acceptance must be reported from actual runs; source inspection alone does not establish them.

The migration avoids Android CursorWindow's full-row size limit, including historical rows with more than 3 MiB of combined before/after snapshots. Tests also cover strict rejection of malformed UTF-8 and exact original-byte retention. Native upgrade testing is still required to establish device acceptance; JVM success alone does not establish it.

### Executed checks — 11 November 2026

- Initial implementation: 80 targeted tests passed, with debug product/unit compilation and `lintDebug` passing (`genesis-storage-wave-tests.log`).
- After adding non-deleting corruption handlers: 64 selected tests ran; 63 passed. One fixture incorrectly expected the derived SHM index to remain byte-identical. The database and WAL assertions already passed. Product code was unchanged; the fixture was corrected to distinguish the shared-memory index and additionally assert rollback-journal retention.
- Final focused run: all 17 storage migration cases passed, including both physical corruption fixtures, large-row/invalid-UTF-8 preservation, interruption, conflicts and restart. The other 47 selected current-product regressions passed in the preceding run: shared transactions/gating, inventory, historical migrations, schema compatibility, currency and prompts. Debug compilation and final `lintDebug` passed (`genesis-storage-final-tests.log`).
- Original UI/source protection, upgrade integrity, branding and particle-resource guards passed. Four source-review guard tests passed. Schema 7 preserves all 31 historical campaign entity definitions and all four original playable definitions exactly.

Native installation/upgrade and physical-device acceptance are not claimed by these JVM checks. The full application still needs gameplay-authority reconciliation and the remaining Genesis milestones.
