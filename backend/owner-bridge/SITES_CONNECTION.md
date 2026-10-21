# Astravar Owner Connection: private campaign V2 upgrade

Reuse the existing owner-private Astravar Owner Connection Site, its database, audience, owner identity, bootstrap transport and connector identity. The separate Test Merchant bridge, disposable anonymous lab and Yazsras Studios website are outside this upgrade.

The previous HP deployment hosting-cost gate was resolved during the verified code35 deployment: the existing included Site was deployed as version6 without a new payment method, paid plan or additional resource. That historical gate must not be reintroduced as an unexplained new permission request. This V2 upgrade uses the same existing approved resources and does not authorize paid services. Inspect current Site/source/access before deployment and retain the exact project ID. Source changes and local tests are not a claim of a hosted V2 deployment.

## Preserved deployment bindings

Preserve DB, OWNER_SITE_USER_ID and SITES_SERVICE_ACCESS_BEARER server-side. Never copy their values into source control, logs, chat or release files. Preserve the existing .openai/hosting.json with d1 DB, r2 null and mcp capability. Applied migrations0000 through0002 remain unchanged; append0003_campaign_sync only. Never recreate the database or create schema inside request handlers.

Run `node --test backend/owner-bridge/*.test.mjs`, then the deterministic `build-worker.mjs`. The artifact contains server/index.js and the ordered migrations, journal and snapshots under .openai/drizzle. Copy the exact existing Site manifest into the build. Push the reviewed source, save its matching archive and deploy privately. Record the returned source commit, version, migration/deployment result and unchanged audience independently.

## Account, device and scoped authorization

Sites authenticates MCP identity and replaces oai-authenticated-user-id; the service pins its existing exact owner. Direct unauthenticated hosts must not trust caller-supplied identity headers. Nonsecret MCP schema discovery exposes no record access.

The installation-bound encrypted bootstrap grants private transport only. A new explicit campaign pairing requests selected read/write categories, displays them for the signed-in owner, then issues a separate campaign grant. Old fictional or HP credentials do not imply campaign authority. V2 campaign credentials receive campaign:sync transport scope, never implicit legacy hp:publish or hp:decide. Owner-approved write categories may be enabled individually under Trusted DM Auto-Sync in the native app. Default is manual approval; native and server enforce the grant and category policy. Permission narrowing and revocation take effect at the backend and invalidate older pending grant revisions.

See [CAMPAIGN_V2_CONTRACT.md](CAMPAIGN_V2_CONTRACT.md) for exact DTOs, typed commands, field projection, source-linked chunked references, mirror removals, fingerprint-bound freshness, durable queue processing and independent Android receipt/readback semantics.

## Existing connector identity

Republishing this same mcp-enabled Site refreshes its existing private App/plugin tool list. Do not create a duplicate plugin. Site presentation should describe owner-private player-known campaign synchronization and Trusted DM Auto-Sync, with legacy fictional controls clearly marked diagnostics. Inspect the actual tool catalog after deployment and call get_owner_connection through the actual authenticated integration. The returned campaignSync object distinguishes V2 grants from legacy HP bindings. A fresh conversation may be required for a previously cached catalog; report observed propagation, not assumptions.

The original HP read tool supports an active V2 profile when hp reads are delegated; legacy HP proposal/approval tools remain isolated. New gameplay writes use submit_campaign_transaction, then get_campaign_command_receipt and a separate current record read. A queued command or successful Worker response is not an Android save.

## Phone-only final actions

1. Install the verified original-owner-signed higher-version APK over com.yazsras.astravar. Never uninstall or clear data to bypass update conflicts.
2. Open Config → Owner Connection. Reuse valid encrypted private transport or complete its same-phone authorization/import. Pair the selected existing campaign and review selected read/write categories on the private owner page.
3. Enable Trusted DM Auto-Sync only for the categories you delegate. Ordinary resolved gameplay updates in those categories then use the validated transaction path without repeated device approval. Other selected write categories remain manual. Complete any required ChatGPT platform consent separately.

Engineering owns automated routine QA. Keep physical Galaxy acceptance, owner-signed update preservation, normal authenticated ChatGPT discovery/invocation, native HTTPS emulator acceptance and local protocol tests as separate evidence. Do not describe pending/failed delivery, an absent receipt or an unresolved conflict as a completed save.
