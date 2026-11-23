# Native source transactions in Convergence 4

The owner bridge and Android negotiate `nativeEditingVersion: 1`. A fresh
`NATIVE_EDITING` header in the actor's published loadout identifies an Android
client that can apply these operations. Code 56 continues using its existing
commands. New operations require a fresh grant and matching handset capability.

## Source-qualified gameplay and migration

Submit `define_native_item` or `define_native_actor` in the existing authenticated
`submit_campaign_transaction`. The record kind is `reference`. Its stable ID and
expected revision identify the link between a source and its native object.
Android writes the actor, equipment, typed effects and ownership to Genesis and
Reforge and writes the reference in the same Room transaction.

| Field | Meaning |
| --- | --- |
| version | Integer 1 |
| purpose | GAMEPLAY for resolved play or MIGRATION for historical reconciliation |
| mode | CREATE or EDIT |
| evidence | namespace, externalId, HTTPS url and reviewed revision |
| anchorId, anchorRevision | Existing playable actor and revision as of the beginning of the transaction |
| actorId, actorRole, actorRevision | Target identity, PLAYER or COMPANION and expected playable revision at this step. Zero is valid only for actor creation. |
| profile, ownerId | For actors, explicitly reviewed fields of ReforgeCharacter. A creation can specify attacksPerAction without changing spent actions. A new companion requires its owner's same-campaign actor ID. |
| definition, instance | For items, ItemDefinition and ItemInstance. The instance references the definition and belongs to the target actor. |

Use one operation per source object. Operations execute in order, so the creation of
a companion can precede an item for that actor. A purchase may also include a
revision-checked wallet adjustment and a checkpoint. Resolve asset identities against
the current publication first. Unknown HP or pool availability uses a null value.
Omit existing actor fields that the source does not change. Use the existing
reference ID when editing a previously mapped item. Duplicate identities, conflicting
source mappings, changed revisions and failed native validations abort the batch.

For historical changes the phone runs the transaction in a rollback-only preview,
shows the source fields and calculated outcome, and requires local approval. At
commit it saves and reopens a private Complete campaign recovery copy and compares
its fingerprint with the current Room snapshot. A carried acquisition in resolved
play may follow the previously delegated scopes. Both ends independently derive
the required scopes. An actor creation, definition edit, or pre-equipped item
requires local review. Adding attacks or defenses can require combat write scope.

## Resolved actions

- Use `change_wearable_form` with `itemId` and `formId` (null for original form).
  A worn, operating focus and any required attunement are checked. Its forms list
  supplies the movement modes. Changing form costs the acting creature's action
  when turn tracking is enabled. Suppression, transfer or removal ends the form.
- Use `use_native_feature` with an actor role and featureId. A feature bound to a
  pool spends one available use and applies its specified action cost. Several
  features can refer to one pool. Unknown availability fails closed.
- A reviewed historical worn item may supply its currently observed formId. This preserves an existing form without spending an action.
- Use `resolve_native_recharge` with poolId and the reported die face for an expended shared pool with a configured rechargeRoll. Android checks the die, threshold, and tracked turn. A missed roll also consumes the check for that turn.
- Use `resolve_native_hp` with actor role, kind DAMAGE or HEAL and a positive
  resolved amount. Android applies damage or healing with its rules for temporary
  HP, damage and maximum HP.
- Use `complete_native_craft` against a project revision. Supply version 1, ownerId,
  materials with itemId/expectedRevision/quantity, all affected actorRevisions,
  companionIds, outputTitle, outputCategory and outputQuantity. Genesis atomically
  completes the project, consumes materials and assigns the output.

Read the immutable command receipt, then read the current profile, loadout, item
definition, equipment state, calculated statistics, affected wallets and project
records. A source-reference receipt identifies the operation. Its presence alone
does not establish that a particular bonus is active. A queued command remains
pending until Android reports its decision. Retries use the original command ID.

## Item uses and timed effects

A reviewed item may carry named activations, instance-owned resource pools and conditional effects.
An activation declares its action cost, quantities consumed, mode durations in game seconds,
modes ended and optional temporary-HP dice. The AI sends the resolved activation ID,
item ID, actor role and actual temporary-HP result (null if absent) to `activate_native_item`.
Android rejects unknown or insufficient charges, invalid rolls, unavailable actions and
inactive equipment. Local play exposes the same activations on the equipment sheet.
Effects conditional on ITEM_MODE use that instance's clock. Qualifying MagicalAttack
effects make native damage components magical while active. Documented compound modes
may spend more than one pool in a single action. A reviewed migration may bind explicitly
identified, unequipped, same-actor donor items into a composite without duplicating them.

For a worn transformative item with no verified current form, use formKnown=false
and omit formId in a historical review. Android publishes unknown movement until a
form is confirmed. To reconcile an independently verified charge balance, send a
reviewed MIGRATION edit naming the physical instance and its full pools. Omitting
pools preserves every existing balance. Routine use and recovery follow their own
resolved actions.
