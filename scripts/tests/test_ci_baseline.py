"""Frozen artifact reuse fails closed on source, signer or payload corruption."""
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import warnings
import zipfile

SPEC = importlib.util.spec_from_file_location("ci_baseline", Path(__file__).resolve().parents[1] / "ci-baseline.py")
baseline = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(baseline)


class FrozenBaselineTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.apk = self.root / "baseline.apk"
        self.manifest = self.root / "baseline.json"
        self.inputs = baseline.expected_inputs(49)
        self.write_apk()
        self.document = dict(inputs=self.inputs, apk_sha256=baseline.sha256(self.apk),
                             payload=baseline.payload_entries(self.apk), certificate_sha256="1" * 64)
        self.save()

    def write_apk(self, dex=b"original", signer=b"first"):
        with zipfile.ZipFile(self.apk, "w") as apk:
            apk.writestr("classes.dex", dex)
            apk.writestr("AndroidManifest.xml", b"manifest")
            apk.writestr("META-INF/CERT.SF", signer)
            apk.writestr("META-INF/CERT.RSA", signer)
            apk.writestr("META-INF/MANIFEST.MF", signer)
            apk.writestr("META-INF/services/not-a-signature.RSA", b"retained service")

    def save(self):
        self.manifest.write_text(json.dumps(self.document))

    def test_matching_record_is_accepted(self):
        self.assertEqual(self.document, baseline.validate_cache(self.apk, self.manifest, self.inputs))

    def test_every_build_input_is_bound(self):
        for key in self.inputs:
            changed = dict(self.inputs, **{key: "different"})
            with self.subTest(key=key), self.assertRaises(ValueError):
                baseline.validate_cache(self.apk, self.manifest, changed)

    def test_payload_equality_ignores_only_direct_signature_entries(self):
        before = baseline.payload_entries(self.apk)
        self.write_apk(signer=b"replacement")
        self.assertEqual(before, baseline.payload_entries(self.apk))
        self.assertIn("META-INF/services/not-a-signature.RSA", before)
        self.write_apk(dex=b"changed", signer=b"replacement")
        self.assertNotEqual(before, baseline.payload_entries(self.apk))

    def test_corrupted_apk_is_not_a_cache_miss(self):
        self.write_apk(dex=b"changed")
        with self.assertRaisesRegex(ValueError, "checksum"):
            baseline.validate_cache(self.apk, self.manifest, self.inputs)

    def test_forged_checksum_cannot_hide_payload_change(self):
        self.write_apk(dex=b"changed")
        self.document["apk_sha256"] = baseline.sha256(self.apk)
        self.save()
        with self.assertRaisesRegex(ValueError, "payload"):
            baseline.validate_cache(self.apk, self.manifest, self.inputs)

    def test_owner_key_and_invalid_certificate_are_rejected(self):
        for certificate in (baseline.OWNER_CERTIFICATE, "", "invalid"):
            self.document["certificate_sha256"] = certificate
            self.save()
            with self.subTest(certificate=certificate), self.assertRaises(ValueError):
                baseline.validate_cache(self.apk, self.manifest, self.inputs)

    def test_duplicate_zip_entries_are_rejected(self):
        with warnings.catch_warnings():
            warnings.simplefilter("ignore", UserWarning)
            with zipfile.ZipFile(self.apk, "a") as apk:
                apk.writestr("classes.dex", b"ambiguous")
        with self.assertRaisesRegex(ValueError, "Duplicate"):
            baseline.payload_entries(self.apk)

    def test_baseline_identities_are_frozen_and_distinct(self):
        self.assertEqual({49, 50, 54}, set(baseline.BASELINES))
        self.assertEqual("613360f61121390ec925d8229587a7dd95a354b2", baseline.expected_inputs(54)["source_sha"])
        self.assertNotEqual(baseline.expected_inputs(49), baseline.expected_inputs(50))
        with self.assertRaises(KeyError):
            baseline.expected_inputs(51)

    def prepare(self, *, corrupt_output=False):
        def sign(*args, **kwargs):
            self.assertEqual("sign", args[1])
            output = Path(args[args.index("--out") + 1])
            output.write_bytes(Path(args[-1]).read_bytes())
            if corrupt_output:
                with zipfile.ZipFile(output, "a") as apk:
                    apk.writestr("injected.txt", b"modified")
        with patch.object(baseline, "require_ci_signer", return_value="1" * 64), \
                patch.object(baseline, "certificate", return_value=("1" * 64, "test signature")), \
                patch.object(baseline, "inspect", return_value={"certificate_sha256": "1" * 64}), \
                patch.object(baseline, "run", side_effect=sign), \
                patch.dict("os.environ", {"ASTRAVAR_KEYSTORE": "ci-only-test-key"}):
            return baseline.prepare(49, self.root / "cache", self.root / "output", self.apk, self.root / "sdk")

    def seed_cache(self):
        cache = self.root / "cache"
        cache.mkdir()
        (cache / "code49.apk").write_bytes(self.apk.read_bytes())
        (cache / "code49.json").write_text(self.manifest.read_text())

    def test_warm_cache_reuses_payload_without_a_historical_build(self):
        self.seed_cache()
        with patch.object(baseline, "build_baseline", side_effect=AssertionError("Unexpected build")):
            proof = self.prepare()
        self.assertTrue(proof["cache_hit"])
        self.assertFalse(proof["owner_compatible"])
        self.assertEqual("1" * 64, proof["certificate_sha256"])
        self.assertEqual(self.inputs["source_sha"], (self.root / "output/SOURCE_COMMIT.txt").read_text().strip())

    def test_cold_cache_builds_once_and_saves_only_verified_apk_and_manifest(self):
        def build(inputs, output):
            self.assertEqual(self.inputs, inputs)
            output.write_bytes(self.apk.read_bytes())
        with patch.object(baseline, "build_baseline", side_effect=build) as build_mock:
            proof = self.prepare()
        self.assertFalse(proof["cache_hit"])
        build_mock.assert_called_once()
        self.assertEqual({"code49.apk", "code49.json"}, {p.name for p in (self.root / "cache").iterdir()})

    def test_partial_cache_does_not_fall_back_to_a_build(self):
        self.seed_cache()
        (self.root / "cache/code49.json").unlink()
        with patch.object(baseline, "build_baseline", side_effect=AssertionError("Unexpected build")), \
                self.assertRaisesRegex(ValueError, "Incomplete"):
            self.prepare()

    def test_resigning_cannot_change_product_payload(self):
        self.seed_cache()
        with self.assertRaisesRegex(ValueError, "non-signature"):
            self.prepare(corrupt_output=True)

    def test_failed_build_does_not_seed_cache(self):
        with patch.object(baseline, "build_baseline", side_effect=RuntimeError("build failed")), \
                self.assertRaisesRegex(RuntimeError, "build failed"):
            self.prepare()
        self.assertEqual([], list((self.root / "cache").iterdir()))


if __name__ == "__main__":
    unittest.main()
