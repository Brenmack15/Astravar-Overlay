# Astravar Owner Connection: existing private Site HP extension

This guide extends the **existing owner-private Astravar Owner Connection Site** with a separately approved, profile-scoped HP path. Reuse that Site, database, audience, owner identity and private transport configuration. Do not register a replacement Site or database, change unrelated Cloudflare resources, or modify the separate private Test Merchant bridge or anonymous lab. Source, a published URL and passing local tests do not establish live ChatGPT or phone acceptance.

## Deployment gate and preservation

**The additional hosting cost has not been verified. HP deployment remains blocked until the current account/provider confirms no new payment method, subscription, paid binding or unexpected charge is required.** Existing Site access or an eligible ChatGPT subscription does not establish zero additional hosting cost. Cloudflare free-tier quotas do not establish Sites pricing. Do not activate payment, credits or a new subscription to resolve this gate.

After that gate is satisfied, the release operator must inspect the existing Site's current private audience and source/version metadata, retain its exact project identity, and deploy the reviewed commit through its supported source/build workflow. Preserve these server-only bindings; never copy their values into GitHub, chat, logs or a download:

| Binding | Required preservation |
| --- | --- |
| `DB` | Existing owner-connection database and stored records/receipts |
| `OWNER_SITE_USER_ID` | Existing exact platform-verified, Site-scoped owner pin |
| `SITES_SERVICE_ACCESS_BEARER` | Existing private Site transport credential |

Keep the existing `.openai/hosting.json` project ID and configuration (`d1: "DB"`, `r2: null`, `capabilities: ["mcp"]`). Applied `0000_owner_bridge` and `0001_bootstrap_downloads` migrations and metadata remain unchanged. Append only `0002_live_hp`, which adds isolated HP tables; do not replace the database or run schema creation from request handlers.

Run `node --test backend/owner-bridge/*.test.mjs`, then `node backend/owner-bridge/build-worker.mjs` from the repository. The generated artifact must contain `server/index.js`, `.openai/hosting.json` from the existing Site checkout, and the ordered migration SQL/journal/snapshots under `.openai/drizzle/`. A sibling `drizzle/` artifact directory alone is not discovered by hosted migration deployment. Save the exact reviewed build and record its returned version ID, deployed commit, migration result and unchanged private audience. These instructions are not a deployment claim.

## Authentication and narrow authority

`worker.mjs` requires owner-private Sites dispatch to authenticate and replace `oai-authenticated-user-id`. Do not expose a raw Worker that trusts caller-supplied identity headers. Data operations require the exact configured owner pin. Nonsecret MCP discovery and schema listing grant no record access.

Android needs both the private transport credential and a separate revocable device credential. The installation-bound encrypted bootstrap supplies transport access only; it does not enroll a profile or grant HP permissions. Device pairing requires explicit consent for the actual existing campaign/profile identity. HP device scopes are only `hp:read`, `hp:publish` and `hp:decide`; legacy fictional credentials cannot access live HP records. Re-pairing replaces the earlier writer for that profile without transferring pending approvals. Secure disconnect requests server-side device revocation.

ChatGPT receives only `hp:read`, `hp:propose` and `hp:receipt`. It cannot approve, reject, publish records, enroll or revoke devices. Unknown current/maximum HP stays unknown. Proposals require a matching revision/current value and sufficiently recent device synchronization; reading the server or waiting for a countdown does not refresh that evidence. The server computes the exact signed adjustment and rejects values outside zero through the known maximum without clamping. No ability, inventory, currency, temporary/maximum HP or weapon-engine change is authorized by this path.

## ChatGPT tools and connection

The JSON-RPC `POST /mcp` endpoint implements `server/discover`, `initialize`, `tools/list` and `tools/call`; preferred protocol version is `2026-07-28`. `mcp.mjs` contains the strict schemas. Additional fields and caller-supplied authority are rejected. See [HP_MCP_TOOLS.md](HP_MCP_TOOLS.md) for numeric bounds, provenance, fingerprints, repair metadata and receipt semantics.

