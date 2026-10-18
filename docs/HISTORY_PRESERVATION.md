# Weapon history preservation in 2.1

The owner's 2.1 directive supersedes the earlier destructive newest-20 storage
policy. `AstravarDb` remains schema 3: its historical schema JSON and existing
tables are unchanged. The interactive roll list and accounting Undo still use
the newest 20 records; this limit no longer authorizes loss of original history.

## Immutable records and transactions

`HistoryPreservation` copies original campaign, event, roll and legacy archive
bodies into the existing `archives` table before normalization, replacement,
pruning or malformed-record isolation. Insert/update/delete/REPLACE triggers cover
direct database writes as well as repository operations. Exact source/body
deduplication precedes content-addressed SHA-256 identities, calculated with
chunked BLOB reads. Unchanged working-row reinsertion does not duplicate 20 dice
graphs per action. Omitted bulk-input shots are preserved individually.

Canonical archives, original-file chunks and restore receipts reject deletion,
updates and identity collisions, including SQLite `UPDATE OR REPLACE`. Temporary
capture records are removed only after the canonical copy succeeds in the same
transaction. Storage failures abort the operation; they are not classified as
malformed JSON. Retried event IDs remain recognized after leaving the recent
journal. Malformed original bodies are opaque preserved records, not active
resources or executable actions. Previously deleted records cannot be recovered
by this upgrade.

## Complete backups and the compatibility codec

The production weapon backup flow uses `HistoryBackup`, a streaming ZIP containing
`backup.json`, an archive index, raw archive entries and a versioned checksum
manifest. The pure Kotlin `BackupCodec` and `Repository.export()` remain bounded
working-state compatibility APIs; they are **not complete historical backups**.
The recent session Markdown is likewise only a summary. Campaign Party, inventory
and relationship records retain their separate Campaign backup flow.

Preparing a restore copies the selected file into private disposable staging and
validates it without changing campaign databases. ZIP names, counts, checksums,
protected identities, original-file references and JSON depth are checked.
Confirmation is bound to the reviewed file and the current database fingerprint;
stale state or coherently modified staging is rejected. The UI also requires a
matching complete current backup and an explicit restore confirmation. Recreating
the screen discards its preview and requires reselecting the file.

Confirmed legacy JSON imports retain the exact original bytes in immutable,
content-addressed chunks before applying the bounded active projection. A complete
ZIP imports its raw records directly rather than nesting previous ZIP files.
Existing history and the pre-restore state survive replacement. Successful
restores return a durable receipt; retries return the original receipt and
conflicting command IDs are rejected before changing working history.

Safety budgets are explicit technical limits: at most 1 GiB input/expanded ZIP,
64 MiB per raw archive record, 100,000 archive records, and 512 MiB for legacy JSON.
The working-state envelope remains 8 MiB. Exceeding a budget or running out of
private staging space fails visibly rather than truncating history or importing
a partial file. Keep the selected original backup when a file cannot be accepted.

## Verification boundary

`HistoryPreservationTest`, the existing repository/migration tests,
`HistoryBackupTest`, and `WeaponHistoryBackupUiTest` cover the storage, transport
and review contracts. Consult the exact-head report for their actual results;
the existence of a test is not a passing result.

The native upgrade helper operates only on a stopped disposable emulator's copied,
explicitly fictional database. It injects more than 20 old rolls/events, including
Shatter, malformed history and a legacy archive; records original hashes and
removed history triggers; then independently checks raw retention after an actual
APK update and startup. It must never target the owner's phone or real campaign.
Native execution, Package Manager's pre-start file preservation, and physical
Galaxy behavior are distinct acceptance claims.
