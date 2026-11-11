import json
from pathlib import Path
import runpy
import sqlite3
import tempfile
import unittest

HELPER = runpy.run_path(str(Path(__file__).resolve().parents[1] / "genesis-upgrade-readback.py"))


class GenesisUpgradeReadbackTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.folder = Path(self.temp.name)
        with sqlite3.connect(self.folder / "astravar.db") as db:
            db.execute("CREATE TABLE opaque_history(id TEXT PRIMARY KEY, body BLOB)")
            db.execute("INSERT INTO opaque_history VALUES ('original', ?)", (b'\x00historical\xff',))
        character = {"id": "actor-id", "name": HELPER["ACTOR"], "revision": 4,
                     "currentHp": 17, "baseMaxHp": 32, "temporaryHp": 3, "baseAc": 16,
                     "itemDefinitions": [{"id": "definition-id", "name": HELPER["ITEM"]},
                                         {"id": "supplies-definition-id", "name": HELPER["SUPPLIES"]}],
                     "items": [{"id": "item-id", "definitionId": "definition-id", "quantity": 1, "state": "WORN"},
                               {"id": "supplies-id", "definitionId": "supplies-definition-id", "quantity": 3, "state": "CARRIED"}],
                     "resources": [{"id": "pool-id", "name": HELPER["RESOURCE"], "current": 2, "maximum": 5}]}
        with sqlite3.connect(self.folder / "astravar-reforge.db") as db:
            db.execute("CREATE TABLE reforge_characters(profileId TEXT PRIMARY KEY, characterJson TEXT)")
            db.execute("INSERT INTO reforge_characters VALUES (?,?)", ("actor-id", json.dumps(character)))
        with sqlite3.connect(self.folder / "astravar-campaign-ledger.db") as db:
            db.execute("CREATE TABLE character_profiles(id TEXT PRIMARY KEY, name TEXT, currentHp INTEGER, maximumHp INTEGER, revision INTEGER)")
            db.executemany("INSERT INTO character_profiles VALUES (?,?,?,?,?)", [
                ("actor-id", HELPER["ACTOR"], None, None, 1), ("ally-id", HELPER["ALLY"], 7, 18, 2)])
            db.execute("CREATE TABLE companion_states(companionId TEXT PRIMARY KEY)")
            db.execute("INSERT INTO companion_states VALUES ('ally-id')")
            db.execute("CREATE TABLE campaign_links(id TEXT PRIMARY KEY, fromId TEXT, toId TEXT, label TEXT)")
            db.execute("INSERT INTO campaign_links VALUES ('link-id','actor-id','ally-id','Fictional retained ally')")

    def tearDown(self):
        self.temp.cleanup()

    def test_identical_readback_proves_ids_resources_and_opaque_history(self):
        before = HELPER["inspect"](self.folder)
        HELPER["verify"](before, HELPER["inspect"](self.folder))
        self.assertEqual("item-id", before["fixture"]["item_id"])
        self.assertEqual("supplies-id", before["fixture"]["supplies_item_id"])
        self.assertEqual(1, before["databases"]["astravar.db"]["tables"]["opaque_history"]["rows"])

    def test_unrelated_history_mutation_cannot_pass_current_fixture_checks(self):
        before = HELPER["inspect"](self.folder)
        with sqlite3.connect(self.folder / "astravar.db") as db:
            db.execute("UPDATE opaque_history SET body=?", (b"changed",))
        with self.assertRaises(ValueError):
            HELPER["verify"](before, HELPER["inspect"](self.folder))

    def test_hp_refill_does_not_pass_as_successful_upgrade(self):
        with sqlite3.connect(self.folder / "astravar-reforge.db") as db:
            record = json.loads(db.execute("SELECT characterJson FROM reforge_characters").fetchone()[0])
            record["currentHp"] = 32
            db.execute("UPDATE reforge_characters SET characterJson=?", (json.dumps(record),))
        with self.assertRaises(AssertionError):
            HELPER["inspect"](self.folder)

    def test_empty_or_missing_store_is_never_successful_preservation(self):
        (self.folder / "astravar.db").unlink()
        with self.assertRaises(ValueError):
            HELPER["inspect"](self.folder)

    def test_changed_carried_stack_quantity_is_rejected(self):
        with sqlite3.connect(self.folder / "astravar-reforge.db") as db:
            record = json.loads(db.execute("SELECT characterJson FROM reforge_characters").fetchone()[0])
            record["items"][1]["quantity"] = 1
            db.execute("UPDATE reforge_characters SET characterJson=?", (json.dumps(record),))
        with self.assertRaises(AssertionError):
            HELPER["inspect"](self.folder)

    def test_lost_equipment_state_is_rejected(self):
        with sqlite3.connect(self.folder / "astravar-reforge.db") as db:
            record = json.loads(db.execute("SELECT characterJson FROM reforge_characters").fetchone()[0])
            record["items"][0]["state"] = "CARRIED"
            db.execute("UPDATE reforge_characters SET characterJson=?", (json.dumps(record),))
        with self.assertRaises(AssertionError):
            HELPER["inspect"](self.folder)


if __name__ == "__main__":
    unittest.main()
