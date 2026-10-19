# Local campaign archive import

Campaign → Campaign import opens Android's document picker. An archive is read
as bounded, strict UTF-8 (32 MiB maximum); no remote source, Notion or Cloudflare service is
contacted. Preview checks the supported envelope, exact payload SHA-256,
per-record body hashes, source identities, relationships and mapped records.
The digest detects changed bytes; it does not authenticate who created a file.

The player reviews counts, original content, conflicts and unresolved details,
saves a pre-import campaign backup through Android's document picker, and
confirms the local import. The saved backup is reopened through the document
provider and compared independently before confirmation becomes available.
Differing existing records block the merge. Imports do not replace the weapon
database, execute historical events, spend currency, craft equipment, restore
resources or approve unresolved mechanics.

CampaignLedgerDb migration 3→4 adds reference records, relations, source payloads,
immutable import receipts and verified recovery snapshots. Existing schema files
and data remain intact. Optional typed records merge into the existing Inventory,
Party, Inventions, Connections and Checkpoints tables. The searchable Reference
Library retains complete source text, original properties, provenance and links
for content without a typed destination. External links open only when selected.
Embedded attachments retain their original bytes (or explicitly named lossless
gzip bytes) in the retained source archive. They are not executed or fetched.
Reference Library → a source record → Archive source & attachments exposes
attachment status, original IDs, source URLs and hashes. **Save verified attachment**
exports embedded bytes through the document picker and checks an independent
readback. External-reference-only files are labeled unavailable offline; their
source URLs open in the browser only after a player tap.

## Phone installation and import

1. In the currently installed app, save **Config → Campaign backup → Export
   campaign backup**, and save the separate **Config → Weapon backup**. Keep
   those files while installing an update.
2. Download the owner-signed APK and the private campaign archive JSON to the
   phone. Tap the APK in Android Files and choose **Update**. A compatible update
   retains the existing app sandbox and migrates its campaign database additively.
   Keep the installed app if Android rejects the update; uninstalling would remove
   its local records. Signature compatibility and the incremented version code
   must be verified for the delivered APK, independently of these instructions.
3. Open **Campaign → Campaign import → Choose campaign archive**, then select the
   `.astravar-campaign.json` file. Select the JSON inside the delivery package,
   rather than the ZIP containing the supporting source export and reports.
4. Review **Review campaign archive**: source root/revision, counts by category,
   original source records, mapped campaign records, conflicts, attachment status
   and unresolved details. A conflict blocks import; the app does not offer an
   override that could replace an unresolved current record.
5. Tap **Save verified pre-import backup** and choose a destination in Files.
   Wait for **Backup verified for this review.** The app retains its recovery
   snapshot and independently reopens the chosen external backup document.
6. Select **I reviewed the archive and want to add approved records locally.**
   Then tap **Confirm local import**. Review does not authorize import, and neither
   review nor consent is restored automatically after an Activity restart.
7. Require **Import saved. Independent database readback passed.** Then use
   **Verify saved import** on the saved receipt. If the app was interrupted during
   confirmation, inspect its receipts and verify the saved state before retrying.
   Selecting an already imported package displays its existing-import notice and
   cannot add a second copy of its records.
8. Inspect **Campaign → Reference Library** and the existing **Party**,
   **Inventory**, **Inventions**, **Connections** and **Checkpoints** destinations.
   Armor, Party and Inventory also expose links to the source archive. Missing
   current values remain unknown; the original text and source relationships are
   available in each Library record.

## Recovery and source authority

Before making later campaign edits, **Campaign → Campaign import → Review
rollback** opens a separate recovery decision. Read the scope, select **I
understand this restores the verified pre-import campaign backup.**, and tap
**Confirm rollback**. Recovery verifies the retained snapshot, restores it in a
transaction and checks database readback. Immutable import and recovery receipts
remain available. Later campaign changes block automatic rollback so they cannot
be discarded silently; retain both exported states for reconciliation.

The weapon database is a separate store and requires its own backup. The Notion
archive remains unchanged and recoverable. Local import confirmation does not
establish sole authority: review the reconciliation results and obtain the owner's
separate final approval before authoritative cutover.

## Archive contract

The versioned envelope has these required fields:

```json
{
  "format": "astravar-campaign-archive",
  "schema": 1,
  "payloadSha256": "<lowercase SHA-256 of the exact UTF-8 payload string>",
  "payload": "<JSON-encoded CampaignArchivePayload>"
}
```

`CampaignArchivePayload` declares campaign scope, export time, root URL/revision,
player-visible source records, optional typed `LedgerExport`, issues and
attachments. Each source record includes a stable external ID, title/category,
complete body, original properties JSON, source URL/revision, body SHA-256 and
relation IDs. Unknown values remain null. An unresolved relation stays visibly
unresolved; it is never silently discarded. Proposed inventions and historical
events remain separate from currently available physical equipment.

Commit is one Room transaction. A failed or interrupted transaction leaves no
partial import. Exact repeated package hashes return the original receipt without
adding records or money. Post-import verification queries the database again and
compares identities, relationships, body hashes and every typed value. A guarded
rollback restores the verified pre-import snapshot only while the campaign still
matches the post-import state; later edits require reconciliation instead of
discarding that work. Import and rollback receipts remain available. Standard
schema-4 campaign backups include the Reference Library and migration evidence.

Private source exports, reports, attachments, APKs and signing files must remain
outside this repository. `tools/verify_campaign_archive.py` provides an independent
offline package inspection and also refuses campaign paths inside a Git checkout.
Only fictional fixtures belong in automated source-controlled tests. A successful
local import is separate from approval to make Astravar the authoritative store;
retain the original Notion archive until the owner explicitly approves cutover.

## Large archive memory handling

An actual 192 MiB Android emulator exposed excessive temporary serialization
allocations in the code-26 candidate. Code 28 streams UTF-8 digests and JSON
encoding, reads large SQLite text in bounded chunks, and compares typed records
without materializing another JSON tree containing the retained archive.
Existing SHA-256 and schema-4 backup bytes remain compatible. Document output is
written and independently verified through bounded buffers. The unchanged large
fictional archive remains an acceptance test; a successful JVM test alone does
not certify native import or recovery on Android.