| Primary HP tool | Required arguments |
| --- | --- |
| `get_owner_connection` | None; approved binding/publication metadata only |
| `get_character_hp` | `campaignId`, `profileId` |
| `propose_hp_adjustment` | `campaignId`, `profileId`, `commandId`, `expectedRevision`, `expectedCurrentHp`, signed `adjustment`, `source` |
| `get_hp_pending_updates` | `campaignId` |
| `get_hp_command_receipt` | `campaignId`, `commandId` |

Five legacy diagnostic tools remain available only for their original fixed fictional records:

| Legacy tool | Required arguments |
| --- | --- |
| `get_character` | `campaignId`, `profileId` |
| `get_character_abilities` | `campaignId`, `profileId` |
| `propose_record_update` | `campaignId`, `commandId`, `recordId`, `expectedRevision`, `fields: {score}`, `source` |
| `get_pending_updates` | `campaignId` |
| `get_command_receipt` | `campaignId`, `commandId` |

Those tools retain campaign `synthetic-owner-acceptance`, profile `owner-sync-fixture` and Intelligence record `owner-sync-fixture-int`; they are not live character synchronization. Their enrollment, proposals and receipts remain separate from HP permissions and data.

After an approved deployment, inspect `get_site(include_mcp_connection=true)` for the **existing Site** and its returned MCP/plugin metadata. Refresh the existing plugin connection rather than inventing an endpoint or registering another service. Inspect the actual ChatGPT tool registry and perform an authenticated invocation of the new HP tools. If tools remain absent, use the exact returned plugin ID with the supported plugin connection flow; a fresh conversation may be needed after refresh. Report the exact missing connection/authorization action if blocked. A local HTTP request, backend test or successful legacy fictional invocation does not prove the new HP tools are callable from ChatGPT.

## Phone-only owner actions and acceptance

The owner is asked for only the final installation, scoped connection consent and requested HP approval:

1. Install the verified owner-signed update over `com.yazsras.astravar`, with a compatible signature and higher version code. Preserve the existing backup and app data; do not uninstall or clear storage to bypass an update error.
2. In Config → Owner Connection, use the verified existing HTTPS origin. Reuse valid private transport access or complete its encrypted authorization download/import on this phone. The file is encrypted to this installation's AndroidKeyStore key, expires promptly and contains no plaintext credential. Then review and approve pairing for the selected existing profile and HP scopes through the same private owner account. Transport authorization and profile consent are separate.
3. Once ChatGPT has genuinely discovered the new tools, let it read the approved binding and published HP record. For the owner's requested adjustment, Astravar must display the existing/proposed HP, revision and source. The owner taps **Approve** once. The app commits one local revision and delivers its durable outcome; ChatGPT independently reads the immutable receipt and saved HP record and confirms the same value and revision. A proposal alone never commits a change.

Automated protocol, rejection, replay, conflict, authorization, offline/retry, restart, update-preservation and backup/restore checks belong to engineering verification, not a repeated owner checklist. See [OWNER_HP_NATIVE_TESTS.md](../../docs/OWNER_HP_NATIVE_TESTS.md) and [OWNER_HP_CONNECTION_CONTRACT.md](../../docs/OWNER_HP_CONNECTION_CONTRACT.md). Test logs must contain only fictional fixtures and safe identifiers, never credentials or real campaign exports.

## Readiness boundary

Local SQLite/HTTP/MCP tests and the native emulator workflow provide distinct engineering evidence. Emulator update tests use a temporary debug signer; they do not prove an owner-signed update on the Galaxy S26. Local MCP calls do not prove ordinary ChatGPT discovery, and emulator approval does not prove approval on the installed phone.

**Live HP acceptance remains unverified, and deployment is paused at the unresolved hosting cost gate.** Record actual hosted migration, new-tool discovery/invocation, installed-app preservation, scoped pairing and the owner's approval/receipt/independent-readback results separately when observed. Do not describe a missing receipt, failed request or repair marker as a confirmed change or rollback.
