# One-tap campaign conflict verification

`Campaign import` → **Verify all saved conflicts** runs an offline, read-only audit of the campaign ledger. It does not require choosing another source file and does not call ChatGPT, Cloudflare, Notion, D&D Beyond, or a gameplay engine.

## What is checked

- Every retained reference body against its stored SHA-256; normalized Notion page aliases within a campaign.
- Stored relationship origins and targets, including unresolved targets or now-available targets with historical unresolved flags.
- Inventoried Notion aliases that point to more than one physical item; possible repeated item names are *not* assumed to be duplicates.
- History events referring to absent items, and multiple wallet identities for one holder/campaign. No currency is combined or inferred.
- Every active import receipt against its exact retained source payload digest, campaign identity, source count, reference/relationship readback and typed imported records.
- Stored pre-import recovery backups using bounded byte-chunk digest verification. A backup must be verified before any existing guarded recovery workflow; auditing does not recover or repair it.
- Original unresolved source notes and external-only attachment references, as notices rather than assumed failures.

Results distinguish **integrity problems** (missing/corrupted evidence), **decisions needed** (ambiguous identities, missing targets or differing current records), and **source notices** (unresolved notes, unembedded attachments or historical flags). Exact matches need no player action and count toward verified import receipts. A later legitimate edit can make an imported record differ from its source; that is a review item, **not** an instruction to overwrite the edit. A saved receipt's original whole-database hash may differ after unrelated subsequent work, so this audit checks the imported records individually instead of claiming the entire old database snapshot is unchanged.

The report is bounded to 150 displayed findings while preserving category totals and an omitted count. **Copy diagnostic summary for ChatGPT** copies only explicit report metadata; it excludes structured page bodies, properties and wallet balances, but source note text and names may still be private. Copying does not transmit it. The owner decides whether and where to paste it.

This feature is not a conflict-resolution write engine. It does not silently pick between player corrections, duplicate items, historical or current stats, conflicting wallet snapshots, or Notion revisions. The existing archive preview/backup/confirmation, revision checks, immutable receipts, transaction boundaries and independently verified import/rollback paths are unchanged. It also does not establish a production phone connection, grant new MCP scopes, import private data, or approve a source-of-truth cutover.

## Verification

Run `:app:testDebugUnitTest --tests com.yazsras.astravar.data.CampaignArchiveTest` for isolated fictional tests including a clean read-only audit, missing relationships, modified body hash, ambiguous inventory/wallet identities, and a missing retained source. For changes to campaign/accounting UI also run `:rules:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug`. Never test damage or rollback against a real owner campaign.

## Future scoped automation

A later authorized bridge could expose the audit as an authenticated, read-only, permission-scoped MCP operation and let ChatGPT propose source-linked decisions. A proposed resolution would still need an exact campaign/record identity, source evidence, current revision, backup, appropriate standing grant or native approval, immutable receipt and independent readback. This document does not authorize that integration or any automatic overwrite.
