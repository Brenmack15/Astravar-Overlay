# Astravar campaign synchronization V2

The original fictional and HP V1 contracts remain available and isolated. V2 requires an explicit new campaign enrollment; an HP token never implicitly authorizes campaign publication. All requests use existing authenticated HTTPS service transport plus `Authorization: Bearer <device proof>` and `X-Astravar-Credential` for device routes. Owner enrollment uses Sites-verified account identity, never a caller-selected owner. MCP cannot pair, approve, grant or revoke.

## Enrollment and standing authorization

`POST /v1/pairings` retains campaignId, profileId, deviceName, deviceSecretHash and adds optional `campaignAccess:{readScopes:[...],writeScopes:[...]}`. Categories: hp, resources, inventory, currency, character, companions, engineering, continuity, combat. Writes must be a subset of requested reads. Existing owner summary and approval show the explicit categories. Expanded enrollment issues only `campaign:sync` transport authority; it does not issue legacy HP scopes. Token response adds `campaignAccess:{grantId,revision,readScopes,writeScopes,autoSyncScopes,expiresAt}`. Initial autoSyncScopes is empty.

All V2 paths start `/v2/campaigns/{campaignId}` and return mode `ASTRAVAR_CAMPAIGN_V2` and campaignId. `GET /grant` returns `{grant:{...}}`. `POST /grant` accepts `{commandId,expectedRevision,autoSyncScopes}` and optional readScopes/writeScopes. Those optional permissions may only narrow current owner-approved scopes; re-enabling revoked categories requires fresh owner enrollment. Empty read/write sets revoke those categories while retaining grant inspection and self-revocation. Prior immutable receipts are projected through the current read scopes, so old saved receipts cannot leak revoked fields. The authenticated native device may enable only write scopes separately approved by the owner enrollment. The server increments grant revision with CAS, preserves immutable grant events, and enforces it during submission and receipt. Native local scope authorization must independently match before commit. Revocation and expiry are checked server-side for every operation.

## Records and efficient reads

Wire record: `{recordId,kind,revision,fields}`. `fields` is the existing serialized typed Android record, retaining stable identity, source, timestamps and revision. Kinds: character-profile, character-ability, party-sheet, companion-state, companion-resource, item, item-placement, item-details, item-property, wallet, project, stage, requirement, connection, fact, milestone, link, checkpoint, reference, configuration, roll-history, audit.

`POST /records` accepts `{commandId,records:[...]}`; max 100 envelopes and bounded HTTPS body. Incremental batches are atomic. No monotonic revision rollback or same-revision content change is accepted. Optional removed entries `{kind,recordId,expectedRevision}` perform atomic CAS removal from the server mirror only, preserving the immutable publication log and all local records. An empty records batch with no removals must carry snapshotFingerprint, SHA256 of canonical sorted `{kind,recordId,revision,fingerprint:SHA256(canonical(fields))}` summaries for the CURRENT projected Android snapshot. The server compares its own current summaries and serializes against mirror_generation before updating freshness. A mismatch leaves the mirror stale. A unique matching empty batch is an authenticated freshness heartbeat; retrying an old command never refreshes freshness. Device journal records the publication and independently reads its actual current mirrored rows with GET /publications/{commandId}; this returns exact four-field records for the publication IDs, not cached original payloads. Singular independent GET /records/{kind}/{recordId} remains available. Removed rows are independently checked absent.

`GET /records?kind=...&recordId=...&after=...&limit=...` returns filtered `records`, pagination cursor, source revision/freshness and verification time. `GET /records/{kind}/{recordId}` returns exact four-field `record` plus metadata; IDs may contain a colon for composite ability IDs. Unknown/null values remain unknown. Five-minute freshness is explicit, not a promise of Android instant background execution.

Profile wire projection always retains identity/revision/source/update metadata. Character category adds name/armorClass/level; hp category adds current/max/temp HP. Party-sheet contentJson retains descriptive fields only for character, resources only for resources, conditions only for hp; portrait bitmap is never published. Companion resources require the companions category; player spell/class counters use resources. Audits require every category because historical before/after payloads cross category boundaries. Item and connection thumbnails are omitted. Native publication and backend read/receipt validation both enforce projection. No raw campaign DB, signing material or DM-only adventure archive is exposed.

## Typed transaction and receipts

MCP `submit_campaign_transaction` accepts `{campaignId,commandId,expectedGrantRevision,operations,source}`. Every operation is `{type,recordId,kind,expectedRevision,fields}`. Type payloads are strictly validated. Expected revision zero creates a typed new record; existing updates require its actual revision. Supported operations:

