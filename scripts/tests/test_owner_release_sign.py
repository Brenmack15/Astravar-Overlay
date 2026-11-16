"""Synthetic adversarial verification. Never opens the recovered owner material.

External signing/verification programs are mocked in pipeline tests. Git-only
fixtures exercise actual clean-HEAD behavior. No real signing or APK acceptance
is established by this suite.
"""
import argparse
import contextlib
import copy
import hashlib
import io
import json
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest
from unittest import mock
import warnings
import zipfile

import importlib.util
import sys

SCRIPT = Path(__file__).resolve().parents[1] / "owner_release_sign.py"
spec = importlib.util.spec_from_file_location("owner_release_sign", SCRIPT)
sign = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = sign
spec.loader.exec_module(sign)

COMMIT = "a" * 40
CI_CERT = "b" * 64
MARKER = f'repositories {{\n system: GIT\n revision: "{COMMIT}"\n}}\n'.encode()


def write_zip(path, entries):
    with zipfile.ZipFile(path, "w", compression=zipfile.ZIP_DEFLATED) as archive:
        for name, value in entries.items():
            archive.writestr(name, value)


def payload():
    return {"AndroidManifest.xml": b"fictional manifest", "classes.dex": b"fictional dex",
            sign.PROVENANCE: MARKER, "META-INF/services/a.Service": b"a.Implementation\n",
            "META-INF/library.version": b"1.2.3", "META-INF/LICENSE.txt": b"Fictional license",
            "META-INF/RESOURCE.SF": b"This is payload, not a signature",
            "META-INF/RESOURCE.RSA": b"Also payload", "assets/data.bin": b"\x00\xff\x01" * 4096}


def signature(cert=CI_CERT, v1=False, v2=True, v3=False):
    return (f"Verified using v1 scheme (JAR signing): {str(v1).lower()}\n"
            f"Verified using v2 scheme (APK Signature Scheme v2): {str(v2).lower()}\n"
            f"Verified using v3 scheme (APK Signature Scheme v3): {str(v3).lower()}\n"
            f"Signer #1 certificate SHA-256 digest: {cert}\n")


def evidence(artifact_sha="c" * 64):
    return {"schema_version": 1, "source_commit": COMMIT, "artifact_sha256": artifact_sha,
            "artifact": {"run_id": 101, "artifact_id": 1000, "sha256": artifact_sha},
            "runs": [{"kind": kind, "run_id": run_id, "head_sha": COMMIT,
                      "status": "completed", "conclusion": "success",
                      "html_url": f"https://github.com/{sign.REPOSITORY}/actions/runs/{run_id}"}
                     for kind, run_id in (("full", 100), ("native", 101))]}


def tiered_evidence(artifact_sha="c" * 64):
    run_id = 200
    url = f"https://github.com/{sign.REPOSITORY}/actions/runs/{run_id}"
    return {
        "schema_version": 2, "source_commit": COMMIT, "artifact_sha256": artifact_sha,
        "run": {"run_id": run_id, "run_attempt": 1, "head_sha": COMMIT, "status": "completed",
                "conclusion": "success", "html_url": url},
        "jobs": [{"kind": kind, "job_id": job_id, "run_id": run_id, "run_attempt": 1,
                  "head_sha": COMMIT, "name": name, "status": "completed", "conclusion": "success",
                  "html_url": f"{url}/job/{job_id}"}
                 for kind, job_id, name in (("full", 201, "Full regression gate / verify"),
                                           ("native", 202, "Historical upgrade and native acceptance / verify"))],
        "artifact": {"run_id": run_id, "artifact_id": 203, "name": "android-verification", "sha256": artifact_sha}}


class PayloadTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.before, self.after = self.root / "before.apk", self.root / "after.apk"
        write_zip(self.before, payload())

    def test_every_entry_including_meta_inf_and_signature_like_resources_survives(self):
        write_zip(self.after, dict(reversed(list(payload().items()))))
        report = sign.compare_payload(self.before, self.after)
        self.assertEqual(set(payload()), {entry["name"] for entry in report})
        self.assertEqual(COMMIT, sign.embedded_commit(self.after, COMMIT))

    def test_meta_inf_mutation_removal_and_addition_each_fail(self):
        for name in (sign.PROVENANCE, "META-INF/services/a.Service", "META-INF/library.version",
                     "META-INF/LICENSE.txt", "META-INF/RESOURCE.SF", "META-INF/RESOURCE.RSA"):
            for operation in ("mutate", "remove"):
                with self.subTest(name=name, operation=operation):
                    changed = payload()
                    if operation == "mutate":
                        changed[name] = bytes(x ^ 1 for x in changed[name])
                    else:
                        del changed[name]
                    write_zip(self.after, changed)
                    with self.assertRaises(sign.GateError):
                        sign.compare_payload(self.before, self.after)
        changed = payload(); changed["META-INF/unreviewed.version"] = b"new"
        write_zip(self.after, changed)
        with self.assertRaisesRegex(sign.GateError, "entry_set_changed"):
            sign.compare_payload(self.before, self.after)

    def test_duplicate_entry_cannot_hide_modified_provenance(self):
        with warnings.catch_warnings():
            warnings.simplefilter("ignore", UserWarning)
            with zipfile.ZipFile(self.after, "w") as archive:
                archive.writestr(sign.PROVENANCE, MARKER)
                archive.writestr(sign.PROVENANCE, b"changed")
        with self.assertRaisesRegex(sign.GateError, "duplicate_zip_entry"):
            sign.compare_payload(self.before, self.after)

    def test_traversal_and_symlink_rejected_without_extraction(self):
        write_zip(self.after, {"../outside": b"bad"})
        with zipfile.ZipFile(self.after) as archive:
            with self.assertRaisesRegex(sign.GateError, "unsafe_zip_name"):
                sign.zip_index(archive)
        with zipfile.ZipFile(self.after, "w") as archive:
            link = zipfile.ZipInfo("link"); link.external_attr = 0o120777 << 16
            archive.writestr(link, b"../outside")
        with zipfile.ZipFile(self.after) as archive:
            with self.assertRaisesRegex(sign.GateError, "zip_symlink"):
                sign.zip_index(archive)

    def test_provenance_requires_one_exact_full_revision(self):
        for marker in (MARKER.replace(COMMIT.encode(), b"d" * 40), MARKER + MARKER,
                       MARKER.replace(b"GIT", b"OTHER"), MARKER.replace(COMMIT.encode(), b"aaaaaaa")):
            changed = payload(); changed[sign.PROVENANCE] = marker
            write_zip(self.after, changed)
            with self.assertRaises(sign.GateError):
                sign.embedded_commit(self.after, COMMIT)

    def test_artifact_digest_and_both_source_markers_are_required(self):
        artifact = self.root / "artifact.zip"
        entries = {"app.apk": self.before.read_bytes(), "SOURCE_COMMIT.txt": COMMIT.encode(),
                   sign.PRODUCT_DIGEST: f"{sign.digest(self.before)}  app.apk\n".encode()}
        write_zip(artifact, entries)
        expected = sign.digest(artifact)
        self.assertEqual(sign.digest(self.before), sign.extract_tested_apk(
            artifact, expected, "app.apk", "SOURCE_COMMIT.txt", COMMIT, self.after))
        untouched = self.root / "must-not-exist.apk"
        with self.assertRaisesRegex(sign.GateError, "artifact_digest_mismatch"):
            sign.extract_tested_apk(artifact, "e" * 64, "app.apk", "SOURCE_COMMIT.txt", COMMIT, untouched)
        self.assertFalse(untouched.exists())
        entries["SOURCE_COMMIT.txt"] = b"f" * 40; write_zip(artifact, entries)
        with self.assertRaisesRegex(sign.GateError, "artifact_source_marker_mismatch"):
            sign.extract_tested_apk(artifact, sign.digest(artifact), "app.apk", "SOURCE_COMMIT.txt", COMMIT, untouched)

    def test_same_revision_untested_apk_cannot_be_selected_from_native_artifact(self):
        artifact = self.root / "two-products.zip"
        changed = payload(); changed["assets/data.bin"] = b"untested bytes"
        write_zip(self.after, changed)
        write_zip(artifact, {"tested.apk": self.before.read_bytes(), "untested.apk": self.after.read_bytes(),
                  "SOURCE_COMMIT.txt": COMMIT.encode(),
                  sign.PRODUCT_DIGEST: f"{sign.digest(self.before)}  tested.apk\n".encode()})
        with self.assertRaisesRegex(sign.GateError, "not_native_tested_product"):
            sign.extract_tested_apk(artifact, sign.digest(artifact), "untested.apk", "SOURCE_COMMIT.txt",
                                   COMMIT, self.root / "must-not-select.apk")
        write_zip(artifact, {"tested.apk": self.after.read_bytes(), "SOURCE_COMMIT.txt": COMMIT.encode(),
                  sign.PRODUCT_DIGEST: f"{sign.digest(self.before)}  tested.apk\n".encode()})
        with self.assertRaisesRegex(sign.GateError, "native_tested_apk_digest_mismatch"):
            sign.extract_tested_apk(artifact, sign.digest(artifact), "tested.apk", "SOURCE_COMMIT.txt",
                                   COMMIT, self.root / "must-not-sign.apk")


