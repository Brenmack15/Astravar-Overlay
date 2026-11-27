import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "tools"))
from ascension_migrate_range import native_attack_distance

class NativeDistanceTest(unittest.TestCase):
    def read(self, value, field, delivery, **other):
        return native_attack_distance(("digest", "playable.characters.attacks", ("actor-a", "fixture-attack")),
                                      ["range"], ["data", field], {"range": value},
                                      {"kind": "attack", "actorId": "actor-a", "data": {"delivery": delivery, **other}})

    def test_single_and_paired_ranges(self):
        self.assertEqual(7, self.read("7 ft.", "reach", "melee"))
        self.assertEqual(60, self.read("60 ft.", "range", "ranged"))
        for field,expected in (("range",20),("longRange",80)):
            self.assertEqual(expected,self.read("20/80 ft.",field,"ranged",range=20,longRange=80))

    def test_reject_conditional_or_partial_distances(self):
        for text,field,delivery,other in [
            ("5 ft. (first); 10 ft. (second)", "reach", "melee", {}),
            ("60/120 ft.; extended with aid", "range", "ranged", {"range":60,"longRange":120}),
            ("20/80 ft.", "reach", "melee", {}), ("20/80 ft.", "range","ranged",{"range":20}),
            ("80/20 ft.", "longRange", "ranged",{"range":80,"longRange":20}),
            ("7 ft.","reach","ranged",{}), ("7 m.","range","ranged",{}),
            ("7 ft.","longRange","ranged",{}), ("10001 ft.","range","ranged",{}),
        ]:
            with self.subTest(text=text,field=field),self.assertRaises(ValueError):
                self.read(text,field,delivery,**other)

    def test_bound_to_native_attack_actor_and_field(self):
        target={"kind":"attack","actorId":"other","data":{"delivery":"melee"}}
        for key,source_path,target_path in [
            (("h","playable.characters.attacks",("actor","attack")),["range"],["data","reach"]),
            (("h","review.facts",("review",)),["values","range"],["data","reach"]),
            (("h","playable.characters.attacks",("other","attack")),["unrelated"],["data","reach"]),
            (("h","playable.characters.attacks",("other","attack")),["range"],["data","hitBonus"]),
        ]:
            with self.assertRaises(ValueError):
                native_attack_distance(key,source_path,target_path,{"range":"7 ft."},target)

    def test_conditional_display_bands_require_full_manual_rule(self):
        from ascension_migrate_range import native_conditional_distance
        full = "30/70 ft.; farther targets require a visible marker"
        data = {"nativeRange":full, "resolution":"manual", "delivery":"ranged", "range":30, "longRange":70}
        key = ("source", "playable.characters.attacks", ("reader","arc"))
        target = {"kind":"attack", "actorId":"reader", "data":data}
        read = lambda: native_conditional_distance(key, ["range"], ["data","longRange"], {"range":full}, target)
        self.assertEqual(read(),70)
        data["resolution"] = "attack"
        with self.assertRaises(ValueError): read()
        data.update(resolution="manual", nativeRange="30/70 ft.")
        with self.assertRaises(ValueError): read()
