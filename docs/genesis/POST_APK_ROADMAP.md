# Astravar Project Genesis — Post-APK Roadmap and Deferred Intentions

**Purpose:** Preserve the complete long-term Astravar 4.0 Project Genesis direction while the active development session focuses on shipping **one installable, verified APK for owner testing**. This file is a durable handoff, **not** a claim that every feature is complete and **not** an instruction to expand the current release.

**Status snapshot:** November 12, 2026. This roadmap synthesizes Brendan's Project Genesis instructions, the GitHub-backed Genesis audits and checkpoints, and the verified release handoffs. The currently running Codex session must add any further internal intentions it can actually recover; those unrecorded thoughts cannot be reconstructed from GitHub alone.

**Repository:** Brenmack15/Astravar-Overlay  
**Relevant work:** PR #15 (Project Genesis), PR #14 (prior Visual Rebirth), multiple protected Genesis checkpoint branches.  
**Roadmap branch:** docs/astravar-genesis-post-apk-roadmap-20261112, created separately from active release work.  
**Starting source snapshot:** d12bdfec951acb3525ecb9bd280dcf4b85cc3b3a. This SHA records a starting point, not a permanent instruction to ignore newer commits.

---

## 1. Two separate tracks

### Track A — Finish and deliver the current APK FIRST

The current release is a *testing milestone*, not the final realization of every Genesis feature.

1. Determine the freshest actual release worktree, branch, uncommitted work, CI artifacts, and checkpoint. Preserve recoverable work before doing anything risky.
2. Continue from the newest trustworthy published state. Do not restart Project Genesis, discard a worktree, force-push, reset, clean, or overwrite existing source or campaign data.
3. Resolve the outstanding native-test verification failure on the latest candidate without weakening tests. Earlier checkpoints that passed must be kept intact and may be used to isolate regressions.
4. Run the full appropriate tests for the *exact commit intended for delivery*: debug/release test suites, lint, rules/Python/bridge checks, and native Android UI/instrumentation tests. Record which checks ran and which did not; emulator passes are not physical-device passes.
5. Build the release APK and verify package name, version, target/minimum SDK, certificate, APK integrity, installation compatibility, and data preservation. The original owner release signing certificate must match the installed app; an emulator/test-signed artifact is not a drop-in owner update.
6. If the original owner signing materials are unavailable, report that explicitly and request the authorized signing handoff. Never substitute a new key and call it compatible.
7. Deliver the final APK or a concrete download link, checksum, signing fingerprint, release commit, installation/update instructions, and an honest list of limitations. Keep PR #15 and existing checkpoints. Do not imply Galaxy S26 testing took place unless it actually did.
8. Ask Brendan to test the APK before expanding scope. Collect structured feedback about crashes, navigation, visual usability, campaign selection, character sheet, data, gestures, creation, inventory, and companion flows.

**Stop condition:** The current APK release is finished only when a reproducible release artifact with verified signing/update compatibility has been delivered to the owner. Passing tests or merely building a debug APK is not that condition.

**No scope creep:** Do not implement backlog items in Sections 3–5 merely to make the roadmap look complete. Correct release-blocking defects and preserve everything else for Track B.

### Track B — Resume full Project Genesis AFTER owner testing

When Brendan has installed and evaluated Track A, use his feedback and the test results to rank and implement the remaining Genesis work in short, independently verifiable vertical slices. Preserve unfinished tasks in this document.

---

## 2. The original Genesis product vision

Astravar is intended to become a premium, cohesive, native Android Dungeons & Dragons character-management and play application capable of progressively replacing D&D Beyond for its owner.

Non-negotiable experience:

- One connected system for campaigns, characters, companions, NPCs, encounters, spells, features, resources, inventory, equipment, currency, notes, references, and homebrew.
- Create a record once and expose it wherever needed without hidden duplicate copies or inconsistent authorities.
- Swipe horizontally between Character Sheet, Actions, Spells, Inventory, Features, and Companions, with clear page indicators, sensible Back behavior, preserved scroll/state, and accessible tap alternatives. Add an understandable global navigation scheme.
- Put frequently used actions within easy reach: roll an attack, cast a spell, change HP, use an ability, rest, inspect inventory, and switch or inspect companions.
- Rebuild creation/editing for characters, items, companions, NPCs, encounters, and campaigns with context-aware defaults, validation, understandable labels, and reliable save/cancel behavior.
- Make inventory and equipment real workflows: inspect, search, filter, sort, mark favorites, equip/unequip, attune/unattune, move/transfer, adjust quantity, and distinguish ownership from mere references. Support optional drag/long-press and equivalent tap controls.
- Offer a consistent dark-fantasy visual system—deep navy, charcoal/black, refined gold accents, premium typography, legible contrast and touch targets—throughout ordinary and advanced screens.
- Keep established D&D mechanics and accepted homebrew intact. Do not silently reclassify characters, rewrite the campaign, or turn narrative metadata into mechanical bonuses.
- Preserve local-first reliability, provenance, owner/campaign identity, app signing identity, existing data, and a safe backup/restore story, even across a redesign.

