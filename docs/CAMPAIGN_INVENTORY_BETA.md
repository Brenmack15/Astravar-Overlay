# Astravar 1.7.0: Campaign Inventory Capture MVP (Offline Beta)

**Scope:** A non-destructive, locally stored campaign-item and research interface integrated into the recovered **v1.7.0 source**. This implementation is separate from the old core/payload screen and from the established weapon/combat database.

## Installation safety

- **Recovery parent commit:** `b4f539bca7965b40153bc5e237f66b458ee1578e` on `recovery/astravar-latest-working-2026-10-17`. Work only on a new feature branch based on this exact commit. Leave draft PR #1 unmerged.
- Build the **debug** APK, using package `com.yazsras.astravar.debug` and suffix `1.7.0-inventory-beta`. It installs *alongside*, not over, the release app currently on the phone.
- Only the debug build exposes the new **More → Campaign Inventory · local beta** entry. Do not change signing keys or release package, ship new release, or assume same data profile as the installed owner-signed app.
- **Existing Room v3 schema and migrations are NOT modified.** A separate versioned `astravar-campaign-ledger.db` stores new items and their research event history, with no destructive migration fallback.
- This prototype does NOT connect to the Notion campaign ledger, Cloudflare or ChatGPT; a local item entry cannot be read by the DM yet. Keep using the private Notion ledger for active campaign recording.
- Existing weapon backup/restore does **not** include the new separate ledger DB. Use the new **Export JSON** button on the Campaign Inventory page before reinstalling or clearing debug data. That export contains item profiles, embedded compressed images, and their event history. **There is no restore/import UI in this beta yet.** Do not trust it as the sole copy of valuable live campaign records.

## User workflows

1. Open **More → Campaign inventory · local beta**.
2. Select **New item**; enter the item name, category, owner, location/state, quantity (leave blank for unknown), description, confirmed known properties, possible uses, limits, source, and optional Notion page ID or URL.
3. **Choose item image** imports the selected picture into a small compressed WebP thumbnail stored inside the Room record (not just an external temporary URI).
4. Save and reopen the same item; edits require the original revision, so an outdated editor cannot silently override a newer saved discovery.
5. In **Research and discoveries**, select Confirmed / Possible use / Limitation / Correction, enter what was learned and the source or check, and **Append discovery**. The item summary updates and an immutable event records the previous and resulting item snapshot.
6. **Export JSON** to a document destination you control. No cloud sync or automatic transfer is enabled.

## Safety and data semantics

- Item ID and event command IDs are UUIDs; repeated commands are idempotent and reject payload collisions. Research records are append-only.
- `quantity=null` means unknown; do not silently treat it as 0. A theoretical use cannot become a confirmed mechanical property without deliberate adjudication.
- Do not populate this database with presumed Zerg inventory, gold amounts, unverified spell effects, or fictitious research results.
- Item classification and ownership are player-visible only; no GM secrets belong here. New update/edit actions require expected revision.
- No money mutation, auto-crafting consumption, DM automation, Notion sync or remote server has been added. Any earlier Cloudflare prototype remains separate and has not been verified by this Android integration.

## Acceptance and QA (NOT yet claimed)

- Run `python3 -m unittest discover -s scripts/tests` for the patch transform unit tests.
- In the recovered Android checkout on a new feature branch, apply `scripts/apply_inventory_mvp.py` from this bundle and inspect git diff.
- Run `./gradlew --no-daemon :rules:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug` with bounded timeouts. The package includes 7 new Robolectric/Room tests in `CampaignLedgerTest.kt`.
- Install the `.debug` package on a spare/test device or alongside the release; verify creating, editing, re-opening, picture attachment, discovery notes, process restart and export.
- Before any release, verify database migrations, source-to-APK provenance, label, backup/restore strategy, and more importantly actual Notion/cloud integration on the user's intended ChatGPT campaign workflow.

## Known gaps

- No Android build executed within the preparation environment (Android SDK and Gradle dependency access unavailable).
- No device tests yet.
- Notion-to-Android import, AI-authenticated write path, full image sync, currency, invention/crafting accounting and companions will follow.
- The separate JSON export currently has no built-in import operation. Do not use the debug app as the sole authoritative campaign ledger during play.
- Debug-specific menu hides the feature from release builds until functionality and migration are approved.
