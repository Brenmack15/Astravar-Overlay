# Astravar Project Genesis — Post-APK Roadmap and Deferred Intentions

## Current continuation — November 14, 2026

The owner installed Genesis1/code50 and supplied hands-on feedback. The
post-APK continuation is active; the November12 release freeze below is preserved
history, not the current task scope. Start with [section9](#9-post-install-consolidation-continuation--november-14-2026),
the [consolidation audit](CONSOLIDATION_AUDIT_20261114.md), and the
[current verification report](CONSOLIDATION_VERIFICATION_20261114.md).

The verified release source is `65978f76f59a842657b16b5ce39ad8f9702ec5bb` on
`feat/astravar-project-genesis-consolidation-20261114`. All 51 original/published
checkpoint trees match; PR15 and the delivered code50 source remain intact.
Full CI43620647220 and native CI43620647219 passed on this exact source,
including the corrected 200-percent-font layout. The original-owner-signed code51
APK is verified and ready for delivery. Earlier candidate passes and failures are
recorded separately and are not reused as current acceptance.

| Current milestone | Implementation and acceptance status |
| --- | --- |
| Recovery and preservation | Complete: 51 exact source-tree mappings; original roadmap/checkpoints and PR15 retained |
| Character consolidation and custom/pinned skills | Complete within the stated scope: full regression and native CRUD/navigation/resource/restart/update journeys pass |
| Existing source attacks | Supported reviewed-import slice complete: disk reopen and playable backup restore pass; unsupported mechanics remain review-required and successful native import is not claimed |
| Deferred slice: Equipment favorites | Complete favorite/filter/restart/update slice; general containers and broader reorder remain deferred |
| Code51 release | Complete artifact proof: original certificate, exact tested payload and checksum verified;21 native cases pass. Empirical upgrade uses a disposable signer; no owner-key or physical-device installation claim |
| Remaining Genesis breadth | Partial/unimplemented items remain in the preserved backlog and classified audit; guided progression, general containers and broad automation are not complete |

## Historical November12 handoff — preserved

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

- Deliverable: Astravar-4.0.0-genesis1-code50-owner-signed.apk; package com.yazsras.astravar. The owner download was delivered privately; its private delivery URL is omitted from this publication.
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

---

## 9. Post-install consolidation continuation — November 14, 2026

The owner has installed Genesis1/code50 and supplied explicit hands-on feedback
and a new implementation directive. Track A's release freeze is therefore no
longer the active product scope. Preserve its historical evidence above; resume
Track B on `feat/astravar-project-genesis-consolidation-20261114`.

The exact delivered production baseline remains `a86ab0fa87ce47a4f99550dcfa8fe01ed0e22928`.
The latest recovered development source `d12bdfec951acb3525ecb9bd280dcf4b85cc3b3a`
adds only twelve host-test Journal-readiness lines; retain that useful follow-up.
Authenticated branch comparisons show all other Genesis production branches are
ancestors of d12. Separate documentation and private owner-upgrade CI helper
branches remain preserved. PR #15 is not moved or merged by this continuation.

Read [CONSOLIDATION_AUDIT_20261114.md](CONSOLIDATION_AUDIT_20261114.md) for the
code-based complete/partial/broken/unimplemented classification, legacy-store
preservation map, current implementation ledger and verification boundaries.
Historical open checkboxes above describe broad goals, not evidence that their
working subfeatures are missing. In particular, scoped profile reads, shared
campaign/playable storage, connected creation, restricted crafting, manual
encounters, inventory transfers, swipe/tap navigation and connected backup
already exist and must be preserved.

### Initial November14 audit snapshot

The following statuses were recorded before consolidation implementation. They
are historical evidence; use the current table at the top and the verification
report for the latest acceptance state.

| Owner priority / recovered intention | Current baseline evidence | Required next result | Status |
| --- | --- | --- | --- |
| Retire Armor Systems and redundant Tools routes | `PlayerShell` and `AppScreen` expose legacy screens alongside Genesis | Move useful capabilities first; retain artwork/data/configuration; remove ordinary old entries | In progress |
| Integrate armor attacks and Active Element into Combat | Accepted pure Kotlin engine, separate `astravar.db` and `overlay` preferences; no direct Genesis controls | Reviewed character-bound armor state, correct current modifiers, engine costs/dice/results and preserved selections/history | In progress |
| Integrate reservoir and spell library into Spells | Modern spellbook is present; core/payload/template system remains separate | Single authoritative resources and connected template/load/cast workflows without duplicate spell rows | In progress |
| Character rules, features, resources and activity | Modern sections exist; legacy upgrades/resources/history remain separate | Contextual controls and accepted-effect accounting without duplication; retained history and recovery state | In progress |
| Remove prominent yellow shortcuts | `GenesisQuickActions` duplicates page navigation | Retain accessible tabs and contextual unique actions; reduce primary visual clutter | In progress |
| Custom and pinned skills | Standard training/calculations exist; custom CRUD/pins do not | Persisted custom identity/ability/training/bonus/notes/override; stable pinned A–Z then unpinned A–Z; backup/restart coverage | In progress |
| Preserve modern and armor backup coverage | Connected backup has 64MiB cap and excludes separate weapon/preferences | Newly adopted armor state must travel with its character; retain old weapon/history recovery and disclose remaining exclusions | In progress |
| Deterministic native encounter text | d12 native failure retained; application production is identical to a86 | Correct input mechanism while preserving exact text and save/reopen/mechanical-state assertions | Open verification work |
| Deferred Genesis breadth | Guided multiclass/progression, general containers/favorites, broad crafting/encounter automation and some historical editors remain partial or absent | Select the next small verified vertical slice after consolidation, preserving the full backlog | Deferred |
| New owner-compatible APK | Prior owner certificate and code50 baseline are recorded | Version greater than50, exact-source tests, original certificate, checksum, in-place update/data-preservation proof and honest limitations | Pending |

At that initial checkpoint, no new implementation had yet been accepted.
Actual commit/test/artifact evidence is appended below and in the verification report. The new directive
authorizes development and release preparation; it does not permit invented
physical-device testing, private signing-material publication or destructive
data migration.


### Implementation checkpoint after consolidation coding

Recoverable commits now implement character-owned custom/pinned skills, a shared
armor adapter and atomic Combat rolls, explicit original-system adoption,
reservoir/payload controls in Spells, reviewed armor configuration in Features,
contextual recovery/history, and removal of the ordinary redundant Tools routes
and prominent shortcut row. The next small deferred slice also adds persisted
Equipment favorites and a Favorites filter (`3099b0a`, `da7fae6`); broader
container and reorder workflows remain open.

See the implementation checkpoint table in
[CONSOLIDATION_AUDIT_20261114.md](CONSOLIDATION_AUDIT_20261114.md) for exact commits,
first observed failures, preserved assertions, native upgrade sequence and
remaining product limits. Initial focused evidence is **180/181 rules passed**
and **55/57 application tests passed**, followed by fixes and additional coverage
awaiting rerun. Native compilation was corrected after observer API errors;
compilation and emulator acceptance are distinct gates. None of these partial
results is a completed owner-compatible release or physical-device claim.


The second local focused pass advances evidence to **184/184 rules** and
**64/65 application cases**, plus **328 Python tests**, with native test Kotlin
compilation passing. It found and retained a real adopted-history revision-order
failure in canonical armor readback; fixing that defect and rerunning it is the
next gate. These local runs used an evolving worktree, so release acceptance
still requires an exact clean source and the native/owner-update evidence.


The exact frozen candidate and subsequent acceptance results are tracked in
[CONSOLIDATION_VERIFICATION_20261114.md](CONSOLIDATION_VERIFICATION_20261114.md)
on the separate verification-documentation branch. Candidate `e00cb749` passes
all186 rules cases and the final21 application cases covering the corrected
armor revision defect, plus328 Python checks. Broad CI, actual native execution
and original-owner installation are still pending at this checkpoint. Earlier
failed evidence above remains historical and is not deleted.


A further consolidation gap was identified during preservation review: prior
character adoption retained existing source attack records as evidence without
making every supported attack an ordinary Combat action. Explicit reviewed
source-attack import is now part of the pending milestone. It must preserve
structured formulas, identity and provenance, avoid duplicate imports, and keep
unresolved configurations visible for review. This does not certify private or
current-phone Zerg records; use synthetic verification and the accepted source
mechanics, without copying stale private mirrors into code or public reports.


### Final-source full regression checkpoint

Published source `f5971e3b421629b2a51c1f052893d56fc1b64199` is tree-equivalent
to local `9eee925dc09478efcf15a802f7925a4225baa552`; all 45 checkpoint pairs
are preserved in the source map. Full CI43608137979 passes: 186 rules, 972
application debug cases (3 opt-in skips), 958 release cases (17 documented
skips), 328 Python and 134 bridge checks. Both lint variants have zero errors
(debug81 warnings +1 hint; release83 warnings +1 hint). Raw artifact XML was
independently counted. Native CI43608138002 and original-owner signing/update
verification are still pending at this checkpoint; no physical-device or
current-Zerg acceptance is implied. See
[CONSOLIDATION_VERIFICATION_20261114.md](CONSOLIDATION_VERIFICATION_20261114.md)
for the exact source, workflow URLs, artifact hash, skip reasons and preserved
failed/cancelled attempts.


The subsequent native result for f597 is **failed**, not pending: run43608138002
exhausted downward-first discovery before returning to the upper pinned-skills
heading and a newly created companion name. The exact companion name is visible
in the failure screenshot and XML. Test-only correction `03ae955` uses known
upper anchors and a fresh saved-name snapshot, retains every exact assertion and
the same 30-second budgets, and adds positive saved-pin checks. See the fifth-run
evidence in the verification report. No signing or final acceptance is claimed;
corrected exact-source full/native gates are required.


### Current48f full regression checkpoint

Exact source `48f7575e58cdf14654f5083104a57b09a99a8b68` passes full
CI43613698288. Artifact14122539554 was hash-verified and independently recounted:
186 rules pass; debug972 pass/3 skips and release958 pass/17 skips (975 total
each), with zero failures/errors. Python328 and bridge134 pass. Lint has zero
errors (debug81 warnings +1 hint; release83 warnings +1 hint). Native
CI43613698316 is still running, and owner-artifact proof remains pending.
The actual upgrade test uses a disposable signer; owner certificate/package/
payload verification and empirical test-signed migration are separate evidence.
No owner-key code51 installation or physical-device test is claimed.


The48f native gate subsequently passed 16 cases, including the complete new
consolidation journey, Genesis navigation/backup, crafting and encounters, but
failed the late200-percent-font update capture. The screenshot exposed both a
cramped element-summary layout and a seven-pixel clipped Change element marker.
Responsive product correction `7dcb955` and bounded native reveal `2fef3cc`
preserve the strict screenshot gate. Imports did not run; new exact-source
release gates remain required. See the seventh-run evidence in the verification
report; no owner signing or complete native acceptance is claimed.


### Intermediate eighth-candidate full regression checkpoint

Source `65978f76f59a842657b16b5ce39ad8f9702ec5bb` passes full CI43620647220,
with 51 preserved checkpoint pairs. Verified artifact14125885867 and independent
raw-XML recount establish186 rules passes,975 debug passes/3 skips and961
release passes/17 skips (978 application cases each), zero failures/errors.
Python328 and bridge134 pass. Lint has zero errors (debug81 warnings +1 hint;
release83 warnings +1 hint). The new precise responsive layout tests pass.
At this intermediate checkpoint, native CI43620647219 and owner-artifact
verification were pending. Both subsequently passed as recorded below; earlier
native successes remain source-specific evidence.


### Final consolidation milestone acceptance

Release source `65978f76f59a842657b16b5ce39ad8f9702ec5bb` passes full
CI43620647220 and native CI43620647219. Final measured results are186 rules,
975 debug passes/3 skips,961 release passes/17 skips (978 application cases per
variant),328 Python,134 bridge and21 native cases across 18 successful
invocations. Both lint variants have zero errors; existing warnings and explicit
skip reasons remain documented. Independent native receipts verify retained
stores/source/history, later canonical writes, connected backup, crafting,
encounters and all four import/recovery cases.

The completed slice consolidates ordinary armor/element Combat, reservoir and
payload Spells, contextual rules/resources/history, custom/pinned skills and
Equipment favorites. Source-attack import is complete for supported reviewed
records, with focused disk reopen/playable backup coverage; unsupported
mechanics remain explicit. The complete guided progression/container/automation
backlog remains partial or unimplemented as classified above. Current Zerg phone
data is not certified by fictional fixtures.

`Astravar-4.0.0-genesis2-code51-owner-signed.apk` is 13,184,678 bytes, SHA-256
`81c4ab63e4aba980bc7881919d6e90333e2f72f3031d43db2fbbe1fa17d5e644`.
Its production package and original certificate match the original owner code50
APK; all 210 tested CI payload entries are unchanged, with no rebuild.
Signature/package/payload proof and disposable-signer49→50→51 migration evidence
are distinct. No owner-key code51 or Galaxy S26 installation was performed by
this verification work. See the current verification report for exact hashes,
source mapping, receipts, limitations and the retained failed/cancelled attempts.