This vision is **not** satisfied by a single polished screen, an unused mockup, a green compilation, or a partial schema layer. Progress should be measured in real end-to-end tasks a player can complete.

---

## 3. Deferred implementation backlog

The statuses below are deliberately conservative: existing code may partially implement an item, and later commits may have advanced it. Inspect current source and relevant checkpoint evidence before marking any item done.

### P0 — Correct connected data and preserve existing campaigns

- [ ] Audit every entity and relationship for a single defined authority: character, campaign membership, owner, companion, item/physical copy, spell, feature, resource, currency, NPC, encounter, and homebrew source.
- [ ] Complete the connected create/read/update/delete and cross-navigation flows without stale shadow copies or accidentally duplicated physical inventory.
- [ ] Preserve source provenance, stable IDs, historical records and user-entered data across upgrades, exports, imports, and restores.
- [ ] Resolve any legacy/modern dual-authority seams using safe, tested migration/cutover behavior. Keep a rollback or recovery plan; never destructive-fallback a database migration.
- [ ] Verify large legacy campaign/profile access (including Zerg Hasma) without an application-wide oversized export during simple selection. The historical 32-million-character preview limit is a regression risk to explicitly test.
- [ ] Demonstrate restore/import compatibility with representative real-sized, anonymized fixtures. Do not publish real campaign backups or signing credentials.

### P1 — Character play and rules fidelity

- [ ] Finish any incomplete character creation, ancestry/background/class/subclass, feature acquisition, feats/ASI, spellcasting, leveling, multiclass and progression behaviors required by the supported rules version.
- [ ] Verify character sheet calculations (ability modifiers, proficiency, skills/saves, AC, initiative, HP, movement, attacks, spell attack/DC, slot and charge/resource use). Respect pinned values and authorized overrides.
- [ ] Unify actions, dice, spells, rests, conditions, equipment modifiers, and combat accounting with the same state shown on the character sheet.
- [ ] Keep homebrew first-class and editable with explicit attribution and override rules. Differentiate manual, derived, legacy, and reference-only fields.
- [ ] Make companion control and switch/inspect flows reliable without silently importing or altering linked campaign NPCs or independent characters.

### P1 — Complete campaign, creation, and encounter workflows

- [ ] Finish and simplify campaign creation/editing, membership invitations/assignments (where relevant), participant navigation, ownership, and unlink behavior.
- [ ] Provide guided create/edit for item, companion, NPC, character, campaign and encounter with appropriate defaults, validation, field dependencies, dirty-state protection and source transparency.
- [ ] Review the recently integrated encounter work for full end-to-end use: create encounter, add/remove/reorder participants, resolve links to characters/NPCs/companions, initiative, damage, conditions, encounter completion, and persistence.
- [ ] Remove orphaned or duplicated entry points; unify legacy screens with current connected flows without deleting user data.

### P1 — Inventory, equipment, and crafting

- [ ] Make the item inspect and equipment management flows complete across sheet, combat, companions, and campaign inventory; check item quantity and physical ownership.
- [ ] Finish attunement, equip/unequip, transfer, move, filter, sort, search, favorites, and accessible drag/long-press alternatives.
- [ ] Verify crafting consumes the correct materials exactly once, respects resources and item provenance, handles failure/undo safely, and produces connected inventory records.
- [ ] Keep equipment effects consistent with combat and rest state; define precedence for magical armor, shields, special items, and homebrew.

### P1 — Navigation, interaction design, and visual coherence

- [ ] Make horizontal gesture navigation robust across the six core character views, including input conflicts with scrolls/lists, visible page position, Back behavior, deep links, and state preservation.
- [ ] Provide a coherent shell/home screen, predictable global navigation and quick actions without long paths to ordinary play tasks.
- [ ] Rework confusing dialogs, densely packed forms, awkward field labels, unreachable controls, inconsistent design tokens, and redundant information.
- [ ] Evaluate one-handed Samsung Galaxy S26 interaction, Android system bars/insets, keyboard behavior, touch targets, text scaling, talkback/screen readers, color contrast, and reduced motion.
- [ ] Keep rich text, linked notes, reference views, and permitted Notion integrations usable with a local-first/offline fallback. Never silently pull or overwrite external data.

### P2 — Quality, resilience, and polish

