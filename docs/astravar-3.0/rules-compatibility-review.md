# Rules compatibility and Android toolchain review

Reviewed 2026-11-07 against the working tree at commit `31535a418a7047b01946d9ffa26609be4193a568`. This was an independent read-only source review. Findings were sent immediately to the coordinating and rules-engine agents. The final test report must record fixes and remaining limitations; this document does not certify subsequent code changes.

## Android toolchain

The repository pins AGP **8.13.2**, Gradle **8.13**, Kotlin **2.2.21**, JDK/JVM **17**, and compile/target SDK **36**, with min SDK **26**. Google's AGP 8.13 release notes explicitly list Gradle 8.13 and JDK 17, with maximum supported API 36.1. This combination is within that published support envelope. AGP 8.13.2 also documents its R8 Kotlin 2.3 support; that is not a reason to upgrade the existing Kotlin version. [T1]

The client's Android 17 metadata is a useful device claim, not a measurement of the phone or APK installation. Android's official release notes identify Android 17 as API 37. A target SDK is distinct from the runtime OS; targeting 36 does not itself establish incompatibility with a 37 runtime. Physical-device behavior still requires an attached device or explicit test evidence. [T2]

In this research shell `adb` was not on PATH, so no phone properties were read. This is a shell observation, not proof that no toolchain or device can exist elsewhere in the environment. Do not claim the Galaxy's installed version, operating-system build, owner certificate, or an in-place installation from metadata alone.

## Version boundaries

`RuleVersion` separates revised core rules from the string-pinned legacy Infusions Artificer class. `CharacterCalculator` only derives legacy Artificer slots and preparation when that exact class version is selected; other classes require explicit capacities. That is a useful conservative boundary. The implementation must retain accepted exceptions and never relabel a revised class, playtest class, or custom companion as the legacy class.

The public official 2020 Armorer guide confirms preparation uses Intelligence modifier plus half Artificer level rounded down, with a minimum of one, and permits changing the list after a Long Rest. The engine's preparation-capacity formula matches that rule. [R1]

The official Tasha contents page links the legacy class chapter, but unauthenticated access to that chapter redirected to the marketplace. Consequently this investigation did **not** independently reread the complete final 1–20 class slot table or all class-specific recharge features. Search results from official Unearthed Arcana PDFs are playtest content and were deliberately excluded as final-rule verification. The current accepted level-9 fixture's 4/3/2 capacities are preserved, but tests of a hardcoded table alone are not primary-source verification of every row. Future table expansion and multiclass calculations require authorized final-source verification. [R2]

## Confirmed implementation alignment

| Inspected code | Review result |
|---|---|
| `abilityModifier` | Uses floor division, including odd scores below 10; preserves unknowns separately |
| `proficiencyBonus` | Level-banded calculation remains independent from individual ability values |
| `rollMode` | Any Advantage and any Disadvantage cancel; repeated sources do not add dice. [R3] |
| `SecureDice.critical` + attack components | Doubles dice counts while retaining flat modifiers; component exceptions are explicit. [R4] |
| `resolveDamage` | Combines matching packets, suppresses repeated resistance, halves with downward rounding before vulnerability, and gives immunity precedence. This matches the reviewed damage order. [R4] |
| Legacy Artificer preparation capacity | Correct explicit legacy formula; always-prepared entries excluded from normal preparation count. [R1] |
| `previewCast` / `commitCast` | Pure preview, explicit concentration replacement, revision recheck, and post-confirmation consumption are good safety boundaries |
| `preserveCapacities` | Equipment projection retains lost HP and unknown current HP rather than inventing healing; campaign-specific policy remains explicit |
| Item effect acceptance | Proposed/unverified/retired sources stay inactive; composite donor suppression avoids simple duplication |

The first two rows are code-level mathematical observations. This review does not certify all class, item, or condition mechanics merely because the general calculation infrastructure exists.

## Substantive findings sent for integration

These findings refer to the inspected source snapshot, not necessarily the final integrated tree.

