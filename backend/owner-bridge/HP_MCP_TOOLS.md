# Owner-approved HP tools

The existing fictional character/ability tools remain scoped to their original fixed test records. These additional tools use a separate, explicitly approved live HP binding. They do not import a campaign, expose the complete local database, or modify abilities, inventory, currency, companions, combat formulas, temporary HP, or maximum HP.

## Authentication and authority

The MCP endpoint runs behind owner-private Sites dispatch. It requires the exact platform-verified, pinned owner identity before calling any data operation. A caller-supplied device bearer, display name, role, owner ID, or `confirmed` field is not ChatGPT authentication. Nonsecret protocol discovery and schema listing remain available before configuration for plugin provisioning; they reveal no connection records.

HP tools receive a server-constructed connector principal with only `hp:read`, `hp:propose`, and `hp:receipt`. They cannot pair, approve, reject, publish, enroll, or revoke. The native app separately obtains `hp:read`, `hp:publish`, and `hp:decide` for the player-selected profile following explicit consent. Existing fictional device credentials do not grant live HP access.

The profile ID is the existing local record identity. The campaign ID and profile binding come from verified enrollment and publication, not a character name or an invented alias. `get_owner_connection` returns approved binding metadata and published record IDs only; it is not a database export.

## Tool definitions

`mcp.mjs` contains the exact schemas and dispatch. Every arguments object rejects additional fields. Canonical lowercase UUIDs identify commands; revisions are positive safe integers. Campaign/profile IDs match `[A-Za-z0-9][A-Za-z0-9._-]{0,127}` and must also pass the backend's approved-binding checks.

| Tool | Required arguments | Narrow result |
| --- | --- | --- |
| `get_owner_connection` | None; an omitted arguments object or `{}` is accepted | Approved live HP bindings, publication/revision metadata and nonnegative `supersededCount` |
| `get_character_hp` | `campaignId`, `profileId` | One device-published HP record, revision, provenance and synchronization freshness |
| `propose_hp_adjustment` | `campaignId`, `profileId`, `commandId`, `expectedRevision`, `expectedCurrentHp`, `adjustment`, `source` | A server-computed proposed HP difference requiring native approval |
| `get_hp_pending_updates` | `campaignId` | Pending HP-only differences within active approved bindings, plus separate bounded repair metadata |
| `get_hp_command_receipt` | `campaignId`, `commandId` | Independent immutable outcome for an active approved HP binding, or an unverified error |

`expectedCurrentHp` is an integer from 0 through 1,000,000. `adjustment` is a signed, nonzero integer from −1,000,000 through 1,000,000. The proposal source is required and limited to 1,900 characters without truncation. The server computes the exact proposed current HP as its verified current HP plus the signed adjustment and rejects a result outside zero through the known maximum HP; it does not clamp the value. Clients cannot submit a replacement `currentHp`, `fields`, maximum HP, temporary HP, dice result, rest, or authority flag.

A proposal requires the expected revision and expected current HP to match the saved record. Current and maximum HP must be known, and a sufficiently fresh device synchronization must exist. Unknown or stale values are not filled in or refreshed by the tool. A local/server conflict requires an explicit new proposal after a fresh read; a retry reuses its original command ID and exact request.

The proposal fingerprint binds the canonical object:

```text
{campaignId, commandId, recordId: profileId, expectedRevision,
 fields: {currentHp: serverComputedValue}, source, adjustment}
```

The exact saved proposal and fingerprint must be shown and checked by Astravar. Native approval commits through the existing local record path, durably retains its outcome for delivery, and reports the committed fields/revision. The MCP proposer cannot create that decision. Receipt retrieval and a separate record read establish confirmation; a proposal response, HTTP success, timer, missing receipt, or failed request does not prove a saved change or rollback.

Re-pairing replaces the earlier writer for that profile and does not transfer any pending approval. Pending-update responses separate active proposals from `supersededCount` and up to 50 `superseded` metadata entries marked `SUPERSEDED_BY_REPAIR`. These entries contain command/record IDs, the expected revision and source, without the old HP fields. The marker is not a manufactured rejection or application receipt: an old unacknowledged command remains unverified, and its original receipt stays unavailable. Any new adjustment requires a fresh read and a new proposal under the current approved binding.

## Conversation flow

The connected host discovers the ordinary MCP tools through the existing Site plugin. ChatGPT first reads the approved connection metadata and the selected HP record. After a player requests a specific HP adjustment, it proposes that adjustment using the returned current HP and revision, then leaves approval to Astravar's native control. After the player approves, ChatGPT independently retrieves the immutable receipt and reads the saved HP record again. A confirmed HP value and revision must agree across both results.

Pairing consent and the final native HP approval remain the player's actions. Developer-run protocol, conflict, retry, authorization and persistence tests provide engineering evidence without requiring the player to perform a routine test checklist.

Actual host discovery, authenticated invocation and native device results must be recorded separately from local HTTP/MCP tests. A published endpoint or a passing mock does not establish that ChatGPT can invoke the tool or that the installed phone committed its result. A device-published server snapshot can be older than an offline phone's local state; freshness and revision checks must remain visible.
