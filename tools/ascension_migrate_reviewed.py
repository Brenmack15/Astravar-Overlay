"""Rebuild a reviewed native graph from exact local sources without executing gameplay."""
import base64
from collections import Counter
from pathlib import Path
import subprocess
from ascension_migrate_range import native_attack_distance, native_conditional_distance
from ascension_migrate_provenance import historical_transfer
from ascension_migrate_legacy import (COINS, IDENTITY, encode, inspect_notion_transfer, loads,
                                      sha, source_families, verify)
from ascension_migrate_printed_actions import printed_action_terms, validate_printed_witness
from ascension_migrate_attestations import FORMAT as ATTESTED_FORMAT, index_attestations, verify_attestations, verify_attested_targets
from ascension_migrate_funds import validate_fund_target
from ascension_migrate_features import FEATURE_NORMALIZERS, native_feature_value
from ascension_migrate_documents import FAMILY as DOCUMENT_FAMILY, FORMAT as DOCUMENT_FORMAT, index_documents
from verify_campaign_archive import inspect_archive
from ascension_migrate_weapon import archive_rows, working_rows
from ascension_migrate_authority import consumed_state, archived_by, fixture_memberships, fixture_only, reviewed_grants

FORMAT = "astravar-reviewed-migration"
STATS = ("STR", "DEX", "CON", "INT", "WIS", "CHA")
# Every target is an existing playable domain kind. Security, grants, requests, asset
# access and cloud receipts have different authority and cannot be constructed here.
REQUIRED = {
    "actor": "role rulesProfile level abilities currentHp maxHp temporaryHp baseAc proficiency skills poolIds effectIds",
    "resource_pool": "type current maximum recovery",
    "form_deployment":"status formId formRevision previousFormId requiredSeconds startedAt startedByCommand sourceItemId elapsedBeforeSegment segment",
    "form": "source", "feature": "source", "effect": "source", "progression_event": "source",
    "rest_report": "actorId", "recovery_event": "source", "care_protocol": "source", "care_event": "source",
    "roll": "", "roll_batch": "", "roll_preset": "", "attack": "source", "attack_attempt": "",
    "encounter": "", "action_definition": "source", "action_use": "", "concentration_check": "",
    "spell": "source", "spell_grant": "spellId", "cast_instance": "spellId",
    "wallet": "holderId shared coins source", "coin_transaction": "source", "party_exchange": "parties holdings source",
    "item_definition": "category description rarity stackable wearable handheld attunement source",
    "item_instance": "definitionId ownerId custodianId locationId holding partyAccess equipment attuned operating quantity source",
    "item_event": "source", "world_entity": "category knownDescription partyStorage source", "relationship": "source",
    "known_fact": "detail evidence source", "timeline_event": "source", "objective": "source",
    "checkpoint": "scene source", "research_note": "source", "invention_project": "intent status requirements source",
    "blueprint_version": "source", "project_stage": "source", "component_event": "source",
    "continuity_profile": "notes source",
    "story_arc":"classification parentArcId summary state worldStart worldEnd eventIds source",
    "campaign_session":"arcId previousSessionId playedOn openingCheckpointId closingCheckpointId summary state worldStart worldEnd eventIds source",
}
HISTORICAL = {
    DOCUMENT_FAMILY,
    "campaign.events", "records.receipts", "records.audit", "records.crafts", "records.consumptions", "records.milestones",
    "archive.receipts", "archive.backups", "archive.sources", "playable.changes", "playable.rolls", "authority.receipts",
    "archive.references", "archive.relations", "reference.records", "reference.attachments", "reference.metadata", "playable.drafts",
    "notion.currency", "notion.metadata", "weapon.manifest", "weapon.archive_rows", "weapon.recent_history", "weapon.working_archives",
}
# A stage-only retention can be used for unresolved, hidden or unsupported data of any family.
# Active authority will refuse any such outstanding decision.
ACTOR_OWNED = {"form_deployment","resource_pool", "form", "feature", "effect", "care_protocol", "care_event", "rest_report",
               "recovery_event", "progression_event", "roll", "roll_batch", "roll_preset", "attack",
               "attack_attempt", "action_definition", "action_use", "concentration_check", "spell_grant", "cast_instance"}
REF = {
    "form_deployment":{"formId":"form","previousFormId":"form","sourceItemId":"item_instance"},
    "actor": {"activeDeploymentId":"form_deployment","activeFormId": "form", "concentrationCastId": "cast_instance", "encounterId": "encounter",
              "poolIds": "resource_pool", "effectIds": "effect", "recoveryPoolIds": "resource_pool"},
    "resource_pool": {"recoveryActorId": "actor"}, "wallet": {"holderId": "actor"},
    "item_instance": {"definitionId": "item_definition", "ownerId": "actor", "custodianId": "actor", "installedOnActorId":"actor",
                      "locationId": "world_entity", "resourcePoolId": "resource_pool", "additionalResourcePoolIds": "resource_pool", "externalRecipientId": "world_entity"},
    "story_arc":{"parentArcId":"story_arc","eventIds":"timeline_event"},
    "campaign_session":{"arcId":"story_arc","previousSessionId":"campaign_session","openingCheckpointId":"checkpoint",
                        "closingCheckpointId":"checkpoint","eventIds":"timeline_event"},
    "form": {"inheritedRuleFormIds":"form", "sourceItemId": "item_instance", "integrityPoolId": "resource_pool", "deploymentPoolId": "resource_pool",
             "resetPoolId": "resource_pool", "suppressedItemIds": "item_instance"},
    "effect": {"formId": "form", "sourceItemId": "item_instance"}, "feature": {"formId": "form"},
    "spell_grant": {"spellId": "spell", "resourceId": "resource_pool", "sourceItemId": "item_instance"},
    "cast_instance": {"spellId": "spell", "grantId": "spell_grant"},
    "attack": {"sourceItemId": "item_instance", "formId": "form"}, "attack_attempt": {"attackId": "attack"},
    "project_stage": {"projectId": "invention_project"}, "blueprint_version": {"projectId": "invention_project"},
}
EDGE_TARGET = {
    "care_operator":"actor","requires_spell":"spell","requires_tool":"item_definition",
    "feature_source":"item_instance","communicates_with":"actor",
    "infusion_operator":"actor","known_infusion":"feature","dedicated_slot":"resource_pool",
    "deployment_form":"form","deploying":"form_deployment","inherits_form_rules":"form",
    "includes_event":"timeline_event","part_of_arc":"story_arc","follows_session":"campaign_session","session_in_arc":"story_arc",
    "opens_at_checkpoint":"checkpoint","closes_at_checkpoint":"checkpoint",
    "actor": "actor", "represents_actor": "actor", "owned_by": "actor", "held_by": "actor", "installed_on":"actor", "stored_in":"item_instance","located_at": "world_entity",
    "definition": "item_definition", "charge_pool": "resource_pool", "uses_pool": "resource_pool", "excludes_form": "form", "feature_mechanic": "*",
    "triggered_by_attack":"attack_attempt","triggered_by_cast":"cast_instance",
    "applies_to_spell":"spell", "for_cast":"cast_instance","for_attempt":"attack_attempt","for_use":"action_use",
    "for_concentration":"concentration_check","used_cast":"cast_instance","used_substitution":"action_definition",
    "part_of_multiattack":"action_use","declared_target":"actor","target":"actor","split_from":"item_instance",
    "from_stack":"item_instance","into_stack":"item_instance","merged_into":"item_instance","seller":"world_entity",
    "previous_owner":"actor","new_owner":"actor","previous_custodian":"actor","new_custodian":"actor",
    "modifies_damage":"attack spell",
    "permits_payload":"spell", "uses_payload_library":"action_definition", "uses_preparation":"action_definition",
    "construction_project":"invention_project",
    "affected_pool": "resource_pool", "recovers_on_actor_rest": "actor", "requires_item": "item_instance",
    "suppresses_item": "item_instance", "active_form": "form", "requires_form": "form", "feature_form": "form",
    "grants_spell": "spell", "cast_spell": "spell", "used_grant": "spell_grant", "concentrates_on": "cast_instance",
    "known_about": "*", "from_subject": "*", "to_subject": "*", "event_subject": "*", "corrects": "timeline_event",
    "assigned_actor": "actor", "linked_project": "invention_project", "milestone": "timeline_event",
    "scene_location": "world_entity", "last_event": "timeline_event item_event coin_transaction party_exchange component_event rest_report", "pending_roll": "roll", "active_encounter": "encounter",
    "objective": "objective", "party_actor": "actor", "participant": "actor", "initiative_roll": "roll", "adjudicated_roll": "roll",
    "includes_attack": "attack", "includes_roll": "roll", "used_preset": "roll_preset", "used_attack": "attack", "source_attack": "attack",
    "item_subject": "item_instance", "payment": "coin_transaction", "wallet_leg": "wallet", "external_recipient": "world_entity",
    "historical_previous_owner": "world_entity actor", "historical_new_owner": "world_entity actor", "corrects_history": "item_event",
    "research_basis": "research_note", "research_roll": "roll", "item_studied": "item_instance",
    "design_for": "invention_project", "stage_of": "invention_project", "requires_project": "invention_project",
    "requires_stage": "project_stage", "blueprint": "blueprint_version", "supersedes_version": "blueprint_version",
    "requires_material": "item_definition", "completed_output": "item_instance", "paid_stage": "project_stage",
    "part_of_trade": "party_exchange", "trade_party": "actor", "trade_wallet": "wallet", "trade_holding": "item_instance",
}
EDGE_FROM = {
    "care_operator":{"care_protocol"},"requires_spell":{"care_protocol"},"requires_tool":{"care_protocol"},
    "feature_source":{"feature"},"communicates_with":{"feature"},
    "triggered_by_attack":{"attack_attempt"},"triggered_by_cast":{"attack_attempt"},
    "modifies_damage":{"effect"},
    "permits_payload":{"action_definition"},"uses_payload_library":{"attack"},"uses_preparation":{"attack"},
    "infusion_operator":{"feature"},"known_infusion":{"feature"},"dedicated_slot":{"feature"},
    "deployment_form":{"form_deployment"},"deploying":{"actor"},"inherits_form_rules":{"form"},
    "part_of_arc":{"story_arc"},"follows_session":{"campaign_session"},"session_in_arc":{"campaign_session"},
    "opens_at_checkpoint":{"campaign_session"},"closes_at_checkpoint":{"campaign_session"},"includes_event":{"story_arc","campaign_session"},
    "definition": {"item_instance"}, "owned_by": {"item_instance"}, "held_by": {"item_instance", "wallet"}, "installed_on":{"item_instance"},
    "stored_in":{"item_instance"}, "located_at": {"item_instance"}, "known_about": {"known_fact"}, "represents_actor": {"world_entity"},
    "from_subject": {"relationship"}, "to_subject": {"relationship"}, "design_for": {"blueprint_version"},
    "stage_of": {"project_stage"}, "active_form": {"actor"}, "concentrates_on": {"actor"},
    **dict.fromkeys(("last_event", "scene_location", "pending_roll", "active_encounter", "objective", "party_actor"), {"checkpoint"}),
}


