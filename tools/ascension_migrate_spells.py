"""Preserve native spell identity and route boundaries when reviewing a grant."""
from ascension_migrate_legacy import verify


def verify_grant_origin(target, source_key, native, targets):
    if target["kind"] != "spell_grant":
        return
    actor = target["actorId"]
    data = target["data"]
    spell = targets.get(data.get("spellId"), {})
    verify(actor and spell.get("kind") == "spell" and spell.get("actorId") == actor
           and len(source_key[2]) == 2 and source_key[2][0] == actor, "Spell grant has no matching native owner")
    definition = spell["data"]
    levels = data.get("levels")
    verify(isinstance(levels,list) and levels and all(type(level) is int and definition["baseLevel"] <= level <= 9 for level in levels)
           and len(levels) == len(set(levels)), "Reviewed spell levels exceed their definition")
    if source_key[1] == "playable.characters.spells":
        verify(native["id"] == data["spellId"] == source_key[2][1] and native["level"] == definition["baseLevel"]
               and data["preparation"] == native["preparation"].lower(), "Reviewed grant differs from original spell")
        if native["level"] == 0:
            verify(data["route"]=="at_will" and levels==[0] and native.get("slotRequired") is False,
                   "Native cantrip gained a leveled route")
        else:
            available = {r["data"].get("slotLevel") for r in targets.values() if r["kind"]=="resource_pool"
                         and r["actorId"]==actor and r["data"]["type"]=="spell_slot"}
            verify(native.get("slotRequired") is True and data["route"]=="slot" and set(levels)<=available,
                   "Reviewed slot grant exceeds native route or existing slot levels")
        verify(not data.get("resourceId") and not data.get("cost"), "Reviewed ordinary spell gained a separate cost")
    else:
        pool = targets.get(data.get("resourceId"), {})
        verify(source_key[1]=="playable.characters.features" and native.get("resourceId") and
               native["resourceId"]==data.get("resourceId") and data["route"]=="limited" and data.get("cost")==1 and
               levels==[definition["baseLevel"]] and pool.get("kind")=="resource_pool" and pool["actorId"]==actor and
               pool["data"]["type"]!="spell_slot" and spell["title"].casefold() in native["description"].casefold(),
               "Limited spell grant lacks the original named spell and separate actor pool")
    if data.get("reviewedAdjudication"):
        verify(definition["resolution"]=="manual", "Manual route requires an explicitly adjudicated definition")
    if data.get("reactionTriggerSpellId"):
        trigger = targets.get(data["reactionTriggerSpellId"], {})
        verify(data.get("reviewedAdjudication") and definition["timing"]=="reaction" and trigger.get("kind")=="spell" and
               trigger["actorId"]==actor, "Reaction lacks its actor-bound trigger spell")
    if data.get("requiresWorn"):
        item=targets.get(data.get("sourceItemId"), {})
        verify(item.get("kind")=="item_instance" and item["actorId"]==actor, "Focus grant lacks its original physical source")


def verify_item_feature(target, source_key, native, targets):
    if target["kind"]!="feature":
        return
    data=target["data"]
    fields=set(data)&{"infusionRule","preparedItemRule","infusionClassFeatureId"}
    if not fields:
        return
    actor=target["actorId"]
    verify(len(fields)==1 and source_key[1]=="playable.characters.features" and len(source_key[2])==2 and
           source_key[2][0]==actor and data.get("category")=="class" and data.get("description")==native.get("description"),
           "Item feature requires its exact native class rule")
    if "preparedItemRule" in fields:
        cfg=data["preparedItemRule"]
        pool=targets.get(cfg["poolId"],{})
        verify(pool.get("kind")=="resource_pool" and pool.get("actorId")==actor and
               pool["data"].get("current")==0 and pool["data"].get("maximum")==cfg["capacity"] and pool["data"]["recovery"]=="none" and
               data.get("initialReviewedStorage")=={"loadedItemId":None,"loadedSpellId":None,"remaining":0} and
               not data.get("loadedSpellItem"), "Empty storage must bind an existing empty independent pool")
        verify(cfg["ability"] in targets[actor]["data"]["abilities"] and cfg["eligibleItemIds"] and cfg["eligibleSpellIds"],
               "Missing reviewed storage ability, host or spell")
        for key in cfg["eligibleItemIds"]:
            host=targets.get(key,{})
            verify(host.get("kind")=="item_instance" and host.get("actorId")==actor,"Invalid possible storage host")
        for key in cfg["eligibleSpellIds"]:
            spell=targets.get(key,{})
            verify(spell.get("kind")=="spell" and spell.get("actorId")==actor and spell["data"].get("baseLevel") in (1,2)
                   and spell["data"].get("timing")=="action","Invalid reviewed storage spell")
    elif "infusionRule" in fields:
        cfg=data["infusionRule"]
        verify(cfg["knownFormulas"] and len(set(cfg["knownFormulas"]))==len(cfg["knownFormulas"])
               and all(label in native["description"] for label in cfg["knownFormulas"])
               and not cfg["eligibleTargets"] and not data.get("activeInfusions"),"Staging cannot activate unassigned formulas")
        state=data["infusionPlanning"]
        verify(state.get("knownFormulas")==cfg["knownFormulas"] and set(state.get("preferred",[]))<=set(cfg["knownFormulas"])
               and state.get("ordinaryLimit")==cfg["ordinaryLimit"] and state.get("armorOnlyLimit")==cfg["armorOnlyLimit"]
               and state.get("ordinaryAssignments")=="pending" and state.get("armorAssignments")==[] and state.get("automaticCreation") is False,
               "Infusion limits differ from the separately reviewed planning state")
        for item in cfg["armorItemIds"]:
            verify(targets.get(item,{}).get("kind")=="item_instance" and targets[item]["actorId"]==actor,"Invalid reviewed armor identity")
    else:
        parent=targets.get(data["infusionClassFeatureId"],{})
        verify(parent.get("kind")=="feature" and parent.get("actorId")==actor and
               parent["data"].get("infusionRule",{}).get("armorOnlyLimit")==data.get("additionalArmorOnlySlots") and
               data.get("automaticApplication") is False and all(part in native["description"] for part in data["eligibleArmorParts"]),
               "Separate armor parts require the original class rule and armor-only capacity")
