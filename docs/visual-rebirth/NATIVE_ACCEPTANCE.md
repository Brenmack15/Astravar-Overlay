# Visual Rebirth native acceptance

These are executable coverage targets, not claims that a run has passed. The
accepted candidate must link the exact Git commit, successful workflow and
downloaded screenshots. All records below are isolated fictional fixtures.

The existing ordered player journey is followed by
`VisualRebirthJourneyInstrumentationTest.resourcesCompanionGearJournalAndLargeText`
before update-preservation snapshots and archive recovery. No assertion is
replaced by a screenshot. The new stage verifies resource use, companion gear,
edited campaign NPC persistence and large-text navigation using ordinary native
controls. Its configuration changes are reset in `finally`.

| Requested surface | Native screenshot target |
| --- | --- |
| Character selection | `player-first-character-selection.png` |
| Main character sheet | `player-first-created-sheet.png`, `rebirth-character-font-1.0.png` |
| Character statistics | `rebirth-character-statistics.png` |
| Combat | `player-first-companion-direct-combat.png`, `player-first-spellbook-concentration-after-restart.png` |
| Spellbook | `player-first-spellbook-filtered.png`, `rebirth-spellbook-font-1.0.png` |
| Inventory | `player-first-inventory.png`, `same-key-update-inventory.png`, `rebirth-inventory-font-1.0.png`, `rebirth-inventory-font-1.3.png`, `rebirth-inventory-font-2.0.png` |
| Equipment management | `player-first-equipment-review.png`, `rebirth-companion-equipped-after-restart.png` |
| Companion selection | `rebirth-companion-selection.png` |
| Companion profile | `player-first-generic-companion.png` |
| Campaign dashboard | `rebirth-campaign-dashboard.png`, `rebirth-campaign-font-1.0.png`, `rebirth-campaign-font-1.3.png`, `rebirth-campaign-font-2.0.png` |
| Relationships | `player-first-companion-relationship.png`, `player-first-reopened-companion.png` |
| Forms and dialogs | `player-first-health-dialog.png`, `player-first-advancement-preview.png`, `rebirth-campaign-npc-after-restart.png` |

Additional captures use font scales 1.3 and 2.0 at 1080 × 2400 pixels / 432 dpi
(400dp width) for the character sheet, gameplay actions, spellbook, campaign-item
inventory and campaign journal. The inventory and journal visits are read-only;
they return to the same character sheet and recheck its health. Native
assertions require an on-screen 48dp Roll dice target and access to all six flat
character sections. Existing acceptance retains landscape, keyboard, legacy
armor, same-key update, large archive, duplicate, stale and rollback checks.

The native selector discovers the actual horizontal section viewport, observes
complete enabled bounds and dispatches one tab selection. Hidden labels are
found through native swipes. It does not insert clicks, change saved state
directly, manufacture screenshots or suppress assertion failures.

An emulator run does not establish physical Galaxy S26 acceptance. Artwork and
Robolectric images are not substitutes for these native screenshots. Review the
captured pixels for clipping, hierarchy, density, contrast and consistency
before delivering the application candidate.
