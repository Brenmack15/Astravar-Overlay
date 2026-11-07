# Functional parity and verification matrix

## Historical research baseline

Research date: 2026-11-07. Astravar source inspected at baseline commit `852bbd48af9b6c70be5ce44d5f898d1224ca0e7c` before integration. This matrix is a baseline/research assessment; source presence is not a successful runtime acceptance test. Final implementation and test status belongs in the coordinating release report.

Evidence keys: **Live** = personally exercised in official public Presto UI. **Video** = observed in owner-supplied recording. **Official** = documented supported workflow. **Untested** = no live owned-character verification. Public UI details and limitations are in [beyond-research.md](beyond-research.md).

| Capability | D&D Beyond evidence | Astravar baseline evidence | Reforge requirement / acceptance boundary |
|---|---|---|---|
| Character landing and identity | Live / Video | `CharacterOverviewV2` and home shell | Open directly on active character; preserve artwork and identity |
| Base/effective ability scores | Live layout; editing untested | Manual score + separate magical modifier | Typed effects, inspector, reversible equipment calculations; Test A |
| Ability checks | Video rolls | Existing dice engine is weapon-oriented | Explicit ability-roll initiation with current derived modifier |
| Saving throws | Live layout / Video | Companion manual sheet references | Derived proficiency/effects and conditional notes |
| Skills and Expertise | Live table / Video | Manual `PartySheetData` skill entries | Skill/tool rolls, proficiency and Expertise from typed state |
| Passive senses | Live / Video | Manual sheet senses | Derived supported passives, preserve custom senses |
| Initiative | Live / Video | Manual initiative reference | Derived Dex/effects with explicit roll |
| Dice formula and history | Video normal/advantage history | `DiceRoller`, `CombatHistoryPage` | General dice, persisted faces, transparent breakdown |
| Advantage/disadvantage | Video advantage | `RollModeControl` present | Cancellation and situational source explanation |
| Attacks and damage | Live rows / Video rolls | Existing armor/weapon attack engine | General actions plus typed components; preserve accepted engine |
| Damage resistance/immunity | Video defense section; outcome untested | Existing custom damage machinery | Per-type resolution, no duplicated resistance; Test C |
| Action/bonus/reaction grouping | Live filter / Video | Existing combat screens | One-tap combat actions and visible costs |
| HP / temporary HP | Live controls / Video | Editable profile HP | Nullable current HP, no refill when maximum changes; Test B |
| Conditions and concentration | Live headings / Video; mutation untested | Primarily manual sheet text | Typed reversible effects with restrictions; Test F |
| Class/subclass and progression | Live identity/features; leveling untested | Manual identity/progression | Explicit rules version, legacy Artificer retained, reviewed advancement |
| Feats and proficiencies | Live grouped features / Video | Manual sheet proficiencies | Structured grants without copying commercial catalog |
| Known/prepared spells | Video / owner mutation untested | Payload templates and free-text spellcasting | Distinct known, prepared, always prepared, cantrip states |
| Spell attack and DC | Live / Video | Independent weapon profile statistics | Calculated spellcasting projection and item-specific effects |
| Spell-slot use and upcasting | Live CAST detail preview / Video | Existing slots and reservoir charge workflow | Preview cost; consume once after confirmation; Test E |
| Spell concentration | Untested in live example | Manual text | Reject incompatible casting or explicitly replace concentration |
| Short / Long Rest | Official/video resource labels; rest execution untested | Existing custom resource mechanics | Eligible-only recovery, duration/restrictions preview |
| Inventory search and filters | Live search | `PartyEquipmentV2` placement view | Fast name/type/container filtering, useful empty results |
| Item details and properties | Live drawer | Existing inventory records and detail forms | Rich readable detail and optional history |
| Equip/unequip effects | Live state visible / mutation untested | Placement state exists; automatic derived model limited | Validate, preview, commit, recalculate reversibly |
| Attunement | Live dedicated area / Video | Recorded equipment state | Independent attunement gate and limits; Test D |
| Containers and quantities | Live / Video | `ItemPlacement`, persistent identity | Stable instances, weight/quantity, valid containment |
| Charges and consumables | Live item note / Video | Existing custom counters | Atomic consumption and correct recharge |
| Currency | Live coin summary / Video | Party wallet and ledger | Retain accounting history and ownership |
| Companion sheets | Live empty Extras / Video populated list | `PartyTypedSheetV2`, independent counters | Own abilities/actions/forms/resources; no generic replacement |
| Companion transformations | Untested | Manual upgrades/progression | Confirmed form definitions and stat projection; pending proposals inactive |
| Campaign/NPC rich content | Outside live example scope | Existing campaign pages render flat text | Safe rich text, links, sections, readable profiles; Test H |
| Global campaign search | Outside live example scope | Separate record views | One search across supported typed records |
| Local-first offline use | Not verified for Beyond | Room + local repositories present | Restart persistence and deterministic calculations; Test I |
| Export/import | Official PDF export verified in docs | Existing campaign archive/backups | Preserve formats, review user-provided file candidates |
| Continuous D&D Beyond sync | No supported API contract verified | App explicitly states no connection | No private endpoint adapter or false sync claim |
| Secure Astravar synchronization | Not a Beyond parity claim | Authenticated owner bridge, receipts and revisions | Retain scopes, conflicts, idempotency and honest statuses; Test J |
| Standalone operation | Beyond is standalone | Overlay permission/service controls present | Remove normal overlay dependency and background service; Test K |
| In-place Android upgrade | Outside Beyond scope | Existing package/signing/release checks | Owner certificate, version progression, database preservation; Test L |

