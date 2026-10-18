# Astravar 2.0 design implementation limits

This branch is a native implementation preview, not a claim that every approved Astravar 2.0 visual or interaction has been reproduced and accepted. [Asset provenance](ASTRAVAR_2_ASSET_PROVENANCE.md) records the exact incorporated originals, source specifications and preservation conflicts.

## Verified asset integration

- The front armor illustration and all ten damage particles are original recovered PNGs, copied unchanged into Android resources. Their hashes match the originals.
- Artwork does not contain functional UI labels or changing values. Native Android controls and text provide interaction and accessibility.
- Existing Cinzel and Inter font files are reused. The approved Libre Baskerville attack-name face has not been recovered or added; do not describe the complete typography specification as implemented.
- Particle originals are opaque images on black, not transparent sprites or animations. A native frame may present them without altering the source images.

## Current native implementation

| Area | Implemented source behavior | Boundary |
| --- | --- | --- |
| Armor artwork | The unchanged front illustration uses `ContentScale.Fit` in a 180 dp hero area. The earlier decorative tagline was removed. | This is a native composition, not a completed full-screen mockup reproduction. |
| Character overview | A manual local status editor records sourced name, level, HP, temporary HP and AC. Blank values remain unknown. | These reference values do not change weapon calculations or infer current campaign resources. |
| Ability scores | Six native tiles form three columns × two rows. Raw score is the large value; total modifier is in a lower oval. Tapping opens a centered editor with separate score and magical modifier, base/total previews, source, notes, Save and Cancel. | Sourced local records and revision guards preserve existing values. No ability entry automatically reconfigures the combat engine. |
| Armor entries | Active Element and Core Systems use equally weighted side-by-side panels with a shared minimum height. Active Element opens the native element wheel. | Current Core Systems entry opens the existing Cores/Payloads/History controls; the separate read-only capability reference library is still outstanding. |
| Element selection | Nine original particle medallions surround central Force. Selection closes the dialog and updates stored weapon preferences; unavailable elements follow the current engine's locks. | Force uses existing neutral behavior; the proposed extra Force dice are not active. Existing multi-element capacity is preserved. |
| Navigation | Primary destinations are Astravar / Inventory / Campaign / More. Astravar exposes Armor / Combat / Systems; Campaign exposes visible Inventions / Connections / Eek tabs, with Connections initially selected. Systems exposes Cores / Payloads / History. | This incorporates the expanded campaign scope rather than copying the older four-tab concept literally. Connections is a visible campaign tab, not a hidden notebook. |
| Theme | Midnight-navy surfaces, restrained 4% background texture, 5% metal texture and 16 dp cut-corner panels reuse existing native components and packaged Cinzel/Inter fonts. | Palette and geometry alignment do not establish complete visual parity or device acceptance. |

## Design fidelity still requiring review

The native overview/editors and radial selector now exist; they should not be described as missing. Their screenshot fidelity, small-screen sizing, accessibility, keyboard behavior, selection persistence and physical-device behavior still require measured acceptance. The approved wheel order is Fire / Cold / Lightning / Acid / Poison / Thunder / Necrotic / Psychic / Radiant clockwise from top, with Force at center.

The character status panel currently emphasizes HP, AC and level, while the original armor reference describes equal AC / Astravar Attack / Spell Save DC summary tiles. Saved weapon statistics remain available separately. Do not claim exact correspondence with the approved character-overview composition or automatically link manual character values to weapon rules.

Independent transparent attack icons and scalable frames, the approved Libre Baskerville attack-name face, final compact Roll Button artwork, and completed full-screen composites remain unavailable or unverified. The six armor attack profiles, spell-family/variant selection workflow and data-driven read-only capability library are not completed merely by adding the overview or wheel. Do not fabricate unsupported gauntlet attacks or a Force Neutral upgrade to make a reference look functional.

Remaining visual checks include metallic angular ornamentation, restrained glows, typography, 48 dp touch targets, long labels, large system text, reduced motion and portrait/landscape layouts. Avoid duplicate navigation, slogans, decorative filler and DM-only live counters. Preserve the existing functional fallback when decorative assets or effects are unavailable.

## Preservation boundaries

- Keep the current Kotlin engine authoritative. The Notion Discharge example and proposed Force specialization must not silently replace existing dice, range, radius, consumption or saved profile rules.
- Preserve immutable old roll/event snapshots, existing weapon Room migrations, artwork, overlay functionality, release package/signing identity and saved data.
- New item descriptions, connection facts, companion notes and equipment records do not activate gameplay mechanics, refill resources, advance time or establish hidden campaign facts.
- No real Notion campaign import, cloud authority transfer or production synchronization is established by visual integration. A synthetic bridge test is distinct from those capabilities.
- Emulator checks cannot establish physical Galaxy S26 installation, data preservation, visual acceptance or D&D Beyond coverage. Report actual test/device evidence separately.

## Acceptance still needed

Review native screenshots against the exact approved component references; verify responsive/accessibility behavior; exercise selection, cancel/save, roll failure, result breakdown and persistence paths; and obtain physical-device acceptance. Continue to identify missing assets and mechanics conflicts explicitly rather than filling gaps with substitute art or invented rules.