def exact(a, b):
    return encode(a) == encode(b)


def path_value(value, path, missing=False):
    verify(isinstance(path, list) and len(path) <= 40, "Invalid evidence path")
    for index, step in enumerate(path):
        if step == "$printedAction":
            verify(index == 0, "Printed action must begin at an original feature")
            value = printed_action_terms(value)
        elif step == "$json":
            verify(isinstance(value, str), "Expected native encoded JSON at evidence path")
            value = loads(value)
        elif isinstance(value, dict) and isinstance(step, str):
            if missing and step not in value:
                return None
            verify(step in value, "Missing evidence field")
            value = value[step]
        elif isinstance(value, list) and type(step) is int and 0 <= step < len(value):
            value = value[step]
        else:
            raise ValueError("Evidence path does not identify a source value")
    return value


from ascension_migrate_supplement import FORMAT as SUP_FORMAT, FAMILY as SUP_FAMILY, index_supplement, verify_supplements, supplemental_witness, historical_projections

def indexed(raw):
    if raw.startswith(b"PK\x03\x04"):
        return archive_rows(raw)
    v = loads(raw)
    fmt = v["format"]
    if fmt == "astravar-backup":
        return fmt, working_rows(v)
    if fmt == ATTESTED_FORMAT:
        return index_attestations(v)
    if fmt == SUP_FORMAT:
        return index_supplement(v)
    if fmt == DOCUMENT_FORMAT:
        return index_documents(v)
    if fmt == "astravar-reviewed-source-facts":
        verify(set(v)=={"format","version","owner","continuity","facts"} and type(v["version"]) is int and v["version"]==1 and
               isinstance(v["facts"],list) and 0<len(v["facts"])<=10000,"Invalid reviewed source-facts envelope")
        facts=[]
        for fact in v["facts"]:
            verify(isinstance(fact,dict) and set(fact)=={"id","sourceBinding","reason","values"} and
                   isinstance(fact["id"],str) and IDENTITY.fullmatch(fact["id"]) and isinstance(fact["sourceBinding"],dict) and
                   isinstance(fact["reason"],str) and fact["reason"].strip() and isinstance(fact["values"],dict) and fact["values"],
                   "Invalid reviewed source fact")
            facts.append(((fact["id"],),fact))
        verify(len({ids for ids,_ in facts})==len(facts),"Duplicate reviewed source-fact identity")
        families=[("review.facts",True,facts)]
    elif fmt == "astravar-campaign-archive":
        inspect_archive(raw)
        payload = loads(v["payload"])
        families = [("reference.records", True, [((r["externalId"],), r) for r in payload["records"]]),
                    ("reference.metadata", True, [((payload["campaignId"],), {k:x for k,x in payload.items() if k not in ("records","attachments","mapped")})]),
                    ("reference.attachments", True, [((str(i),), r) for i,r in enumerate(payload.get("attachments",[]))])]
        if payload.get("mapped"):
            families.extend(("reference."+name, declared, rows) for name,declared,rows
                            in source_families(payload["mapped"], archive_projection=True))
    elif fmt in ("astravar-notion-character-transfer-v1", "astravar-notion-character-transfer-v2"):
        inspect_notion_transfer(v, raw)
        families = [("notion.character", True, [((v["transferId"],), v["character"])]),
                    ("notion.metadata", True, [((v["transferId"],), {k:x for k,x in v.items() if k not in ("character","inventory","currency","reviewedSpellbook")})]),
                    ("notion.inventory", True, [((r["sourceId"],),r) for r in v["inventory"]]),
                    ("notion.currency", True, [((r["eventId"],),r) for r in v["currency"]])]
        if v.get("reviewedSpellbook"):
            book = v["reviewedSpellbook"]
            families.append(("notion.spells", True, [((r["sourceId"],r["spellKey"]),r) for r in book["spells"]]))
            families.append(("notion.spellbook", True, [((v["transferId"],), {k:x for k,x in book.items() if k!="spells"})]))
    else:
        families = source_families(v)
    items = {(name, tuple(identity)): row for name,_,entries in families for identity,row in entries}
    def children(parent, identity, data, field):
        values = data.get(field)
        if not values:
            return []
        if isinstance(values,dict):
            return [(parent+"."+field, (*identity,str(key)), {"sourceKey":str(key),"value":v}) for key,v in values.items()]
        verify(isinstance(values,list), "Unsupported native child collection")
        result = []
        for index,v in enumerate(values):
            if isinstance(v,dict) and isinstance(v.get("id"),str):
                child, content = v["id"], v
            elif isinstance(v,str):
                child, content = v, {"sourceKey":v}
            else:
                # Anonymous children remain source-addressable. New snapshots require a fresh review.
                child, content = str(index), v if isinstance(v,dict) else {"value":v}
            result.append((parent+"."+field, (*identity,child), content))
        return result
    nested=[]
    for (family, identity),row in items.items():
        if family=="playable.characters":
            char=loads(row["characterJson"])
            verify(char.get("id")==identity[0], "Native actor identity differs inside the source envelope")
            for field in ("resources","effects","items","itemDefinitions","features","attacks","spells","rollPresets",
                          "customSkills","spellSlots","permanentAdvancements","conditions","activeStances"):
                nested.extend(children(family,identity,char,field))
            for item in char.get("items", []):
                nested.extend(children(family+".items", (*identity,item["id"]), item, "pools"))
            for definition in char.get("itemDefinitions",[]):
                for field in ("effects","attacks","forms","wearableForms","activations","resources"):
                    nested.extend(children(family+".itemDefinitions", (*identity,definition["id"]), definition,field))
            if char.get("armor"):
                nested.append((family+".armor",identity,char["armor"]))
        if family=="authority.items":
            for name in ("definitionJson","instanceJson"):
                doc=loads(row[name])
                verify(isinstance(doc,dict), "Invalid embedded physical item or definition")
                for field in ("effects","attacks","forms","wearableForms","activations","resources"):
                    nested.extend(children(family+"."+name,identity,doc,field))
    for name,identity,row in nested:
        verify((name,identity) not in items,"Duplicate nested native identity")
        items[name,identity]=row
    return fmt,items


def template(raws, owner, continuity):
    names, decisions = {}, []
    for raw in raws:
        digest = sha(raw)
        fmt, rows = indexed(raw)
        verify(digest not in names, "Duplicate original export")
        names[digest] = fmt
        for (family, ids), row in sorted(rows.items()):
            decisions.append({"source":digest, "family":family, "identity":list(ids), "rowSha256":sha(encode(row)),
                              "disposition":"pending", "reason":"", "targets":[], "playerKnown":False})
    verify(names and decisions, "At least one identified source record is required")
    return {"format":FORMAT,"version":2,"owner":owner,"continuity":continuity,"sources":dict(sorted(names.items())),
            "decisions":decisions,"targets":[],"links":[],"parity":[],"media":[]}


def scoped_identity(tenant, family, identity, kind, original):
    """Stable alias for a collision or store-local composite key across later exports."""
    verify(isinstance(original,str) and original.strip() or type(original) is int, "A native identity value is required")
    return "legacy-"+sha(encode(["ascension-identity-v1",*tenant,family,list(identity),kind,original]))


