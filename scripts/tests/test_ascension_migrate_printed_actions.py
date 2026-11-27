"""Detached synthetic evidence for conversion of printed action terms."""
import sys
import unittest
from pathlib import Path

sys.path.insert(0,str(Path(__file__).resolve().parents[2]/"tools"))
from ascension_migrate_printed_actions import printed_action_terms, validate_printed_witness
from ascension_migrate_reviewed import path_value


def entry(description, timing="Action"):
    return {"id":"fixture-defense","source":{"acceptance":"ACCEPTED_HOMEBREW"},
            "description":description,"actionType":timing,"resourceId":"fixture-reactor"}


class PrintedActionTermsTest(unittest.TestCase):
    def test_pair_preserves_each_record(self):
        supplied = entry("Completed synthetic fixture. Magical Horn +3 1d6+2 piercing, Tail +4 2d8-1 bludgeoning, one each Multiattack.")
        data=printed_action_terms(supplied)
        self.assertEqual(data["sequence"],["Horn","Tail"])
        self.assertEqual(path_value(supplied,["$printedAction","attacks","Tail","flat"]),-1)
        self.assertEqual(data["attacks"]["Horn"]["fixedHit"],3)
        self.assertTrue(data["attacks"]["Horn"]["magical"])
        for change in [("Tail","Horn"),("Completed","Proposed"),("synthetic fixture","synthetic fixture if charged"),("2d8","0d8")]:
            with self.subTest(change=change), self.assertRaises(ValueError):
                printed_action_terms(entry(supplied["description"].replace(*change)))

    def test_selected_save_and_cost(self):
        row=entry("Bonus Action once per deployment, selected hearing creatures 20ft CHA DC12 or Frightened up to 2min, repeat at end of turn; allies may be excluded.","Bonus Action")
        self.assertEqual(printed_action_terms(row),{"range":20,"saveAbility":"CHA","saveDc":12,"durationSeconds":120,"cost":1})
        with self.assertRaises(ValueError):
            printed_action_terms({**row,"resourceId":None})
        with self.assertRaises(ValueError):
            printed_action_terms({**row,"source":{"acceptance":"PLANNED"}})
        with self.assertRaises(ValueError):
            printed_action_terms({**row,"description":row["description"]+" The save changes later."})

    def test_shared_grounded_teleport(self):
        row=entry("Bonus Action Phase Gate <=45ft to visible unoccupied destination, 2/LR shared both forms; Huge Golem must arrive grounded.","Bonus Action")
        self.assertEqual(printed_action_terms(row),{"range":45,"maximum":2,"cost":1})
        with self.assertRaises(ValueError):
            printed_action_terms({**row,"description":row["description"].replace("visible unoccupied","any")})

    def test_wrong_target_field_is_rejected(self):
        attack={"kind":"attack","title":"Horn","data":{"formId":"fixture-form"}}
        path=["$printedAction","attacks","Horn","dice"]
        validate_printed_witness(path,["data","components",0,"dice"],attack,"exact")
        for dest in (["data","baseAc"], ["data","fixedHit"], ["data","components",1,"dice"]):
            with self.assertRaises(ValueError):
                validate_printed_witness(path,dest,attack,"exact")

    def test_bounded_replacement(self):
        row=entry("Fixture twin swing. Replace at most one with Sonic Pulse. End.")
        self.assertEqual(printed_action_terms(row),{"maximum":1,"replacement":"Sonic Pulse"})
        with self.assertRaises(ValueError):
            printed_action_terms(entry(row["description"]+" Replace again."))
        with self.assertRaises(ValueError):
            path_value({"nested":row},["nested","$printedAction","replacement"])


if __name__ == "__main__":
    unittest.main()
