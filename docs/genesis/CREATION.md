# Genesis creation and editing checkpoint

## Audited behavior at 3543e83

`ReforgeGameplay.kt` housed separate character/item/attack/spell/resource forms.
Character creation omitted class/species identity, health and rules were mixed
with long expandable sections, and spell/attack forms exposed all fields at once.
Attack, spell and resource cancellation checked only whether a name was present,
so unnamed edits to other fields could be discarded without a dirty warning.
New character/attack/spell/resource IDs were generated on each save attempt.
`PartyPageV2` created a campaign profile and companion state atomically but offered
no owner association in the creation interface. These are source observations;
source review alone is not proof of runtime behavior.

## Implemented interaction

The shared `GenesisEditorSheet` uses the existing native modal, minimum 48dp
controls, dirty-cancel protection, and fixed action footer. A single focused
section is visible; Back/Next, directly selectable section tabs, and a Review &
save shortcut support both thorough setup and short name-only creation. Each
section starts at the top when selected. Draft navigation survives recreation.
Asynchronous repository errors appear inside the active editor and prevent
another save while its transaction is running. Long descriptions use the existing
bounded file-backed draft mechanism; failed recovery cannot save empty content.
Existing records offer Save from any section; new records expose Save on Review.

| Entity | Sections | Persistence authority |
| --- | --- | --- |
| Character | Identity, ability scores, health & defense, spellcasting, review | Existing Reforge character save/create callback |
| Item | Item, equipment, effects, review | Same aggregate; existing equipment/calculation rules |
| Attack | Attack, accuracy, damage, review | Existing character attack collection and revision |
| Spell | Spell, casting, reference, review | Existing character spellbook and revision |
| Resource | Resource, recovery, review | Existing resource collection and revision |
| Companion | Identity, association, review | Campaign profile/state/owner link transaction |

Identity includes species, class, subclass and background without deriving any
abilities, slots or resources from those names. Unknown numeric values remain
unknown. No class engine, rest policy, damage formula or source acceptance rule
is changed. Item mechanics require explicit review and preserve the existing
attunement/equipment checks. Cantrips hide the irrelevant spell-slot toggle.
Existing additional attack damage components remain intact when the base damage
is edited. New entity IDs are retained across save retries and recreation.

Companion creation waits for source-profile and active-profile context to load,
then defaults to the active profile only if its canonical campaign identity
exists. Searchable owner selection and Unassigned are explicit alternatives.
The selected owner revision and a saved command UUID pass to the atomic creation
API; stale owners do not receive partially created records. A playable-only
profile with no campaign identity is not fabricated as an owner.

## Verification status

`GenesisCreationUiTest` adds executable journeys for minimal character creation,
invalid health, restoration and stable retry identity, item effect approval,
cantrip visibility, dirty cancellation, asynchronous failure/retry feedback,
2× font scaling, and companion owner linkage. Execution
results must be recorded after the actual test run. No physical-device or native
emulator acceptance is claimed by this file alone.

## Remaining reconstruction scope

Campaign/NPC/encounter/crafting creation and the deeper companion stat editor are
not reconstructed in this checkpoint. Existing companion status/resource forms
remain compact and revision-aware. Editors preserve Activity recreation drafts;
there is no new promised indefinitely retained abandoned-draft manager. The
shared editor writes through existing callbacks, so cross-store reconciliation
remains the responsibility of the Genesis domain integration work.
