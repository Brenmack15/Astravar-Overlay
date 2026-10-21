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
        self.assertEqual(len(native.ALLOWED_FILES), 8)
        self.assertIn("scripts/ci-player-first-emulator.sh", native.ALLOWED_FILES)
        self.assertIn("app/src/test/java/com/yazsras/astravar/ui/RebuildVisualAcceptanceTest.kt", native.ALLOWED_FILES)
        self.assertIn("overlay-target/src/androidTest/java/com/yazsras/astravar/UpdatePreservationInstrumentationTest.kt", native.ALLOWED_FILES)

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


class NativeRecheckCode32Test(unittest.TestCase):
    git = NativeRecheckTest.git
    repository = NativeRecheckTest.repository
    commit = NativeRecheckTest.commit
    metadata = NativeRecheckTest.metadata
    source_run = NativeRecheckTest.source_run
    source_jobs = NativeRecheckTest.source_jobs
    archive = NativeRecheckTest.archive
    verify_synthetic_archive = NativeRecheckTest.verify_synthetic_archive

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        native.configure_profile("code32")
        self.addCleanup(native.configure_profile, "code31")

    def reviewed_files(self, repo, omit=None):
        for name in native.ALLOWED_FILES - {omit}:
            path = repo / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text("reviewed fictional harness repair\n")
        self.commit(repo)

    def test_only_named_pinned_profiles_can_be_selected_and_legacy_is_preserved(self):
        self.assertEqual(native.VERSION_CODE, 32)
        self.assertEqual(native.BASE_COMMIT, "80e5dd2dc6645338ece59a7bb2c11cba0a3f7c06")
        self.assertEqual(native.BASE_TREE, "f957c7ac94f68d611cec02eaa85a3d8ed6456661")
        self.assertTrue(native.STRICT_CHANGED_FILES)
        self.assertEqual(len(native.ALLOWED_FILES), 6)
        native.configure_profile("code31")
        self.assertEqual(native.VERSION_CODE, 31)
        self.assertEqual(native.BASE_COMMIT, "55a6b2db8065152606a421ca6e3f7a5b46da55c1")
        self.assertEqual(native.RUN_ID, 39254633105)
        self.assertFalse(native.STRICT_CHANGED_FILES)
        self.assertEqual(len(native.ALLOWED_FILES), 8)
        with self.assertRaisesRegex(ValueError, "Only the pinned"):
            native.configure_profile("unreviewed")

    def test_exact_six_committed_files_are_required_and_product_changes_fail(self):
        repo, base, tree = self.repository()
        self.reviewed_files(repo)
        proof = native.source_guard(repo, base, tree)
        self.assertEqual(set(proof["changed_paths"]), native.ALLOWED_FILES)
        self.assertEqual(proof["reused_product_version_code"], 32)
        self.assertEqual(proof["profile"], "code32")
        self.git(repo, "reset", "--hard", base)
        self.reviewed_files(repo, omit="docs/ASTRAVAR_2_1_NATIVE_RECHECK_CODE32.md")
        with self.assertRaisesRegex(ValueError, "exact reviewed"):
            native.source_guard(repo, base, tree)
        self.git(repo, "reset", "--hard", base)
        self.reviewed_files(repo)
        (repo / "app/src/main/Product.kt").write_text("unreviewed product change\n")
        self.commit(repo)
        with self.assertRaisesRegex(ValueError, "full verification"):
            native.source_guard(repo, base, tree)

    def test_profile32_requires_its_completed_successful_build_and_artifact(self):
        native.verify_artifact_metadata(self.metadata())
        proof = native.verify_source_run(self.source_run(), self.source_jobs())
        self.assertEqual(proof["source_run_id"], 39270984985)
        self.assertEqual(proof["product_build_step_conclusion"], "success")
        self.assertIn("offscreen status", proof["limitation"])
        wrong = self.metadata()
        wrong["id"] = native.CODE31_PROFILE["ARTIFACT_ID"]
        with self.assertRaises(ValueError): native.verify_artifact_metadata(wrong)
        jobs = self.source_jobs()
        jobs["jobs"][0]["steps"][1]["conclusion"] = "failure"
        with self.assertRaisesRegex(ValueError, "product build"):
            native.verify_source_run(self.source_run(), jobs)

    def test_profile32_archive_payload_and_mapping_are_independently_checked(self):
        archive, payloads = self.archive()
        self.assertIn("Astravar-2.1-CI-ONLY-code32.apk", payloads)
        self.assertNotIn("Astravar-2.1-CI-ONLY-code31.apk", payloads)
        self.assertEqual(self.verify_synthetic_archive(archive, payloads), payloads)
        changed = dict(payloads, **{"mapping.txt": b"unreviewed map"})
        with self.assertRaisesRegex(ValueError, "R8 mapping checksum"):
            self.verify_synthetic_archive(archive, changed)

    def test_profile32_actual_inspection_requires_32_and_its_one_test_certificate(self):
        info = {"package": "com.yazsras.astravar", "version_code": 32, "version_name": "2.1.0",
                "label": "Astravar", "certificate_sha256": native.CI_CERTIFICATE, "debuggable": False,
                "owner_signature_verified": False, "launcher_artwork_bytes_verified": True,
                "alignment_16kb_verified": True, "apk_sha256": native.APK_HASHES["Astravar-2.1-CI-ONLY-code32.apk"]}
        native.verify_product_inspection(info, 32)
        with self.assertRaises(ValueError): native.verify_product_inspection(dict(info, version_code=31), 32)
        with self.assertRaises(ValueError): native.verify_product_inspection(dict(info, certificate_sha256=native.CODE31_PROFILE["CI_CERTIFICATE"]), 32)

    def test_exact_subject_skips_only_its_full_job_and_isolates_concurrency(self):
        import re
        root = MODULE.parents[1]
        workflow = (root / ".github/workflows/android-2.1-native-recheck32.yml").read_text()
        calls = re.findall(r"python3 scripts/native_recheck.py([^\n]*)", workflow)
        self.assertEqual(len(calls), 6)
        self.assertTrue(all(call.startswith(" --profile code32 ") for call in calls))
        self.assertIn("ci: run verified native code32 harness recheck", workflow)
        self.assertIn("source scripts/ci-player-first-emulator.sh", workflow)
        self.assertIn("CampaignImportInstrumentationTest#cMappedCampaignRecordsReachPlayerViews 1", workflow)
        self.assertIn("CampaignImportInstrumentationTest#dMappedCampaignRecordsRollbackAfterReadback 1", workflow)
        full = (root / ".github/workflows/android-2.1.yml").read_text()
        condition = re.search(r"    if: >-\n      (.+)\n", full).group(1)
        suffix = re.search(r"group: \"astravar-21-\$\{\{ github.ref_name \}\}\$\{\{ (.+) \}\}\"", full).group(1)
        def evaluate(expression, event, message):
            expression = expression.replace("github.event_name", "event").replace("github.event.head_commit.message", "message").replace("&&", " and ").replace("||", " or ")
            return eval(expression, {"__builtins__": {}}, dict(event=event, message=message))
        subject = "ci: run verified native code32 harness recheck"
        self.assertFalse(evaluate(condition, "push", subject))
        self.assertEqual(evaluate(suffix, "push", subject), "-native32-harness-only")
        for event, message in [("push", "ci: verify migration native interfaces"), ("push", "normal change"), ("workflow_dispatch", None)]:
            self.assertTrue(evaluate(condition, event, message))
            self.assertEqual(evaluate(suffix, event, message), "")


