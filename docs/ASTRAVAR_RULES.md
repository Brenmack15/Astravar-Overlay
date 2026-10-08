# Astravar rules — Force rules v2 (application 1.5.0)

Source: the supplied ASTRAVAR_ANDROID_MASTER_PROMPT, read in full before implementation. These are campaign homebrew mechanics, not a replacement official Artificer class. Later explicit player corrections take precedence over this editable profile. Past shots keep their profile snapshot; stored payloads keep their loading snapshot.

## Accepted

- Zerg must be attuned and a usable core installed. Starter character configuration is Artificer 5, INT +4, proficiency +3, save DC 15. Those values do not establish current resources.
- Base attack: INT + proficiency + enabled calibration. Primary direct damage is `N` d12 + INT + calibration Force; `N` is the persisted Base Damage Dice Count (currently 2, selectable 1–10 by a discrete slider in Rules & upgrades). The flat modifier applies once. Range 60/180 ft, with ordinary long-range disadvantage. No physical bullet consumption. Neutral Force has no selector d8.
- Acid, Cold, Fire, Lightning, Poison, Thunder: +`M`d8 of the selected type, where `M` is the independent persisted Elemental Selector Dice Count (default/current canonical 2, selectable 1–10). Selector changes are free, including between the two attacks of level-5 Extra Attack. Extra Attack is two ordinary attacks within one Attack action.
- Advanced elements unlock at 8; confirmed Dual Resonance at 10 permits two basic selector elements. Calibration applies once to base Force damage, never to blasts, spell overcharge, selector dice, or save DC.
- Discharge always costs one Action: 60 ft range, 15 ft radius, `3N` Primary Force dice, currently `3N`d12 Force; core exhausted for 600 game seconds. An intact-core swap does not recover the removed core.
- Shatter always costs one Action: 120 ft range, **60 ft radius**, seven times Discharge Force dice, currently `21N`d12 Force; core and ALL remaining payloads permanently destroyed. Only explicitly selected payloads contribute damage.
- Discharge Force dice = `3 × N`; Shatter Force dice = `7 × Discharge = 21 × N`. Both inherit the Primary Force die size. Level, spell level, slots and the old additive progression have no effect on current emergency damage. No flat INT/calibration modifier is added. The old emergency spell-level control is removed.
- Each selected unlocked element adds `M`d8 **once** to either explosion, including each eligible Dual Resonance element. Neutral has no selector component. Selector dice, spell payloads and overcharge are never multiplied by emergency scaling.
- A successful Dexterity save halves each actual rolled Force/selector component separately, rounded down; optional spell payload/overcharge still deals zero on success. Failed saves take each full component. Example: 71 Force +9 Lightning becomes 35 Force +4 Lightning on success. Do not reroll dice or combine types before halving. No ally exemptions or invented enemy statistics.
- Direct Impact is optional and separate: a pistol hit adds `N`d12 + INT + calibration Force; a critical uses `2N`d12 with the flat once. The explosion and its selectors never critical, including when the impact does. A miss does not cancel/scatter the explosion. Direct and blast damage packets round defenses separately.
- Stored payloads persist through rests, time, removal, restart, and updates until used, purged, overwritten, or destroyed. Loading snapshots actual spell/slot or documented scroll level, typed eligible damage, approval notes, source, core, and game time.
- Charge, purge, and overwrite use a Bonus Action. Charge requires confirmation of touch/availability; a slot OR a recorded scroll is consumed, never both. Overwrite selects the old payload explicitly; no refunds. Immediate slot imbuing is part of firing. Scrolls use the Bonus Action charge route.
- Only approved instantaneous damage transfers; conditions, ongoing damage, concentration effects, forced movement, summons, original area geometry, and unrelated riders do not. Multi-instance totals and unusual scroll eligibility require explicit approval.
- Normal intact swaps: Action at level 5, Bonus Action after confirmed level-10 upgrade. Post-Shatter installation: 600 game seconds initially, Action after confirmed approximately level-15 upgrade, Bonus Action after confirmed level-20 upgrade. These times never change activation costs.
- Spare completed cores are separately tracked; the UI assigns no carrying limit. Core readiness and weapon installation state are distinct.
- First prototype: existing pistol + cult crystal, 150 gp total, three focused game days, no checks. Replacement: rare Resonant Crystal +250 gp secondary materials (100+50+50+50), three focused days, appropriate tools/workspace. One week was a sourcing estimate. Logging work never automatically awards items or changes gold.

