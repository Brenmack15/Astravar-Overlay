"""Exercise reviewed imports against the real staged schema, reopening the isolated store after every command."""
import base64
import copy
import subprocess
import json
import sys
import tempfile
import unittest
from contextlib import closing
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "tools"))
import ascension_migrate as mig
from ascension_migrate_legacy import COINS, encode
from ascension_migrate_reviewed import STATS, project, scoped_identity, template

TENANT=("fixture-owner","fixture-continuity")
ACTOR="fixture-actor"
WALLET="fixture-wallet"
ITEM="fixture-gear"
DEF="fixture-definition"
POOL="fixture-pool"


class ReviewedGraphTest(unittest.TestCase):
    def setUp(self):
        folder=tempfile.TemporaryDirectory()
        self.addCleanup(folder.cleanup)
        self.root=Path(folder.name)
        self.lab=self.root/"stage"
        mig.init(self.lab,*TENANT)
        self.dst=self.root/"recovery.zip"
        self.path=self.root/"mapping.json"
        actor={"id":ACTOR,"name":"Fixture","revision":3,"level":4,"rules":{"core":"LEGACY_2014"},
               "baseAbilities":dict(zip(STATS,(8,12,13,14,10,9))),"currentHp":13,"baseMaxHp":22,"baselineConstitutionModifier":1,"temporaryHp":None,
               "reviewedProficiency":{"bonus":2,"source":{"id":"review","name":"Verified baseline","acceptance":"ACCEPTED_HOMEBREW"}},"baseAc":12,"gameSeconds":12,"resources":[{"id":POOL,"name":"Clips","current":1,"maximum":4,
               "type":"ordinary","recovery":"none","source":"Fixture protocol"}]}
        definition={"id":DEF,"name":"Fixture type","category":"tool","description":"Fixture implement","rarity":None,
                    "stackable":True,"wearable":False,"handheld":True,"attunement":"none","source":"Fixture catalog"}
        instance={"id":ITEM,"definitionId":DEF,"quantity":3,"attuned":False,"operating":False,"equipment":"packed",
                  "locationId":None,"source":"Fixture built record"}
        self.wallet={"id":WALLET,"holderProfileId":ACTOR,"name":"Fixture purse","campaignId":TENANT[1],
                     "gold":14,"silver":None,"electrum":0,"platinum":1,"copper":3,"source":"Fixture ledger","revision":2}
        ledger={"format":"astravar-campaign-items","schema":5,"exportedAt":102,
                "items":[],"events":[{"commandId":"historical-purchase","amount":7}],"records":{"wallets":[self.wallet]}}
        self.complete={"format":"astravar-connected-campaign","schema":1,"exportedAt":103,"campaign":ledger,
            "playable":{"format":"astravar-reforge","schema":2,"exportedAt":1022,"characters":[{"profileId":ACTOR,
                        "revision":3,"characterJson":json.dumps(actor),"originJson":"{}","createdAt":100,"updatedAt":101}],
                        "rolls":[],"changes":[],"drafts":[]},
            "authority":{"actors":[{"actorId":ACTOR,"role":"PLAYER","campaignId":TENANT[1],"playable":True,"revision":3}],
                         "items":[{"itemId":ITEM,"ownerActorId":ACTOR,"definitionJson":json.dumps(definition),
                                   "instanceJson":json.dumps(instance),"revision":2,"activationCommandId":"old","updatedAt":101}],
                         "receipts":[{"commandId":"immutable-history","kind":"NATIVE","fingerprint":"recorded",
                                      "requestJson":"{}","outcomeJson":"{}","createdAt":100}]}}
        self.notes={"format":"astravar-reforge","schema":2,"exportedAt":99,"characters":[],"rolls":[
            {"id":"historic-roll","profileId":ACTOR,"formula":"1d20+1","components":[{"dice":[{"sides":20,"face":13}],"modifier":1}]}],"changes":[]}
        self.files=[self.root/"complete.json",self.root/"history.json"]
        for path,v in zip(self.files,[self.complete,self.notes]):
            path.write_bytes(encode(v))
        self.plan=template([p.read_bytes() for p in self.files],*TENANT)
        self.by_family={d["family"]:d for d in self.plan["decisions"]}
        for d in self.plan["decisions"]:
            d.update(disposition="historical",reason="Identified immutable synthetic event")
        def mapped(family,*ids):
            self.by_family[family].update(disposition="map",targets=list(ids),playerKnown=True,reason="Reviewed exact source and membership")
        mapped("playable.characters",ACTOR,POOL)
        mapped("playable.characters.resources",POOL)
        mapped("authority.actors",ACTOR)
        mapped("authority.items",ITEM,DEF)
        mapped("records.wallets",WALLET)
        def target(i,kind,aid,title,data,rev):
            self.plan["targets"].append(dict(id=i,kind=kind,actorId=aid,title=title,data=data,life="active",revision=rev))
        target(ACTOR,"actor",ACTOR,actor["name"],{"role":"player","rulesProfile":"legacy_2014","level":actor["level"],
               "proficiency":actor["reviewedProficiency"]["bonus"],"abilities":actor["baseAbilities"],"baseAc":actor["baseAc"],
               "maxHp":actor["baseMaxHp"],"baselineConstitutionModifier":1,"currentHp":actor["currentHp"],"temporaryHp":None,"gameSeconds":12,
               "speeds":{},"conditions":[],"poolIds":[POOL],"effectIds":[],"skills":{}},3)
        target(POOL,"resource_pool",ACTOR,"Clips",{k:v for k,v in actor["resources"][0].items() if k not in ("id","name")},3)
        target(DEF,"item_definition",None,definition["name"],{k:v for k,v in definition.items() if k not in ("id","name")},2)
        target(ITEM,"item_instance",ACTOR,"Synthetic holding",dict(definitionId=DEF, ownerId=ACTOR,custodianId=ACTOR,
                locationId=None,holding="carried",partyAccess=True,equipment="packed",attuned=False,operating=False,
                quantity=3,source=instance["source"]),2)
        target(WALLET,"wallet",ACTOR,self.wallet["name"],dict(holderId=ACTOR,shared=False,
               coins={"cp":3,"sp":None,"ep":0,"gp":14,"pp":1},source=self.wallet["source"]),2)
        def witness(f,i,t,p,transform="exact"):
            d=self.by_family[f]
            self.plan["parity"].append({**{k:d[k] for k in ("family","source","identity")},
                "sourcePath":p,"target":i,"targetPath":t,"transform":transform})
        p=["characterJson","$json"]
        w="playable.characters"
        witness(w,ACTOR,["id"],["profileId"])
        for k in ("level","currentHp","temporaryHp","gameSeconds","baseAc","baselineConstitutionModifier"):
            witness(w,ACTOR,["data",k],p+[k])
        witness(w,ACTOR,["data","abilities"],p+["baseAbilities"])
        witness(w,ACTOR,["data","maxHp"],p+["baseMaxHp"])
        witness(w,ACTOR,["data","proficiency"],p+["reviewedProficiency","bonus"])
        witness(w,ACTOR,["data","rulesProfile"],p+["rules","core"],"lowercase")
        witness("authority.actors",ACTOR,["data","role"],["role"],"lowercase")
        witness(w,ACTOR,["data","poolIds",0],p+["resources",0,"id"])
        witness("playable.characters.resources",POOL,["id"],["id"])
        for k in ("current","maximum","type","recovery","source"):
            witness("playable.characters.resources",POOL,["data",k],[k])
        witness("authority.items",DEF,["id"],["definitionJson","$json","id"])
        for k in self.plan["targets"][2]["data"]:
            witness("authority.items",DEF,["data",k],["definitionJson","$json",k])
        witness("authority.items",ITEM,["id"],["itemId"])
        for k in ("definitionId","locationId","quantity","attuned","operating","equipment","source"):
            witness("authority.items",ITEM,["data",k],["instanceJson","$json",k])
        for k in ("ownerId","custodianId"):
            witness("authority.items",ITEM,["data",k],["ownerActorId"])
        witness("records.wallets",WALLET,["id"],["id"])
        witness("records.wallets",WALLET,["data","source"],["source"])
        witness("records.wallets",WALLET,["data","holderId"],["holderProfileId"])
        for key,column in COINS.items():
            witness("records.wallets",WALLET,["data","coins",key],[column])
        def link(f,source,relation,to,path):
            d=self.by_family[f]
            self.plan["links"].append({**{k:d[k] for k in ("family","source","identity")},
                "from":source,"relation":relation,"to":to,"targetPath":path})
        link("records.wallets",WALLET,"held_by",ACTOR,["holderProfileId"])
        link("authority.items",ITEM,"definition",DEF,["instanceJson","$json","definitionId"])
        link("authority.items",ITEM,"owned_by",ACTOR,["ownerActorId"])
        link("authority.items",ITEM,"held_by",ACTOR,["ownerActorId"])

    def package(self):
        self.path.write_bytes(encode(self.plan))
        return mig.convert_reviewed(self.files,self.path,self.dst,TENANT)

    def test_scoped_actor_provenance_uses_identity_witnesses_and_reproduces_version_one(self):
        originals={mig.sha(path.read_bytes()):path.read_bytes() for path in self.files}
        legacy=copy.deepcopy(self.plan)
        legacy["version"]=1
        _,old_rows,old_links,old_media=project(originals,legacy,TENANT)
        _,rows,links,media=project(originals,self.plan,TENANT)
        self.assertEqual((old_links,old_media),(links,media))
        for prior,current in zip(old_rows,rows):
            before=json.loads(prior["data_json"])
            after=json.loads(current["data_json"])
            if current["id"]==ACTOR:
                self.assertNotIn("importedSources",before)
                refs=after.pop("importedSources")
                self.assertEqual([{"family":"playable.characters","sourceId":ACTOR,"revision":3,"updatedAt":101,
                                   "fields":["abilities","baseAc","currentHp","level","maxHp","proficiency","rulesProfile","temporaryHp"]}],refs)
                self.assertFalse(any(digest in json.dumps(refs) for digest in originals))
            self.assertEqual(before,after)
        actor=next(t for t in self.plan["targets"] if t["id"]==ACTOR)
        actor["data"]["importedSources"]=[{"sourceId":"another-actor"}]
        with self.assertRaisesRegex(ValueError,"derived from reviewed identity"):
            project(originals,self.plan,TENANT)

    def test_reviewed_companion_bond_and_concentrated_cast_survive_isolated_recovery(self):
        companion="fixture-helper"; spell="support-spell"; cast="recorded-support"; grant="support-route"
        actor=next(t for t in self.plan["targets"] if t["id"]==ACTOR)
        helper={**actor["data"],"role":"companion","poolIds":["occupied-slot"],"rollAidRefs":[{"casterId":ACTOR,"castId":cast,"expiresAt":72}]}
        aid={"family":"support","sides":4,"applies":["attack","save"]}
        pattern={"category":"class","source":"Teaching record","description":"Original known pattern","references":[]}
        slot={"type":"ordinary","maximum":1,"current":0,"recovery":"none","source":"Occupied capacity record"}
        bond={"operatorId":ACTOR,"knownFeatureId":"known-pattern","dedicatedPoolId":"occupied-slot"}
        ability={"category":"companion","source":"Original bond","description":"Dedicated companion","references":[],"infusionBond":bond}
        recorded=[(companion,"actor",companion,helper),("known-pattern","feature",ACTOR,pattern),
                  ("occupied-slot","resource_pool",companion,slot),("original-bond","feature",companion,ability),
                  (spell,"spell",ACTOR,{"baseLevel":1,"source":"Reviewed source","concentration":True,"timing":"action",
                                       "resolution":"automatic","ritual":False,"components":[],"rollAid":aid}),
                  (grant,"spell_grant",ACTOR,{"spellId":spell,"levels":[1],"route":"slot","preparation":"prepared","source":"Ledger grant"}),
                  (cast,"cast_instance",ACTOR,{"spellId":spell,"grantId":grant,"rollAid":aid,"resolution":"automatic",
                                              "targets":[companion],"concentration":True})]
        archive_records=[]
        def add_record(id,values):
            body="Player-known reviewed synthetic source for "+id
            archive_records.append({"externalId":id,"visibility":"player","category":"character","sourceUrl":"collection://fixture/"+id,
                                    "propertiesJson":json.dumps(values),"body":body,"contentSha256":mig.sha(body.encode())})
        for id,kind,owner,data in recorded:
            add_record(id,{"id":id,"actorId":owner,"data":data})
        add_record("known-caster-state",{"actorId":ACTOR,"concentrationCastId":cast})
        payload=json.dumps({"campaignId":TENANT[1],"title":"Fixture historical mechanics","sourceRootUrl":"collection://fixture",
                            "sourceRootRevision":"4","records":archive_records})
        source=self.root/"historical-abilities.json"
        source.write_bytes(encode({"format":"astravar-campaign-archive","schema":1,"payload":payload,"payloadSha256":mig.sha(payload.encode())}))
        self.files.append(source); reviewed=template([source.read_bytes()],*TENANT); self.plan["sources"].update(reviewed["sources"])
        sources={}
        for decision in reviewed["decisions"]:
            if decision["family"]=="reference.records":
                id=decision["identity"][0];sources[id]={k:decision[k] for k in ("source","family","identity")}
                decision.update(disposition="map",targets=[ACTOR if id=="known-caster-state" else id],playerKnown=True,reason="Review exact structural source")
            else:
                decision.update(disposition="historical",reason="Retain source collection metadata")
            self.plan["decisions"].append(decision)
        original_path=["propertiesJson","$json"]
        def witness(key,target,source_path,target_path):
            self.plan["parity"].append({**sources[key],"target":target,"sourcePath":original_path+source_path,"targetPath":target_path,"transform":"exact"})
        def edge(key,origin,relation,target,path):
            self.plan["links"].append({**sources[key],"from":origin,"relation":relation,"to":target,"targetPath":original_path+path})
        for id,kind,owner,data in recorded:
            self.plan["targets"].append(dict(id=id,kind=kind,actorId=owner,title=id,data=data,revision=1,life="active"))
            witness(id,id,["id"],["id"])
            for key in data: witness(id,id,["data",key],["data",key])
        actor["data"]["concentrationCastId"]=cast
        witness("known-caster-state",ACTOR,["concentrationCastId"],["data","concentrationCastId"])
        edge("known-caster-state",ACTOR,"concentrates_on",cast,["concentrationCastId"])
        for key,relation in (("operatorId","infusion_operator"),("knownFeatureId","known_infusion"),("dedicatedPoolId","dedicated_slot")):
            edge("original-bond","original-bond",relation,bond[key],["data","infusionBond",key])
        edge(grant,grant,"grants_spell",spell,["data","spellId"])
        edge(cast,cast,"cast_spell",spell,["data","spellId"])
        edge(cast,cast,"used_grant",grant,["data","grantId"])
        edge(cast,cast,"target",companion,["data","targets",0])
        # A bad target or extra slot must not turn a historical source into a new gameplay effect.
        originals={mig.sha(path.read_bytes()):path.read_bytes() for path in self.files}
        for wrong in ("operator","target","missing-edge","optional-toggle"):
            invalid=copy.deepcopy(self.plan)
            where={r["id"]:r for r in invalid["targets"]}
            if wrong=="operator": where["original-bond"]["data"]["infusionBond"]["operatorId"]=companion
            if wrong=="target": where[cast]["data"]["targets"]=[ACTOR]
            if wrong=="missing-edge": invalid["links"]=[e for e in invalid["links"] if e["relation"]!="dedicated_slot"]
            if wrong=="optional-toggle": where[spell]["data"]["concentration"]=False
            with self.subTest(wrong=wrong),self.assertRaises(ValueError):
                project(originals,invalid,TENANT)
        self.assertEqual(0,self.package()["counts"]["cloud_receipts"])
        preview=mig.inspect(self.lab,self.dst);mig.apply(self.lab,self.dst,preview["approvalSha256"])
        mig.control(self.lab,"cutover",mig.control(self.lab,"cutover")["approvalSha256"])
        verify_script=mig.ROOT/"scripts/tests/ascension_reviewed_readback.mjs"
        def readback(stage,epoch):
            return json.loads(subprocess.check_output(["node",str(verify_script),mig.inspect(stage)["database"],"--companion-sources",str(epoch)],cwd=mig.ROOT))
        first=readback(self.lab,2)
        self.assertEqual((0,0,"support",4),(first["revision"],first["bondRemaining"],first["activeAid"]["family"],first["activeAid"]["sides"]))
        portable=self.root/"post-cutover.zip";mig.export(self.lab,portable)
        recovered=self.root/"cold";mig.init(recovered,*TENANT)
        preview=mig.inspect(recovered,portable);mig.apply(recovered,portable,preview["approvalSha256"])
        mig.control(recovered,"cutover",mig.control(recovered,"cutover")["approvalSha256"])
        second=readback(recovered,3)
        self.assertEqual(first["activeAid"],second["activeAid"]);self.assertEqual(0,second["bondRemaining"])

    def test_multisource_equal_mechanics_original_receipts_and_exact_graph(self):
        result=self.package()
        self.assertEqual(5,result["counts"]["cloud_entities"])
        self.assertEqual(4,result["counts"]["cloud_links"])
        self.assertEqual(0,result["counts"]["cloud_receipts"])
        self.assertEqual(2,result["originalSources"])
        b=mig.read_bundle(self.dst)
        compare=mig.mechanical_parity(self.files[0],b)
        self.assertEqual((0,0,[]),(compare["counts"]["different"],compare["counts"]["absent"],compare["holds"]))
        self.assertGreaterEqual(compare["counts"]["equal"],20)
        self.assertGreaterEqual(compare["counts"]["bothUnknown"],2)
        preview=mig.inspect(self.lab,self.dst)
        mig.apply(self.lab,self.dst,preview["approvalSha256"])
        with closing(mig.db_connect(mig.inspect(self.lab)["database"])) as db:
            self.assertEqual(5,len(mig.rows_by_family(db,TENANT)["cloud_entities"]))
            self.assertEqual(2,len(mig.sources(db)))
            self.assertIn(b"historical-purchase",mig.sources(db)[mig.sha(self.files[0].read_bytes())][0])
        accepted=mig.control(self.lab,"cutover")["approvalSha256"]
        self.assertEqual("ISOLATED_CLOUD",mig.control(self.lab,"cutover",accepted)["status"])
        worker=mig.ROOT/"scripts/tests/ascension_reviewed_readback.mjs"
        after=json.loads(subprocess.check_output(["node",str(worker),mig.inspect(self.lab)["database"]],cwd=mig.ROOT))
        self.assertEqual((2,0,13,1,3),(after["epoch"],after["revision"],after["ordinaryHp"],after["pool"],after["items"]))
        copied=self.root/"verified.zip"
        mig.export(self.lab,copied)
        mig.check_database(mig.read_bundle(copied))

    def test_faults_never_publish_a_partial_stage(self):
        def decision(p): p["decisions"].pop(0)
        def stale(p): p["decisions"][0]["rowSha256"]="0"*64
        def wrong_owner(p): p["owner"]="other"
        def source_digest(p): p["sources"].pop(next(iter(p["sources"])))
        def hidden(p): next(d for d in p["decisions"] if d["disposition"]=="map")["playerKnown"]=False
        def unbound(p): p["targets"].append({**p["targets"][0],"id":"unbound"})
        def orphan(p): p["targets"][3]["data"]["definitionId"]="gone"
        def wrong_type(p): p["targets"][3]["data"]["definitionId"]=ACTOR
        def divergence(p): p["targets"][4]["data"]["coins"]["sp"]=0
        def alias(p): next(w for w in p["parity"] if w["targetPath"]==["data","maxHp"])["sourcePath"][-1]="currentHp"
        def no_witness(p): p["parity"]=[w for w in p["parity"] if w["targetPath"]!=["data","quantity"]]
        def forged_roll(p): p["targets"][0]["data"]["currentHp"]=1000
        def wrong_edge(p): p["links"][0]["to"]=DEF
        def drop_edge(p): p["links"].pop()
        def event_as_active(p): next(d for d in p["decisions"] if d["family"]=="playable.characters").update(disposition="historical",targets=[])
        def fabricate_receipt(p): p["targets"][3]["kind"]="cloud_receipts"
        pristine=copy.deepcopy(self.plan)
        for fault in (decision,stale,wrong_owner,source_digest,hidden,unbound,orphan,wrong_type,divergence,alias,no_witness,forged_roll,wrong_edge,drop_edge,event_as_active,fabricate_receipt):
            with self.subTest(fault=fault.__name__):
                self.plan=copy.deepcopy(pristine)
                fault(self.plan)
                with self.assertRaises(ValueError):
                    self.package()
                self.assertFalse(self.dst.exists())
                self.assertEqual(0,mig.inspect(self.lab)["state"]["revision"])

    def test_private_unresolved_sources_are_retained_and_hold_authority(self):
        self.by_family["playable.rolls"].update(disposition="retain",reason="Dice ownership requires separate review")
        self.package()
        preview=mig.inspect(self.lab,self.dst)
        mig.apply(self.lab,self.dst,preview["approvalSha256"])
        with self.assertRaisesRegex(ValueError,"Unresolved source"):
            mig.control(self.lab,"cutover")
        decision=mig.control(self.lab,"rollback")["approvalSha256"]
        self.assertEqual("ROLLED_BACK",mig.control(self.lab,"rollback",decision)["status"])

    def rebind_source(self,index,doc):
        old=mig.sha(self.files[index].read_bytes())
        self.files[index].write_bytes(encode(doc))
        fresh=template([self.files[index].read_bytes()],*TENANT)
        new=next(iter(fresh["sources"]))
        self.plan["sources"][new]=self.plan["sources"].pop(old)
        current={(d["family"],tuple(d["identity"])):d for d in fresh["decisions"]}
        for section in ("decisions","links","parity","media"):
            for d in self.plan[section]:
                if d["source"]==old:
                    d["source"]=new
                    if section=="decisions":
                        d["rowSha256"]=current[d["family"],tuple(d["identity"])]["rowSha256"]
        return new

    def test_same_reviewed_candidate_is_decoded_and_restored_as_canonical_private_media(self):
        script="""import {encodeMediaPng} from './backend/owner-bridge/cloud-media-codec.mjs';
        process.stdout.write(await encodeMediaPng(180,1,new Uint8Array(180*4).fill(149)));"""
        png=subprocess.check_output(["node","--input-type=module","-e",script],cwd=mig.ROOT)
        row=self.complete["playable"]["characters"][0]
        parsed=json.loads(row["characterJson"])
        parsed["portraitBase64"]=base64.b64encode(png).decode()
        row["characterJson"]=json.dumps(parsed)
        self.rebind_source(0,self.complete)
        d=self.by_family["playable.characters"]
        media={**{k:d[k] for k in ("source","family","identity")},"sourcePath":["characterJson","$json","portraitBase64"],
               "target":ACTOR,"approvedSha256":"0"*64}
        self.plan["media"]=[media]
        with self.assertRaisesRegex(ValueError,"approved digest"):
            self.package()
        media["approvedSha256"]=mig.sha(png)
        self.package()
        bundle=mig.read_bundle(self.dst)
        self.assertEqual(2,len(bundle["objects"]))
        self.assertEqual(png,bundle["objects"][mig.sha(png)])
        entity=next(r for r in bundle["rows"]["cloud_entities"] if r["kind"]=="media_asset")
        self.assertEqual(ACTOR,entity["actor_id"])
        self.assertEqual(entity["id"],next(r["to_id"] for r in bundle["rows"]["cloud_links"] if r["relation"]=="primary_image"))
        self.assertEqual(180,json.loads(entity["data_json"])["width"])
        proof=mig.inspect(self.lab,self.dst)["approvalSha256"]
        mig.apply(self.lab,self.dst,proof)
        again=self.root/"media-reopened.zip"
        mig.export(self.lab,again)
        self.assertEqual(bundle["objects"],mig.read_bundle(again)["objects"])

    def test_source_local_collision_alias_rewrites_only_proven_references(self):
        d=self.by_family["playable.characters.resources"]
        alias=scoped_identity(TENANT,d["family"],d["identity"],"resource_pool",POOL)
        self.plan["targets"][0]["data"]["poolIds"]=[alias]
        self.plan["targets"][1]["id"]=alias
        for d in self.plan["decisions"]:
            d["targets"]=[alias if i==POOL else i for i in d["targets"]]
        for w in self.plan["parity"]:
            if w["target"]==POOL:
                w["target"]=alias
                if w["targetPath"]==["id"]:
                    w["transform"]="scopedIdentity"
            if w["targetPath"]==["data","poolIds",0]:
                w["transform"]="canonicalRef"
        self.package()
        bundle=mig.read_bundle(self.dst)
        self.assertIn(alias,{r["id"] for r in bundle["rows"]["cloud_entities"]})

    def test_normalized_classification_requires_independent_original_and_cannot_author_balances(self):
        d=self.by_family["playable.characters.resources"]
        binding={k:d[k] for k in ("source","family","identity","rowSha256")}
        facts={"format":"astravar-reviewed-source-facts","version":1,"owner":TENANT[0],"continuity":TENANT[1],
               "facts":[{"id":"mechanical-classification","sourceBinding":binding,"reason":"Reviewed original pool and authored definition",
                         "values":{"type":"ordinary","current":1}}]}
        path=self.root/"facts.json";path.write_bytes(encode(facts));self.files.append(path)
        prefix=template([path.read_bytes()],*TENANT)
        f=prefix["decisions"][0]
        f.update(disposition="map",reason="Approved semantic classification for this source pool",targets=[POOL],playerKnown=True)
        self.plan["sources"].update(prefix["sources"]);self.plan["decisions"].append(f)
        w=next(w for w in self.plan["parity"] if w["target"]==POOL and w["targetPath"]==["data","type"])
        w.update({k:f[k] for k in ("source","family","identity")},sourcePath=["values","type"])
        evidence=copy.deepcopy(next(w for w in self.plan["parity"] if w["target"]==POOL and w["targetPath"]==["data","current"]))
        idx=self.plan["parity"].index(evidence)
        self.plan["parity"][idx]={**w,"targetPath":["data","current"],"sourcePath":["values","current"]}
        with self.assertRaisesRegex(ValueError,"cannot supply"):
            self.package()
        self.plan["parity"][idx]=evidence
        facts["facts"][0]["sourceBinding"]["rowSha256"]="0"*64
        self.rebind_source(2,facts)
        with self.assertRaisesRegex(ValueError,"original row"):
            self.package()
        facts["facts"][0]["sourceBinding"]["rowSha256"]=d["rowSha256"]
        self.rebind_source(2,facts)
        self.assertEqual(3,self.package()["originalSources"])

    def test_original_native_attack_and_accepted_variants_cost_survive_edit_and_portable_migration(self):
        attack_id="fixture-ray"
        raw=self.complete["playable"]["characters"][0]
        actor=json.loads(raw["characterJson"])
        sheet={"id":attack_id,"name":"Tuning ray","attackBonus":7,"damageFormula":"1d4+1 force and 1d6+2 elemental",
               "actionEconomy":"Action","resourceCost":"One documented tool charge","evidenceStatus":"Confirmed","source":"Survey apparatus sheet"}
        source_json=json.dumps(sheet,separators=(",",":"))
        recorded={"profileId":ACTOR,"attackId":attack_id,"sheetRevision":8,"sheetSource":sheet["source"],"attackJson":source_json,
                  "fingerprint":mig.sha(source_json.encode()),"reviewedSheetConflicts":[]}
        raw_components=[{"id":"ray-base","label":"Force ray","type":"FORCE","formula":{"dice":[{"count":1,"sides":4}],"flat":1},"criticalDice":True},
                        {"id":"element","label":"Elemental ray","type":"FIRE","formula":{"dice":[{"count":1,"sides":6}],"flat":2},"criticalDice":True}]
        attack={"id":attack_id,"name":sheet["name"],"fixedAttackBonus":7,"proficient":False,"actionType":"Action",
                "tags":["ranged"],"addAbilityToDamage":False,"requiredItemId":ITEM,"damage":raw_components,"recordedSource":recorded}
        actor["attacks"]=[attack];raw["characterJson"]=json.dumps(actor)
        native_item=self.complete["authority"]["items"][0]
        instance=json.loads(native_item["instanceJson"]);instance.update(quantity=1,equipment="held",operating=True)
        native_item["instanceJson"]=json.dumps(instance)
        next(t for t in self.plan["targets"] if t["id"]==ITEM)["data"].update(quantity=1,equipment="held",operating=True)
        self.rebind_source(0,self.complete)
        nd=next(d for d in template([self.files[0].read_bytes()],*TENANT)["decisions"] if d["family"]=="playable.characters.attacks")
        nd.update(disposition="map",playerKnown=True,targets=[attack_id],reason="Reviewed original native attack and both components")
        self.plan["decisions"].append(nd)

        # This independent player-known archive is the authority for the limited pool and permitted elements.
        # The native attack's prose alone cannot establish either rule.
        supplement={"attackId":attack_id,"timing":"attack","delivery":"ranged","resolution":"attack",
                    "cost":{"poolId":POOL,"amount":1},"variants":["fire","cold"],
                    "selections":[{"actorId":ACTOR,"componentId":"element","type":"cold","formId":None}]}
        body="Accepted device-era elemental configuration and exact tool charge."
        reference={"externalId":"approved-rule","visibility":"player","category":"equipment","sourceUrl":"collection://fixture/equipment",
                   "propertiesJson":json.dumps(supplement),"body":body,"contentSha256":mig.sha(body.encode())}
        payload=json.dumps({"campaignId":TENANT[1],"title":"Fixture source","sourceRootUrl":"collection://fixture",
                            "sourceRootRevision":"8","records":[reference]})
        source_path=self.root/"approved-source.json";source_path.write_bytes(encode({"format":"astravar-campaign-archive","schema":1,
            "payloadSha256":mig.sha(payload.encode()),"payload":payload}))
        self.files.append(source_path);source_plan=template([source_path.read_bytes()],*TENANT)
        self.plan["sources"].update(source_plan["sources"])
        for d in source_plan["decisions"]:
            d.update(disposition="historical",reason="Preserved original archive identity")
            if d["family"]=="reference.records":
                rd=d;d.update(disposition="map",targets=[attack_id],reason="Matching approved rule for this attack identity",playerKnown=True)
            self.plan["decisions"].append(d)
        def binding(d): return {k:d[k] for k in ("source","family","identity")}
        def witness(d,sp,tp,transform="exact"):
            self.plan["parity"].append({**binding(d),"sourcePath":sp,"target":attack_id,"targetPath":tp,"transform":transform})
        def edge(d,relation,to,sp):
            self.plan["links"].append({**binding(d),"from":attack_id,"relation":relation,"to":to,"targetPath":sp})
        from_native={"source":recorded["sheetSource"],"sourceItemId":ITEM,"fixedHit":7,"recordedSource":recorded}
        pp=["propertiesJson","$json"]
        data={**from_native,**{k:supplement[k] for k in ("timing","delivery","resolution","cost","selections")}}
        components=[]
        for i,c in enumerate(raw_components):
            value={"id":c["id"],"name":c["label"],"type":c["type"].lower(),"dice":c["formula"]["dice"][0]["count"],
                   "sides":c["formula"]["dice"][0]["sides"],"flat":c["formula"]["flat"],"critical":c["criticalDice"]}
            path=["data","components",i]
            for target,original in (("id",["id"]),("name",["label"]),("type",["type"]),("dice",["formula","dice",0,"count"]),
                                    ("sides",["formula","dice",0,"sides"]),("flat",["formula","flat"]),("critical",["criticalDice"])):
                witness(nd,["damage",i]+original,path+[target],"lowercase" if target=="type" else "exact")
            if i==1:
                value["variants"]=supplement["variants"];witness(rd,pp+["variants"],path+["variants"])
            components.append(value)
        data["components"]=components
        self.plan["targets"].append(dict(id=attack_id,kind="attack",actorId=ACTOR,title=sheet["name"],data=data,life="active",revision=8))
        witness(nd,["id"],["id"]);witness(nd,["name"],["title"]);witness(nd,["recordedSource","sheetRevision"],["revision"])
        for target,field in (("fixedHit",["fixedAttackBonus"]),("source",["recordedSource","sheetSource"]),
                             ("recordedSource",["recordedSource"]),("sourceItemId",["requiredItemId"])):
            witness(nd,field,["data",target])
        for k in ("timing","delivery","resolution","cost","selections"):
            witness(rd,pp+[k],["data",k])
        edge(nd,"requires_item",ITEM,["requiredItemId"]);edge(rd,"uses_pool",POOL,pp+["cost","poolId"])

        frozen=copy.deepcopy(self.plan)
        for failure in ("missing original","illegal variant","missing cost edge"):
            self.plan=copy.deepcopy(frozen)
            if failure=="missing original": self.plan["targets"][-1]["data"].pop("recordedSource")
            if failure=="illegal variant": self.plan["targets"][-1]["data"]["selections"][0]["type"]="acid"
            if failure=="missing cost edge": self.plan["links"].pop()
            with self.subTest(failure=failure),self.assertRaises(ValueError): self.package()
        self.plan=frozen;self.package()
        mig.apply(self.lab,self.dst,mig.inspect(self.lab,self.dst)["approvalSha256"])
        mig.control(self.lab,"cutover",mig.control(self.lab,"cutover")["approvalSha256"])
        script=str(mig.ROOT/"scripts/tests/ascension_attack_readback.mjs")
        result=json.loads(subprocess.check_output(["node",script,mig.inspect(self.lab)["database"],"edit"],cwd=mig.ROOT))
        self.assertEqual(recorded,result["originalSource"])
        bundle=self.root/"after-edit.zip";mig.export(self.lab,bundle);mig.check_database(mig.read_bundle(bundle))
        second=self.root/"fresh";mig.init(second,*TENANT)
        mig.apply(second,bundle,mig.inspect(second,bundle)["approvalSha256"])
        mig.control(second,"cutover",mig.control(second,"cutover")["approvalSha256"])
        restored=json.loads(subprocess.check_output(["node",script,mig.inspect(second)["database"],"restored"],cwd=mig.ROOT))
        self.assertEqual(result,restored)

    def test_accepted_native_maximum_hp_and_damage_effects_use_shared_types(self):
        raw=self.complete["playable"]["characters"][0]
        ch=json.loads(raw["characterJson"])
        ch["effects"]=[{"id":"native-"+target.lower(),"name":"Synthetic typed effect","source":{"id":"native-reference",
            "name":"Source fixture","acceptance":"ACCEPTED_HOMEBREW","revision":1},
            "payload":{"target":target,"operation":"ADD","amount":2,"key":""},"enabled":True} for target in ("MAX_HP","DAMAGE","ATTACK")]
        for effect in ch["effects"][1:]:
            effect.update(chargeResourceId=POOL,chargeCost=2)
        raw["characterJson"]=json.dumps(ch)
        self.rebind_source(0,self.complete)
        fresh=template([self.files[0].read_bytes()],*TENANT)
        actor=self.plan["targets"][0]
        for index,effect in enumerate(ch["effects"]):
            ident=effect["id"]
            d=next(d for d in fresh["decisions"] if d["family"]=="playable.characters.effects" and d["identity"][-1]==ident)
            d.update(disposition="map",targets=[ident],reason="Accepted native numeric effect",playerKnown=True)
            self.plan["decisions"].append(d)
            actor["data"]["effectIds"].append(ident)
            adr=self.by_family["playable.characters"]
            self.plan["parity"].append({**{k:adr[k] for k in ("source","family","identity")},
               "sourcePath":["characterJson","$json","effects",index,"id"],"target":ACTOR,"targetPath":["data","effectIds",index],"transform":"exact"})
            data={"target":("max_hp","damage","attack")[index],"operation":"add","amount":2,"key":"all","source":"Source fixture","enabled":True}
            if "chargeResourceId" in effect:
                data["charge"] = {"poolId":POOL,"amount":2,"trigger":"confirmed_hit" if data["target"]=="damage" else "attempt"}
                bound={k:d[k] for k in ("source","family","identity")}
                self.plan["links"].append({**bound,"from":ident,"relation":"uses_pool","to":POOL,"targetPath":["chargeResourceId"]})
                for native_field,field,transform in ((["chargeResourceId"],"poolId","exact"),(["chargeCost"],"amount","exact"),
                                                     (["payload","target"],"trigger","nativeChargeTrigger")):
                    self.plan["parity"].append({**bound,"sourcePath":native_field,"target":ident,"targetPath":["data","charge",field],"transform":transform})
            self.plan["targets"].append(dict(id=ident,kind="effect",actorId=ACTOR,title=effect["name"],life="active",revision=1,data=data))
            for source,target,transform in ((["id"],["id"],"exact"),(["payload","target"],["data","target"],"numericEffectTarget"),
                 (["payload","operation"],["data","operation"],"lowercase"),(["payload","amount"],["data","amount"],"exact"),
                 (["payload","key"],["data","key"],"unqualifiedEffectKey"),(["source","name"],["data","source"],"exact"),
                 (["enabled"],["data","enabled"],"exact")):
                self.plan["parity"].append({**{k:d[k] for k in ("source","family","identity")},
                    "sourcePath":source,"target":ident,"targetPath":target,"transform":transform})
        self.package()
        result=mig.read_bundle(self.dst)
        effects={r["id"]:json.loads(r["data_json"]) for r in result["rows"]["cloud_entities"] if r["kind"]=="effect"}
        self.assertEqual({"max_hp","damage","attack"},{data["target"] for data in effects.values()})
        self.assertEqual(("attempt","confirmed_hit"),(effects["native-attack"]["charge"]["trigger"],effects["native-damage"]["charge"]["trigger"]))
        pool=next(r for r in result["rows"]["cloud_entities"] if r["id"]==POOL)
        self.assertEqual(1,json.loads(pool["data_json"])["current"])
        self.dst.unlink()
        next(t for t in self.plan["targets"] if t["id"]=="native-damage")["data"]["charge"]["trigger"]="attempt"
        with self.assertRaisesRegex(ValueError,"trigger lacks matching evidence"):
            self.package()

    def test_pending_form_deployment_requires_real_form_owner_and_encounter_membership(self):
        from ascension_migrate_reviewed import check_target
        data={"status":"deploying","formId":"shape","formRevision":3,"previousFormId":None,"requiredSeconds":60,
              "startedAt":123,"startedByCommand":"past-command","sourceItemId":None,"elapsedBeforeSegment":12,
              "segment":{"gameSeconds":100,"encounterId":"meeting","round":2,"index":0}}
        deployment=dict(id="old-deployment",kind="form_deployment",actorId=ACTOR,title="Synthetic transformation",data=data)
        row=dict(id=ACTOR,kind="actor",actorId=ACTOR,data={"activeDeploymentId":deployment["id"],"encounterId":"meeting"})
        encounter={"kind":"encounter","data":{"participants":[ACTOR]}}
        entities={ACTOR:row,"shape":{"kind":"form","actorId":ACTOR},"meeting":encounter}
        check_target(deployment,entities)
        encounter["data"]["participants"]=["different-actor"]
        with self.assertRaisesRegex(ValueError,"membership or turn"):
            check_target(deployment,entities)
        data["segment"]={"gameSeconds":112,"encounterId":None}
        row["data"]["encounterId"]=None
        check_target(deployment,entities)
        entities["shape"]["actorId"]="different-actor"
        with self.assertRaisesRegex(ValueError,"another actor"):
            check_target(deployment,entities)

    def test_reference_archive_can_restore_source_backed_arc_and_session_with_unknown_dates(self):
        def doc(identity,kind,props):
            return {"externalId":identity,"category":kind,"visibility":"player","body":"Archived player-known account",
                    "contentSha256":mig.sha(b"Archived player-known account"),"propertiesJson":json.dumps(props),"relationIds":[],
                    "sourceUrl":"https://example.test/"+identity}
        core={"summary":None,"state":"active","worldStart":None,"worldEnd":None,"eventIds":[],"source":"Approved public reference"}
        arc={"classification":"story_arc","parentArcId":None,**core}
        session={"previousSessionId":None,"arcId":"fixture-arc","playedOn":None,"openingCheckpointId":None,"closingCheckpointId":None,**core}
        payload={"campaignId":TENANT[1],"title":"Fixture archive","sourceRootUrl":"https://example.test/fixture","sourceRootRevision":"1",
                 "records":[doc("fixture-arc","story_arc",arc),doc("fixture-session","campaign_session",session)]}
        raw=json.dumps(payload)
        archive={"format":"astravar-campaign-archive","schema":1,"payloadSha256":mig.sha(raw.encode()),"payload":raw}
        self.files=[self.root/"references.json"];self.files[0].write_bytes(encode(archive))
        self.plan=template([self.files[0].read_bytes()],*TENANT)
        for d in self.plan["decisions"]:
            if d["family"]=="reference.metadata":
                d.update(disposition="historical",reason="Original archive header")
                continue
            identity=d["identity"][0]
            kind,data=("story_arc",arc) if identity=="fixture-arc" else ("campaign_session",session)
            d.update(disposition="map",targets=[identity],playerKnown=True,reason="Reviewed named historical source")
            self.plan["targets"].append(dict(id=identity,kind=kind,actorId=None,title=identity,revision=1,life="active",data=data))
            src={k:d[k] for k in ("source","family","identity")}
            self.plan["parity"].append({**src,"sourcePath":["externalId"],"target":identity,"targetPath":["id"],"transform":"exact"})
            for key in data:
                self.plan["parity"].append({**src,"sourcePath":["propertiesJson","$json",key],"target":identity,"targetPath":["data",key],"transform":"exact"})
            if kind=="campaign_session":
                self.plan["links"].append({**src,"from":identity,"to":"fixture-arc","relation":"session_in_arc",
                                           "targetPath":["propertiesJson","$json","arcId"]})
        self.assertEqual(2,self.package()["counts"]["cloud_entities"])
        self.assertEqual("session_in_arc",mig.read_bundle(self.dst)["rows"]["cloud_links"][0]["relation"])

    def test_pinned_multiclass_baseline_requires_exact_external_tracks_and_the_registered_total(self):
        tracks = [{"classId":"survey","name":"Surveyor","edition":"custom","level":3,"source":"Pinned fixture rules"},
                  {"classId":"guide","name":"Guide","edition":"legacy_2014","level":1,"source":"Recorded fixture class",
                   "subclass":{"name":"Test scout","source":"Approved subclass source"}}]
        recorded = {"externalId":ACTOR,"category":"character","visibility":"player","body":"Reviewed character progression",
                    "contentSha256":mig.sha(b"Reviewed character progression"),"propertiesJson":json.dumps({"classTracks":tracks}),
                    "relationIds":[],"sourceUrl":"https://example.test/actor"}
        payload = json.dumps({"campaignId":TENANT[1],"title":"Source for pinned class levels","sourceRootUrl":"https://example.test/fixture",
                              "sourceRootRevision":"1","records":[recorded]})
        raw = encode({"format":"astravar-campaign-archive","schema":1,"payloadSha256":mig.sha(payload.encode()),"payload":payload})
        target = next(t for t in self.plan["targets"] if t["kind"] == "actor")
        target["data"]["classTracks"] = tracks
        with self.assertRaisesRegex(ValueError, "Missing required mechanical parity witness"):
            self.package()
        path = self.root / "class-sources.json"; path.write_bytes(raw); self.files.append(path)
        extra = template([raw], *TENANT)
        self.plan["sources"].update(extra["sources"])
        for row in extra["decisions"]:
            if row["family"] == "reference.metadata":
                row.update(disposition="historical",reason="Original separately-reviewed source header")
            else:
                row.update(disposition="map",reason="Verified class identities for this actor",playerKnown=True,targets=[ACTOR])
                self.plan["parity"].append({**{k:row[k] for k in ("source","family","identity")},
                    "sourcePath":["propertiesJson","$json","classTracks"],"target":ACTOR,"targetPath":["data","classTracks"],"transform":"exact"})
            self.plan["decisions"].append(row)
        self.package()
        mig.apply(self.lab, self.dst, mig.inspect(self.lab, self.dst)["approvalSha256"])
        restored = self.root / "class-restore.zip"; mig.export(self.lab, restored)
        result = mig.read_bundle(restored)
        data = json.loads(next(r["data_json"] for r in result["rows"]["cloud_entities"] if r["id"] == ACTOR))
        self.assertEqual(tracks, data["classTracks"]); self.assertFalse(result["rows"]["cloud_receipts"])
        for bad in ([tracks[0],tracks[0]], [tracks[1]]):
            target["data"]["classTracks"] = bad
            validation = subprocess.run(["node",str(mig.ROOT/"tools/ascension_migrate_contracts.mjs")],input=encode([target]),capture_output=True)
            self.assertNotEqual(0,validation.returncode)

    def test_older_source_requires_explicit_proven_supersession(self):
        earlier=copy.deepcopy(self.complete["campaign"])
        earlier["records"]["wallets"][0]["revision"]=1
        earlier["records"]["wallets"][0]["gold"]=20
        earlier["events"]=[]
        path=self.root/"earlier.json";path.write_bytes(encode(earlier));self.files.append(path)
        prefix=template([path.read_bytes()],*TENANT)
        older=prefix["decisions"][0]
        older.update(disposition="map",reason="Prior source",playerKnown=True,targets=[WALLET])
        self.plan["sources"].update(prefix["sources"]);self.plan["decisions"].append(older)
        with self.assertRaisesRegex(ValueError,"Conflicting native source versions"):
            self.package()
        current=self.by_family["records.wallets"]
        older.update(disposition="superseded",targets=[],playerKnown=False,reason="Both wallets reviewed with matching stable identity and later revision",
                     supersededBy={k:current[k] for k in ("source","family","identity","rowSha256")})
        self.assertEqual(3,self.package()["originalSources"])
        b=mig.read_bundle(self.dst)
        self.assertEqual(14,json.loads(next(r["data_json"] for r in b["rows"]["cloud_entities"] if r["id"]==WALLET))["coins"]["gp"])

    def test_native_amount_cannot_substitute_equal_unrelated_event(self):
        self.wallet["silver"]=14
        self.files[0].write_bytes(encode(self.complete))
        with self.assertRaisesRegex(ValueError,"export digest"):
            self.package()


if __name__=="__main__":
    unittest.main()
