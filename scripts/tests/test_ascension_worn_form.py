"""A projected wearable rule must resolve both native declarations to one physical source."""
import copy
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "tools"))
from ascension_migrate_legacy import encode, sha
from ascension_migrate_worn_form import verify_worn_form


def source_key(row):
    return row["source"], row["family"], tuple(row["identity"])


class WornFormTest(unittest.TestCase):
    def setUp(self):
        native = {"id":"shape", "name":"Fixture guise", "movement":{"walk":None}}
        families = {"authority.items.definitionJson.forms":["gear", "shape"],
                    "playable.characters.itemDefinitions.forms":["pilot", "pattern", "shape"]}
        bindings = [{"source":"snapshot", "family":k, "identity":v, "rowSha256":sha(encode(native)),
                     "original":native} for k,v in families.items()]
        self.entries = {source_key(b):native for b in bindings}
        self.dispositions = {k:{"disposition":"map", "targets":["shape-mode"]} for k in self.entries}
        self.rule = {"actorId":"pilot", "itemInstanceId":"gear", "itemDefinitionId":"pattern",
                     "sourceBindings":bindings, "decisionId":"fixture", "productionApproved":False,
                     "ownerConfirmation":"approved", "approvedMechanics":{"activation":"action",
                     "voluntaryReversion":"action", "duration":"indefinite while worn and operational",
                     "humanoidMovement":{"walkFt":20, "flyFt":0}, "preserveUnderlyingStatisticsAndHp":True,
                     "hybridAvailable":False, "humanoidNaturalWeaponAttacksAvailable":False,
                     "humanoidBreathWeaponsAvailable":False}}
        self.form = {"timing":"action", "activationSeconds":0, "sourceItemId":"gear", "requiresWorn":True,
                     "requiresOperating":True, "revertOnSourceLoss":True, "suppressesNaturalAttacks":True,
                     "blockedAttackPoolId":"fixture-pool", "speeds":{"walk":20, "fly":0}, "nativeDeclaration":native}
        self.targets = {"gear":{"kind":"item_instance","actorId":"pilot","data":{"definitionId":"pattern","quantity":1}},
                        "shape-mode":{"id":"shape-mode","kind":"form","actorId":"pilot","data":self.form},
                        "pattern":{"kind":"item_definition","data":{"wearable":True,"attunement":"none"}}}
        self.row = {"kind":"item_definition","values":{"wearable":True},"targetId":"pattern",
                    "ruling":"fixture","origin":bindings[1]}

    def validate(self):
        return verify_worn_form(self.row,self.rule,self.entries,self.targets,self.dispositions,source_key)

    def test_requires_both_originals_and_one_source(self):
        self.assertTrue(self.validate())
        original=copy.deepcopy(self.dispositions)
        for alternate in ({"disposition":"retain","targets":[]}, {"disposition":"map","targets":["other-mode"]}):
            self.dispositions[next(iter(original))]=alternate
            with self.assertRaises(ValueError):
                self.validate()

    def test_rejects_extra_benefits_and_wrong_source(self):
        for field,value in (("baseAc",22),("resetPoolId","extra"),("sourceItemId","other-gear"),
                            ("speeds",{"walk":35,"fly":0}),("requiresWorn",False)):
            with self.subTest(field=field):
                self.targets["shape-mode"]["data"]={**self.form,field:value}
                with self.assertRaises(ValueError):
                    self.validate()
