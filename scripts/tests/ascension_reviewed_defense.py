"""Review an actual Kotlin Character Backup against this isolated fixture's separately approved item registry."""
import copy
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "tools"))
import ascension_migrate as mig
from ascension_migrate_legacy import encode, sha
from ascension_migrate_reviewed import indexed, project, template

ACTOR = "recovery-actor"
OWNER, CONTINUITY = "native-test-owner", "qa-native-fixture"
ITEMS = {"ward":"ward-def", "lantern":"lantern-def"}
SOURCE = "Reviewed synthetic equipment ledger"


def item_registry():
    # These are explicitly recorded synthetic custody and classification facts independent of sheet export.
    properties = {"role":"player", "source":SOURCE, "ownerId":ACTOR, "custodianId":ACTOR, "partyAccess":True,
                  "locationId":None, "holding":"carried", "stackable":False, "wearable":True, "handheld":False,
                  "category":"equipment", "rarity":None, "attunement":"none", "activation":"passive", "requiresOperating":True, "poolType":"charge", "activationType":"use"}
    doc = {"externalId":"registry", "category":"equipment","visibility":"player","body":SOURCE,
           "contentSha256":sha(SOURCE.encode()), "propertiesJson":encode(properties).decode(),
           "relationIds":[], "sourceUrl":"https://example.test/synthetic-ward-registry"}
    payload = encode({"campaignId":CONTINUITY, "title":"Synthetic equipment register", "sourceRootUrl":"https://example.test/source",
                      "sourceRootRevision":"1", "records":[doc]}).decode()
    return encode({"format":"astravar-campaign-archive","schema":1,"payloadSha256":sha(payload.encode()),"payload":payload})


