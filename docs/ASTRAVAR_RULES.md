# Astravar rules profile v1

Source: the supplied ASTRAVAR_ANDROID_MASTER_PROMPT, read in full before implementation. These are campaign homebrew mechanics, not a replacement official Artificer class. Later explicit player corrections take precedence over this editable profile. Past shots keep their profile snapshot; stored payloads keep their loading snapshot.

## Accepted

- Zerg must be attuned and a usable core installed. Starter character configuration is Artificer 5, INT +4, proficiency +3, save DC 15. Those values do not establish current resources.
- Base attack: INT + proficiency + enabled calibration. Base damage: 1d10 + INT + calibration Force. Range 60/180 ft, with ordinary long-range disadvantage. No physical bullet consumption. Neutral Force has no selector d8.
- Acid, Cold, Fire, Lightning, Poison, Thunder: +1d8 of the selected type. Selector changes are free, including between the two attacks of level-5 Extra Attack. Extra Attack is two ordinary attacks within one Attack action.
- Advanced elements unlock at 8; confirmed Dual Resonance at 10 permits two basic selector elements. Calibration applies once to base Force damage, never to blasts, spell overcharge, selector dice, or save DC.
- Discharge always costs one Action: 60 ft range, 15 ft radius, base 8d10 Force at level 5; core exhausted for 600 game seconds. An intact-core swap does not recover the removed core.
- Shatter always costs one Action: 120 ft range, **60 ft radius**, base 24d10 Force at level 5; core and ALL remaining payloads permanently destroyed. Only explicitly selected payloads contribute damage.
- Emergency scaling by Artificer spell progression: level 5–8 = 8/24d10; 9–12 = 10/30d10; 13–16 = 12/36d10; 17–20 = 14/42d10. Remaining slots and loaded scroll levels do not change this. Explicit override is available.
- A successful Dexterity save halves the single rolled Force total, rounded down, and takes zero optional spell payload/overcharge. Failed saves take full Force plus selected payloads. 43 Force +19 payload means 62 on failure, 21 on success before defenses. No ally exemptions or invented enemy statistics.
- Direct impact is optional: a pistol attack adds base weapon Force damage once, while the target still saves against the blast. An impact miss does not cancel the explosion or scatter it. An impact critical doubles only its base d10. Separate direct and blast damage packets round defenses independently.
- Stored payloads persist through rests, time, removal, restart, and updates until used, purged, overwritten, or destroyed. Loading snapshots actual spell/slot or documented scroll level, typed eligible damage, approval notes, source, core, and game time.
- Charge, purge, and overwrite use a Bonus Action. Charge requires confirmation of touch/availability; a slot OR a recorded scroll is consumed, never both. Overwrite selects the old payload explicitly; no refunds. Immediate slot imbuing is part of firing. Scrolls use the Bonus Action charge route.
- Only approved instantaneous damage transfers; conditions, ongoing damage, concentration effects, forced movement, summons, original area geometry, and unrelated riders do not. Multi-instance totals and unusual scroll eligibility require explicit approval.
- Normal intact swaps: Action at level 5, Bonus Action after confirmed level-10 upgrade. Post-Shatter installation: 600 game seconds initially, Action after confirmed approximately level-15 upgrade, Bonus Action after confirmed level-20 upgrade. These times never change activation costs.
- Spare completed cores are separately tracked; the UI assigns no carrying limit. Core readiness and weapon installation state are distinct.
- First prototype: existing pistol + cult crystal, 150 gp total, three focused game days, no checks. Replacement: rare Resonant Crystal +250 gp secondary materials (100+50+50+50), three focused days, appropriate tools/workspace. One week was a sourcing estimate. Logging work never automatically awards items or changes gold.

## Provisional and editable

- Spell-Imbued Shot: base Force + eligible approved spell damage +1d8 per actual slot/scroll level. Replaces ALL free selector dice, including Dual Resonance dice. Direct attack critical doubles the base, spell, and overcharge dice, never flat bonuses.
- One stored payload through 11; two after confirmed level-12 Dual Storage. Level 10 does not grant two spell slots. Release selection is explicit; unselected payloads survive ordinary shots and Discharge.
- One spell-powered firing per tracked turn initially. This implementation counts a blast that adds spell energy toward this provisional limit as well. Capacity, release limit, and spell firings per turn are editable; the UI labels the policy provisional.
- Upgrade eligibility is separate from player confirmation that an upgrade has been earned/installed. Milestone confirmations default off. Level gates still apply.
- The approximately level-15 replacement upgrade threshold can be edited.

## Assumptions / proposals

- +1 Arcane Calibration is enabled in the starter profile as an editable assumption: +8 attack, 1d10+5 Force. Proposed +2 at 10 / +3 at 15 is disabled initially.
- Four/five simultaneous elements or payloads at level 20 is an unresolved direction. No such campaign cap is invented. Capacity/release override controls allow explicit table choices (technical input range 1–12).
- Level-20 synthetic manufacturing removes the natural crystal requirement only after confirmation. Cost/time remain unknown until explicitly entered; it is not free or instant.
- Direct pistol impacts beyond its 60 ft normal range show disadvantage, with the emergency ability's own absolute range limit (Discharge 60, Shatter 120). This targeting interpretation is documented, not an extension to 180 ft.
- Encounter action tracking is opt-in; it applies two ordinary attacks or one full Action, one Bonus Action, and the configured provisional spell limit. Starting a new tracked turn is an explicit player action.

No default resources, completed crafting, known spells, rolls, successful saves, rests, or campaign history are invented. The app records player-confirmed facts. It does not adjudicate the campaign or synchronize D&D Beyond.

## Player correction — automatic dice, 2026-09-28

The owner's explicit redesign request replaces manual-only rolling. Astravar rolls its own attack, base damage, selectors, approved instantaneous stored/immediate spell damage, overcharge and emergency Force dice. It never rolls another creature's saving throw. Raw faces and modifiers persist with costs in a single transaction. Damage dice are rolled even on a miss for transparency; missed direct damage is applied as zero. Costs are still spent as established above.

Normal uses one d20; advantage/disadvantage use two and retain both. Opposing advantage/disadvantage cancel, including long-range disadvantage. The selected natural 20 is critical and the selected natural 1 misses. Unknown AC preserves a non-adjudicated attack total and rolled damage, allowing another ordinary attack without a hit/miss form. A blast's direct impact with unknown AC still requires the DM's hit/miss decision because per-creature damage depends on that outcome.

Critical eligibility remains unchanged: double dice, never flat modifiers; base/direct spell/overcharge/selector dice double on a critical, emergency blast and blast payload dice do not. No new weapon, ammunition, charge or campaign resources are introduced.
