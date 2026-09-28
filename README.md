# Astravar Overlay

**Version 1.1.0 candidate: automatic dice and a simplified combat interface.** See the test report for executed checks and device coverage. No physical Galaxy S26 or D&D Beyond verification is claimed.

Native, offline Android companion for the custom Astravar arcane pistol. Kotlin + Jetpack Compose, a shared JVM rules engine, transactional Room storage, DataStore preferences, and a genuine `TYPE_APPLICATION_OVERLAY` foreground service. This is not a website or D&D Beyond integration.

The app starts without cores, attunement, known slots, scrolls, or loaded spells. Confirm a starter profile or explicitly choose the separate labeled demo. Ordinary combat is attack → result → roll again. Attack and damage dice are automatic and saved with resource costs in one idempotent transaction. The main app and floating panel share the same campaign, selectors, raw dice and history.

Features include normal/advantage/disadvantage, natural 1/20 handling, optional target AC, typed elemental/spell damage, separate direct-impact and blast resolution, per-creature saves and defenses, quick core/payload selection, the latest 20 shots backed by the complete audit history, core inventory and replacement, game-time recovery, payload snapshots, editable provisional policies, crafting logs, corrections, JSON backups, and player-safe session exports. Other creatures' saves remain DM input. Manual dice overrides are optional advanced settings. No Internet permission, account, analytics, backend, accessibility service, scraping, or sheet synchronization.

## Build and test

JDK 17, Android platform 36, build tools 35.0.0. The genuine Gradle 8.13 wrapper includes its distribution SHA-256.

```sh
timeout -k 10s 540s ./gradlew --no-daemon :rules:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
timeout -k 10s 180s ./gradlew --no-daemon :overlay-target:assembleDebug :overlay-target:assembleDebugAndroidTest
```

Release packaging requires the owner's durable signing identity through environment variables. It fails explicitly if signing is not provisioned. See [build/signing](docs/BUILD_AND_SIGNING.md). The debug package ends in `.debug`; it does not overwrite a real campaign in the release app.

## Documentation

- [Mechanics and authority](docs/ASTRAVAR_RULES.md)
- [Implementation decisions and toolchain](docs/DECISIONS.md)
- [Actual validation and remaining checks](docs/TEST_REPORT.md)
- [Phone-only installation](docs/ANDROID_INSTALL.md)
- [Build and signing](docs/BUILD_AND_SIGNING.md)
- [Handoff / capability status](docs/HANDOFF.md)

The bounded workflow runs rules/database tests, debug/release lint and builds, and API 36 overlay tests against a controlled second app. It tests optimized release code using an ephemeral CI key; the delivered candidate uses the existing owner key. The test report records actual execution. Release publication and merging to main are owner actions, never automatic.

Unofficial companion; not affiliated with Wizards of the Coast, D&D Beyond, or Samsung. No third-party rulebook, character sheet, proprietary spell description, or D&D Beyond branding is included. The owner-supplied Astravar image is used as the visual reference and bundled artwork.
