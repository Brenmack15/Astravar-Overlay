# Astravar v1.7.0 visual acceptance

## Sources and rendering method

The required `Astravar_UI_Redesign_Reference_Pack.zip` was extracted and reviewed. It contains the actual bronze-and-azure Astravar pistol, six concept mockups, the 44.14-second current-app recording at 1080 × 2340, and its contact sheet. The mockup rifle and fictional core/payload names are presentation examples only.

The before images include unaltered video frames extracted at 0.7 seconds (result), 6 seconds (cores), 10 seconds (payloads), 16 seconds (history), and 34 seconds (More). Previous actual v1.6.0 native graphics captures provide the Weapon and overlay baselines. Their campaign values differ from the recording; comparison concerns the presentation, not equal roll totals.

New screenshots are captured from the real production Compose components and real overlay service view builder with API 35 Robolectric native graphics. The capture draws the actual Android view tree onto an Android `Bitmap` after Compose is idle. No reference image is embedded as an interactive screen, and no screenshot is an AI-generated substitute for a running component. The same application components are assembled with the production header and navigation. Deterministic rule fixtures supply real `Engine.plan` / `DiceRoller` results and real persisted-model snapshots.

This is an available Android rendering/test environment, not a physical Samsung or emulator session. It cannot verify Samsung font rasterization, system-overlay permission behavior, D&D Beyond interaction, gesture navigation, or real-device performance. Those limitations remain explicit.

## Required captures

| Screen | Production component | New evidence filename |
|---|---|---|
| Weapon | `Dashboard`, `AstravarHeader`, `WeaponNavigation` | `weapon.png` |
| Core selection | `CoresPage`, `InventoryTabs` | `cores.png` |
| Payload selection | `PayloadsPage`, `InventoryTabs` | `payloads.png` |
| History | `CombatHistoryPage` | `history.png` |
| Attack result | `Dashboard` → `RollResultCard`, including fixed roll-mode controls and Roll Again | `result.png` |
| Expanded overlay | `OverlayService` view builder | `overlay-expanded.png` |
| Collapsed overlay | `OverlayService` view builder | `overlay-collapsed.png` |
| More / Control room | `ControlRoomPage` | `more.png` |

Additional captures include the true natural-20 result (`critical.png`), true natural-1 result (`natural1.png`), overlay result (`overlay-result.png`), a narrow 360 dp Weapon layout (`weapon-360dp.png`), and the reduced-scale capsule (`overlay-collapsed-85percent.png`).

## Review findings

All eight required screens and the five additional states were rendered and directly inspected after the aggregate debug/release tests passed. All thirteen PNGs were independently decoded completely with Pillow; the capture helper also checks successful PNG compression and the IEND trailer, then publishes the file by atomic rename. The earlier truncated result artifact was replaced by a verified complete capture.

| Screen | Reference comparison and final review |
|---|---|
| Weapon | The approved pistol is isolated against an engraved blue instrument halo inside a beveled bronze frame. Four dimensional stat wells, core/payload modules, Cinzel headings, Inter functional labels, and a fixed illuminated action replace the flat v1.6 card. The Force type fits at both tested widths. Normal/Advantage/Disadvantage stay visible above Roll Attack on both 412 dp and 360 dp phones. Detailed firing/element selection remains in the scrollable operation area. |
| Cores | Framed inventory modules use distinct mechanical crystal medallions and explicit Equipped, Ready, and Unavailable states. Actual capacity and exhaustion information are visible. The disabled install action remains legible; charge/overwrite and crafting controls remain available. Long names fit without pushing status outside the card. |
| Payloads | The actual approved template snapshot supplies the name, typed 3d8 expression, separate overcharge, source, and one-use state. A compact Deselect control replaces the oversized wrapped selection button discovered in the first render. Tabs have matching heights and selected materials. Template and scroll controls remain genuine operations. The fixture does not add Chromatic Orb to the installed application's catalogue. |
| History | Three actual deterministic result snapshots are visible on the first 412 dp screen. Fine beveled frames, outcome-colored medallions, prominent attack totals, dice formula, typed damage chips, timestamps, and details controls replace the plain stacked cards. Only the actual natural 20 or natural 1 receives its special indicator. The newest-20 storage rule is unchanged. |
| Attack result | A large illuminated attack dial and separate framed damage components follow the result concept. The complete production Dashboard capture includes the visible fixed Roll Again action and mode controls. Ordinary results say Potential Damage and defer the hit to the DM; true criticals use gold and natural 1 uses red. Individual-die detail is available by scrolling, instead of permanently expanding large pools. |
| Expanded overlay | The compact 300 × 364 dp instrument panel contains the actual pistol, live expression, attack/save statistics, mode controls, core/payload state, and illuminated Roll Attack. Thin metallic rims replaced overly heavy initial borders. Functional labels and wrapped expressions remain readable. No fictional rifle or game scene is in the service UI. |
| Collapsed overlay | A 174 × 52 dp horizontal engraved capsule contains Astravar's emblem, readiness text/ring, and expansion control. At 85% scale it measures 147.5 × 51 dp in the native render, preserving a physical 48 dp target instead of clipping a 48 dp child inside a 44 dp window. |
| More | All five actual utility links use textured framed control-room modules, mechanical medallions, Cinzel titles, readable Inter descriptions, and clear navigation cues. No abandoned Combat/Encounter destination appears. |

The reference identity is implemented through optimized artwork, actual texture resources, native geometric engraving, multi-layer metallic frames, licensed display/function fonts, glow accents, and live data. The mockups' fictional names, rifle, generic hit prediction, oversized overlay, and decorative example outcomes are intentionally absent. The final captures demonstrate a substantial visual transformation from the supplied plain current-app recording. They do not establish pixel-perfect equivalence with an AI concept or physical-device acceptance.

The evidence bundle contains the thirteen original new PNGs, extracted recording frames, eight before/after pairs, seven concept/reference pairs, a nine-panel native-screen overview, and source metadata. Comparison sheets only uniformly rescale full original images into labeled columns; they do not paint over or synthesize any UI content.

## Interaction checks

Presentation tests check that the Weapon primary roll action stays visible, enabled, and at least 48 dp high; all four destination labels remain visible; shot options can be reached by scrolling; approved payload dice and selected state are present; and all five secondary Control room utilities remain reachable. They inspect 412 dp and 360 dp phone layouts. No Armor Class controls or fictional mockup component names appear.

The existing History test still renders twenty retained 210d12 Shatter entries, checks newest-first ordering, and opens details only on request. The existing mechanical tests remain the authority for randomness, resources, migrations, saves, criticals, and retention; screenshots are not a replacement for those tests.

The aggregate run passed 104 rules tests and all 43 Android tests in both debug and release variants. Initial visual inspection led to corrections for clipped Force labels, mismatched tab sizes, oversized payload actions, excessive inventory/history density, hidden narrow-phone mode controls, thick overlay rims, and reduced-scale capsule clipping. Final evidence was regenerated after these corrections. Physical install-over, Samsung system bars/keyboard, live overlay dragging/permissions, D&D Beyond interaction, and real-device frame-rate/memory measurements were not executed in this environment.
