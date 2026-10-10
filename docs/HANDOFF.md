# Astravar 1.7.0 handoff

Started from clean `24509e2` (1.6.0/code 9) on `feat/weapon-focused-interface`; the full visual implementation is on `feat/premium-instrument-ui`. Existing work was preserved without repository reset, engine replacement or rules rollback. Previous release records remain in `HANDOFF_1.6.0.md` and `RELEASE_NOTES_1.6.0.md`.

## Presentation and assets

The Compose application retains four primary destinations: Weapon (default), Cores & Payloads, History and More. `ArcaneDesign.kt` centralizes licensed Cinzel/Inter typography, repeating graphite and brushed-metal tiles, beveled/chamfered surfaces, instrument medallions, decorative dividers, illuminated buttons, segmented controls, charge indicators and navigation. `Common.kt` applies the same materials to existing forms, choices and secondary actions. No UI screenshot or static mockup is used as an interactive screen.

`Dashboard.kt` composes the approved transparent pistol derivative over azure illumination and engraved geometry, then displays actual configured statistics, core/payload status, roll modes, elements and results. `Inventory.kt` keeps all established storage, charging, purging, source, template and crafting operations inside the new core/payload card system. `CombatComponents.kt` provides quick selectors and compact bounded History with expanded saved dice. `AppScreen.kt` routes the header settings action and the grouped control room; `InventoryTabs` and `ControlRoomPage` are production components shared with rendered screenshot fixtures.

The owner-supplied pistol remains the weapon reference. Its transparent derivative is optimized as `astravar_cutout.webp` with a 1024-pixel maximum edge; the original approved image remains in source. Small reusable texture tiles and original vector icons cover the actual implemented elements, resources and navigation. Cinzel and Inter are distributed under SIL Open Font License 1.1, with full notices retained in the APK. See `ASSET_LICENSES.md`.

## Native overlay

The overlay remains an Android View hierarchy in a `TYPE_APPLICATION_OVERLAY` foreground-service window. `ArcaneSurface.kt` draws cached metal gradients, framing, engraving, ready lights, sigils and result dials, with small shared texture tiles and a cached downsampled pistol illustration. It does not retain an Activity/Service in its material cache or allocate a View for every die.

Standard dimensions remain 174×52 dp collapsed, 300×364 dp expanded and a 420 dp cap for detailed/secondary views before scaling and display-bound clamping. Dragging, orientation-specific saved positions, focusable editors, notification Show/Stop, permission checks and session lifecycle remain the existing implementation. The visible window bounds, rather than a hidden full-screen surface, determine its footprint. Styling does not add looping animation or combat-triggering lifecycle callbacks.

## Mechanical and persistence boundary

This release changes presentation only. The shared Kotlin engine, transactional repository, Room v3 and DataStore architecture remain authoritative. No new schema, current rules version or configuration migration is introduced. Nd12 Primary Force, Md8 selectors, Discharge 3N and Shatter 21N are unchanged. Direct-impact criticals do not double explosions; selectors and spell contributions remain separately typed. Source spending, spell-slot reconciliation, core exhaustion/destruction, crafting and explicitly confirmed recovery remain established mechanics.

The 1.6.0 simplification remains: new ordinary rolls do not use target AC and keep unconfirmed/potential damage; only actual natural 1/20 outcomes are automatic. Historical AC/defense fields remain decode-compatible and immutable. Serialized encounter/turn fields continue supporting optional weapon action budgets, with no general encounter dashboard or round counter. Confirming the player's next weapon action window does not advance game time.

Newest-20 SQL retention, pre-observation streaming legacy cleanup, compact roll snapshots, bounded Undo and backup limits remain unchanged. No history reset, campaign replacement, count reset or resource refill accompanies this visual update.

## Version and validation

Package `com.yazsras.astravar`, versionName `1.7.0`, versionCode `10`. Existing owner signing material stays outside git; no new signing identity is created. Final signed-APK checks are recorded in `TEST_REPORT.md`, not inferred from the version configuration.

Actual Compose and native View screenshot fixtures render the production components for visual review; see `VISUAL_ACCEPTANCE.md` for before/reference/after comparisons. Test counts, lint/build execution, signing/alignment verification and unexecuted physical-device checks belong in `TEST_REPORT.md`. No physical Samsung/D&D Beyond acceptance is claimed from headless rendering.
