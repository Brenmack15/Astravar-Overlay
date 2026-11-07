# Functional parity and verification matrix

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

## Gameplay-essential gaps to track explicitly

A feature is complete only after domain tests, persistence wiring, user initiation and real UI verification agree. At this baseline the most significant gaps are general character-derived calculations, general checks/skills, general spell management, automatic equipment effects, form-aware companion execution and rich campaign presentation. Existing armor/weapon, import, wallet, history and private-sync systems must be reused or adapted rather than discarded.

A PDF import may transfer a snapshot only. It must preserve unknowns, show candidate changes, avoid treating old exported values as current resources, retain original source content and never describe itself as continuous synchronization.
