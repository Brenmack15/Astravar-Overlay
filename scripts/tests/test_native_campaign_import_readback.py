import argparse
import importlib.util
import json
from pathlib import Path
import sqlite3
import subprocess
import sys
import tempfile
import unittest

spec = importlib.util.spec_from_file_location("native_campaign_readback", Path(__file__).resolve().parents[1] / "native-campaign-import-readback.py")
readback = importlib.util.module_from_spec(spec)
spec.loader.exec_module(readback)


class IndependentNativeCampaignReadbackTest(unittest.TestCase):
    room_schema = 4

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.path = self.root / "astravar-campaign-ledger.db"
        self.manifest = self.root / "baseline.json"
        self.fixture = self.root / "fixture.json"
        db = sqlite3.connect(self.path)
        db.executescript(f'''PRAGMA user_version={self.room_schema};
        CREATE TABLE fictional_existing(id TEXT PRIMARY KEY,quantity INTEGER);
        INSERT INTO fictional_existing VALUES('fictional-current-item',NULL);
        CREATE TABLE campaign_references(id TEXT PRIMARY KEY,campaignId TEXT,externalId TEXT,title TEXT,category TEXT,body TEXT,propertiesJson TEXT,sourceUrl TEXT,sourceRevision TEXT,contentSha256 TEXT,packageHash TEXT);
        CREATE TABLE campaign_reference_relations(id TEXT PRIMARY KEY,fromId TEXT,toId TEXT,kind TEXT,resolved INTEGER);
        CREATE TABLE campaign_import_receipts(packageHash TEXT PRIMARY KEY,state TEXT,recordCount INTEGER,relationCount INTEGER,readbackSha256 TEXT);
        CREATE TABLE campaign_import_backups(packageHash TEXT PRIMARY KEY,snapshotJson TEXT,snapshotSha256 TEXT,sourceStateSha256 TEXT);
        CREATE TABLE campaign_archive_sources(packageHash TEXT PRIMARY KEY,payloadJson TEXT);''')
        db.commit(); db.close()
        readback.run(self.args("snapshot"))
        records=[]
        for identity in ("fictional-note", "fictional-contact"):
            body="Fictional body "+identity
            records.append(dict(externalId=identity,title=identity,category="Fictional",body=body,propertiesJson="{}",sourceUrl="https://example.invalid/"+identity,sourceRevision="1",contentSha256=readback.sha(body),relationIds=["fictional-contact"] if identity == "fictional-note" else []))
        payload=json.dumps(dict(campaignId="fictional-native-migration",records=records),ensure_ascii=False)
        self.package=readback.sha(payload)
        self.fixture.write_text(json.dumps(dict(format="astravar-campaign-archive",schema=1,payloadSha256=self.package,payload=payload)))
        db=sqlite3.connect(self.path)
        for row in records:
            db.execute("INSERT INTO campaign_references VALUES(?,?,?,?,?,?,?,?,?,?,?)", ("fictional-native-migration::"+row["externalId"],"fictional-native-migration",row["externalId"],row["title"],row["category"],row["body"],row["propertiesJson"],row["sourceUrl"],row["sourceRevision"],row["contentSha256"],self.package))
        a="fictional-native-migration::fictional-note";b="fictional-native-migration::fictional-contact"
        db.execute("INSERT INTO campaign_reference_relations VALUES(?,?,?,?,1)",(readback.sha(a+"\0"+b),a,b,"notion-relation"))
        db.execute("INSERT INTO campaign_import_receipts VALUES(?, 'APPLIED',2,1,'fictional-after')",(self.package,))
        backup='{"fictional":"backup"}'
        db.execute("INSERT INTO campaign_import_backups VALUES(?,?,?,'fictional-before')",(self.package,backup,readback.sha(backup)))
        db.execute("INSERT INTO campaign_archive_sources VALUES(?,?)",(self.package,payload))
        db.commit();db.close()

    def args(self,action):
        return argparse.Namespace(action=action,database=self.path,manifest=self.manifest,fixture=self.fixture,backup_document=None,synthetic_demo=True,expected_room_schema=self.room_schema)

    def mutate(self,sql):
        with sqlite3.connect(self.path) as db:db.execute(sql)

    def test_independent_readback_preserves_unknown_and_verifies_originals(self):
        result=readback.run(self.args("applied"))
        self.assertEqual(2,result["active_imported_records"])
        self.assertTrue(result["preexisting_rows_unchanged"])

    def test_content_and_relationship_identity_tampering_are_detected(self):
        self.mutate("UPDATE campaign_reference_relations SET id='wrong-identity'")
        with self.assertRaises(AssertionError):readback.run(self.args("applied"))

    def test_preexisting_unknown_cannot_silently_become_zero(self):
        self.mutate("UPDATE fictional_existing SET quantity=0")
        with self.assertRaisesRegex(AssertionError,"preexisting campaign row changed"):
            readback.run(self.args("applied"))

    def test_rollback_requires_retained_receipts_and_restored_rows(self):
        with sqlite3.connect(self.path) as db:
            for table in ("campaign_references","campaign_reference_relations","campaign_archive_sources"):
                db.execute("DELETE FROM "+table)
            db.execute("INSERT INTO campaign_import_receipts VALUES(?,'ROLLED_BACK',2,1,'fictional-before')",("rollback:"+self.package,))
        result=readback.run(self.args("rolled-back"))
        self.assertEqual(0,result["active_imported_records"])
        self.assertEqual(["APPLIED","ROLLED_BACK"],result["receipt_states"])
        self.mutate("DELETE FROM campaign_import_receipts WHERE state='APPLIED'")
        with self.assertRaises(KeyError):readback.run(self.args("rolled-back"))

    def test_wrong_or_unsupported_room_version_is_rejected(self):
        for version in (3, 6, 5 if self.room_schema == 4 else 4):
            with self.subTest(version=version):
                self.mutate(f"PRAGMA user_version={version}")
                with self.assertRaisesRegex(ValueError, "Expected additive campaign Room schema"):
                    readback.run(self.args("applied"))
        self.mutate(f"PRAGMA user_version={self.room_schema}")

    def test_baseline_schema_drift_is_rejected(self):
        baseline = json.loads(self.manifest.read_text())
        baseline["roomSchema"] = 5 if self.room_schema == 4 else 4
        self.manifest.write_text(json.dumps(baseline))
        with self.assertRaisesRegex(AssertionError, "schema changed since the captured baseline"):
            readback.run(self.args("applied"))