class GateTests(unittest.TestCase):
    def test_ci_requires_successful_distinct_full_and_native_same_head(self):
        sign.check_ci_evidence(evidence(), COMMIT, "c" * 64)
        changes = [("status", "in_progress"), ("conclusion", "failure"), ("head_sha", "f" * 40),
                   ("kind", "full"), ("run_id", 100), ("html_url", "https://example.com/run")]
        for field, value in changes:
            changed = evidence(); changed["runs"][1][field] = value
            with self.subTest(field=field), self.assertRaises(sign.GateError):
                sign.check_ci_evidence(changed, COMMIT, "c" * 64)
        for field in ("source_commit", "artifact_sha256"):
            changed = evidence(); changed[field] = "f" * 64
            with self.subTest(field=field), self.assertRaises(sign.GateError):
                sign.check_ci_evidence(changed, COMMIT, "c" * 64)

    def test_artifact_must_belong_to_successful_native_run_with_same_digest(self):
        for field, value in (("run_id", 100), ("artifact_id", 0), ("sha256", "f" * 64)):
            changed = evidence(); changed["artifact"][field] = value
            with self.subTest(field=field), self.assertRaises(sign.GateError):
                sign.check_ci_evidence(changed, COMMIT, "c" * 64)

    def test_unknown_receipt_fields_cannot_leak_into_public_reports(self):
        for target in ("top", "run", "artifact"):
            changed = evidence()
            value = changed if target == "top" else changed["runs"][0] if target == "run" else changed["artifact"]
            value["unexpected_private_field"] = {"token": "sentinel-secret"}
            with self.subTest(target=target), self.assertRaises(sign.GateError) as caught:
                sign.check_ci_evidence(changed, COMMIT, "c" * 64)
            self.assertNotIn("sentinel-secret", str(caught.exception))

    def test_tiered_run_requires_distinct_successful_full_native_jobs(self):
        good = tiered_evidence()
        result = sign.check_ci_evidence(good, COMMIT, "c" * 64)
        self.assertEqual(200, result["run"]["run_id"])
        self.assertEqual({201, 202}, {job["job_id"] for job in result["jobs"]})
        for field, value in (("job_id", 201), ("job_id", True), ("run_id", 199), ("run_attempt", 2),
                             ("head_sha", "f" * 40), ("status", "in_progress"), ("conclusion", "skipped"),
                             ("name", "Fast development gate"), ("name", "Full regression gate / verify"),
                             ("kind", "full"), ("html_url", "https://example.com/job")):
            with self.subTest(field=field, value=value):
                changed = copy.deepcopy(good); changed["jobs"][1][field] = value
                with self.assertRaises(sign.GateError):
                    sign.check_ci_evidence(changed, COMMIT, "c" * 64)
        for field, value in (("status", "in_progress"), ("conclusion", "failure"),
                             ("run_attempt", 2), ("head_sha", "f" * 40)):
            with self.subTest(run_field=field):
                changed = copy.deepcopy(good); changed["run"][field] = value
                with self.assertRaises(sign.GateError):
                    sign.check_ci_evidence(changed, COMMIT, "c" * 64)

    def test_tiered_native_artifact_cannot_be_substituted_with_fast_artifact(self):
        for field, value in (("run_id", 100), ("name", "Astravar-development-verification"),
                             ("artifact_id", 0), ("sha256", "f" * 64)):
            with self.subTest(field=field):
                changed = tiered_evidence(); changed["artifact"][field] = value
                with self.assertRaises(sign.GateError):
                    sign.check_ci_evidence(changed, COMMIT, "c" * 64)

    def test_tiered_evidence_rejects_unknown_fields_and_missing_gates(self):
        for target in ("top", "run", "job", "artifact"):
            changed = tiered_evidence()
            row = changed if target == "top" else changed["jobs"][0] if target == "job" else changed[target]
            row["secret"] = "sentinel-secret"
            with self.subTest(target=target), self.assertRaises(sign.GateError) as caught:
                sign.check_ci_evidence(changed, COMMIT, "c" * 64)
            self.assertNotIn("sentinel-secret", str(caught.exception))
        for jobs in ([], tiered_evidence()["jobs"][:1]):
            changed = tiered_evidence(); changed["jobs"] = jobs
            with self.assertRaises(sign.GateError):
                sign.check_ci_evidence(changed, COMMIT, "c" * 64)

    def test_sdk_min_and_target_are_exact_and_unambiguous(self):
        good = "sdkVersion:'26'\ntargetSdkVersion:'36'\n"
        self.assertEqual({"sdkVersion": 26, "targetSdkVersion": 36}, sign.check_sdk_levels(good))
        for bad in (good.replace("26", "25"), good.replace("36", "35"), good + "sdkVersion:'26'\n"):
            with self.assertRaisesRegex(sign.GateError, "sdk_levels"):
                sign.check_sdk_levels(bad)

    def test_signature_policy_rejects_v1_unknown_or_multiple_signers(self):
        sign.input_signature_policy(sign.signature_info(signature()))
        with self.assertRaisesRegex(sign.GateError, "unsupported_v1"):
            sign.input_signature_policy(sign.signature_info(signature(v1=True)))
        with self.assertRaises(sign.GateError):
            sign.signature_info(signature() + f"Signer #2 certificate SHA-256 digest: {CI_CERT}\n")
        sign.owner_signature_policy(sign.signature_info(signature(sign.OWNER_CERT, v3=True)))
        for bad in (signature(CI_CERT, v3=True), signature(sign.OWNER_CERT), signature(sign.OWNER_CERT, v1=True, v3=True)):
            with self.assertRaises(sign.GateError):
                sign.owner_signature_policy(sign.signature_info(bad))

    def test_credentials_only_enter_scoped_environment_never_sign_command(self):
        secrets = sign.parse_secrets({"keyAlias": "fixture-alias", "storePassword": "synthetic-store-only",
                                     "keyPassword": "synthetic-key-only"})
        base = {"JAVA_HOME": "/fictional/java"}; env = sign.secret_environment(base, secrets)
        command = sign.sign_command("apksigner", "fake.p12", secrets.alias, "input.apk", "output.apk")
        self.assertNotIn(secrets.store_password, " ".join(command))
        self.assertNotIn(secrets.key_password, " ".join(command))
        self.assertIn("env:GENESIS_OWNER_STORE_PASS", command)
        self.assertIn("env:GENESIS_OWNER_KEY_PASS", command)
        self.assertNotIn("GENESIS_OWNER_STORE_PASS", base)
        self.assertEqual(secrets.store_password, env["GENESIS_OWNER_STORE_PASS"])
        self.assertNotIn(secrets.store_password, repr(secrets))

    def test_failure_redacts_external_output_and_private_config_values(self):
        with mock.patch.object(sign.subprocess, "run", return_value=subprocess.CompletedProcess([], 1, "sentinel-secret", "sentinel-secret")):
            stdout, stderr = io.StringIO(), io.StringIO()
            with contextlib.redirect_stdout(stdout), contextlib.redirect_stderr(stderr):
                with self.assertRaisesRegex(sign.GateError, "constant_failure") as caught:
                    sign.run(["fake-tool"], code="constant_failure")
            self.assertNotIn("sentinel-secret", str(caught.exception) + stdout.getvalue() + stderr.getvalue())
        with self.assertRaises(sign.GateError):
            sign.parse_secrets({"keyAlias": "synthetic", "alias": "ambiguous", "storePassword": "sentinel", "keyPassword": "sentinel"})
        with self.assertRaisesRegex(sign.GateError, "duplicate_json_key"):
            sign.unique_json('{"source_commit":"a","source_commit":"b"}')

    def test_existing_key_must_be_private_entry_with_exact_owner_certificate(self):
        secrets = sign.Secrets("synthetic", "synthetic", "synthetic")
        good = f"Entry type: PrivateKeyEntry\nSHA256: {sign.OWNER_CERT}\n"
        with mock.patch.object(sign, "run", return_value=good):
            sign.verify_existing_key("not-executed", "not-a-key", secrets, {})
        for bad in (good.replace("PrivateKeyEntry", "trustedCertEntry"), good.replace(sign.OWNER_CERT, CI_CERT), good + good):
            with mock.patch.object(sign, "run", return_value=bad), self.assertRaises(sign.GateError):
                sign.verify_existing_key("not-executed", "not-a-key", secrets, {})


