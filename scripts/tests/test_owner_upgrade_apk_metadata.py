"""Public AGP-shaped metadata, with no APK bytes, keys, network or device access."""
import copy
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).parents[1]))
import owner_upgrade_envelope as E
import owner_upgrade_transfer as T
import owner_upgrade_publication as P
from test_owner_upgrade_envelope import fixture

TEST_BADGING = """package: name='com.yazsras.astravar.target.test' versionCode='' versionName='' platformBuildVersionName='16' platformBuildVersionCode='36' compileSdkVersion='36' compileSdkVersionCodename='16'
sdkVersion:'26'
targetSdkVersion:'36'
"""
TEST_MANIFEST = """N: android=http://schemas.android.com/apk/res/android
  E: manifest (line=2)
    A: package="com.yazsras.astravar.target.test" (Raw: "com.yazsras.astravar.target.test")
    E: uses-sdk (line=5)
      A: android:minSdkVersion(0x0101020c)=(type 0x10)0x1a
      A: android:targetSdkVersion(0x01010270)=(type 0x10)0x24
    E: instrumentation (line=9)
      A: android:name(0x01010003)="androidx.test.runner.AndroidJUnitRunner" (Raw: "androidx.test.runner.AndroidJUnitRunner")
      A: android:targetPackage(0x01010021)="com.yazsras.astravar.target" (Raw: "com.yazsras.astravar.target")
    E: application (line=13)
      A: android:debuggable(0x0101000f)=(type 0x12)0xffffffff
"""


