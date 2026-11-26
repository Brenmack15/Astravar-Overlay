import json
from pathlib import Path
import runpy
import shutil
import sqlite3
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
legacy = runpy.run_path(str(ROOT / "scripts/tests/test_genesis_upgrade_readback.py"))
upgrade = runpy.run_path(str(ROOT / "scripts/ascension-upgrade-readback.py"))


class Android17ReadbackTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.directory = Path(self.temp.name)
        before = self.directory / "before"
        before.mkdir()
        self.leaf = "astravar-campaign-ledger.db"
        with sqlite3.connect(before / self.leaf) as db:
            legacy["create_entities"](db, legacy["schema"]("CampaignLedgerDb", 9))
            actor = {"id":"fixture-actor","name":"Ascension upgrade fixture","currentHp":17,"baseMaxHp":32,
                     "temporaryHp":3,"baseAbilities":{"INT":15}}
            legacy["insert"](db, "reforge_characters", profileId=actor["id"], characterJson=json.dumps(actor), revision=1)
            legacy["insert"](db, "reforge_changes", commandId="fixture-command", profileId=actor["id"])
        self.after = self.directory / "after"
        shutil.copytree(before,self.after)
        for name, value in (("sdk.txt","37"),("release.txt","17"),("guest-fingerprint.txt","test-emulator")):
            (self.directory / name).write_text(value)

    def test_retained_payload_and_report(self):
        result = upgrade["verify"](self.directory)
        self.assertEqual(10,len(result["tables"]))
        self.assertFalse(result["physicalDevice"])
        self.assertEqual(17,result["ordinaryHp"])

    def test_changed_history_refused(self):
        with sqlite3.connect(self.after / self.leaf) as db:
            db.execute("UPDATE reforge_changes SET operation='different'")
        with self.assertRaisesRegex(ValueError, "gameplay table changed"):
            upgrade["verify"](self.directory)

    def test_unexpected_cloud_activation_or_guest_version_refused(self):
        with sqlite3.connect(self.after / "astravar-cloud-cache.db") as db:
            db.execute("CREATE TABLE workspace(slot INTEGER)")
            db.execute("INSERT INTO workspace VALUES(1)")
        with self.assertRaisesRegex(ValueError, "bound a cloud continuity"):
            upgrade["verify"](self.directory)
        (self.directory / "sdk.txt").write_text("36")
        with self.assertRaisesRegex(ValueError, "guest version"):
            upgrade["verify"](self.directory)
