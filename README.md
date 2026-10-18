# Astravar Overlay — 2.0 preview

Native Android armor and campaign companion built on recovered v1.7.0 and the validated Inventory Beta. Preserves the Kotlin combat engine, automatic dice, weapon Room schema 3 and migrations, native floating overlay, historical snapshots, fonts and original artwork.

## Native experience

- **Astravar:** approved armor artwork, manual character overview/six ability scores, original elemental particles, armor/combat/systems navigation, familiar cores, payloads and roll history.
- **Inventory:** persistent item identity, images, unknown quantities, categories, optional measured weight, descriptive properties, sourced research, stable equipment/project/connection links and audit.
- **Campaign:** Inventions with stages/requirements and confirmed transactional material consumption/output; Connections with facts and meaningful milestones; Eek with separate manually sourced resources and accepted homebrew.
- **More:** separate campaign/weapon backups, validated schema-1/schema-2 import preview and merge-only restore, synthetic connectivity lab, existing rules, overlay settings and diagnostics.

Illustration values and proposed descriptions never confer mechanics. Existing engine numbers win over unresolved mockup examples. No Notion campaign records are imported. HP, slots, currency, ownership and companion resources are not invented or refilled.

## Public fictional connectivity lab

The optional debug HTTPS client connects to the existing disposable Cloudflare Worker. Only fixed fictional templates scoped to `astravar-public-disposable` / `sandbox-merchant-0001` are accepted. Its Room database, outbox, receipts and cursor are physically separate from campaign/weapon records. Commands survive restart; retries preserve IDs/payloads; stale revisions become conflicts. No authentication is added to this deliberately public prototype.

**Real inventory, character, Eek, Connections, currency and Notion data are never uploaded.** No analytics, paid API, paid hosting activation, D&D Beyond scraping or accessibility automation. A private production connection is separate work. HTTP tests alone do not establish ChatGPT MCP or physical Galaxy interoperability.

## Installation and data boundaries

Debug preview: `com.yazsras.astravar.v2debug`, `2.0.0-preview`, code `20`. Coexists with production `com.yazsras.astravar` and Inventory Beta `com.yazsras.astravar.debug`. Do not uninstall either working application. Android sandboxes are separate; explicitly preview/restore a chosen export to transfer supported data. Campaign and weapon backups remain separate. Production signing configuration is preserved; this branch delivers debug APKs only.

## Build and verify

JDK 17, checksum-pinned Gradle 8.13, Android SDK 36/build tools 35.0.0; Room 2.8.4.

```sh
python3 -m unittest discover -s scripts/tests -v
python3 scripts/verify_v2_protected_source.py
./gradlew --no-daemon --console=plain :rules:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

`SandboxLiveSyncTest` is explicitly skipped by default. `ASTRAVAR_LIVE_SANDBOX=true` permits its fixed fictional live write/read-back. Other network tests use deterministic transports. Reports distinguish host tests from physical S26 validation.

Actions uploads `Astravar-2.0-debug-apk` containing the installable APK, SHA-256, binary metadata, signature/alignment checks and source commit. No release publication or automatic PR merge.

See [asset provenance](docs/ASTRAVAR_2_ASSET_PROVENANCE.md), [design limits](docs/ASTRAVAR_2_DESIGN_LIMITS.md), [mechanics](docs/ASTRAVAR_RULES.md), [decisions](docs/DECISIONS.md), and [signing](docs/BUILD_AND_SIGNING.md).

Unofficial tabletop companion; not affiliated with Wizards of the Coast, D&D Beyond or Samsung.
