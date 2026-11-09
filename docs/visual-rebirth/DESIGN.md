# Astravar — Visual Rebirth

## Baseline and product problem

The implementation continues `cb7de7964421bf38325be4c885b4ef37baeaa460`
(3.1.0-rc4, build 48, PR #13). All earlier unmerged development is retained.
The exact baseline passed native run 42611821428 and Reforge run 42611821392.
Those historical results are baseline evidence, not verification of this change.

The owner's supplied Astravar recording (87.83 seconds) and D&D Beyond recording
(78.40 seconds), standalone D&D Beyond inventory/spellbook images, and exact
baseline emulator captures were recovered and visually examined. Private
recordings and campaign values are deliberately excluded from this repository.

The baseline places a tall hero, setup links, imported-record notices and wrapped
tabs before live statistics. Repeated rounded purple panels give controls, lore,
technical records and gameplay values similar visual weight. Companion profiles
repeat identity and demand navigation through source-oriented material.

## Design direction

An illustrated field journal: ink navy and charcoal, ivory text, warm metal for
primary actions, blue for spellcasting and mint for health/recovery. Purple is a
limited accent. Native Cinzel headings provide identity; Inter carries gameplay
and forms. No generated text or controls are embedded in illustrations.

- Compact identity and prominent HP, AC and initiative precede setup controls.
- Four stable primary destinations: Character, Inventory, Companions, Campaign.
- Character sections use a single flat selector; resources/history have direct
  entries. No nested More selector for routine play.
- Rows and dividers organize skills, spells and inventory. Distinct surfaces are
  reserved for important summaries, inspected objects and review dialogs.
- The entire actionable row has a minimum 48 dp target. Labels wrap at larger
  text sizes; essential information never depends on color alone.
- Source provenance, diagnostics and backups stay accessible through named
  advanced sections. Save, review and conflict safeguards remain explicit.
- Unknown values say Not set or Unknown. Neither art nor example data initializes
  gameplay records. Companion rosters derive from saved identities.
- Campaign opens on actual saved journal context with routes to people, projects
  and checkpoints. It does not fabricate a current quest or narrative.
- Original atmospheric artwork supports identity and empty states. Everyday
  combat is not pushed down by an ornamental banner.

## Engineering boundaries

Presentation and navigation change; repository operations, rules calculations,
optimistic revisions, immutable receipts, explicit source adoption and draft
recovery remain authoritative. No Room schema migration, campaign overwrite,
store consolidation, remote deployment or owner-device operation is included.
The permanent package and owner signing configuration are preserved.

The original campaign ledger and playable equipment remain distinct stores.
Transfers and reviewed copies must identify their destination. A simpler screen
must not imply synchronization that does not exist.

Complete campaign JSON backups still have their established size limits. Scoped
selection does not establish oversized full-backup safety.

## Acceptance evidence

The delivery record must distinguish historical baseline captures, current
emulator screenshots, automated unit checks, and physical-device acceptance.
Use fictional fixtures for native journeys and retain source commit, screenshot
names, font scale and test results. Reinspect the result visually before calling
the candidate accepted. A compiled APK alone does not satisfy the assignment.