- [ ] Improve responsiveness with many characters, campaign relationships, locations, inventory copies, and custom source records. Replace eager loading where profiling justifies it.
- [ ] Add fast search, safe filtering, loading/empty/error states, useful confirmation and undo patterns.
- [ ] Add meaningful visual polish and tasteful fantasy micro-interactions only after the connected daily workflows are intuitive and stable.
- [ ] Maintain release automation, regression fixtures, traceable coverage, backup/restore validation, and signed update checks.

**Priority order is a starting hypothesis, not permission to implement everything at once.** Promote actual owner-tested blockers above cosmetic or speculative work.

---

## 4. Vertical-slice acceptance tests

Each follow-on milestone should be demonstrated in the app, not solely through unit tests.

1. **Returning owner:** Upgrade the installed APK under the original key; open an existing campaign and Zerg Hasma without deleting data, changing identities, or running whole-database export accidentally.
2. **Character day-to-day:** Swipe Sheet → Actions → Spells → Inventory → Features → Companions; roll/use a resource/rest; return to the same character and see consistent state.
3. **Creation:** Create and edit a new character/item/companion/NPC/encounter/campaign; navigate to them from their linked contexts; cancel/undo safely; reopen after force-stop.
4. **Ownership:** Move, equip, transfer, attune, and adjust an item quantity; every consuming screen reflects the same physical copy and derived effects.
5. **Encounter:** Create, populate, run, save, leave, and resume an encounter with correctly linked participants and state.
6. **Crafting:** Start with known materials, craft once, verify exact material accounting and newly linked output; test failure and app restart.
7. **Backup and scale:** Export/import a representative large fixture and verify source identity, relationships, notes, homebrew and resource balances survive.
8. **Accessibility:** Complete ordinary play using visible taps without requiring a long-press or drag; check screen reader, font scaling, and supported Android versions.

Record exact tests, target API/device, artifact hash, before/after data assertions, and any exclusions.

---

## 5. Hard constraints and workflow safety

- Keep Android package ID com.yazsras.astravar and compatibility with the original owner-signed certificate (recorded fingerprint suffix …e847). Do not create a differently signed upgrade and claim it can update the installed app.
- Preserve campaign data, owner identity, provenance, items, custom mechanics, accepted homebrew, and in-progress work. Never delete, silently migrate destructively, replace existing backups, or expose personal campaign data in public CI.
- Preserve PR #15, historical branches/checkpoints and the working release process. No force push, destructive clean/reset, unauthorized merge, or release promotion.
- Keep new capabilities connected to actual persisted state; do not pass mocked/static previews off as production features.
- Clearly distinguish local tests, GitHub CI, emulator runs, physical Galaxy S26 runs, owner-signed release checks, and actual install/update checks.
- If access to signing secrets or personal data is blocked, report the blocker and request the correct authorized handoff. Do not invent a workaround.

---

## 6. Source and recovery pointers

Start by reading the most current corresponding files **on their actual branches**, not assuming these filenames always indicate the latest execution:

- docs/genesis/SESSION_RECOVERY.md
- docs/genesis/RESUME_VERIFICATION_20261112.md
- docs/genesis/CONNECTED_AUTHORITY.md and CONNECTED_UI.md
- docs/genesis/DATA_AUDIT.md and INTERACTION_AUDIT.md
- docs/genesis/CREATION.md and STORAGE_CUTOVER.md
- docs/genesis/CRAFTING_CHECKPOINT.md
- docs/genesis/ENCOUNTER_CHECKPOINT.md and newer combined/encounter recovery checkpoints
- docs/genesis/NATIVE_PLAN.md and latest CI/workflow results
- docs/ASTRAVAR_RULES.md, docs/DECISIONS.md and repository AGENTS.md before changing rules or mechanics

Recorded November 12 evidence (verify anew before acting):
- Earlier candidate a86ab0fa87ce passed full CI and Android-native verification.
- A later candidate d12bdfec951acb3525ecb9bd280dcf4b85cc3b3a passed full CI but did not pass its native-test workflow.
- This document is not a claim that either candidate has an original-owner-signed, physically installed release APK.

---

## 7. Codex-owned remaining intentions — update before release handoff

The current Codex execution should append the **exact** unfinished intentions it can recover from its current context, worktrees, notes, TODOs, tools and recent commits. Do not invent or silently discard work that was intended but never published.

For each intention, add a row here:

