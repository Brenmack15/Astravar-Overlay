# Owner character connection contract

This service is restricted in both server code and Android code to one fictional
acceptance campaign (`synthetic-owner-acceptance`), one fictional profile
(`owner-sync-fixture`), and its Intelligence record (`owner-sync-fixture-int`).
It does not upload Zerg, Notion exports, equipment, currency, hidden information,
or the separate combat profile. Hosting source and passing local tests do not
establish a connection to an installed Android app or a ChatGPT tool connection.

## Trust and persistence

`core.mjs` operates on the D1 prepared-statement/batch API. `schema.sql` creates
new `bridge_*` tables only. The immutable receipt insertion checks the prior
revision and applies the mirror update in a single SQLite statement, including
on D1. SQL triggers also enforce cross-table command identity, immutable
proposals/receipts, at most 50 outstanding proposals, 20 unexpired pairing
attempts, and 10 unrevoked devices. A rejected or conflicting decision never
updates a mirrored record. No database deletion or destructive migration is
required.

`sqlite-adapter.mjs` uses Node 24's SQLite API solely for persistent contract
tests. It does not replace the hosted D1 implementation. Tests exercise two
independent SQLite connections, process-style restart, file backup/restore,
integrity checks, role and scope denial, stale revisions, idempotency,
revocation, and full approved-field readback. These are backend tests; native
device acceptance remains a separate gate.

The hosted Worker must be private behind the verified Sites identity/service
gateway. The pinned `OWNER_SITE_USER_ID` is deployment configuration. Caller
JSON and device headers cannot select that owner identity. Sites, rather than
the device, authenticates and replaces `oai-authenticated-user-id`. Deploying
this entrypoint directly on an anonymous origin without that gateway is unsafe.
Neither deployment nor real-data acceptance is authorized by this source file.

## Pair and revoke

1. The Android client generates a cryptographically random URL-safe secret of
   at least 43 characters and retains it in its encrypted local credential vault.
2. `POST /v1/pairings` accepts `campaignId`, `deviceName`, and its lowercase
   SHA-256 `deviceSecretHash`. The response includes a 10-minute `deviceCode`
   and high-entropy `userCode`; these codes do not confer owner rights.
3. The owner signs in to the private service and approves `userCode` through
   `POST /v1/owner/pairings/approve`. A connector principal cannot approve it.
4. `POST /v1/pairings/token` accepts `deviceCode` and `deviceSecret`. Before
   owner approval it returns HTTP 202 and `status: PENDING`; afterwards it returns
   `status: PAIRED`, `credentialId`, `expiresAt`, `ownerId`, `ownerLabel`,
   `deviceName`, and the three granted scopes. Secrets are never stored in
   plaintext in the server database. The same retained device secret is the
   device bearer; lost polling responses can be retried safely.
5. Device requests send `Authorization: Bearer <retained secret>` and
   `X-Astravar-Credential: <credentialId>`, plus the private host service gate
   where required. The scopes are `character:read`, `character:publish`, and
   `proposal:decide`. Credentials expire after 30 days. Re-enrollment is the
   current renewal procedure; automatic refresh tokens are not implemented.
6. `POST /v1/campaigns/{campaignId}/devices/revoke` with `{}` revokes the
   requesting device on the server. The signed-in owner can independently list
   devices with `GET /v1/owner/devices` and revoke a lost device with
   `POST /v1/owner/devices/revoke` and `{credentialId}`. Local disconnect is
   distinct from confirmed server revocation. Repeating the self-revocation
   request with the same hash-verified inactive credential can only confirm
   its own revoked state; it grants no record read, pairing, or mutation access.
   This permits recovery when the first revocation response was lost.

## Narrow character loop

All campaign routes start with `/v1/campaigns/{campaignId}/`; successful results
carry `{mode: "ASTRAVAR_OWNER_BRIDGE_V1", campaignId, ...}`. Timestamps are
integer epoch milliseconds. IDs, revisions, fields, and allowed extra keys are
validated; malformed requests fail closed. Origin-crossing browser requests,
oversized bodies, and redirects are rejected by the appropriate layers.

- `POST characters` is device-only, with `commandId`, the explicitly selected
  fictional `profile`, one typed `ability`, and `expectedRevision`. Initial
  publication is revision 1 against 0; subsequent explicit local publications
  require exactly `expectedRevision + 1`. The profile must match its previously
  published snapshot. The response contains an immutable `PUBLISHED` receipt.
- `GET characters/owner-sync-fixture` returns only that profile and its
  published ability records. Unknown HP or other absent fields remain absent.
  The ability shape is `{recordId, kind: "character_ability", revision, fields}`;
  fields are `profileId`, `key`, `score`, `magicModifier`, `notes`, `source`,
  `updatedAt`. Only the fixture's `INT` record is currently supported.
- MCP `propose_record_update` creates a pending proposal from `commandId`,
  `recordId`, `expectedRevision`, `fields: {score}`, and `source`. Its
  fingerprint is SHA-256 of recursively key-sorted integer-only JSON of
  `{campaignId, ...request}`. The response includes old/current fields, the
  proposed score, and an explicit difference. It does not write an ability.
- `GET pending` returns at most 50 pending proposals. Further proposals are
  rejected with HTTP 429 until existing ones are decided; no invisible backlog
  can grow behind the response limit.
- After explicit native approval, the Android application commits through its
  existing local transactional record/receipt path, then submits `POST decisions`
  with `commandId`, `approved`, `localAppliedRevision`, `localReceiptId`,
  `proposalFingerprint`, and `localFields`. Approved `localFields` must preserve
  the original profile/key/magical modifier/notes, match the proposed score,
  and carry the exact local provenance
  `Player-approved owner proposal {commandId} · {proposal source}`. The source
  is capped at 1,900 characters to allow this provenance within 2,000 characters.
  The server retains the device's actual saved timestamp and full fields.
- Rejection sends `approved: false` and explicit null `localAppliedRevision`,
  `localReceiptId`, and `localFields`. A decision yields immutable `APPLIED`,
  `REJECTED`, or `CONFLICT` receipt. `localRecordFingerprint` binds the approved
  snapshot to the receipt. Conflicts report the actual current mirror revision,
  which can be newer than the proposal's expected revision.
- `GET receipts/{commandId}` independently recovers a decision/publication
  receipt. `GET history?after={cursor}` pages up to 50 receipts. Retrying the
  identical command returns its original receipt; reusing its ID with different
  content fails with HTTP 409. The server never retries a conflicting write as
  a new revision or silently rebases it.

A ChatGPT connector principal has only `character:read`, `proposal:create`, and
`proposal:read`; it cannot publish, approve/reject, pair devices, or change
credentials. MCP discovery does not by itself establish platform registration,
user connection, or a real authenticated invocation.

## Remaining acceptance gates

The full field/revision loop must be observed on the actual owner-signed Android
application and an authenticated ChatGPT invocation, including native approval,
restart, backup/restore, offline recovery, rejection, stale/duplicate commands,
invalid/revoked authorization, and conflicting local edits. A local core/MCP
test does not satisfy that standard. Grouped updates, other abilities, other
campaign record types, real-data activation, credential automatic renewal, and
production deployment remain separate work or explicit approval gates.
