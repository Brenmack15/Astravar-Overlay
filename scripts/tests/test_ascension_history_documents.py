import base64
import copy
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "tools"))
from ascension_migrate_documents import FAMILY, FORMAT, index_documents
from ascension_migrate_legacy import encode, sha
from ascension_migrate_reviewed import project, template


class HistoricalDocumentsTest(unittest.TestCase):
    def setUp(self):
        body = "Entry\r\nTwo café records.\r\n".encode()
        self.record = {"sourceId": "original-fixture", "versionId": "0", "filename": "history.md",
                       "mediaType": "text/markdown", "bytes": len(body), "sha256": sha(body),
                       "base64": base64.b64encode(body).decode()}
        self.tenant = ("fixture-owner", "fixture-continuity")
        self.doc = {"format": FORMAT, "version": 1, "owner": self.tenant[0],
                    "continuity": self.tenant[1], "documents": [self.record]}

    def test_separate_versions_original_integrity_and_private_projection(self):
        self.doc["documents"].append(dict(self.record, versionId="1"))
        fmt, rows = index_documents(self.doc)
        self.assertEqual(fmt, FORMAT)
        self.assertEqual(len(rows), 2)
        record = rows[FAMILY, ("original-fixture", "0")]
        self.assertEqual(record["sha256"], self.record["sha256"])
        self.assertNotIn("base64", record)
        raw = encode(self.doc)
        plan = template([raw], *self.tenant)
        for decision in plan["decisions"]:
            decision.update(disposition="historical", reason="Earlier private observation")
        projected = project({sha(raw): raw}, plan, self.tenant)
        self.assertEqual(projected[1:], ([], [], {}))
        plan["decisions"][0].update(disposition="map", playerKnown=True, targets=["attempted-publication"])
        with self.assertRaisesRegex(ValueError, "visibility"):
            project({sha(raw): raw}, plan, self.tenant)

    def test_wrong_tenant_rejected_even_if_mapping_names_that_tenant(self):
        raw = encode(self.doc)
        wrong = ("different-owner", self.tenant[1])
        with self.assertRaisesRegex(ValueError, "owner or continuity"):
            project({sha(raw): raw}, template([raw], *wrong), wrong)

    def test_rejects_malformed_or_duplicate_sources(self):
        for change in ({"sha256": "0"*64}, {"bytes": True}, {"filename": "../file"},
                       {"base64": self.record["base64"][:-1]}, {"sourceId": ""}):
            with self.subTest(change=change), self.assertRaises(ValueError):
                bad = copy.deepcopy(self.doc)
                bad["documents"][0].update(change)
                index_documents(bad)
        self.doc["documents"].append(dict(self.record))
        with self.assertRaisesRegex(ValueError, "Duplicate"):
            index_documents(self.doc)
