# Working on Astravar Overlay

Read docs/ASTRAVAR_RULES.md and docs/DECISIONS.md before changing mechanics. Keep the shared pure Kotlin engine authoritative for both native surfaces. Run `./gradlew :rules:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug` for changes affecting accounting or UI. Use the controlled `overlay-target` app for overlay interaction tests.

Never claim physical Samsung/D&D Beyond coverage from an emulator test. Do not include campaign PDFs, personal exports, secrets, keystores, or signing recovery files in source control. Do not force push, merge main, publish a release, or rotate the release key without the owner's approval. Use feature branches. Unresolved campaign mechanics must stay visibly configurable.

No automatic rolls, resource refills, campaign time advancement, backend, Internet permission, accessibility automation, scraping, or D&D Beyond synchronization. Preview changes nothing; commit is idempotent and transactional. Preserve unselected payloads except when their core is destroyed. Keep old payload and event snapshots immutable. Never add destructive Room migration fallbacks.
