# Genesis consolidation — preservation audit and implementation ledger

## Scope and evidence

Brendan installed Genesis1/code50 and supplied the November 14 continuation
directive. This authorizes the post-owner-testing track of the saved roadmap.
The immediate milestone consolidates daily character play, preserves the accepted
armor engine and adds custom/pinned skills. It does not declare the wider
Genesis roadmap finished.

The baseline audit below reads immutable source
`a86ab0fa87ce47a4f99550dcfa8fe01ed0e22928`, the exact delivered application.
Development commit `d12bdfec951acb3525ecb9bd280dcf4b85cc3b3a` adds only twelve
host-test Journal-readiness lines in `NavigationV2Test.kt`; no production files
differ. That correction remains relevant and must be retained in the follow-up.
Current implementation lives on
`feat/astravar-project-genesis-consolidation-20261114`.

All existing Genesis production checkpoint/feature branches were compared with
d12 through authenticated GitHub reads. Crafting, encounter, provenance,
navigation, storage and native reconstruction branches are already ancestors.
Do not reimplement them merely because older checkpoint prose says unfinished.
The divergent release-continuation branch contains documentation; the divergent
owner-upgrade-envelope branch contains CI/transport helpers, not application
features. PR #15 remains open at `7e229a43e4d66e91fa09f35ea39e1453387f38fe`.

Status vocabulary:

- **Complete:** the narrowly named behavior is present, with the scope and
  historical verification stated. This is not certification of every related
  feature or this new milestone.
- **Partial:** a real implementation exists, but the named broader workflow has
  explicit missing behavior or verification.
- **Broken:** an existing route or integration fails the owner's stated use;
  this does not imply the retained underlying engine is defective.
- **Unimplemented:** the requested behavior has no corresponding baseline
  user-facing implementation. A data field alone is not a completed workflow.

## Legacy-to-character preservation map

| Existing system | Baseline authority and reusable implementation | Destination and required cutover |
| --- | --- | --- |
| Armor Systems | `ArmorOverviewV2`, `CharacterOverviewV2`, `ArmorCapabilitiesRebuild`; references to original campaign profile plus separate weapon state | Retire ordinary Tools entry after Combat has element controls and Features has relevant capabilities. Sheet remains the authority for character statistics. Do not delete art, source records or configurations. |
| Armor Weapon | `rules/Engine.kt`, `DiceRoller.kt`, `Model.kt`; `Repository` in `data/Store.kt` stores `Campaign` in `astravar.db`; `Dashboard` is the old presentation | Native Genesis Combat cards/controllers must use the accepted engine through reviewed character ownership. Do not embed Dashboard. Preserve modes, payloads, configured weapon profile, dice breakdowns, charges and historical shots. |
| Active Element / aim | `OverlayPrefs` and `Preferences` in `data/Store.kt`; `overlay` DataStore stores elements, mode, selected payload IDs, advantage/disadvantage and aim settings | Preserve the exact saved selections at adoption and make current state accessible from Combat. Avoid a new silent default or independent writable copy. |
| Spell Reservoir | `Campaign.cores`, installed core identity, payloads and engine accounting; `CoresPage` | Genesis Spells must distinguish source spell/template from a loaded core payload. Retain capacity, cooldown, charge and current contents, with reviewed resource authority. |
| Spell Library | Saved spell templates/configurations and `PayloadsPage`; specialized delivery behavior in the same armor engine | Connect library/template inspection and payload loading to Spells without copying every loaded payload into the ordinary spell list. Keep identities and custom configurations. |
| Rules & Profile | `RulesPage`, `WeaponProfilesV21`, `WeaponDamageUpgrades`; accepted `Campaign.profile` values and source references | Character-specific configuration belongs in Features/Sheet. Use current calculated character modifiers where appropriate; preserve independent weapon values that have a different accepted meaning. Do not apply already represented effects twice. |
| Resources & Recovery | Legacy `ResourcesPage` reads weapon slots/game time; modern `GameplayRules`, `ReforgeSessionDialogs`, `ReforgeHitDice` operate on `ReforgeCharacter` | Shared character HP/temp HP/Hit Dice/slots/resources/rest actions remain authoritative. Armor integration must explicitly reconcile existing balances and recovery state without refilling resources. |
| Roll History | Legacy `CombatHistoryPage` and retained weapon `rolls/events/archives`; character `reforge_rolls` and immutable `reforge_changes` | Character History/Activity must expose both retained sources with clear identity and useful filtering. Relocation must not rewrite old dice or truncate preservation history. |
| Yellow shortcuts | `GenesisQuickActions`, per-profile preference layout, normal and compact character entry points | Remove the prominent duplicate Health/Actions/Spells/Inventory row. Keep visible section tabs and context-specific actions. Retained old preference values do not justify leaving clutter in the primary UI. |

