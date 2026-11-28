"""Portable fixtures for independently pinned supplemental definitions."""
import copy
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "tools"))
from ascension_migrate_legacy import encode, sha
from ascension_migrate_reviewed import indexed, source_key
from ascension_migrate_supplement import FAMILY, FORMAT, historical_projections, supplemental_witness, verify_supplements


class SupplementalEvidenceTest(unittest.TestCase):
    def setUp(self):
        native = {"id": "sample-reach", "text": "Short contact action"}
        self.origin = {"source": sha(b"fictional native export"), "family": "native.action",
                       "identity": ["sample-reach"], "rowSha256": sha(encode(native))}
        self.assertion = {"id": "review-reach", "kind": "attack", "actorId": "sample-actor",
                          "origin": self.origin, "ruling": "fixture-ruling",
                          "values": {"reach": 3}, "targetId": "sample-reach"}
        self.doc = {"format": FORMAT, "version": 1, "owner": "sample-owner",
                    "continuity": "qa", "records": [self.assertion]}
        self.source = sha(encode(self.doc))
        kind, rows = indexed(encode(self.doc))
        self.assertEqual(kind, FORMAT)
        self.key = self.source, FAMILY, ("review-reach",)
        self.entries = {(self.source, family, identity): row for (family, identity), row in rows.items()}
        self.entries[source_key(self.origin)] = native
        self.authority = {"supplements": [self.source], "decisions": {
            "fixture-ruling": {"decisionId": "fixture-ruling", "ownerConfirmation": True, "productionApproved": False}}}
        self.targets = {"sample-reach": {"id": "sample-reach", "actorId": "sample-actor",
                                        "kind": "attack", "data": {"reach": 3}}}
        self.dispositions = {self.key: {"targets": ["sample-reach"]}}

    def verify(self):
        verify_supplements({"reviewAuthority": self.authority}, self.entries,
                           self.targets, self.dispositions, source_key)

    def test_original_binding_and_scoped_exact_value(self):
        self.verify()
        self.targets["sample-reach"]["data"]["reach"] = 30
        with self.assertRaises(ValueError):
            self.verify()
        self.targets["sample-reach"]["data"]["reach"] = 3
        self.entries[source_key(self.origin)]["text"] = "A different original"
        with self.assertRaises(ValueError):
            self.verify()

    def test_changed_pin_target_and_unconfirmed_ruling(self):
        for part in ("supplement", "ruling", "projection"):
            with self.subTest(part=part):
                authority, targets = copy.deepcopy(self.authority), copy.deepcopy(self.targets)
                if part == "supplement":
                    authority["supplements"] = [sha(b"different approved supplement")]
                elif part == "ruling":
                    authority["decisions"]["fixture-ruling"]["ownerConfirmation"] = False
                else:
                    targets["sample-reach"]["actorId"] = "unrelated-actor"
                with self.assertRaises(ValueError):
                    verify_supplements({"reviewAuthority": authority}, self.entries,
                                       targets, self.dispositions, source_key)

    def test_witness_must_use_exact_authorized_field_and_target(self):
        witness = {"target": "sample-reach", "sourcePath": ["values", "reach"],
                   "targetPath": ["data", "reach"], "transform": "exact"}
        supplemental_witness(self.key, self.assertion, witness)
        for changes in ({"target": "other"}, {"transform": "canonicalRef"}, {"sourcePath": ["values", "id"]}):
            with self.subTest(changes=changes), self.assertRaises(ValueError):
                supplemental_witness(self.key, self.assertion, {**witness, **changes})

    def test_duplicate_assertion_and_unauthorized_state_changes_rejected_at_intake(self):
        for values in ({"quantity": 1}, {"currentHp": 3}, {"wallet": {}}, {"attuned": True}):
            with self.subTest(values=values), self.assertRaises(ValueError):
                indexed(encode({**self.doc, "records": [{**self.assertion, "values": values}]}))
        with self.assertRaises(ValueError):
            indexed(encode({**self.doc, "records": [self.assertion, self.assertion]}))

    def test_a_historical_projection_requires_bound_family_row_and_original_scope(self):
        def classify(family, row, kind, proof, *, sha_override=None, duplicate=False):
            claim = {"source": sha(b"native export"), "family": family, "identity": ["synthetic-state"],
                     "rowSha256": sha_override or sha(encode(row))}
            rule = {"binding": claim, "kind": kind, "proof": proof}
            rules = [rule, rule] if duplicate else [rule]
            key = source_key(claim)
            return historical_projections({"historicalProjections": rules}, {key:row}, source_key)
        kind = "older-unknown-condition-projection"
        proof = {"source": "older state"}
        row = {"condition": "Unknown"}
        self.assertEqual(len(classify("records.companionStates", row, kind, proof)), 1)
        for family, record, options in [
            ("records.facts", row, {}), ("records.companionStates", {"condition": "Active"}, {}),
            ("records.companionStates", row, {"sha_override": sha(b"changed")}),
            ("records.companionStates", row, {"duplicate": True})]:
            with self.subTest(family=family, options=options), self.assertRaises(ValueError):
                classify(family, record, kind, proof, **options)
        with self.assertRaises(ValueError):
            classify("playable.characters.features", {"description":"proposed only"},
                     "proposal-or-superseded-note", {"scope":"built"})
