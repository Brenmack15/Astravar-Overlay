import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "tools"))
from ascension_migrate_spells import verify_grant_origin


class ReviewedSpellGrantTest(unittest.TestCase):
    def setUp(self):
        self.spell = {"id":"ward","title":"Ward","actorId":"mage","kind":"spell",
                      "data":{"baseLevel":1,"timing":"reaction","resolution":"manual"}}
        self.targets = {"ward":self.spell, "charge":{"kind":"resource_pool","actorId":"mage","data":{"type":"charge"}},
                        "slot":{"kind":"resource_pool","actorId":"mage","data":{"type":"spell_slot","slotLevel":1}},
                        "incoming":{"kind":"spell","actorId":"mage","data":{"baseLevel":1}}}
        self.native = {"id":"ward","level":1,"preparation":"ALWAYS_PREPARED","slotRequired":True}
        self.data = {"spellId":"ward","levels":[1],"preparation":"always_prepared","route":"slot",
                     "reviewedAdjudication":True,"reactionTriggerSpellId":"incoming"}

    def check(self, data, family="spells", identity="ward", source=None):
        g={"kind":"spell_grant","actorId":"mage","data":data}
        verify_grant_origin(g, ("digest","playable.characters."+family,("mage",identity)), source or self.native,self.targets)

    def test_slot_route_requires_owned_existing_level_and_exact_original(self):
        self.check(self.data)
        for delta in ({"route":"at_will"},{"levels":[1,2]},{"resourceId":"charge"},{"spellId":"incoming"},
                      {"reactionTriggerSpellId":"unrelated"},{"preparation":"known"}):
            with self.subTest(delta=delta), self.assertRaises(ValueError):
                self.check({**self.data,**delta})

    def test_limited_route_needs_named_spell_and_the_original_resource(self):
        fields={**self.data,"route":"limited","resourceId":"charge","cost":1}
        source={"description":"Ward once per rest","resourceId":"charge"}
        self.check(fields,"features","grant",source)
        for delta in ({"resourceId":"slot"},{"cost":2},{"levels":[2]}):
            with self.subTest(delta=delta), self.assertRaises(ValueError):
                self.check({**fields,**delta},"features","grant",source)
        with self.assertRaises(ValueError):
            self.check(fields,"features","grant",{**source,"description":"Another spell"})
