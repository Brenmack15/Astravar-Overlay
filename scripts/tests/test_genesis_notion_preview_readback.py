import json
from pathlib import Path
import runpy
import shutil
import sqlite3
import unittest

ROOT = Path(__file__).resolve().parents[2]
HELPER = runpy.run_path(str(ROOT / "scripts/genesis-notion-preview-readback.py"))
CONNECTED = runpy.run_path(str(ROOT / "scripts/tests/test_genesis_connected_readback.py"))


class NotionPreviewReadbackTest(unittest.TestCase):
    def setUp(self):
        self.fixture = CONNECTED["GenesisConnectedReadbackTest"]()
        self.fixture.setUp()
        self.before = self.fixture.database
        with sqlite3.connect(self.before) as db:
            fixture = CONNECTED["FIXTURE"]
            fixture["create_entities"](db, fixture["schema"]("CampaignLedgerDb", 9))
        self.after = self.fixture.root / "notion-preview.db"
        shutil.copyfile(self.before, self.after)

    def tearDown(self):
        self.fixture.tearDown()

    def test_all_unchanged_rows_pass_and_do_not_claim_native_apply(self):
        result = HELPER["inspect"](self.before, self.after, synthetic_emulator=True)
        self.assertTrue(result["all_campaign_rows_unchanged"])
        self.assertFalse(result["native_apply_performed"])
        self.assertGreater(result["rows_verified"], 0)

    def test_changed_hp_and_added_history_are_rejected(self):
        with sqlite3.connect(self.after) as db:
            db.execute("UPDATE character_profiles SET currentHp = 15 WHERE id = 'hero'")
        with self.assertRaisesRegex(ValueError, "changed saved records"):
            HELPER["inspect"](self.before, self.after, synthetic_emulator=True)
        shutil.copyfile(self.before, self.after)
        with sqlite3.connect(self.after) as db:
            db.execute("UPDATE genesis_campaigns SET notes = 'An unapproved preview write' WHERE id = 'campaign'")
        with self.assertRaisesRegex(ValueError, "changed saved records"):
            HELPER["inspect"](self.before, self.after, synthetic_emulator=True)

    def test_requires_explicit_synthetic_scope(self):
        with self.assertRaisesRegex(ValueError, "fictional emulator"):
            HELPER["inspect"](self.before, self.after)

    def test_fixture_is_named_for_existing_hero_and_contains_deferred_item(self):
        data = json.loads((ROOT / "scripts/fixtures/native-notion-preview.json").read_text())
        self.assertEqual("Genesis Native Hero", data["character"]["name"])
        self.assertEqual("astravar-notion-character-transfer-v1", data["format"])
        self.assertEqual(["Carried", "Consumed"], [item["state"] for item in data["inventory"]])
        self.assertIsNone(data["provisionalGpEquivalent"])
        self.assertFalse(any(item.get("acceptedNumericEffects") for item in data["inventory"]))
