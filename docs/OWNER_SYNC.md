# Legacy owner sync queue and synthetic harness

This document records the earlier `OwnerSyncProtocol` queue/reference-service
implementation and its historical evidence. The current Config interface uses
the separate typed `OwnerConnectionProtocol`, `OwnerConnectionClient`, and
`backend/owner-bridge` service. See [Owner connection acceptance](OWNER_CONNECTION_ACCEPTANCE.md)
and the backend README for the current pairing, proposal, device approval,
receipt, local publication, and revocation workflow. The protocols are distinct;
the generic queue harness is not the deployed character service.

The code34 connection candidate adds an encrypted, device-bound owner-browser
authorization handoff before native device pairing. It remains fictional-only.
Deployment, actual ChatGPT discovery/invocation, and physical Galaxy acceptance
are separate gates and must not be inferred from local unit/contract tests.

The remaining sections describe the earlier queue-only candidate as it stood
before the direct-connection implementation, rather than the current Config UI.

This candidate implements an Android protocol, encrypted device-credential vault,
durable offline command queue and verified remote receipt/history handling. Real
campaign activation is deliberately closed. There is no deployed owner sync
service, enrollment flow, production connector or automatic campaign upload.
Notion remains the established campaign ledger until a separately approved
cutover. The existing private Astravar DM Bridge and its database are unchanged.

## Implemented boundaries

`OwnerSync.kt` is independent of the public disposable sandbox. It rejects that
sandbox's origin and insecure, credential-bearing or loopback literal origins.
DNS names are lowercased and trailing root dots removed before origin policy and
credential binding, so aliases cannot evade those same endpoint restrictions. No service
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

Config → Owner connection opens the native `OwnerSyncConfigPage`. It permits saving a fictional test
scope, preparing a fixed fictional offline change, inspecting queue/receipt
states, manual authenticated send/refresh after service enrollment, and local
disconnect. It has no password/token-paste flow. Real campaign IDs are rejected.
An HTTPS address passing syntax checks is not evidence of owner authentication
or trustworthy hosting. The authenticated enrollment service must establish that
trust before any credential is provisioned; this candidate has no enrollment
screen, browser-cookie bridge or automatic connection.

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
| Android queue, receipt verification, history integrity, offline replay, credential scope, encrypted vault | Verified exact-head CI e84a3df/run39158352867 XML shows 18/18 repository/vault tests and 2/2 Android/Robolectric Config UI tests passing in each variant, including root-dot normalization. That workflow later timed out in the separate build25 R8 pass; overall and later-source results belong to the upgrade checkpoint and final report. These are not native network or ChatGPT-to-device acceptance. |
| AndroidKeyStore hardware behavior | Implementation present; crypto tests inject an AES key and do not constitute a physical device/TEE test |
| Existing private DM Bridge read | Read-only tool call returned Sites verified identity, Sites D1, DEMO_ONLY, revision 4 / 3 milestones on 18 October 2026; no write performed |
| New native Android client to an enrolled HTTPS service | Not verified; no production service or device enrollment available |
| New protocol exposed as owner-authenticated ChatGPT tools | Not implemented/deployed; existing private bridge is a different synthetic protocol/store |
| Native ChatGPT Android and new native Astravar round trip | Not verified for this upgrade; historical fictional public Worker evidence is separate |
| Real campaign upload or canonical import | Disabled; not performed or approved |

These are separate acceptance layers. The Python HTTP round trip exercises the
local reference service with Python requests. Android repository tests use a fake
transport to exercise actual Kotlin/Room code and restart persistence; the shared
Unicode fixture verifies a wire fingerprint across languages. Config UI tests
exercise native Compose on Robolectric, including a durable fictional offline
queue and disabled send/read controls when no credential exists. None of those
tests sends an Android HTTPS request to the reference service. Local service 403
scope/read-only denial and durable revocation are tested independently; native
tests exercise a simulated 401 revocation, explicit access-restored retry,
local scope/expiry denial, 409 conflict, 429 quota and lost-response recovery.

The private Sites DM Bridge read used its already installed ChatGPT tool and
its separate synthetic D1 store. It is not a tool adapter for `OwnerSyncProtocol`
or proof that ChatGPT and the new native connection share a record. No new
ChatGPT write, native ChatGPT Android test, installed Astravar device test,
owner enrollment or real service deployment was performed in this upgrade.

The local SQLite harness is authoritative only for its own fictional fixtures.
The Android queue/inbox is staging. There is no production authority switch,
typed party/inventory/currency server command adapter, approval-policy service,
cross-device canonical record store or real campaign replay migration here.
The generic replacement command cannot stand in for those domain transactions.

## Exact next actions

1. Select an owner-controlled free hosting arrangement and review its security,
   persistence and cost limits before deployment. Preserve the existing private
   bridge/database; do not replace it or send records to the anonymous Worker.
2. Implement and test owner authentication, explicit device enrollment, revocable
   short-lived campaign-scoped credentials, player-known filtering, authorization
   and one authoritative event/receipt store. Add secure re-enrollment and a
   visible access-restored recovery action; local disconnect alone does not
   revoke server access. Never reuse ChatGPT browser cookies as device tokens.
3. Expose owner-authenticated ChatGPT tools and typed application adapters against
   that same authority. Reuse the app's transactional actor/item/currency command
   rules rather than replacing partial snapshots. Define confirmed-event approval
   policy and treat narrative/lore text as data, never tool instructions.
4. With fictional records, capture ChatGPT read → approved write → original
   receipt → actual native Astravar HTTPS refresh → independent ChatGPT readback.
   Then verify native offline edits, restart, lost-response replay, stale conflicts,
   quota failures, cross-campaign/read-only denial, credential expiry/revocation,
   server redeployment persistence and accessible device feedback. Verify native
   ChatGPT Android separately from this Work session's tool access.
5. Complete backup/restore and reviewed migration reconciliation acceptance.
   Only separate owner approval may then enable a real campaign adapter/import
   and change the authoritative source. Until that decision, retain the current
   ledger and all installed application data.
