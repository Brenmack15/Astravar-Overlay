"""An equipped full-health actor must retain base HP, with the source item still required."""
import json
from pathlib import Path
import subprocess
import unittest

CONTRACT = Path(__file__).resolve().parents[2] / "tools" / "ascension_migrate_contracts.mjs"


class EffectiveCapacityTest(unittest.TestCase):
    def test_current_health_above_base_requires_active_projection(self):
        ident = "fixture-actor"
        actor = dict(id=ident, kind="actor", title="Fixture", actorId=ident, life="active", data=dict(
            abilities=dict(STR=10, DEX=11, CON=12, INT=14, WIS=13, CHA=8), baseAc=10, effectIds=["growth"],
            maxHp=31, currentHp=36, level=5, role="player", rulesProfile="legacy_2014",
            baselineConstitutionModifier=1, skills={}, conditions=[], poolIds=[], proficiency=3))
        effect = dict(id="growth", kind="effect", title="Fixture enhancement", actorId=ident, life="active", data=dict(
            target="ability_score", key="CON", operation="add", amount=2, enabled=True,
            source="Fixture garment", sourceItemId="gear", requiresAttunement=True, requiresWorn=True))
        item = dict(id="gear", kind="item_instance", actorId=ident, life="active", title="Fixture gear", data=dict(
            partyAccess=True, custodianId=ident, quantity=1, equipment="worn", attuned=True, holding="carried"))

        def check(rows, passes):
            result = subprocess.run(["node", str(CONTRACT)], input=json.dumps(rows), capture_output=True, text=True)
            if passes:
                self.assertEqual(result.returncode, 0, result.stderr)
            else:
                self.assertNotEqual(result.returncode, 0)
                self.assertIn("HP exceeds recorded effective capacity", result.stderr)

        check([actor,effect,item], True)
        check([actor], False)
        item["data"]["attuned"]=False
        check([actor,effect,item], False)
        item["data"].update(attuned=True,equipment="packed")
        check([actor,effect,item], False)
        item["data"]["equipment"]="held"
        check([actor,effect,item], False)
        item["data"]["equipment"]="worn"
        actor["data"]["currentHp"]=37
        check([actor,effect,item], False)


if __name__ == "__main__":
    unittest.main()
