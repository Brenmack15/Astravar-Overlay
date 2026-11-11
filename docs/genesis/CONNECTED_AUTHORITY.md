# Genesis connected authority checkpoint

This is the bounded authority implementation after the schema-7 storage cutover, not a claim that the full reconstruction or every legacy campaign has been accepted.

## Implemented contract

Schema 8 adds campaign identities, explicit actor role/membership/ownership, physical item authority, and immutable command receipts to the existing `CampaignLedgerDb`. Every historical table keeps its schema; startup enrolls nothing. The original retained Reforge database and the installation-local storage receipt remain governed by the storage-cutover rules.

`GenesisAuthorityRepository` creates real campaigns and source/playable actors in one Room transaction. PLAYER, COMPANION and NPC are explicit choices. Source-only NPCs and companions have identities and relationships without invented levels, HP, proficiency, attacks or statistics. Creating a playable actor writes its actual identity, profile, abilities, shared sheet and configured inventory together. New NPCs also have the same-ID Connections record. Campaign selection uses the existing preferences store, while membership resolves a first-class database identity.

Existing records require a frozen, revision-bound authority review. The review binds the current playable aggregate, source snapshot, supplemental companion/entity/resource rows, explicit membership/owner, and every shared field difference. Acceptance preserves the original adoption origin and records the complete reviewed source once in an immutable receipt. Unknown or conflicting ownership blocks acceptance. Existing identities and pending/unverified mechanical evidence are retained; imported prose never becomes an automatic modifier.

After acceptance, gameplay writes use the established rules engine. Canonical HP, conditions, configured resources, abilities, quantity, equipment, containers and transfers project to the source views inside the same Room transaction and retain normal source audits plus gameplay receipts. Legacy HP changes retain exactly one ordinary caller profile revision/receipt, including no-op approvals. Transfer stages both canonical owners before projecting physical ownership. Removing an association retains the physical identity and exact last-owned mechanics; the source placement becomes unassigned storage. Reassignment reuses those mechanics and quantity, clearing former attunement for the new owner. Companion resources bind stable canonical pool IDs to source resource IDs, so renaming preserves the counter and replaces the previous managed sheet label.

Legacy entry points either route compatible managed changes or reject unsupported mechanical decisions with a review action. Explicit first-class memberships govern sync scopes; an unassigned membership cannot silently fall back to an obsolete legacy campaign label.

## Portable recovery

Complete connected backup format 1 contains the existing campaign backup, existing playable backup, and portable authority graph. It is merge-only, validates exact conflicts and request-bound immutable outcomes, and commits all domains plus its restore audit atomically. Installation storage receipts are intentionally excluded and never replaced by portable files. Old campaign formats and old playable formats remain accepted as partial merge inputs when they preserve the merged authority graph. Existing historical backups are not rewritten.

The complete file is bounded by actual UTF-8 bytes (64 MiB). Large gameplay history and authority proof columns are read in bounded chunks with strict UTF-8 decoding, preserving exact strings instead of forcing wide rows through Android CursorWindow. New authority creation/acceptance and reviewed managed acquisition validate a complete prospective backup before commit. Partial export/restore cannot strip management markers and turn connected projections into independently writable truth. Archive imports may add disjoint source records; graph validation prevents imports or rollback from replacing connected truth.

## Deliberate limits

- Existing actors are not automatically enrolled. Source-only membership does not silently promote an existing local playable copy.
- Legacy manual magical modifiers require a dedicated per-effect reconciliation decision before mechanical authority acceptance. The existing playable/effect editors remain available, but this legacy reconciliation is unfinished; modifiers are never silently zeroed or treated as approved structured effects.
- Managed crafting material consumption and remote acquire-and-assign require a further integrated command path. They currently fail with an explicit review message; their unconnected historical workflows remain available.
- Shared source projections cover gameplay values described above. Existing narrative spell/attack/form/ruling content remains sourced descriptive information; it is not parsed into mechanics or deleted.
- A generic source “Equipped” value cannot choose WORN versus HELD. Equip through the explicit playable control; retained unassigned items are initially reassigned Carried or Stored.
- Partial backup buttons may direct connected databases to the complete connected backup surface. This preserves authority instead of exporting an apparently complete but split graph.
- Physical-device acceptance and a signed release are separate root milestones. This document does not claim either.

## Verification checkpoint

Schema 8 was generated by Room. The unchanged schema-1 through schema-7 pins and the additive-table gate pass. Source changes retain exact reversible review proof and the original owner-signing block pin. Focused repository, route, backup, archive, UI and historical-migration suites are being executed; final counts and commit are recorded in the parent checkpoint after results are inspected. Do not treat an unrun test class or compilation alone as journey verification.

### Recoverable backend implementation checkpoint

The first full focused run compiled but recorded 89 tests / 41 failures. The common cause was the new small-row backup reader using `getBlob(TEXT)`, which included a trailing control byte on the exercised Android driver. The reader now casts TEXT to BLOB explicitly and has an exact Unicode/NUL/scalar regression. The next core run recorded 48 tests / 7 failures; 41 passed, including all four archive boundary cases and wide-row/byte-limit recovery checks. The seven failures were triaged to four owner-link endpoint mismatches in portable validation, an invalid acquisition fixture order, one inconsistent HP fixture, and newly generated campaign IDs containing a character excluded by the existing sync protocol. Corrections are on disk and require rerun. Product and native-test compilation succeeded before those last corrections.

This checkpoint is not a release candidate. Outstanding verification includes the authority-command collision guards, corrected core tests, conditional-action/short-viewport UI tests and refreshed native journeys. The whole-campaign export performed after authority creation is also a known scale blocker under review: unrelated large source archives must not prevent basic connected creation. Replace that check with targeted transactional invariants while retaining explicit complete-export limits and the existing bounded playable acquisition recovery check. No original data, old schema pins, application/signing identity, or historical backup format was replaced.
