"""Fail-closed acceptance checks for immutable-product native harness reuse."""
import hashlib
import importlib.util
import json
from pathlib import Path
import stat
import subprocess
import tempfile
import unittest
from unittest.mock import patch
import zipfile

MODULE = Path(__file__).resolve().parents[1] / "native_recheck.py"
SPEC = importlib.util.spec_from_file_location("native_recheck", MODULE)
native = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(native)


class NativeRecheckTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)

    def git(self, repo, *args):
        return subprocess.check_output(["git", "-C", str(repo), *args], stderr=subprocess.STDOUT).decode().strip()

    def repository(self):
        repo = self.root / "repo"
        repo.mkdir()
        self.git(repo, "init", "--quiet")
        self.git(repo, "config", "user.name", "Synthetic fixture")
        self.git(repo, "config", "user.email", "fictional@example.invalid")
        for name in ["app/src/main/Product.kt", "rules/src/Rules.kt", "app/src/main/res/icon.xml",
                     "app/build.gradle.kts", "scripts/ci-player-first-emulator.sh",
                     "overlay-target/src/androidTest/NativeTest.kt",
                     "overlay-target/src/main/java/com/yazsras/astravar/target/TargetActivity.kt"]:
            path = repo / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text("original\n")
        self.git(repo, "add", ".")
        self.git(repo, "commit", "-qm", "synthetic baseline")
        return repo, self.git(repo, "rev-parse", "HEAD"), self.git(repo, "rev-parse", "HEAD^{tree}")

    def commit(self, repo):
        self.git(repo, "add", ".")
        self.git(repo, "commit", "-qm", "synthetic harness repair")

    def test_committed_instrumentation_and_exact_recheck_files_are_allowed(self):
        repo, base, tree = self.repository()
        (repo / "overlay-target/src/androidTest/NativeTest.kt").write_text("new test\n")
        for name in native.ALLOWED_FILES:
            path = repo / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text("new harness support\n")
        self.commit(repo)
        result = native.source_guard(repo, base, tree)
        self.assertEqual(result["reused_product_source_commit"], base)
        self.assertEqual(result["test_source_commit"], self.git(repo, "rev-parse", "HEAD"))
        self.assertEqual(len(result["changed_paths"]), len(native.ALLOWED_FILES) + 1)

    def test_only_exact_controlled_activity_fixture_is_allowed_among_main_sources(self):
        repo, base, tree = self.repository()
        fixture = "overlay-target/src/main/java/com/yazsras/astravar/target/TargetActivity.kt"
        (repo / fixture).write_text("controlled second-app IME insets repair\n")
        self.commit(repo)
        result = native.source_guard(repo, base, tree)
        self.assertEqual(result["changed_paths"], [fixture])
        self.assertEqual(result["reused_product_source_commit"], base)
        self.assertEqual(result["reused_product_source_tree"], tree)

        forbidden = [
            "overlay-target/src/main/java/com/yazsras/astravar/target/OtherActivity.kt",
            fixture + ".bak",
            "overlay-target/src/main/AndroidManifest.xml",
            "overlay-target/src/main/res/layout/target.xml",
            "overlay-target/build.gradle.kts",
            "app/src/main/Product.kt",
            "app/src/main/res/icon.xml",
            "app/build.gradle.kts",
            "rules/src/Rules.kt",
            "scripts/ci-player-first-emulator.sh",
        ]
        for name in forbidden:
            with self.subTest(forbidden_path=name):
                self.git(repo, "reset", "--hard", base)
                # An allowed fixture repair must never mask a second source change.
                (repo / fixture).write_text("controlled second-app IME insets repair\n")
                path = repo / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text("unapproved adjacent source or product change\n")
                self.commit(repo)
                with self.assertRaisesRegex(ValueError, "full verification"):
                    native.source_guard(repo, base, tree)

    def test_product_rules_resources_build_and_unchanged_runner_cannot_be_reused(self):
        repo, base, tree = self.repository()
        for name in ["app/src/main/Product.kt", "rules/src/Rules.kt", "app/src/main/res/icon.xml",
                     "app/build.gradle.kts", "scripts/ci-player-first-emulator.sh",
                     "overlay-target/src/main/Target.kt", "docs/unreviewed.md"]:
            with self.subTest(path=name):
                self.git(repo, "reset", "--hard", base)
                path = repo / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text("changed product or unrelated file\n")
                self.commit(repo)
                with self.assertRaisesRegex(ValueError, "full verification"):
                    native.source_guard(repo, base, tree)

    def test_product_deletion_and_mode_change_are_rejected(self):
        repo, base, tree = self.repository()
        product = repo / "app/src/main/Product.kt"
        product.unlink()
        self.commit(repo)
        with self.assertRaisesRegex(ValueError, "full verification"):
            native.source_guard(repo, base, tree)
        self.git(repo, "reset", "--hard", base)
        product.chmod(0o755)
        self.commit(repo)
        with self.assertRaisesRegex(ValueError, "full verification"):
            native.source_guard(repo, base, tree)

    def test_dirty_tracked_and_untracked_sources_are_rejected(self):
        repo, base, tree = self.repository()
        path = repo / "overlay-target/src/androidTest/NativeTest.kt"
        path.write_text("uncommitted\n")
        with self.assertRaisesRegex(ValueError, "clean committed"):
            native.source_guard(repo, base, tree)
        self.git(repo, "reset", "--hard", base)
        (repo / "untracked-source.kt").write_text("untracked\n")
        with self.assertRaisesRegex(ValueError, "clean committed"):
            native.source_guard(repo, base, tree)

    def test_baseline_tree_mismatch_and_unrelated_history_are_rejected(self):
        repo, base, tree = self.repository()
        with self.assertRaisesRegex(ValueError, "Baseline tree"):
            native.source_guard(repo, base, "0" * 40)
        self.git(repo, "checkout", "--orphan", "unrelated")
        self.git(repo, "commit", "-qm", "unrelated synthetic history")
        with self.assertRaises(subprocess.CalledProcessError):
            native.source_guard(repo, base, tree)

    def metadata(self):
        return {"id": native.ARTIFACT_ID, "name": native.ARTIFACT_NAME, "expired": False,
                "size_in_bytes": native.ARTIFACT_BYTES, "digest": "sha256:" + native.ARTIFACT_SHA256,
                "workflow_run": {"id": native.RUN_ID, "head_sha": native.BASE_COMMIT}}

    def test_wrong_artifact_digest_run_source_and_expiry_are_rejected(self):
        native.verify_artifact_metadata(self.metadata())
        for key, value in [("id", 1), ("name", "different"), ("expired", True), ("size_in_bytes", 1), ("digest", "sha256:wrong")]:
            metadata = self.metadata()
            metadata[key] = value
            with self.subTest(field=key), self.assertRaises(ValueError):
                native.verify_artifact_metadata(metadata)
        for key, value in [("id", 1), ("head_sha", "wrong-source")]:
            metadata = self.metadata()
            metadata["workflow_run"][key] = value
            with self.subTest(field=key), self.assertRaises(ValueError):
                native.verify_artifact_metadata(metadata)

    def archive(self, *, source=None, extra=None):
        archive = self.root / "synthetic.zip"
        payloads = {name: ("fictional " + name).encode() for name in native.APK_HASHES}
        with zipfile.ZipFile(archive, "w") as z:
            z.writestr("validation/SOURCE_COMMIT.txt", source or native.BASE_COMMIT)
            for name, data in payloads.items():
                z.writestr("validation/" + name, data)
            if extra:
                z.writestr(*extra)
        return archive, payloads

    def verify_synthetic_archive(self, archive, payloads):
        with patch.object(native, "ARTIFACT_BYTES", archive.stat().st_size), patch.object(native, "ARTIFACT_SHA256", hashlib.sha256(archive.read_bytes()).hexdigest()), patch.dict(native.APK_HASHES, {n: hashlib.sha256(p).hexdigest() for n, p in payloads.items()}, clear=True):
            return native.verified_archive_payloads(archive)

    def test_zip_digest_payload_hash_and_source_are_independently_required(self):
        archive, payloads = self.archive()
        self.assertEqual(self.verify_synthetic_archive(archive, payloads), payloads)
        with self.assertRaisesRegex(ValueError, "ZIP checksum"):
            native.verified_archive_payloads(archive)
        wrong = dict(payloads)
        name = next(iter(wrong))
        wrong[name] = b"different content"
        with self.assertRaisesRegex(ValueError, "APK checksum"):
            self.verify_synthetic_archive(archive, wrong)
        archive, payloads = self.archive(source="wrong source")
        with self.assertRaisesRegex(ValueError, "SOURCE_COMMIT"):
            self.verify_synthetic_archive(archive, payloads)

    def test_zip_traversal_duplicate_and_symlink_are_rejected(self):
        link = zipfile.ZipInfo("validation/link")
        link.create_system = 3
        link.external_attr = (stat.S_IFLNK | 0o777) << 16
        for extra in [("../escape", "no"), ("/absolute", "no"), ("validation\\escape", "no"),
                      ("validation/SOURCE_COMMIT.txt", native.BASE_COMMIT), (link, "../../escape")]:
            with self.subTest(entry=str(extra[0])):
                archive, payloads = self.archive(extra=extra)
                with self.assertRaisesRegex(ValueError, "Unsafe or duplicate"):
                    self.verify_synthetic_archive(archive, payloads)

    def test_actual_apk_inspection_cannot_accept_other_identity_or_signer(self):
        original = {"package": "com.yazsras.astravar", "version_code": 24, "version_name": "2.1.0",
                    "label": "Astravar", "certificate_sha256": native.CI_CERTIFICATE, "debuggable": False,
                    "owner_signature_verified": False, "launcher_artwork_bytes_verified": True,
                    "alignment_16kb_verified": True, "apk_sha256": native.APK_HASHES["Astravar-2.1-CI-ONLY-code24.apk"]}
        native.verify_product_inspection(original, 24)
        for key, value in [("package", "com.example"), ("version_code", 25), ("version_name", "wrong"),
                           ("label", "Astravar test"), ("certificate_sha256", "wrong"), ("debuggable", True),
                           ("owner_signature_verified", True), ("launcher_artwork_bytes_verified", False),
                           ("alignment_16kb_verified", False), ("apk_sha256", "wrong")]:
            info = dict(original, **{key: value})
            with self.subTest(field=key), self.assertRaises(ValueError):
                native.verify_product_inspection(info, 24)

    def test_current_code26_and_exact_new_artifact_name_are_required(self):
        info = {"package": "com.yazsras.astravar", "version_code": 26, "version_name": "2.1.0",
                "label": "Astravar", "certificate_sha256": native.CI_CERTIFICATE, "debuggable": False,
                "owner_signature_verified": False, "launcher_artwork_bytes_verified": True,
                "alignment_16kb_verified": True, "apk_sha256": native.APK_HASHES["Astravar-2.1-CI-ONLY-code26.apk"]}
        native.verify_product_inspection(info, 26)
        with self.assertRaises(ValueError):
            native.verify_product_inspection(dict(info, version_code=25), 26)
        with self.assertRaises(ValueError):
            native.verify_artifact_metadata(dict(self.metadata(), name="Astravar-2.1-verification"))


if __name__ == "__main__":
    unittest.main()
