# Dynamic ChatGPT prompts — Astravar 2.4.0 / code 41

## Access and behavior

Open **Config → ChatGPT**, or **Config → Settings → ChatGPT Prompts**. Add a prompt, search its name/ID/category/description/content, filter by category, read it, edit it, or confirm deletion. Editors use the same PlayerCard/PlayerSheet controls and private large-text draft storage as inventory. Save validates fields; Cancel/Close asks before discarding changed text. IDs can be chosen when creating a record and remain stable afterwards. Created and last-updated dates appear with expanded content. Concurrent edits/deletions are rejected using the revision the editor originally opened.

The eleven existing prompts are manual clipboard handoffs. Astravar currently has no direct ChatGPT/OpenAI inference request, and this feature adds no network calls, account connection, model dependency, API key, permission or campaign write scope. **Copy handoff prompt** retrieves the current record by ID each time. Editing wording affects the next copy immediately, including copies after app restart. Reading or editing a prompt never sends it automatically.

## Existing architecture and implementation

The native `:app` module uses Kotlin, Compose/Material3, Room, coroutines and DataStore; `:rules` remains the shared combat engine, and `:overlay-target` is its controlled instrumentation harness. Existing Item CRUD is local `LedgerDao` / `CampaignLedgerRepository` code over `astravar-campaign-ledger.db`, observed through Flow and rendered in `CampaignLedgerPage`. The separate Cloudflare owner bridge provides scoped typed campaign reads/commands/receipts; it has no generic prompt or model-inference endpoint to reuse.

Prompt records reuse that same Room database and repository patterns. `ChatGptPromptRepository` owns validation, transaction boundaries, immutable IDs and optimistic revisions. `ChatGptPromptsPage` owns list/editor/confirmation/variable UI only; no prompt bodies remain defined in Kotlin. Both existing navigation routes receive the same repository.

## Additive migration and defaults

Room **5 → 6** adds only:

- `chatgpt_prompts`: stable primary-key `id`, `name`, optional `description`, editable `content`, optional `category`, `createdAt`, `updatedAt`, and optimistic `revision`; indexes on name and category.
- `prompt_catalog_state`: a durable installation marker and initialization metadata.

Existing Room schemas 1–5, campaign rows, weapon storage, database filenames, preferences, credentials, receipts, overlay behavior and mechanics stay intact. Older supported database versions migrate through their existing chains and then 5→6. There is no destructive fallback or reset.

The unchanged `astravar_chatgpt_prompts.json` is the single original seed source. On first prompt use, one transaction validates and inserts the eleven defaults without overwriting an existing ID, then writes marker `astravar-chatgpt-catalog-v1`. A failure commits neither seeds nor marker. Later visits/restarts/updates check the marker before even loading the bundled asset, preserving all user edits and confirmed deletions. The asset is never a fallback for a missing/deleted record. A future new catalog generation requires a deliberate new migration policy rather than silently repopulating deleted defaults.

Existing identifiers and wording are preserved exactly:

| Stable identifier | Existing workflow | Category |
| --- | --- | --- |
| `dm-continuity` | Continue a campaign in a new chat | Campaign |
| `dev-continuity` | Continue Android development in a new Work/Codex chat | Development |
| `sync-reconcile` | Reconcile Notion and Astravar records | Recovery |
| `level-up` | Review a party member level-up | Party |
| `inventory-event` | Update inventory, equipment and currency | Campaign |
| `checkpoint` | Save session handoff | Campaign |
| `gear-audit` | Recover the latest authoritative checkpoint | Party |
| `research` | Record inventions and completed crafting | Campaign |
| `backup-recovery` | Recover after conversation context is exhausted | Recovery |
| `source-of-truth` | Check synchronization, revisions and conflicts | Recovery |
| `release` | Validate a new signed APK | Development |

These are currently manual selections, so none is a mandatory hidden feature reference. Deletion is explicit and confirmed. A subsequent lookup/render of a missing ID returns a clear unavailable/deleted error and never copies stale or replacement wording.

## Reusable variables

