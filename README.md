# Astravar

PR #4 also contains the **opt-in Remote Updates experiment**. Config → Remote Updates creates an empty disposable stat profile, accepts strict versioned single-ability proposals for this installation's test session, and requires Apply or Reject. Approved changes use the existing campaign revision/audit/receipt transaction. Nothing targets Zerg or the normal `local-player` profile. See [the exact Galaxy S26 test procedure and protocol](docs/REMOTE_STAT_TEST.md).

The current CI download is **Astravar-2.0-Stat-Test-apk**, built with `-PastravarRemoteStatTest=true`: `2.0.2-remote-stat-test` (22), `com.yazsras.astravar.v2statdebug`, launcher **Astravar**. It installs alongside the prior UI Rebuild because that CI APK's ephemeral signer is unavailable. Keep all existing apps installed. The ordinary build without that property retains the UI Rebuild identity described below. No release signing configuration changes. The supplied launcher portrait is used for adaptive and legacy icons; Config → Help & diagnostics shows the actual version and build. See [identity and permanent update strategy](docs/ASTRAVAR_IDENTITY.md).

Native Android armor and campaign companion on the verified 2.0 engineering baseline (`7c1e42c0f2b3ea639aa675b9109f8d2ddbcee528`). Development branch: `feat/astravar-2.0-ui-rebuild`. The existing integrated preview and draft PR #3 remain recoverable and unmerged.

## Native experience

One primary navigation bar: **Armor · Combat · Campaign · Config**.

- **Armor:** compact character overview, six ability tiles, Active Element wheel and Core Systems reference. Character and companion values stay unknown until explicitly recorded.
- **Combat:** selected attack, supported payload/element controls, compact formulas, latest result, roll mode and automatic rolling. Detail and selection interactions open focused overlays. The preserved Kotlin engine determines dice, resource costs and adjudication boundaries.
- **Campaign:** Inventory, Inventions, Connections and Eek are one tap from a compact hub. Records open detail/edit sheets. Stable identities, sourced research, equipment links, confirmed crafting transactions and audit history remain intact.
- **Config:** core and spell management, rules, resources, roll history, floating overlay settings, separate campaign/weapon backups, diagnostics and fictional Cloudflare sync.

Original artwork and live native text are reused. Distinctive missing artwork is not fabricated. Read the [UI rebuild implementation and limitations](docs/ASTRAVAR_UI_REBUILD.md). The [older preview provenance](docs/ASTRAVAR_2_ASSET_PROVENANCE.md) and [older preview design limits](docs/ASTRAVAR_2_DESIGN_LIMITS.md) describe the previous implementation; their screen layouts and outstanding-work lists are historical.

## Preservation and data boundaries

Combat and automatic dice, native system overlay, weapon Room schema 3 and its migrations, campaign Room schema 2 and its migration, saved snapshots, transactional material consumption, audit records, JSON validation/restore, and the separate sandbox HTTP/outbox implementation are preserved. The source gates verify 33 recovered files and 79 preview foundation files plus the owner signing block.

Illustration values never confer mechanics. The six approved attack illustrations are selection references; unsupported armor attacks remain unavailable. The read-only armor capability reference does not reconfigure the independently saved weapon engine. Manual character/companion records do not change combat modifiers or invent HP, charges, ownership, rests or campaign outcomes. No real Notion campaign import is performed.

## Installation

This branch targets **Astravar 2.0 UI Rebuild**, `2.0.1-ui-rebuild`, code **21**, package **`com.yazsras.astravar.v2uidebug`**. It installs separately from the release (`com.yazsras.astravar`), Inventory Beta (`com.yazsras.astravar.debug`) and original 2.0 Preview (`com.yazsras.astravar.v2debug`). **Keep all working apps installed.** It does not update the original Preview or automatically share its data.

Export source data before transferring it. Campaign and weapon JSON use separate tools; campaign restore previews validated missing records and rejects conflicting IDs. The fictional outbox is not included in either backup. See [Samsung installation and transfer instructions](docs/ASTRAVAR_2_PHONE_INSTALL.md) and [build/signing guidance](docs/BUILD_AND_SIGNING.md).

## Public fictional sync

The optional debug client retains the existing disposable Cloudflare origin and fixed `astravar-public-disposable` / `sandbox-merchant-0001` scope. Its database, pending commands, receipts and cursor remain separate from campaign/weapon records. Retries preserve original IDs and payloads; conflicts remain visible. The original connectivity page sends only fixed fictional templates. The separately opted-in Remote Updates experiment accepts strict disposable-profile commands and sends only bounded command/session/outcome acknowledgements; no sheet is serialized.

**No real inventory, character, Eek, Connections, currency or Notion data is uploaded.** No paid service, new authentication project, analytics, D&D Beyond scraping or accessibility automation is introduced. Experimental private MCP-tool failures are separate from the native client. Host tests alone do not establish new APK/device or ChatGPT MCP interoperability.

## Build and verify

JDK 17, checksum-pinned Gradle 8.13, Android SDK 36 / build-tools 35.0.0; Room 2.8.4.

```sh
python3 -m unittest discover -s scripts/tests -v
python3 scripts/verify_v2_protected_source.py
python3 scripts/verify_ui_foundation.py
./gradlew --no-daemon --console=plain :rules:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug -PastravarRemoteStatTest=true -PastravarScreenshots="$PWD/validation/screenshots"
```

`SandboxLiveSyncTest` is skipped by default. Explicit `ASTRAVAR_LIVE_SANDBOX=true` enables its single fixed fictional live write, durable-command recovery, exact retry and read-back test. Other HTTP tests use deterministic transports.

Use the exact commit’s Actions artifacts and delivery verification report for current build/test results, native renders and device limitations. Previous preview reports are historical evidence for their named APKs.

After a successful UI-branch run, Actions provides **`Astravar-2.0-Stat-Test-apk`** containing `Astravar-2.0-Stat-Test-debug.apk`, SHA-256, APK metadata, signature/alignment checks and source commit. The separate `android-verification` artifact contains reports and native UI captures. `RemoteStatLiveTest` requires explicit `ASTRAVAR_LIVE_REMOTE_STATS=true` (or the workflow's `remote_stat_live` input); it writes only a disposable proposal and its limited acknowledgement. It is a host test with simulated approval, not physical phone proof. No production release or automatic PR merge occurs.

See [mechanics](docs/ASTRAVAR_RULES.md) and [decisions](docs/DECISIONS.md). Unofficial tabletop companion; not affiliated with Wizards of the Coast, D&D Beyond or Samsung.
