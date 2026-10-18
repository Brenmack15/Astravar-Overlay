"""Synthetic-only reconciliation acceptance. No private campaign sources."""
import copy
import importlib.util
from pathlib import Path
import tempfile
import subprocess
import unittest

spec = importlib.util.spec_from_file_location("campaign_import_preview", Path(__file__).resolve().parents[2] / "tools/campaign_import_preview.py")
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


def envelope(rows):
    return {"format": module.FORMAT, "campaignId": "fictional-campaign", "records": rows}


def item(identity="original:item:1", **fields):
    return {"kind": "item", "id": identity, "sourceId": "original:page:1", "sourceUrl": "https://example.invalid/fictional/page1", "revision": 2,
            "visibility": "player-known", "evidence": "Confirmed", "fields": {"name": "Fictional component", "quantity": None, **fields}}


class ReconciliationAcceptance(unittest.TestCase):
    def test_unknowns_and_original_ids_survive_and_no_commit_exists(self):
        row = item()
        before = copy.deepcopy(row)
        result = module.preview(envelope([row]), envelope([]))
        self.assertEqual(before, result["entries"][0]["sources"][0])
        self.assertIsNone(result["entries"][0]["sources"][0]["fields"]["quantity"])
        self.assertFalse(result["canCommit"])
        self.assertEqual(before, row)

    def test_same_name_different_ids_are_distinct_items(self):
        result = module.preview(envelope([item("id1"), item("id2")]), envelope([]))
        self.assertEqual(2, result["summary"]["PROPOSED_ADDITION"])

    def test_conflicting_hp_or_later_correction_never_overwrites_local(self):
        local = item(hp=60)
        incoming = item(hp=64)
        incoming["approvedCorrection"] = {"fields": ["hp"], "source": "claimed acceptance"}
        result = module.preview(envelope([incoming]), envelope([local]))
        entry = result["entries"][0]
        self.assertEqual("CONFLICT", entry["status"])
        self.assertEqual(60, entry["retained"]["fields"]["hp"])
        self.assertFalse(result["cutoverApproved"])

    def test_duplicates_and_unresolved_relationships_are_reviewed(self):
        row = item()
        row["references"] = [{"kind": "character", "id": "missing-original-character"}]
        result = module.preview(envelope([row, copy.deepcopy(row)]), envelope([]))
        self.assertEqual(1, len(result["duplicateRows"]))
        self.assertEqual("CONFLICT", result["entries"][0]["status"])

    def test_scope_hidden_data_negative_resources_and_stale_revisions_rejected(self):
        for change in ({"visibility": "dm-only"}, {"revision": 0}, {"fields": {"quantity": -1}}):
            bad = item(); bad.update(change)
            with self.assertRaises(ValueError): module.preview(envelope([bad]), envelope([]))
        wrong = envelope([]); wrong["campaignId"] = "other"
        with self.assertRaises(ValueError): module.preview(envelope([]), wrong)
        stale = item(); stale["revision"] = 1
        self.assertEqual("CONFLICT", module.preview(envelope([stale]), envelope([item()]))["entries"][0]["status"])

    def test_public_checkout_output_is_forbidden(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            subprocess.run(["git", "init", "--quiet", str(root)], check=True)
            with self.assertRaises(ValueError): module.private_output(root / "private-preview.json")

    def test_notion_source_without_ordered_revision_stays_unknown_and_review_only(self):
        row = item(); row["revision"] = None
        result = module.preview(envelope([row]), envelope([]))
        entry = result["entries"][0]
        self.assertIsNone(entry["sources"][0]["revision"])
        self.assertEqual("CONFLICT", entry["status"])
        self.assertTrue(any("revision is unknown" in reason for reason in entry["reasons"]))
        self.assertFalse(result["canCommit"])
