import importlib.util
import json
from pathlib import Path
import tempfile
import threading
import unittest
import urllib.error
import urllib.request
import uuid

PATH = Path(__file__).resolve().parents[2] / "tools" / "owner_sync_harness.py"
SPEC = importlib.util.spec_from_file_location("owner_sync_harness", PATH)
sync = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(sync)


class OwnerSyncHarnessTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.path = Path(self.directory.name) / "synthetic.db"
        self.clock = 10000
        self.service = sync.SyntheticOwnerService(self.path, now=lambda: self.clock)
        self.campaign = "synthetic-acceptance"
        self.credential, self.token = self.service.enroll_fixture_device(self.campaign)

    def tearDown(self):
        self.service.close()
        self.directory.cleanup()

    def body(self, revision=0, command=None, record="fixture-actor", fields=None):
        return sync.canonical(dict(v=1, campaignId=self.campaign, commandId=command or str(uuid.uuid4()),
                                   recordId=record, expectedRevision=revision, operation="replace_confirmed_record",
                                   confirmed=True, source="Synthetic acceptance fixture", fields=fields or {"name": "Test Explorer", "hp": None}))

    def commit(self, body):
        return self.service.execute(self.credential, self.token, self.campaign, body)

    def test_confirmed_write_returns_stable_receipt_and_independent_readback(self):
        body = self.body()
        original = self.commit(body)
        self.assertEqual(original, self.commit(body))
        records = self.service.records(self.credential, self.token, self.campaign)["records"]
        self.assertEqual(records, [{"recordId": "fixture-actor", "revision": 1, "fields": {"name": "Test Explorer", "hp": None}}])
        changes = self.service.changes(self.credential, self.token, self.campaign)
        self.assertEqual(1, len(changes["events"]))
        self.assertEqual(original["receipt"]["eventId"], changes["events"][0]["eventId"])

    def test_restart_preserves_records_original_receipt_and_credential_revocation(self):
        body = self.body()
        result = self.commit(body)
        self.service.close()
        self.service = sync.SyntheticOwnerService(self.path, now=lambda: self.clock)
        self.assertEqual(result, self.commit(body))
        self.service.revoke_fixture_device(self.credential)
        with self.assertRaisesRegex(sync.Denied, "CREDENTIAL_REVOKED_OR_EXPIRED"):
            self.commit(body)
        self.service.close()
        self.service = sync.SyntheticOwnerService(self.path, now=lambda: self.clock)
        with self.assertRaisesRegex(sync.Denied, "CREDENTIAL_REVOKED_OR_EXPIRED"):
            self.service.records(self.credential, self.token, self.campaign)

    def test_stale_revision_rolls_back_and_does_not_consume_command_id(self):
        self.commit(self.body())
        command = str(uuid.uuid4())
        with self.assertRaisesRegex(sync.Denied, "REVISION_CONFLICT"):
            self.commit(self.body(command=command))
        receipt = self.commit(self.body(revision=1, command=command))["receipt"]
        self.assertEqual(2, receipt["cursor"])
        self.assertEqual(2, len(self.service.changes(self.credential, self.token, self.campaign)["events"]))

    def test_reused_command_with_changed_content_is_rejected(self):
        body = self.body()
        self.commit(body)
        data = json.loads(body)
        data["fields"]["name"] = "Different fixture"
        with self.assertRaisesRegex(sync.Denied, "COMMAND_ID_REUSED"):
            self.commit(sync.canonical(data))
        self.assertEqual(1, len(self.service.changes(self.credential, self.token, self.campaign)["events"]))

    def test_access_denied_for_cross_campaign_wrong_token_read_only_and_expiry(self):
        with self.assertRaisesRegex(sync.Denied, "ACCESS_DENIED"):
            self.service.records(self.credential, self.token, "synthetic-other")
        with self.assertRaisesRegex(sync.Denied, "CREDENTIAL_REVOKED_OR_EXPIRED"):
            self.service.records(self.credential, "invalid", self.campaign)
        read_id, read_token = self.service.enroll_fixture_device(self.campaign, write=False)
        self.service.records(read_id, read_token, self.campaign)
        with self.assertRaisesRegex(sync.Denied, "ACCESS_DENIED"):
            self.service.execute(read_id, read_token, self.campaign, self.body())
        self.clock += 3600
        with self.assertRaisesRegex(sync.Denied, "CREDENTIAL_REVOKED_OR_EXPIRED"):
            self.service.records(self.credential, self.token, self.campaign)

    def test_real_campaign_scope_fails_closed(self):
        for campaign in ("local-player", "astravar", "../synthetic-fixture", "synthetic-fixture/other"):
            with self.assertRaises(sync.Denied):
                self.service.enroll_fixture_device(campaign)

    def test_unknown_values_are_preserved_and_unconfirmed_commands_rejected(self):
        body = json.loads(self.body())
        body["confirmed"] = False
        with self.assertRaisesRegex(sync.Denied, "INVALID_COMMAND"):
            self.commit(sync.canonical(body))
        body["confirmed"] = True
        self.commit(sync.canonical(body))
        self.assertIsNone(self.service.records(self.credential, self.token, self.campaign)["records"][0]["fields"]["hp"])

    def test_cross_runtime_unicode_canonical_fixture_and_decimal_rejection(self):
        body = dict(v=1, campaignId="synthetic-native-acceptance", commandId="00000000-0000-0000-0000-000000000001",
                    recordId="actor", expectedRevision=0, operation="replace_confirmed_record", confirmed=True,
                    source="fixture", fields={"😀": "accent é", "\ue000": None, "integer": 42})
        import hashlib
        self.assertEqual("a59d32aaca438f1d93581551f49f2986ea0434e0215f02971ec0c70c4e80ae20", hashlib.sha256(sync.canonical(body).encode()).hexdigest())
        with self.assertRaises(ValueError):
            sync.canonical({"decimal": 1.5})

    def test_page_cursor_is_contiguous_and_ahead_cursor_rejected(self):
        for i in range(3):
            self.commit(self.body(record=f"fixture-{i}"))
        first = self.service.changes(self.credential, self.token, self.campaign, limit=2)
        self.assertEqual((2, 3, True), (first["nextCursor"], first["currentCursor"], first["hasMore"]))
        second = self.service.changes(self.credential, self.token, self.campaign, after=2)
        self.assertEqual((3, False), (second["nextCursor"], second["hasMore"]))
        with self.assertRaisesRegex(sync.Denied, "INVALID_CURSOR"):
            self.service.changes(self.credential, self.token, self.campaign, after=4)

    def test_concurrent_commands_on_same_revision_commit_once(self):
        results = []
        def write():
            try:
                results.append(self.commit(self.body()))
            except sync.Denied as error:
                results.append(error.code)
        threads = [threading.Thread(target=write) for _ in range(4)]
        for thread in threads:
            thread.start()
        for thread in threads:
            thread.join()
        self.assertEqual(3, results.count("REVISION_CONFLICT"))
        self.assertEqual(1, len(self.service.changes(self.credential, self.token, self.campaign)["events"]))

    def test_http_protocol_auth_write_receipt_retry_independent_read_revocation(self):
        server = sync.loopback_server(self.service)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        origin = f"http://127.0.0.1:{server.server_port}/v1/campaigns/{self.campaign}"
        headers = {"Authorization": "Bearer " + self.token, "X-Astravar-Credential": self.credential, "Content-Type": "application/json"}
        def request(path, body=None, request_headers=None):
            req = urllib.request.Request(origin + path, body.encode() if body else None, headers=request_headers or headers)
            with urllib.request.urlopen(req, timeout=2) as response:
                return json.load(response)
        try:
            with self.assertRaises(urllib.error.HTTPError) as error:
                request("/records", request_headers={"Accept": "application/json"})
            self.assertEqual(401, error.exception.code)
            body = self.body()
            committed = request("/commands", body)
            self.assertEqual(committed, request("/commands", body))
            self.assertEqual(1, request("/records")["records"][0]["revision"])
            self.assertEqual(committed["receipt"]["eventId"], request("/changes?after=0&limit=50")["events"][0]["eventId"])
            with self.assertRaises(urllib.error.HTTPError) as error:
                request("/records", request_headers=dict(headers, Origin="https://untrusted.example"))
            self.assertEqual(403, error.exception.code)
            self.service.revoke_fixture_device(self.credential)
            with self.assertRaises(urllib.error.HTTPError) as error:
                request("/records")
            self.assertEqual(401, error.exception.code)
        finally:
            server.shutdown()
            server.server_close()
            thread.join(2)


if __name__ == "__main__":
    unittest.main()