### Historical gameplay-essential gaps

A feature is complete only after domain tests, persistence wiring, user initiation and real UI verification agree. At this baseline the most significant gaps are general character-derived calculations, general checks/skills, general spell management, automatic equipment effects, form-aware companion execution and rich campaign presentation. Existing armor/weapon, import, wallet, history and private-sync systems must be reused or adapted rather than discarded.

A PDF import may transfer a snapshot only. It must preserve unknowns, show candidate changes, avoid treating old exported values as current resources, retain original source content and never describe itself as continuous synchronization.

## Current Reforge implementation snapshot

Source reviewed at `30f0b9dda4e558246ecc481a4a97b23acf898cf0` on 2026-11-07. The historical table above records what motivated the work; the tables below account for the same 38 capabilities after implementation. They describe source-backed product scope, not complete D&D Beyond parity or physical-device acceptance.

**Implemented** means a usable workflow exists for its stated supported scope. **Partial** means meaningful functionality exists but a material requirement, integration or final acceptance remains. **Gap** means the capability is intentionally unavailable or still requires implementation. Totals: 20 implemented, 16 partial, 2 gaps. Tools, compendiums and encounter adjudication are not implied by a generic dice dialog.

The full pure-rules suite has **163 passing tests**. Focused native verification passed **56 cases in each variant**, and the final shared-harness regression passed **191 debug cases** and **189 release cases with two intentional skips**, without failures. Both lint variants passed without errors. These selected runs are distinct from complete CI execution and its separately recorded results. APK signing, package-manager update results and physical-device limits are tracked in [verification.md](verification.md) and [release.md](release.md); source presence is not installation evidence.

### Implemented within the stated scope — 20 capabilities