class IndependentSchema5NativeCampaignReadbackTest(IndependentNativeCampaignReadbackTest):
    room_schema = 5


class IndependentMappedNativeReadbackTest(unittest.TestCase):
    room_schema = 4

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.database = self.root / "astravar-campaign-ledger.db"
        self.manifest = self.root / "mapped-before.json"
        repo = Path(__file__).resolve().parents[2]
        self.fixture = repo / "overlay-target/src/androidTest/assets/fictional-mapped-campaign.json"
        self.envelope = json.loads(self.fixture.read_text())
        self.source = json.loads(self.envelope["payload"])
        self.package = self.envelope["payloadSha256"]
        schema = json.loads((repo / f"app/schemas/com.yazsras.astravar.data.CampaignLedgerDb/{self.room_schema}.json").read_text())
        with sqlite3.connect(self.database) as db:
            db.execute(f"PRAGMA user_version={self.room_schema}")
            for entity in schema["database"]["entities"]:
                db.execute(entity["createSql"].replace("${TABLE_NAME}", entity["tableName"]))
            original = dict(self.source["mapped"]["items"][0], id="fictional-before-item", title="Fictional existing unknown item",
                            notionPageId=None, itemState="Unknown", location="Unknown", owner="Unknown")
            self.insert(db, "ledger_items", original)
            self.insert(db, "campaign_import_backups", dict(packageHash="b" * 64, snapshotJson="{}", snapshotSha256=readback.sha("{}"),
                        sourceStateSha256="fictional-earlier-state", createdAt=1))
        args = argparse.Namespace(action="snapshot", database=self.database, manifest=self.manifest, synthetic_demo=True, expected_room_schema=self.room_schema)
        readback.run(args)
        self.groups = {"ledger_items": self.source["mapped"]["items"], "ledger_events": self.source["mapped"]["events"]}
        self.groups.update({table: self.source["mapped"]["records"][label] for label, table in readback.MAPPED_TABLES.items()})
        with sqlite3.connect(self.database) as db:
            for table, rows in self.groups.items():
                for row in rows:
                    self.insert(db, table, row)
            for row in self.source["records"]:
                self.insert(db, "campaign_references", {key: row[key] for key in ("externalId", "title", "category", "body", "propertiesJson", "sourceUrl", "sourceRevision", "contentSha256")} |
                            dict(id=self.source["campaignId"] + "::" + row["externalId"], campaignId=self.source["campaignId"], packageHash=self.package))
            self.insert(db, "campaign_archive_sources", dict(packageHash=self.package, payloadJson=self.envelope["payload"]))
            self.insert(db, "campaign_import_receipts", dict(packageHash=self.package, campaignId=self.source["campaignId"], title=self.source["title"], importedAt=1,
                        recordCount=len(self.source["records"]), relationCount=0, state="APPLIED", readbackSha256="fictional-after", summary="Fictional typed test"))
            self.insert(db, "campaign_import_backups", dict(packageHash=self.package, snapshotJson="{}", snapshotSha256=readback.sha("{}"),
                        sourceStateSha256="fictional-before", createdAt=2))

    @staticmethod
    def insert(db, table, row):
        values = [json.dumps(value, ensure_ascii=False, separators=(",", ":")) if isinstance(value, list) else value for value in row.values()]
        db.execute('INSERT INTO "' + table + '" (' + ','.join('"' + key + '"' for key in row) + ') VALUES (' + ','.join('?' for _ in row) + ')', values)

    def verify(self, **kwargs):
        return readback.verify_mapped(self.database, self.manifest, self.fixture, synthetic_demo=True, **kwargs)

    def test_complete_mapped_values_and_every_original_table_are_verified(self):
        result = self.verify()
        self.assertTrue(result["mapped_complete_values_verified"])
        self.assertTrue(result["null_values_preserved"])
        self.assertTrue(result["preexisting_rows_and_archive_evidence_unchanged"])
        self.assertEqual(1, result["mapped_counts"]["ledger_items"])

    def test_workflow_cli_preserves_complete_baseline_and_verifies_copied_database(self):
        command = Path(__file__).resolve().parents[1] / "native_recheck.py"
        output = self.root / "readback.json"
        subprocess.check_call([sys.executable, str(command), "verify-mapped", "--database", str(self.database),
                               "--manifest", str(self.manifest), "--fixture", str(self.fixture),
                               "--output", str(output), "--expected-room-schema", str(self.room_schema), "--synthetic-demo"], stdout=subprocess.DEVNULL)
        self.assertTrue(json.loads(output.read_text())["mapped_complete_values_verified"])
        captured = self.root / "captured.json"
        subprocess.check_call([sys.executable, str(command), "snapshot-mapped", "--database", str(self.database),
                               "--output", str(captured), "--expected-room-schema", str(self.room_schema), "--synthetic-demo"], stdout=subprocess.DEVNULL)
        self.assertIn("campaign_import_backups", json.loads(captured.read_text())["allTables"])

    def test_unknown_hp_quantity_or_wallet_cannot_become_zero(self):
        for table, field, key, identity in [("ledger_items", "quantity", "id", "fictional-mapped-lens"),
                                             ("character_profiles", "currentHp", "id", "local-player"),
                                             ("party_wallets", "copper", "id", "fictional-mapped-wallet")]:
            with self.subTest(table=table):
                with sqlite3.connect(self.database) as db:
                    db.execute(f'UPDATE "{table}" SET "{field}"=0 WHERE "{key}"=?', (identity,))
                with self.assertRaisesRegex(AssertionError, "Mapped readback differs"):
                    self.verify()
                with sqlite3.connect(self.database) as db:
                    db.execute(f'UPDATE "{table}" SET "{field}"=NULL WHERE "{key}"=?', (identity,))

    def test_preexisting_original_and_immutable_backup_changes_are_detected(self):
        with sqlite3.connect(self.database) as db:
            db.execute("UPDATE campaign_import_backups SET snapshotJson='tampered' WHERE packageHash=?", ("b" * 64,))
        with self.assertRaisesRegex(AssertionError, "preexisting typed row or immutable archive"):
            self.verify()

    def test_missing_relationship_target_or_historical_row_is_detected(self):
        with sqlite3.connect(self.database) as db:
            db.execute("DELETE FROM ledger_events WHERE commandId='fictional-mapped-acquisition'")
        with self.assertRaisesRegex(AssertionError, "Imported typed row missing"):
            self.verify()
        with sqlite3.connect(self.database) as db:
            self.insert(db, "ledger_events", self.groups["ledger_events"][0])
            db.execute("DELETE FROM character_profiles WHERE id='local-player'")
        with self.assertRaisesRegex(AssertionError, "Imported typed row missing"):
            self.verify()

    def test_rollback_restores_every_table_and_retains_audit_receipts(self):
        with sqlite3.connect(self.database) as db:
            for table, rows in self.groups.items():
                columns = list(db.execute(f'PRAGMA table_info("{table}")'))
                keys = [row[1] for row in sorted(columns, key=lambda row: row[5]) if row[5]]
                for row in rows:
                    db.execute('DELETE FROM "' + table + '" WHERE ' + ' AND '.join('"' + key + '"=?' for key in keys), [row[key] for key in keys])
            db.execute("DELETE FROM campaign_references WHERE packageHash=?", (self.package,))
            db.execute("DELETE FROM campaign_archive_sources WHERE packageHash=?", (self.package,))
            self.insert(db, "campaign_import_receipts", dict(packageHash="rollback:" + self.package, campaignId=self.source["campaignId"], title=self.source["title"], importedAt=2,
                        recordCount=1, relationCount=0, state="ROLLED_BACK", readbackSha256="fictional-before", summary="Fictional recovery"))
        self.assertEqual("mapped-rollback-verified", self.verify(rolled_back=True)["status"])
        with sqlite3.connect(self.database) as db:
            db.execute("DELETE FROM campaign_import_receipts WHERE packageHash=?", (self.package,))
        with self.assertRaises(AssertionError):
            self.verify(rolled_back=True)


