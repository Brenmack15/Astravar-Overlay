"""Independent archive validation uses fictional source bytes only."""
import base64
import importlib.util
import json
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location("campaign_archive_verifier", Path(__file__).resolve().parents[2] / "tools/verify_campaign_archive.py")
verifier = importlib.util.module_from_spec(spec)
spec.loader.exec_module(verifier)


class CampaignArchiveVerificationTest(unittest.TestCase):
    def fixture(self):
        body = "Fictional historical source; no gameplay occurs."
        return dict(campaignId="fictional-campaign", title="Fictional archive", exportedAt=1,
                    sourceRootUrl="https://example.invalid/root", sourceRootRevision="fictional-revision",
                    records=[dict(externalId="fictional-row", title="Fictional row", category="Reference", body=body,
                                  propertiesJson="{}", sourceUrl="https://example.invalid/row", sourceRevision="1",
                                  contentSha256=verifier.sha256(body), relationIds=[], visibility="player")],
                    issues=[], attachments=[])

    def encode(self, value):
        payload = json.dumps(value, ensure_ascii=False)
        return json.dumps(dict(format="astravar-campaign-archive", schema=1,
                               payloadSha256=verifier.sha256(payload), payload=payload)).encode()

    def test_full_identity_and_content_hash_are_reported_without_import(self):
        report = verifier.inspect_archive(self.encode(self.fixture()))
        self.assertEqual(1, report["referenceRecords"])
        self.assertTrue(report["relationshipTargetsComplete"])
        self.assertFalse(report["importPerformed"])
        self.assertFalse(report["cutoverApproved"])

    def test_missing_relation_is_preserved_and_reported_as_unresolved(self):
        value = self.fixture()
        value["records"][0]["relationIds"] = ["fictional-missing-target"]
        report = verifier.inspect_archive(self.encode(value))
        self.assertEqual(1, report["relationships"])
        self.assertEqual([["fictional-row", "fictional-missing-target"]], [list(row) for row in report["unresolvedRelationships"]])
        self.assertFalse(report["relationshipTargetsComplete"])

    def test_collection_source_identity_is_retained(self):
        value = self.fixture()
        value["records"][0]["sourceUrl"] = "collection://fictional-source-identity"
        self.assertEqual(1, verifier.inspect_archive(self.encode(value))["referenceRecords"])

    def test_non_player_body_hash_and_duplicate_json_are_rejected(self):
        value = self.fixture()
        value["records"][0]["visibility"] = "dm-only"
        with self.assertRaises(ValueError): verifier.inspect_archive(self.encode(value))
        value = self.fixture()
        value["records"][0]["body"] = "Changed fictional source"
        with self.assertRaises(ValueError): verifier.inspect_archive(self.encode(value))
        raw = self.encode(self.fixture()).decode().replace("{", '{"schema": 1,', 1)
        with self.assertRaises(ValueError): verifier.inspect_archive(raw.encode())

    def test_embedded_attachment_bytes_are_independently_hashed(self):
        value = self.fixture()
        content = b"Fictional attachment"
        value["attachments"] = [dict(id="fictional-attachment", fileName="fictional.txt", status="embedded",
                                     base64=base64.b64encode(content).decode(), sha256=verifier.sha256(content))]
        self.assertEqual(1, verifier.inspect_archive(self.encode(value))["embeddedAttachments"])
        value["attachments"][0]["sha256"] = "0" * 64
        with self.assertRaises(ValueError): verifier.inspect_archive(self.encode(value))


if __name__ == "__main__": unittest.main()