| Intended feature or fix | Current evidence/implementation | Exact missing work | Dependencies/risks | Acceptance test | Priority |
| --- | --- | --- | --- | --- | --- |
| Owner-signed upgrade acceptance | Owner APK code50 is signed and statically validated; test-signed native upgrade passed on a86 | Complete original-owner49→50 emulator check if separately authorized | Automatic approval rejected encrypted APK upload; no signing key or campaign data is in the prepared envelope | Exact signed APK installs over code49; UID, records and later write survive | P0 verification |
| Stable encounter text fixture | d12 native run43256864350 failed on manual→Manual text mismatch; focused field exists and IME autocorrection was enabled | Isolate deterministic keyboard input without weakening exact assertions | Preserve failed evidence and d12 host-test readiness fix | Exact text, save/reopen and unchanged actor mechanics pass | P1 |
| Owner device acceptance | a86 passed19 native tests on Android16/API36 | Evaluate Galaxy S26/Android17 navigation, Back/keyboard, campaign opening and persistence | No physical-device claim; preserve real records | Owner feedback on actual installed release | P1 |
| Remaining Genesis product work | Existing sections3–4 retain progression, historical editors, inventory/container/favorites and wider crafting/encounter scope | Choose one small slice after owner feedback | Scope remains frozen for release delivery | Relevant end-to-end acceptance test | P1–P2 |

### Release handoff — November 12, 2026

- Deliverable: Astravar-4.0.0-genesis1-code50-owner-signed.apk; package com.yazsras.astravar. [Owner download](https://chatgpt.com/library?file_id=libfile_758c61e4f78c81919956e5cbcd7e960f&account_id=personal).
- APK SHA-256: `2c14bd7ec186122a94b5273e072ea262efeae60b00af96c65ecc21c49ce0e9b1`. Original owner certificate SHA-256: `4c5cb0113cae5d0dee2619ee30f5b3c593d4aa897e1fa38dee7ed5561ec4e847`.
- Exact release source: `feat/astravar-project-genesis-accessible-navigation-20261112`, `a86ab0fa87ce47a4f99550dcfa8fe01ed0e22928`. Latest development source remains `d12bdfec951acb3525ecb9bd280dcf4b85cc3b3a` on the journal-readiness branch; it differs only by12 host-test readiness lines. No newer production work was dropped.
- Reused passing runs43251800831 and43251800807: rules166, debug929 pass/3 skip, release915 pass/17 skip, Python320, bridge134, native19 pass; lint0 errors. Thirteen independent package/SQLite readbacks reproduced. Backup/restore, migration6→9 and persistence checks passed with fictional records and CI signatures. No expensive suite was rerun.
- The signed APK has the same210 ZIP entry contents as the tested product; only signing changed. minSdk26, targetSdk36, non-debuggable and16KiB alignment verified.
- Original-owner upgrade run43323086598 did not reach installation: automatic approval rejected uploading the encrypted APK envelope. It is not a passing owner-key installation result. Local emulator fallback is unavailable. Explicit transfer approval is required; a fresh attempt may be needed after recipient expiry.
- Four recovered worktrees were clean. No new uncommitted production source needed preserving. PR15 and all existing checkpoints remain unchanged; earlier unavailable original worktree history remains described in recovery documents.
- Emulator-covered flows include swipes/taps, quick actions, connected creation/editing, equipment/companions, restricted crafting, manual encounter tracking, large-campaign selection and backup/import. Full progression/multiclass, some historical editors, general containers and inventory drag/reorder/favorites remain partial. Complete connected backup remains limited to64MiB and does not include weapon backup, settings, connection configuration, some drafts or external portrait bytes.
- Next product milestone must be selected from Brendan's hands-on feedback. No further feature development is authorized by this handoff itself.

Also record:
- The newest protected source commit and branch at APK handoff.
- The exact APK/release artifact and certificate status (or why unavailable).
- Any local-only progress that could not be recovered, named honestly.
- Which major user-visible flows are complete, partially implemented, broken, or untested.
- The next recommended post-owner-feedback milestone with a small, realistic scope.

**This is a documentation-only handoff instruction.** Do not pause APK delivery to implement this backlog.

---

## 8. Resume command for a future Codex session

> Resume Astravar 4.0 Project Genesis **after** the first verified APK and my hands-on testing. Read docs/genesis/POST_APK_ROADMAP.md on branch docs/astravar-genesis-post-apk-roadmap-20261112, the newest release checkpoint and recovery documents, repository AGENTS.md, and my test feedback. Inspect latest protected GitHub/source state and recover worktrees before coding. Reconcile the roadmap against actual code and verification; keep completed items completed and update the deferred intentions table. Choose the highest-value unfinished vertical slice, implement it on a new safe branch, test it end-to-end, document evidence, and preserve PR #15, campaign data, original signing compatibility and all checkpoints. Never restart, reset, clean, force push or claim an APK is owner-signed without checking. Work incrementally rather than attempting the entire backlog in one long session.
