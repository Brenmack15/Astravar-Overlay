"""Fail-closed acceptance checks for immutable-product native harness reuse."""
import hashlib
import copy
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
                     "overlay-target/src/androidTest/java/com/yazsras/astravar/OverlayInstrumentationTest.kt",
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
        for name in native.ALLOWED_FILES:
            path = repo / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text("new harness support\n")
        self.commit(repo)
        result = native.source_guard(repo, base, tree)
        self.assertEqual(result["reused_product_source_commit"], base)
        self.assertEqual(result["test_source_commit"], self.git(repo, "rev-parse", "HEAD"))
        self.assertEqual(len(result["changed_paths"]), len(native.ALLOWED_FILES))

    def test_only_exact_reviewed_fixtures_and_runner_are_allowed(self):
        repo, base, tree = self.repository()
        fixture = "overlay-target/src/androidTest/java/com/yazsras/astravar/OverlayInstrumentationTest.kt"
        (repo / fixture).write_text("bounded font configuration observation\n")
        self.commit(repo)
        result = native.source_guard(repo, base, tree)
        self.assertEqual(result["changed_paths"], [fixture])
        self.assertEqual(result["reused_product_source_commit"], base)
        self.assertEqual(result["reused_product_source_tree"], tree)

        forbidden = [
            "overlay-target/src/main/java/com/yazsras/astravar/target/TargetActivity.kt",
            "overlay-target/src/main/java/com/yazsras/astravar/target/OtherActivity.kt",
            "overlay-target/src/androidTest/OtherTest.kt",
            "overlay-target/src/androidTest/java/com/yazsras/astravar/CampaignImportInstrumentationTest.kt",
            fixture + ".bak",
            "overlay-target/src/main/AndroidManifest.xml",
            "overlay-target/src/main/res/layout/target.xml",
            "overlay-target/build.gradle.kts",
            "app/src/main/Product.kt",
            "app/src/main/res/icon.xml",
            "app/build.gradle.kts",
            "rules/src/Rules.kt",
            "scripts/ci-player-first-emulator-other.sh",
        ]
        for name in forbidden:
            with self.subTest(forbidden_path=name):
                self.git(repo, "reset", "--hard", base)
                # An allowed fixture repair must never mask a second source change.
                (repo / fixture).write_text("bounded font configuration observation\n")
                path = repo / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text("unapproved adjacent source or product change\n")
                self.commit(repo)
                with self.assertRaisesRegex(ValueError, "full verification"):
                    native.source_guard(repo, base, tree)

    def test_product_rules_resources_build_and_unapproved_runner_cannot_be_reused(self):
        repo, base, tree = self.repository()
        for name in ["app/src/main/Product.kt", "rules/src/Rules.kt", "app/src/main/res/icon.xml",
                     "app/build.gradle.kts", "scripts/unapproved-native-runner.sh",
                     "overlay-target/src/main/Target.kt", "docs/unreviewed.md"]:
            with self.subTest(path=name):
                self.git(repo, "reset", "--hard", base)
                path = repo / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text("changed product or unrelated file\n")
                self.commit(repo)
                with self.assertRaisesRegex(ValueError, "full verification"):
                    native.source_guard(repo, base, tree)

    def test_native_runner_preserves_gates_and_exposes_import_before_postupdate_overlay(self):
        script = (MODULE.parent / "ci-player-first-emulator.sh").read_text()
        calls = [
            "run_class OverlayInstrumentationTest 3 validation/native-overlay.txt",
            "run_class UpdateFixtureInstrumentationTest 1 validation/native-update-fixture.txt",
            "run_class UpdatePreservationInstrumentationTest 1 validation/native-update-readback.txt",
            "run_class CampaignImportInstrumentationTest#aDocumentPickerPreviewConfirmationAndLibrary 1 validation/native-campaign-import.txt",
            "run_class CampaignImportInstrumentationTest#bGuardedRollbackRestoresVerifiedSnapshot 1 validation/native-campaign-rollback.txt",
            "run_class OverlayInstrumentationTest#bNotificationStop 1 validation/native-updated-notification-stop.txt",
            "run_class OverlayInstrumentationTest#cRollRestorationMainHistoryAndLivePermissionRecovery 1 validation/native-updated-overlay-recovery.txt",
        ]
        positions = []
        for call in calls:
            self.assertEqual(script.count(call), 1, call)
            positions.append(script.index(call))
        self.assertEqual(positions, sorted(positions))
        self.assertGreater(script.index("scripts/history-preservation-fixture.py verify"), positions[-1])
        self.assertIn('ASTRAVAR_NATIVE_CLASS_TIMEOUT=720 ' + calls[3], script)
        self.assertIn('grep -q "^OK ($astravar_count test"', script)
        self.assertEqual(len(native.ALLOWED_FILES), 7)
        self.assertIn("scripts/ci-player-first-emulator.sh", native.ALLOWED_FILES)
        self.assertIn("app/src/test/java/com/yazsras/astravar/ui/RebuildVisualAcceptanceTest.kt", native.ALLOWED_FILES)

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
        path = repo / "overlay-target/src/androidTest/java/com/yazsras/astravar/OverlayInstrumentationTest.kt"
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

    def source_run(self):
        return {"id": native.RUN_ID, "head_sha": native.BASE_COMMIT, "status": "completed",
                "conclusion": "failure", "run_attempt": 1,
                "path": ".github/workflows/android-2.1-migration-native.yml",
                "head_branch": "feat/astravar-2.1-player-first"}

    def source_jobs(self):
        return {"jobs": [{"id": native.SOURCE_JOB_ID, "run_id": native.RUN_ID,
                          "head_sha": native.BASE_COMMIT, "name": "migration-native",
                          "status": "completed", "conclusion": "failure", "steps": [
                              {"name": "Verify reviewed memory fix and exact fresh source", "status": "completed", "conclusion": "success"},
                              {"name": native.SOURCE_BUILD_STEP, "status": "completed", "conclusion": "success"},
                              {"name": native.SOURCE_NATIVE_STEP, "status": "completed", "conclusion": "failure"},
                              {"name": "Upload artifact", "status": "completed", "conclusion": "success"}]}]}

    def test_completed_source_run_with_successful_product_build_is_required(self):
        result = native.verify_source_run(self.source_run(), self.source_jobs())
        self.assertEqual(result["product_build_step_conclusion"], "success")
        self.assertEqual(result["source_run_conclusion"], "failure")
        for key, value in [("id", 1), ("head_sha", "wrong"), ("status", "in_progress"),
                           ("conclusion", "success"), ("run_attempt", 2), ("path", "other.yml"),
                           ("head_branch", "main")]:
            with self.subTest(run_field=key), self.assertRaisesRegex(ValueError, "source run"):
                native.verify_source_run(dict(self.source_run(), **{key: value}), self.source_jobs())
        for key, value in [("id", 1), ("run_id", 1), ("head_sha", "wrong"), ("name", "verify"),
                           ("status", "in_progress"), ("conclusion", "cancelled")]:
            jobs = self.source_jobs()
            jobs["jobs"][0][key] = value
            with self.subTest(job_field=key), self.assertRaisesRegex(ValueError, "source job"):
                native.verify_source_run(self.source_run(), jobs)

    def test_failed_incomplete_missing_duplicate_build_or_other_failed_step_is_rejected(self):
        valid = self.source_jobs()
        mutations = []
        for status, conclusion in [("completed", "failure"), ("in_progress", None)]:
            jobs = copy.deepcopy(valid)
            jobs["jobs"][0]["steps"][1].update(status=status, conclusion=conclusion)
            mutations.append(jobs)
        for action in ["remove", "duplicate", "extra_failure", "pending", "no_native_failure", "extra_job"]:
            jobs = copy.deepcopy(valid)
            steps = jobs["jobs"][0]["steps"]
            if action == "remove":steps.pop(1)
            elif action == "duplicate":steps.append(dict(steps[1]))
            elif action == "extra_failure":steps[0]["conclusion"] = "failure"
            elif action == "pending":steps[3].update(status="pending", conclusion=None)
            elif action == "no_native_failure":steps[2]["conclusion"] = "success"
            else:jobs["jobs"].append(copy.deepcopy(jobs["jobs"][0]))
            mutations.append(jobs)
        for index, jobs in enumerate(mutations):
            with self.subTest(case=index), self.assertRaises(ValueError):
                native.verify_source_run(self.source_run(), jobs)
    def archive(self, *, source=None, extra=None, tree=None):
        archive = self.root / "synthetic.zip"
        payloads = {name: ("fictional " + name).encode() for name in native.APK_HASHES}
        payloads["mapping.txt"] = b"fictional R8 mapping"
        with zipfile.ZipFile(archive, "w") as z:
            z.writestr("validation/SOURCE_COMMIT.txt", source or native.BASE_COMMIT)
            z.writestr("validation/fresh-native-source-proof.json", json.dumps({
                "sourceCommit": native.BASE_COMMIT, "sourceTree": tree or native.BASE_TREE,
                "ownerSigningUsed": False, "realCampaignDataUsed": False}))
            for name, data in payloads.items():
                z.writestr(native.MAPPING_ARTIFACT_PATH if name == "mapping.txt" else "validation/" + name, data)
            if extra:
                z.writestr(*extra)
        return archive, payloads

    def verify_synthetic_archive(self, archive, payloads):
        with patch.object(native, "ARTIFACT_BYTES", archive.stat().st_size), patch.object(native, "ARTIFACT_SHA256", hashlib.sha256(archive.read_bytes()).hexdigest()), patch.dict(native.APK_HASHES, {n: hashlib.sha256(p).hexdigest() for n, p in payloads.items() if n.endswith(".apk")}, clear=True), patch.object(native, "MAPPING_SHA256", hashlib.sha256(payloads["mapping.txt"]).hexdigest()):
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
        wrong = dict(payloads, **{"mapping.txt": b"different R8 mapping"})
        with self.assertRaisesRegex(ValueError, "R8 mapping checksum"):
            self.verify_synthetic_archive(archive, wrong)
        archive, payloads = self.archive(source="wrong source")
        with self.assertRaisesRegex(ValueError, "SOURCE_COMMIT"):
            self.verify_synthetic_archive(archive, payloads)
        archive, payloads = self.archive(tree="wrong tree")
        with self.assertRaisesRegex(ValueError, "source tree"):
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

    def test_current_code31_and_exact_new_artifact_name_are_required(self):
        info = {"package": "com.yazsras.astravar", "version_code": 31, "version_name": "2.1.0",
                "label": "Astravar", "certificate_sha256": native.CI_CERTIFICATE, "debuggable": False,
                "owner_signature_verified": False, "launcher_artwork_bytes_verified": True,
                "alignment_16kb_verified": True, "apk_sha256": native.APK_HASHES["Astravar-2.1-CI-ONLY-code31.apk"]}
        native.verify_product_inspection(info, 31)
        with self.assertRaises(ValueError):
            native.verify_product_inspection(dict(info, version_code=30), 31)
        with self.assertRaises(ValueError):
            native.verify_artifact_metadata(dict(self.metadata(), name="Astravar-2.1-verification"))


if __name__ == "__main__":
    unittest.main()
