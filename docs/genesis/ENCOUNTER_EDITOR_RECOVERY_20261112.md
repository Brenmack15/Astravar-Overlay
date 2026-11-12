# Saved encounter setup editor recovery — 12 November 2026

Base: `c6dc9a9eec55b5526346f51dd4f091baeb61d155`.
This isolated checkpoint was prepared through GitHub while the existing execution environment was unavailable. No local worktree, PR, verification branch, signing input or campaign data was modified.

## Correction

The existing-setup editor initialized participant membership revisions in an asynchronous effect. Readiness came from a delegated Compose State read while the participant list had been captured earlier in composition. A first IO emission could therefore pair loaded readiness with the previous empty list and permanently assign the editor's missing-member sentinel, -1, to real saved members. Saving would remain blocked until removing and re-adding them.

`GenesisEncounterEditor` now reads the loaded/actors pair once per composition. Initialization, membership checks and captured list values share that immutable snapshot. The editor still freezes each chosen membership revision: a later membership change requires explicit review and does not silently refresh the draft.

## Added actual UI regressions

Two tests extend `GenesisEncounterUiTest` without changing its existing journeys:

1. Open a persisted setup whose participants have authority revisions above 1, hold the actual repository readiness gate, then release it. Edit the title, manual order and initiative, recreate the saved editor state, save, reopen Room, and verify the exact encounter identity/revision, unknown initiative, unchanged hero/source NPC and valid complete backup.
2. Open a persisted setup, make a draft edit, then change the participant's authority revision. Verify Save becomes unavailable, the existing setup is unchanged and no actor mechanics are modified.

The separate Character routing fix at `c66f2738adc354c73911b08712d5161fd55b3684` is intentionally not duplicated here. The existing full-shell encounter navigation failure still requires that shared fix and its own verification. No native test assertions were bypassed.

## Verification status

**Untested recovery checkpoint.** Source inspection only: no Kotlin compilation, Gradle, UI test, emulator, APK build, installation or signing was possible locally. Run the complete encounter UI class and existing navigation/character routing tests after safely integrating the required checkpoints. Existing source-review manifests and immutable signing/schema boundaries remain unchanged.
