"""Diagnostics must remain useful without serializing any private exception data."""
import io
import json
from pathlib import Path
import re
import subprocess
import sys
import unittest
import urllib.error

sys.path.insert(0, str(Path(__file__).parents[1]))
import owner_upgrade_diagnostics as D
import owner_upgrade_envelope as E


class DiagnosticsTest(unittest.TestCase):
    def test_literal_check_identifier_is_stable_and_locally_mapped(self):
        try:
            E.require(False, "Artifact archive digest mismatch")
        except E.RequirementFailure as error:
            identifier = D.failure_id(error)
        self.assertRegex(identifier, r"^CHECK_[0-9a-f]{16}$")
        self.assertEqual(identifier, E.RequirementFailure("Artifact archive digest mismatch").diagnostic_id)
        entry = D.catalog()[identifier]
        self.assertEqual(entry["meaning"], "Artifact archive digest mismatch")
        self.assertTrue(any("owner_upgrade_transfer.py:" in source for source in entry["source"]))

    def test_unknown_errors_never_publish_paths_urls_tokens_or_owner_bytes(self):
        secret = "https://token:PRIVATE_TOKEN@private.invalid/owner.apk?sig=PRIVATE_OWNER_BYTES"
        errors = [ValueError(secret), KeyError(secret), OSError(secret), RuntimeError(secret),
                  urllib.error.HTTPError(secret, 403, secret, {}, io.BytesIO(secret.encode())),
                  urllib.error.URLError(secret), subprocess.TimeoutExpired([secret], 3, output=secret)]
        for error in errors:
            with self.subTest(kind=type(error).__name__):
                identifier = D.failure_id(error)
                self.assertIn(identifier, D.GENERIC)
                self.assertNotIn("PRIVATE", identifier)
                self.assertNotIn("https", identifier)
                self.assertNotIn("owner.apk", identifier)

    def test_catalog_covers_all_literal_checks_and_contains_no_absolute_paths(self):
        catalog = D.catalog()
        self.assertGreater(len(catalog), 70)
        for identifier, entry in catalog.items():
            self.assertRegex(identifier, r"^(?:CHECK_[0-9a-f]{16}|[A-Z_]+)$")
            for location in entry.get("source", []):
                self.assertRegex(location, r"^scripts/owner_upgrade_[a-z]+\.py:\d+$")

    def test_disabled_cli_reports_a_controlled_code_and_failure_exit(self):
        # Use the real disabled checkpoint, or an independent disabled copy if
        # the root has since enabled a concrete reviewed configuration.
        import tempfile
        source = Path(__file__).parents[1]
        config = json.loads((source / "owner-upgrade-config.json").read_text())
        config["enabled"] = False
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "config.json"; path.write_text(json.dumps(config))
            result = subprocess.run([sys.executable, str(source / "owner_upgrade_transfer.py"),
                                     "check", "--config", str(path)], capture_output=True, text=True, timeout=10)
        self.assertEqual(result.returncode, 1)
        self.assertEqual(result.stdout, "")
        self.assertRegex(result.stderr, r"^Owner upgrade transfer failed: CHECK_[0-9a-f]{16}\n$")
        self.assertIn(result.stderr.strip().rsplit(" ", 1)[1], D.catalog())


if __name__ == "__main__": unittest.main()
