# Operating Astravar Cloud

## Environments

The private Site terminates authenticated owner and connector traffic. Use one Worker, a relational D1 binding named `DB`, and a private object bucket named `ASTRAVAR_MEDIA`. The package includes the existing V1/V2 protocols, current cloud operations and the ordered SQL migration chain. Existing device-authoritative continuities keep their previous writer until an explicit, separately reviewed epoch change.

The loopback fixture supplies a simulated trusted identity only on its local request dispatcher. A raw public Workers endpoint would allow a caller to supply an identity header. A hosting operator must use the existing private Sites ingress or a separately verified authentication adapter that strips caller-provided identity headers and sets identity after authentication. Access through any alternate host or public bucket must be disabled.

| Setting | Operational meaning |
| --- | --- |
| `OWNER_SITE_USER_ID` | Exact owner identity provided by the trusted Site. Configure it privately. |
| `DB` | Dedicated D1 database for the environment. Use a primary-first session for command evidence and readback. |
| `ASTRAVAR_MEDIA` | Private bucket for validated originals, thumbnails and staged media. Use a different bucket for every environment. |
| `ASTRAVAR_CLOUD_ENABLED=1` | Enables authenticated V3 routing. It does not enroll an existing campaign. |
| `ASTRAVAR_CLOUD_ENV=isolated` | Enables explicit owner creation of empty `qa-` continuities in engineering environments. Omit it in a production configuration. |
| `ASTRAVAR_CLOUD_RATE_CAPACITY` | Maximum accumulated traffic credits per continuity and authenticated principal. Supply an integer from 16 through 4,096. The production default is 120. Isolated development defaults to 4,096. |
| `ASTRAVAR_CLOUD_RATE_REFILL_MS` | Milliseconds per credit, from 1 through 60,000. Production defaults to 500 and isolated development to 25. An invalid setting refuses gameplay traffic until an administrator repairs the configuration. |
| Legacy bootstrap and optional push credentials | Keep existing secrets in the provider secret store. Push is an optional hint for V3 clients. |

## Reproducible local verification

Use Node 24, Python 3.11 or later, SQLite with JSON and foreign key support, JDK 17 and the repository's Gradle wrapper. For a loopback provider rehearsal, install the tested Miniflare package outside the source checkout. The provider smoke prints committed revision, receipt count and device count and destroys its temporary fixture at exit.

