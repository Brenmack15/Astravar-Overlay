import argparse
import importlib.util
import json
from pathlib import Path
import sqlite3
import tempfile
import unittest

spec = importlib.util.spec_from_file_location("native_campaign_readback", Path(__file__).resolve().parents[1] / "native-campaign-import-readback.py")
readback = importlib.util.module_from_spec(spec)
spec.loader.exec_module(readback)


class IndependentNativeCampaignReadbackTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.path = self.root / "astravar-campaign-ledger.db"
        self.manifest = self.root / "baseline.json"
        self.fixture = self.root / "fixture.json"
        db = sqlite3.connect(self.path)
        db.executescript('''PRAGMA user_version=4;
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
        return argparse.Namespace(action=action,database=self.path,manifest=self.manifest,fixture=self.fixture,backup_document=None,synthetic_demo=True)

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
