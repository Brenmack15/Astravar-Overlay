# Native gameplay implementation

The new Character destination opens a playable local-first sheet. Existing campaign profiles are offered as reviewed copies; a clean installation can create a character with unknown HP, abilities, spell availability, and charges. Adoption never changes the source campaign database. The sheet labels its actual local-only persistence state.

## Implemented workflows

- Ability checks, all standard skills, saving throws, initiative and spell attacks use the calculated projection. A statistic inspector shows the actual active calculation and a compact permanent-score editor.
- Explicitly initiated random or physical-face dice support formulas, Advantage/Disadvantage cancellation, ownership labels, raw faces, discarded faces, modifiers and immutable saved history. Opening a sheet or dice dialog never rolls. Pending results and their identities survive activity state restoration. A changed character revision blocks rebinding old dice to new statistics.
- Structured attacks roll calculated attack and typed potential damage together. Critical dice come from the engine. Hit/miss confirmation atomically saves exact dice and engine-produced resource/action changes. A no-hit-cost unresolved attack atomically records dice and spends attempt/action costs. Attacks with hit-dependent charges or once-per-turn effects require explicit hit/miss adjudication before saving; generated faces remain retained while deciding.
- Equipment can be added or edited without replacing stable item identities. Equipment states, quantity, favorites, slot, attunement requirements and reviewed effects are editable. Numeric effects, typed damage dice with critical eligibility, qualifying attacks, charges, once-per-turn limits, damage defenses and roll edges can be authored. Equipping/attuning previews derived changes before an atomic revision-checked save.
- HP actions preserve unknown values. Damage/healing and explicit current-value reconciliation are separate. Permanent-score or equipment changes preserve outstanding lost HP; name-only setup edits preserve the recorded Constitution baseline.
- Spells retain their user-owned source version, preparation, casting time, components, range, duration, concentration, ritual status, slot requirements and resource costs. Casting previews cost and concentration replacement; only confirmation consumes known available resources. Preparation uses the central engine's limits.
- Current spell slots are recorded distinctly from calculated capacity. Generic resources retain separate maximum/current/recharge/recovery values. Rest preview requires confirmation of actual eligible elapsed campaign time. Known values recover according to the engine; unknown resources remain unknown.
- Conditions, exhaustion level, concentration and next-turn actions are explicit. Source provenance and revision history sit behind optional disclosures.
- Attacks, spells and resources have stable-ID editing, rather than an add-only setup path.

## Practical gaps

This is a substantial native vertical slice, not certification that every normal campaign session is covered. Complex item properties unsupported by the structured editor require a validated typed import or further editor work. No commercial compendium or complete automatic class-builder catalog ships. Feat/class feature acquisition and spell effect adjudication remain user-authored and campaign-specific; narrative text never becomes an automatic bonus. The established armor weapon mechanics remain under More → Armor weapon, preserving their accepted engine; the main Combat destination opens character actions. A completely unified generic/armor combat screen remains further integration work.

There is no general later-adjudication queue. The UI therefore withholds unresolved saving for attacks that have outstanding hit-dependent resource decisions; free unresolved attacks already spend their attempt/action costs. There is no target roster; target defenses remain DM-controlled. Hit Dice are configurable typed resources. Explicit eligible-rest confirmation enables an initiated die roll, healing preview, and atomic saved faces/use/HP update; no rest or elapsed time is inferred. Reviewed companion copies use the same independent sheet engine; confirmed form switching and existing specialized companion tools are separate work. Existing Cloudflare campaign synchronization is preserved but new sheet state is not presented as synchronized. These gaps must remain in the release/continuation report.

## Verification ownership

`ReforgeDiceOutcomeUiTest` covers charged hit decisions and action spending for no-cost unresolved attacks. `ReforgeCharacterUiTest` covers clean opening without invented records, equipment preview/confirmation preserving damage, explicit-only roll persistence, and the previously identified equipped/name-only HP regression. Execution results belong in the integrated verification report; test existence is not a passing result.
