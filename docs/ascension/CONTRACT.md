# Cloud protocol v1 implementation contract

The existing Sites Worker is the sole public ingress. Routes in the cloud protocol use `/v3/continuities/{continuityId}`. Authenticated MCP uses the same service methods. Unenrolled continuities continue using the existing private bridge. All new SQL table names are prefixed with `cloud_`.

## Identities and SQL

All tenant tables use `owner_id TEXT, continuity_id TEXT` and tenant-qualified keys. Times are UTC epoch milliseconds. Cloud revisions and epochs are nonnegative integers, starting with epoch 1 and revision 0 on explicit creation of an empty synthetic continuity. Authority is `legacy`, `staged` or `cloud`.

| Table | Application contract beyond the tenant key |
|---|---|
| cloud_continuities | authority, epoch, revision, schema_version. Composite primary key owner_id, continuity_id. |
| cloud_entities | id, kind, actor_id (nullable; equals own ID for an actor), title, data_json, life (active/archived), revision. One ID space across kinds per continuity. |
| cloud_links | from_id, relation, to_id. Composite PK including tenant and three columns, two same-tenant entity references. |
| cloud_grants | subject, scopes_json, actors_json, auto_json, expires_at, revoked, revision. Separate, owner-issued permit for each continuity and principal. '*' as explicit full actor access. Scopes are a JSON array of e.g. inventory.read and inventory.write. auto_json is an array of write scopes eligible for direct commits. |
| cloud_requests | command_id, subject, digest, request_json, access_json, status, submitted_at. Stable identity, request bytes and submitter; status can only be finalized through a reviewed operation. |
| cloud_receipts | command_id, transaction_id, status, epoch, revision, result_json, access_json, created_at. Immutable. One terminal receipt per command. |
| cloud_changes | revision, ordinal, entity_id, kind, actor_id, change_type. Consumption uses canonical records or tombstones plus full snapshot on scope changes. |
| cloud_tombstones | id, kind, deleted_revision. Reject recreation of a deleted identity. |
| cloud_guards | ok INTEGER NOT NULL CHECK(ok=1). A transactional assertion for preconditions; successful batches empty the table. |

Domain definitions are versioned JSON contracts with constrained scalar fields. Membership, provenance, ownership, equipment/hard dependencies and historical relationships use validated same-tenant links. Special-purpose columns/tables can be added without a second actor, wallet or item identity. Keep collections pageable. Avoid arbitrary public entity put/delete/SQL endpoints.

## Domain planner contract

A command envelope has exactly `{version:1,commandId,epoch,expectedRevision,operation,args}`. A domain module exports a frozen object mapping operation names to `{scope,schema,plan}`. Scope is a category without a suffix. Schema is a strict JSON Schema object for args with named properties and required fields. Each async `plan(ctx,args)` returns a small, JSON-safe named result. It may throw `BridgeError` for invalid input. Nothing is persisted until the central service validates the entire plan. Namespace kinds/ops use snake_case; scopes are character, inventory, economy, resources, combat, spells, rolls, crafting, world, chronicle, media, lifecycle.

| Planner call | Behavior |
|---|---|
| ctx.get(kind,id) | Returns canonical record or null. Enforces read permission and actor scope. Record shape is {id,kind,actorId,title,data,life,revision}. Includes staged changes. |
| ctx.put({id,kind,actorId,title,data,life}) | Async. Creates or changes an entity. Checks read/write scopes for old and new owners, stages a before/after change. ActorId is explicit, null for continuity-wide records. life defaults to active. |
| ctx.list(kind) | Async bounded scoped results. Exceeding the bound fails explicitly. Use a direct ID for gameplay when possible. |
| ctx.link(fromId,relation,toId) / ctx.unlink(fromId,relation,toId) | Async. Stages a same-continuity edge. Both endpoints must exist and be accessible. |
| ctx.require(scope,actorId,write=true) | Asserts a category/actor permission, records it for approval and receipt visibility. An actor-scoped grant cannot see a continuity-wide record. |
| ctx.now, ctx.commandId | Trusted UTC milliseconds and stable command identity. Never use the wall clock to infer completed game time. |

Owned domain modules: cloud-mechanics.mjs for character, resources, combat, spells and rolls; cloud-records.mjs for inventory, economy, crafting, world and Chronicle. Each also exports a kind-to-scope object. All actions on canonical related entities use ctx, including new resources or provenance. Versioned operation schemas shared with the clients are also the discovery source. No domain module may accept an arbitrary table, SQL snippet, arbitrary projection, open JSON patch or unvalidated document. Each unsupported operation remains unavailable until it has a typed validator and evidence.

## Client transport and security

GET capabilities, GET state, GET receipts/{id}, GET approvals, POST commands and POST approvals/{id}/{verify|refuse} are relative to the cloud route. Authenticated native credentials use existing bearer / X-Astravar-Credential. DM principal is the existing authenticated Sites MCP identity with separate cloud grant. Only the authenticated owner with same-origin CSRF protection may alter grants or make an owner decision. Device owner decisions require an independently reviewed device approval credential; a gameplay grant alone cannot authorize owner decisions.

Submit returns status, commandId, epoch, revision, and either a receipt or a pending reason. Revisions are never inferred from a push. A receipt contains the transactionId and before/after changes. Readback enforces present-day scopes even for historical receipts. Pending review binds exact request digest, input revision and submitting principal; approval rechecks original grants and every dependency. Cloud mutations guard grant revision, expiry, credential state, authority epoch and continuity revision in the atomic SQL batch. An exact retry returns existing disposition under current visibility. If record evidence cannot prove event identity, return a conflict. Same-value events remain distinct.

### Authenticated traffic state

Migration 0013 adds `cloud_rate_buckets(owner_id,continuity_id,subject,next_ms)` with a composite primary key. The gateway charges one budget per authorized subject and continuity before performing gameplay work, using both HTTP and MCP entry points. The reserved `*` continuity identifies the authenticated owner’s isolated enrollment budget. This operational table is independent of the authoritative gameplay revision. Cold recovery preserves canonical records and receipts and starts with fresh rate capacity. The operations guide documents configuration, costs, HTTP and MCP response bodies and exact retry behavior.

The optional `form.data.orderKey` is a persistent ASCII sort key. Before its first move a configuration sorts by its own stable ID. `move_form` accepts two adjacent active forms of one actor and atomically exchanges those keys. A display-only change preserves the effective `configurationRevision` used by tracked transformation. `duplicate_form` accepts the actor, source form, distinct target form and new title. It snapshots only canonical form configuration, preserves finite pools and item references, links source form identities through `inherits_form_rules`, and copies selected component types against the same attack. Source-specific definitions and exclusions are evaluated against the actual form and its approved inherited form IDs. Every artwork association still names its reviewed target. Retirement enters the existing exact-revision, owner-reviewed lifecycle operation.
