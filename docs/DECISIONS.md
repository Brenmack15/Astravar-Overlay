# Engineering decisions

## Toolchain

Pinned stable versions: Android compile/target API 36; min API 26; AGP 8.13.2; Gradle 8.13; Kotlin/Compose compiler 2.2.21; Compose BOM 2025.12.01; Room 2.8.4; DataStore 1.2.0; KSP 2.2.21-2.0.4; Activity Compose 1.12.2; Lifecycle 2.10.0; core-ktx 1.17.0; JDK 17. No previews or dynamic dependency versions.

AGP's official compatibility table specifies Gradle 8.13 and JDK 17, and supports API 36.1. Kotlin 2.x uses its matching Compose compiler plugin. Room 2.8.4 requires at least API 23; this app's real overlay requirement sets the minimum to 26.

Primary references checked during implementation:

- https://developer.android.com/build/releases/agp-8-13-0-release-notes
- https://developer.android.com/develop/ui/compose/setup-compose-dependencies-and-compiler
- https://developer.android.com/jetpack/androidx/releases/room
- https://developer.android.com/develop/background-work/services/fgs/service-types
- https://developer.android.com/develop/background-work/services/fgs/changes
- https://developer.android.com/reference/android/view/WindowManager.LayoutParams
- https://developer.android.com/studio/publish/app-signing

The build environment initially had only a JRE. A checksum-verified portable Temurin JDK 17.0.20.1, checksum-verified Gradle 8.13 distribution, Android command-line tools 19.0, platform 36, build tools 35.0.0, and emulator tools were installed in scratch. The newest command-line bootstrap attempted an additional unavailable download; the pinned classic SDK manager worked. Java was configured to use the supplied environment proxy and its trusted CA, without disabling certificate verification. These environment-specific paths/settings are not part of the source project.

## Small modules

- `rules`: pure Kotlin typed domain, validation, preview/commit, damage, core handling, JSON backup model, deterministic tests.
- `app`: Compose full app, Room repository/event log, DataStore preferences, native Android service-window overlay, SAF imports/exports.
- `overlay-target`: controlled second Android application for outside-touch, keyboard, rotation, and overlay-hiding tests. It is not bundled into the release app.

The overlay uses native Android Views, not Compose, inside its service window. Consequently no service-hosted Compose lifecycle or saved-state owner is needed. Rendering does not own resource rules; both surfaces call the same repository and engine. Text entry explicitly enables a focusable editor; ordinary controls and the collapsed crystal are not focusable. The window is only as large as the visible panel, not a hidden full-screen surface.

## Foreground session

`specialUse` is declared with `FOREGROUND_SERVICE_SPECIAL_USE` and a manifest subtype explaining the user-started tabletop control overlay. No conventional foreground-service type accurately describes this function. The service is started only from a visible Activity or an explicit ongoing-notification interaction; it is `START_NOT_STICKY`. No boot receiver, background resurrection, accessibility service, or disguised media/data-sync behavior.

The app requests display-over-apps permission only after an explicit tap. Notification permission is also user-driven; a visible ongoing notification with Show/Stop is required by the product's session-start flow. Denial does not trigger a loop. Overlay permission is checked before adding/updating a window and periodically during a session. Screen lock hides the panel; unlocking does not silently restore it. Opening this app's permission flow stops the session. Android/protected applications may hide overlays; this is respected.

Position is saved independently by orientation and clamped using system-bar/cutout bounds. IME insets reduce panel height while editing. Buttons are at least 48 dp before user scaling; collapsed crystal includes the recent-result state. Labels remain textual; colors are decorative. Settings expose display scale and SAF-selected artwork. Device diagnostics report actual model, Android/API/build, and app version.

## Accounting and persistence

Room stores versioned campaign resources, a separate newest-20 compact roll table, and a bounded accounting Undo journal whose resource snapshots omit all roll lists. Every mutation executes in a Room transaction with a unique command ID. Replays of a committed ID return the existing state; competing stale shot previews fail revision checks. Spend occurs at commit, independent of later hit/miss. Pending plans include the profile and payload snapshots needed to resume after process death.

Corrections restore the previous snapshot and append a new explicit event. Retained dice are not rerolled; expired history cannot be restored. Game time advances only by player commands. A Long Rest log does not assume elapsed time or refill unknown counters. Database version 3 has explicit tested 1→2→3 migrations; no destructive fallback exists.

Backups use strict versioned JSON. They validate active references, IDs, dice/levels and resource values; isolate invalid historical records; and bound nesting and retained data. Legacy input is streamed (up to 512 Mi characters) into an 8 MiB retained-data envelope. Import previews counts and requires a current backup before replacement. Import is atomic and idempotent; the newest 20 imported rolls replace the recent-roll window, and duplicate legacy archives are discarded. Preferences remain device-local. This JSON size limit is a technical import limit, not a campaign resource rule.

## Security / distribution

No Internet permission or broad storage permission. SAF is used for chosen files/images. No advertising, analytics, backend, login, AI service, OCR, screen recording, or touch injection exists in the product. Emulator UI automation belongs only to tests. No rulebook/character-sheet attachments are copied to the repository. Supplied weapon artwork is the only attached image bundled.

CI pins action commit SHAs and uses read-only repository permissions. No job receives the owner signing secrets. The feature-branch workflow uses a disposable CI key to test the optimized release and uploads APK/test evidence. The delivered candidate is signed privately with the existing owner key. It never creates a public Release, merges, or changes visibility.


## Runtime verification boundary and recovery

The overlay window uses a display-associated window context, following Android's Context API contract. Black-box UI tests live in the independent companion app so R8-optimized product dependencies cannot break the instrumentation runner. A first in-product release runner failed with missing `androidx.tracing.Trace`; that test arrangement was removed rather than weakening product optimization.

