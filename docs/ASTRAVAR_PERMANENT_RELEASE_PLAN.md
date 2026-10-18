# Astravar permanent release — groundwork and acceptance gates

Prepared on 18 October 2026 from verified GitHub PR #4 head
`28c890a9af83a5e83f33d639f0fed2734af35a0f`.

## Owner's newest direction

The owner reports having removed the previous experimental Android apps and
confirms there was no prior app data to keep. Reuse the existing Astravar 2.0
source, approved visuals and mechanics. Establish one lasting Android identity
and add campaign-record support following the reviewed Notion model. This does
not authorize importing or editing the canonical private Notion campaign records,
publicly distributing real data, changing mechanics, or creating a replacement
signing key. The current `remote-stat-test-v1` profile and Cloudflare Worker
session are fictional tests, not Zerg's authoritative character.

## Permanent Android identity and release checks

- Production `applicationId`: `com.yazsras.astravar`. Leave the legacy debug
  `com.yazsras.astravar.v2statdebug` identity test-only. The current build-23 APK
  uses an ephemeral GitHub Actions signer and cannot become the permanent
  in-place-update series; it can remain a noncanonical visual prototype.
- Locate the **existing owner RSA-4096 PKCS12 signing identity** in an
  owner-controlled secure workflow. The historical public certificate is
  `4c5cb0113cae5d0dee2619ee30f5b3c593d4aa897e1fa38dee7ed5561ec4e847`.
  Confirm this with an actual signed owner APK; do not upload the private key or
  its passwords to GitHub source, ChatGPT chat or test artifacts.
- Set a new monotonically increased `ASTRAVAR_VERSION_CODE` after inspecting
  all prior owner releases; set production `ASTRAVAR_VERSION_NAME` explicitly.
- CI may exercise optimized release **only** with an isolated disposable key;
  never present that CI APK as the owner-signed permanent release.
- Product acceptance requires `assembleRelease`, `testReleaseUnitTest`,
  `lintRelease`, `:rules:test`, protected-source audits, genuine Android package
  inspection, signing-certificate fingerprint check and 16 KB alignment.
- Test a subsequent update with the **same** signer and higher version code on
  an emulator with fictional data. Check saved inventory, Room migrations,
  history, receipts, backups and restored records. Only then offer it for phone use.

## Image packaging repair implemented by this patch

The original ten `particle_*.png` resources were JPEG-encoded internally.
This proposed repair changes their filename extension to `.jpg` only, retaining
identical bytes, resource base identifiers and approved artwork. The
`ui_foundation_sha256.json` ledger remaps exactly ten resource paths while
keeping all original digests. The verification script checks JPEG magic,
end-of-image marker, bytes and that no misleading `.png` path remains.
No combat or database code is modified.

## Campaign Records development sequence

The approved live Notion staging ledger and [Campaign Data Model v2](https://app.notion.com/p/3fc4d9aa716c813cb832f5a1c2e611dc)
remain the authority for current campaign notes. **Preserve what is already
implemented:** the existing CampaignLedgerDb v2 already supports item records,
inventions, connections, companion records, an immutable audit trail, and
strict JSON export, read-only import preview, and merge-only restore with
conflict rejection. CampaignBackupPageV2 already exposes confirmation in the UI.
The first real-app acceptance gate is therefore to **retest** these features
on a permanent candidate with synthetic records, not rewrite them. The next
expansion targets the remaining currency/checkpoint model, source-ID mapping,
authoritative inventory/campaign source reconciliation, and authenticated sync.
Respect unknown-vs-zero quantities and preserve provenance, discoveries,
research, relationships, Eek and established rules. DM/AI changes require
source attribution, optimistic revisions, verified receipts, deduplication,
and explicit approval for unresolved or consequential events. Never infer stats,
coin balances or inventory ownership.

Do **not** silently turn on remote sync, move private Notion records, or
reinterpret proposed rules as approved mechanics. A private, authenticated,
zero-cost bridge and author-approved canonical-source cutover are separate gates.

## What this handoff does NOT do

It does not sign, install or deliver a permanent APK, does not rotate keys,
does not create/merge a PR, and does not migrate Notion campaign data. A GitHub
HTTP 403 on branch creation remains the publication blocker until the owner
reconnects a write-capable integration or uses authorized Work Cloud Browser.
