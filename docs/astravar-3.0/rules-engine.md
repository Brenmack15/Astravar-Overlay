# Reforge rules engine

Implementation: `rules/src/main/kotlin/com/yazsras/astravar/rules/reforge/`. The existing accepted Astravar weapon engine, snapshots, formulas, history codec and serialized enums remain unchanged. Reforge is a separately reviewed structured character workspace; calculating a projection never modifies either store.

## Contract and authority

`ReforgeCharacter` is a versioned, serializable aggregate with stable identity and revision. It separates base scores, permanent advancements, derived values, current resources, typed effects, item definitions, individually owned item instances, preparation state, attacks, conditions and explicit game time. Identity, features, feats, language/tool training, notes and artwork references are independent of mechanical effects. Feature prose never creates a bonus.

`null` means unknown, including HP, ability scores and resource availability. Zero is a known value. A missing ability remains unknown; it is never replaced by 10. A known lower bound does not establish an exact unknown score. An explicit SET effect can establish an effective score without changing its permanent baseline.

Rule families explicitly distinguish revised core and legacy core. Class progression is an independent version string. A generic character starts with `unconfigured`; legacy Infusions Artificer spell progression is available only with the exact `legacy-artificer-infusions-2020` pin and an explicitly supplied class level. Other classes use reviewed slot maxima overrides. No class, subclass, infusion, feat or companion update is inferred from a name or narrative.

Only OFFICIAL and ACCEPTED_HOMEBREW effect sources activate. PROPOSED, UNVERIFIED and RETIRED mechanics remain readable but inactive. The caller and repository preserve accepted-source provenance; choosing an acceptance status in an editor is an explicit record edit, not AI adjudication.

## Numeric resolution and stacking

The calculator validates the aggregate, resolves active equipment and source eligibility, evaluates declarative conditions, then suppresses duplicate benefits by stacking key (or stable effect ID). Priority descending, numeric magnitude descending and stable identity break ties deterministically. Duplicate owned instances of the same definition do not duplicate its benefit. Conflicting effect IDs and composite cycles are rejected.

Numeric operations are explicit:

| Operation | Meaning |
| --- | --- |
| SET | Replace the baseline using the highest-priority setting |
| ADD | Add after the chosen baseline or setting |
| MINIMUM | Raise a known value to the specified floor |
| MAXIMUM | Cap a known value |

An effect whose wording means “unless already higher” must use MINIMUM, not SET. The engine does not guess item wording. Ability-maximum adjustments have a distinct numeric target. Every projection includes the actual applied source lines; the inspector does not maintain a parallel explanation string. Checked addition rejects integer overflow instead of silently wrapping.

Targets include abilities and caps, saves, skills, initiative, AC, maximum HP, speeds, attack and damage rolls, spell attack/DC and resource capacity. Skill proficiency and Expertise use separate typed payloads. All 18 standard skills are projected even without training, so item-granted proficiency participates in the same calculation.

## Item activation

Item definitions and owned instances are separate. CARRIED/STORED does not activate equipped effects; WORN/HELD does. Held-only, attuned-only, stance, creature type, damage type, contextual tag, successful-hit, charge-available, once-per-turn and expiry gates are explicit data. Missing context or unknown charges fail closed.

Equip validates a single instance, slot occupancy, level/ability requirements and integrated-donor restrictions. Attunement validates configured capacity and explicit per-definition attunement costs, including a reviewed zero-cost composite exception. Donors referenced by a composite never activate as independent equipment, even if an older record still marks them worn. No donor effect is inferred or copied onto a composite.

`previewEquipment` returns before/after projections and a proposed value without saving. Equipment edits preserve outstanding damage and existing resource availability. Increasing a capacity grants no uses, spell slots or temporary HP.

## HP semantics

The HP baseline records both a known maximum and the Constitution modifier already included at the established level. A derived Constitution change adjusts that maximum by `level × (new modifier − baseline modifier)`. A missing baseline modifier disables this inference and produces a visible warning.

The isolated acceptance fixture demonstrates permanent STR 8/CON 14/INT 21 and maximum 66. A reviewed armor effect sets STR 21/CON 16/AC 20, deriving maximum 75 at level 9. With current HP 42/66, outstanding damage is 24: equipment produces 51/75 and removal returns 42/66. This preserves the same missing HP; it is not a rest or refill. Damage beyond a reduced maximum survives a zero clamp and returns correctly on re-equipping. Explicit unknown current HP remains unknown even if an imported record carries older damage bookkeeping.

Damage requires known current and temporary HP. Healing requires known current and maximum HP. Temporary HP replacement is a separate explicit operation and does not stack. Equipment cannot grant temporary HP merely because a passive payload exists; activating an ability must call the explicit grant operation.

## Attacks and dice

`AttackRules.preview` derives attack modifiers and typed damage components, qualifies riders, applies conversions, doubles critical dice while retaining flat modifiers once, and previews charge costs. An independent companion attack may use a reviewed fixed to-hit value without requiring an inferred ability or adding proficiency a second time.

`resolveDamage` validates every supplied component total, groups by damage type and actual applicable defense set, and applies immunity or resistance followed by vulnerability. Same-type magical and nonmagical packets combine before rounding when their applicable defenses are the same; nonmagical-only defenses split them when necessary. Duplicate resistance/vulnerability does not stack.