The storage distinction matters: baseline `AstravarApplication` explicitly keeps
the weapon store separate, while `GenesisApplicationStorage` connects campaign
and playable records in the campaign Room database. Moving buttons alone cannot
resolve this authority boundary. Preserve the original weapon store/history as
recovery evidence; newly adopted character mechanics must have a single active
write path. No automatic adoption, profile-name matching or resource reset is
justified by this audit.

Original assets include `ApprovedAttackMedallion`, `ApprovedAbilityMedallion`,
`ElementParticle`, armor banners and their bundled drawable bytes. Asset values
are illustration, not permission to overwrite configured mechanics. Preserve
the source/artwork/signing guards and record any legitimate source-review
additions without changing historical pins.

## Baseline feature classification

| Feature | Status at a86 | Source evidence and remaining work |
| --- | --- | --- |
| Campaign/playable shared Room storage | Complete, scoped | `GenesisStorageMigration.kt` retains original raw rows and a cutover receipt; `GenesisAuthority.kt` and projections share transactions. Historical schema6→9 native retention passed. Armor storage is excluded from this completed scope. |
| Explicit campaign, actor and physical-item authority | Partial | `GenesisAuthority`, `GenesisAuthorityProjection`, `GenesisInventory` and `GenesisItemCreation` provide reviewed enrollment and connected writes. Broader CRUD/editor parity and the weapon authority seam remain. |
| Large source-profile selection without whole export | Complete, scoped | `ReforgeLegacyAdapter.readLegacyPreview` calls `readCharacterSnapshot`; `CampaignCharacterSnapshot` supplies scoped data. `ReforgeLargeCampaignSelectionTest` and historical native large-campaign journey cover the old oversized-export regression. This does not prove all current-roster scans scale. |
| Character creation and baseline editing | Partial | `GenesisConnectedCharacterEditor` and `ReforgeCharacterEditor` have staged identity, abilities, health/defense, casting and review. Class/subclass are largely recorded choices, not a full rules-driven builder. |
| Character projection and accepted equipment effects | Partial | `CharacterCalculator` derives abilities, saves, trained skills, AC, HP, slots, attack/DC and defenses from structured accepted effects. Source review handles known legacy modifier conflicts. Complete class feature/armor integration is missing. |
| Single-level advancement | Complete, scoped | `AdvancementRules.advance` records one confirmed level, accepted HP gain/permanent increases and preserves spent capacity. `ReforgeAdvancementDialog` exposes preview/save. It deliberately does not grant rulebook content. |
| Full class/subclass progression and multiclass | Unimplemented as a guided rules workflow | `className`/`subclass` are strings; advancement contains a pinned single-class legacy Artificer caster-level rule. No class-level breakdown or guided multiclass progression exists. Preserve accepted manual builds. |
| Standard skill training and checks | Complete, scoped | `SkillTraining`, `CharacterCalculator` and Training editor implement governing ability/proficiency/expertise and standard checks. This does not include the new custom-skill editor or pinning. |
| Custom-skill CRUD and display | Unimplemented | Baseline `skills` is a map and generic SKILL effects exist, but Sheet loops the fixed `reforgeSkills` list and Training has no custom CRUD. Add stable identities, notes/bonuses/explicit overrides, validation and backup persistence. |
| Skill pinning and grouped A–Z order | Unimplemented | No baseline skill-pin state or control. Implement pinned A–Z then unpinned A–Z, case-insensitive with stable duplicate-name ties, without changing mechanics. |
| Ordinary attack editor and dice | Partial | `AttackRules`, `ReforgeAttackEditor`, `ReforgeDice` and immutable roll receipts support calculated/independent attacks, typed damage, effects and turn/resource accounting. Specialized armor remains a separate tool; generic save-based delivery is not a complete universal attack workflow. |
| Armor attacks within Genesis Combat | Broken integration | Combat either routes to the legacy tool for an exact source identity or tells the player to use Tools. It does not show the established specialized modes directly. Retain and adapt the engine rather than substitute generic attacks. |
| Active Element in daily Combat | Broken integration | Selection survives in weapon preferences but ordinary Genesis Combat does not expose it. Preserve selection and permitted mode behavior during consolidation. |
| Prepared/known/custom spellbook | Complete, scoped | `SpellDefinition`, `GameplayRules.previewCast/commitCast/prepareSpell` and Spells UI support preparation, slots, concentration, rituals, explicit resource costs, details and name/filter search. This is not a bundled full spell catalog or reservoir integration. |
| Reservoir/template integration | Broken integration | `CoresPage`/`PayloadsPage` remain separate Tools routes; `ReforgeCharacter.spells` is disconnected. Preserve templates versus loaded instances, exact effects and capacity/accounting. |
| HP, temp HP, Hit Dice, resources, rests | Partial | Modern `GameplayRules` and dialogs use the same `ReforgeCharacter`, including explicit confirmation and unknown values. Legacy armor slots/time/recovery still live in `Campaign`; these must not continue as independent current totals after adoption. |
| Character features, notes and provenance | Partial | `ReforgeIdentity`, `ReforgeNotesEditor`, structured effects and source inspection exist. Descriptive feature text intentionally does not activate effects; legacy weapon upgrades/configuration still need a coherent Features route. |
| Companions | Partial | `GenesisCompanionResources`, `ReforgeCompanionActions`, Party roster and reviewed linked sheets support established identities, source-backed HP/counters and navigation. Not every creature action/class feature is automated. Unreviewed NPCs are not silently converted to companions. |
| Item inspect/equip/attune/quantity/transfer | Complete, scoped | `GameplayRules`, `InventoryRules`, `ReforgeItemManagement`, `GenesisInventory` and connected authority preserve same physical IDs and current projections. Historical host/native journeys cover representative connected flows, not every homebrew combination. |
| General container management | Partial | `ItemInstance.containerId`, source placements and crafting output-container validation exist. The everyday Equipment UI has no complete general container picker and reorganizing workflow. |
| Inventory search/filter | Complete, scoped | `GenesisInventoryHub` searches names/owners with My items/All items; Equipment supports name/Equipped/Attuned filters. Broad sort/filter UX remains limited. |
| Inventory favorites/drag/reorder | Partial data, unimplemented UI | `ItemInstance.favorite` exists, but baseline hub/Equipment does not expose favorite pinning or drag/reorder controls. Do not confuse persisted schema support with an available feature. |
| Connected crafting | Partial | `GenesisCrafting` atomically reviews/consumes ordinary carried/stored unattuned material and creates a plain same-ID output with provenance. Effects, composite links, occupied containers and attack-dependent materials are intentionally guarded. General crafting/time/recipe automation is not implemented. |
| NPC/campaign creation and membership | Partial | `GenesisAuthority.createCampaign/createActor`, connected editors and association review exist. Source-only actors preserve unknown mechanics. Some historical editors and broad membership/ownership workflows remain. |
| Manual encounter lifecycle | Complete, scoped | `GenesisEncounters` persists draft/start/next/end/reopen, manual order, initiative and linked actors with immutable receipts. Its tracker never copies combat statistics. Historical native journey and SQLite proof passed. |
| Automated encounter mechanics | Unimplemented beyond character routes | Tracker advancement does not roll initiative, advance campaign time, refill actions, alter actor HP/conditions or resolve enemies. Character Combat remains the actual mechanical route. Wider encounter automation is deferred. |
| Connected notes/reference viewing | Partial | `ReforgeRichText`, `RebirthCampaignJournal`, `ReforgeCampaignConnections` and reference library provide local text/links and draft recovery. Historical editors and external-reference parity remain; no automatic external synchronization is authorized. |
| Character swipe/tap/Back/state | Complete, scoped | `GenesisPager`, `PlayerTabs` and character routing retain page/scroll state and visible tap alternatives. a86 fixed actual accessibility occlusion. New consolidation routes still need fresh regression and native acceptance. |
| Navigation/visual coherence | Broken in reported areas; partial overall | New shell uses consistent dark surfaces and accessible tabs, but Tools exposes duplicate old generations and the yellow shortcut strip duplicates primary sections. Owner feedback defines the next correction. |
| Connected backup/restore | Partial | `GenesisAuthorityBackup`, `ReforgeBackup`, `GenesisUnifiedBackupPage` retain linked source/playable/history data with verified current backup before merge. Baseline cap is 64MiB; weapon store, settings/layouts, connection setup, some drafts and external portrait bytes are excluded. |
| Large-campaign performance | Partial | Scoped source selection and chunked raw migration are implemented; allCharacters/allPlacements scans remain in inventory/crafting authority paths. Profile actual data before claiming comprehensive scalability. |
| Accessibility / Android acceptance | Partial | a86 historical native API36 tests cover taps, swipes, text scale and representative routes. New milestone needs fresh checks. User reports installation/testing on Galaxy S26/Android17; this is not an automated physical-device test record. |