## Provisional and editable

- Spell-Imbued Shot: configured `N`d12 base Force + eligible approved spell damage +1d8 per actual slot/scroll level. Replaces ALL free selector dice, including Dual Resonance dice. Direct attack critical doubles the base, spell, and overcharge dice, never flat bonuses.
- One stored payload through 11; two after confirmed level-12 Dual Storage. Level 10 does not grant two spell slots. Release selection is explicit; unselected payloads survive ordinary shots and Discharge.
- One spell-powered firing per tracked turn initially. This implementation counts a blast that adds spell energy toward this provisional limit as well. Capacity, release limit, and spell firings per turn are editable; the UI labels the policy provisional.
- Upgrade eligibility is separate from player confirmation that an upgrade has been earned/installed. Milestone confirmations default off. Level gates still apply.
- The approximately level-15 replacement upgrade threshold can be edited.

## Assumptions / proposals

- +1 Arcane Calibration is enabled in the starter profile as an editable assumption: +8 attack, currently 2d12+5 Force. Base Damage Dice Count can be changed in Rules & upgrades; proposed +2 at 10 / +3 at 15 calibration is disabled initially.
- Four/five simultaneous elements or payloads at level 20 is an unresolved direction. No such campaign cap is invented. Capacity/release override controls allow explicit table choices (technical input range 1–12).
- Level-20 synthetic manufacturing removes the natural crystal requirement only after confirmation. Cost/time remain unknown until explicitly entered; it is not free or instant.
- Direct pistol impacts beyond its 60 ft normal range show disadvantage, with the emergency ability's own absolute range limit (Discharge 60, Shatter 120). This targeting interpretation is documented, not an extension to 180 ft.
- Encounter action tracking is opt-in; it applies two ordinary attacks or one full Action, one Bonus Action, and the configured provisional spell limit. Starting a new tracked turn is an explicit player action.

No default resources, completed crafting, known spells, rolls, successful saves, rests, or campaign history are invented. The app records player-confirmed facts. It does not adjudicate the campaign or synchronize D&D Beyond.

## Player correction — automatic dice, 2026-09-28

The owner's explicit redesign request replaces manual-only rolling. Astravar rolls its own attack, base damage, selectors, approved instantaneous stored/immediate spell damage, overcharge and emergency Force dice. It never rolls another creature's saving throw. Raw faces and modifiers persist with costs in a single transaction. Damage dice are rolled even on a miss for transparency; missed direct damage is applied as zero. Costs are still spent as established above.

Normal uses one d20; advantage/disadvantage use two and retain both. Opposing advantage/disadvantage cancel, including long-range disadvantage. The selected natural 20 is critical and the selected natural 1 misses. Unknown AC preserves a non-adjudicated attack total and rolled damage, allowing another ordinary attack without a hit/miss form. A blast's direct impact with unknown AC still requires the DM's hit/miss decision because per-creature damage depends on that outcome.

Critical eligibility remains unchanged: double dice, never flat modifiers; base/direct spell/overcharge/selector dice double on a critical, emergency blast and blast payload dice do not. No new weapon, ammunition, charge or campaign resources are introduced.

## Historical correction — slider and emergency scaling, 2026-10-03 (superseded damage formulas)

