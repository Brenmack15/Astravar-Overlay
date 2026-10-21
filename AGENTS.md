# Working on Astravar Overlay

Read docs/ASTRAVAR_RULES.md and docs/DECISIONS.md before changing mechanics. Keep the shared pure Kotlin engine authoritative for both native surfaces. Run `./gradlew :rules:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug` for changes affecting accounting or UI. Use the controlled `overlay-target` app for overlay interaction tests.

Never claim physical Samsung/D&D Beyond coverage from an emulator test. Do not include campaign PDFs, personal exports, secrets, keystores, or signing recovery files in source control. Do not force push, merge main, publish a release, or rotate the release key without the owner's approval. Use feature branches. Unresolved campaign mechanics must stay visibly configurable.

Preserve the explicitly approved automatic attack/damage engine. Do not infer rests, resource refills, campaign time advancement, other creatures' saves or unapproved mechanics. No accessibility automation, scraping, or D&D Beyond synchronization. Preview changes nothing; commit is idempotent and transactional. Preserve unselected payloads except when their core is destroyed. Keep old payload and event snapshots immutable. Never add destructive Room migration fallbacks.

The owner's 17 October 2026 implementation directive authorizes a debug-only HTTPS client for the existing public disposable Cloudflare sandbox. INTERNET permission belongs only in the debug manifest; no login, OAuth, credentials, paid services or new backend. Only fixed fictional sandbox messages may leave the application. Keep the sandbox DB/outbox physically separate from campaign and weapon stores. Never upload local inventory, currency, character, Eek, Connections or Notion records. Preserve durable command IDs, receipt checks, revision conflicts and offline queues. Network/ChatGPT/physical-device success requires direct evidence, not mocks or screenshots of a different client.

The original Astravar 2.0 Preview uses com.yazsras.astravar.v2debug. The UI reconstruction branch deliberately uses com.yazsras.astravar.v2uidebug (2.0.1-ui-rebuild, code21) because the old Preview debug signing key is unavailable; preserve this separate-install identity to keep release, Inventory Beta and the working Preview installed with their data intact. Local data does not cross application sandboxes automatically; restore requires a validated user-selected backup. Keep main/recovery/PRs #1, #2 and #3 unchanged and unmerged. Use original approved artwork and live native text; illustration values and proposed armor formulas never override accepted engine rules.

The owner's 18 October 2026 Remote-Control Test explicitly extends PR #4 with a debug-only, opt-in ability command carried by that same fictional public event channel. This is a narrow exception to fixed-template-only messages: strict versioned commands target only the fixed disposable profile and current installation test session, and limited outcome receipts contain no character sheet. Never target `local-player`/Zerg, initialize unknown abilities, authenticate a sender by its display label, auto-apply an unreviewed command, or upload real records. Apply only after player confirmation through `CampaignRecords.saveAbility`, retaining optimistic revisions, immutable audit and durable deduplication. No existing Room schema or combat logic may change. The explicit `-PastravarRemoteStatTest=true` build uses `com.yazsras.astravar.v2statdebug` (2.0.2-remote-stat-test, code22) to coexist with the previously delivered UI APK whose ephemeral CI signer is unavailable. Keep both existing installations and all their data; never require uninstalling them. Release signing configuration remains untouched.

## Permanent-app adoption (owner correction, 18 October 2026)

The owner reports that the older experimental Android installations were already
uninstalled and contained no data requiring migration. This updates ONLY the
prior handset-installation-preservation assumption; do not touch currently
installed Astravar instances without explicit authorization. Retain historical
commits and draft PRs. Build on the tested 2.0 UI code, with the original
permanent package `com.yazsras.astravar` and the **existing owner release key**.
CI/debug APKs have one-run ephemeral signers and cannot become a reliable update
channel. Never treat uninstalling old versions as authorization to create a new
signing key, copy production data to GitHub, sync Notion automatically, or alter
confirmed D&D rules. Keep campaign-records onboarding read-only/test-only until
backup/restore, scope authorization, and source reconciliation pass.

## Astravar 2.1 implementation authorization

The owner's 18 October master upgrade authorizes an isolated 2.1 feature branch,
additive tested campaign-schema migrations, editable sourced Party profiles,
transactional equipment and approved rules changes. Earlier schema/mechanics
freezes applied to the disposable connectivity experiments, not this upgrade.
Retire Shatter and spare-core operations for new play while retaining historical
decoding, audits and backups. Preserve unknown values and unresolved source
conflicts. Never commit private handoff review JSON, campaign exports or PDFs.
Owner-authenticated sync must fail closed until an authorized backend, device
credentials and acceptance evidence exist. Real import and source-of-truth
cutover still require separate owner approval. Owner signing identity remains
unchanged; temporary test certificates must be identified as such.
The 2.1 owner directive also authorizes HTTPS Internet access in the permanent
application for the authenticated connection architecture. Cleartext remains
disabled. This does not authorize any real campaign path to the anonymous lab;
the synthetic protocol and private bridge remain distinct.

## Owner HP connection authorization (20 October 2026)

The owner's subsequent live-connection directive authorizes narrowly scoped,
authenticated access to an existing selected character's HP through the existing
owner-private Sites service. Pairing must bind the exact existing campaign/profile
identity and HP permission scopes. A connector may read and propose; only explicit
native player approval may change current HP. Preserve unknown HP and all maximum
HP, temporary HP, unrelated profile fields, campaign records and combat mechanics.
Record the approved acceptance adjustment as a connection test, not an invented
encounter, damage roll or campaign event. All destructive/adversarial/failure tests
must use isolated fictional fixtures and automated cloud/emulator verification;
never deliberately fail or corrupt the owner's real phone records. The earlier
fictional-only boundaries still apply to the disposable sandbox and original
fictional protocol, not this separately scoped authenticated HP extension.
Preserve the existing private Site audience, package and owner signing certificate.
Do not request routine manual testing; the only indispensable owner actions are
account/device consent, one verified APK installation and the real HP approval.

## Campaign integration authorization (21 October 2026)

The owner's Astravar 2.2 directive extends the existing private service to typed,
scoped player-known campaign reads and domain transactions. Explicit one-time
campaign enrollment and independently revocable read/write scopes are required;
existing fictional or HP permissions do not silently grant campaign access.
Trusted DM Auto-Sync may apply resolved, explicitly authorized gameplay operations
within standing scopes without a fresh native approval. Manual approval remains
available. Both backend and Android enforce grants, revisions, command identity,
atomicity and immutable receipts; report pending delivery until Android commits
and independent receipt/readback agree. Preserve unknown values and all existing
stores, backups, mechanics and source identities. Real-data migration/cutover
still requires separate owner authorization. Test failures only against isolated
fictional data. Keep private exports and signing material out of source control.
The existing owner-private Site and plugin identity must be reused; the separate
legacy DM Bridge and disposable public sandbox remain unchanged. Routine source,
CI, included hosting deployment and release preparation are authorized. Physical
installation, account pairing, standing grants and platform consent remain owner
actions and must never be reported complete without evidence.