def stage(source, destination):
    raw = source.read_bytes()
    native = indexed(raw)[1]
    aux = item_registry()
    plan = template([raw,aux],OWNER,CONTINUITY)
    decisions = {(r["family"],tuple(r["identity"])):r for r in plan["decisions"]}
    def row(family, *identity):
        return decisions[family,tuple(identity)]
    registry = row("reference.records","registry")
    for decision in plan["decisions"]:
        if decision["family"] in ("playable.changes", "playable.rolls", "playable.drafts", "reference.metadata"):
            decision.update(disposition="historical",reason="Original frozen native receipt or source header")
    def source_ref(d):
        return {k:d[k] for k in ("source","family","identity")}
    def approve(d,t):
        d.update(disposition="map",reason=SOURCE,playerKnown=True)
        if t not in d["targets"]: d["targets"].append(t)
    def witness(d,t,path,field,transform="exact"):
        approve(d,t)
        plan["parity"].append({**source_ref(d),"sourcePath":path,"target":t,"targetPath":field,"transform":transform})
    def prop(t,field,record_key=None):
        witness(registry,t,["propertiesJson","$json",record_key or field],["data",field])
    def link(d,identity,rel,to,path):
        approve(d,identity)
        plan["links"].append({**source_ref(d),"from":identity,"relation":rel,"to":to,"targetPath":path})
    targets = {}
    def target(identity,kind,actor,name,data):
        targets[identity] = data
        plan["targets"].append({"id":identity,"kind":kind,"actorId":actor,"title":name,"life":"active","revision":1,"data":data})
    actor_row = row("playable.characters",ACTOR)
    char = json.loads(native["playable.characters",(ACTOR,)]["characterJson"])
    assert {r["id"]:r["definitionId"] for r in char["items"]} == ITEMS
    prefix = ["characterJson","$json"]
    pool_ids = [item["pools"][0]["id"] for item in char["items"]]
    effects = [e["id"] for d in char["itemDefinitions"] for e in d["effects"]]
    actor = {"role":"player","rulesProfile":char["rules"]["core"].lower(),"level":char["level"],
             "abilities":char["baseAbilities"],"gameSeconds":char["gameSeconds"],
             "baselineConstitutionModifier":char["baselineConstitutionModifier"],"baseAc":char["baseAc"],"currentHp":char["currentHp"],
             "temporaryHp":char["temporaryHp"],"maxHp":char["baseMaxHp"],"proficiency":char["reviewedProficiency"]["bonus"],
             "skills":char["skills"],"conditions":char["conditions"],"effectIds":effects,"poolIds":pool_ids}
    target(ACTOR,"actor",ACTOR,char["name"],actor)
    witness(actor_row,ACTOR,["profileId"],["id"])
    prop(ACTOR,"role")
    for key, native_key in {"level":"level","abilities":"baseAbilities","baseAc":"baseAc","currentHp":"currentHp",
                            "gameSeconds":"gameSeconds","baselineConstitutionModifier":"baselineConstitutionModifier",
                            "temporaryHp":"temporaryHp","maxHp":"baseMaxHp","skills":"skills","conditions":"conditions"}.items():
        witness(actor_row,ACTOR,prefix+[native_key],["data",key])
    witness(actor_row,ACTOR,prefix+["rules","core"],["data","rulesProfile"],"lowercase")
    witness(actor_row,ACTOR,prefix+["reviewedProficiency","bonus"],["data","proficiency"])
    source_props = json.loads(json.loads(json.loads(aux)["payload"])["records"][0]["propertiesJson"])
    instance = {v:k for k,v in ITEMS.items()}
    for index,d in enumerate(char["itemDefinitions"]):
        did = d["id"]
        source_row = row("playable.characters.itemDefinitions",ACTOR,did)
        data = {k:source_props[k] for k in ("category","rarity","stackable","wearable","handheld","attunement","activation","source")}
        data["description"] = d["description"]
        target(did,"item_definition",None,d["name"],data)
        witness(source_row,did,["id"],["id"])
        witness(source_row,did,["description"],["data","description"])
        for field in data:
            if field != "description": prop(did,field)
        for action in d["activations"]:
            aid = action["id"]
            item = instance[did]
            item_row = row("playable.characters.items",ACTOR,item)
            native_action = row("playable.characters.itemDefinitions.activations",ACTOR,did,aid)
            rid, amount = next(iter(action["costs"].items()))
            assert len(action["costs"]) == 1 and not action["durations"] and not action["ends"] and action["temporaryHp"] is None
            target(aid,"action_definition",ACTOR,action["name"],{"type":"use","timing":action["actionType"].lower(),"source":SOURCE,
                   "sourceItemId":item,"requiresOperating":True,"cost":{"poolId":rid,"amount":amount}})
            witness(native_action,aid,["id"],["id"])
            witness(native_action,aid,["actionType"],["data","timing"],"lowercase")
            prop(aid,"type","activationType"); prop(aid,"source"); prop(aid,"requiresOperating")
            witness(item_row,aid,["id"],["data","sourceItemId"])
            witness(item_row,aid,["pools",0,"id"],["data","cost","poolId"])
            witness(native_action,aid,["costs",rid],["data","cost","amount"])
            link(item_row,aid,"requires_item",item,["id"]);link(item_row,aid,"uses_pool",rid,["pools",0,"id"])
        for pos,effect in enumerate(d["effects"]):
            item = instance[did]
            src = row("playable.characters.itemDefinitions.effects",ACTOR,did,effect["id"])
            eid,payload = effect["id"],effect["payload"]
            data = {"enabled":effect["enabled"],"requiresAttunement":effect["requiresAttunement"],"requiresOperating":True,
                    "sourceItemId":item,"source":effect["source"]["name"],"sourceRecord":effect["source"]}
            special = "damageType" in payload
            if special:
                data.update(target="damage_defense",key=payload["damageType"].lower(),operation=payload["kind"].lower())
                for a,b,tr in (("type","target","nativeDefensePayload"),("damageType","key","lowercase"),("kind","operation","lowercase")):
                    witness(src,eid,["payload",a],["data",b],tr)
            else:
                data.update(target="ability_score",key=payload["key"],operation=payload["operation"].lower(),amount=payload["amount"])
                for a,b,tr in (("target","target","numericEffectTarget"),("key","key","exact"),("operation","operation","lowercase"),("amount","amount","exact")):
                    witness(src,eid,["payload",a],["data",b],tr)
            target(eid,"effect",ACTOR,effect["name"],data)
            witness(src,eid,["id"],["id"])
            for key in ("enabled","requiresAttunement"): witness(src,eid,[key],["data",key])
            witness(src,eid,["source"],["data","sourceRecord"])
            witness(src,eid,["source","name"],["data","source"])
            prop(eid,"requiresOperating")
            origin = row("playable.characters.items",ACTOR,item)
            witness(origin,eid,["id"],["data","sourceItemId"])
            link(origin,eid,"requires_item",item,["id"])
            witness(actor_row,ACTOR,prefix+["itemDefinitions",index,"effects",pos,"id"],["data","effectIds",effects.index(eid)])
    for pos,it in enumerate(char["items"]):
        ident, definition, pool = it["id"],it["definitionId"],it["pools"][0]
        rid=pool["id"]
        item_row=row("playable.characters.items",ACTOR,ident)
        pool_row=row("playable.characters.items.pools",ACTOR,ident,rid)
        data={k:source_props[k] for k in ("ownerId","custodianId","locationId","holding","partyAccess","source")}
        data.update(quantity=it["quantity"],definitionId=definition,attuned=it["attuned"],operating=it["operating"],
                    equipment={"HELD":"held","WORN":"worn","CARRIED":"packed"}[it["state"]],resourcePoolId=rid)
        target(ident,"item_instance",ACTOR,ident,data)
        witness(item_row,ident,["id"],["id"])
        for key in ("quantity","attuned","operating","definitionId"): witness(item_row,ident,[key],["data",key])
        witness(item_row,ident,["state"],["data","equipment"],"equipmentState")
        witness(item_row,ident,["pools",0,"id"],["data","resourcePoolId"])
        for k in ("ownerId","custodianId","locationId","source"): prop(ident,k)
        for k,rel in (("ownerId","owned_by"),("custodianId","held_by")):
            link(registry,ident,rel,ACTOR,["propertiesJson","$json",k])
        link(item_row,ident,"definition",definition,["definitionId"])
        link(item_row,ident,"charge_pool",rid,["pools",0,"id"])
        target(rid,"resource_pool",ACTOR,pool["name"],{"type":"charge","maximum":pool["maximum"],"current":pool["current"],
                "recovery":pool["recharge"].lower(),"source":SOURCE})
        witness(pool_row,rid,["id"],["id"])
        for k in ("current","maximum"): witness(pool_row,rid,[k],["data",k])
        prop(rid,"type","poolType"); prop(rid,"source")
        witness(pool_row,rid,["recharge"],["data","recovery"],"lowercase")
        witness(actor_row,ACTOR,prefix+["items",pos,"pools",0,"id"],["data","poolIds",pos])
    for field,value in (("sourceItemId","lantern"),("sourceRecord",{"id":"foreign-rule"}),("key","cold")):
        altered = copy.deepcopy(plan)
        record = next(t for t in altered["targets"] if t["id"]=="source-force-ward")
        record["data"][field] = value
        try:
            project({sha(raw):raw,sha(aux):aux},altered,(OWNER,CONTINUITY))
        except ValueError:
            pass
        else:
            raise AssertionError("Reviewed native source drift was accepted: "+field)
    directory = source.parent
    mapping = directory/"reviewed.json"; mapping.write_bytes(encode(plan))
    registry_path = directory/"approved-registry.json"; registry_path.write_bytes(aux)
    package = directory/"source.zip"
    summary = mig.convert_reviewed([source,registry_path],mapping,package,(OWNER,CONTINUITY))
    assert summary["counts"]["cloud_entities"] == 11 and summary["counts"]["cloud_receipts"] == 0
    compared = mig.mechanical_parity(source, mig.read_bundle(package))
    differences = [(c["kind"],c["field"],c["status"]) for c in compared["checks"] if c["status"] in ("absent","different")]
    assert not differences, differences
    charge_checks = {(v["id"],v["field"]):v["status"] for v in compared["checks"] if v["kind"] == "item_resource"}
    assert charge_checks["ward-charges","current"] == "equal" and charge_checks["lamp-charges","current"] == "bothUnknown"
    mig.init(destination,OWNER,CONTINUITY)
    mig.apply(destination,package,mig.inspect(destination,package)["approvalSha256"])
    mig.control(destination,"cutover",mig.control(destination,"cutover")["approvalSha256"])
    return mig.inspect(destination)["database"]


if __name__ == "__main__":
    stage(Path(sys.argv[1]),Path(sys.argv[2]))