## Ordered implementation and verification ledger

| Milestone | Scope | Initial status | Evidence required before completion |
| --- | --- | --- | --- |
| A — Recovery and preservation | Preserve a86, d12 readiness correction, PR15 and complete roadmap; map legacy state/art/history to character destinations | Audit recorded; no production pass inferred | Branch ancestry/source comparison, local worktree inspection, unchanged identity/signing/artwork/schema pins |
| B1 — Character skills | Custom-skill create/edit/remove, calculation, backup, standard/custom pinning and stable A–Z groups | Implementation in progress | Formula and duplicate-name tests; repository round-trip/restart/backup; UI create/edit/pin/unpin/delete and accessibility |
| B2 — Armor authority and Combat | Reviewed persisted armor adoption; current character modifiers and single resource authority; native Combat mode/element/roll controls | Implementation in progress | Stale review/retry/rollback protection; exact formulas/costs/advantage; retained legacy state/history; restart and backup |
| B3 — Spells and reservoir | Source spells/templates versus loaded payloads; inspect/configure/load/cast and consume through shared armor state | Implementation in progress | Capacity/slots/payload selection and cooldown tests; no duplicate spend; state agreement across Combat/Spells/Sheet; restart/restore |
| B4 — Shell, Features and History | Retire migrated Tools entries; remove prominent yellow row; contextual rules/configuration/recovery/history and reused art | Implementation in progress | All capabilities reachable through modern controls; preserved original art/history; no hidden old route required for ordinary play; tap/Back/text-scale checks |
| C — Next deferred vertical slice | Choose the highest-impact remaining workflow only after this consolidation is verified | Deferred | Concrete owner-facing acceptance and independent persistence/data assertions; update this table before expanding |
| D — Owner update | Increment version beyond50, exact-source broad/native verification, original-key signing and compatible install proof | Pending | APK hash/source/certificate/package/version proof; ordinary in-place update and data retention evidence; explicit physical-device limitations |

