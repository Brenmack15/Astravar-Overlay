# Reforge changelog

Source candidate: 3.0.0-rc1/code42, built on the unmerged 2.4.0/code41 branch. Release readiness is tracked separately.

## Character and gameplay

- Added a standalone native character workspace with deterministic, explainable calculations and reviewed adoption of original campaign records.
- Separated permanent scores, advancement, active item effects, current resources and derived projections. Equipment preview is read-only until confirmed.
- Added structured numeric, typed damage, defense and roll-edge effects with attunement, source acceptance, charges, qualifications and stacking controls.
- Added native checks, saves, initiative, skills, spell attacks, attacks/damage, custom formulas and manual-face input with explicit initiation and persisted breakdowns.
- Added guarded spell preparation/casting previews, concentration, slot tracking, conditions, incoming damage, recovery resources and actual-rest confirmation.
- Added identity/background/features/feats/tool/language records, player-confirmed advancement, safe local portraits and autosaved notes with durable conflict-reviewed recovery drafts.
- Added reviewed conversion of eligible confirmed companion attacks, keeping independent companion identity and statistics. Complex source costs, custom critical rules and multiattack remain explicit manual setup.
- Preserved training, effect and condition drafts across recreation with bounded recovery, and retained independent companion attack bonuses plus critical/magical damage flags during editing.

## Product and information design

- Reworked primary navigation around Character, Combat, Inventory, Campaign and More; retained specialized armor tools through explicit secondary navigation.
- Replaced oversized source ability editing with a compact editor and optional legacy/source details.
- Rebuilt NPC reading around overview, facts, open threads and dated milestones; technical provenance remains available in disclosures.
- Added native rich-text rendering, safe Notion mentions, headings, lists, checklists, quotes, tables, readable links and bounded long-content display.
- Added local global search across campaign records and structured spells, attacks, equipment and resources.
- Made local-source attribution automatic for ordinary inventory, NPC and project edits while preserving imported attribution.
- Improved disclosure state accessibility, clear-search actions, card hierarchy, large-font layout and writing previews.

## Data and runtime

- Added an isolated versioned Room store for typed local mechanics, immutable revisioned changes, exact roll outcomes and recovery drafts. Original campaign and weapon schemas/stores remain preserved.
- Added atomic roll/resource outcomes, idempotent command retries, stale-edit rejection, full local workspace backup and guarded merge-only restore.
- Retired the overlay service declaration, display-over-apps permission, special-use foreground permission, launch controls and permission requests. Removed its foreground startup, polling, notification channels, permission watcher and screen receiver; direct lifecycle invocation now stops immediately. Historical view-rendering source/preferences remain for recovery and regression evidence.
- Preserved the authenticated Cloudflare connection and anonymous fictional sandbox boundaries. New workspace records are explicitly local-only, with no silent production cutover.

## Verification infrastructure

- Fixed bounded UI-readiness waits without weakening backup, recovery or conflict assertions.
- Routed test composition through a queued dispatcher supported by the pinned Compose version, preventing Room callbacks from applying test frames on IO threads. The application, database executors and dependency versions are unchanged by this harness fix.

## Evidence and limits

Actual D&D Beyond interface research, a functional parity matrix, 35 product and engineering findings, data audit, versioned engine design, migration notes and verification evidence live in this directory. Numerical tests are synthetic fixtures rather than imports of live campaign state.

A full commercial compendium, complete class/multiclass catalog, automatic interpretation of arbitrary prose, complete companion form policies and an authenticated sync adapter for new mechanics are not implied by this candidate. See `gameplay.md`, `verification.md`, `release.md` and the exact continuation checkpoint.
