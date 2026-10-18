import importlib.util
import json
from pathlib import Path
import sqlite3
import shutil
import tempfile
import unittest

spec = importlib.util.spec_from_file_location("history_fixture", Path(__file__).parents[1] / "history-preservation-fixture.py")
fixture = importlib.util.module_from_spec(spec)
spec.loader.exec_module(fixture)


class HistoryPreservationFixtureTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.path = Path(self.temp.name) / "astravar.db"
        self.manifest = Path(self.temp.name) / "expected.json"
        self.campaign = {"demo": True, "revision": 10, "cores": [{"id": "fixture-core"}], "pending": [], "gameSeconds": 0}
        db = sqlite3.connect(self.path)
        db.executescript("""
            PRAGMA user_version=3;
            CREATE TABLE campaign(id INTEGER PRIMARY KEY,body TEXT NOT NULL);
            CREATE TABLE events(id TEXT PRIMARY KEY,revision INTEGER NOT NULL,body TEXT NOT NULL,origin TEXT NOT NULL,label TEXT NOT NULL,gameSeconds INTEGER NOT NULL,undone INTEGER NOT NULL);
            CREATE TABLE rolls(id TEXT PRIMARY KEY,sequence INTEGER NOT NULL,body TEXT NOT NULL);
            CREATE TABLE archives(id TEXT PRIMARY KEY,body TEXT NOT NULL);
            CREATE TABLE room_master_table(id INTEGER PRIMARY KEY,identity_hash TEXT);
            INSERT INTO room_master_table VALUES(42,'synthetic-v3-identity');
            CREATE TRIGGER bound_rolls AFTER INSERT ON rolls BEGIN DELETE FROM rolls WHERE id NOT IN(SELECT id FROM rolls ORDER BY sequence DESC,id DESC LIMIT 20); END;
            CREATE TRIGGER bound_events AFTER INSERT ON events BEGIN DELETE FROM events WHERE id NOT IN(SELECT id FROM events ORDER BY revision DESC,id DESC LIMIT 20); END;
        """)
        db.execute("INSERT INTO campaign VALUES(1,?)", (json.dumps(self.campaign),))
        for n in range(2):
            shot = {"plan": {"id": f"recent-roll-{n}", "ability": "ARCANE", "coreId": "fixture-core", "revision": n,
                             "profile": {"character": "CI FICTIONAL"}}, "rolled": {"synthetic-face": n + 1}}
            event = {"id": f"recent-event-{n}", "before": self.campaign, "after": self.campaign, "compact": True}
            db.execute("INSERT INTO rolls VALUES(?,?,?)", (shot["plan"]["id"], n, json.dumps(shot)))
            db.execute("INSERT INTO events VALUES(?,?,?,?,?,?,?)", (event["id"], n, json.dumps(event), "main", "Synthetic original", 0, 0))
        db.commit(); db.close()

    def tearDown(self):
        self.temp.cleanup()

    def emulate_raw_archive_then_bounded_cleanup(self):
        """A stand-in consumer establishes the fixture checker contract, not Android verification."""
        db = sqlite3.connect(self.path)
        originals = []
        for table in fixture.TABLES:
            originals.extend((table, str(key), body) for key, body in db.execute(f"SELECT id,body FROM {table}"))
        for table, key, body in originals:
            db.execute("INSERT OR IGNORE INTO archives VALUES(?,?)", (fixture.archive_id(table, key, body), body))
        for table, order in (("rolls", "sequence"), ("events", "revision")):
            db.execute(f"DELETE FROM {table} WHERE id NOT IN(SELECT id FROM {table} ORDER BY {order} DESC,id DESC LIMIT 20)")
        # Real startup may compact a retained event; its exact original must still be independently recoverable.
        db.execute("UPDATE events SET body=? WHERE id='recent-event-0'", ('{"synthetic":"compacted"}',))
        db.commit(); db.close()

    def test_seed_exceeds_old_window_but_preserves_campaign_and_two_newest_raw_rolls(self):
        before = sqlite3.connect(self.path)
        campaign = before.execute("SELECT body FROM campaign").fetchone()[0]
        rolls = list(before.execute("SELECT id,sequence,body FROM rolls ORDER BY sequence DESC"))
        before.close()
        result = fixture.seed(self.path, self.manifest, True)
        self.assertEqual(62, result["seeded_rows"])
        db = sqlite3.connect(self.path)
        self.assertEqual(campaign, db.execute("SELECT body FROM campaign").fetchone()[0])
        self.assertEqual(rolls, list(db.execute("SELECT id,sequence,body FROM rolls ORDER BY sequence DESC LIMIT 2")))
        self.assertEqual(32, db.execute("SELECT COUNT(*) FROM rolls").fetchone()[0])
        self.assertEqual(33, db.execute("SELECT COUNT(*) FROM events").fetchone()[0])
        self.assertEqual("delete", db.execute("PRAGMA journal_mode").fetchone()[0])
        self.assertEqual(3, db.execute("PRAGMA user_version").fetchone()[0])
        self.assertEqual("synthetic-v3-identity", db.execute("SELECT identity_hash FROM room_master_table WHERE id=42").fetchone()[0])
        seeded = json.loads(db.execute("SELECT body FROM rolls WHERE id='synthetic-history-roll-000'").fetchone()[0])
        self.assertEqual("SHATTER", seeded["plan"]["ability"])
        self.assertIsNone(seeded["rolled"])
        db.close()

    def test_copied_wal_is_checkpointed_without_losing_committed_demo_state(self):
        source = sqlite3.connect(self.path)
        self.assertEqual("wal", source.execute("PRAGMA journal_mode=WAL").fetchone()[0])
        state = {**self.campaign, "notes": "CI FICTIONAL committed only in copied WAL"}
        source.execute("UPDATE campaign SET body=? WHERE id=1", (json.dumps(state),))
        source.commit()
        copied = Path(self.temp.name) / "copied"
        copied.mkdir()
        target = copied / "astravar.db"
        try:
            for suffix in ("", "-wal", "-shm"):
                candidate = Path(str(self.path) + suffix)
                if candidate.exists():
                    shutil.copyfile(candidate, Path(str(target) + suffix))
        finally:
            source.close()
        fixture.seed(target, self.manifest, True)
        db = sqlite3.connect(target)
        self.assertEqual(state, json.loads(db.execute("SELECT body FROM campaign WHERE id=1").fetchone()[0]))
        self.assertEqual("delete", db.execute("PRAGMA journal_mode").fetchone()[0])
        db.close()
        self.assertFalse(Path(str(target) + "-wal").exists())

    def test_new_preservation_triggers_are_removed_before_injection_and_recorded(self):
        db = sqlite3.connect(self.path)
        db.execute("CREATE TRIGGER preserve_rolls_insert AFTER INSERT ON rolls BEGIN INSERT INTO archives(id,body) VALUES('prearchived-'||NEW.id,NEW.body); END")
        db.execute("CREATE TRIGGER preserve_archive_no_update_collision BEFORE UPDATE ON archives WHEN OLD.id!=NEW.id BEGIN SELECT RAISE(ABORT,'Synthetic production-name collision guard'); END")
        db.execute("CREATE TRIGGER unrelated_archive_observer AFTER INSERT ON archives BEGIN SELECT 1; END")
        db.commit(); db.close()
        fixture.seed(self.path, self.manifest, True)
        db = sqlite3.connect(self.path)
        self.assertEqual(0, db.execute("SELECT COUNT(*) FROM archives WHERE id LIKE 'prearchived-%'").fetchone()[0])
        db.close()
        dropped = {row["name"] for row in json.loads(self.manifest.read_text())["dropped_history_triggers"]}
        self.assertEqual({"bound_events", "bound_rolls", "preserve_rolls_insert", "preserve_archive_no_update_collision"}, dropped)
        db = sqlite3.connect(self.path)
        self.assertEqual([("unrelated_archive_observer",)], list(db.execute("SELECT name FROM sqlite_master WHERE type='trigger' ORDER BY name")))
        db.close()

    def test_verify_accepts_exact_raw_archives_and_unchanged_working_records(self):
        fixture.seed(self.path, self.manifest, True)
        self.emulate_raw_archive_then_bounded_cleanup()
        result = fixture.verify(self.path, self.manifest, True)
        self.assertEqual(20, result["recent_events"])
        self.assertEqual(20, result["recent_rolls"])
        self.assertGreater(result["immutable_archive_rows"], 20)
        self.assertTrue(result["current_campaign_unchanged"])

    def test_verify_rejects_previous_destructive_pruning_and_archive_deletion(self):
        fixture.seed(self.path, self.manifest, True)
        db = sqlite3.connect(self.path)
        db.execute("DELETE FROM rolls WHERE sequence<0")
        db.execute("DELETE FROM events WHERE revision<0")
        db.execute("DELETE FROM archives")
        db.commit(); db.close()
        with self.assertRaisesRegex(ValueError, "Raw history missing or rewritten"):
            fixture.verify(self.path, self.manifest, True)

    def test_archive_id_cannot_hide_changed_raw_bytes(self):
        fixture.seed(self.path, self.manifest, True)
        self.emulate_raw_archive_then_bounded_cleanup()
        manifest = json.loads(self.manifest.read_text())
        target = next(row for row in manifest["records"] if row["id"] == "synthetic-history-malformed-event")
        db = sqlite3.connect(self.path)
        db.execute("UPDATE archives SET body='changed' WHERE id=?", (target["archive_id"],))
        db.commit(); db.close()
        with self.assertRaisesRegex(ValueError, "Raw history missing or rewritten"):
            fixture.verify(self.path, self.manifest, True)

    def test_refuses_non_demo_or_missing_acknowledgement_without_mutation(self):
        with self.assertRaisesRegex(ValueError, "acknowledgement"):
            fixture.seed(self.path, self.manifest, False)
        db = sqlite3.connect(self.path)
        db.execute("UPDATE campaign SET body=?", (json.dumps({**self.campaign, "demo": False}),))
        db.commit(); db.close()
        with self.assertRaisesRegex(ValueError, "non-demo"):
            fixture.seed(self.path, self.manifest, True)
        db = sqlite3.connect(self.path)
        self.assertEqual(2, db.execute("SELECT COUNT(*) FROM rolls").fetchone()[0])
        self.assertEqual(2, db.execute("SELECT COUNT(*) FROM sqlite_master WHERE type='trigger'").fetchone()[0])
        db.close()
        self.assertFalse(self.manifest.exists())

    def test_seed_rejects_existing_manifest_and_partial_native_fixture(self):
        self.manifest.write_text("retained evidence")
        with self.assertRaisesRegex(ValueError, "existing expected-hash"):
            fixture.seed(self.path, self.manifest, True)
        self.assertEqual("retained evidence", self.manifest.read_text())
        self.manifest.unlink()
        db = sqlite3.connect(self.path)
        db.execute("DELETE FROM rolls")
        db.commit(); db.close()
        with self.assertRaisesRegex(ValueError, "two-roll demo"):
            fixture.seed(self.path, self.manifest, True)

    def test_current_values_and_recent_window_are_independently_verified(self):
        fixture.seed(self.path, self.manifest, True)
        self.emulate_raw_archive_then_bounded_cleanup()
        db = sqlite3.connect(self.path)
        db.execute("UPDATE campaign SET body=? WHERE id=1", (json.dumps({**self.campaign, "gameSeconds": 1}),))
        db.commit(); db.close()
        with self.assertRaisesRegex(ValueError, "Current demo campaign values changed"):
            fixture.verify(self.path, self.manifest, True)


if __name__ == "__main__":
    unittest.main()
