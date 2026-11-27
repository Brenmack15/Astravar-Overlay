import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "tools"))
from ascension_migrate_features import native_feature_value


class CustomSkillTest(unittest.TestCase):
    def test_rank_requires_exact_unmodified_actor_skill(self):
        skill = "skill:sample"
        source = ("digest", "playable.characters.customSkills", ("reader", skill))
        training = {"ability": "DEX", "proficient": True, "expertise": True}
        native = {"id": skill, "training": training, "bonus": 0, "baselineOverride": None}

        def rank(row=native, owner="reader", dest=skill):
            return native_feature_value("nativeSkillRank", source, row, ["training"], row["training"],
                                        {"kind": "actor", "actorId": owner, "data": {}}, ["data", "skills", dest, "rank"])

        self.assertEqual(rank(), 2)
        self.assertEqual(rank(dict(native, training=dict(training, expertise=False))), 1)
        for field, value in (("bonus", 2), ("baselineOverride", 13), ("id", "other")):
            with self.assertRaises(ValueError):
                rank(dict(native, **{field: value}))
        with self.assertRaises(ValueError):
            rank(owner="other")
        with self.assertRaises(ValueError):
            rank(dest="other")