def source_key(ref):
    verify(isinstance(ref,dict) and isinstance(ref.get("identity"),list) and
           all(isinstance(x,str) for x in ref["identity"]), "Invalid native row reference")
    return ref["source"],ref["family"],tuple(ref["identity"])


def integer(value, lower=0, upper=1_000_000_000):
    return value is None or type(value) is int and lower<=value<=upper


def check_target(row, entities):
    kind, data, aid = row["kind"], row["data"], row["actorId"]
    verify(isinstance(data, dict) and data and set(REQUIRED[kind].split()) <= data.keys(), "Missing typed target fields for "+kind)
    if "source" in REQUIRED[kind].split() and kind!="actor":
        verify(isinstance(data["source"],str) and data["source"].strip(), "Missing target provenance")
    verify(aid is None or entities.get(aid,{}).get("kind")=="actor", "Orphan actor projection")
    verify(kind not in ACTOR_OWNED or aid is not None, "Actor-owned mechanics require an actor")
    if kind=="actor":
        verify(aid==row["id"] and isinstance(data["abilities"],dict) and set(data["abilities"])==set(STATS) and
               all(integer(v,0,100) for v in data["abilities"].values()), "Invalid actor or ability shape")
        verify(all(integer(data[k]) for k in ("currentHp","maxHp","temporaryHp","baseAc","proficiency")),
               "Invalid actor statistic")
        verify(isinstance(data["poolIds"],list) and isinstance(data["effectIds"],list) and isinstance(data["skills"],dict),
               "Invalid actor mechanics collections")
    if kind=="actor":
        verify(not data.get("diceDelegations"), "Roll delegation requires fresh owner authorization")
    if kind=="form_deployment":
        verify(data["status"] in ("deploying","completed","cancelled","interrupted") and type(data["requiredSeconds"]) is int and
               data["requiredSeconds"]>=0 and isinstance(data["segment"],dict),"Invalid form deployment history")
        verify(data["status"]!="deploying" or entities[aid]["data"].get("activeDeploymentId")==row["id"],
               "Pending deployment and actor disagree")
        for field in ("formId","previousFormId"):
            if data[field] is not None:
                verify(entities.get(data[field], {}).get("actorId") == aid, "Deployment refers to another actor's form")
        segment = data["segment"]
        verify("encounterId" in segment and "gameSeconds" in segment and integer(segment["gameSeconds"], 0, 100_000_000_000)
               and type(data["elapsedBeforeSegment"]) is int and data["elapsedBeforeSegment"] >= 0, "Invalid deployment time basis")
        encounter_id = segment["encounterId"]
        if encounter_id is not None:
            encounter = entities.get(encounter_id, {})
            verify(encounter.get("kind") == "encounter" and aid in encounter["data"].get("participants", [])
                   and type(segment.get("round")) is int and segment["round"] >= 1
                   and type(segment.get("index")) is int and 0 <= segment["index"] < len(encounter["data"]["participants"]),
                   "Unproven encounter membership or turn for deployment")
            verify(data["status"] != "deploying" or entities[aid]["data"].get("encounterId") == encounter_id,
                   "Active actor and deployment disagree on current encounter")
    if kind=="attack":
        cost = data.get("cost")
        if cost is not None:
            pool = entities.get(cost.get("poolId") if isinstance(cost, dict) else None, {})
            verify(pool.get("kind") == "resource_pool" and pool.get("actorId") == aid, "Attack cost belongs to a missing or different actor pool")
        components = data.get("components")
        choices = data.get("selections", [])
        verify(isinstance(components, list) and all(isinstance(part,dict) and isinstance(part.get("id"),str) for part in components)
               and isinstance(choices,list), "Invalid attack components or variant assignments")
        parts = {part["id"]: part for part in components}
        verify(len(parts) == len(components), "Duplicate native attack component identity")
        selections = set()
        for selection in choices:
            verify(isinstance(selection,dict) and isinstance(selection.get("componentId"),str) and
                   isinstance(selection.get("actorId",aid),str) and (selection.get("formId") is None or isinstance(selection["formId"],str)),
                   "Invalid attack variant identity")
            actor = selection.get("actorId", aid)
            form = selection.get("formId")
            key = actor, form, selection.get("componentId")
            verify(entities.get(actor, {}).get("kind") == "actor" and
                   (actor == aid or data.get("transfersWithItem") is True) and
                   (form is None or entities.get(form, {}).get("kind") == "form" and entities[form]["actorId"] == actor) and
                   key not in selections and selection.get("type") in parts.get(key[-1], {}).get("variants", []),
                   "Invalid migrated attack variant or actor/form assignment")
            selections.add(key)
    if kind=="effect":
        charge = data.get("charge")
        if charge is not None:
            verify(isinstance(charge, dict) and set(charge) == {"poolId","amount","trigger"}
                   and data.get("target") in ("attack","spell_attack","damage") and type(charge["amount"]) is int
                   and charge["amount"] > 0 and (charge["trigger"] == "attempt" or
                   charge["trigger"] == "confirmed_hit" and data["target"] == "damage"), "Invalid native effect charge")
            pool = entities.get(charge["poolId"], {})
            verify(pool.get("kind") == "resource_pool" and pool.get("actorId") == aid and
                   pool["data"].get("type") in ("charge","ordinary","lifetime"), "Invalid actor-scoped effect charge pool")
        verify(row["id"] in entities[aid]["data"].get("effectIds",[]), "Actor does not reference its effect")
    if kind=="resource_pool":
        verify(all(integer(data[k]) for k in ("maximum","current")) and
               (None in (data["maximum"],data["current"]) or data["current"]<=data["maximum"]), "Resource exceeds recorded capacity")
        verify(data["type"] in ("ordinary","hit_die","spell_slot","integrity","lifetime","charge") and
               data["recovery"] in ("none","short_rest","long_rest","either_rest","dawn","deployment","edition_hit_dice","recharge_roll") and
               (data["type"]!="lifetime" or data["recovery"]=="none") and
               (data["type"]=="hit_die")==bool(data.get("dieSides")) and (data["type"]=="spell_slot")==bool(data.get("slotLevel")),
               "Invalid pool shape or recovery")
        if data["recovery"]=="recharge_roll":
            verify(type(data.get("rechargeSides")) is int and type(data.get("rechargeMinimum")) is int and
                   1<=data["rechargeMinimum"]<=data["rechargeSides"], "Invalid recharge die or threshold")
        verify(row["id"] in entities[aid]["data"].get("poolIds",[]), "Actor does not reference its pool")
    if kind=="wallet":
        verify(isinstance(data["coins"],dict) and set(data["coins"])==set(COINS) and all(integer(v) for v in data["coins"].values()) and
               type(data["shared"]) is bool and aid==data["holderId"] and data["shared"]==(aid is None), "Invalid wallet shape")
    if kind=="item_instance":
        if data.get("holding")=="installed" or data.get("installedOnActorId") is not None:
            verify(data.get("installedOnActorId") and data["installedOnActorId"]==data["custodianId"]==data["ownerId"]==aid and
                   data["holding"]=="installed" and data["quantity"]==1 and data["partyAccess"] is True and
                   data["equipment"]=="packed" and data["attuned"] is False and data["operating"] is True and
                   data["locationId"] is None, "Installed holding requires one original integral asset on its operator")
        if "covenantLocked" in data:
            verify(type(data["covenantLocked"]) is bool and (not data["covenantLocked"] or data["quantity"]==1),
                   "Invalid restricted physical holding")
        verify(type(data["quantity"]) is int and 0<=data["quantity"]<=1_000_000_000 and (data["quantity"]>0 or row["life"]=="archived") and
               all(type(data[k]) is bool for k in ("partyAccess","attuned","operating")), "Invalid physical holding quantities or state")
        verify(aid==(data["custodianId"] if data["partyAccess"] and data["custodianId"] else data["ownerId"] if data["partyAccess"] else None),
               "Item projection disagrees with custody or party access")
        extra = data.get("additionalResourcePoolIds", [])
        verify(isinstance(extra,list) and len(extra) <= 15 and (not extra or data.get("resourcePoolId")) and
               data.get("resourcePoolId") not in extra, "Invalid additional physical charge pools")
        for pool_id in ([data["resourcePoolId"]] + extra if data.get("resourcePoolId") else []):
            pool = entities.get(pool_id, {})
            verify(data["quantity"] == 1 and pool.get("kind") == "resource_pool" and pool["data"].get("type") == "charge"
                   and (data["holding"] != "carried" or pool["actorId"] == data["custodianId"]), "Invalid physical item charge pool")
        definition = entities.get(data["definitionId"],{}).get("data",{})
        verify(not definition or data["quantity"]<=1 or definition.get("stackable") and definition.get("attunement")=="none",
               "Unique item cannot hold a stack")
        verify(data["equipment"] in ("packed","held","worn") and
               (data["equipment"]=="packed" or data["partyAccess"] and data["custodianId"] is not None and data["holding"]=="carried"),
               "Inaccessible or uncarried equipment cannot be active")
    if kind in ("story_arc","campaign_session"):
        verify(aid is None,"Campaign chronology cannot be actor-owned")
    if kind=="continuity_profile":
        verify(aid is None, "Continuity metadata cannot be actor-owned")
    for field, expected_kind in REF.get(kind,{}).items():
        value = data.get(field)
        ids = value if isinstance(value,list) else [] if value is None else [value]
        verify(all(isinstance(v,str) and entities.get(v,{}).get("kind")==expected_kind for v in ids), "Orphan or wrong typed relationship "+field)
        verify(len(ids)==len(set(ids)), "Duplicate canonical relationship")
        if kind=="form" and field=="inheritedRuleFormIds":
            verify(row["id"] not in ids and all(entities[v]["actorId"]==aid for v in ids), "Form inherits another actor's rules")
        if kind=="actor" and field in ("poolIds","effectIds","activeFormId","activeDeploymentId","concentrationCastId"):
            verify(all(entities[v]["actorId"]==row["id"] for v in ids), "Actor projection references another actor's mechanic")