| Baseline # | Capability | Current behavior and scope | Implementation evidence |
|---|---|---|---|
| 1 | Character landing and identity | Character is the launcher destination; reviewed source-copy adoption, explicit character selection, identity/background/portrait editing and visible local-save authority. Adoption does not rewrite the original campaign profile. | `ReforgeCharacterSheet.kt`, `ReforgeIdentity.kt`, `ReforgeLegacyAdapter.kt` |
| 2 | Base/effective ability scores | Permanent scores and advancement remain distinct from reversible equipped effects. The inspector uses actual calculation lines; unknown and zero remain distinct. HP changes preserve outstanding damage. | `CharacterCalculator.kt`, `ReforgeGameplay.kt`, rules acceptance A/B |
| 3 | Ability checks | Tapping an ability offers an explicitly initiated check with the current derived modifier and applicable supported roll restrictions. | `ReforgeCharacterSheet.kt`, `ReforgeAbilityInspector`, `ConditionRules.kt` |
| 4 | Saving throws | All six saves use configured proficiency and active numeric effects, with supported condition restrictions and explicit roll initiation. Target-specific encounter decisions remain player/DM responsibilities. | `CharacterCalculator.kt`, `ConditionRules.kt`, `ReforgeCharacterSheet.kt` |
| 6 | Passive senses | Perception, Investigation and Insight passives derive from configured skills and applicable roll edges; movement modes and custom senses remain editable/readable. Narrative senses are not invented. | `CharacterCalculator.kt`, `ReforgeCharacterSheet.kt`, training editor |
| 7 | Initiative | Derived Dexterity/effects and versioned condition roll edges feed the initiative action; rolling is explicit. | `CharacterCalculator.kt`, `ConditionRules.kt`, `ReforgeCharacterSheet.kt` |
| 8 | Dice formula and history | Native custom formulas, supported die sizes, random/manual faces, retained/discarded results, ownership labels and immutable persisted breakdowns. Roll identity and originating character revision are retained. | `SecureDice.kt`, `ReforgeDice.kt`, `ReforgeStore.kt` |
| 9 | Advantage/disadvantage | Opposing sources cancel and repeated sources do not stack. Supported contextual effects feed rolls; the dice display retains both faces and the kept result. | `CharacterCalculator.rollMode`, `SecureDice.kt`, native dice dialog |
| 11 | Damage resistance/immunity | Typed components support immunity, nonstacking resistance/vulnerability and explicit magical qualifiers; same applicable defense groups combine before rounding. Incoming damage has a preview and confirmation. | `AttackRules.kt`, `ReforgeHpDialog`, rules acceptance C |
| 13 | HP / temporary HP | Damage/healing, temporary HP and confirmed direct reconciliation are separate. Unknowns persist, changing capacity is not a rest, and equipment changes preserve lost HP. | `GameplayRules.kt`, `ReforgeSessionDialogs.kt`, rules acceptance B |
| 18 | Spell attack and DC | Both derive separately from configured casting ability/proficiency and typed item effects. An attack-only bonus does not increase spell-save DC. | `CharacterCalculator.kt`, spell attack roll action, rules acceptance E |
| 19 | Spell-slot use and upcasting | Slot capacity and nullable available slots are distinct. The cast preview checks chosen level, configured costs and concentration; a revision-checked confirmation consumes resources once. Arbitrary spell damage/upcast effects are not automatically inferred. | `GameplayRules.previewCast`, `ReforgeCastDialog`, `ReforgeStore.kt` |
| 23 | Item details and properties | Native item editing/details preserve stable instances, descriptions, quantity, favorites, placement and supported typed effect summaries; rich source-record details remain available. | `ReforgeItemEditor`, equipment cards, preserved inventory details |
| 24 | Equip/unequip effects | Requirements, slots, acceptance, held/worn/attuned gates and composite-donor suppression control supported typed effects. Before/after previews require confirmation and recalculate reversibly. The compact editor exposes a defined subset of the engine. | `GameplayRules.kt`, `CharacterCalculator.kt`, `ReforgeEquipmentPreview`, rules acceptance A–D |
| 25 | Attunement | Independent attunement gating, capacity checks and explicit reviewed composite costs; equipment alone cannot activate attunement-only properties. | `GameplayRules.attune`, item definitions, rules acceptance D |
| 31 | Campaign/NPC rich content | NPCs have readable identity/status, overview, known information, open threads and milestones. Native Markdown and safe Notion links hide raw markup; original content/provenance remain recoverable. Remote images require a tap. | `RecordPresentation.kt`, `ReforgeRichText.kt`, `design.md` |
| 32 | Global campaign search | Local search spans existing items, people/places, inventions, profiles and references plus configured workspace spells, attacks, equipment definitions and resources, with readable result previews. It is not an external compendium search. | `ReforgeSearch.kt`, `ReforgeSearchIndex.kt` |
| 33 | Local-first offline use | Actual installed owner code42, with no active default network, completed reviewed adoption, creation/editing, structured equipment activation/reversal, calculated secure rolls and cold restart. Disk/readback and reopened UI retained exact faces, selected actor, permanent scores, HP damage and effects. Source documents retain their explicit-save workflows; physical-device behavior is separate. | `ReforgeStore.kt`, `ReforgePreferences.kt`, native owner-APK acceptance I, `verification.md` |
| 37 | Standalone operation | Primary navigation is Character/Combat/Inventory/Campaign/More. Overlay service/permission declarations, launch controls and permission requests are retired; historical code/preferences are retained for reversibility. Native merged-manifest verification passed; final APK inspection remains separate. | `AndroidManifest.xml`, navigation/settings changes, `verification.md` acceptance K |
| 38 | In-place Android upgrade | Actual original-owner optimized code42 installed with install-r over actual owner code41 on isolated AOSP35. Permanent package/certificate and UID10148 match; all four original databases/50 tables/22 rows and all three settings files match after offline workflows. Physical S26/Android17 installation remains unperformed; this scoped package proof is not complete gameplay parity. | `reforge_inspect_apk.py`, `reforge_upgrade_fixture.py`, actual owner-APK acceptance L, `release.md` |