```sh
node --test backend/owner-bridge/*.test.mjs
python3 tools/ascension_trace_check.py
bash scripts/ci-python-tests.sh
node backend/owner-bridge/build-worker.mjs /tmp/astravar-verified-bundle
bash scripts/ci-ascension-provider.sh
./gradlew :rules:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

A successful bundle validates the exact migration order against the consolidated SQL and emits the Worker plus the migration journal. The existing hosting example intentionally lacks a bucket and project ID. Populate a private hosting manifest only in an authorized deployment workspace. Never point the development checks at that manifest's production bindings.

## Hosting preparation

1. Select the private owner account, billing plan, region/residency policy, maximum monthly budget, storage quota and restore objectives. Obtain explicit approval before creating infrastructure with a recurring cost.
2. Rehearse the exact release commit with separate SQL and object stores. Verify unauthenticated access is refused, an empty QA continuity starts at revision zero, and each authorized client sees only its granted actors and categories.
3. Before applying database migrations, create an independently readable, encrypted SQL and media backup with a verified manifest. Record the active epoch, committed revision, pending proposals, package digest and object inventory. Keep the recovery key in separate custody.
4. Package the matching Worker, reviewed hosting manifest and appended migrations. Apply the migrations and deploy privately in the selected environment. Confirm the original private audience, HTTP ingress, MCP catalog and bucket access restrictions after promotion. A healthy endpoint alone provides no gameplay proof.
5. Repeat the synthetic command, immutable receipt, independent readback, media association, denied-access and recovery checks. Enrollment, grant issuance and migration of a production continuity are separate, owner-authorized operations.

D1 operation volume is driven by **rows** scanned or written. R2 cost depends on retained bytes and operation classes. Size SQL, private objects, backup history and request traffic separately. For a storage estimate, 500 portraits averaging 1 MiB plus 500 thumbnails averaging 32 KiB use approximately 516 MiB before revisions and backups. This is an illustrative workload. The actual campaign volume, billing plan and monthly expenditure have not been measured or approved. Provider comparisons, pricing and retention references are in [the implementation ledger](PLAN.md).

## Authorization and ordinary gameplay

Open the continuity's owner review screen from Owner Connection. The permissions page lists the trusted DM, enrolled devices and grant history. Grant the required read and write categories, selected actors, expiration and categories eligible for automatic commits. A new grant does not confer additional dice delegation or permission to approve a restricted operation. Write operations require all affected scopes across the proposed changes. Owners can narrow or revoke grants and inspect their audit entries.

At the beginning of a DM session, discover authorized operations using `get_cloud_capabilities`. Retrieve the latest scene using `get_cloud_checkpoint_context`, then retrieve older scoped changes, related records or links only as needed. For a resolved event, choose a stable command ID, retain the actual player declaration and dice evidence, submit its typed schema and retrieve the immutable receipt and scoped records independently. A network timeout is an unknown result. Read the same command ID first, then retry that exact payload if needed. A fresh ID describes a different event.

The owner inbox shows current, proposed and affected records, costs and evidence. A media decision requires display of the actual staged image for the selected target. The approval badge counts requests eligible to commit at the current grant and revision. The owner can still inspect an obsolete request and resolve its terminal disposition. Exact refusal and prior history remain retrievable.

## Outage and recovery

| Observation | Action |
| --- | --- |
| Device is offline and the cache remains eligible | Read the last verified snapshot with its stale indicator. Resume mutations only after an authenticated primary read. |
| Device received a push or submission timed out | Inspect the authoritative receipt and refresh the cache. Background delivery carries no commit evidence. |
| Epoch, grant or revision changed during paging | Discard incomplete pages and retrieve a fresh scoped snapshot. Authentication expiry and connectivity loss have distinct meanings. |
| Owner-review baseline changed | Inspect the exact proposal and conflicting records. Link a newly reviewed replacement to the original digest if its intended operation still applies. |
| SQL is unavailable | Ordinary gameplay remains unverified. Leave the existing writer and epoch intact while recovering the service. |
| A media upload or object cleanup is interrupted | Keep the previous committed association, inspect the upload/digest and retry the original operation. The durable cleanup intent is retried on receipt inspection. |
| A checkpoint is stale | Retrieve the confirmed checkpoint and changes since its revision. Reading an old checkpoint does not roll gameplay backward. |
| A backup predates later commits | Restore to a separate staged environment. Reconcile later committed events before reviewing a fresh authority transition. |

The local recovery toolkit has no scheduled provider job. For isolated environments, export before upgrades and after each accepted rehearsal, keep the current and previous independently verified generations, and run a cold restore before discarding the earlier copy. A production deployment must choose and implement a backup schedule, retention, key custody, monitoring, owner alerts and a measured recovery-time objective. Provider SQL point-in-time recovery needs matching asset versions and application-level verification. [Recovery formats and commands](RECOVERY.md) · [Media, deletion and recovery limits](STORAGE.md)

## Native release

The signed production package uses `com.yazsras.astravar`. Upgrade an existing installation in place with Android's package installer after verifying the published APK hash, version and owner certificate against the release evidence. Keep the existing private backup and original owner-signed APK during acceptance. The debug application ID and disposable CI installation signatures are reserved for synthetic verification. The signing operator checks the frozen code 58 original and candidate evidence before producing an owner artifact. [Private release procedure](../OWNER_RELEASE_PROCEDURE.md)

## Gameplay request limits

Authenticated Android, owner web and DM requests use the same admission policy. Each continuity and authenticated principal has one burst budget shared by all its gameplay routes. Scope validation precedes allocation. Ordinary state, settings, changes and receipt reads cost one credit each. Capability, approval, relationship, sheet, spell, action and other multi-record projections cost three. Game commands, owner decisions, grant changes and private image reads cost four. Uploading a candidate costs sixteen. The owner-wide engineering enrollment budget applies across proposed QA continuity IDs. The owner and each authorized device have independent budgets.

The Worker admits requests using one conditional D1 write, bounded credit refill and the trusted service clock. A denied request leaves its debt unchanged. An admitted request consumes traffic credits even when subsequent gameplay validation refuses it. Gameplay revisions and balances are independent of this accounting. The HTTP command path checks before reading the command or private bytes. The authenticated MCP path decodes its 64 KiB bounded RPC envelope to identify the tool and continuity, then checks before full argument validation, media decoding, gameplay planning and commit. Anonymous public tool discovery and legacy transport limits retain their own handling. The trusted gateway remains responsible for unauthenticated ingress and overall traffic controls.

A limited HTTP request returns 429, a whole-second `Retry-After` header and a JSON error with `code=CLOUD_RATE_LIMITED`, `retryAfterMs`, `retryAfterSeconds` and `submission=NOT_EXECUTED`. MCP uses the same status and header with JSON-RPC error code -32029 and matching error data. Wait for the supplied duration. On a paginated read, repeat the same cursor, epoch, revision and grant revision. A later version conflict requires rebootstrap. On a pending mutation, retain the exact journaled command ID and approved payload. After cooldown retrieve its receipt or retry the identical envelope and verify authoritative readback. The limit can recur during recovery. An earlier accepted attempt may already have committed. [HTTP 429](https://www.rfc-editor.org/rfc/rfc6585.html#section-4) · [Retry-After header](https://www.rfc-editor.org/rfc/rfc9110.html#section-10.2.3)

D1 stores the subject, continuity and the next-credit timestamp. The table contains no token, IP address, request body, player record or action history. Traffic debt survives Worker restart. A cold campaign restore begins with fresh traffic budgets. `cloud-rate.test.mjs` checks exact millisecond boundaries, maximum idle refill, concurrent reservations, authorization and invalid configuration. `scripts/verify-ascension-rate.cjs` uses the bundled Worker with local D1 and R2 for transport 429s, exact command recovery, blocked media staging and a 2,462-record first read under production limit settings.
