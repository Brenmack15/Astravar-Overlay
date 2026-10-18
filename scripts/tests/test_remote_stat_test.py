"""No network writes: independent protocol fixtures, retry and privacy boundary tests."""
import copy
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

SCRIPT = Path(__file__).resolve().parents[1] / "remote_stat_test.py"
spec = importlib.util.spec_from_file_location("remote_stat_test", SCRIPT)
client = importlib.util.module_from_spec(spec)
spec.loader.exec_module(client)
COMMAND = "05469a20-d289-4d8c-8522-bce258afbb2b"
SESSION = "a56f6040-4ae8-4015-9cdc-735ea010d363"
EVENT = "f7ea04a6-4705-4fd2-8b98-d69af185c59e"


def state():
    return client.prepare_state(SESSION, "INT", 0, 16, 10, COMMAND)


def receipt(saved):
    body = client.validate_state(saved)
    return {"mode": client.MODE, "campaignId": client.CAMPAIGN,
            "connectionId": client.CONNECTION, "status": "COMMITTED",
            "commandId": body["commandId"], "eventId": EVENT,
            "priorRevision": body["expectedRevision"], "resultingRevision": body["expectedRevision"] + 1,
            "payloadFingerprint": saved["fingerprint"], "recordedAt": "2026-10-18T12:00:00Z"}


class FakeTransport:
    def __init__(self):
        self.receipt = None
        self.posts = []
        self.drop_response = False
        self.conflict = False

    def __call__(self, path, body=None):
        if path.endswith("/receipt"):
            if self.receipt is None:
                raise client.ApiError(404, "COMMAND_NOT_FOUND")
            return self.receipt
        if path == client.BASE + "/milestones":
            self.posts.append(body)
            if self.conflict:
                raise client.ApiError(409, "REVISION_CONFLICT")
            self.receipt = receipt(state())
            if self.drop_response:
                self.drop_response = False
                raise OSError("Simulated offline after commit")
            return self.receipt
        raise AssertionError(path)