### Partial coverage or acceptance — 16 capabilities

| Baseline # | Capability | Implemented now | Remaining boundary / concrete next milestone |
|---|---|---|---|
| 5 | Skills and Expertise | All 18 standard skills derive from configured training, proficiency/Expertise and effects; training is editable. Tool proficiency labels are stored. | Tool checks currently use generic dice/formulas rather than a dedicated calculated tool-check workflow. Add ability selection, applicable tool training/Expertise and source explanations. Source adoption still requires training reconciliation. |
| 10 | Attacks and damage | Editable structured attacks use calculated or independent fixed to-hit values, typed dice, critical rules, qualifiers and resource costs. Hit/miss confirmation atomically saves dice and costs; independent companion attacks avoid double proficiency. | Specialized accepted armor weapons remain under More → Armor weapon. Unify their context without altering accepted mechanics. No target roster or deferred queue for unresolved hit-dependent costs exists; those attacks require a decision before saving. |
| 12 | Action/bonus/reaction grouping | Compact Combat exposes attacks, HP, conditions, limited uses, slots and explicit Action/Bonus Action/Reaction/attack availability. Only My next turn resets spent availability. | Feature descriptions/action labels are not a complete automatically granted action catalog; unified armor actions and richer action-category organization remain. |
| 14 | Conditions and concentration | Version-pinned supported condition effects, exhaustion, immunity suppression, explicit application/removal and timed/rest expiry update projections. Incapacitating conditions end concentration. | Incoming attack edges, automatic critical ranges, death saves, concentration saves and spatial/environmental decisions are not fully automated. Add explicit workflows rather than treating a stored condition label as complete adjudication. |
| 15 | Class/subclass and progression | Identity, pinned core/class versions and confirmed advancement are editable. Pinned legacy Infusions Artificer slot/preparation calculations remain separate from revised core. | No full licensed class/subclass/multiclass builder or automatic feature awards. Add versioned definitions/choice validation; wider commercial legacy slot-table verification remains limited by authorized source access. |
| 16 | Feats and proficiencies | User-authored features/feats retain stable IDs, acceptance, descriptions and optional resource links. Save/skill training and languages/tools are configurable. | Feature prose grants no bonus automatically; no complete feat catalog or acquisition prerequisite engine. Add reviewed structured feature grants and licensed definitions. |
| 17 | Known/prepared spells | User-authored cantrip, known, prepared and always-prepared states, versioned descriptions and legacy Artificer preparation limits exist. | No full spell catalog, automatic class lists/awards or complete known-caster policy. Preparation timing and all rest-related legality still need explicit policy rather than assuming every editor change is a gameplay preparation event. |
| 20 | Spell concentration | Cast preview requires explicit replacement when concentration conflicts; cancellation consumes nothing. Current concentration is visible and incapacitation ends it. | Concentration saves and ritual/long-casting interval tracking are not automated. Spell effects/targets/components remain player/DM adjudicated. |
| 21 | Short / Long Rest | Explicit eligible elapsed-time confirmation previews configured recovery; capacity/current/recharge/recovered amount remain distinct. Unknown counts stay unknown. Hit Dice support an initiated die roll, healing preview and atomic saved use/roll/HP update. | World-state eligibility, sleep/food, interruption rules, exact prior-rest interval, custom shortened rests and all class-specific recovery remain table-confirmed/configured. No lifecycle event completes a rest. |
| 22 | Inventory search and filters | Existing owned inventory remains searchable; global search includes both legacy records and structured equipment definitions. Equipment cards expose state, quantity, favorites and quick equip/attune. | New character equipment and legacy inventory remain separate reviewed copies. A single canonical inventory authority, richer shared filters/container navigation and consistent cross-view mutations require reconciliation. |
| 26 | Containers and quantities | Stable existing item placements/containers/ownership history remain intact; new owned instances support quantity and equipment state. | No complete bidirectional container/weight/stack authority across the old ledger and new mechanics workspace. Reconcile stable instance IDs and verify transfers/consumption without duplicates before cutover. |
| 27 | Charges and consumables | Typed pools retain nullable availability/capacity/recharge; attempts versus successful-hit charges and once-per-turn effects are distinguished. Existing consumable/ledger tools remain available. | A complete generic consumable transaction and automatic shared inventory/resource reconciliation are not implemented. More complex charge/effect authoring needs a reviewed editor or validated typed import. |
| 28 | Currency | Existing wallet, ledger and accounting history are retained and accessible. | Currency is not newly mirrored into the character workspace. Integrate only after canonical ownership/transaction identity is established; do not infer balances or duplicate ledger entries. |
| 29 | Companion sheets | Reviewed companion copies use independent abilities/HP/training/attacks/resources. Confirmed simple source attacks have an explicit interpretation/review converter; fixed attack bonuses are editable. | Full latest-campaign reconciliation is not automatic. Complex attacks, shared-resource rules, specialized existing systems and source updates need explicit setup/integration; no generic monster substitution occurs. |
| 34 | Export/import | Existing supported archives remain; versioned local JSON workspace backup includes characters, immutable history, dice and notes drafts. Validation, preview and conflict-rejecting merge-only restoration protect IDs/history. | No D&D Beyond PDF parser is implemented. Add user-selected official PDF field-by-field candidates with preserved bytes, unknowns/source versions and explicit review. JSON backup is not a D&D Beyond import or synchronization channel. |
| 36 | Secure Astravar synchronization | Existing authenticated campaign bridge/scopes/revisions/receipts/revocation and anonymous fictional-only boundaries are preserved. Backend protocol suite passed; local command retries/stale conflicts have coverage. | New character workspace, dice and notes are explicitly local-only: no new-state continuous sync. A versioned private adapter needs outbox/receipts/conflict handling and independent Android readback before any separately authorized production cutover. |

