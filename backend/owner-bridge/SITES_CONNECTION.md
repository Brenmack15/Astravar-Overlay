# Astravar Owner Connection deployment and phone acceptance

This is **separate service source**, distinct from the existing private Test Merchant bridge and anonymous Cloudflare lab. The source package does not itself register or deploy a Site. A locally passing MCP request is not evidence that the ChatGPT app has connected to this service. The original five fictional tools are preserved; additional owner-approved HP tools are defined in [HP_MCP_TOOLS.md](HP_MCP_TOOLS.md). Deployment and acceptance status must come from the current release evidence.

## Security boundary

`worker.mjs` is specifically for an owner-private Sites deployment. Sites must authenticate access and replace the `oai-authenticated-user-id` header before invoking the Worker. Never deploy this adapter as an unrestricted raw Worker that trusts caller-supplied identity headers. The server pins the exact verified, Site-scoped owner ID in `OWNER_SITE_USER_ID`; an arbitrary signed-in viewer cannot act as owner or connector.

Android requires two independent credentials: the private Sites transport bearer, and a narrower revocable device credential. A device token by itself cannot cross the private Sites gateway. The encrypted bootstrap download supplies only the transport credential to the requesting installation. Device pairing and permission approval remain separate. Disconnect requests server-side device revocation before clearing local credentials.

Runtime configuration is server-only:

| Name | Purpose |
| --- | --- |
| `DB` | New isolated D1 database; never the existing bridge database |
| `OWNER_SITE_USER_ID` | Exact verified owner identity for this new Site |
| `SITES_SERVICE_ACCESS_BEARER` | Platform-provided private service access credential; never source, logs, chat, or a plaintext download |

The original fictional service and its five tool schemas accept only campaign `synthetic-owner-acceptance`, profile `owner-sync-fixture`, and Intelligence record `owner-sync-fixture-int`. The separately authorized HP path requires an explicit campaign/profile binding and its own native device scopes; fictional credentials cannot access it. Other record types and combat-engine writes remain outside this connection. Connector tools can propose changes; only an authenticated enrolled Android device can submit approval or rejection. Approval in ChatGPT never substitutes for the native player control.

## Source and deployment preparation

1. Obtain the owner's explicit approval for a **new production deployment** and verify the chosen service's current free-tier limits. Do not change unrelated Cloudflare resources, the old private bridge, its audience/database, or the anonymous lab.
2. Register one new owner-private Site through the supported Sites workflow. Copy the returned ID unchanged into `.openai/hosting.json`, preserving the fields from `hosting.example.json`. Do not reuse an ID from another project. Copy the returned expected origin exactly; an expected origin is not proof of a live deployment.
3. Run `node --test backend/owner-bridge/*.test.mjs` from the Android repository. Build the Worker with `node backend/owner-bridge/build-worker.mjs` into a clean output directory. The output contains `server/index.js` and the journal-listed `.openai/drizzle/*.sql` migrations with their `.openai/drizzle/meta` journal and snapshots. Once the Site hosting configuration exists, the output also contains `.openai/hosting.json`. In a `dist` artifact these paths must be `dist/server/index.js`, `dist/.openai/hosting.json`, and `dist/.openai/drizzle/...`; `dist/drizzle` alone is not discovered by hosted migration deployment. The supported Sites packager also copies a Site checkout's root `drizzle` directory into this same `dist/.openai/drizzle` artifact location. Keep the source migrations and hosting configuration in the Site checkout and use its supported build/source helper. Do not run schema creation from request handlers. Applied `0000` SQL and metadata remain immutable; `0001_bootstrap_downloads` adds only the isolated ephemeral ciphertext-ticket table. The build validates the ordered journal/snapshot chain and combined schema before packaging its allowlisted files.
4. Push the exact source through the new Site's source helper, save that exact commit/build artifact, then deploy the saved version privately. Before owner configuration, only nonsecret MCP discovery/tool schemas and the requesting visitor's own verified identity setup route work. All record operations, pairing and owner administration fail closed. Record the returned saved-version ID, deployed commit, migration result, URL and plugin ID. No package should claim these fields before the platform returns them.
5. Open `/owner/setup/identity` through the new private Site while signed in as its owner. This route returns only the caller's platform-verified Site-scoped ID and cannot claim ownership or read/write records. Pin that exact value as server-side `OWNER_SITE_USER_ID`. Provision the platform-returned service access bearer for this **same Site** as server-side secret `SITES_SERVICE_ACCESS_BEARER`, using supported runtime-secret administration without putting it in chat or source. Verify configuration and repeat authenticated calls. Do not take an owner ID from an arbitrary Android request, email address, display label, or guess.
6. Call `get_site(include_mcp_connection=true)` for the **new returned project ID**. The returned `mcp_url`, OAuth resource and `plugin_id` define the real connector. Merely creating a backend URL does not install or connect it.

The existing bridge's verified manifest shape is `{"d1":"DB","r2":null,"capabilities":["mcp"]}` plus its own project ID. This source deliberately contains no invented new project ID, endpoint, plugin identity, or credential.

## ChatGPT tool contract

The stateless JSON-RPC `POST /mcp` implements `server/discover`, `initialize`, `tools/list` and `tools/call`. The preferred MCP version is `2026-07-28`. It returns MCP text and structured content; typed operation errors set `isError: true` without leaking credentials or database exception strings.