The original 1.0.0 environment had no `/dev/kvm`. Its API 35 software emulator timed out; an API 36 ATD image had repeated system-service ANRs and omitted System UI. These attempts did not establish overlay acceptance.

Recovery preserved the feature branch's existing automatic-dice redesign. The original scratch checkout and hung-process log were unavailable, and no stale Android/Gradle process remained. A missing recent-history helper and a non-onboarded test fixture were repaired. Local dependency resolution required correcting inherited proxy settings while retaining TLS verification. Slow downloads were stopped at an explicit deadline; a later bounded Android build passed. Accelerated GitHub Actions emulation replaces the failed software-emulation path. Early hosted failures (missing sdkmanager, conflicting SDK paths, and an AVD path mismatch) were diagnosed from logs and fixed. Build, boot, install and instrumentation operations now have explicit time limits, with failure evidence retained. Consult TEST_REPORT for final measured results. Emulator checks do not establish physical Samsung/D&D Beyond behavior.

## Automatic dice and combat flow

The explicit player correction in ASTRAVAR_RULES supersedes the original manual-only requirement. Injectable dice make rules tests deterministic; production uses SecureRandom. Both surfaces call the same transactional fire operation, which persists raw faces, selected d20, modifiers, typed components, critical eligibility and costs together. Other creatures' saves are never rolled. Ordinary attacks permit unknown AC. Discharge/Shatter keep confirmation and per-creature resolution. Optional manual overrides live in advanced settings.

Combat uses a dark charcoal/gold/frost theme, compact weapon status, one-tap attacks, result cards and quick core/payload selectors. Main and overlay share persistent selections and history. The original latest-20 presentation has been superseded by v1.5.1 storage retention; older rolls are now permanently discarded. Version 1.1.0 increases version code to 2 without changing the Room schema or signing identity.

References: https://developer.android.com/reference/android/content/Context#createWindowContext(int,android.os.Bundle), https://developer.android.com/studio/test/managed-devices, https://android-developers.googleblog.com/2021/10/whats-new-in-scalable-automated-testing.html.


## Force rules v2 (application 1.5.0)

The owner's 2026-10-07 correction supersedes d10 and all level/additive emergency calculations. A versioned profile supplies `primaryForceDieSides` from one current `PrimaryForceRules.DIE_SIDES` constant. Current emergency counts use 3N and 7×3N. A plan snapshots the final blast count and its complete profile; legacy shot profiles receive rules version 1, active profiles upgrade to version 2. Event/archive campaign profiles stay historical; Undo normalizes only the restored active profile. No SQL schema or destructive migration is needed.

Explosion selector components reuse `Portion.BLAST`, giving each type its existing independent save-halving path. They never join Direct Impact's critical eligibility. Payloads retain `Portion.PAYLOAD` and their zero-on-success rule. Slider and combat previews share Engine component construction. Large pools use existing compact summaries and expandable text per component, not one View per face. Generic Dice validation permits all 210 canonical Shatter dice while the unrelated template-input limit remains 200.

## Bounded history (application 1.5.1)

This correction supersedes the earlier unlimited journal/archive policy. Only the newest 20 rolls are retained. Room v3 separates compact immutable roll snapshots from current resource state and from bounded accounting Undo snapshots, which omit roll lists. Accounting observers read metadata only. Stable roll ordering uses stored sequence then ID; event ordering uses revision then ID. Both descending indexes support bounded reads and SQL cleanup. SQLite insert triggers and Room transactions enforce the shared limit even for batched writes. The single shared repository state flow assembles a coherent Campaign for existing UI/overlay consumers.

Before any legacy observer starts, SQL prunes old events, then 8 KiB BLOB chunks stream oversized campaign/event fields. A streaming JSON filter retains only the newest valid shot window before decoding. Malformed historical items can be discarded without replacing active campaign resources. Duplicate archived journals are removed. Backup import follows the same streaming policy; export has no duplicate roll copies. Operation retries within the retained journal remain idempotent and stale shot revisions remain rejected. Undo is limited to retained accounting operations and does not resurrect expired rolls.

No Force, selector, emergency, critical, save, slot or core rule changes accompany this stability release.

## Weapon control interface (application 1.6.0)

The owner's redesign supersedes the older Combat/Inventory/Encounter layout. Compose now has four primary destinations: Weapon (default), Cores & Payloads, History, More. A restrained charcoal, antique-gold and blue design uses cached linework, painted sigils, system serif headings and readable system body text. The supplied updated pistol artwork replaces the prior approved image. No fictional concept weapon/core/payload names are introduced. Setup forms are disclosed on demand. Weapon's Roll action stays at the bottom of the screen above navigation; roll details expand only when requested.

The native View overlay retains its service-window, permission, drag, position, notification, keyboard and shared-repository architecture. Its standard collapsed bar is 174×52 dp and expanded weapon surface 300×364 dp before display scaling; secondary/result views cap at 420 dp height and scroll. Status and action controls retain accessible text and minimum touch targets. No animated shader, full-screen texture or per-die view allocation is added.

New DiceRoller output strips AC even when passed a legacy RollOptions instance. Legacy-only validation continues to recognize old AC-derived results, and historical defense snapshots are untouched. DataStore migration removes only the obsolete armor_class preference. No Room schema reset, campaign replacement, count migration or history expansion accompanies the redesign. The legacy encounter/turn fields now back only the optional weapon action-budget controls; no general encounter UI remains. Starting the player's next weapon action window never advances the recovery clock.

Screenshot fixtures render actual Compose/native View implementations under Robolectric; they are review evidence, not claims of physical overlay operation on Samsung or D&D Beyond.