Base Damage Dice Count remains one persisted integer, now selected from 1 through 10. The full-app slider previews direct, Discharge and Shatter expressions while dragging and commits a whole count when released. App and overlay share the Room profile; no separate emergency setting exists. Direct critical dice use 2N with the flat modifier once. Elemental, payload and overcharge dice retain their existing rules; cantrip imbuing is not implemented.

New plans snapshot the final emergency count in `blastDiceCount`. Pre-v1.3 pending/history/archive shots without that snapshot restore the original level-only blast expression. Their faces and resolved damage remain unchanged, including unrolled committed blasts and explicit spell-level overrides. Old campaign profiles missing Base Damage Dice Count still migrate to 2; old shot profiles missing it retain their actual 1d10 rule. Modern backups retain both fields.

## Historical correction — independent elemental selector upgrade, 2026-10-06 (emergency exclusion superseded)

`elementalSelectorDiceCount: Int` controls only ordinary free selector damage, independently of `baseDamageDiceCount`. Both discrete 1–10 sliders live together in Weapon → Upgrades. Their combined preview derives from the same Profile Dice expressions as combat and updates during either drag; releasing a slider commits only its own integer field. The existing Force selection is never reset.

Ordinary Elemental Shot is `Nd10 + INT + calibration` Force plus `Md8` of the selected unlocked element. Confirmed Dual Resonance gives each basic element the full `Md8` contribution. Eligible criticals double both direct dice counts (2N d10 and 2M d8 per element), with flat modifiers once. Neutral Force, Spell-Imbued Shot, Direct Impact and emergency explosions gain no free selector dice. Payload and overcharge expressions, element unlocks, costs, and level/Force-driven emergency formulas are unchanged. Cantrip imbuing is absent.

Current/old campaign profiles without the selector field default to M=2. Old committed and historical shot profiles without it decode as M=1, including pending, event and archive snapshots; raw faces and results are not rewritten. Explicit modern values round-trip through JSON backups. The Room schema remains version 2, and app/overlay observe the same persisted campaign.


## Current correction — d12 Force and emergency multipliers, 2026-10-07

This correction supersedes the older Force die size, level/additive emergency progression and selector exclusion described in the historical sections above. `PrimaryForceRules.DIE_SIDES = 12` is the authoritative current die-size constant; `Profile.primaryForceDieSides` supplies it to base, direct impact and emergency dice. `forceRulesVersion = 2` is persisted alongside both independent dice counts. Rules version 1 is used only by historical/committed shot snapshots to preserve d10 expressions.

The Primary Force slider keeps its 1–10 count and now shows d12; the selector remains 1–10 d8. Previews use `Engine.components` and the current shared element selection, with no selector displayed for Neutral. Both full app and overlay allow elemental or Neutral explosions. Profile changes do not reset inventory, resources, encounter state, preferences or stored payloads. Cantrip imbuing remains absent.

Examples: N=4 gives 4d12+modifier direct, 12d12 Discharge and 84d12 Shatter; N=5 gives 5d12+modifier, 15d12 and 105d12. N=10 intentionally permits 210 independent d12 Shatter results. Summaries remain compact; expandable detail/history preserve every face. Spell damage keeps its own die sizes, including d10 where applicable.

The active profile is migrated on database read/import and on mutations (including Undo) without changing either selected count. Missing older N/M fields retain their established default migration rules. Historical event/archive profiles and committed shot profiles retain rules version 1, old selector eligibility, `blastDiceCount`, raw faces and resolved totals. The only remaining legacy level formula is in backup migration for pre-v1.3 plans that lacked a saved blast count. Room remains schema v2; there is no destructive migration or event-row rewrite.

## History retention correction — 2026-10-07

Keep only the newest 20 combat entries, including pending/rolled results shown by History. Automatically discard older entries in persistent storage. History stays newest-first and preserves all individual faces of retained large pools; the current-result card and overlay use the same bounded data. Duplicate archived accounting roll copies are not retained. This supersedes unlimited-history preservation; it does not alter any combat damage, resource, critical or save rule above.
