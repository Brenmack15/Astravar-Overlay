"""Exercise reviewed imports against the real staged schema, reopening the isolated store after every command."""
import copy
import json
import sys
import tempfile
import unittest
from contextlib import closing
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "tools"))
import ascension_migrate as mig
from ascension_migrate_legacy import COINS, encode
from ascension_migrate_reviewed import STATS, template

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
               "baseAbilities":dict(zip(STATS,(8,12,13,14,10,9))),"currentHp":13,"maxHp":22,"temporaryHp":None,
               "proficiencyBonus":2,"baseAc":12,"gameSeconds":12,"resources":[{"id":POOL,"name":"Clips","current":1,"maximum":4,
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
               "proficiency":actor["proficiencyBonus"],"abilities":actor["baseAbilities"],"baseAc":actor["baseAc"],
               "maxHp":actor["maxHp"],"currentHp":actor["currentHp"],"temporaryHp":None,"gameSeconds":12,
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
        for k in ("level","currentHp","temporaryHp","maxHp","gameSeconds","baseAc"):
            witness(w,ACTOR,["data",k],p+[k])
        witness(w,ACTOR,["data","abilities"],p+["baseAbilities"])
        witness(w,ACTOR,["data","proficiency"],p+["proficiencyBonus"])
        witness(w,ACTOR,["data","rulesProfile"],p+["rules","core"],"lowercase")
        witness("authority.actors",ACTOR,["data","role"],["role"],"lowercase")
        witness(w,ACTOR,["data","poolIds",0],p+["resources",0,"id"])
        witness(w,POOL,["id"],p+["resources",0,"id"])
        for k in ("current","maximum","type","recovery","source"):
            witness(w,POOL,["data",k],p+["resources",0,k])
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

    def test_native_amount_cannot_substitute_equal_unrelated_event(self):
        self.wallet["silver"]=14
        self.files[0].write_bytes(encode(self.complete))
        with self.assertRaisesRegex(ValueError,"export digest"):
            self.package()


if __name__=="__main__":
    unittest.main()
