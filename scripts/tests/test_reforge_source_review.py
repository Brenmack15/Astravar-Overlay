import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location("reforge_source_review", Path(__file__).parents[1] / "reforge_source_review.py")
review = importlib.util.module_from_spec(spec)
spec.loader.exec_module(review)


class ReforgeSourceReviewTests(unittest.TestCase):
    def fixture(self):
        old = b"fun oldEditor() = sourceRequired\n"
        new = b"fun newEditor() = historyOptional\n"
        return old, new, {"previous_pinned_sha256": hashlib.sha256(old).hexdigest(),
                          "reviewed_sha256": hashlib.sha256(new).hexdigest(),
                          "exact_replacements": [{"before": old.decode(), "after": new.decode()}]}

    def test_review_restores_exact_baseline_bytes(self):
        old, new, entry = self.fixture()
        self.assertEqual(old, review.restore_reviewed_source(new, entry))

    def test_unreviewed_edit_fails_even_if_replacement_still_exists(self):
        _, new, entry = self.fixture()
        with self.assertRaises(ValueError):
            review.restore_reviewed_source(new + b"unreviewed()\n", entry)

    def test_reverse_proof_cannot_claim_different_predecessor(self):
        _, new, entry = self.fixture()
        entry["exact_replacements"][0]["before"] = "different predecessor\n"
        with self.assertRaises(ValueError):
            review.restore_reviewed_source(new, entry)

    def test_ambiguous_replacement_fails(self):
        _, new, entry = self.fixture()
        duplicate = new + new
        entry["reviewed_sha256"] = hashlib.sha256(duplicate).hexdigest()
        with self.assertRaises(ValueError):
            review.restore_reviewed_source(duplicate, entry)

    def genesis_fixture(self, directory):
        root = Path(directory)
        source_root = Path(__file__).resolve().parents[2]
        target = root / review.GENESIS_CONDITION_PATH
        target.parent.mkdir(parents=True)
        target.write_bytes((source_root / review.GENESIS_CONDITION_PATH).read_bytes())
        (root / 'scripts').mkdir()
        manifest = json.loads((source_root / 'scripts/genesis_condition_source_review.json').read_text())
        (root / 'scripts/genesis_condition_source_review.json').write_text(json.dumps(manifest))
        return root, target, manifest

    def test_genesis_accuracy_exception_restores_original_pinned_model(self):
        with tempfile.TemporaryDirectory() as directory:
            root, _, _ = self.genesis_fixture(directory)
            original = review.baseline_bytes(root, review.GENESIS_CONDITION_PATH)
            self.assertEqual(review.GENESIS_CONDITION_PRIOR_HASH, hashlib.sha256(original).hexdigest())

    def test_extra_edit_default_or_damage_change_is_rejected(self):
        for before, after in [('conditionAttackAdjustment: Int = 0', 'conditionAttackAdjustment: Int = 1'),
                              ('intelligence + bonus)', 'intelligence + bonus + 1)'),
                              ('package com.yazsras.astravar.rules', '// unreviewed\npackage com.yazsras.astravar.rules')]:
            with self.subTest(before=before), tempfile.TemporaryDirectory() as directory:
                root, target, _ = self.genesis_fixture(directory)
                self.assertIn(before, target.read_text())
                target.write_text(target.read_text().replace(before, after, 1))
                with self.assertRaises(ValueError):
                    review.baseline_bytes(root, review.GENESIS_CONDITION_PATH)

    def test_recomputed_manifest_cannot_authorize_changed_default(self):
        with tempfile.TemporaryDirectory() as directory:
            root, target, manifest = self.genesis_fixture(directory)
            target.write_text(target.read_text().replace('conditionAttackAdjustment: Int = 0', 'conditionAttackAdjustment: Int = 1'))
            manifest['reviewed_sha256'] = hashlib.sha256(target.read_bytes()).hexdigest()
            for replacement in manifest['exact_replacements']:
                replacement['after'] = replacement['after'].replace('conditionAttackAdjustment: Int = 0', 'conditionAttackAdjustment: Int = 1')
            (root / 'scripts/genesis_condition_source_review.json').write_text(json.dumps(manifest))
            with self.assertRaises(ValueError):
                review.baseline_bytes(root, review.GENESIS_CONDITION_PATH)

    def test_general_reforge_manifest_still_cannot_waive_rules_source(self):
        with tempfile.TemporaryDirectory() as directory:
            root, _, _ = self.genesis_fixture(directory)
            (root / 'scripts/reforge_source_review.json').write_text(json.dumps({
                'verified_source_baseline': review.BASELINE,
                'files': {review.GENESIS_CONDITION_PATH: {}}}))
            with self.assertRaisesRegex(ValueError, 'cannot waive this immutable boundary'):
                review.baseline_bytes(root, review.GENESIS_CONDITION_PATH)
