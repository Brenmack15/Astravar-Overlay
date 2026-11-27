import sys
import unittest
from pathlib import Path

sys.path.insert(0,str(Path(__file__).resolve().parents[2]/"tools"))
from ascension_migrate_features import native_feature_value


class FeatureNormalizationTest(unittest.TestCase):
    def test_per_actor_skills_and_unconditional_feature_clauses(self):
        actor={"actorId":"reader","kind":"actor","data":{}}
        key=("digest","playable.characters",("reader",))
        src=["characterJson","$json","skills","Athletics"]
        dst=["data","skills","athletics","rank"]
        value={"proficient":True,"expertise":True}
        rank=lambda value:native_feature_value("nativeSkillRank",key,{},src,value,actor,dst)
        self.assertEqual(rank(value),2)
        with self.assertRaises(ValueError): rank(dict(value,halfProficiency=True))
        feat=("digest","playable.characters.features",("reader","feat"))
        source={"acceptance":"ACCEPTED_HOMEBREW"}
        row={"kind":"CLASS","source":source}
        def count(s):
            return native_feature_value("nativeAttackActionCount",feat,row,["description"],s,actor,["data","attacksPerAction"])
        self.assertEqual(count("You may make three attacks per Attack action."),3)
        with self.assertRaises(ValueError): count("One attack per Attack action while mounted.")
        row["kind"]="FEAT"
        target=dict(actorId="reader",kind="effect",data=dict(target="passive",key="athletics",operation="add",sourceRecord=source))
        text="+3 to passive Strength (Athletics) and passive Wisdom (Survival) ONLY."
        def bonus():
            return native_feature_value("nativePassiveBonus",feat,row,["description"],text,target,["data","amount"])
        self.assertEqual(bonus(),3)
        target["data"]["key"]="history"
        with self.assertRaises(ValueError): bonus()
        target["actorId"]="other"
        with self.assertRaises(ValueError): bonus()


if __name__=="__main__":
    unittest.main()