def required_witnesses(row):
    kind, data = row["kind"], row["data"]
    paths = {
        "actor": [("data",x) for x in ("currentHp","maxHp","temporaryHp","level","baseAc","proficiency")] +
                 [("data","abilities",x) for x in STATS],
        "resource_pool": [("data",x) for x in ("current","maximum")],
        "wallet": [("data","coins",x) for x in COINS]+[("data","holderId")],
        "item_instance": [("data",x) for x in ("quantity","definitionId","ownerId","custodianId","locationId","attuned","equipment","operating")],
    }.get(kind,[])
    # Additional supplied mechanical amounts may never be invented during normalization.
    for name in ("gameSeconds","slotLevel","dieSides","recoveryAmount","integrity","hitBonus","saveDc","criticalThreshold"):
        if name in data:
            paths.append(("data",name))
    return set(paths)|{("id",)}

# Source aliases capture known native wire spellings as well as their cloud equivalents.
MECHANICAL_NAMES = {
    "currentHp":{"currentHp","hpCurrent","hp"}, "maxHp":{"maxHp","maximumHp","hpMax","maxHitPoints","baseMaxHp"},
    "temporaryHp":{"temporaryHp","tempHp"}, "baseAc":{"baseAc","baseArmorClass","armorClass"},
    "proficiency":{"proficiency","proficiencyBonus","bonus"}, "level":{"level","currentLevel","totalLevel"},
    "current":{"current","remaining","available","balance"}, "maximum":{"maximum","max","capacity"},
    "quantity":{"quantity","count"}, "definitionId":{"definitionId","id","definitionKey"},
    "ownerId":{"ownerId","ownerActorId","ownerProfileId","holderProfileId"},
    "custodianId":{"custodianId","carriedBy","holderProfileId","ownerActorId"}, "locationId":{"locationId","storageId"},
    "holderId":{"holderId","holderProfileId","ownerActorId"}, "attuned":{"attuned","isAttuned"},
    "equipment":{"equipment","equipmentState","state"}, "operating":{"operating","activated","isOperating"},
    **{key:{key,name} for key,name in COINS.items()}, **{stat:{stat,stat.lower()} for stat in STATS},
}
NATIVE_KIND = {"resources":"resource_pool","spellSlots":"resource_pool","effects":"effect","items":"item_instance",
               "itemDefinitions":"item_definition","features":"feature","attacks":"attack","spells":"spell",
               "rollPresets":"roll_preset","forms":"form","wearableForms":"form","activations":"action_definition",
               "pools":"resource_pool","permanentAdvancements":"progression_event","customSkills":"actor","conditions":"actor","activeStances":"actor"}
SCALAR_RULES = {"lowercase":str.lower, "uppercase":str.upper}
WIRE_ENUMS = {
    "numericEffectTarget":{"ABILITY":"ability_score","ABILITY_CHECK":"ability_check","SAVE":"save","SKILL":"skill",
                           "INITIATIVE":"initiative","ARMOR_CLASS":"ac","MAX_HP":"max_hp","ATTACK":"attack","DAMAGE":"damage",
                           "SPELL_ATTACK":"spell_attack","SPELL_DC":"spell_dc","SPEED":"speed","PASSIVE":"passive"},
    "unqualifiedEffectKey":{"":"all"},
    "nativeChargeTrigger":{"ATTACK":"attempt","DAMAGE":"confirmed_hit"},
    "nativeDefensePayload":{"com.yazsras.astravar.rules.reforge.EffectPayload.Defense":"damage_defense"},
    "equipmentState":{"STORED":"packed","CARRIED":"packed","HELD":"held","WORN":"worn"},
}
def scalar_paths(value, prefix=()):
    if isinstance(value,dict):
        for key,child in value.items():
            yield from scalar_paths(child,prefix+(key,))
    elif isinstance(value,list):
        for i,child in enumerate(value):
            yield from scalar_paths(child,prefix+(i,))
    else:
        yield prefix,value


def actor_source_references(plan, entries, decisions, targets):
    """Publish only witnessed identities of mapped, player-known actor records."""
    eligible={"playable.characters","records.characterProfiles","authority.actors"}
    fields={}
    bindings={}
    for witness in plan["parity"]:
        key=source_key(witness)
        target=witness["target"]
        if key[1] not in eligible or targets[target]["kind"]!="actor" or not decisions[key]["playerKnown"]:
            continue
        path=witness["targetPath"]
        if path==["id"] and witness["transform"] in ("scopedIdentity","exact"):
            original=path_value(entries[key],witness["sourcePath"])
            if isinstance(original,str) and IDENTITY.fullmatch(original):
                bindings[(target,key)]=original
        if len(path)>1 and path[0]=="data" and path[1] in {"abilities","level","baseAc","maxHp","currentHp","temporaryHp","proficiency","rulesProfile"}:
            fields.setdefault((target,key),set()).add(path[1])
    per_actor={}
    for (target,key),original in sorted(bindings.items()):
        row=entries[key]
        revision=row.get("revision")
        stamp=row.get("updatedAt")
        ref={"family":key[1],"sourceId":original,"revision":revision if type(revision) is int and revision>0 else None,
             "updatedAt":stamp if type(stamp) is int and stamp>0 else None,
             "fields":sorted(fields.get((target,key),set()))}
        # Multiple independent exports can witness the same actor and revision.
        per_actor.setdefault(target,{})[encode(ref)]=ref
    return {actor:[rows[key] for key in sorted(rows)] for actor,rows in per_actor.items()}