- adjust_hp: character-profile, `{delta}`; unknown HP fails closed.
- set_status: character-profile `{currentHp?,maximumHp?,temporaryHp?}` or party-sheet `{conditions}` for an explicit confirmed correction.
- adjust_resource: party-sheet `{resourceName,delta}` or companion-resource `{delta}`; unknown remaining/capacity fails closed.
- adjust_currency: wallet, optional copperDelta/silverDelta/electrumDelta/goldDelta/platinumDelta; no negative or invented unknown balance.
- move_item: item, `{expectedPlacementRevision,holderProfileId?,containerItemId?,installedProjectId?,state?}`; item and original placement identity commit together. Omitted holder/container/installation/state fields preserve the existing placement; omitted holder also preserves the existing item owner label even when no structured placement exists. Explicit null clears only the supplied identity. State, if supplied, must be a supported string. The server rejects invalid mixed container/installation destinations, containment cycles and implicit child ownership changes.
- consume_item: item `{quantity}`; zero inventory marks consumed and updates the existing placement atomically.
- acquire_item: item expectedRevision zero, fields typed LedgerDraft with confirmed evidence and caller-supplied stable record ID. Optional `placement:{holderProfileId,expectedHolderRevision,state?}` atomically creates its initial carried/equipped assignment; omitted state is Carried. Holder must be an existing published character-profile in the exact campaign and active grant with the exact expected revision (companions use their existing profile identity). No companion profile is inferred or synthesized. Item and placement both begin at revision 1 and appear in the single immutable transaction receipt. Conflicting owner/location/itemState, unavailable quantity, unsupported placement states, stale/foreign holder identities and existing item/placement IDs fail closed. No placement preserves legacy unassigned acquisition behavior.
- set_ability: existing character-ability `{score}`; preserves magical modifiers and notes.
- save_checkpoint: checkpoint typed fields.
- set_record: whitelisted descriptive typed records only; cannot bypass HP/resources/conditions operations or replace combat/audit/history storage.

Backend expands operations with scope, currentFields, proposedFields and sideEffects including their own frozen currentFields for independently validated expected results. Fingerprint is SHA256 of canonical sorted-key JSON `{campaignId,commandId,grantRevision,operations,source}` including expanded operations. Canonical numbers are safe integers; arrays retain order. Pending command status is PENDING_DELIVERY only when all operation categories have standing Auto-Sync, otherwise PENDING_APPROVAL. Both are uncommitted.

Device `GET /pending` returns `{grant,commands}`. Native code verifies the full fingerprint, frozen grant revision and local independent category scopes; applies all original typed operations in one domain transaction and preserves an immutable local receipt. Device `POST /decisions` accepts `{commandId,fingerprint,status,localReceiptId,records}`. APPLIED requires an actual local receipt and complete projected records including item-placement side effects. REJECTED/CONFLICT require no records/no local receipt. Backend compares expected resulting fields/revisions, performs atomic mirror CAS and appends an immutable independent receipt. Duplicate request retries retain the original immutable result; changed payload with same command ID fails.

`GET /receipts/{commandId}` and MCP `get_campaign_command_receipt` return `{receipt:{commandId,status,localReceiptId,fingerprint,records:[full envelopes],recordedAt,eventId,deviceCredentialId}}`. ChatGPT must independently retrieve this receipt and current saved record revision before saying a save completed. A queue HTTP success is not a device commit.

`POST /devices/revoke {}` is idempotent for the same expired/revoked self credential and returns actual server revocation; a local-only disconnect is never represented as server revocation.

## Read tools

get_owner_connection (legacy connection plus campaignSync grants), get_campaign_state, get_character, get_character_abilities, get_character_resources, get_party, get_companion, get_inventory, get_item, get_equipment, get_currency, get_inventions, get_research, get_connections, get_campaign_checkpoint, get_campaign_history, get_astravar_configuration, get_roll_history, get_campaign_record, get_campaign_reference, get_campaign_command_receipt. Reads are bounded and filtered; no arbitrary SQL or unrestricted database replacement is available.

## Evidence boundary

Node fixtures test actual SQLite/D1-equivalent atomicity and host routing; native HTTPS emulator testing exercises the real production Worker against isolated fictional records. Neither establishes a physical Galaxy commit or normal hosted ChatGPT invocation. Hosted MCP discovery, authenticated tool invocation, and physical-device acceptance must be recorded separately after actual observation.

## Chunked archive references

