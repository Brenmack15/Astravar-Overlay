# Astravar 2.1 rules and source boundaries

New play uses one permanent internal nexus. `Engine.plan` and `Engine.commit` reject Shatter, including attempts to replay an old plan as a new action. `Engine.install` rejects spare-core swaps and replacements. Charging targets only the installed internal reservoir and requires no redundant physical-touch confirmation. Legacy core lists, Shatter enum values, saved expressions, raw faces, payloads and audit snapshots remain readable. Advancing game time never completes an old replacement installation automatically. A legacy destroyed/uninstalled state exposes a one-time **Review legacy nexus** action in Config → Weapon resources & recovery. The player selects an existing intact record and enters the resolved source; adoption retains every legacy core/payload byte, the chosen record's overload, and all attunement/resources/clocks. Only obsolete active-installation flags are cleared. An adoption marker prevents using the review as a general swap. If all legacy cores are destroyed or absent, a separate explicit confirmation can record an already-established, intact, currently available nexus with an empty reservoir under a new stable ID. Unknown readiness or reservoir contents must be resolved first. This never revives a destroyed record, crafts an item, assumes attunement or replenishes slots/HP. Source and prior topology survive backups and audit. Identical retries change nothing.

## Melee

The accepted 11 October Armor Mk.I reference supplies the integrated melee architecture: one attack within the Attack action, 5-foot reach, Force damage plus a Guardian Thunder component, and an optional unlocked elemental selector. Spell-Imbued Gauntlet replaces the selector with approved instantaneous spell damage and the existing per-spell-level overcharge dice. Criticals double each direct die pool and retain flat modifiers once. These operations share the ranged attack's persisted costs, revisions, immutable snapshots, roll history and advantage/disadvantage implementation.

Current attack bonus, Force expression and ability modifiers are an explicit versioned `MeleeProfile` with a source and approval. No current character sheet or resources are seeded by an upgrade. Missing, unapproved or conflicting Intelligence configuration keeps melee locked with an explanation. Changes do not rewrite older rolls or the existing ranged profile.

Charged Gauntlet is a separate Bonus Action before the attack. It persists across process restart, adds the configured STR + DEX + INT modifier sum only to the next melee attempt, and is consumed on that attempt even on a miss. A ranged attack leaves it unused. The player-confirmed next weapon turn expires an unused charge without advancing in-world time. The modifier never doubles on a critical. Its damage type is not specified in the supplied reference: charging remains locked until a sourced type is explicitly configured. On a hit the reference calls for a DM-entered Dexterity save against the configured spell DC; failure pushes 10 feet. The app never rolls that save or invents collision damage. The normal Guardian rider remains a contextual rule, not automatic adjudication of another creature's actions.

Ordinary Guardian Thunder Gauntlet is a separate five-foot attack with an explicitly sourced ordinary attack bonus and the configured Intelligence modifier on 1d8 Thunder. It remains usable while the advanced weapon network is overloaded, and adds no enhanced Force, selector, payload or Charged Gauntlet damage. It does not recover or change the overload clock. Missing ordinary accuracy keeps this fallback visibly locked instead of assuming enhanced accuracy.

The spell-firing count persists even when optional general attack-budget enforcement is off. Confirming the next weapon turn resets the count; reopening the app does not.

## Individual damage profiles

Each damage type can hold a versioned, sourced `DamageFormulaProfile`. Unapproved overrides inherit the shared default. Accepted overrides supply count, die size and flat modifier; old `ShotPlan.profile` snapshots retain their exact earlier formula. Advanced elements still require level 8 and explicit `ADVANCED_ELEMENTS` confirmation. Formula configuration never grants an unlock.

Force config replaces the existing primary Force pool. It never adds an extra Neutral selector die. An approval/source is required for an override; a proposal alone does not become accepted enhanced Force damage. Discharge continues to derive its Force count and size from the inherited primary expression and does not gain its flat bonus.

## Discharge conflict requiring reconciliation

No automatic migration changes the existing weapon profile or recorded resources. The inherited implementation uses 3 × primary Force dice, 60-foot targeting, a 15-foot radius and 600 seconds of explicitly recorded in-world overload. The later Armor Mk.I source describes a different fixed formula, range, radius and a once-per-Long-Rest limit while also retaining the ten-minute in-world overload. These differing rule versions require an explicit accepted configuration decision before changing existing live Discharge accounting. The release must disclose this unresolved conflict rather than silently substitute either record. No real-world countdown or assumed rest is introduced.

## Verification

Historical Shatter regressions construct archived snapshots in test-only fixtures; there is no production execution bypass. New tests separately reject planning/committing Shatter, preserve the archived snapshots, exercise melee costs/criticals/payloads, independent formulas, advanced gates, source locks, charge persistence and explicit in-world recovery. Only measured build results establish a pass.
