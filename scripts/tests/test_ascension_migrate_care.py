import copy
import sys
import unittest
from pathlib import Path

sys.path.insert(0,str(Path(__file__).resolve().parents[2]/"tools"))
from ascension_migrate_care import validate_care_projection


class NativeCareProjection(unittest.TestCase):
    def setUp(self):
        self.family="playable.characters.features"
        self.origin=("digest",self.family,("construct","restore"))
        self.description="Disabled at 0 HP, 12 minutes Artisan Tools plus Patch once/Artisan Long Rest."
        def feature(text):
            return {"description":text,"source":{"acceptance":"ACCEPTED_HOMEBREW"}}
        self.entries={self.origin:feature(self.description),
            ("digest",self.family,("construct","heal")):feature("Patch restores 2d8 HP on active construct."),
            ("digest",self.family,("construct","fold")):feature("Outside combat one minute to fold/unfold.")}
        self.tool={"kind":"item_definition","targetId":"tool","actorId":None,"ruling":"",
            "values":{"id":"tool","title":"Artisan Tools (catalog)","category":"Artisan tools","source":self.description,
                "description":"Catalog only.","rarity":None,"handheld":True,"stackable":False,"wearable":False,"attunement":"none"}}
        self.targets={"construct":{"kind":"actor","id":"construct","title":"Fixture Construct"},"operator":{"id":"operator","kind":"actor","title":"Artisan Sample"},
            "spell":{"kind":"spell","actorId":"operator","title":"Patch","data":{"resolution":"automatic","components":[],"nativeCastingTime":"2 minutes"}},
            "tool":{"kind":"item_definition","title":"Artisan Tools (catalog)"},
            "repair":{"id":"repair","kind":"resource_pool","actorId":"construct","data":{"recovery":"short_rest"}},
            "restore":{"id":"restore","kind":"resource_pool","actorId":"construct","data":{"recovery":"long_rest","recoveryActorId":"operator"}}}
        self.protocol={"kind":"care_protocol","targetId":"protocol","actorId":"construct","ruling":"",
            "values":{"id":"protocol","title":"Protocol","operatorId":"operator","spellId":"spell","toolDefinitionId":"tool",
            "repairPoolId":"repair","reconstructionPoolId":"restore","repairDice":2,"repairSides":8,"repairSeconds":120,
            "reconstructionSeconds":720,"dormancySeconds":60,"source":"Sample rules"}}

    def verify(self,row):
        validate_care_projection(row,self.origin,self.entries,self.targets)

    def test_source_tied_catalog(self):
        self.verify(self.tool)
        for change in ({"title":"Unknown Tools (catalog)"},{"wearable":True},{"quantity":1},{"attunement":"required"}):
            row=copy.deepcopy(self.tool)
            row["values"].update(change)
            with self.assertRaises(ValueError):self.verify(row)

    def test_qualified_pool_and_projection(self):
        self.verify(self.protocol)
        for change in ({"reconstructionPoolId":"repair"},{"repairSides":10},{"repairSeconds":60},{"operatorId":"construct"},
                       {"toolDefinitionId":"spell"},{"dormancySeconds":1},{"bonusHp":2}):
            row=copy.deepcopy(self.protocol)
            row["values"].update(change)
            with self.assertRaises(ValueError):self.verify(row)
        self.targets["restore"]["actorId"]="operator"
        with self.assertRaises(ValueError):self.verify(self.protocol)