Large read-only source references are transported as reference metadata plus bounded UTF-8 text parts. IDs begin archive: plus a hash of the original local source identity and meta/body:index/properties:index suffixes. Metadata retains original referenceId, Notion externalId, sourceURL/revision, body/property byte counts, part counts and SHA256 values. Each part retains original referenceId, part/index/total, text and contentHash. Full archive parts require all read categories because source prose may span HP, inventory, wealth and other categories. Native chunks fit the bounded publication body; the server independently enforces archive scope. get_campaign_reference reads one metadata/body/properties part by original referenceId, optional part and index. No truncation is represented as a complete document.

Oversized immutable audits use metadata at the original audit record ID with chunked:true, originalRecordId, originalKind, contentSha256, contentBytes, parts, encoding and partIdPrefix. Ordered audit-kind text chunks use partIdPrefix + index, retain originalRecordId/contentSha256 and per-part hashes, and require all read categories. get_campaign_history/get_research list metadata instead of flooding the first page with text parts. Use get_campaign_record with the provided prefix and zero-based index to retrieve each narrow part and reconstruct/verify the original canonical JSON.

## Wire bounds and deterministic typed defaults

Archive metadata and parts retain the native sourceVersion field. New typed commands resolve omitted primary IDs from the command's stable recordId and omitted campaign IDs from its selected campaign context; explicitly wrong identities remain rejected. Deterministic Kotlin DTO defaults are normalized before producing expected saved fields, while partial updates merge existing fields first. Partial party-sheet content merges existing nested content and normalizes deterministic nested defaults; new nested attacks/capabilities need stable IDs. Unsafe incomplete new sheets are rejected before queuing, and nonlocal new sheets/characters require local identity enrollment. Unknown resource defaults stay null.

Every expanded command is projected through the CURRENT read grant before its fingerprint or response is generated. Revoking HP access therefore also removes HP from newly generated rename-command baselines and proposed fields, even before the phone republishes a narrower mirror.

Pending delivery returns an ordered prefix whose full serialized UTF-8 envelope is at most48KiB, with hasMore indicating retained work. Remaining commands stay durable for later reads after receipts. Individually undeliverable expanded commands are rejected before insertion. The backend also checks a projected result/side-effect acknowledgement estimate with bounded provenance overhead against60KiB. The native app independently verifies its actual decision payload fits65536bytes inside the same Room transaction and rolls back oversized results before any local commit. A bounded protocol response never means omitted work was completed.


### Compact 2.3 read views

Existing unfiltered get_inventory/get_equipment calls remain valid. Optional holderProfileId selects the explicit committed placement relation and returns INVENTORY_BY_HOLDER_V1; it never infers a holder from display text. Results retain each item's original record identity, provenance, revision and device freshness. get_character_resources emits RESOURCE_COUNTERS_V1 containing saved resource counters only, including explicit null values, scoped to the requested profile/companion identity. Resource and inventory pages are bounded and preserve composite kind/record identity cursors. These server mirror views never establish a newer offline Android commit.


## Project Unification diagnostics

`get_owner_connection.campaignSync.connections` now includes `publishedRecordCount`
and `synchronizationState`: `NO_READ_SCOPES`, `NO_DEVICE_RECORDS`,
`STALE_DEVICE_MIRROR`, or `DEVICE_MIRROR_AVAILABLE`. Counts and timestamps include
only records visible under the **current** grant; revoked categories and archive
bodies requiring broader scopes do not contribute. `lastDeviceSyncAt` is the
oldest visible record's device timestamp, so a fresh unrelated publication cannot
hide stale records. `latestDevicePublicationAt` separately reports the newest.
An authenticated empty heartbeat never changes an empty mirror into published
gameplay.

Scoped record/session responses add `synchronization` with the returned record
count, oldest device timestamp, explicit empty/stale state and a next action.
Paginated end-of-view reads are distinguished from an empty first view. This is
information about that bounded authorized view, not proof that the whole phone
is empty, online, or ready. `deviceReadiness` remains
`UNKNOWN_UNTIL_DEVICE_REPORTS`; only the app can diagnose its local campaign,
membership and canonical-value review. `verifiedAt` remains the server read time,
not a substitute for device publication or a committed transaction receipt.

Published records with an explicit `fields.campaignId` different from their
authorized route are rejected before atomic publication. The same check applies
to prepared operations and applied device receipt records. This does not infer
membership from a name, create campaigns or broaden grants.

Before publishing a reviewed Genesis playable actor, Android validates the
selected character's source projections against its authoritative saved revision
and fingerprint using the existing scoped reader. Divergent HP, abilities,
resources or equipment stop publication for reconciliation; synchronization does
not rewrite either store to hide the conflict. An unrelated campaign is not
scanned or exported for this check.

