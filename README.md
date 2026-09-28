# Astravar Overlay

**Delivery status: signed release candidate; overlay runtime acceptance is blocked.** Rules/database tests and native builds pass. Local emulation has not produced a passing overlay test; no Galaxy S26 or D&D Beyond verification is claimed. See the actual test report.

Native, offline Android companion for the custom Astravar arcane pistol. Kotlin + Jetpack Compose, a shared JVM rules engine, transactional Room storage, DataStore preferences, and a genuine `TYPE_APPLICATION_OVERLAY` foreground service. This is not a website or D&D Beyond integration.

The app starts without cores, attunement, known slots, scrolls, or loaded spells. Confirm a starter profile or explicitly choose the separate labeled demo. Every shot has a free preview, an idempotent commit that spends resources, and manual result entry. The main app and floating panel share the same campaign and history.

Features include typed elemental/spell damage, actual hit/critical/miss entry, separate direct-impact and blast resolution, per-creature saves and defenses, core inventory and replacement, game-time recovery, payload snapshots, editable provisional policies, crafting logs, corrections, JSON backups, and player-safe session exports. No Internet permission, account, analytics, backend, automatic dice, accessibility service, scraping, or sheet synchronization.

## Build and test

JDK 17, Android platform 36, build tools 35.0.0. The genuine Gradle 8.13 wrapper includes its distribution SHA-256.

```sh
./gradlew :rules:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
./gradlew :overlay-target:assembleDebug :overlay-target:assembleDebugAndroidTest
```

Release packaging requires the owner's durable signing identity through environment variables. It fails explicitly if signing is not provisioned. See [build/signing](docs/BUILD_AND_SIGNING.md). The debug package ends in `.debug`; it does not overwrite a real campaign in the release app.

## Documentation

- [Mechanics and authority](docs/ASTRAVAR_RULES.md)
- [Implementation decisions and toolchain](docs/DECISIONS.md)
- [Actual validation and remaining checks](docs/TEST_REPORT.md)
- [Phone-only installation](docs/ANDROID_INSTALL.md)
- [Build and signing](docs/BUILD_AND_SIGNING.md)
- [Handoff / capability status](docs/HANDOFF.md)

The supplied, not-yet-run workflow is configured to run rules/database tests, lint, debug builds, and API 35/36 overlay tests against a controlled second app. The existence of a workflow is not proof it ran; the test report records actual execution. Release publication and merging to main are owner actions, never automatic.

Unofficial companion; not affiliated with Wizards of the Coast, D&D Beyond, or Samsung. No third-party rulebook, character sheet, proprietary spell description, or D&D Beyond branding is included. The owner-supplied Astravar image is used as the visual reference and bundled artwork.
