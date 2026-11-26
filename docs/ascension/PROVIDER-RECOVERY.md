# Local D1 and R2 backup and recovery

The development adapter exercises the bundled Astravar Worker against persistent local D1 and private R2 bindings. It performs a logical, single-continuity backup and a cold restore to a newly initialized provider generation. It uses the same versioned ZIP, source mapping, media checks and independent SQLite validator as the migration CLI. The provider database and object bucket have separate durability boundaries. A backup verifies both before publishing the package. A replacement generation stays outside gameplay routing until its contents and authority change have been verified.

## Run the rehearsal

Use a private development checkout, Linux with `flock`, Node 24, Python 3.11+ and the local Miniflare package bundled with Wrangler 4.168.0. The tested runtime was Miniflare 5.20261123.1-beta with workerd 1.20261123.1. The runtime configuration uses the Miniflare 5 module and persistence API. Development tools can be installed outside this repository. The following command creates temporary isolated provider generations and removes its synthetic data after completion.

```sh
node scripts/verify-ascension-provider-recovery.cjs /absolute/path/to/node_modules/miniflare
```

The fixture uses the application's authenticated MCP and owner-review paths to create an actor, attach a supplied PNG and leave one pending review. The test independently reopens the D1 database and R2 bucket in new workerd instances. It verifies the canonical ZIP, stable receipts and image bytes, stage rollback, prior-epoch refusal and safe recovery after interruption between the two authority transitions. It also tests an incomplete object snapshot, grant revision drift during export, contention on the maintenance lock, stale recovery after new gameplay and protection of sibling continuities.

## Commands

| Command | Result |
| --- | --- |
| `node tools/ascension_provider.cjs init DIR --owner ID --continuity qa-ID --miniflare MODULE` | Creates a new private local database and bucket with the checked-in migration chain. An existing directory is refused. |
| `node tools/ascension_provider.cjs status DIR --miniflare MODULE` | Opens each relevant binding and reports the routed and staged authority epochs. During an interrupted cutover the administrator can inspect the actual fences. |
| `node tools/ascension_provider.cjs backup DIR PACKAGE --miniflare MODULE` | Reads the routed D1 generation and referenced R2 objects, runs canonical package validation and publishes a new private file. An existing package is preserved. |
| `node tools/ascension_provider.cjs stage DIR PACKAGE --miniflare MODULE` | Reports the digest for the reviewed stage. Supply the same command with `--approve DIGEST` to write and independently reopen a new D1/R2 generation. |
| `node tools/ascension_provider.cjs cutover DIR --miniflare MODULE` | Returns an approval digest bound to the database, package, stage, pending requests and authority state. Repeat with `--approve DIGEST` to fence the former writer, promote the reviewed stage and atomically switch the local routing file. |
| `node tools/ascension_provider.cjs rollback-stage DIR` | Returns a review token to retire an unpublished stage. Repeat with `--approve DIGEST` to preserve the current authoritative continuity and remove the stage from routing. |

When a verified prior cloud package contains pending proposals, add `--carry-pending` to both cutover calls to preserve those exact requests for later review. They retain their original epoch and grant provenance. An empty isolated destination cannot fence a prior writer outside this managed root. Stop and fence that development writer separately before activating the continuity there. A restored owner must separately authorize the necessary client grants. Authority recovery never grants standing access from exported audit records. Reissue the identical approved cutover after a crash. A durable intent blocks opening the gameplay worker until the decision finishes.

A local administrator who already holds Miniflare D1/R2 bindings can call `backupBindings({db,bucket}, ownerId, continuityId, outputPath, optionalSourceEvidencePackage)` from `tools/ascension_provider.cjs`. This path only reads the named QA continuity and referenced objects. Provide its original recovery package if earlier native-source mapping evidence must travel with the export. Managed generations preserve that sidecar when they are restored. `withGameplay(root, callback, miniflareModule)` lets integration tests use the actual routed Worker. Its ephemeral local capability protects incoming loopback requests while the generation is open.

## Consistency, limits and operations

D1 captures the canonical row families, current authority, grants fingerprint and schema columns as one SQL batch. The adapter reads full original files and thumbnails, verifies their hashes and lengths, checks their versions again and repeats the entire database read. A grant change, pending proposal or modified record can invalidate a snapshot even when the gameplay revision stays constant. The canonical validator checks source manifests, row hashes, cross-record references, actual PNG decode and receipt evidence. Keep the resulting file under owner-private permissions. The migration CLI provides optional authenticated encryption with separately managed recovery keys. [D1 Worker API](https://developers.cloudflare.com/d1/worker-api/d1-database/) · [Miniflare D1 bindings](https://developers.cloudflare.com/workers/testing/miniflare/storage/d1/) · [Miniflare R2 bindings](https://developers.cloudflare.com/workers/testing/miniflare/storage/r2/)

The restore loads the actual D1 rows in one transaction, writes private objects into a previously unused generation, then starts a fresh runtime and verifies both. The provider adapter caps each table at 25,000 rows and 32 MiB, canonical rows at 128 MiB total, and the bound restore at 500 SQL statements. A limit failure leaves the previous generation routed. Existing media size limits, package size limits and schema version checks also apply. The local lock coordinates writers opened through this adapter. Quiesce external local writers when using already-open bindings, and obtain a fresh capture following a drift refusal.

Single-continuity exports can originate from shared development bindings. Switching an entire provider generation requires a dedicated root, accounted-for private objects and independently preserved legacy state. An unrelated continuity, unrelated stored assets, unclassified application tables or populated legacy gameplay tables block that switch. The tested D1 runtime recreates its internal `_cf_METADATA` table. The logical campaign package excludes SQLite bookkeeping and provider-internal metadata. Previously paired device credentials and standing grants are excluded from the new generation. The former generation and its local source sidecar remain private administrative recovery data. An owner decides when those retained copies may be removed. A staged rollback is available before cutover. Later gameplay requires a new consistency review and a forward recovery using current receipts and records.

This rehearsal does not measure hosted recovery time, provider-level point-in-time restoration, geographic failure handling or production retention. Set the production backup schedule, object version retention, recovery point objective and encrypted copy location when separately approving hosting. The application does not enroll a live continuity or activate provider hosting from this tool.