Current milestone results must be appended with exact commits, commands, counts,
artifacts and failures after execution. Do not transfer historical a86 green
results to changed code. A committed implementation is not a test pass, a debug
APK is not an owner-compatible update, and a source review is not native QA.

## Historical release evidence retained

- a86 full run `43251800831` and native run `43251800807` were freshly confirmed
  completed/success via GitHub on November 14. Recorded release totals:
  rules166, debug929 pass/3 skip, release915 pass/17 skip, Python320, bridge134,
  native19 pass and zero lint errors. These counts belong only to a86.
- d12 full run `43256864460` passed; native `43256864350` failed. The recorded
  failure is Gboard changing encounter note text `manual` to `Manual`; app code
  preserves the supplied text. Fix deterministic input without weakening exact
  save/reopen assertions.
- Genesis1 owner APK SHA-256:
  `2c14bd7ec186122a94b5273e072ea262efeae60b00af96c65ecc21c49ce0e9b1`.
  Owner certificate SHA-256:
  `4c5cb0113cae5d0dee2619ee30f5b3c593d4aa897e1fa38dee7ed5561ec4e847`.
  Package `com.yazsras.astravar`, versionCode50, minSdk26, targetSdk36.
- Original-owner49→50 helper run `43323086598` failed before installation after
  the APK transfer was rejected. It is not a passing owner-key install test.
  Key recovery/handling and new release checks belong to the release operator;
  no private materials or campaign export belongs in this public document.
- New owner feedback is installation/usability evidence and authorizes this
  development phase; it does not establish all code50 or new-build acceptance
  criteria automatically.

See [POST_APK_ROADMAP.md](POST_APK_ROADMAP.md) for the preserved original vision
and [RESUME_VERIFICATION_20261112.md](RESUME_VERIFICATION_20261112.md) for earlier
source-specific diagnostics. Latest historical release continuation lives on
`checkpoint/astravar-genesis-release-verification-20261112`; its old freeze
instructions are superseded by the owner's November 14 continuation directive.


## Recoverable implementation checkpoint — November 14

The following are source checkpoints on the continuation branch, not a release
acceptance claim. The baseline classification above remains useful historical
evidence and is deliberately not overwritten with the new implementation.