def project(raw_by_hash, plan, tenant):
    base_fields={"format","version","owner","continuity","sources","decisions","targets","links","parity"}
    verify(isinstance(plan,dict) and base_fields <= set(plan) <= base_fields|{"media","reviewAuthority"} and
           plan["format"]==FORMAT and type(plan["version"]) is int and plan["version"] in (1,2) and
           (plan["owner"],plan["continuity"])==tenant and all(isinstance(v,str) and v.strip() for v in tenant),
           "Unsupported or wrongly scoped reviewed mapping")
    baseline=template(list(raw_by_hash.values()), *tenant)
    verify(exact(plan["sources"],baseline["sources"]) and set(raw_by_hash)==set(plan["sources"]), "Reviewed source export digest mismatch")
    for key in ("decisions","targets","links","parity"):
        verify(isinstance(plan[key],list) and len(plan[key])<=200_000, "Invalid or oversized mapping section")
    entries={}
    for digest, raw in raw_by_hash.items():
        verify(digest==sha(raw), "Original export digest mismatch")
        fmt, indexed_rows=indexed(raw)
        if fmt in ("astravar-reviewed-source-facts", DOCUMENT_FORMAT, ATTESTED_FORMAT, SUP_FORMAT):
            doc=loads(raw)
            verify((doc["owner"],doc["continuity"])==tenant, "Reviewed source belongs to another owner or continuity")
        entries.update({(digest,family,identity):r for (family,identity),r in indexed_rows.items()})
    archived_weapon_sources = {}
    prior_transfers = {}
    for key,row in entries.items():
        if key[1]=="review.facts":
            binding=row["sourceBinding"]
            original=source_key(binding)
            verify(set(binding)=={"source","family","identity","rowSha256"} and original in entries and original[1]!="review.facts" and
                   sha(encode(entries[original]))==binding["rowSha256"], "Reviewed source fact lacks an independently supplied original row")
            if row["values"] == {"sourceRole":"original_armor_archive"}:
                verify(original[1:] == ("weapon.working", ("campaign",)), "Historical archive designation requires the exact weapon working source")
                archived_weapon_sources[key] = original[0]
            if row["values"] == {"sourceRole":"original_notion_transfer"}:
                verify(original[1]=="notion.character" and plan["sources"][original[0]].startswith("astravar-notion-character-transfer-"),
                       "Historical transfer role requires the original character transfer")
                transfer=loads(raw_by_hash[original[0]])
                verify(any(historical_transfer(transfer,loads(raw_by_hash[digest]),tenant) for digest,fmt in plan["sources"].items()
                           if fmt=="astravar-connected-campaign"), "Original transfer lacks a later matching native import and actor")
                prior_transfers[key]=original[0]
    verify_attestations(raw_by_hash, plan["sources"], plan.get("reviewAuthority"), entries, tenant, source_key, path_value)
    corrections, exclusions = reviewed_grants(plan.get("reviewAuthority"), tenant, entries, path_value, source_key)
    retired_projections=historical_projections(plan.get("reviewAuthority"), entries, source_key)
    scoped_fixtures = fixture_memberships(entries)
    decisions={}
    obligations=Counter()
    for d in plan["decisions"]:
        fields={"source","family","identity","rowSha256","disposition","reason","targets","playerKnown"}
        extra = {"superseded":{"supersededBy"},"archiveProjection":{"archivedBy"}}
        verify(isinstance(d,dict) and set(d)==fields|extra.get(d.get("disposition"),set()),
               "Invalid source decision shape")
        key=source_key(d)
        verify(key in entries and key not in decisions and sha(encode(entries[key]))==d["rowSha256"], "Missing, duplicate or changed source row binding")
        disposition=d["disposition"]
        verify(disposition in ("map","historical","retain","foreign","superseded","archiveProjection","fixture","excluded") and isinstance(d["reason"],str) and 0<len(d["reason"].strip())<=2000 and
               type(d["playerKnown"]) is bool and isinstance(d["targets"],list) and
               all(isinstance(x,str) and IDENTITY.fullmatch(x) for x in d["targets"]) and len(set(d["targets"]))==len(d["targets"]),
               "An explicit source disposition and reason is required")
        native=entries[key]
        membership=native.get("campaignId")
        if key[1]=="authority.campaigns": membership=native["id"]
        if disposition=="excluded" or key in exclusions:
            verify(key in exclusions and disposition=="excluded" and not d["targets"] and not d["playerKnown"],
                   "An exact owner exclusion must be isolated from gameplay")
        if disposition=="fixture":
            verify(not d["targets"] and not d["playerKnown"] and fixture_only(key,native,tenant,scoped_fixtures),
                   "Fixture exclusion lacks independent source provenance or foreign actor membership")
        if disposition=="archiveProjection":
            verify(not d["targets"], "Historical projection cannot activate a second target")
            archived_by(key,native,d["archivedBy"],entries,plan["sources"],source_key)
        if disposition=="foreign":
            verify(membership is not None and membership!=tenant[1] and not d["targets"], "Foreign record has no independent foreign membership")
        if disposition=="superseded":
            replacement=d["supersededBy"]
            newer=source_key(replacement)
            verify(not d["targets"] and newer in entries and newer[0]!=key[0] and newer[1:]==key[1:] and
                   set(replacement)=={"source","family","identity","rowSha256"} and replacement["rowSha256"]==sha(encode(entries[newer])) and
                   type(native.get("revision")) is int and type(entries[newer].get("revision")) is int and
                   entries[newer]["revision"]>native["revision"] and key[1] not in HISTORICAL,
                   "Supersession needs the same physical source identity and independently newer revision")
        if disposition=="historical":
            historical_weapon = key[1].startswith("weapon.working") and key[0] in archived_weapon_sources.values()
            historical_transfer_row = key[1].startswith("notion.") and key[0] in prior_transfers.values()
            verify((key[1] in HISTORICAL or key in archived_weapon_sources or historical_weapon or key in prior_transfers or historical_transfer_row or key in retired_projections) and not d["targets"],
                   "Active data cannot be retired as an historical event")
        if disposition=="map":
            verify(d["playerKnown"] and d["targets"] and membership in (None,tenant[1]) and native.get("visibility") not in ("private","dm","hidden"),
                   "Mapped record has unresolved membership, visibility or targets")
        if disposition=="retain":
            verify(not d["targets"],"Unresolved private source cannot publish a gameplay target")
            obligations[key[0]]+=1
        decisions[key]=d
    verify(decisions.keys()==entries.keys(),"Every original source row requires an exact disposition")
    for d in decisions.values():
        if d["disposition"]=="archiveProjection":
            verify(decisions[source_key(d["archivedBy"])]["disposition"] in ("map","retain","historical","excluded","fixture","foreign"),
                   "Native counterpart lacks an independent disposition")
    for role_key, source_hash in archived_weapon_sources.items():
        verify(decisions[role_key]["disposition"] == "historical" and
               all(d["disposition"] == "historical" for key,d in decisions.items()
                   if key[0] == source_hash and key[1].startswith("weapon.")),
               "An owner-designated historical weapon store cannot also activate current mechanics")
    for role, source in prior_transfers.items():
        verify(decisions[role]["disposition"]=="historical" and all(d["disposition"]=="historical" for k,d in decisions.items() if k[0]==source),
               "A prior transfer cannot also activate duplicate current records")
    active_by_native={}
    immutable_by_native={}
    for key,d in decisions.items():
        if d["disposition"]=="superseded":
            verify(decisions[source_key(d["supersededBy"])]["disposition"]=="map","Superseding source did not map to a verified target")
        if d["disposition"]=="historical" and key[1] in ("campaign.events","records.receipts","records.crafts","authority.receipts","playable.changes","playable.rolls","weapon.archive_rows"):
            prior=immutable_by_native.get(key[1:],d["rowSha256"])
            verify(prior==d["rowSha256"], "Conflicting native immutable command or event evidence requires review")
            immutable_by_native[key[1:]]=prior
        if d["disposition"]!="map":
            continue
        native_identity=key[1:]
        signature=(d["rowSha256"], tuple(sorted(d["targets"])))
        verify(native_identity not in active_by_native or active_by_native[native_identity]==signature,
               "Conflicting native source versions require explicit reconciliation")
        active_by_native[native_identity]=signature
    entities={}
    for target in plan["targets"]:
        if plan["version"]==2 and isinstance(target,dict) and target.get("kind")=="actor":
            verify(isinstance(target.get("data"),dict) and "importedSources" not in target["data"], "Actor source references are derived from reviewed identity witnesses")
        verify(isinstance(target,dict) and set(target)=={"id","kind","actorId","title","life","revision","data"} and
               isinstance(target["id"],str) and IDENTITY.fullmatch(target["id"]) and target["id"] not in entities and
               target["kind"] in REQUIRED and target["life"] in ("active","archived") and type(target["revision"]) is int and target["revision"]>=1 and
               isinstance(target["title"],str) and 0<len(target["title"].strip())<=180, "Invalid or duplicate typed canonical target")
        verify(target["kind"]!="continuity_profile" or target["id"]==tenant[1], "Wrong continuity metadata target")
        entities[target["id"]]=target
    verify_supplements(plan, entries, entities, decisions, source_key)
    verify_attested_targets(decisions, entries, entities, tenant, scoped_identity, plan, source_key)
    declared={i for d in decisions.values() for i in d["targets"]}
    verify(entities.keys()==declared, "Unbound target or missing declared canonical target")
    for key, d in decisions.items():
        if key[1]=="review.facts" and d["disposition"]=="map":
            base=decisions[source_key(entries[key]["sourceBinding"])]
            verify(base["disposition"]=="map" and set(d["targets"])<=set(base["targets"]),
                   "Reviewed facts are not authorized by their original row disposition")
    for key,d in decisions.items():
        if d["disposition"]=="map" and (key[1].startswith("playable.characters.") or key[1].startswith("authority.items.")):
            kind=NATIVE_KIND.get(key[1].rsplit(".",1)[-1])
            verify(kind is None or any(entities[i]["kind"]==kind for i in d["targets"]),
                   "Nested native mechanic lacks its typed target")
    physical_pool_sources = {}
    for key,decision in decisions.items():
        if key[1] == "playable.characters.items.pools" and decision["disposition"] == "map":
            for target_id in decision["targets"]:
                if entities[target_id]["kind"] == "resource_pool":
                    previous = physical_pool_sources.setdefault(target_id, key[2])
                    verify(previous == key[2], "Distinct native physical-item pools require independent canonical identity")
    memberships={}
    for key, native in entries.items():
        if key[1]=="authority.actors" and decisions[key]["disposition"]!="superseded" and native.get("campaignId") is not None:
            memberships.setdefault(native["actorId"],set()).add(native["campaignId"])
    for target in entities.values():
        if target["kind"]=="actor":
            verify(memberships.get(target["id"],{tenant[1]})=={tenant[1]},
                   "Mapped actor conflicts with independently recorded continuity membership")
        check_target(target,entities)
    aliases={}
    for w in plan["parity"]:
        if isinstance(w,dict) and w.get("transform")=="scopedIdentity":
            key=source_key(w)
            ident=w.get("target")
            verify(key in entries and ident in entities and w["targetPath"]==["id"], "Invalid scoped identity witness")
            original=path_value(entries[key],w["sourcePath"])
            verify(ident==scoped_identity(tenant,key[1],key[2],entities[ident]["kind"],original), "Source-derived alias differs from reviewed identity")
            aliases[ident]=original
    denied_ids = {key[2][-1] for key in exclusions}
    verify(not (denied_ids.intersection(entities) or denied_ids.intersection(aliases.values())),
           "Owner-excluded physical identity entered the typed graph")
    for target in entities.values():
        extra = target["data"].get("additionalResourcePoolIds")
        if target["kind"]=="item_instance" and extra:
            original_id = aliases.get(target["id"],target["id"])
            for pool_id in [target["data"]["resourcePoolId"],*extra]:
                native = physical_pool_sources.get(pool_id)
                verify(native is not None and native[-2] == original_id and
                       (target["data"]["holding"]!="carried" or native[0] in (target["actorId"],aliases.get(target["actorId"]))) and
                       native[-1] == aliases.get(pool_id,pool_id),
                       "Additional pools need distinct nested identities from this physical holding")
    for key, decision in decisions.items():
        family, ids = key[1:]
        if decision["disposition"] != "map" or family not in ("playable.characters.itemDefinitions.effects",
                "authority.items.definitionJson.effects", "authority.items.instanceJson.effects"):
            continue
        native_source = entries[key].get("source")
        for target_id in decision["targets"]:
            target = entities[target_id]
            if target["kind"] != "effect":
                continue
            source_id = target["data"].get("sourceItemId")
            holding = entities.get(source_id, {})
            verify(holding.get("kind") == "item_instance", "A native equipment effect needs its original physical source")
            if family == "playable.characters.itemDefinitions.effects":
                definition_id = holding["data"]["definitionId"]
                verify(ids[0] in (target["actorId"], aliases.get(target["actorId"])) and
                       ids[1] in (definition_id, aliases.get(definition_id)), "Native effect belongs to a different actor or physical definition")
            else:
                verify(ids[0] in (source_id, aliases.get(source_id)), "Native effect belongs to a different physical source")
            if isinstance(native_source, dict) and native_source.get("id"):
                verify(exact(target["data"].get("sourceRecord"),native_source), "The native equipment rule source identity must be preserved")
    for key, decision in decisions.items():
        if decision["disposition"] != "map" or not key[1].endswith(".attacks"):
            continue
        source = entries[key].get("recordedSource")
        if source is None:
            continue
        verify(isinstance(source,dict) and isinstance(source.get("profileId"),str) and source["profileId"].strip() and
               source.get("attackId") == entries[key].get("id") and isinstance(source.get("attackJson"),str) and
               sha(source["attackJson"].encode()) == source.get("fingerprint"), "The original native attack source identity or digest is invalid")
        recorded_attack = loads(source["attackJson"].encode())
        verify(isinstance(recorded_attack,dict) and recorded_attack.get("id") == source["attackId"], "The reviewed source names a different native attack")
        for target_id in decision["targets"]:
            target = entities[target_id]
            if target["kind"] == "attack":
                verify(exact(target["data"].get("recordedSource"), source), "The original native attack review must be preserved")
                if key[1] == "playable.characters.attacks":
                    verify(key[2][0] in (target["actorId"], aliases.get(target["actorId"])), "The native actor attack is mapped to another actor")
    witnesses={}
    for w in plan["parity"]:
        verify(isinstance(w,dict) and set(w)=={"source","family","identity","sourcePath","target","targetPath","transform"} and
               isinstance(w["target"],str) and w["target"] in entities and w["transform"] in ("exact","omittedUnknown","scopedIdentity","canonicalRef","ownerCorrection","ownerConfirmedCapacity","confirmedFunds","consumedLifecycle","nativeSlotLevel","nativeSlotBalance","nativeAttackDistance","nativeConditionalRange","nativeAbilityColumn","ownerAttackComponent",*FEATURE_NORMALIZERS,*SCALAR_RULES,*WIRE_ENUMS),
               "Invalid mechanical evidence witness")
        key=source_key(w)
        verify(key in decisions and w["target"] in decisions[key]["targets"], "Mechanical evidence belongs to another row or target")
        tp=w["targetPath"]
        verify(isinstance(tp,list) and tp and (tp[0] in ("id","revision","title") or tp[0]=="data" and len(tp)>1), "Invalid target evidence path")
        pair=w["target"],tuple(tp)
        verify(pair not in witnesses, "Duplicate target parity witness")
        sp=w["sourcePath"]
        supplemental_witness(key,entries[key],w)
        if isinstance(sp,list) and "$printedAction" in sp:
            verify(sp[:1]==["$printedAction"] and sp.count("$printedAction")==1 and key[1]=="playable.characters.features" and len(key[2])==2 and
                   entities[w["target"]]["actorId"]==key[2][0] and entities[w["target"]]["kind"] in ("attack","action_definition"),
                   "Printed attack belongs to another actor or gameplay family")
            validate_printed_witness(sp,tp,entities[w["target"]],w["transform"])
        expected=path_value(entries[key],sp,w["transform"]=="omittedUnknown")
        actual=path_value(entities[w["target"]],tp)
        if w["transform"] == "nativeAbilityColumn":
            target=entities[w["target"]]
            profile,stat=key[2] if len(key[2])==2 else (None,None)
            sheet=entries.get((key[0],"playable.characters",(profile,)),{})
            verify(key[1]=="records.characterAbilities" and stat in STATS and sp==["score"] and tp==["data","abilities",stat] and
                   target["kind"]=="actor" and profile in (target["id"],aliases.get(target["id"])) and "characterJson" in sheet and
                   exact(loads(sheet["characterJson"]).get("baseAbilities",{}).get(stat),expected),"Ability column differs from the original base score")
        if w["transform"] == "consumedLifecycle":
            expected = consumed_state(corrections,entities[w["target"]],key,sp,tp)
        if w["transform"] in FEATURE_NORMALIZERS:
            expected=native_feature_value(w["transform"],key,entries[key],sp,expected,entities[w["target"]],tp)
            if w["transform"]=="nativeSingleActionForm":
                holding=entities.get(entities[w["target"]]["data"]["sourceItemId"],{})
                verify(holding.get("kind")=="item_instance" and holding.get("actorId")==key[2][0] and
                       holding["data"].get("definitionId") in (key[2][1], aliases.get(key[2][1])),
                       "Form timing must come from its own physical source")

        if w["transform"] in ("nativeSlotLevel","nativeSlotBalance"):
            is_level = w["transform"] == "nativeSlotLevel"
            verify(key[1]=="playable.characters.spellSlots" and len(key[2])==2 and
                   entities[w["target"]]["kind"]=="resource_pool" and entities[w["target"]]["data"]["type"]=="spell_slot" and
                   key[2][0] in (entities[w["target"]]["actorId"],aliases.get(entities[w["target"]]["actorId"])) and
                   key[2][1] in tuple(map(str, range(1,10))) and
                   sp == (["sourceKey"] if is_level else ["value"]) and tp == ["data","slotLevel" if is_level else "current"],
                   "Native spell-slot witness has the wrong actor, source or field")
            if is_level:
                verify(expected == key[2][1], "Invalid indexed spell-slot level")
                expected = int(expected)
        if w["transform"] == "ownerAttackComponent":
            grant=corrections.get(pair)
            target=entities[w["target"]]
            verify(grant and grant["op"]=="restoreAttackComponent" and source_key(grant)==key and grant["sourcePath"]==sp and
                   target["kind"]=="attack" and target["actorId"]==key[2][0] and key[2][1] in (target["id"],aliases.get(target["id"])) and
                   target["data"]["components"][tp[2]]["id"]==entries[key]["damage"][sp[1]]["id"],"Component belongs to another attack or actor")
            expected=grant["after"].lower() if tp[-1]=="type" else grant["after"]
        elif w["transform"] == "confirmedFunds":
            grant = corrections.get(pair)
            verify(grant is not None and grant["op"] == "confirmedFunds" and source_key(grant) == key and
                   grant["sourcePath"] == sp and entities[w["target"]]["kind"] == "wallet", "Unbound confirmed holding component")
            validate_fund_target(grant, entities[w["target"]], scoped_identity(tenant,key[1],key[2],"wallet",expected), entries)
            expected = grant["after"]
        elif w["transform"] in ("ownerCorrection","ownerConfirmedCapacity") or pair in corrections:
            grant = corrections.get(pair)
            verify(grant is not None and grant["op"] in ("correct","confirmSlotCapacity","consumeRemainder") and w["transform"]==("ownerConfirmedCapacity" if grant["op"]=="confirmSlotCapacity" else "ownerCorrection") and source_key(grant)==key and
                   grant["sourcePath"]==sp and grant["targetKind"]==entities[w["target"]]["kind"] and
                   exact(expected,grant["before"]), "Field correction lacks its exact authorized source and target")
            if grant["op"]=="confirmSlotCapacity":
                target=entities[w["target"]]
                verify(target["data"].get("type")=="spell_slot" and str(target["data"].get("slotLevel"))==key[2][1] and
                       key[2][0] in (target["actorId"],aliases.get(target["actorId"])), "Reviewed capacity belongs to a different slot")
            expected=grant["after"]
        if w["transform"]=="scopedIdentity":
            verify(tp==["id"] and exact(aliases.get(actual),expected),"Incorrect source-derived identity")
            expected=actual
        if w["transform"]=="nativeConditionalRange":
            expected=native_conditional_distance(key,sp,tp,entries[key],entities[w["target"]])
        if w["transform"]=="nativeAttackDistance":
            expected=native_attack_distance(key,sp,tp,entries[key],entities[w["target"]])
        if w["transform"]=="canonicalRef":
            verify(tp[0]=="data" and isinstance(actual,str) and actual in aliases and exact(aliases[actual],expected),
                   "Canonical relationship lacks its original source identity")
            expected=actual
        if w["transform"] in WIRE_ENUMS:
            mapping=WIRE_ENUMS[w["transform"]]
            verify(isinstance(expected,str) and expected in mapping, "Unsupported native enum conversion")
            if w["transform"] == "nativeDefensePayload":
                native = entries[key]
                payload = native.get("payload", {})
                target_data = entities[w["target"]]["data"]
                verify(key[1].endswith(".effects") and sp == ["payload","type"] and tp == ["data","target"] and
                       payload.get("damageType") in ("ACID","BLUDGEONING","COLD","FIRE","FORCE","LIGHTNING","NECROTIC","PIERCING",
                           "POISON","PSYCHIC","RADIANT","SLASHING","THUNDER") and
                       payload.get("kind") in ("RESISTANCE","IMMUNITY","VULNERABILITY") and
                       target_data.get("key") == payload["damageType"].lower() and target_data.get("operation") == payload["kind"].lower()
                       and exact(target_data.get("sourceRecord"), native.get("source"))
                       and not payload.get("nonmagicalOnly") and not native.get("conditions") and not native.get("chargeResourceId")
                       and native.get("source",{}).get("acceptance") in ("OFFICIAL","ACCEPTED_HOMEBREW") and
                       native.get("expiresOn","NONE") == "NONE" and native.get("expiresAtGameSeconds") is None,
                       "Unresolved native Damage Defense discriminator, source scope or condition")
            if w["transform"] in ("numericEffectTarget","unqualifiedEffectKey","nativeChargeTrigger"):
                native=entries[key]
                verify(key[1].endswith(".effects") and isinstance(native.get("payload"),dict) and
                       native["payload"].get("operation") in ("ADD","SET","MINIMUM","MAXIMUM") and
                       native.get("source",{}).get("acceptance") in ("OFFICIAL","ACCEPTED_HOMEBREW") and
                       not native.get("conditions") and
                       native.get("expiresOn","NONE")=="NONE" and not native.get("expiresAtGameSeconds"),
                       "Native effect needs review of acceptance, eligibility, expiration or charge evidence")
                charge = entities[w["target"]]["data"].get("charge")
                if native.get("chargeResourceId") is not None or native.get("chargeCost", 0) != 0:
                    trigger = WIRE_ENUMS["nativeChargeTrigger"].get(native["payload"].get("target"))
                    verify(isinstance(native.get("chargeResourceId"),str) and isinstance(charge,dict) and trigger is not None and charge["trigger"] == trigger and
                           charge["amount"] == native.get("chargeCost") and
                           native.get("chargeResourceId") in (charge["poolId"], aliases.get(charge["poolId"])),
                           "Native charge cost, pool or confirmed trigger lacks matching evidence")
                else:
                    verify(charge is None, "Uncharged native effect cannot invent a charge")
                target_path = {"numericEffectTarget":["data","target"], "unqualifiedEffectKey":["data","key"],
                               "nativeChargeTrigger":["data","charge","trigger"]}[w["transform"]]
                verify(tp==target_path, "Effect transform targets the wrong field")
                if w["transform"]=="unqualifiedEffectKey":
                    verify(native["payload"].get("target") in ("MAX_HP","DAMAGE","INITIATIVE","ARMOR_CLASS","SPELL_ATTACK","SPELL_DC","ATTACK"),
                           "Native effect requires an explicit typed subject")
            if w["transform"]=="equipmentState":
                verify(tp==["data","equipment"] and sp and sp[-1]=="state", "Invalid equipment state transform")
            expected=mapping[expected]
        if w["transform"] in SCALAR_RULES:
            verify(isinstance(expected,str), "Case normalization requires a recorded enumeration")
            expected=SCALAR_RULES[w["transform"]](expected)
        if w["transform"]=="omittedUnknown":
            verify(expected is None, "An observed value cannot become unknown")
        verify(exact(expected,actual),"Recorded mechanical parity mismatch for "+str(tp[-1]))
        if len(tp)>1 and tp[-1] in MECHANICAL_NAMES and w["transform"] not in ("nativeSlotBalance","ownerConfirmedCapacity","confirmedFunds","consumedLifecycle","nativeAbilityColumn"):
            parsed_form_value = (w["transform"]=="nativeTimedFormValue" and sp==["description"] and
                                 (tp==["data","baseAc"] or len(tp)==3 and tp[:2]==["data","abilityOverrides"] and tp[2] in STATS))
            component_holder = (tp == ["data","holderId"] and sp == ["actorId"] and key[1] == "authority.actors" and
                                entities[w["target"]]["kind"] == "wallet" and
                                any(g["op"] == "confirmedFunds" and g["target"] == w["target"] and source_key(g) == key
                                    for g in corrections.values()))
            verify(parsed_form_value or component_holder or (sp and sp[-1] in MECHANICAL_NAMES[tp[-1]]),
                   "Incorrect source field for recorded mechanical value")
        if key[1]=="review.facts":
            verify(tp[0]=="data" and tp[-1] not in MECHANICAL_NAMES and sp and sp[0]=="values" and
                   all(type(v) not in (int,float) for _,v in scalar_paths(actual)), "Reviewed classifications cannot supply identities or numeric mechanical parity")
        if tp==["id"]:
            verify(isinstance(expected,str) and w["transform"] in ("scopedIdentity","exact"), "Canonical identity must retain an original native identity")
        witnesses[pair]=key
    verify(corrections.keys() <= witnesses.keys(), "Approved field correction lacks a typed witness")
    bound_fields={i:set() for i in entities}
    for i,path in witnesses:
        if len(path)>1 and path[0]=="data":
            bound_fields[i].add(path[1])
    for ident,target in entities.items():
        need=required_witnesses(target)
        need.update(("data",)+path for path,value in scalar_paths(target["data"]) if type(value) in (float,int))
        if target["kind"] in ("actor","resource_pool"):
            need.update(("data",)+path for path,value in scalar_paths(target["data"]) if value is not None)
        if target["kind"]=="item_instance":
            need.add(("data","source"))
            need.update(("data",k) for k in ("installedOnActorId","covenantLocked") if k in target["data"])
        if target["kind"]=="wallet":
            need.add(("data","source"))
        for path,_ in scalar_paths(target["data"]):
            verify(not any(isinstance(k,str) and k.lower() in ("password","accesstoken","bearertoken","privatekey","clientsecret","refresh_token","access_token")
                           for k in path), "Credentials cannot be published through recovery")
        # Subtree witnesses can bind more than one structured field exactly, e.g. an approved attack or recorded roll.
        for path in need:
            verify(any((ident,path[:n]) in witnesses for n in range(1,len(path)+1)),
                   "Missing required mechanical parity witness for "+ident+"/"+"/".join(map(str,path)))
        # Arbitrary prose or numbers cannot replace the original mechanical body of less common record families.
        if target["kind"] not in ("actor","resource_pool","wallet","item_instance"):
            for key in target["data"]:
                verify(key in bound_fields[ident], "Unbound typed field "+key)
    # Cross-validate the same typed contract used by the service. This process only
    # evaluates schemas. It cannot authorize a client, execute dice or spend a resource.
    contract = Path(__file__).with_name("ascension_migrate_contracts.mjs")
    result = subprocess.run(["node",str(contract)], input=encode(list(entities.values())),
                            capture_output=True, timeout=30, check=False)
    verify(result.returncode==0, "Shared gameplay contract refused a reviewed definition: "+result.stderr.decode()[:300])
    links=[]
    seen=set()
    for link in plan["links"]:
        verify(isinstance(link,dict) and set(link)=={"from","relation","to","source","family","identity","targetPath"} and
               (link["from"],link["relation"],link["to"]) not in seen and link["from"]!=link["to"] and link["from"] in entities and
               link["to"] in entities and link["relation"] in EDGE_TARGET, "Orphan, duplicate or unsupported typed edge")
        key=source_key(link)
        if isinstance(link["targetPath"],list) and "$printedAction" in link["targetPath"]:
            verify(link["targetPath"][:1]==["$printedAction"] and link["targetPath"].count("$printedAction")==1 and
                   key[1]=="playable.characters.features" and len(key[2])==2 and
                   entities[link["from"]]["kind"] in ("attack","action_definition") and entities[link["from"]]["actorId"]==key[2][0],
                   "Printed action link belongs to another actor or gameplay family")
        verify(key in decisions and link["from"] in decisions[key]["targets"] and
               (exact(path_value(entries[key],link["targetPath"]),link["to"]) or
                link["to"] in aliases and exact(path_value(entries[key],link["targetPath"]),aliases[link["to"]])), "Relationship lacks source-bound target identity")
        source_entity=entities[link["from"]]
        bond_field={"infusion_operator":"operatorId","known_infusion":"knownFeatureId","dedicated_slot":"dedicatedPoolId"}.get(link["relation"])
        if bond_field:
            verify((source_entity["data"].get("infusionBond") or {}).get(bond_field)==link["to"], "Original companion bond edge disagrees with its recorded source")
        if source_entity["kind"]=="cast_instance" and source_entity["data"].get("rollAid") is not None:
            cast=source_entity["data"]
            permitted={"target":cast["targets"],"cast_spell":[cast["spellId"]],"used_grant":[cast["grantId"]]}
            verify(link["relation"] not in permitted or link["to"] in permitted[link["relation"]],"Concentrated roll aid edge disagrees with its cast")
        if link["relation"] in ("permits_payload","uses_payload_library","uses_preparation"):
            other=entities[link["to"]]
            verify(source_entity["actorId"]==other["actorId"], "Payload or preparation belongs to another actor")
            if link["relation"]=="permits_payload":
                verify(any(v["spellId"]==other["id"] for v in source_entity["data"].get("payloadVariants",[])), "Payload spell not permitted")
            else:
                target_action=(source_entity["data"].get("payloadRule") or {}).get("storeActionId") if link["relation"]=="uses_payload_library" else source_entity["data"].get("chargeActionId")
                verify(other["id"]==target_action and other["data"].get("sourceItemId")==source_entity["data"].get("sourceItemId"), "Payload or preparation has wrong physical source")
        expected=EDGE_TARGET[link["relation"]]
        verify((expected=="*" or entities[link["to"]]["kind"] in expected.split()) and
               entities[link["from"]]["kind"] in EDGE_FROM.get(link["relation"],REQUIRED), "Wrong typed edge endpoint")
        seen.add((link["from"],link["relation"],link["to"]))
        links.append({"owner_id":tenant[0],"continuity_id":tenant[1],"from_id":link["from"],"relation":link["relation"],"to_id":link["to"]})
    # Cloud readers and eligibility use physical links, as well as the immutable original record.
    must_link={"wallet":{"holderId":"held_by"},"item_instance":{"definitionId":"definition","ownerId":"owned_by","custodianId":"held_by","locationId":"located_at","resourcePoolId":"charge_pool","additionalResourcePoolIds":"charge_pool"},
               "spell_grant":{"spellId":"grants_spell","sourceItemId":"requires_item","resourceId":"uses_pool"},
               "actor":{"activeFormId":"active_form","concentrationCastId":"concentrates_on"}}
    must_link["item_instance"]["containerItemId"]="stored_in"
    must_link["item_instance"]["installedOnActorId"]="installed_on"
    must_link["actor"]["activeDeploymentId"]="deploying"
    must_link["care_protocol"]={"operatorId":"care_operator","spellId":"requires_spell","toolDefinitionId":"requires_tool",
                                "repairPoolId":"uses_pool","reconstructionPoolId":"uses_pool"}
    must_link["form_deployment"]={"formId":"deployment_form"}
    must_link["form"]={"inheritedRuleFormIds":"inherits_form_rules"}
    must_link["effect"]={"sourceItemId":"requires_item","formId":"requires_form"}
    must_link["attack"]={"sourceItemId":"requires_item","formId":"requires_form","excludedForms":"excludes_form"}
    must_link["story_arc"]={"parentArcId":"part_of_arc","eventIds":"includes_event"}
    must_link["campaign_session"]={"arcId":"session_in_arc","previousSessionId":"follows_session",
        "openingCheckpointId":"opens_at_checkpoint","closingCheckpointId":"closes_at_checkpoint","eventIds":"includes_event"}
    ancestry={start:end for start,relation,end in seen if relation in ("follows_session","part_of_arc")}
    closed=set()
    for ident in ancestry:
        visited=set()
        node=ident
        while node in ancestry and node not in closed:
            verify(node not in visited,"Circular chronology relationship")
            visited.add(node)
            node=ancestry[node]
        closed.update(visited)
    for ident,target in entities.items():
        if target["kind"] == "feature" and target["data"].get("infusionBond") is not None:
            bond = target["data"]["infusionBond"]
            for field,relation in (("operatorId","infusion_operator"),("knownFeatureId","known_infusion"),("dedicatedPoolId","dedicated_slot")):
                verify((ident,relation,bond[field]) in seen, "Missing original companion bond edge for "+field)
        if target["kind"] == "cast_instance" and target["data"].get("rollAid") is not None:
            cast = target["data"]
            verify((ident,"cast_spell",cast["spellId"]) in seen and (ident,"used_grant",cast["grantId"]) in seen and
                   all((ident,"target",target_id) in seen for target_id in cast["targets"]), "Missing source-bound roll-aid cast edge")
        if target["kind"] == "attack" and target["data"].get("cost"):
            verify((ident,"uses_pool",target["data"]["cost"]["poolId"]) in seen, "Missing canonical edge for attack cost")
        if target["kind"] == "effect" and target["data"].get("charge"):
            verify((ident,"uses_pool",target["data"]["charge"]["poolId"]) in seen, "Missing canonical edge for effect charge")
        for field,relation in must_link.get(target["kind"],{}).items():
            val=target["data"].get(field)
            values=val if isinstance(val,list) else [] if val is None else [val]
            verify(all((ident,relation,i) in seen for i in values), "Missing canonical edge for "+field)
    refs=actor_source_references(plan,entries,decisions,entities) if plan["version"]==2 else {}
    rows=sorted(({"owner_id":tenant[0],"continuity_id":tenant[1],"id":t["id"],"kind":t["kind"],"actor_id":t["actorId"],
                  "title":t["title"],"life":t["life"],"revision":t["revision"],
                  "data_json":encode({**t["data"],**({"importedSources":refs[t["id"]]} if t["id"] in refs else {})}).decode()}
                 for t in entities.values()),key=lambda x:x["id"])
    links.sort(key=lambda r:(r["from_id"],r["relation"],r["to_id"]))
    media_objects={}
    presentations=set()
    for m in plan.get("media",[]):
        verify(isinstance(m,dict) and set(m)=={"source","family","identity","sourcePath","target","approvedSha256"} and
               m["target"] in entities and m["target"] not in presentations,"Duplicate or invalid reviewed primary media association")
        key=source_key(m)
        verify(key in decisions and m["target"] in decisions[key]["targets"] and key[1]!="review.facts",
               "Media target lacks an original player-known scope review")
        encoded=path_value(entries[key],m["sourcePath"])
        verify(isinstance(encoded,str) and 0<len(encoded)<=6_000_000, "Missing or oversized private primary image")
        original=base64.b64decode(encoded,validate=True)
        digest=sha(original)
        verify(digest==m["approvedSha256"],"Exact primary artwork differs from its approved digest")
        script="""import fs from 'node:fs';import{inspectMediaPng}from './backend/owner-bridge/cloud-media-codec.mjs';
        try { const {meta,thumbnail}=await inspectMediaPng(new Uint8Array(fs.readFileSync(0)));
        process.stdout.write(JSON.stringify({meta,thumb:Buffer.from(thumbnail).toString('base64')}));}
        catch {process.exitCode=2;}"""
        result=subprocess.run(["node","--input-type=module","-e",script],cwd=contract.parents[1],input=original,capture_output=True,timeout=15)
        verify(result.returncode==0, "Private image failed shared service media validation")
        decoded=loads(result.stdout)
        meta=decoded["meta"]
        thumb=base64.b64decode(decoded["thumb"],validate=True)
        verify(meta["sha256"]==digest and sha(thumb)==meta["thumbnailSha256"], "Invalid private image metadata")
        # One private upload namespace per scoped, approved association and version.
        id_hash=sha(encode(["ascension-native-media-v1",*tenant,m["target"],digest]))
        media_id="media-"+id_hash
        verify(media_id not in entities, "Media and gameplay record identity conflict")
        presentations.add(m["target"])
        data={**meta,"uploadId":"u-"+id_hash}
        media_objects[digest]=original
        media_objects[meta["thumbnailSha256"]]=thumb
        rows.append({"owner_id":tenant[0],"continuity_id":tenant[1],"id":media_id,"kind":"media_asset",
                     "actor_id":entities[m["target"]]["actorId"],"title":"Primary image","life":"active","revision":1,"data_json":encode(data).decode()})
        links.append({"owner_id":tenant[0],"continuity_id":tenant[1],"from_id":m["target"],"relation":"primary_image","to_id":media_id})
    rows.sort(key=lambda r:r["id"])
    links.sort(key=lambda r:(r["from_id"],r["relation"],r["to_id"]))
    contributing={(key,ident) for (ident,_),key in witnesses.items()}
    for link in plan["links"]:
        contributing.add((source_key(link),link["from"]))
    for m in plan.get("media",[]):
        contributing.add((source_key(m),m["target"]))
    content_contributions={(key[1:],decisions[key]["rowSha256"],ident) for key,ident in contributing}
    for key,d in decisions.items():
        if d["disposition"]=="map":
            verify(any((key[1:],d["rowSha256"],ident) in content_contributions for ident in d["targets"]),
                   "Mapped source row has no independent contribution to its canonical target")
    manifest_hash=sha(encode(plan))
    reports={}
    for digest in raw_by_hash:
        disposition=Counter(d["disposition"] for k,d in decisions.items() if k[0]==digest)
        reports[digest]={"format":plan["sources"][digest],"sha256":digest,"owner":tenant[0],"continuity":tenant[1],
                         "mappingFormat":FORMAT,"mappingSha256":manifest_hash,"reviewedMapping":plan,
                         "dispositions":dict(sorted(disposition.items())),"cutoverHolds":obligations[digest],"blockers":{}}
    return reports,rows,links,media_objects
