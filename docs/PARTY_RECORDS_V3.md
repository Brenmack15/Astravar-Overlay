# Party records and inventory integrity in Astravar 2.1

The 2.1 player-first branch extends the existing `astravar-campaign-ledger.db` with an explicit additive Room 2→3 migration. The original `AstravarDb`, historical Campaign Ledger schema JSON files, item identities, companion records and old audit snapshots remain intact. There is no second inventory database and no automatic campaign import.

## Character authority

`CharacterProfile` remains the saved identity, level, HP and AC record; `CharacterAbility` remains the six ability scores and their revision history. `CompanionState` and `CompanionResource` retain companion-specific condition and resource records. `PartySheetRecord` has a foreign key to the same character identity and adds typed descriptive movement, saving throws, skills, senses, languages, defenses, traits, actions, attacks, spellcasting, resources, provenance and unresolved conflicts. Its revisions are audited through the existing `CampaignAudit` and `CommandReceipt` tables.

Sheet text and damage formulas are descriptive records. Saving an attack or proposed ability does not execute it, grant an engine mechanic, assume a level-up, refill HP, or decide an unresolved source conflict. Unknown numeric values remain nullable. Editing established combat mechanics still requires accepted rules in the shared engine.

## One item, one placement

`ItemPlacement` has the existing physical `LedgerItem.id` as its primary key. A transfer changes that item, never copies it. A whole recorded stack moves as one identity; splitting or merging stacks is not currently implemented. The placement references a saved character, an existing item used as a container, or an invention project. Each transfer:

- checks the observed item and placement revisions;
- rejects missing targets, unavailable items and cyclic containment;
- updates the compatibility owner/state/location fields and immutable item/placement snapshots in one Room transaction;
- records the original outcome under a stable command ID;
- returns that original receipt on a matching retry, and rejects changed-content command-ID reuse.

A container and its contents cannot have conflicting structured holders. Changing a container's owner requires removing its contents first; the application never silently transfers other items. Old descriptive links remain relationships and do not authorize ownership or consumption.

After a structured placement exists, the legacy item editor cannot independently overwrite owner, state, location or quantity. Dedicated quantity commands check revision and availability, reject negative inventory and unknown baselines, and retain receipts. A sourced baseline confirmation can establish a previously unknown count. A zero balance becomes consumed while retaining the item identity and history. Installed components are unavailable for ordinary quantity spending.

`reverseTransfer` is a compensating audited transfer with current revision checks, not deletion of the old transaction. A first structured transfer whose previous holder was unknown cannot manufacture an old holder during rollback.

## Currency and continuity

`PartyWallet` records nullable copper, silver and gold balances independently. Only known denominations can be adjusted; no inferred zero or automatic conversion is used. All denominations in one currency command commit together or roll back together. Checkpoints retain last confirmed action, unresolved choices, objectives, references and unknowns without changing character or combat state. `commitExchange` combines explicit item acquisitions, item moves, known quantity changes and wallet changes in one Room transaction. A parent receipt retains deterministic child receipts; injected child failures roll back all changes. The command records resolved prices and quantities without inferring a purchase, conversion or new item. New acquisitions reuse the existing item-creation command inside that transaction and return its original item ID in the child receipt.

External source references preserve namespace and original external IDs, with a unique mapping to a retained record. They prevent duplicate source identities; a source link does not grant permission to import the real campaign or to change its authoritative store.

## Backups and migration review

The existing backup envelope remains `astravar-campaign-items`; schema 3 adds party sheets, placements, wallets, checkpoints and external references. Schemas 1 and 2 remain readable. New records participate in the existing read-only preview, content fingerprint, current-state fingerprint, merge-only restore, referential validation, history validation, receipt validation and rollback-on-error transaction.

Different retained IDs are not overwritten. A stale preview must be regenerated. Unsupported versions, duplicate source mappings, contradictory ownership and forged/forked audit histories reject the entire restore. A backup is not evidence that a proposed ruling or conflicting source became accepted. Real campaign migration and canonical cutover still require separate owner approval after security, backup and integration acceptance.

## Verification scope

Synthetic tests cover typed unknowns, revision conflicts, idempotent retries, single-item transfers, ownership bypass attempts, containment cycles, injected transaction failures, consumption, quantity baselines, currency bounds, checkpoint preservation, external-ID collisions, full v3 backup/restore, failure rollback, compensating transfer and real Room validation of frozen v1/v2 databases through the additive migration. Android test execution results are recorded in the upgrade checkpoint; source code alone does not establish a passing build or device acceptance.
