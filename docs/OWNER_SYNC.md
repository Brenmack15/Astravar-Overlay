# Owner-authenticated synchronization readiness

This candidate implements an Android protocol, encrypted device-credential vault,
durable offline command queue and verified remote receipt/history handling. Real
campaign activation is deliberately closed. There is no deployed owner sync
service, enrollment flow, production connector or automatic campaign upload.
Notion remains the established campaign ledger until a separately approved
cutover. The existing private Astravar DM Bridge and its database are unchanged.

## Implemented boundaries

`OwnerSync.kt` is independent of the public disposable sandbox. It rejects that
sandbox's origin and insecure, credential-bearing or loopback origins. No service
address or credential is bundled. HTTPS uses the platform TLS verifier, bounded
request/response sizes, timeouts, no redirects and a campaign-scoped URL. No cookie
or token is placed in a URL. Diagnostics expose fixed issue codes rather than
remote messages that could contain private values.

`OwnerSyncCredentialVault` encrypts enrolled credentials with an AES-GCM key held
by AndroidKeyStore. The encrypted blob is bound to its exact origin and campaign
using authenticated associated data. Credential access checks origin, campaign
and expiry; failed decryption or a restored blob without its original device key
fails closed. Neither the credential nor its plaintext is kept in Room, backup
exports, log messages or data-class diagnostics. The existing app disables Android
backup/device transfer. Local disconnect removes the device's credential but is
**not** evidence of server-side revocation. A real service must revoke that
credential and enforce revocation on every request.

`OwnerSyncDb` stores only the synchronization outbox, immutable received event
staging and cursor. It is not a second inventory or character database, and it
does not write to either existing campaign or weapon database. Campaign changes
need an adapter to the application's accepted transactional command system and
an explicit cutover gate. No such real-data adapter is activated here.

The 2.1 directive authorizes native authenticated connectivity in the permanent
application. INTERNET permission now belongs to the main manifest and cleartext
traffic is disabled. This supersedes the earlier debug-only permission boundary.
The public Worker still accepts only its isolated fictional test messages, and
real campaign adapters remain absent and fail closed. Permission to use HTTPS
does not supply an owner service, device enrollment or verified connectivity.

The native Config screen, `OwnerSyncConfigPage`, permits saving a fictional test
scope, preparing a fixed fictional offline change, inspecting queue/receipt
states, manual authenticated send/refresh after service enrollment, and local
disconnect. It has no password/token-paste flow. Real campaign IDs are rejected.

## Wire contract, version 1

| Route | Purpose |
| --- | --- |
| `GET /v1/campaigns/{id}/records` | Scoped record snapshots on the reference service |
| `POST /v1/campaigns/{id}/commands` | Confirmed revision-checked command; returns original immutable receipt |
| `GET /v1/campaigns/{id}/changes?after=0&limit=50` | Ordered event feed with durable contiguous cursor |

Requests carry a device credential in the Authorization header and a
non-secret credential ID in `X-Astravar-Credential`. The reference operation is
`replace_confirmed_record`, using a complete fictional snapshot, not an implicit
merge or an inventory/currency mutation. Production commands must use the app's
typed ownership/currency/character rules, preserving all multi-record invariants.

Every write has a UUID command ID, exact campaign/record identity, expected
revision, explicit confirmed source, version and canonical payload. Object keys
sort recursively by UTF-16 code units; arrays retain order; unknown fields remain JSON null. Version 1
accepts only JSON integer numbers within the exact 53-bit range; sourced decimal
notation must remain a string instead of acquiring ambiguous cross-runtime
floating-point serialization. The
SHA-256 fingerprint covers exact canonical UTF-8 bytes. Identical retry returns
the original event ID, revision, cursor and recorded time. Reusing an ID with
changed content is rejected. Optimistic conflicts never silently rebase.

The native client verifies receipt command ID, fingerprint, record, revision
transition, event UUID and cursor before marking a save verified. History reads
verify scope, each contiguous cursor, original command fingerprint and any
previously observed receipt. Events and cursor commit in one Room transaction.
A gap, mismatched event, timeout or unverified response preserves the prior cursor
and queue. Authentication denial/revocation and conflicts enter review states;
quota/network failures stay pending. Authentication retries require explicit
review; conflicts require a newly resolved command.

## Local synthetic reference service

`tools/owner_sync_harness.py` implements the same wire contract against one
SQLite authority for fictional records, events and receipts. It has no public
bind option, no default running server and no external provider connection.
Its fixture provisioning creates revocable, expiring, campaign-scoped opaque
credentials and stores only SHA-256 token hashes. Read-only devices cannot write.
Authorization precedes receipt lookup, so revocation also denies replay/readback.
SQLite transactions serialize concurrent edits and roll back rejected writes.

This service is a **local acceptance harness**, not production OAuth, production
hosting or a private Sites replacement. Its loopback HTTP tests do not establish
Android HTTPS connectivity, native ChatGPT tool access or production security.

Run its independent acceptance tests with:

```sh
python -m unittest scripts.tests.test_owner_sync_harness -v
```

## Evidence and remaining gates

| Check | Evidence / status |
| --- | --- |
| Local service auth, write, original receipt, retry, independent readback, revocation | 11 Python tests passed, including actual loopback HTTP requests and a shared Unicode fingerprint fixture |
| Durable service records/receipts/revocation after reopen | Covered by Python tests |
| Concurrent stale revisions and changed-ID collisions | Covered by Python tests |
| Android queue, receipt verification, history integrity, offline replay, credential scope, encrypted vault | 18 repository/vault and 2 native UI Robolectric tests added; current Android build results are recorded in the upgrade checkpoint |
| AndroidKeyStore hardware behavior | Implementation present; crypto tests inject an AES key and do not constitute a physical device/TEE test |
| Existing private DM Bridge read | Read-only tool call returned Sites verified identity, Sites D1, DEMO_ONLY, revision 4 / 3 milestones on 18 October 2026; no write performed |
| New native Android client to an enrolled HTTPS service | Not verified; no production service or device enrollment available |
| New protocol exposed as owner-authenticated ChatGPT tools | Not implemented/deployed; existing private bridge is a different synthetic protocol/store |
| Native ChatGPT Android and new native Astravar round trip | Not verified for this upgrade; historical fictional public Worker evidence is separate |
| Real campaign upload or canonical import | Disabled; not performed or approved |

Before enabling real records, select a free hosting arrangement already available
to the owner and review its security/cost limits; do not activate paid services.
Use a supported owner-authenticated ChatGPT connection and owner-approved device
enrollment, revocable short-lived scoped credentials, server-side authorization,
player-known record filtering and a single authoritative event/receipt store.
Treat all narrative/lore text as data, never executable tool instructions.
Do not reuse a ChatGPT browser session cookie as an Android device credential.

Then verify, using fictional records first: ChatGPT authenticated read; approved
write; original receipt; native Astravar refresh; independent ChatGPT readback;
native offline edit/retry; no duplicate commit after lost response; stale
revision conflict; cross-campaign and read-only denial; credential revocation;
restart/restore; persistent data across server deployment; and device large-font
and offline feedback. Verify native ChatGPT Android separately from this Work
session's tool access. Only after those gates, accepted backups, reviewed migration
preview and separate owner approval may a real campaign adapter or cutover open.
