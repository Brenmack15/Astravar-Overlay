"""Validate a reviewed wearable form against both native declarations and the same physical item."""
from ascension_migrate_legacy import encode, sha, verify


def verify_worn_form(row, rule, entries, targets, decisions, source_key):
    values = row["values"]
    definition_change = row["kind"] == "item_definition" and values == {"wearable": True}
    form_change = row["kind"] == "form" and values.get("requiresWorn") is True
    if not (definition_change or form_change):
        return False
    verify(isinstance(rule, dict) and rule.get("decisionId") == row["ruling"]
           and rule.get("productionApproved") is False and rule.get("ownerConfirmation"),
           "Wearable form change requires a pinned owner ruling")
    actor, item, definition = (rule.get(k) for k in ("actorId", "itemInstanceId", "itemDefinitionId"))
    physical = targets.get(item, {})
    verify(physical.get("kind") == "item_instance" and physical.get("actorId") == actor
           and physical["data"].get("definitionId") == definition and physical["data"].get("quantity") == 1,
           "Wearable form physical identity mismatch")
    bindings = rule.get("sourceBindings")
    verify(isinstance(bindings, list) and len(bindings) == 2, "Both original form bindings are required")
    by_family = {b["family"]:b for b in bindings}
    families = {"authority.items.definitionJson.forms": [item], "playable.characters.itemDefinitions.forms": [actor, definition]}
    verify(by_family.keys() == families.keys(), "Original form binding families differ")
    mapped_forms = []
    for family, prefix in families.items():
        b = by_family[family]
        key = source_key(b)
        native = entries.get(key, {})
        verify(native and b["identity"] == [*prefix, native.get("id")] and b.get("original") == native
               and b.get("rowSha256") == sha(encode(native)), "Original wearable form binding differs")
        disposition = decisions[key]
        verify(disposition["disposition"] == "map" and len(disposition["targets"]) == 1,
               "Original wearable form must have one reviewed destination")
        mapped_forms.extend(disposition["targets"])
    verify(bindings[0]["source"] == bindings[1]["source"] and bindings[0]["original"] == bindings[1]["original"]
           and mapped_forms[0] == mapped_forms[1] and source_key(row["origin"]) in [source_key(b) for b in bindings],
           "Wearable form declarations do not describe the same source and form")
    form = targets.get(mapped_forms[0], {})
    verify(form.get("kind") == "form" and form.get("actorId") == actor and form["data"].get("sourceItemId") == item,
           "Wearable form belongs to a different physical source")
    data = form["data"]
    approved = rule.get("approvedMechanics", {})
    movement = approved.get("humanoidMovement", {})
    verify(approved.get("activation") == data.get("timing") == approved.get("voluntaryReversion") == "action"
           and approved.get("duration") == "indefinite while worn and operational"
           and approved.get("preserveUnderlyingStatisticsAndHp") is True and approved.get("hybridAvailable") is False
           and approved.get("humanoidNaturalWeaponAttacksAvailable") is False
           and approved.get("humanoidBreathWeaponsAvailable") is False
           and data.get("speeds") == {"walk": movement.get("walkFt"), "fly": movement.get("flyFt")}
           and all(type(movement.get(k)) is int for k in ("walkFt", "flyFt"))
           and all(data.get(k) is True for k in ("requiresWorn", "requiresOperating", "revertOnSourceLoss", "suppressesNaturalAttacks"))
           and not data.get("abilityOverrides") and not data.get("requiresAttunement") and data.get("activationSeconds") == 0
           and data.get("blockedAttackPoolId") and not any(data.get(k) is not None for k in
               ("deploymentPoolId", "integrityPoolId", "resetPoolId", "durationSeconds", "baseAc")),
           "Wearable form differs from approved mechanics")
    allowed = {"source", "sourceItemId", "requiresWorn", "requiresOperating", "revertOnSourceLoss",
               "grounded", "speeds", "abilityOverrides", "suppressesNaturalAttacks", "blockedAttackPoolId",
               "timing", "activationSeconds", "nativeDeclaration"}
    verify(set(data) <= allowed and data.get("nativeDeclaration") == bindings[0]["original"],
           "Wearable form contains additional rules or altered native evidence")
    item_type = targets.get(definition, {})
    verify(item_type.get("kind") == "item_definition" and item_type["data"].get("wearable") is True
           and item_type["data"].get("attunement") == "none", "Standalone wearable item definition mismatch")
    expected_target = definition if definition_change else form["id"]
    verify(row["targetId"] == expected_target, "Wearable ruling applied to another target")
    return True