class RealGitTests(unittest.TestCase):
    def test_clean_exact_head_rejects_tracked_staged_untracked_and_stale_changes(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            def git(*args):
                return subprocess.check_output(["git", "-C", str(root), *args], stderr=subprocess.DEVNULL, text=True).strip()
            git("init", "-q"); git("config", "user.name", "Synthetic verifier"); git("config", "user.email", "synthetic@example.invalid")
            (root / "tracked.txt").write_text("initial")
            git("add", "tracked.txt"); git("commit", "-qm", "synthetic fixture")
            commit = git("rev-parse", "HEAD")
            self.assertEqual(root, sign.clean_head(root, commit))
            for flag in ("assume-unchanged", "skip-worktree"):
                git("update-index", "--" + flag, "tracked.txt")
                (root / "tracked.txt").write_text("hidden change")
                with self.assertRaisesRegex(sign.GateError, "hides_tracked"):
                    sign.clean_head(root, commit)
                (root / "tracked.txt").write_text("initial")
                git("update-index", "--no-" + flag, "tracked.txt")
            with self.assertRaisesRegex(sign.GateError, "source_head_mismatch"):
                sign.clean_head(root, "0" * 40)
            (root / "tracked.txt").write_text("changed")
            with self.assertRaisesRegex(sign.GateError, "not_clean"):
                sign.clean_head(root, commit)
            git("add", "tracked.txt")
            with self.assertRaisesRegex(sign.GateError, "not_clean"):
                sign.clean_head(root, commit)
            git("commit", "-qm", "second synthetic fixture"); commit = git("rev-parse", "HEAD")
            (root / "new.txt").write_text("untracked")
            with self.assertRaisesRegex(sign.GateError, "not_clean"):
                sign.clean_head(root, commit)


class SyntheticPipelineTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="synthetic-signer-", dir=Path(__file__).parent)
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.apk = self.root / "input.apk"; write_zip(self.apk, payload())
        self.baseline = self.root / "baseline-not-real.apk"; self.baseline.write_bytes(b"fictional non-APK baseline")
        self.artifact = self.root / "artifact.zip"
        write_zip(self.artifact, {"app.apk": self.apk.read_bytes(), "SOURCE_COMMIT.txt": COMMIT.encode(),
                                sign.PRODUCT_DIGEST: f"{sign.digest(self.apk)}  app.apk\n".encode()})
        self.ci = self.root / "ci.json"; self.ci.write_text(json.dumps(evidence(sign.digest(self.artifact))))
        self.config = self.root / "fictional-config.json"
        self.config.write_text(json.dumps({"keyAlias": "fixture", "storePassword": "synthetic-store-only", "keyPassword": "synthetic-key-only"})); self.config.chmod(0o600)
        self.key = self.root / "not-a-keystore.txt"; self.key.write_text("These are not key bytes."); self.key.chmod(0o600)
        sdk = self.root / "sdk"; tools = sdk / "build-tools/35.0.0"; tools.mkdir(parents=True)
        for name in ("apksigner", "zipalign", "aapt"):
            (tools / name).touch()
        java = self.root / "java"; (java / "bin").mkdir(parents=True); (java / "bin/keytool").touch()
        self.source_repo = self.root / "source-repo"; self.source_repo.mkdir()
        self.args = argparse.Namespace(repo=self.source_repo, source_commit=COMMIT, artifact=self.artifact,
            artifact_sha256=sign.digest(self.artifact), ci_evidence=self.ci, baseline_apk=self.baseline,
            sdk=sdk, java_home=java, output_dir=self.root / "new-output", apk_entry="app.apk",
            source_entry="SOURCE_COMMIT.txt", version_code=52, version_name="4.0.0-synthetic",
            private_config=self.config, keystore=self.key, authorize_signing=True, preflight_only=False)
        self.mutation = None
        self.calls = []
        self.addCleanup(mock.patch.stopall)
        mock.patch.object(sign, "BASELINE_SHA", sign.digest(self.baseline)).start()
        self.clean = mock.patch.object(sign, "clean_head", return_value=self.source_repo).start()
        fake = mock.Mock(); fake.inspect.return_value = {"synthetic_inspector": True}
        mock.patch.object(sign, "trusted_inspector", return_value=(fake, {"synthetic": True})).start()
        mock.patch.object(sign, "run", side_effect=self.external).start()

    def external(self, args, **kwargs):
        args = list(map(str, args)); self.calls.append(args)
        if "verify" in args:
            name = Path(args[-1]).name
            return signature(sign.OWNER_CERT if name in {self.baseline.name, "owner-signed.apk"} else CI_CERT,
                             v3=name == "owner-signed.apk")
        if "badging" in args:
            return "sdkVersion:'26'\ntargetSdkVersion:'36'\n"
        if "-list" in args:
            return f"Entry type: PrivateKeyEntry\nSHA256: {sign.OWNER_CERT}\n"
        if "sign" in args:
            source, target = Path(args[-1]), Path(args[args.index("--out") + 1])
            if self.mutation == "payload":
                changed = payload(); changed["META-INF/library.version"] = b"changed"
                write_zip(target, changed)
            else:
                shutil.copyfile(source, target)
            if self.mutation == "evidence":
                self.ci.write_text(json.dumps({"changed": True}))
            return ""
        raise AssertionError("Unexpected synthetic external call")

    def test_tiered_evidence_completes_mocked_pipeline_with_native_product_proof(self):
        self.ci.write_text(json.dumps(tiered_evidence(sign.digest(self.artifact))))
        result = sign.sign_release(self.args)
        self.assertTrue(Path(result["apk"]).is_file())
        report = json.loads(Path(result["report"]).read_text())
        self.assertEqual({201, 202}, {job["job_id"] for job in report["ci"]["jobs"]})
        self.assertEqual(sign.digest(self.artifact), report["source_artifact_sha256"])

    def test_same_or_older_version_cannot_be_signed(self):
        for code in (0, 49, 50, 51):
            self.args.version_code = code
            with self.assertRaisesRegex(sign.GateError, "candidate_version_must_advance"):
                sign.sign_release(self.args)
        self.assertFalse(self.args.output_dir.exists())

    def test_output_cannot_be_written_into_source_checkout(self):
        self.args.output_dir = self.source_repo / "output"
        with self.assertRaisesRegex(sign.GateError, "output_must_be_outside_source_checkout"):
            sign.sign_release(self.args)
        self.assertFalse(self.args.output_dir.exists())

    def test_private_key_inside_checkout_is_rejected_before_signing(self):
        self.args.keystore = self.source_repo / "key.p12"
        self.args.keystore.write_text("fictional key")
        self.args.keystore.chmod(0o600)
        with self.assertRaisesRegex(sign.GateError, "private_material_must_be_outside_checkout"):
            sign.sign_release(self.args)
        self.assertFalse(any("sign" in call for call in self.calls))

    def test_preflight_does_not_open_private_config_or_key(self):
        self.args.preflight_only, self.args.authorize_signing = True, False
        self.args.private_config = self.root / "does-not-exist.json"
        self.args.keystore = self.root / "does-not-exist.p12"
        result = sign.sign_release(self.args)
        self.assertFalse(result["signing_performed"])
        self.assertFalse(result["private_material_read"])
        self.assertFalse(any("sign" in call or "-list" in call for call in self.calls))
        self.assertFalse((self.args.output_dir / "COMPLETE").exists())

    def test_mocked_complete_pipeline_publishes_only_verified_identical_payload(self):
        result = sign.sign_release(self.args)
        self.assertTrue((self.args.output_dir / "COMPLETE").is_file())
        sign.compare_payload(self.apk, Path(result["apk"]))
        report = json.loads(Path(result["report"]).read_text())
        self.assertEqual([], report["zip_signature_entries_excluded"])
        self.assertEqual(set(payload()), {x["name"] for x in report["byte_identical_zip_entries"]})
        self.assertNotIn("synthetic-store-only", Path(result["report"]).read_text())
        self.assertFalse(list(self.args.output_dir.glob("work-*")))

    def test_publication_is_exclusive_and_never_clobbers_existing_files(self):
        self.args.output_dir.mkdir(); marker = self.args.output_dir / "existing.txt"; marker.write_text("keep")
        with self.assertRaisesRegex(sign.GateError, "output_already_exists"):
            sign.sign_release(self.args)
        self.assertEqual("keep", marker.read_text())
        self.assertEqual([], self.calls)

    def test_failed_ci_rejects_before_private_phase_or_output_creation(self):
        changed = evidence(sign.digest(self.artifact)); changed["runs"][1]["conclusion"] = "failure"
        self.ci.write_text(json.dumps(changed))
        with mock.patch.object(sign, "parse_secrets") as secret_reader:
            with self.assertRaisesRegex(sign.GateError, "ci_gate_not_successful"):
                sign.sign_release(self.args)
            secret_reader.assert_not_called()
        self.assertFalse(self.args.output_dir.exists())

    def test_payload_or_evidence_change_prevents_delivery_and_preserves_failure_report(self):
        for mutation in ("payload", "evidence"):
            with self.subTest(mutation=mutation):
                self.mutation = mutation
                self.args.output_dir = self.root / ("failed-" + mutation)
                self.ci.write_text(json.dumps(evidence(sign.digest(self.artifact))))
                with self.assertRaises(sign.GateError):
                    sign.sign_release(self.args)
                self.assertFalse(list(self.args.output_dir.glob("*.apk")))
                self.assertFalse((self.args.output_dir / "COMPLETE").exists())
                self.assertTrue((self.args.output_dir / "FAILED.json").is_file())
                self.assertFalse(list(self.args.output_dir.glob("work-*")))

    def test_source_change_during_signing_prevents_publication(self):
        self.clean.side_effect = [self.source_repo, self.source_repo, sign.GateError("source_checkout_not_clean")]
        with self.assertRaisesRegex(sign.GateError, "source_checkout_not_clean"):
            sign.sign_release(self.args)
        self.assertFalse(list(self.args.output_dir.glob("*.apk")))

    def test_late_read_and_cleanup_failure_leave_no_complete_marker_or_apk(self):
        original_digest, original_cleanup = sign.digest, sign.shutil.rmtree
        for failure in ("late-read", "cleanup"):
            with self.subTest(failure=failure):
                self.args.output_dir = self.root / failure
                def read(path):
                    if failure == "late-read" and Path(path).name.startswith("Astravar-"):
                        raise OSError("synthetic late read failure")
                    return original_digest(path)
                def cleanup(path, *args, **kwargs):
                    if failure == "cleanup" and not kwargs.get("ignore_errors"):
                        raise OSError("synthetic cleanup failure")
                    return original_cleanup(path, *args, **kwargs)
                with mock.patch.object(sign, "digest", side_effect=read), mock.patch.object(sign.shutil, "rmtree", side_effect=cleanup):
                    with self.assertRaises(sign.GateError):
                        sign.sign_release(self.args)
                self.assertFalse((self.args.output_dir / "COMPLETE").exists())
                self.assertFalse(list(self.args.output_dir.glob("*.apk")))
                self.assertTrue((self.args.output_dir / "FAILED.json").is_file())


if __name__ == "__main__":
    unittest.main()