Use `Generate a description for {characterName} based on {context}.` Copy opens a variable form, with a preview and a final copy action. The values apply to that copy and do not change the saved template. Future feature code can use `repository.render(id, mapOf("characterName" to name, "context" to context))`.

Variables are case-sensitive names beginning with a letter, followed by letters, digits or underscores (maximum 64 characters). `{{name}}` emits literal `{name}`. Ordinary JSON braces stay literal. Replacement is single-pass and literal: inserted `$1`, backslashes or `{other}` text is not executed or expanded again. Missing named values and a wholly empty result fail clearly. No variable automatically fetches or invents campaign data. The original eleven prompts contain no placeholders and render byte-for-byte as before.

Technical validation limits are 120 characters for name/ID, 4,000 for description, 80 for category, 100,000 for prompt content/completed output, and 64 distinct variables. Output length is checked with Long arithmetic before substitution to avoid oversized allocation/clipboard writes. IDs accept letters/numbers/dots/underscores/hyphens and cannot change on update.

## Backup and access boundaries

Prompts are app-wide local settings, rather than campaign facts. Campaign JSON export remains schema 5 and weapon/campaign backups do **not** include prompt settings. Campaign import, restore and rollback leave the prompt tables and catalog marker untouched; tests verify this explicitly. Cross-device prompt export/sync and server/MCP prompt CRUD are not included. Existing campaign access grants do not expose or authorize prompt editing.

## Verification

Tests cover all eleven exact migrated defaults; initialization atomicity/idempotence; edited/deleted/custom records after close/reopen; CRUD, timestamps, duplicate IDs, invalid input and stale revisions; current-stored-content lookup; literal/missing/escaped/oversized variables; frozen Room5-to6 row preservation; unchanged prompt settings through campaign rollback; native Compose create/edit/cancel/delete confirmation, latest copy and variable preview/copy. Existing historical migration tests and independent SQLite readback now understand Room6 while retaining campaign export schemas4/5 and frozen older profiles.

Run the focused prompt suites with:

```sh
./gradlew :app:testDebugUnitTest --tests '*ChatGpt*'
```

For full checks use `:rules:test :app:testDebugUnitTest :app:lintDebug :app:lintRelease :app:assembleDebug`, Python tests under `scripts/tests`, and the upgrade/foundation/branding/particle guards. An owner release must use the original owner signing certificate, permanent package `com.yazsras.astravar`, and code41, and its finished APK must be inspected directly before delivery. Native Robolectric screenshots/tests are not evidence of physical tablet installation.

## Changed-file map

- New production data layer: `app/src/main/java/com/yazsras/astravar/data/ChatGptPrompts.kt`.
- Database/repository integration: `data/CampaignLedger.kt`; generated schema `app/schemas/com.yazsras.astravar.data.CampaignLedgerDb/6.json`.
- Prompt management and navigation: `ui/ChatGptPromptsPage.kt`, `ui/AppScreen.kt`, `ui/PlayerShell.kt`.
- New tests: `data/ChatGptPromptRepositoryTest.kt`, `ui/ChatGptPromptManagementTest.kt`; adapted legacy `ui/ChatGptToolkitTest.kt`.
- Existing migration test compatibility: `CampaignMigrationTest.kt`, `PartyMigrationTest.kt`, `CampaignArchiveMigrationTest.kt`, `CampaignArchiveSchemaCompatibilityTest.kt`, `CampaignCurrencyV22Test.kt`.
- Independent preservation/native checks: `scripts/verify_upgrade_integrity.py`, `scripts/verify_ui_foundation.py`, `scripts/ui_foundation_sha256.json`, `scripts/native-campaign-import-readback.py`, `scripts/tests/test_native_campaign_import_readback.py`, `scripts/native_recheck.py`, `scripts/ci-emulator.sh`, `scripts/ci-player-first-emulator.sh`.
- Version and documentation: `app/build.gradle.kts`, `README.md`, this guide. Paths abbreviated as `data/` and `ui/` above are under `app/src/main/java/com/yazsras/astravar/` for production and `app/src/test/java/com/yazsras/astravar/` for tests.
- The original prompt asset and frozen schemas1–5 are unchanged; there are no backend, permission, credential or rules-engine changes.
