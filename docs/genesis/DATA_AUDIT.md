# Genesis data architecture audit

Baseline: `3543e83bfca5e589f1bbedb09964431ce94a2319` (PR #14), inspected from the Genesis branch. This document records source inspection, not executed emulator or handset acceptance. No production record, schema, signing material or source-of-truth designation was changed by this audit.

## Finding

The application has reliable transactions **within** its established domains, but its character-facing product combines independent authorities. Reforge adoption intentionally creates a local gameplay copy. The original campaign profile, item placement, companion sheet, and cloud synchronization continue to operate separately. Identical IDs therefore do not imply shared current values.

The architecture—not merely the navigation—explains the reported missing, duplicated and disconnected content. Another database or more UI-only mirroring would compound it.

## Existing authorities and preservation boundaries

| Domain | Current store / owner | Identity and write behavior | Reconstruction implication |
| --- | --- | --- | --- |
| Accepted Astravar weapon mechanics | `astravar.db`, `AstravarDb` v3, `Repository`, shared pure Kotlin weapon engine | Campaign state plus immutable history preservation; explicitly initiated mutations | Preserve engine, existing state and retained original snapshots. It is not a generic character DB and must not become one by assumption. |
| Campaign records and physical inventory | `astravar-campaign-ledger.db`, `CampaignLedgerDb` v6 | `LedgerItem`, stable item ID, nullable quantity, evidence status, revision; `ItemPlacement` has one row per physical item and foreign keys to holder/container/project | Strongest existing relational foundation. Creation, quantity, placement, crafting and source records already have audited transactions. |
| Campaign actor identity / descriptive statistics | `character_profiles`, `character_abilities`, `party_sheets` in campaign DB | Profile ID; per-record revisions; nullable unknowns; sheet foreign key; source attribution | Current HP/AC/abilities here are not current Reforge values after adoption. Narrative sheet data must not be automatically translated to mechanics. |
| Companions | Campaign profile plus `companion_states`, `companion_resources`, `party_sheets`, generic links | `createCompanion` writes profile and companion state atomically with shared ID | Companion is a role plus associated records, not a name match. Existing creation has no owner parameter. |
| Playable characters, inventory, spells and rules | `astravar-reforge.db`, `ReforgeDb` v1, `ReforgeRepository` | Whole `ReforgeCharacter` JSON aggregate; nested item definitions/instances, effects, spells, features, resources; separate revisions, changes, rolls and note drafts | Typed engine is useful and must remain authoritative for calculations. Its current physical storage and duplicated item/profile fields create the split. |
| Campaign relationships / NPCs / locations | `campaign_links`, `connection_entities`, facts and milestones | Existing-endpoint validation; unlink appends a tombstone audit and retains endpoints/history | Reuse stable endpoints and relationship removal semantics. Do not delete entities when removing an association. |
| Campaign scope | `PartySheetRecord.campaignId`, wallets/checkpoints/references and explicit external membership references | Scope assembled by `CampaignSyncDomain.snapshot`; default `local-campaign` only under specific rules | There is no first-class campaign entity or universal campaign foreign key. Do not infer campaign membership from display name. |
| Source material / provenance | Campaign archive/reference tables and external IDs | Original hashes, source revisions, reference relations and import receipts | Remain provenance, not automatically approved gameplay facts. |
| Crafting | Projects, stages, material requirements, executions and consumption rows | Explicit confirmed material consumption and output share one campaign transaction | Preserve accounting and distinguish planned work from completed output. |
| Owner synchronization | Separate connection/settings/queue databases plus `CampaignSyncDomain` | Scoped, revision-checked campaign transactions; immutable device receipts | Reforge is absent from the synchronized domain. New local authority must not silently enlarge existing read/write grants. |
| Fictional test transport | Separate sandbox/remote-stat DBs | Deliberate isolation | Preserve separation; never use as a shortcut to real-data integration. |

Primary sources: `data/CampaignLedger.kt`, `data/CampaignRecords.kt`, `data/PartyRecords.kt`, `data/ReforgeStore.kt`, `data/CampaignSyncDomain.kt`, `data/Store.kt`; paths relative to `app/src/main/java/com/yazsras/astravar/`.

## Verified causes of divergence

| Finding | Evidence at baseline | User-visible consequence | Reconstruction decision |
| --- | --- | --- | --- |
| Adoption is a one-time copy | `ReforgeLegacyAdapter.readLegacyPreview/adopt`; `ReforgeOrigin` retains source JSON/hash; warning explicitly says stores remain separate | Updating old profile or remote campaign HP does not update playable HP; local edits do not update campaign status | Keep snapshot history, replace silent ambiguity with explicit authority and field-level reconciliation before cutover. |
| Character creation writes only playable DB | `ReforgeCharacterSheet` Create calls `local.create` | New character has no campaign relationships until another workflow is completed | One creation service must establish actor kind and membership in the same authoritative transaction. |
| Existing identity bridge is name-only | `ReforgeCampaignIdentity.createReviewedCampaignIdentity`; immutable receipt proof and ID collision protection | Creating campaign identity does not integrate HP, stats, equipment, ownership or resources | Preserve proof; do not describe it as synchronization. Use as input to reviewed unification. |
| Inventory import is UI-owned and one-way | `ReforgeCharacterSheet` ImportItem and `ReforgeCampaignItemBridge.campaignItemPlayableCopy` | Copy creates another writable representation of the physical item | Move acquisition logic into repository; validate exact ownership/scope and preserve source/placement proof. |
| Import picker is not holder-scoped | `available` filters all `repository.items`; excludes only items already in selected character | An item assigned elsewhere can be copied to another playable sheet; exact physical IDs can exist on several sheets | Reject new ambiguous acquisition and provide reconciliation for existing duplicates; never delete duplicates automatically. |
| Physical ownership uniqueness is only local to aggregate | `validateCharacter` checks duplicate item IDs inside one character; `create/save` do not check other character rows | Separate sheets can each carry the same physical item ID outside explicit transfer | Enforce uniqueness at repository transaction boundary, ideally indexed ownership in the existing authority. Grandfather existing conflicts for review without blocking unrelated HP edits. |
| Import omits important physical state | `campaignItemPlayableCopy` returns only ID/name/description/quantity; carried, unattuned defaults | Source placement, container, evidence and attunement are not integrated | Unknown/unreviewed states remain visible; never invent an equipped or attuned state. |
| Adoption loses container structure in the playable projection | `readLegacyPreview` maps placement state but not `containerItemId`; original source snapshot retains it | Items appear flat even when source inventory has containers | Reconcile valid included container IDs, retain omitted/unknown placement as unresolved. |
| Item definitions are copied between character aggregates | `InventoryRules.transfer` copies definition if absent, rejects conflicting definition | Later edits to definitions on other sheets can diverge | Definitions need one authoritative identity/catalogue; physical instance owner remains separate from definition. |
| Companion creation does not set owner | `CampaignRecordsRepository.createCompanion(name)` accepts no owner/campaign | Newly created companion may exist in global roster without attachment to initiating character | Contextual creation must explicitly bind owner and campaign without inventing statistics. |
| Playable character has no actor kind, owner or campaign field | `ReforgeModel.ReforgeCharacter` | UI cannot reliably distinguish new character, companion or NPC; ordinary character creation is labeled “character or companion” | Add explicit role/membership metadata with unknown legacy values; do not classify arbitrary IDs as companion. |
| Companion presentation uses legacy special cases | `PartyPageV2` known IDs and companion-state roster; `PartyRelationshipsV31` treats every non-`local-player` as companion | Generic additional PCs may be omitted or interpreted incorrectly | Roster should derive from explicit role and relationships, not hardcoded identity. Preserve labels only as historical fallback. |
| Narrative and executable mechanics differ | `PartySheetData` vs `ReforgeCharacter`; `ReforgeCompanionActions` converts only confirmed limited attacks | Descriptive abilities do not necessarily appear as usable attacks/resources | Keep reviewed conversion, unsupported limitations and source evidence; never parse arbitrary prose into effects. |
| Generic creation and multi-campaign scope are incomplete | No campaign table; membership distributed across sheets/references | A campaign editor cannot safely be implemented as a new label alone | Establish campaign identity/membership deliberately before claiming campaign lifecycle completion. |
| Full backup domains remain independent | `CampaignBackup`, `ReforgeBackup`, weapon backup | One backup does not protect all app data | Unification requires a complete recovery manifest and verified domain coverage; preserve old formats. |

## Existing safeguards worth retaining

- Campaign records use optimistic revisions, command fingerprints and immutable outcome receipts. Changed retries reject; identical retries return original results.
- Item placement has foreign keys and cycle/availability validation. `PartyRecordsRepository` can atomically acquire, assign, exchange and adjust quantities in the same campaign database.
- Reforge has transactional saves, roll/resource commits and cross-character item transfer within its own database. Transfers use the same physical instance identity and reset activation only under explicit transfer rules.
- Character selection is now scoped through `CampaignCharacterDao.snapshot`, not whole-database export. It preserves related identities, revisions, incident relationships and source metadata without loading every narrative body.
- Adoption rejects changed reviewed source; confirmed companion attack conversion rejects uncertain mechanics and intervening changes.
- Unlinking retains endpoints and immutable history. Archiving playable characters is reversible.
- Unknown current/max HP and abilities are represented as null. Existing state is not healed or reset on opening a screen.

## Smallest coherent foundation

The safe implementation sequence is two distinct layers. The first provides immediate integrity improvement; the second is necessary before declaring fully integrated reconstruction.

1. Introduce one application-facing domain service over **existing** repositories, with typed actor/item identities and explicit write routing. No new database. Give UI a single observed actor workspace containing playable mechanics, canonical inventory/relationships, provenance and unresolved conflicts. A projection is read state, not another persisted authority.
2. Move inventory acquisition out of composable callbacks. Enforce current item and placement revisions, exact target identity, explicit campaign membership, globally unique physical ownership and immutable source proof. Ensure stale source or stale character rejects without writing. Retain current genuine cross-character transaction for supported transfers.
3. Add explicit actor role, owner and campaign membership in the established relational store through additive schema changes. Existing ambiguous actors remain unclassified for review. Contextual creation writes identity, role and associations atomically.
4. For full mechanical/physical integration, co-locate typed playable tables in the existing campaign database, with a lossless, idempotent migration from Reforge that preserves every character JSON byte, origin, command, roll, draft and timestamp. Leave the original Reforge file intact as a recovery source until validation. Identical rows can deduplicate; conflicting rows must block cutover and be reviewable. A durable validated migration receipt must select the destination authority after commit so restart cannot re-copy a changed destination.
5. Within that single transaction boundary, route actor and inventory commands through one domain API. Maintain explicit authority for mutable gameplay values; retain old source snapshots as provenance. Reconcile old profile/item differences once through review rather than last-write-wins. Read-only legacy projections must not remain alternate editing surfaces after cutover.
6. Keep the special weapon engine/store and all remote grants unchanged until a separately verified mapping exists. Do not call UI integration a backend or weapon-resource synchronization proof.

Step 4 is a real migration and requires its own acceptance evidence. Moving tables alone does not resolve conflicting HP or items, and simultaneous writes to two independent Room databases are not an atomic alternative. If it cannot be validated in the current execution, preserve an incomplete checkpoint instead of claiming full integration.

## Required tests and acceptance evidence

Existing relevant suites include `CampaignCharacterSnapshotTest`, `ReforgeLargeCampaignSelectionTest`, `ReforgeLegacyAdoptionTest`, `ReforgeRepositoryTest`, `ReforgeItemTransferTest`, `ReforgeCampaignIdentityTest`, `CampaignSyncDomainTest`, `PartyRecordsTest`, `PartyMigrationTest`, `CampaignArchiveMigrationTest`, and `ReforgeInventoryFlowTest`. This audit did not execute them.

| Scenario | Required proof |
| --- | --- |
| Same physical item imported to two characters | Second acquisition rejects at repository boundary, including concurrent attempts and archived original holder; no duplicated stock or changed receipts. |
| Source item belongs to another actor/campaign | Picker does not offer it as ordinary acquisition; forged/stale submission rejects; explicit transfer follows established ownership semantics. |
| Source quantity/placement changes during review | Neither partial acquisition nor mechanical activation occurs; current source remains intact. |
| Existing duplicate physical IDs | Explicit conflict list; unrelated HP edits remain possible; resolution preserves original histories and never discards quantities by inference. |
| Item lifecycle | Create once, assign, container move, equip, attune, edit effect, transfer, reopen; identical IDs and quantities across all views; recalculation through the existing engine. |
| Source approval | Proposed/unverified property never becomes active merely because item was acquired; unknown quantity stays unknown. |
| Companion lifecycle | Contextual create sets owner and campaign, no invented stats; companion seen in correct roster; edit/reopen uses same identity; removal of ownership preserves companion. |
| Multiple player characters | Second PC does not become companion solely because ID differs from `local-player`; campaign switching does not leak actors. |
| Lossless store migration | Realistic code49 fixture with all tables, originals, receipts, malformed preserved history and drafts; byte/content checks before and after; SQLite foreign-key/integrity checks. |
| Restart/fault injection | Termination before copy, midway, before receipt and after commit; resumes exactly once without replacing newer state; old source remains recoverable. |
| Conflict/corruption | Duplicate IDs with differing content, unsupported schema, truncated source, insufficient space; no destructive fallback or empty replacement. |
| Upgrade/recovery | Same package and owner signature, normal `adb install -r`, UID/installation continuity, no clear/uninstall; data comparisons and actual UI reopen separate from compilation. |
| Cloud changes vs local edits | No silent authority promotion or enlarged scope; if not integrated, UI accurately exposes outstanding divergence and release report marks it incomplete. |

## Audit limits

This is a source-based architectural audit only. It does not establish that every button works, that migrations have run, or that the application is usable on a physical Samsung handset. Full UI coverage, baseline screenshots, repository tests, native journeys and signed upgrade evidence remain separate deliverables.

## Preparatory foundation implemented after the baseline audit

`GenesisInventory.kt` now exposes a shared observed inventory projection keyed by physical item ID. It retains every playable holder, the original campaign item and placement, and explicit review issues. Multiple holders or contradictory ownership do not select a stock total arbitrarily. A unique current playable item remains distinct from its older source evidence; quantity/description/placement differences are visible. An archived campaign source remains visible when its physical ID is still held by a playable sheet.

Repository acquisition replaces the UI-only copy mechanism through `reviewCampaignItem` and `acceptCampaignItem`. The source item must already have a verified placement assigned to the exact actor. Missing or other holders are unavailable until campaign assignment is reviewed. The selected source item, placement, profile revision, evidence and item properties are retained in an immutable `INVENTORY` receipt. The source is checked again before a single local character/receipt transaction. Changed target revisions, modified review interpretation, concurrent physical-ID claims and colliding command retries reject. The same exact retry returns its original outcome even when later source or gameplay edits exist.

Ordinary character creation, saves and action commits now guard physical ownership across playable sheets, including archived owners. Existing duplicate identities remain preserved and visible for review; unrelated edits still work. Changing an ambiguous duplicated instance or introducing another claim rejects. Explicit supported item transfers retain the existing atomic two-character transaction. Backup merges cannot introduce new ownership collisions between otherwise separate histories. A historic backup already containing a conflict can still be recovered intact for review.

The existing Reforge Room schema remains version 1. Backup format schema 2 adds the source-proof inventory receipt; schema 1 is still accepted. No existing receipt is rewritten. Code49's older backup reader cannot consume schema2, so this is not a promise of application downgrade compatibility.

Before a newly accepted campaign item can commit, the complete prospective Reforge backup is validated and serialized within the same local transaction. If source proof plus immutable before/after history would exceed the supported export limit, the new acquisition and receipt roll back. This deliberately more expensive check is confined to explicit campaign-item acquisition; it does not impose a global backup scan or new failure on unrelated HP/rest edits. No duplicate exported text is persisted. The near-limit test retains an existing valid backup, attempts a bulky new source proof, and checks rollback plus restoration from the earlier backup.

`GenesisInventoryTest` adds targeted proof for context/ownership, stale reads, unapproved effect rejection, exact retry, concurrent acquisition, legacy conflict handling, cross-view projection, backup validation, forced receipt-insert rollback and actual database close/reopen. Execution results must be recorded from the test run; the presence of these tests is not a passing result.

### Storage cutover A supersedes the physical-store limitation

The initial projection/acquisition implementation above preceded storage cutover A.
Current production now uses campaign schema 7 and a shared Room instance for the
campaign and playable repositories. All four playable table definitions and raw
values are copied without JSON rewriting; a durable installation receipt prevents
replay of the retained original over later edits. Source validation and reviewed
acquisition now occur in the same production transaction, closing the earlier
cross-store race. Read `STORAGE_CUTOVER.md` for recovery, backup boundaries and
executed migration evidence.

**Still incomplete:** shared storage does not make legacy campaign fields and
playable fields one authority. Their independent edit paths, differing values and
source provenance still require explicit reconciliation, authoritative command
routing and portable authority-aware recovery. Owner synchronization and new
campaign/actor/item creation must be connected through that layer before claiming
the full unified item/actor lifecycle.