### Publication hotfix contract

Each complete canonical record (identity, kind, revision and fields) is bounded to
48,000 UTF-8 bytes, matching the native publisher. The HTTP request remains bounded
to 65,536 bytes. Unicode and JSON escaping count toward the wire limit; JavaScript
UTF-16 string length is not the record-size contract. Initial and replayed
publication acknowledgements carry the exact immutable persisted publication time.
Current-record readback remains independent of that acknowledgement.

### Terminal conflicts and replacement commands (Android code 58)

Use `inspect_campaign_conflict(campaignId, commandId)` to retrieve the frozen proposal, dependent placement, expected and published revisions and its immutable device receipt. A terminal CONFLICT contains zero saved records and a null local receipt ID. New devices may attach a bounded failure code, zero-based operation index (-1 for a transaction-wide check), schema field and observed revision. Legacy receipts retain the reason as unknown.

Call `preview_conflict_resolution(campaignId, commandId)` on that command. READY includes the rechecked operations, automatic or native approval mode and a preview token. Android remains authoritative. WAIT_FOR_DEVICE requires a fresh complete publication from the upgraded installation. An existing replacement identifies its immutable command. ALREADY_FULFILLED on a purchase identifies the receipt to read independently. OWNER_REVIEW and VALIDATION_FAILED require resolving the reported ambiguity or validation problem first.

For READY, call `resolve_conflicted_transaction(campaignId, commandId, previewToken)`. It rebuilds the preview, binds the new ID to its failed parent and submits through the ordinary grant, revision, native validation and receipt path. Read its receipt and independently read each saved record. Current recovery supports an atomic single-identity carried purchase with a confirmed debit, non-overlapping descriptive edits to existing projects or connections, and fixed current/temporary HP assignments with a verified prior value and unchanged HP capacity. Disputed ownership, changed payment denomination, historical source work and other material decisions receive owner review. A disabled category requires native approval. A replaced or revoked grant cannot retrieve the original private payload through the conflict endpoint.

Existing receipt readers also receive a read-only conflict preview alongside the immutable receipt when their current grant permits inspection.

## Owner-requested wake hints (opt-in)

The `request_campaign_sync(campaignId)` MCP tool authorizes one **best-effort wake hint**, not an Android action or commit. The owner identity is pinned by Sites authentication; the existing campaign grant selects the enrolled device. The request cannot supply a device, broaden scopes, approve an outstanding native migration, bypass a consent control, or insert new gameplay.

If Pushy is configured and the handset registered an encrypted token, the service issues the existing short-lived signed `ASTRAVAR_CAMPAIGN_SYNC_WAKE` payload. The owner-triggered path uses a 30-second atomic per-device cooldown and otherwise returns an explicit unavailable/throttled result. The wake contains no campaign, character, grant or gameplay data; the device authenticates through the normal connection, checks pause/revocation and fetches its existing pending queue.

The response reports provider acceptance or unavailability with `confirmation: DEVICE_SYNC_UNVERIFIED`. It **never** represents a native `APPLIED` transaction, successful background execution or guaranteed arrival during Doze/network loss. Continue to verify immutable receipts, published record revisions and native calculated statistics. On systems without Pushy registration, normal on-open sync and 15-minute recovery remain available.

## Timed and resource-bounded wearable forms

The optional Reforge `WearableForm` fields `abilityOverrides`, `armorClassOverride`, `size`, `activationSeconds`, `durationSeconds`, `deploymentResourceId`, `integrityResourceId`, `resetResourceId` and `grounded` preserve existing ordinary wearable items (default action duration six seconds, no resource costs). A one-minute deploy requires an explicitly resolved `elapsedSeconds` in a `change_wearable_form` action, and cannot be compressed into a single tracked combat Action. The device checks actual resource availability, spends the deployment once, resets only a named per-deployment feature pool, applies form-derived Strength/AC/movement, then expires or releases the chassis without healing or restoring charges. If Integrity is present, it absorbs resolved incoming damage before ordinary HP; excess damage carries through the usual defense/temporary-HP rules after ejection. These are generic, opt-in mechanics; an existing character never silently receives an alternate form.

`AttackDefinition.requiredFormId` / `excludedFormIds` and `CharacterFeature.requiredFormId` enforce form-specific actions without allowing a normal animal attack during the armored transformation. The actor's saved original physical equipment IDs and attunement are preserved. No real Eek warform may be activated until its installed native form item, attacks and features have a separate owner-approved migration and independent device verification.
