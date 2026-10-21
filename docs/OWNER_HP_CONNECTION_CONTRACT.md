# Owner HP connection contract

This extension continues the existing owner connection. The original fictional
protocol remains separate and cannot authorize access to actual profiles.

## Authority and scope

The Android campaign database remains authoritative. Pairing selects an existing
character profile and binds the new device grant to its exact profile and campaign
scope. No profile or hit points are initialized by synchronization. An existing,
unambiguous Party sheet campaign ID is retained when valid. Otherwise a stable
technical synchronization namespace is derived as `astravar-live-` followed by the
first 32 hexadecimal characters of SHA-256 of the profile ID; this creates no game
campaign record. Fictional fixture IDs are forbidden in this path.

HP device grants contain only `hp:read`, `hp:publish`, and `hp:decide`. Authenticated
owner MCP calls use only `hp:read`, `hp:propose`, and `hp:receipt`. A connector can
never publish, approve, reject, enroll, or revoke a device. The outer Sites gateway
credential does not replace the profile-scoped device grant.

## Typed transport

The HP mode is `ASTRAVAR_OWNER_HP_V1`. API routes begin with
`/v1/hp/campaigns/{campaignId}/`. Record ID equals the existing profile ID; it is
not a fictional test identity or a newly created character.

The sole mirrored fields are `name`, `currentHp`, `maximumHp`, `temporaryHp`,
`source`, and `updatedAt`. The existing profile revision is preserved. Initial
publication uses expected server revision zero and can publish any valid existing
local revision without writing or incrementing the local profile. Subsequent
publication must pass a server compare-and-swap check and cannot downgrade data.
Durable publication command IDs and receipts make retries idempotent.

The actual device's last verified synchronization time is distinct from HTTP read
time. Reading a server mirror cannot make an offline device appear fresh. Reads
report freshness, unknown values remain null, and stale or unknown HP cannot be
used to create an adjustment proposal.

Unchanged records use a dedicated authenticated heartbeat rather than creating
permanent publication receipts. Its five fields are `profileId`, `revision`,
`fieldsFingerprint`, `sentAt`, and `commandId`. The server verifies the exact active
writer, record revision and canonical narrow snapshot hash, a 60-second timestamp
window and increasing send time. Retrying the last identical heartbeat returns its
original acknowledgement without making it fresh again. Only bounded heartbeat
metadata changes; HP, revision and immutable command receipts do not. A new native
heartbeat follows an actual local profile read and independent remote match. An
unacknowledged heartbeat needs no durable command journal because it changes no
campaign record. Record publications and player decisions retain durable queues.

## Proposal and approval

`propose_hp_adjustment` takes campaign/profile IDs, command ID, expected profile
revision, expected current HP, a signed nonzero adjustment, and request source.
The server calculates the proposed current HP and validates it against zero and
the known maximum. It stages a proposal only. Maximum HP, temporary HP, other
profile fields, combat settings, and unrelated records are never writable by this
operation.

Proposal fingerprints are SHA-256 over canonical JSON of exactly:

```
{campaignId, commandId, recordId, expectedRevision, fields, source, adjustment}
```

`fields` contains only `currentHp`. Pending proposals include the narrow current
fields, signed adjustment, explicit difference, expected revision, and fingerprint.
The native approval interface displays the name, current and proposed HP,
adjustment, source and revision. Approval rechecks both the expected revision and
the frozen full local profile. It commits through `CampaignRecords.saveProfile`
with a stable local command ID, changing only current HP and normal audit metadata.
The source is `Player-approved HP connection test <commandId> · <request source>`;
this is a connection adjustment and does not fabricate an encounter or damage roll.

Approval intent is durable before the local transaction. The campaign database's
immutable command receipt closes restart/crash windows; an independent connection
journal tracks delivery. The decision body uses `commandId`, `approved`,
`localAppliedRevision`, `localReceiptId`, `proposalFingerprint`, and `localFields`.
Rejection has null local-commit fields and creates no profile revision.

## Verification and automation

Server receipt and record readback are separate operations. A completed command
must have one immutable receipt and the saved profile revision must independently
match its result. Native and backend tests exercise replay, stale revisions,
unknown HP, unauthorized records/devices, expiration, revocation, interruption,
retry, restart and backup restoration using isolated fictional records.

Native emulator evidence is labeled as emulator evidence. Real-device acceptance
requires the owner-installed APK, actual device pairing, one explicit HP approval,
the independent receipt and actual saved record readback. No screenshot, PDF,
Notion record, fixture, inferred full-health value, or backend mock substitutes for
that acceptance.

Replacing an enrollment does not transfer approval authority. Old unresolved
proposals are reported as `SUPERSEDED_BY_REPAIR` metadata, without a fabricated
command receipt. A restored local profile and its original local campaign receipt
can be preserved and published under a newly owner-approved enrollment with a new
publication command. This does not claim the old server command was applied.
