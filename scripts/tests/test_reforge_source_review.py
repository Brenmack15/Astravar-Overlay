import hashlib
import importlib.util
from pathlib import Path
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
