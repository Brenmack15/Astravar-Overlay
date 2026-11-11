# Genesis interaction audit — recovered baseline

Baseline: `3543e83bfca5e589f1bbedb09964431ce94a2319` (PR #14 head, code49).
This document records **source inspection**, not a new emulator or handset acceptance pass.
All source line references below refer to that baseline, before Genesis edits.
No personal campaign records were used. No feature was removed during this audit.

## Coverage and limits

`control-inventory.csv` inventories 1,173 matching control/helper sites in 59 files,
from all 106 production Kotlin files under `app/src/main/java`. Each row includes
baseline SHA, exact file, line, nearest lexical function, control primitive,
nearby literal labels, source excerpt and an explicit `NOT RUN` runtime status.
It covers buttons, tabs, forms, pickers, dialogs, disclosures, click modifiers,
scroll modifiers and Back handlers. Helper declarations are marked separately.
Repeated controls generated from records are represented by their source site,
not one row per possible campaign record. Nearby literals are discovery aids,
not asserted accessible names. The scan is a lexical inventory, not a complete
Kotlin call-graph analysis: native view callbacks and wrappers may expand into
additional runtime controls. The workflow matrix below supplies product context.

The existing tests named here were inspected for coverage. Their presence does
not establish that they passed against Genesis. Final verification is pending
for every reconstructed workflow until the implementation and relevant runs exist.

## Recovered screen and route map

| Entry | Screens and secondary destinations | Source |
| --- | --- | --- |
| App launch | Character; recovery panel if specialized weapon state cannot load | `AppScreen.kt:37`, `StorageRecoveryPanel.kt` |
| Primary bottom navigation | Character, Inventory, Companions, Campaign; dynamically wraps to more rows at larger font sizes | `PlayerShell.kt:27`, `PlayerShell.kt:74` |
| Character | Sheet, Combat, Equipment, Spells, Resources, History | `ReforgeCharacterSheet.kt:203` |
| Sheet | Ability inspector; saving throw and skill rolls; passive scores; identity/features; notes; advancement; campaign connections; provenance | `ReforgeCharacterSheet.kt:206`, `ReforgeIdentity.kt` |
| Combat | HP, conditions, concentration end, attack list/editor/roll, turn economy/configuration, next turn | `ReforgeCharacterSheet.kt:274` |
| Equipment | Search; All gear/Equipped/Attuned filters; inspect; edit; equip/unequip preview; attune; manage/split/transfer/remove; campaign copy-import | `ReforgeCharacterSheet.kt:339`, `ReforgeItemManagement.kt` |
| Spells | Attack roll; slot summary/editor; preparation disclosure; All/Prepared/Cantrips filters; inspect/edit/prepare/cast | `ReforgeCharacterSheet.kt:377`, `ReforgeSessionDialogs.kt` |
| Resources | Add/edit resource; rest/recovery; spend Hit Die; use one | `ReforgeCharacterSheet.kt:444`, `ReforgeHitDice.kt` |
| History | Saved dice, changes/revisions, original sources | `ReforgeCharacterSheet.kt:472` |
| Character selection | Active and archived sheets; create; review legacy profile; archive/restore | `ReforgeCharacterSheet.kt:489` |
| Inventory root | My gear (second instance of playable Equipment); Campaign items (ledger) | `PlayerShell.kt:118` |
| Campaign item ledger | Search/category filters; create/edit/archive; item detail; ownership/location; artwork; properties/research/discoveries; linked records/history; backup | `CampaignLedgerPage.kt` |
| Companions | Searchable roster; name-only creation; member Sheet/Equipment/Currency/History; source dossiers | `PartyPageV2.kt:65` |
| Companion Sheet | Campaign profile and abilities; playable-sheet review/open/combat shortcut; status/counters/relationships; typed stat-block sections; Eek tools | `PartyPageV2.kt:134`, `PartyCompanionEditorV2.kt`, `PartyTypedSheetV2.kt` |
| Companion equipment | Give/take/equip/install/store movement; quantity correction; item purchase and sale; historical records | `PartyEquipmentV2.kt`, `PartyExchangeV21.kt` |
| Companion currency | Balance editor and income/expense records | `PartyWalletV2.kt` |
| Companion relationships | Add/edit/remove links with explicit typed targets | `PartyRelationshipsV31.kt` |
| Campaign root | Connections, Inventions, Checkpoints; recent people/places and projects; reference/import disclosure | `RebirthCampaignJournal.kt` |
| Connections | People/groups/locations; detail, create/edit, fact, milestone, create/edit/remove link, linked record, history/event | `ConnectionsPageV2.kt` |
| Inventions | Blueprint/project editor; research/material links; craft preparation/review/commit; output references | `CampaignPagesV2.kt:67` |
| Checkpoints | Create/edit/read checkpoint; scene, confirmed action, pending choice, resource/ruling summaries | `CampaignCheckpointsV21.kt` |
| Reference library | Search/category filters; readable source; linked references; archive metadata/attachments/original text | `CampaignReferenceLibraryV21.kt` |
| Find | Local aggregate search; result body sheet and internal text links | `ReforgeSearch.kt` |
| Tools | Armor systems, prompts, appearance; two advanced disclosures; diagnostics | `PlayerShell.kt:139` |
| Advanced combat & magic | Armor weapon, spell reservoir, spell library, rules/profile, resources/recovery, roll history | `PlayerShell.kt:144`, `Dashboard.kt`, `Inventory.kt`, `Management.kt` |
| Records, backups & connection | Character sources, character backup, campaign backup/import, owner connection, weapon backup | `PlayerShell.kt:152` |
| Development-only tools | Fictional sync lab and remote updates; isolated from ordinary party records | `SandboxSyncPage.kt`, `RemoteUpdatesPage.kt`, `AppScreen.kt:176` |
| Owner connection | Pairing, identity/scopes, approval mode, pending proposals, synchronization controls, notifications, pause/revoke/readback | `CampaignConnectionPage.kt`, `CampaignConnectionContent.kt`; historical HP/owner content implementations retained |
| Backup/import | Three independent backup formats; campaign archive import/review; stale/conflict checks; recovery panels | `ReforgeBackupPage.kt`, `CampaignBackupPageV2.kt`, `CampaignBackupReviewV21.kt`, `CampaignArchiveImportV21.kt`, `Management.kt:333` |
| Appearance | Haptics, image picker/reset, advanced manual dice override; explicit end-demo action when demo | `Management.kt:454` |
| Prompt management | Search/category, create/edit/duplicate/archive/restore, variable entry/preview/copy | `ChatGptPromptsPage.kt` |
| Historical specialized surfaces | Armor overview, permanent-nexus adoption, element wheel, core/payload sheets, templates, scroll sources, original native overlay implementation | `AstravarHomeV2.kt`, `ArmorCapabilitiesRebuild.kt`, `PermanentNexusAdoption.kt`, `ElementWheelV2.kt`, `Inventory.kt`, `overlay/OverlayService.kt` |

The app does not expose a campaign create/switch workflow or encounter-builder
destination in this route tree. These requested Genesis workflows are new scope,
not existing UI that can be claimed preserved by relocating a label.

## Findings that explain the reported friction

1. **There is no content paging.** `PlayerTabs` is a horizontally scrollable row
   of tap targets. `ReforgeCharacterSheet` switches content using `when(section)`.
   `AppScreen` similarly swaps the four main destinations. No `HorizontalPager`
   or page gesture exists at the recovered baseline. Existing horizontal scroll
   is tab/filter/text overflow scrolling, not navigation.
2. **One apparent item has two workflows.** Inventory explicitly divides “My
   gear” and “Campaign items.” Campaign import into playable equipment creates
   a reviewed copy. `ReforgeCharacterSheet.kt:373` explains that original records
   remain separate. A successful edit in one view is not evidence of propagation
   to the other. A replacement shell alone cannot resolve this.
3. **Companion play is a second stage.** Creation saves a campaign companion;
   “Review for play” creates a separate playable sheet. The roster displays
   campaign profile HP/AC while the playable sheet has independent state. Typed
   manual resources, tracked companion counters and playable resources can all
   exist. `PartyTypedSheetV2.kt:116` explicitly detects a second counter by name
   and tells the player to reconcile it. The app currently surfaces the
   architecture to the player instead of offering one authoritative interaction.
4. **New characters are not immediately selectable in campaign relationships.**
   A separate Campaign connections dialog creates a name-only campaign identity.
   It explicitly says names/gameplay edits are not automatically synchronized
   (`ReforgeCampaignConnections.kt:39`). This is a functional disconnection.
5. **Search ends at a read-only excerpt.** `ReforgeGlobalSearch` combines legacy
   and playable records (including duplicate character identities under distinct
   kinds), but selecting a result only opens its body. There is no destination
   callback for editing, casting, equipping or opening the authoritative record.
6. **The specialized armor attack is remote from the main Combat page.** An empty
   attack list directs the player to Tools → Armor weapon. The accepted weapon
   engine remains valuable and must remain authoritative, but its ordinary play
   entry should be contextual and immediately reachable from Actions.
7. **Features are low in Sheet content.** Identity, features/feats, notes and
   advancement follow skills and passive scores. Features have no dedicated
   top-level character page. Frequent resource use/rest requires Resources.
8. **Forms share a visual wrapper but not a creation contract.** PlayerSheet
   provides scrolling, a fixed action footer and discard confirmation. Yet
   companion creation is name-only; character creation combines name/level with
   disclosures; item creation has deeply nested effects; campaign item/connection
   forms use other local drafts and source fields. Ownership defaults and final
   appearance across systems differ by route.
9. **Lists often eagerly compose all records.** Character lists, inventories,
   companions, spells and many campaign pages use a scrollable Column with
   `forEach`, rather than lazy lists. Search is lazy. Large-campaign performance
   requires measurement and targeted replacement; no performance result is
   inferred from this source observation.
10. **Dialogs are layered state strings.** App tool, campaign child destination,
    character dialog, item inspect/edit/manage, roll, preview and companion
    selection are independently held. Individual Back handlers and Dialog
    dismissal exist, but consistent stack behavior requires real interaction
    tests, especially when a deep link opens another destination.

## Workflow coverage matrix

Status legend: `SOURCE` = behavior traced, no new runtime execution;
`GAP` = requested capability not present in the recovered UI;
`PENDING` = final Genesis result not verified. Test names are existing relevant
coverage, not assertions that every row or every control is exercised.

| Workflow / intended behavior | Recovered behavior and dependencies | Problem / reconstruction decision | Existing relevant automated coverage | Final verification |
| --- | --- | --- | --- | --- |
| Launch and open saved actor | ReforgePreferences selects nonarchived playable sheet; independent weapon state gates app body | SOURCE: retain recovery access; do not let unrelated weapon initialization block normal character access | `ReforgePreferencesTest`, `StorageRecoveryPanelTest`, `CampaignRecoveryUiTest` | PENDING |
| Move between character sections by tap and flick | Tap-only PlayerTabs changes string section | GAP: implement native pager with stable keys, tap alternative and per-page state | `RebirthNavigationStateTest` covers tab-strip scrolling/state, not page flicks | PENDING |
| Retain scroll/search/drafts across navigation/recreation | SaveableStateHolder at main routes; page scroll reset keys; saveable modal draft helpers | SOURCE: retain independent state per actor/page and verify process recreation | `RebirthNavigationStateTest`, `ReforgeDraftRestorationUiTest`, `UiDraftStateTest` | PENDING |
| Prioritize horizontal gestures correctly | Horizontal tab/filter/text rows; modal forms; weapon slider; no page gesture | GAP: pager must defer to child horizontal gestures and not take over dialogs/sliders/selection | No baseline page-gesture conflict suite | PENDING |
| Customize quick actions | No quick-action preference or reorder UI found | GAP: persist action IDs/order; long-press drag plus move-up/down alternatives | No baseline coverage | PENDING |
| Create character → configure → reopen/edit | ReforgeCharacterEditor writes playable character; name/level enough; optional stats; only pinned legacy-Artificer capacity configured here | SOURCE: guided stages; never infer unknown stats; establish campaign association at same completed workflow | `ReforgeCharacterUiTest.newCharacterNeedsOnlyNameAndRetainsUnknownStats`, `PlayerFirstJourneyInstrumentationTest` | PENDING |
| Character advancement | Explicit advancement preview; does not implement complete compendium-driven class progression | SOURCE: retain accepted manual progression and clearly identify unsupported class automation | `ReforgeCharacterUiTest`, `PlayerFirstJourneyInstrumentationTest` | PENDING |
| Adopt established profile | Character-scoped preview, source fingerprint, explicit mechanics review, preserved original records | SOURCE: keep review; detect later source divergence rather than silently overwrite | `ReforgeLegacyAdoptionTest`, `ReforgeLargeCampaignSelectionTest`, native large-selection test | PENDING |
| Roll ability/save/skill/initiative | Sheet rows open reviewable dice dialog; roll persists with revision | SOURCE: retain dice ownership distinction and explicit save; contextually accessible actions | `ReforgeDiceOutcomeUiTest`, `ReforgeCharacterUiTest.fullSkillRowStartsAReviewableRollWithoutOpeningADisclosure` | PENDING |
| Attack and damage | Playable attacks route through rules; specialized armor remains another engine surface | SOURCE: unify action entry while preserving distinct accepted mechanics | `ReforgeIndependentAttackUiTest`, `ReforgeDiceOutcomeUiTest`, rules suite, `CombatDraftSafetyTest` | PENDING |
| HP/temp HP/conditions/concentration | Vitals and Combat open dialogs; no incidental healing; concentration end explicit | SOURCE: preserve and expose from quick actions | `ReforgeCharacterUiTest`, `ReforgeRepositoryTest`, `VisualRebirthJourneyInstrumentationTest` | PENDING |
| Turn/resource spending | Optional turn economy; explicit next turn; counters support use-one and Hit Dice | SOURCE: one authoritative view; no reset on navigation | `ReforgeRepositoryTest`, `ReforgeHitDice` rules coverage, native player journey | PENDING |
| Item create → equip/attune → edit effects | Playable ItemDefinition + ItemInstance inside character; review effects, equipment preview, capacity preservation | SOURCE: keep rules engine and IDs; write through one item relationship service | `ReforgeCharacterUiTest.equipPreviewIsReadOnlyAndConfirmationPreservesDamage`, `ReforgeInventoryFlowTest`, rules tests | PENDING |
| Add existing campaign item | Explicit copy with provenance and no automatic magic | SOURCE: replace copy-centric UX with identity-backed association and explicit conflict/review handling | `ReforgeCampaignItemBridgeTest`, `ReforgeCharacterUiTest.copyingCampaignItemIsExplicitAndKeepsOriginalIdentityWithoutMagic` | PENDING |
| Transfer/split/remove playable gear | Reforge item-management dialog; transactional transfer; remove retains history/source | SOURCE: preserve ownership semantics and stale target protection; avoid a second owned physical item | `ReforgeItemTransferTest`, inventory rules tests | PENDING |
| Containers/favorites/sort/group/drag | Item state/location available; favorite set in editor; no full container move/reorder journey in main gear list | GAP: explicit valid container targets and tap alternative; establish domain model before drag UI | Partial inventory rules coverage; no full gesture journey | PENDING |
| Campaign item create/edit/archive/research | Ledger editor, discoveries, history, owner/location strings and typed placements | SOURCE: retain archive/provenance/history; contextual owner defaults and shared item detail | `CampaignLedgerPageTest`, `CampaignLedgerTest`, `ReforgeInventoryFlowTest` | PENDING |
| Companion create → associate owner | Name-only campaign creation; associations are separate relationships | SOURCE: guided identity/owner/campaign context; all unknown stats stay unknown | `CompanionCreationTest`, `PartyPlayerFirstTest.addingCompanionFromRosterOpensNewSheetAndPreservesUnknownStats` | PENDING |
| Companion stats/forms/resources | Profile, typed sheet, tracked counters and optional playable sheet | SOURCE: define authoritative fields and review ambiguous duplicates; one stat block UI | `PartyPlayerFirstTest`, `CampaignPagesV2Test`, `PartyRecordsTest` | PENDING |
| Companion combat | Review/open playable sheet; attack adapter preserves source and can reject unsupported multiattacks | SOURCE: direct play destination using selected companion identity; no main-actor fallback | `ReforgeCompanionActionsTest`, pinned-companion cases in `ReforgeCharacterUiTest` | PENDING |
| Companion equipment/currency | Typed placement transactions and wallet records in campaign store | SOURCE: connect to same playable item representation and recalculation | `PartyPlayerFirstTest.equipmentTransferUsesOneItemAndPreservesUnknownQuantity`, wallet/purchase tests | PENDING |
| Spell add → prepare → cast → slots → rest | Per-character spell definitions; preparation filters and reviewed cast confirmation; rest explicit | SOURCE: retain cast gating and resource transactions; unify contextual creation | `ReforgeCharacterUiTest.spellbookSearchAndFiltersPreserveTheReviewedCastingWorkflow`, rules tests | PENDING |
| Homebrew feature create/edit/use | Identity editor holds features; resources/effects separately represented | SOURCE: expose Features page; route supported resource/effect actions; label descriptive-only capabilities | `ReforgeContentUiTest`, `ReforgeDraftRestorationUiTest`, rules tests | PENDING |
| Campaign create/switch/membership | No campaign creation/switch destination in recovered shell | GAP: requires explicit campaign lifecycle/associations; never fabricate membership from a name | No baseline complete UI journey | PENDING |
| NPC/location/group → relationships | Connections supports entities, fact/milestone append and typed links | SOURCE: context-aware creation and deep-link navigation; unlink preserves target | `ConnectionsPageV2Test`, `ReforgeCampaignConnectionsTest`, `CampaignRecordsTest` | PENDING |
| Encounter create/add actors/run | No encounter-builder destination; specialized weapon has its own turn budget | GAP: implement deliberate encounter lifecycle without reviving retired enemy/weapon assumptions | No baseline complete UI journey | PENDING |
| Crafting project → materials → output | Blueprint remains descriptive until explicit atomic craft commit; material references not possession | SOURCE: retain accounting safeguards; use same resulting item ID in inventory | `CampaignPagesV2Test` craft preview/cancel/stale/retry cases, `CampaignRecordsTest` | PENDING |
| Notes/rich text/checkpoint | Structured notes draft recovery, rich text, typed checkpoints | SOURCE: keep exact text/pending choices; reusable editor and direct context links | `ReforgeNotesUiTest`, `ReforgeNotesRecoveryDecisionTest`, `PartyPlayerFirstTest.checkpointUiRetainsExactPendingChoiceAndUnknownGameTime` | PENDING |
| Global search → act on result | Aggregated local documents; readonly body dialog | GAP: typed destination/open action, canonical de-duplication; preserve imported source distinction | `ReforgeSearchIndexTest`, `ReforgeSearchSnippetTest` | PENDING |
| Reference/import/conflict review | Separate preserved source records with explicit imported archive preview | SOURCE: retain provenance and unresolved review; no autoapproval | `CampaignArchiveUiV21Test`, `CampaignConflictAuditUiTest`, archive schema tests | PENDING |
| Backups and restore | Playable, campaign and weapon are separate formats; bounded full-campaign export | SOURCE: preserve all formats; coherent backup home must not imply unified snapshot until proven | `ReforgeBackupUiTest`, `CampaignBackupUiSafetyTest`, `WeaponHistoryBackupUiTest`, `HistoryPreservationTest` | PENDING |
| Owner sync/pairing/pause/grants | Scoped authenticated campaign connection; playable sheets not automatically included | SOURCE: preserve grants/receipts/revisions and private identity; do not imply UI integration equals network synchronization | `CampaignConnectionUiTest`, `CampaignSyncDomainTest`, native sync/HTTPS tests | PENDING |
| Prompt create/edit/duplicate/archive/copy | Persistent prompt repository with variable preview | SOURCE: preserve functional management; shared editor shell where useful | `ChatGptPromptManagementTest`, `ChatGptPromptRepositoryTest`, `ChatGptToolkitTest` | PENDING |
| Appearance/accessibility | Shared 48dp buttons, heading/selected semantics, font scaling, haptic preference | SOURCE: retain; validate TalkBack order, contrast, long names, reduced motion and keyboard after rebuild | `PrimaryNavigationLayoutTest`, `PlayerFirstLayoutTest`, large-font cases | PENDING |
| Background/reopen/upgrade | Existing external observer journeys and update preservation tests | SOURCE: repeat on exact Genesis payload with realistic old fixtures, ordinary in-place upgrade, independent readback | `UpdateFixtureInstrumentationTest`, `UpdatePreservationInstrumentationTest`, `ReforgeRecoveryNativeTest` | PENDING |

## Reconstruction boundaries and recommended sequence

1. Make the application route state typed and explicit. Keep a small stable set
   of primary destinations. A selected actor owns a horizontal pager for Sheet,
   Actions, Spells, Inventory, Features and relevant companions/resources.
   Save per-actor/page scroll, filter and detail position. Taps and swipes must
   update the same pager state. Back closes detail/editor first, then returns
   to its parent; it should not replay a long history of lateral swipes.
2. Resolve canonical identity/association before replacing creation forms.
   UI must call one domain operation for “create and assign.” Preserve original
   source rows and explicit approvals; don't silently copy mechanics or claim
   all stores now synchronize. A first safe increment can expose consistent
   projection of known linked records while conflicts remain reviewable.
3. Use one creator/editor shell with a short Basics stage, optional Details,
   Relationships and Mechanics stages, and a clear save result. Context carries
   campaign, owner and container IDs; defaults are visible/changeable. Durable
   drafts must retain their target and expected revision. Cancellation is
   distinct from archiving, unlinking and deletion.
4. Build common quick actions on existing domain commands, not duplicated UI
   arithmetic. Persist stable action IDs and ordering; accessible reorder
   buttons accompany drag. Ordinary navigation works without setup.
5. Preserve specialized armor, Eek, crafting, source review, prompts, backup and
   connection functionality. Move infrequent diagnostics/provenance behind
   disclosures; keep context-sensitive mechanics accessible during play.
6. Audit both source and runtime for the final build. The CSV is the baseline
   checklist; update workflow result rows with actual tested artifact/commit,
   fixture, test name and evidence location. Do not mark all 1,173 sites passed
   because a small selection of journeys passed.

## Required runtime fixture matrix

Use isolated fictional records only. At minimum cover:

- Empty install; name-only character and companion with unknown HP/stats.
- Existing adopted character with unchanged and later-diverged campaign source.
- Two characters and two companions, with distinguishable HP/resources and names.
- One linked physical item, split stack, unattuned/attuned equipment, conditional
  effects, unapproved effects, conflicting legacy ownership and archived record.
- Known/prepared/always-prepared spells; empty/current/unknown slots; insufficient
  resource/action cases; cancellation and stale revision during confirmation.
- Long names, multiline notes, keyboard visible, 200% font, landscape, screen
  reader actions, disabled animation scale and haptics disabled.
- Horizontal page flick, tab-strip scroll, diagonal/vertical scroll, text
  selection, slider drag, item/quick-action long-press drag and system edge Back.
- Activity recreation, background/foreground, process death, failed save, retry,
  app restart, old-APK → new-APK update, and preserved IDs/source/raw histories.
- Large synthetic campaigns with measured rendering/scroll/memory behavior.

No actual Galaxy S26 or Android 17 result is established by this source audit.