| Tool | Required arguments | Result |
| --- | --- | --- |
| `get_character` | `campaignId`, `profileId` | Latest device-published fictional profile, abilities, revisions and provenance |
| `get_character_abilities` | `campaignId`, `profileId` | Only sourced ability records and revisions |
| `propose_record_update` | `campaignId`, canonical `commandId` UUID, `recordId`, `expectedRevision`, `fields:{score}`, `source` | Staged difference; the saved record stays unchanged |
| `get_pending_updates` | `campaignId` | Pending current/proposed differences |
| `get_command_receipt` | `campaignId`, `commandId` | Independent immutable result, or `RECEIPT_NOT_READY` |
| `get_owner_connection` | None | Only approved live HP binding/publication metadata |
| `get_character_hp` | `campaignId`, `profileId` | One approved profile's device-published HP record, revision and freshness |
| `propose_hp_adjustment` | `campaignId`, `profileId`, `commandId`, `expectedRevision`, `expectedCurrentHp`, signed `adjustment`, `source` | Server-computed HP difference staged for native approval |
| `get_hp_pending_updates` | `campaignId` | Pending HP-only differences within active approved campaign bindings |
| `get_hp_command_receipt` | `campaignId`, `commandId` | Independent immutable HP outcome, or an unverified error |

`mcp.mjs` is the exact schema source. Additional fields, invalid or unapproved targets, noninteger values, stale revisions, sources above 1,900 characters and caller-supplied authority are rejected. The original fictional tools cannot target live records. Sources are never truncated. There is no MCP approval, character publish, enrollment or credential tool. Nonsecret discovery and schema listing can run before owner configuration for platform provisioning; `tools/call` always requires the configured owner and verified identity. The HP tool guide defines its separate native consent, bounds and exact proposal fingerprint.

When Sites reports the new plugin available, inspect the actual current tool registry and invoke a read operation. If the plugin is missing, use `plugin_management.suggest_plugins` with the exact returned plugin ID. If installation reports **Already installed** but tools remain absent, start a new ChatGPT conversation with the plugin connected. The phone path is Plugins → Personal → Created by you → Install or Connect. No developer computer, local MCP configuration, or API key is required for this Sites connector path.

## Phone-only pairing and fictional acceptance

After an owner-signed APK has been verified for the installed package/signature/version:

1. Preserve an existing campaign backup. Install the update over `com.yazsras.astravar`; do not uninstall or clear data to resolve a package, signature or version error.
2. In Astravar → Config → Owner Connection, enter the verified new HTTPS origin. Start the private transport authorization and open its link on this phone. Sign in through the owner-private Site. Confirm the request code matches Astravar, download the encrypted authorization and import it into Astravar before its 10-minute expiration. A strict same-origin form POST prepares the encrypted envelope and redirects to an opaque, owner-authenticated GET attachment valid for two minutes. Browser/DownloadManager GET, HEAD and Range retries return the same full encrypted bytes until expiry; no credential or ciphertext appears in the URL. Tickets are stored only as hashes with owner-bound ciphertext, capped at eight active entries, and expired entries are deleted. The file is encrypted for that installation's AndroidKeyStore key and cannot enroll another phone. A successful HTTP response does not prove the browser saved the file.
3. Start device pairing in Astravar. Open the new service's `/owner` page using the same owner account, compare the pairing code with Astravar, and approve the device. Complete pairing in Astravar and inspect the account label, device and read/publish/decision scopes.
4. Explicitly expose the fictional fixture from the native screen. This must create/publish an actual local Room record; it must not select `local-player`, Zerg, or the weapon engine.
5. With the new plugin genuinely connected, ask ChatGPT to read the fixture. Save the returned value, record ID and revision. Ask it to propose one Intelligence score change with that revision and a new command UUID. Before native approval, independently read the fixture and verify that its saved value has not changed and no final receipt exists.
6. Sync Astravar. Confirm the native screen shows the same command, source, old value and proposed value. Tap **Approve**. The app must commit one local revision and its durable outcome, then retry authenticated delivery if interrupted. ChatGPT must read both the immutable command receipt and the resulting record independently and confirm the same score, provenance and revision.
7. Restart Astravar and repeat the readback. Complete actual Android backup/restore acceptance using the app's validated backup workflow. Server SQLite persistence/backup tests do not prove Android Room backup/restore.
8. Repeat rejection, stale revision, command replay, network disconnect/retry, invalid/expired authorization, owner revocation and simultaneous local/remote edits. For a lost phone, use `/owner` to list and revoke the device; revocation must take effect on the next authenticated server call. Record observed native logs and outcomes without credentials or campaign data.

Only after that complete acceptance and separate live-data authorization should scopes, schemas and native adapters be extended to Zerg's real records. This source does not reconcile the Level 9 character versus Level 5 weapon-engine discrepancy, infer current HP, heal, refresh resources, alter homebrew, or construct research proposals.

## Evidence limits

Local tests exercise actual SQLite durability, typed HTTP/MCP handlers, authenticated roles, proposal-only writes, receipts, revisions, encryption and the generated Worker. They do not establish platform publication, hosted D1 migration acceptance, plugin discovery/authorization in ChatGPT, native HTTPS on the Galaxy S26, user approval on the installed app, installation/update preservation, or the complete requested Android backup/restore loop. Those gates must be reported as pending until independently observed. Device credentials expire after 30 days and require explicit re-pairing; automatic renewal is not implemented.

No hosting plan or price guarantee is encoded in this source. The production deployment approval must identify the chosen provider and currently verified limits, confirm the applicable account remains within its free plan, and exclude paid activation. Cloudflare's published Worker/D1 free-tier limits do not by themselves prove the pricing or quota policy of a Sites deployment.