### Explicit gaps — 2 capabilities

| Baseline # | Capability | Current decision | Next milestone / acceptance boundary |
|---|---|---|---|
| 30 | Companion transformations | No confirmed-form switching engine or UI is implemented. Independent companion sheets do not establish form HP/resource/equipment policies. | Define accepted generic separate/shared HP, damage carryover, equipment, armor-pool, reaction and resource contracts from current confirmed records; test reversible form transitions. Proposed upgrades remain inactive. |
| 35 | Continuous D&D Beyond sync | No suitable officially supported public character API/authentication contract was verified; no private-endpoint adapter or credential extraction was added. | Use manual structured editing, user-reviewed local files and optional user-initiated browser links. Reassess automation only if an official supported contract becomes available; never label snapshot import as continuous sync. |

### Interpretation and continuation

This candidate closes the central manual-statistics gap with an explainable engine and native rolls/equipment workflows, while preserving source campaign records. It does **not** establish that a fully reconciled owner character can complete every normal session without remaining setup or specialized tools. A representative session replay after reconciling training, spells, attacks, companions and resources is still required for that claim.

The implementation inventory is documented in [gameplay.md](gameplay.md), [rules-engine.md](rules-engine.md) and [design.md](design.md). Prioritized integration boundaries and acceptance milestones are in [known-limitations.md](known-limitations.md). Preserve this snapshot when later verification or implementation changes the status; update the coordinating release/checkpoint evidence with the exact tested commit.
