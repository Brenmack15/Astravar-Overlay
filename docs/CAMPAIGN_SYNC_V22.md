# Campaign connection and Trusted DM Auto-Sync

Astravar 2.2 extends the existing Android application and owner-private service.
The campaign Room database remains authoritative. Server records are scoped
mirrors of committed device records. A queued command or successful HTTP request
does not establish a gameplay save.

The wire contract and typed operations are specified in
[`CAMPAIGN_V2_CONTRACT.md`](../backend/owner-bridge/CAMPAIGN_V2_CONTRACT.md).
Original fictional diagnostics and the earlier manual HP protocol remain
separate. Their credentials do not grant campaign authority.

## Owner setup on Android

1. Install the verified original-owner-signed update over the existing app.
   Preserve application data; never uninstall or clear storage to resolve an
   update conflict.
2. Open Config → Owner connection. Select the existing saved campaign/profile,
   choose read and permitted-update categories, and authorize its pairing on the
   existing private owner page. Reuse valid private service authorization or use
   the installation-encrypted browser authorization flow if renewal is needed.
3. Enable Trusted DM Auto-Sync and explicitly select the permitted categories.
   The server and device must both confirm the standing grant. Manual mode
   remains available. Changing a grant invalidates outstanding commands made
   against its prior revision rather than silently expanding their authority.
4. Keep the existing floating play session active while using ChatGPT for active
   delivery. The app, connection screen and existing foreground overlay perform
   authenticated synchronization. Android also schedules a periodic background
   fallback; an inactive or offline device can leave delivery pending.

Only visible short-lived human-readable codes are eligible for tap-to-copy.
Device proofs, private transport credentials and signing material are never
clipboard controls. Grant revocation is reported complete only after the server
confirms it. Private service renewal does not silently re-enroll a campaign.

## DM operation

Use the existing connector and read its actual authorized connections first.
Select the established campaign and stable profile/item/resource IDs. Prefer the
bounded active-session aggregate and specific record queries over unrelated
reference retrieval. Preserve unknown fields, source conflicts and stale-device
warnings. Read the current saved revision before submitting a write.

Submit only gameplay the player explicitly authorized, decided or resolved.
Keep one unique command ID for all retries of the same exact transaction.
Expected record revisions and the current grant revision are mandatory. Group
related costs and acquisitions into the same typed transaction. Moving or
installing a physical object retains its identity; views are not extra objects.

Submission returns pending delivery or pending manual approval. Completion
requires an independent immutable command receipt and reads of the saved records
matching the committed revisions and values. The Android client also compares
its domain receipt, server receipt and independent record readback. Report
partial verification, offline delivery and conflicts explicitly. Never replace
an uncertain command with a new ID to obtain a second application.

No command implicitly creates a rest, roll, acquisition, payment, level-up,
construction completion, accepted homebrew ruling or new campaign scene.
Character spellcasting statistics and the independent weapon engine retain
their established meaning. Roll-history reads do not roll player-facing dice.

## Recovery and reconciliation

The durable connection journal is separate from campaign and combat stores.
Command intent survives interruption; the immutable campaign receipt is checked
before a retry can perform domain changes again. Every supported multi-record
operation uses the existing campaign transaction domain. Combat records are
read-only synchronization projections and cannot become incidental writes in
a campaign transaction.

Conflicts preserve the original command and receipt. A confirmed correction is
a new typed transaction against the actual current revision with a source
explaining the correction; it never rewrites the historical receipt. Historical
backups and original source identities remain recoverable. New wallet EP/PP
fields are nullable, with no inferred currency conversion or invented balance.

Private imports require a preview, a verified pre-import backup, source identity
reconciliation and the necessary owner authorization. A source absent from an old
export is not necessarily missing on the phone. Original Notion records remain
unchanged. Do not restore older unknown HP, counters, items or workshop states
over later confirmed records merely to make the mirrors agree.

## Verification boundaries

Local SQLite, Kotlin/Room, Compose, emulator HTTPS, hosted MCP and physical phone
acceptance are separate evidence classes. Disposable CI signing proves test APK
compatibility only; original-owner APK certificate verification is a separate
release check. Neither establishes an installed Galaxy update by itself.

The release handoff records the exact tested source, artifact checksums, actual
test outcomes, deployed version, observed connector discovery/invocation and
remaining owner-only actions. Unobserved physical commits are never marked done.
