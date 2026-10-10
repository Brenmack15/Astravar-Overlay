# Astravar 1.5.1 handoff

Source began at `613cd67` (1.5.0/code 7) on a clean checkout. Work is on `fix/bounded-roll-history`; no reset or rules rollback was performed.

History OOM mechanism: the v1.5 repository eagerly selected and decoded every `events` row. Each row held before/after Campaigns including the entire growing pending-roll list. Thus roll storage and deserialization grew quadratically with attacks; the UI's latest-20 slice did not prune anything.

`HistoryRetention.MAX_HISTORY_ENTRIES = 20` is shared by storage, queries, migration, backups and UI. Room v3 adds indexed `rolls` and event metadata. Rolls are stored once as PendingShot snapshots; campaign JSON and accounting undo states contain no roll lists. Eager event observation selects only bounded metadata. SQL triggers and repository transactions keep at most 20 records in each table. Campaign view assembly reads resources and bounded rolls in one transaction and is shared by main app and overlay.

Migration 2→3 prunes event rows by indexed revision/id before decoding any body, reads large text as 8 KiB SQLite BLOB substrings, and streams the campaign pending array into a 20-entry window. Valid retained rolls keep their actual profiles, expressions, faces and results. Bad historical records are isolated; invalid campaign resources cause a concise failure without replacing data. Legacy duplicate archives are deleted. Migration 1→2→3 remains supported; no destructive fallback.

Backups preserve active state and at most 20 combat rolls. Oversized legacy files are streamed from SAF on IO, never loaded into a String/JSON tree first. Parsing has bounded record sizes, depth 64, an 8 MiB retained-data envelope and a 512 Mi-character streaming input limit. The import preview holds only the bounded decoded result. No duplicate archive is created on import. Undo restores retained resources/results, but cannot resurrect rolls permanently discarded by retention.

Combat rules are unchanged. Package `com.yazsras.astravar`, versionName 1.5.1, versionCode 8. Use the existing owner key only; never commit signing material. Consult TEST_REPORT.md for actual results and upgrade-device limits.