| Priority | Finding | Engineering consequence |
|---|---|---|
| High | Stunned missing from automatic Strength/Dexterity save-failure restriction. [R3] | Add to the restriction path and test; do not offer a die roll as a chance to succeed |
| High | Revised Invisible lacks initiative Advantage; Incapacitated lacks initiative Disadvantage. [R3] | Project version-specific initiative edges, including inherited incapacitation |
| Medium | Revised Grappled lacks attack Disadvantage against targets other than the grappler. [R3] | Use an explicit target-context condition; do not guess the target |
| High | Casting turn state does not track revised per-turn slot spending or legacy bonus-action spell restrictions. [R5][R6] | Keep core-version policies separate and test reaction/bonus/action combinations |
| Medium | `prepareSpell` acts as an unrestricted immediate edit. [R1] | Label as a record correction or gate gameplay preparation to eligible rest/time; validate spell level |
| High | Rest confirmation enforces duration but not start-HP, previous-rest timing, or interruption eligibility. [R3] | Add eligibility inputs/preview or clearly disclose manual adjudication before recovery |
| Medium | Existing conditions remain projected when newly gained immunity should suppress their effects. [R4] | Preserve the historical condition but suppress applicable mechanics while immune |
| Medium | Long casting and rituals have no casting-interval model. [R5][R6] | Require elapsed time and casting concentration or disclose this as manually adjudicated |
| Medium | Generic known-spell casting classes are represented by a model whose KNOWN state means unprepared | Add explicit class casting policy before claiming support for all classes |
| Medium | Target-side condition attacks/automatic criticals, death saves, and death-state handling are not a complete encounter adjudicator | Keep contextual manual controls and avoid claims of complete automatic combat rules |

Revised rest eligibility includes at least 1 HP to start, an eight-hour rest, and a sixteen-hour wait before the next start; resource triggers remain feature-specific. [R3] The current generic rest mechanism needs an explicit policy for Hit Dice and any nonstandard companion recovery. It must not automatically infer that every class resource recharges alike.

## Focused regression scenarios

1. Stunned character attempts a Strength or Dexterity save: restriction is visible; no random success.
2. Revised Invisible character rolls initiative: Advantage; an opposing source cancels it.
3. Revised Incapacitated or inherited incapacitation: initiative Disadvantage.
4. Grappled character attacks the grappler, another target, or an unknown target: only justified modifiers apply.
5. Same event has two odd, same-type damage components and resistance: combine before rounding; immunity yields zero.
6. An odd damage packet has both resistance and vulnerability: halve and round first, then double; the result need not equal the original.
7. Critical attack includes bonus cold dice plus flat damage: dice double, flats remain unchanged.
8. One revised turn casts an Action spell using a slot, then attempts a Reaction spell using a slot: enforce the selected turn policy; next creature's turn has separate state.
9. Legacy bonus-action cantrip still invokes the legacy restriction; do not substitute the revised slot-only rule.
10. An incomplete/interrupted rest or zero-HP start cannot masquerade as an eligible completed rest.
11. A character gains condition immunity while already affected: effects suppress without deleting audit history.
12. Legacy Artificer preparation capacity and slots use class level, while proficiency uses total character level; multiclass capacities remain explicitly unsupported unless verified.

These are recommended tests, not claimed executions. The rules agent owns implementing and running them where included in the release scope.

## Primary sources

Accessed 2026-11-07. Only official publisher/platform documentation was used for normative findings; forum answers and playtest texts were not used as final-rule authority.

- **T1:** Android Developers, [AGP 8.13 release notes, including 8.13.2 and compatibility](https://developer.android.com/build/releases/agp-8-13-0-release-notes).
- **T2:** Android Developers, [Android 17 release notes](https://developer.android.com/about/versions/17/release-notes).
- **R1:** D&D Beyond, [Artificer 101: Armorer, from Tasha's Cauldron of Everything](https://www.dndbeyond.com/posts/912-artificer-101-armorer-from-tashas-cauldron-of), 2020 official class guide.
- **R2:** D&D Beyond, [Tasha's Cauldron of Everything contents](https://www.dndbeyond.com/sources/dnd/tcoe); linked legacy chapter requires authorized access in this environment.
- **R3:** D&D Beyond Basic Rules, [Rules Glossary](https://www.dndbeyond.com/sources/dnd/br-2024/rules-glossary), relevant condition, Advantage/Disadvantage and rest entries.
- **R4:** D&D Beyond Basic Rules, [Playing the Game](https://www.dndbeyond.com/sources/dnd/br-2024/playing-the-game), critical damage, resistance, vulnerability and immunity.
- **R5:** D&D Beyond Basic Rules, [Spells](https://www.dndbeyond.com/sources/dnd/br-2024/spells), revised turn and casting-time rules.
- **R6:** D&D Beyond 2014 Basic Rules, [Spellcasting](https://www.dndbeyond.com/sources/dnd/basic-rules-2014/spellcasting), legacy bonus-action and extended casting rules.

## Review snapshot hashes

| File | SHA-256 at review |
|---|---|
| CharacterCalculator.kt | `f6b26c0f72772d1144f4bcc051bfdea3be96a54259b04589b5b9aa4045d17102` |
| ConditionRules.kt | `e35115b787100ddf05680d5462e46f3d695eca7cd8c08f4143665ceb14d5dec0` |
| GameplayRules.kt | `e7e13a31cc7c1c14e8a77582a98ec5c0c8bc8fd6ec66ee22d122b40fc5189025` |
| AttackRules.kt | `14a82bb53f84ab79f7dc21c433a494952c6db9448352901f47b40eb8b853ea28` |