class IndependentMappedSchema5NativeReadbackTest(IndependentMappedNativeReadbackTest):
    room_schema = 5

    def test_legacy_absent_electrum_and_platinum_are_verified_as_null(self):
        for field in ("electrum", "platinum"):
            with self.subTest(field=field):
                with sqlite3.connect(self.database) as db:
                    db.execute(f'UPDATE party_wallets SET "{field}"=0 WHERE id=?', ("fictional-mapped-wallet",))
                with self.assertRaisesRegex(AssertionError, "Mapped readback differs"):
                    self.verify()
                with sqlite3.connect(self.database) as db:
                    db.execute(f'UPDATE party_wallets SET "{field}"=NULL WHERE id=?', ("fictional-mapped-wallet",))

    def test_schema4_fixture_cannot_establish_electrum_or_platinum_even_zero(self):
        for field in ("electrum", "platinum"):
            for value in (0, 3):
                with self.subTest(field=field, value=value):
                    envelope = json.loads(self.fixture.read_text())
                    source = json.loads(envelope["payload"])
                    source["mapped"]["records"]["wallets"][0][field] = value
                    envelope["payload"] = json.dumps(source, ensure_ascii=False)
                    envelope["payloadSha256"] = readback.sha(envelope["payload"])
                    invalid = self.root / "invalid-schema4-coin.json"; invalid.write_text(json.dumps(envelope))
                    with self.assertRaisesRegex(AssertionError, "Legacy schema4 wallet cannot establish"):
                        readback.verify_mapped(self.database, self.manifest, invalid, synthetic_demo=True)

    def test_unexpected_columns_cannot_be_ignored(self):
        with sqlite3.connect(self.database) as db:
            db.execute("ALTER TABLE party_wallets ADD COLUMN unreviewed_coin INTEGER")
        with self.assertRaisesRegex(AssertionError, "Fixture must supply complete typed values"):
            self.verify()

    def test_mapped_baseline_schema_drift_is_rejected(self):
        baseline = json.loads(self.manifest.read_text()); baseline["roomSchema"] = 4
        self.manifest.write_text(json.dumps(baseline))
        with self.assertRaisesRegex(AssertionError, "schema changed since the captured baseline"):
            self.verify()

    def test_unsupported_mapped_room_schema_is_rejected(self):
        with sqlite3.connect(self.database) as db:
            db.execute("PRAGMA user_version=6")
        with self.assertRaisesRegex(AssertionError, "Unsupported campaign Room schema"):
            self.verify()