class ApkMetadataTest(unittest.TestCase):
    def setUp(self):
        self.config, _ = fixture()
        self.expected = self.config["apks"]["observer-test"]
        self.signature = "Number of signers: 1\nSigner #1 certificate SHA-256 digest: " + self.expected["certificate_sha256"] + "\n"

    def inspect(self, badging=TEST_BADGING, manifest=TEST_MANIFEST, signature=None):
        return T.verify_apk_metadata(badging, signature or self.signature, self.expected, manifest)

    def test_agp_absent_test_version_has_explicit_null_receipt(self):
        receipt = self.inspect()
        self.assertIn("version_code", receipt)
        self.assertIsNone(receipt["version_code"])
        self.assertEqual(receipt["instrumentation_target"], E.TEST_TARGET)
        self.assertEqual(receipt["instrumentation_runner"], E.TEST_RUNNER)
        E.validate_config(self.config)

    def test_test_version_absence_is_not_a_wildcard(self):
        for version in ("0", "1", "49", "50", "51"):
            with self.subTest(version=version), self.assertRaises(ValueError):
                self.inspect(badging=TEST_BADGING.replace("versionCode=''", "versionCode='" + version + "'"))
        with self.assertRaises(ValueError): self.inspect(badging=TEST_BADGING.replace(" versionCode=''", ""))

    def test_wrong_instrumentation_target_or_runner_rejected(self):
        with self.assertRaisesRegex(ValueError, "target mismatch"):
            self.inspect(manifest=TEST_MANIFEST.replace('targetPackage(0x01010021)="com.yazsras.astravar.target"',
                                                       'targetPackage(0x01010021)="com.yazsras.astravar"'))
        with self.assertRaisesRegex(ValueError, "runner mismatch"):
            self.inspect(manifest=TEST_MANIFEST.replace("androidx.test.runner.AndroidJUnitRunner", "other.Runner"))

    def test_wrong_package_signer_multiple_signers_and_missing_manifest_rejected(self):
        with self.assertRaises(ValueError): self.inspect(badging=TEST_BADGING.replace(".target.test", ".test"))
        with self.assertRaises(ValueError): self.inspect(signature=self.signature.replace("d" * 64, "f" * 64))
        with self.assertRaises(ValueError): self.inspect(signature=self.signature.replace("signers: 1", "signers: 2"))
        with self.assertRaises(ValueError): self.inspect(manifest=None)

    def test_duplicate_instrumentation_or_target_cannot_hide_a_wrong_declaration(self):
        with self.assertRaises(ValueError): self.inspect(manifest=TEST_MANIFEST + "    E: instrumentation (line=20)\n")
        duplicate = '      A: android:targetPackage(0x01010021)="com.yazsras.astravar.target"\n'
        with self.assertRaises(ValueError):
            self.inspect(manifest=TEST_MANIFEST.replace("    E: application", duplicate + "    E: application"))

    def test_other_roles_keep_exact_numeric_versions(self):
        for role in ("baseline", "candidate", "observer"):
            expected = self.config["apks"][role]
            signature = "Number of signers: 1\nSigner #1 certificate SHA-256 digest: " + expected["certificate_sha256"] + "\n"
            valid = "package: name='" + expected["package"] + "' versionCode='" + str(expected["version_code"]) + "'\n"
            T.verify_apk_metadata(valid, signature, expected)
            for version in ("", "0", "999"):
                badging = valid.replace("versionCode='" + str(expected["version_code"]) + "'", "versionCode='" + version + "'")
                with self.subTest(role=role, version=version), self.assertRaises(ValueError):
                    T.verify_apk_metadata(badging, signature, expected)

    def test_config_cannot_relax_target_runner_version_or_pair_certificate(self):
        mutations = [("version_code", 1), ("version_code", 0), ("instrumentation_target", "com.yazsras.astravar"),
                     ("instrumentation_runner", "other.Runner"), ("certificate_sha256", "f" * 64)]
        for field, value in mutations:
            config = copy.deepcopy(self.config); config["apks"]["observer-test"][field] = value
            with self.subTest(field=field), self.assertRaises(ValueError): E.validate_config(config)
        config = copy.deepcopy(self.config); config["apks"]["observer"]["version_code"] = None
        with self.assertRaises(ValueError): E.validate_config(config)

    def marker_apks(self):
        result = {}
        for role, metadata in self.config["apks"].items():
            fields = ("sha256", "package", "version_code", "certificate_sha256")
            if role == "observer-test": fields += ("instrumentation_target", "instrumentation_runner")
            result[role] = {field: metadata[field] for field in fields}
        return result

    def test_native_preflight_requires_explicit_null_and_instrumentation_proof(self):
        # Execute only the first pure Python preflight, never the shell/emulator.
        script = (Path(__file__).parents[1] / "ci-owner-upgrade.sh").read_text()
        preflight = script.split("python3 - <<'PY'\n", 1)[1].split("\nPY\n", 1)[0]
        _, data = fixture()
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp).resolve(); private = root / "apks"; private.mkdir()
            for role, payload in data.items(): (private / (role + ".apk")).write_bytes(payload)
            marker = {"schema": 1, "validated": True, "candidate_source": self.config["candidate_source"],
                      "baseline_source": self.config["baseline_source"], "owner_certificate_sha256": E.OWNER_CERT,
                      "apks": self.marker_apks()}
            mutations = [None, ("version_code", 1), ("version_code", "MISSING"),
                         ("instrumentation_target", "com.yazsras.astravar"), ("instrumentation_runner", "other.Runner")]
            for index, mutation in enumerate(mutations):
                value = copy.deepcopy(marker)
                if mutation:
                    field, replacement = mutation
                    if replacement == "MISSING": value["apks"]["observer-test"].pop(field)
                    else: value["apks"]["observer-test"][field] = replacement
                (private / "apk-validation.json").write_text(json.dumps(value))
                env = dict(os.environ, RUNNER_TEMP=str(root), OWNER_UPGRADE_PRIVATE=str(private),
                           OWNER_UPGRADE_EVIDENCE=str(root / ("public" + str(index))))
                result = subprocess.run([sys.executable, "-c", preflight], env=env, capture_output=True, text=True, timeout=10)
                with self.subTest(mutation=mutation):
                    self.assertEqual(result.returncode == 0, mutation is None, result.stderr)

    def test_public_receipt_keeps_null_and_exact_instrumentation_metadata(self):
        expected = self.config["observer_artifact"]
        proof = {"format": "astravar-owner-upgrade-transport-v1", "validated": True,
                 "owner_signing_key_received": False, "native_installation_verified": False,
                 "candidate_payload_matches_source_artifact": True, "receiver": E.binding(self.config, 123, 2, "c" * 40),
                 "transfer_commit": "e" * 40, "apks": self.marker_apks(),
                 "source_artifact": {"id": expected["artifact_id"], "digest": expected["digest"],
                                     "run_id": expected["run_id"], "run_attempt": expected["run_attempt"]}}
        for field in ("candidate_source", "baseline_source", "baseline_equivalence", "owner_certificate_sha256", "gates"):
            proof[field] = self.config[field]
        P.check_transport(proof, self.config)
        for field, value in (("version_code", 1), ("instrumentation_target", "other.target"), ("instrumentation_runner", "other.Runner")):
            modified = copy.deepcopy(proof); modified["apks"]["observer-test"][field] = value
            with self.subTest(field=field), self.assertRaises(ValueError): P.check_transport(modified, self.config)


if __name__ == "__main__": unittest.main()