Attack-roll bonus charges are spent on an attempted attack. Damage-rider/conversion charges and once-per-turn riders are spent only on a confirmed hit. Misses preserve hit-only charges. Combined requirements are validated against the same current pool. A stale or changed preview cannot commit. Repository command receipts provide durable idempotency; pure domain values do not claim a save occurred.

`SecureDice` uses `java.security.SecureRandom`, retains every die face and kept/discarded status, validates manual-entry cardinality/ranges, and supports bounded signed multi-die formulas. Advantage/Disadvantage applies to a single positive d20, while other dice in the formula remain ordinary. Opposing sources cancel. No projection, restart, refresh, retry or clock draws a die. UI initiation is required.

## Spellcasting, resources and rests

Prepared, known, always-prepared and cantrip states are distinct. Legacy Artificer preparation uses its pinned Intelligence-plus-half-class-level rule. Spell attack and DC use distinct targets; a spell-attack focus bonus never automatically increases DC. Slot maxima do not establish current availability. Casting previews validate a known available slot/charge, upcasting level, preparation and concentration replacement, and mutate nothing. Commit revalidates the exact preview against its revision. Optional turn tracking enforces action budgets and revised core's one-expended-slot-per-turn rule. Legacy bonus-action spell combinations still require a table legality check; that policy is disclosed rather than silently converted.

Resource pools separately store capacity, nullable availability, recharge trigger, recovered amount, optional die size and explicit permission to restore an unknown count. Long Rest also satisfies Short Rest pool recovery, but does not trigger dawn-only recovery. No rest is inferred from time, startup, edits or installation. Explicit rest confirmation means the user has confirmed a completed eligible rest; minimum recorded duration is one hour for Short Rest/eight hours for Long Rest. Known HP and known configured slots legitimately recover on that Long Rest. Unknown HP/slots/pools remain unknown by default. Temporary HP expires and Exhaustion decreases by one. Timed effects expire only under explicitly advanced game time; rest-expired definitions are disabled and retained rather than deleted.

Hit Dice can be configured as a resource pool with its die size and rule-version-appropriate recovery amount. `previewHitDie` and `spendHitDie` validate an explicitly confirmed eligible Short Rest, a known available die, a valid player-entered/rolled face, known HP and Constitution, then heal the nonnegative face-plus-Constitution result and consume one die. The native repository must persist the roll and resulting value atomically. The helpers never roll, advance time or complete a rest. Starting a ritual/casting interval, exact rest eligibility, interruption rules and custom shortened rest durations are not automatically adjudicated by this first engine. Rest confirmation does not prove food, sleep, prior-rest interval or eligibility from world state. Do not use this helper to claim autonomous DM adjudication.

## Conditions

Standard condition projections include Poisoned check/attack disadvantage; Blinded/Prone attack disadvantage; Restrained speed zero and Dexterity-save disadvantage; appropriate immobilized speeds; Petrified defenses; and version-pinned Exhaustion effects. Revised Invisible initiative advantage and inherited Incapacitated initiative disadvantage are included. Frightened and attack invisibility/grappled effects require explicit context tags when their situational predicate is not otherwise known. Condition immunity suppresses retained condition effects without deleting the historical condition record. Concentration ends when an incapacitating condition is explicitly applied.

Automatic-failure checks are exposed through `ConditionRules.rollRestriction` so the UI does not roll an automatically failed sight/hearing check or Strength/Dexterity save. Incoming attack advantage, range-dependent criticals, unseen targets, concentration saves, environmental adjudication, death saves and movement path legality still require the appropriate player/DM decision. A stored condition label is not a complete encounter simulator.

## Verification and sources

`ReforgeAcceptanceTest` exercises A–F examples, reversibility, zero/unknown distinctions, source approval, composite suppression, typed damage/critical/defenses, charge timing, preparation/concentration, explicit rest/Hit Dice recovery, expiry, generic class safety, serialization, overflow rejection and actual dice validation. Independent adversarial tests supplement it. Executed counts belong in `verification.md`; source code presence is not a passing test result. Imported aggregates have explicit collection/effect-expansion limits, checked arithmetic, and an iterative composite-cycle detector; they cannot silently wrap values or overflow the call stack from a long donor chain.

Primary sources reviewed November 7, 2026:

- [Official revised Rules Glossary](https://www.dndbeyond.com/sources/dnd/br-2024/rules-glossary): Advantage/Disadvantage, attunement, conditions, passive scores, resistance, rituals and recovery definitions.
- [Official revised Playing the Game](https://www.dndbeyond.com/sources/dnd/br-2024/playing-the-game): damage, same conditions and resource ownership.
- [Official legacy Artificer introduction](https://www.dndbeyond.com/posts/854-artificer-101-a-beginners-guide-to-making-magical): legacy preparation formula and separate class identity. This historical article mentions an Armorer playtest; that playtest wording is not used as an accepted mechanical source.
- [Official SRD 5.2](https://media.dndbeyond.com/compendium-images/srd/5.2/SRD_CC_v5.2.pdf): resistance/vulnerability ordering and no repeated stacking.

The level-9 slot example 4/3/2 is independently established by the owner's fixture. The entire commercial legacy Artificer table could not be reread through unauthenticated official access in this run; wider progression must retain that verification limitation. No paid compendium, class prose or third-party rules package was copied into the application.
