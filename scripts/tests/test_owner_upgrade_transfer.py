"""No network, APKs, private owner material or CI runs: synthetic provenance tests."""
import copy
import importlib.util
import json
from pathlib import Path
import sys
import tempfile
import time
import unittest
import zipfile

sys.path.insert(0, str(Path(__file__).parents[1]))
import owner_upgrade_envelope as E
import owner_upgrade_transfer as T
import owner_upgrade_publication as P
from test_owner_upgrade_envelope import fixture


def run_info(active=False):
    return {"id": 123, "run_attempt": 2, "head_sha": "c" * 40, "path": E.WORKFLOW,
            "repository": {"full_name": E.REPOSITORY}, "head_repository": {"full_name": E.REPOSITORY},
            "status": "in_progress" if active else "completed", "conclusion": None if active else "success",
            "run_started_at": "2026-01-01T00:00:00Z", "updated_at": "2026-01-01T00:00:05Z"}


class ProvenanceTest(unittest.TestCase):
    def test_run_id_attempt_source_workflow_and_fork_binding(self):
        original = run_info()
        T.check_run(original, 123, 2, "c" * 40, E.WORKFLOW)
        for field, value in [("id", 124), ("run_attempt", 1), ("head_sha", "d" * 40),
                              ("path", ".github/workflows/other.yml"), ("conclusion", "failure"),
                              ("head_repository", {"full_name": "other/fork"})]:
            mutated = {**original, field: value}
            with self.subTest(field=field), self.assertRaises(ValueError):
                T.check_run(mutated, 123, 2, "c" * 40, E.WORKFLOW)

    def test_active_artifact_may_postdate_run_updated_at(self):
        run = run_info(active=True)
        artifact = {"id": 99, "name": "recipient", "expired": False, "digest": "sha256:" + "a" * 64,
                    "workflow_run": {"id": 123, "head_sha": "c" * 40},
                    "created_at": "2026-01-01T00:00:10Z", "size_in_bytes": 800}
        T.check_artifact(artifact, 99, "recipient", "a" * 64, run)
        run.update(status="completed", conclusion="success")
        with self.assertRaises(ValueError): T.check_artifact(artifact, 99, "recipient", "a" * 64, run)

    def test_artifact_rejects_stale_attempt_wrong_id_digest_source_and_expiry(self):
        run = run_info()
        artifact = {"id": 99, "name": "native", "expired": False, "digest": "sha256:" + "a" * 64,
                    "workflow_run": {"id": 123, "head_sha": "c" * 40},
                    "created_at": "2026-01-01T00:00:02Z", "size_in_bytes": 800}
        T.check_artifact(artifact, 99, "native", "a" * 64, run)
        for field, value in [("id", 100), ("digest", "sha256:" + "b" * 64), ("expired", True),
                             ("workflow_run", {"id": 122, "head_sha": "c" * 40}),
                             ("created_at", "2025-12-31T23:59:59Z")]:
            with self.subTest(field=field), self.assertRaises(ValueError):
                T.check_artifact({**artifact, field: value}, 99, "native", "a" * 64, run)

    def test_successful_workflow_with_skipped_job_or_step_is_not_a_gate(self):
        for workflow, steps in T.CRITICAL_STEPS.items():
            gate = {"run_id": 10, "run_attempt": 1, "source_sha": "c" * 40, "workflow_path": workflow}
            job = {"name": "verify", "run_id": 10, "head_sha": "c" * 40,
                   "status": "completed", "conclusion": "success", "steps": [
                       {"name": step, "status": "completed", "conclusion": "success"} for step in sorted(steps)]}
            T.check_jobs({"total_count": 1, "jobs": [job]}, gate)
            skipped = copy.deepcopy(job); skipped["conclusion"] = "skipped"
            with self.assertRaises(ValueError): T.check_jobs({"total_count": 1, "jobs": [skipped]}, gate)
            skipped = copy.deepcopy(job); skipped["steps"][0]["conclusion"] = "skipped"
            with self.assertRaises(ValueError): T.check_jobs({"total_count": 1, "jobs": [skipped]}, gate)

    def test_candidate_zip_comparison_preserves_source_and_all_non_signature_entries(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            for name, cert in [("ci.apk", b"CI"), ("owner.apk", b"OWNER")]:
                with zipfile.ZipFile(root / name, "w") as archive:
                    for path, data in {"AndroidManifest.xml": b"manifest", "classes.dex": b"dex",
                        "META-INF/version-control-info.textproto": b"source", "META-INF/CERT.RSA": cert}.items():
                        archive.writestr(path, data)
            self.assertEqual(T.zip_payload(root / "ci.apk"), T.zip_payload(root / "owner.apk"))
            with zipfile.ZipFile(root / "changed.apk", "w") as archive:
                archive.writestr("AndroidManifest.xml", b"manifest"); archive.writestr("classes.dex", b"different")
            self.assertNotEqual(T.zip_payload(root / "ci.apk"), T.zip_payload(root / "changed.apk"))

    def test_artifact_only_extracts_exact_fixed_paths_and_digests(self):
        config, data = fixture()
        config["observer_artifact"].update(reference_apk_sha256=E.digest(b"reference"), reference_apk_size=9)
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp); archive = root / "artifact.zip"; output = root / "output"; output.mkdir()
            with zipfile.ZipFile(archive, "w") as handle:
                for role, path in T.ARTIFACT_PATHS.items(): handle.writestr(path, b"reference" if role == "reference" else data[role])
                handle.writestr("../../escape.apk", b"untrusted other entry never extracted")
            T.extract_native_artifact(archive, output, config)
            self.assertEqual({p.name for p in output.iterdir()}, {"observer.apk", "observer-test.apk", "reference.apk"})
            self.assertFalse((root.parent / "escape.apk").exists())

    def test_publication_rejects_extra_apk_private_key_database_and_unfiltered_log(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp); config, _ = fixture()
            E.create_recipient(config, 123, 2, "c" * 40, root / "private", root / "public")
            public = root / "public"
            P.verify_publication(public)
            for name, contents in [("candidate.apk", b"PK\x03\x04"), ("private.pem", b"PRIVATE KEY"),
                                   ("records.db", b"SQLite format 3"), ("logcat.txt", b"unfiltered")]:
                path = public / name; path.write_bytes(contents)
                with self.subTest(name=name), self.assertRaises(ValueError): P.verify_publication(public)
                path.unlink()
            (public / "native-evidence").mkdir()
            (public / "native-evidence/native-seedCode49.txt").write_text("INSTRUMENTATION_STATUS: stream=private output")
            with self.assertRaises(ValueError): P.verify_publication(public)

    def test_disabled_workflow_has_explicit_upload_allowlist_and_no_plaintext_artifact(self):
        workflow = (Path(__file__).parents[2] / ".github/workflows/owner-upgrade-private.yml").read_text()
        self.assertIn("persist-credentials: false", workflow)
        self.assertIn("ci: receive reviewed owner upgrade envelope", workflow)
        self.assertNotIn("secrets.", workflow)
        for block in workflow.split("uses: actions/upload-artifact@")[1:]:
            with_block = block.split("\n      - ")[0]
            self.assertNotIn("owner-upgrade-private", with_block)
            self.assertNotIn("*.apk", with_block)
            self.assertNotIn("**", with_block)
            self.assertNotIn("transport-private.pem", with_block)
        config = E.read_json(Path(__file__).parents[1] / "owner-upgrade-config.json")
        config["enabled"] = False
        with self.assertRaisesRegex(ValueError, "disabled"):
            E.validate_config(config)

    def test_recipient_requires_current_attempt_uploaded_job_and_exact_archive_digest(self):
        config, _ = fixture(); context = E.binding(config, 123, 2, "c" * 40)
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp); now = int(time.time())
            E.create_recipient(config, 123, 2, "c" * 40, root / "private", root / "public", now=now - 5)
            archive = root / "recipient.zip"
            with zipfile.ZipFile(archive, "w") as output:
                output.write(root / "public/recipient.json", "recipient.json")
            sha = E.digest(archive.read_bytes())
            from datetime import datetime, timezone
            stamp = lambda epoch: datetime.fromtimestamp(epoch, timezone.utc).isoformat()
            run = run_info(active=True); run.update(run_started_at=stamp(now-10), updated_at=stamp(now-7))
            artifact = {"id": 99, "name": "owner-upgrade-recipient-123-2", "digest": "sha256:"+sha,
                        "expired": False, "size_in_bytes": archive.stat().st_size, "created_at": stamp(now-3),
                        "workflow_run": {"id": 123, "head_sha": "c"*40}}
            jobs = {"total_count": 1, "jobs": [{"name": "receive-and-verify", "run_id": 123,
                "head_sha": "c"*40, "status": "in_progress", "steps": [{"name": name,
                "status": "completed", "conclusion": "success"} for name in (
                    "Verify live source gates and create ephemeral transport recipient",
                    "Publish only the public recipient descriptor")]}]}
            T.recipient_from_archive(archive, sha, artifact, run, context, 99, run, jobs)
            with self.assertRaises(ValueError):
                T.recipient_from_archive(archive, "f"*64, artifact, run, context, 99, run, jobs)
            with self.assertRaises(ValueError):
                T.recipient_from_archive(archive, sha, artifact, run, context, 100, run, jobs)
            attempt = {**run, "run_attempt": 1}
            with self.assertRaises(ValueError):
                T.recipient_from_archive(archive, sha, artifact, run, context, 99, attempt, jobs)
            jobs["jobs"][0]["steps"][1]["conclusion"] = "skipped"
            with self.assertRaises(ValueError):
                T.recipient_from_archive(archive, sha, artifact, run, context, 99, run, jobs)

    def test_publication_rejects_nested_unknown_data(self):
        proof = {"format": "astravar-owner-upgrade-native-v1", "passed": False,
                 "synthetic_emulator_only": True, "physical_device_verified": False,
                 "cleanup_complete": False, "exit_code": 1, "last_stage": "preflight",
                 "screenshots": {"genesis-before-code49-sheet.png": "secret data"}}
        with self.assertRaises(ValueError): P.check_native(proof)
        proof.pop("screenshots")
        proof["package_retention"] = {"secret": "private material"}
        with self.assertRaises(ValueError): P.check_native(proof)


if __name__ == "__main__": unittest.main()
