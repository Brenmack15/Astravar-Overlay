# Isolated encounter foundation

This work belongs to `recovery/astravar-genesis-encounter-20261112`, rooted at
combined C `0c157b485d4670ecce4d928c317a23fae1438329`. It is an unfinished D
milestone, separate from the C release gate. Do not include it in an APK until its
schema, backup, UI and native verification complete.

The new tracker uses two additive tables in the existing campaign Room database,
with campaign and actor foreign keys. Migration8→9 creates empty tables and
indexes; no encounter, initiative, actor or campaign event is inferred. Previous
schemas and migrations remain unchanged. Room9's generated schema is still
pending the coordinated build, as are the source-review/native schema9 guards.

Participants reference exact authoritative actor identities, including explicitly
selected source-only or archived actors. Unknown initiative remains unknown;
supplied list order is the actual order. A draft may have no participants; a
reviewed start requires at least one. During active tracking, later actor edits,
archival or campaign reassignment never silently remove historical participants.
New setup/start requires current explicit membership in the selected campaign.

Start freezes setup and membership references. Next turn changes only the
tracker's current participant and displayed round. End retains its last cursor.
Opening an ongoing encounter only reads it. The separate explicit Reopen setup
command returns an ended encounter to a draft and clears only that tracker cursor;
its former order and round remain in immutable receipts. Nothing rolls dice,
alters actor HP/resources/conditions/turns, advances campaign time or performs a
rest. Existing combat and character screens remain authoritative.

Commands reuse the authority receipt namespace, freeze before/after snapshots,
reject stale revisions and conflicting retries, and commit in one Room
transaction. Connected backup adds default-omitted encounter fields, retains
original membership proof and validates the full transition chain. Existing
same-ID differences block merge; history remains valid after later actor changes.

At the first backend checkpoint, no Gradle task has run. Backend lifecycle and
portable-proof tests are being added; migration-retention tests are a separate
owned commit. The current C software-emulator run has the sole local build slot.
Preserve this branch and every dirty follow-up before continuing. No remote ref,
PR, source pin, signing material or C checkout has been changed by this work.