class RemoteStatHostTests(unittest.TestCase):
    def test_exact_canonical_wire_fixture(self):
        detail = client.parse_json(state()["body"])["detail"]
        self.assertEqual('{"type":"astravar.remote-ability","v":1,"commandId":"05469a20-d289-4d8c-8522-bce258afbb2b",'
                         '"sessionId":"a56f6040-4ae8-4015-9cdc-735ea010d363","profileId":"remote-stat-test-v1",'
                         '"ability":"INT","expectedRevision":0,"score":16,"source":"ChatGPT fictional test"}', detail)
        self.assertEqual(COMMAND, client.validate_state(state())["commandId"])

    def test_fingerprint_matches_independent_existing_live_fixture(self):
        # Fixture previously observed from deployed Worker, independent of this sender.
        detail = "Disposable Test Merchant recorded a fictional Astravar Android connectivity test."
        self.assertEqual("5a03ba85a554c142f1e20a143b1196d0ba5a5a9dd7c48d6449748997e7a655ab", client.fingerprint(detail, 5))

    def test_no_private_profiles_or_extra_fields_can_be_sent(self):
        for key, value in (("profileId", "real-character"), ("source", "private notes"), ("inventory", ["private"])):
            saved = state()
            outer = client.parse_json(saved["body"])
            inner = client.parse_json(outer["detail"])
            inner[key] = value
            outer["detail"] = client.canonical(inner)
            saved["body"] = client.canonical(outer)
            with self.assertRaises(client.ClientError):
                client.send(saved, lambda *_: self.fail("Invalid private payload reached network"))

    def test_destination_and_fingerprint_cannot_be_changed(self):
        for key, value in (("origin", "https://other.workers.dev"), ("fingerprint", "bad")):
            saved = state()
            saved[key] = value
            with self.assertRaises(client.ClientError):
                client.validate_state(saved)

    def test_loose_types_duplicate_fields_extra_whitespace_and_reordered_payload_are_rejected(self):
        for value in (True, 0.0, "0", None):
            with self.assertRaises(client.ClientError):
                client.command_detail(COMMAND, SESSION, "INT", value, 16)
        for value in (True, 16.0, "16", -1, 1001):
            with self.assertRaises(client.ClientError):
                client.command_detail(COMMAND, SESSION, "INT", 0, value)
        with self.assertRaises(client.ClientError):
            client.parse_json('{"v":1,"v":2}')
        for mutate in (lambda s: s.replace('"v":1', '"v":1 '),
                       lambda s: s.replace('"type":"astravar.remote-ability","v":1', '"v":1,"type":"astravar.remote-ability"')):
            saved = state()
            body = client.parse_json(saved["body"])
            body["detail"] = mutate(body["detail"])
            saved["body"] = client.canonical(body)
            with self.assertRaises(client.ClientError):
                client.validate_state(saved)

    def test_missing_or_mismatched_command_uuid_rejected(self):
        for value in ("bad", COMMAND.upper()):
            with self.assertRaises(client.ClientError):
                client.command_detail(value, SESSION, "INT", 0, 16)
        saved = state()
        body = client.parse_json(saved["body"])
        body["commandId"] = SESSION
        saved["body"] = client.canonical(body)
        with self.assertRaises(client.ClientError):
            client.validate_state(saved)

    def test_transport_revision_and_confirmed_are_strict(self):
        for value in (0, -1, True, 1.0, client.MAX_REVISION + 1):
            with self.assertRaises(client.ClientError):
                client.prepare_state(SESSION, "INT", 0, 16, value, COMMAND)
        saved = state()
        body = client.parse_json(saved["body"])
        body["confirmed"] = 1
        saved["body"] = client.canonical(body)
        with self.assertRaises(client.ClientError):
            client.validate_state(saved)

    def test_receipt_first_replay_never_posts_duplicate(self):
        transport = FakeTransport()
        first = client.send(state(), transport)
        second = client.send(state(), transport)
        self.assertEqual(first, second)
        self.assertEqual(1, len(transport.posts))

    def test_offline_after_commit_recovered_with_same_id_and_body(self):
        transport = FakeTransport()
        transport.drop_response = True
        saved = state()
        original = copy.deepcopy(saved)
        with self.assertRaises(OSError):
            client.send(saved, transport)
        recovered = client.send(saved, transport)
        self.assertEqual(COMMAND, recovered["commandId"])
        self.assertEqual(original, saved)
        self.assertEqual([saved["body"]], transport.posts)

    def test_revision_conflict_does_not_rebase_or_replace_uuid(self):
        transport = FakeTransport()
        transport.conflict = True
        saved = state()
        original = copy.deepcopy(saved)
        with self.assertRaises(client.ApiError) as result:
            client.send(saved, transport)
        self.assertEqual(409, result.exception.status)
        self.assertEqual(original, saved)
        self.assertEqual([saved["body"]], transport.posts)

    def test_unknown_receipt_failure_does_not_post(self):
        def unavailable(*_):
            raise client.ApiError(429, "DAILY_WRITE_CAP")
        with self.assertRaises(client.ApiError):
            client.send(state(), unavailable)

    def test_corrupt_receipt_never_claims_commit(self):
        for key, value in (("payloadFingerprint", "bad"), ("commandId", SESSION),
                           ("priorRevision", True), ("resultingRevision", 12), ("campaignId", "other")):
            bad = receipt(state())
            bad[key] = value
            with self.assertRaises(client.ClientError):
                client.validate_receipt(bad, state())

    def test_prepared_state_roundtrips_without_replacing_existing_file(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "command.json"
            client.save_new(path, state())
            restored = client.parse_json(path.read_text())
            self.assertEqual(state(), restored)
            client.validate_state(restored)
            with self.assertRaises(FileExistsError):
                client.save_new(path, state())

    def test_health_refuses_authenticated_or_real_campaign_service(self):
        for field, value in (("realCampaignAccess", True), ("authentication", "OAUTH"), ("disposable", False)):
            def unsafe(path, body=None):
                self.assertEqual("/health", path)
                health = {"mode": client.MODE, "disposable": True, "authentication": "NONE", "realCampaignAccess": False}
                health[field] = value
                return health
            with self.assertRaises(client.ClientError):
                client.read_revision(unsafe)

    def test_only_tiny_same_command_session_outcome_is_returned(self):
        outcome = {"type": "astravar.remote-ability-result", "v": 1,
                   "commandId": COMMAND, "sessionId": SESSION, "status": "APPLIED"}
        candidates = ["not json", client.canonical({**outcome, "score": 16}),
                      client.canonical({**outcome, "sessionId": COMMAND}), client.canonical(outcome)]
        events = [{"kind": "new_fact", "detail": detail, "priorRevision": n + 1, "resultingRevision": n + 2}
                  for n, detail in enumerate(candidates)]
        page = {"mode": client.MODE, "campaignId": client.CAMPAIGN, "connectionId": client.CONNECTION,
                "milestones": events, "hasMore": False, "currentRevision": 5, "nextCursor": 5}
        self.assertEqual([outcome], client.read_outcomes(state(), lambda *_: page))
        self.assertEqual({"type", "v", "commandId", "sessionId", "status"}, set(outcome))

    def test_history_gap_or_bad_cursor_cannot_verify_outcome(self):
        for field, value in (("nextCursor", 2), ("hasMore", True), ("currentRevision", 2)):
            page = {"mode": client.MODE, "campaignId": client.CAMPAIGN, "connectionId": client.CONNECTION,
                    "milestones": [], "hasMore": False, "currentRevision": 1, "nextCursor": 1}
            page[field] = value
            with self.assertRaises(client.ClientError):
                client.read_outcomes(state(), lambda *_: page)


if __name__ == "__main__":
    unittest.main()