| Checkpoint | Implemented behavior | Verification status at this checkpoint |
| --- | --- | --- |
| `6fa473d` | Character-owned custom skills with stable IDs, training/bonus/notes/manual baseline override; standard/custom pins, deterministic pinned A–Z then unpinned A–Z; Sheet CRUD, roll, delete confirmation | All nine `SkillRulesTest` cases passed in the first rules run. Repository/restart/complete-backup and Compose interaction cases are included in the focused application batch; final rerun is pending. |
| `f7e6943`, `0a3b518` | Native Genesis Combat adapter to the retained armor engine; character-derived accuracy/resources, mode/options/elements, atomic dice/cost commit, exact saved results and condition accuracy snapshots | Original engine regression tests ran alongside six new armor cases. First rules batch was 180 pass / 1 fail; the failing armor fixture is being corrected and rerun. No native pass is inferred. |
| `9f9f8eb`, `27e64f9`, `55731af` | Reviewed character adoption, shared slot/time/action accounting, reservoir load/overwrite/purge and connected payload library, retained unresolved roll drafts | First focused run passed reservoir repository 2/2 and reservoir UI 4/4. Later conservative action-budget adoption and condition guards add coverage and require the final rerun. |
| `a9018be` | Character Features presents retained armor artwork and reviewed damage/melee/engineering/attunement configuration without changing independent resources or original evidence | Pure validation and UI coverage added. One staged-save UI idling failure occurred in the first batch; final validation is pending its correction. |
| `d395d10`, `d31491a` | Retired ordinary legacy Tools entries; contextual character routes; removed prominent quick-action strip; original source inspection; unified character/armor History; unique armor ownership checked in saves and restore | Focused navigation/ownership/restore tests added. The first ownership fixture failed before its intended assertion and is being corrected. Original source stores and history remain preserved. |
| `3099b0a`, `da7fae6` | Next deferred slice: accessible Equipment favorite toggle and Favorites filter persisted through the existing character transaction | UI semantics/reversibility/busy-state coverage added. Final focused and broader results pending. General container editing and inventory drag/reorder remain deferred. |
| `12d1d3e` | Real native candidate journey covers reviewed adoption, automatic armor rolls and one-shot advantage, keyboard/rotation, element restart, reservoir loading, Features, custom-skill CRUD/pinning and retained activity. Existing import, quick-action-customization and upgrade-preservation journeys follow consolidated routes | Native Kotlin compilation initially found four invalid observer arguments; all were fixed. Compilation rerun and actual emulator execution are pending. Existing exact formula/persistence/assertion coverage is retained. |

The first application focused batch was **55 pass / 2 fail (57 total)**. The
failures are explicitly retained above; changes made after that batch are not
covered by its passing counts. Local evidence files are
`astravar-runtime/rules-consolidation.log`,
`astravar-runtime/focused-consolidation-app.log`, and
`astravar-runtime/focused-consolidation.log` beside the repository. These are
transient engineering logs, not published release artifacts. Record final
commands, exact source, results and public CI evidence at the release checkpoint.

### Native preservation sequence

The unchanged legacy weapon journey runs on the frozen code49 baseline before
seeding/snapshotting the upgrade fixture. Its two real automatic rolls are then
part of the retained source history during code49→50→new-version verification.
The new candidate has a separate consolidated-character journey after the
existing Player First character-creation flow. The later update check reads all
four exact old/new armor formulas through Character History, the pinned skills,
loaded reservoir and current element. Separate stopped SQLite and complete
backup checks remain independent of UI success.

This sequencing preserves the old engine's genuine interaction coverage without
restoring retired Tools entries. Historical `ci-player-first-emulator.sh` is
used by old Android2.1 workflows; the active Genesis workflow uses
`ci-emulator.sh`. Do not cite the old harness as candidate acceptance.

### Remaining product limits

- Guided class/subclass progression and multiclass rules, general container
  management, broad encounter automation and unrestricted crafting are not
  delivered by this consolidation slice.
- Armor adoption is explicit and source-reviewed. Unknown character slot counts
  require a visible reconciliation choice; spent action state is retained
  conservatively. No identity is inferred from a matching display name.
- Payload templates and ordinary spells retain separate identities with explicit
  links. Name matching does not merge potentially different mechanics.
- Existing unresolved source disagreements stay visible. In particular, no new
  upgraded Discharge formula or charged-gauntlet damage type is invented.
- Newly adopted armor/configuration and custom skills travel in character and
  complete connected backup records. The original separate weapon archive is
  retained; the broader historical exclusions (settings, connection setup,
  external portrait files and some drafts) still need separate coverage.
- Owner-key artifact, in-place install, broad tests and emulator acceptance are
  pending; Galaxy S26/Android17 physical testing has not been performed here.