class NativeCode33ProfileTest(unittest.TestCase):
    git = NativeRecheckTest.git
    repository = NativeRecheckTest.repository
    commit = NativeRecheckTest.commit
    metadata = NativeRecheckTest.metadata
    source_run = NativeRecheckTest.source_run
    source_jobs = NativeRecheckTest.source_jobs
    archive = NativeRecheckTest.archive
    verify_synthetic_archive = NativeRecheckTest.verify_synthetic_archive
    reviewed_files = NativeRecheckCode32Test.reviewed_files

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        native.configure_profile("code33")
        self.addCleanup(native.configure_profile, "code31")

    def test_profile33_exact_pins_and_historical_profiles_remain_independent(self):
        self.assertEqual(native.VERSION_CODE, 33)
        self.assertEqual(native.BASE_COMMIT, "f35e18c83b07cdb5dd4f73925d110abd68812390")
        self.assertEqual(native.BASE_TREE, "337f10402b53b08e87c7748da8f2b103e3e280f7")
        self.assertEqual(native.RUN_ID, 39301833455)
        self.assertEqual(native.ARTIFACT_ID, 12212558254)
        self.assertEqual(native.ARTIFACT_BYTES, 48981256)
        self.assertEqual(native.ARTIFACT_SHA256, "f41d163eb7597b267a2109b25d0981c3c4fbaf98d4760ea575d444b24e16d8cf")
        self.assertTrue(native.STRICT_CHANGED_FILES)
        self.assertEqual(len(native.ALLOWED_FILES), 6)
        for name, code, run in [("code31", 31, 39254633105), ("code32", 32, 39270984985), ("code33", 33, 39301833455)]:
            native.configure_profile(name)
            self.assertEqual((native.VERSION_CODE, native.RUN_ID), (code, run))

    def test_profile33_requires_exact_six_committed_paths_not_a_subset(self):
        repo, base, tree = self.repository()
        self.reviewed_files(repo)
        proof = native.source_guard(repo, base, tree)
        self.assertEqual(set(proof["changed_paths"]), native.ALLOWED_FILES)
        self.assertEqual((proof["profile"], proof["reused_product_version_code"]), ("code33", 33))
        self.git(repo, "reset", "--hard", base)
        self.reviewed_files(repo, omit="docs/ASTRAVAR_2_1_NATIVE_RECHECK_CODE33.md")
        with self.assertRaisesRegex(ValueError, "exact reviewed"):
            native.source_guard(repo, base, tree)

    def test_profile33_rejects_all_production_adjacent_and_runner_changes(self):
        repo, base, tree = self.repository()
        for name in ["app/src/main/Product.kt", "app/src/main/res/icon.xml", "app/build.gradle.kts",
                     "app/src/main/java/com/yazsras/astravar/ui/CampaignPagesV2.kt", "rules/src/Rules.kt",
                     "app/schemas/unreviewed.json", "scripts/ci-player-first-emulator.sh",
                     "overlay-target/src/main/java/com/yazsras/astravar/target/TargetActivity.kt",
                     "overlay-target/src/androidTest/UnreviewedTest.kt"]:
            with self.subTest(path=name):
                self.git(repo, "reset", "--hard", base)
                self.reviewed_files(repo)
                path = repo / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text("unreviewed source change\n")
                self.commit(repo)
                with self.assertRaisesRegex(ValueError, "full verification"):
                    native.source_guard(repo, base, tree)
        self.git(repo, "reset", "--hard", base)
        self.reviewed_files(repo)
        (repo / "scripts/ci-player-first-emulator.sh").chmod(0o755)
        self.commit(repo)
        with self.assertRaisesRegex(ValueError, "full verification"):
            native.source_guard(repo, base, tree)

    def test_profile33_requires_completed_successful_source_build_and_exact_artifact(self):
        native.verify_artifact_metadata(self.metadata())
        result = native.verify_source_run(self.source_run(), self.source_jobs())
        self.assertEqual(result["source_job_id"], 118228773043)
        self.assertIn("companion-header", result["limitation"])
        for profile in [native.CODE31_PROFILE, native.CODE32_PROFILE]:
            wrong = self.metadata()
            wrong["id"] = profile["ARTIFACT_ID"]
            with self.assertRaises(ValueError): native.verify_artifact_metadata(wrong)
        jobs = self.source_jobs()
        jobs["jobs"][0]["steps"][1]["conclusion"] = "failure"
        with self.assertRaisesRegex(ValueError, "product build"):
            native.verify_source_run(self.source_run(), jobs)

    def test_profile33_archive_checks_both_apks_mapping_and_source(self):
        archive, payloads = self.archive()
        self.assertIn("Astravar-2.1-CI-ONLY-code33.apk", payloads)
        self.assertNotIn("Astravar-2.1-CI-ONLY-code32.apk", payloads)
        self.assertEqual(self.verify_synthetic_archive(archive, payloads), payloads)
        changed = dict(payloads, **{"mapping.txt": b"different map"})
        with self.assertRaisesRegex(ValueError, "R8 mapping checksum"):
            self.verify_synthetic_archive(archive, changed)
        changed = dict(payloads)
        changed["Astravar-2.1-CI-ONLY-code33.apk"] = b"different product"
        with self.assertRaisesRegex(ValueError, "APK checksum"):
            self.verify_synthetic_archive(archive, changed)

    def test_profile33_actual_inspection_requires_33_and_its_disposable_certificate(self):
        info = {"package": "com.yazsras.astravar", "version_code": 33, "version_name": "2.1.0",
                "label": "Astravar", "certificate_sha256": native.CI_CERTIFICATE, "debuggable": False,
                "owner_signature_verified": False, "launcher_artwork_bytes_verified": True,
                "alignment_16kb_verified": True, "apk_sha256": native.APK_HASHES["Astravar-2.1-CI-ONLY-code33.apk"]}
        native.verify_product_inspection(info, 33)
        for changes in [dict(version_code=32), dict(certificate_sha256=native.CODE32_PROFILE["CI_CERTIFICATE"]),
                        dict(owner_signature_verified=True), dict(package="other.package"), dict(debuggable=True)]:
            with self.subTest(changes=changes), self.assertRaises(ValueError):
                native.verify_product_inspection(dict(info, **changes), 33)

    def test_profile33_workflow_preserves_both_typed_checks_sdk_budget_and_full_concurrency(self):
        import re
        root = MODULE.parents[1]
        workflow = (root / ".github/workflows/android-2.1-native-recheck33.yml").read_text()
        calls = re.findall(r"python3 scripts/native_recheck.py([^\n]*)", workflow)
        self.assertEqual(len(calls), 6)
        self.assertTrue(all(call.startswith(" --profile code33 ") for call in calls))
        self.assertIn("ci: run verified native code33 harness recheck", workflow)
        self.assertIn("ASTRAVAR_VERSION_CODE: '33'", workflow)
        self.assertIn("CampaignImportInstrumentationTest#cMappedCampaignRecordsReachPlayerViews 1", workflow)
        self.assertIn("CampaignImportInstrumentationTest#dMappedCampaignRecordsRollbackAfterReadback 1", workflow)
        previous = (root / ".github/workflows/android-2.1-native-recheck32.yml").read_text()
        sdk = lambda text: text.split('          export ANDROID_HOME=', 1)[1].split('      - name: Verify immutable', 1)[0]
        self.assertEqual(sdk(workflow), sdk(previous))
        self.assertIn("timeout -k 10s 360s bash", workflow)
        full = (root / ".github/workflows/android-2.1.yml").read_text()
        condition = re.search(r"    if: >-\n      (.+)\n", full).group(1)
        suffix = re.search(r"group: \"astravar-21-\$\{\{ github.ref_name \}\}\$\{\{ (.+) \}\}\"", full).group(1)
        def evaluate(expression, event, message):
            expression = expression.replace("github.event_name", "event").replace("github.event.head_commit.message", "message").replace("&&", " and ").replace("||", " or ")
            return eval(expression, {"__builtins__": {}}, dict(event=event, message=message))
        for version in [32, 33]:
            subject = f"ci: run verified native code{version} harness recheck"
            self.assertFalse(evaluate(condition, "push", subject))
            self.assertEqual(evaluate(suffix, "push", subject), f"-native{version}-harness-only")
        for event, message in [("push", "ci: verify migration native interfaces"), ("push", "normal change"), ("workflow_dispatch", None)]:
            self.assertTrue(evaluate(condition, event, message))
            self.assertEqual(evaluate(suffix, event, message), "")


if __name__ == "__main__":
    unittest.main()
