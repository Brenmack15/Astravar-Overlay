#!/usr/bin/env python3
"""Fail-closed local signer of an already-tested Astravar APK.

No build, upload, installation, key generation, or default signing action exists.
Importing this module performs no I/O. Secrets are read only after public gates.
"""
from __future__ import annotations

import argparse
from dataclasses import dataclass, field
import hashlib
import importlib.util
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import subprocess
import tempfile
import zipfile

OWNER_CERT = "4c5cb0113cae5d0dee2619ee30f5b3c593d4aa897e1fa38dee7ed5561ec4e847"
BASELINE_SHA = "81c4ab63e4aba980bc7881919d6e90333e2f72f3031d43db2fbbe1fa17d5e644"
BASELINE_CODE = 51
PACKAGE = "com.yazsras.astravar"
REPOSITORY = "Brenmack15/Astravar-Overlay"
PROVENANCE = "META-INF/version-control-info.textproto"
PRODUCT_DIGEST = "validation/product-apk.sha256"
MAX_ENTRY = 512 * 1024 * 1024
MAX_TOTAL = 2 * 1024 * 1024 * 1024


class GateError(Exception):
    """Only constant, non-secret error identifiers cross the CLI boundary."""


def require(condition, code):
    if not condition:
        raise GateError(code)


def digest(path):
    h = hashlib.sha256()
    with Path(path).open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            h.update(block)
    return h.hexdigest()


def unique_json(text):
    def pairs(values):
        result = {}
        for key, value in values:
            require(key not in result, "duplicate_json_key")
            result[key] = value
        return result
    return json.loads(text, object_pairs_hook=pairs)


def run(args, *, env=None, timeout=90, code="external_verification_failed", binary=False):
    try:
        result = subprocess.run(list(map(str, args)), env=env, capture_output=True,
                                text=not binary, timeout=timeout, check=False)
    except (OSError, subprocess.TimeoutExpired):
        raise GateError(code) from None
    require(result.returncode == 0, code)
    return result.stdout


def clean_head(repo, expected):
    require(re.fullmatch(r"[0-9a-f]{40}", expected) is not None, "invalid_source_commit")
    repo = Path(repo).resolve(strict=True)
    root = run(["git", "-C", repo, "rev-parse", "--show-toplevel"], code="source_checkout_invalid").strip()
    require(Path(root).resolve() == repo, "source_must_be_checkout_root")
    head = run(["git", "-C", repo, "rev-parse", "HEAD"], code="source_checkout_invalid").strip()
    require(head == expected, "source_head_mismatch")
    status = run(["git", "-C", repo, "status", "--porcelain=v1", "--untracked-files=all",
                  "--ignore-submodules=none"], code="source_checkout_invalid")
    require(not status.strip(), "source_checkout_not_clean")
    flags = run(["git", "-C", repo, "ls-files", "-v", "-z"], code="source_index_invalid")
    require(all(record.startswith("H ") for record in flags.split("\0") if record),
            "source_index_hides_tracked_files")
    tracked = run(["git", "-C", repo, "ls-files", "--stage", "-z"], code="source_index_invalid")
    for record in tracked.split("\0"):
        if not record:
            continue
        metadata, name = record.split("\t", 1)
        mode, object_id, stage = metadata.split()
        require(stage == "0" and mode in {"100644", "100755", "120000"}, "unsupported_source_index_entry")
        path = repo / name
        if mode == "120000":
            require(path.is_symlink(), "source_tracked_type_changed")
            data = os.fsencode(os.readlink(path))
        else:
            require(path.is_file() and not path.is_symlink(), "source_tracked_type_changed")
            require(bool(path.stat().st_mode & 0o111) == (mode == "100755"), "source_tracked_mode_changed")
            data = path.read_bytes()
        actual = hashlib.sha1(f"blob {len(data)}\0".encode("ascii") + data).hexdigest()
        require(actual == object_id, "source_tracked_bytes_changed")
    return repo


def check_legacy_ci_evidence(document, commit, artifact_sha):
    require(isinstance(document, dict) and document.get("schema_version") == 1, "ci_evidence_schema")
    require(set(document) == {"schema_version", "source_commit", "artifact_sha256", "runs", "artifact"}, "ci_unexpected_fields")
    require(document.get("source_commit") == commit, "ci_evidence_source_mismatch")
    require(document.get("artifact_sha256") == artifact_sha, "ci_evidence_artifact_mismatch")
    runs = document.get("runs")
    require(isinstance(runs, list) and len(runs) == 2, "ci_two_gates_required")
    require({r.get("kind") for r in runs if isinstance(r, dict)} == {"full", "native"}, "ci_gate_kinds")
    ids = set()
    for entry in runs:
        require(set(entry) == {"kind", "run_id", "head_sha", "status", "conclusion", "html_url"}, "ci_run_unexpected_fields")
        require(type(entry.get("run_id")) is int and entry["run_id"] > 0, "ci_run_id")
        require(entry["run_id"] not in ids, "ci_distinct_runs_required")
        ids.add(entry["run_id"])
        require(entry.get("head_sha") == commit, "ci_run_source_mismatch")
        require(entry.get("status") == "completed" and entry.get("conclusion") == "success", "ci_gate_not_successful")
        require(entry.get("html_url") == f"https://github.com/{REPOSITORY}/actions/runs/{entry['run_id']}", "ci_run_url")
    artifact = document.get("artifact")
    require(isinstance(artifact, dict), "ci_artifact_receipt_required")
    require(set(artifact) == {"run_id", "artifact_id", "sha256"}, "ci_artifact_unexpected_fields")
    native_run = next(entry for entry in runs if entry["kind"] == "native")
    require(type(artifact.get("run_id")) is int and artifact["run_id"] == native_run["run_id"], "artifact_must_come_from_successful_native_run")
    require(type(artifact.get("artifact_id")) is int and artifact["artifact_id"] > 0, "ci_artifact_id")
    require(artifact.get("sha256") == artifact_sha, "ci_artifact_receipt_digest_mismatch")
    # This is a pinned operator-supplied receipt, not an independent GitHub query.
    return {"receipt_source": "operator-verified GitHub run and artifact metadata", "runs": runs, "artifact": artifact}


# GitHub reports reusable workflow job names with their caller name prefix.
TIER_B_JOBS = {
    "full": "Full regression gate / verify",
    "native": "Historical upgrade and native acceptance / verify",
}


def check_ci_evidence(document, commit, artifact_sha):
    """Accept legacy separate runs or independent jobs in a successful Tier B run."""
    require(isinstance(document, dict), "ci_evidence_schema")
    if document.get("schema_version") == 1:
        return check_legacy_ci_evidence(document, commit, artifact_sha)
    require(document.get("schema_version") == 2, "ci_evidence_schema")
    require(set(document) == {"schema_version", "source_commit", "artifact_sha256", "run", "jobs", "artifact"},
            "ci_unexpected_fields")
    require(document["source_commit"] == commit, "ci_evidence_source_mismatch")
    require(document["artifact_sha256"] == artifact_sha, "ci_evidence_artifact_mismatch")
    run = document["run"]
    require(isinstance(run, dict) and set(run) == {
        "run_id", "run_attempt", "head_sha", "status", "conclusion", "html_url"
    }, "ci_run_unexpected_fields")
    require(type(run["run_id"]) is int and run["run_id"] > 0 and
            type(run["run_attempt"]) is int and run["run_attempt"] > 0, "ci_run_id")
    require(run["head_sha"] == commit, "ci_run_source_mismatch")
    require(run["status"] == "completed" and run["conclusion"] == "success", "ci_gate_not_successful")
    run_url = f"https://github.com/{REPOSITORY}/actions/runs/{run['run_id']}"
    require(run["html_url"] == run_url, "ci_run_url")
    jobs = document["jobs"]
    require(isinstance(jobs, list) and len(jobs) == 2, "ci_two_gates_required")
    require(all(isinstance(job, dict) for job in jobs) and
            {job.get("kind") for job in jobs} == set(TIER_B_JOBS), "ci_gate_kinds")
    seen = set()
    for job in jobs:
        require(set(job) == {"kind", "job_id", "run_id", "run_attempt", "head_sha", "name",
                             "status", "conclusion", "html_url"}, "ci_job_unexpected_fields")
        require(type(job["job_id"]) is int and job["job_id"] > 0, "ci_job_id")
        require(job["job_id"] not in seen, "ci_distinct_jobs_required")
        seen.add(job["job_id"])
        require(type(job["run_id"]) is int and job["run_id"] == run["run_id"] and
                type(job["run_attempt"]) is int and job["run_attempt"] == run["run_attempt"],
                "ci_job_run_mismatch")
        require(job["head_sha"] == commit, "ci_job_source_mismatch")
        require(job["name"] == TIER_B_JOBS[job["kind"]], "ci_job_role_mismatch")
        require(job["status"] == "completed" and job["conclusion"] == "success", "ci_job_not_successful")
        require(job["html_url"] == f"{run_url}/job/{job['job_id']}", "ci_job_url")
    artifact = document["artifact"]
    require(isinstance(artifact, dict) and set(artifact) == {
        "run_id", "artifact_id", "name", "sha256"
    }, "ci_artifact_unexpected_fields")
    require(type(artifact["run_id"]) is int and artifact["run_id"] == run["run_id"],
            "artifact_must_come_from_successful_native_run")
    require(type(artifact["artifact_id"]) is int and artifact["artifact_id"] > 0, "ci_artifact_id")
    # This name is produced only by the native workflow in the pinned source.
    require(artifact["name"] == "android-verification", "ci_native_artifact_name")
    require(artifact["sha256"] == artifact_sha, "ci_artifact_receipt_digest_mismatch")
    return {"receipt_source": "operator-verified GitHub run, job and artifact metadata",
            "run": run, "jobs": jobs, "artifact": artifact}


def zip_index(archive):
    infos = archive.infolist()
    require(len(infos) <= 100000, "zip_entry_limit")
    names = [info.filename for info in infos]
    require(len(names) == len(set(names)), "duplicate_zip_entry")
    require(sum(info.file_size for info in infos) <= MAX_TOTAL, "zip_total_limit")
    for info in infos:
        name = info.filename
        path = PurePosixPath(name)
        require(name and not path.is_absolute() and ".." not in path.parts
                and "\\" not in name and "\x00" not in name, "unsafe_zip_name")
        require(not (info.flag_bits & 1), "encrypted_zip_entry")
        require(info.file_size <= MAX_ENTRY, "zip_entry_size_limit")
        require((info.external_attr >> 16) & 0o170000 != 0o120000, "zip_symlink")
    return dict(zip(names, infos))


def embedded_commit(apk, expected):
    with zipfile.ZipFile(apk) as archive:
        names = zip_index(archive)
        require(PROVENANCE in names and names[PROVENANCE].file_size < 65536, "apk_provenance_missing")
        text = archive.read(PROVENANCE).decode("utf-8", errors="strict")
    revisions = re.findall(r'\brevision\s*:\s*"([0-9a-f]{40})"', text)
    require(revisions == [expected] and len(re.findall(r"\brevision\s*:", text)) == 1,
            "apk_embedded_revision_mismatch")
    require(len(re.findall(r"\bsystem\s*:\s*GIT\b", text)) == 1, "apk_source_system")
    return expected


def extract_tested_apk(artifact, expected_sha, apk_entry, source_entry, commit, destination):
    require(re.fullmatch(r"[0-9a-f]{64}", expected_sha) is not None, "invalid_artifact_digest")
    require(digest(artifact) == expected_sha, "artifact_digest_mismatch")
    with zipfile.ZipFile(artifact) as archive:
        names = zip_index(archive)
        require(apk_entry in names and source_entry in names and PRODUCT_DIGEST in names,
                "artifact_required_entry_missing")
        require(names[source_entry].file_size < 1024, "artifact_source_marker_size")
        require(archive.read(source_entry).decode("ascii").strip() == commit, "artifact_source_marker_mismatch")
        require(names[PRODUCT_DIGEST].file_size < 4096, "native_product_digest_size")
        native_record = archive.read(PRODUCT_DIGEST).decode("ascii")
        native_match = re.fullmatch(r"([0-9a-f]{64}) [ *]([^\r\n]+)\n?", native_record)
        require(native_match is not None and native_match[2] == apk_entry, "selected_apk_not_native_tested_product")
        with archive.open(apk_entry) as src, Path(destination).open("xb") as dst:
            shutil.copyfileobj(src, dst)
        require(digest(destination) == native_match[1], "native_tested_apk_digest_mismatch")
    require(digest(artifact) == expected_sha, "artifact_changed_during_read")
    embedded_commit(destination, commit)
    return digest(destination)


def signature_info(text):
    certs = re.findall(r"^Signer #\d+ certificate SHA-256 digest: ([0-9a-fA-F]{64})$", text, re.MULTILINE)
    require(len(certs) == 1, "exactly_one_apk_signer_required")
    schemes = {}
    for name, enabled in re.findall(r"^Verified using (v[1234](?:\.1)?) scheme \([^\n]+\): (true|false)$", text, re.MULTILINE):
        require(name not in schemes, "ambiguous_signature_scheme")
        schemes[name] = enabled == "true"
    require(all(k in schemes for k in ("v1", "v2", "v3")), "signature_scheme_report_missing")
    require(schemes["v1"] or schemes["v2"] or schemes["v3"], "no_verified_signature_scheme")
    return {"certificate_sha256": certs[0].lower(), "schemes": schemes}


def input_signature_policy(info):
    require(not info["schemes"]["v1"], "unsupported_v1_signed_input")
    require(info["schemes"]["v2"] or info["schemes"]["v3"], "modern_input_signature_required")


def owner_signature_policy(info):
    require(info["certificate_sha256"] == OWNER_CERT and not info["schemes"]["v1"]
            and info["schemes"]["v2"] and info["schemes"]["v3"], "owner_output_signature_policy")


def check_sdk_levels(badging):
    values = {}
    for field_name, expected in (("sdkVersion", "26"), ("targetSdkVersion", "36")):
        found = re.findall(r"^" + field_name + r":'([^']+)'$", badging, re.MULTILINE)
        require(found == [expected], "apk_sdk_levels_mismatch")
        values[field_name] = int(found[0])
    return values


def compare_payload(before, after):
    report = []
    with zipfile.ZipFile(before) as old, zipfile.ZipFile(after) as new:
        prior, current = zip_index(old), zip_index(new)
        require(set(prior) == set(current), "apk_payload_entry_set_changed")
        for name in sorted(current):
            require(prior[name].file_size == current[name].file_size, "apk_payload_entry_size_changed")
            h = hashlib.sha256()
            with old.open(name) as a, new.open(name) as b:
                while True:
                    left, right = a.read(1024 * 1024), b.read(1024 * 1024)
                    require(left == right, "apk_payload_entry_bytes_changed")
                    if not left:
                        break
                    h.update(left)
            report.append({"name": name, "bytes": current[name].file_size, "sha256": h.hexdigest()})
    return report


@dataclass(repr=False)
class Secrets:
    alias: str = field(repr=False)
    store_password: str = field(repr=False)
    key_password: str = field(repr=False)


def parse_secrets(document):
    fields = {}
    def walk(value):
        require(isinstance(value, dict), "private_config_structure")
        for key, item in value.items():
            if isinstance(item, dict):
                walk(item)
            elif isinstance(item, str):
                normalized = re.sub(r"[^a-z0-9]", "", key.lower())
                fields.setdefault(normalized, []).append(item)
    walk(document)
    def one(keys):
        values = [v for key in keys for v in fields.get(key, [])]
        require(len(values) == 1 and bool(values[0]), "private_config_required_field")
        return values[0]
    return Secrets(one(("alias", "keyalias", "astravarkeyalias")),
                   one(("storepassword", "keystorepassword", "keystorestorepassword",
                        "androidkeystorepassword", "astravarstorepassword")),
                   one(("keypassword", "astravarkeypassword")))


def secret_environment(base, secrets):
    result = dict(base)
    result["GENESIS_OWNER_STORE_PASS"] = secrets.store_password
    result["GENESIS_OWNER_KEY_PASS"] = secrets.key_password
    return result


def sign_command(apksigner, keystore, alias, source, output):
    return [str(apksigner), "sign", "--ks", str(keystore), "--ks-key-alias", alias,
            "--ks-pass", "env:GENESIS_OWNER_STORE_PASS", "--key-pass", "env:GENESIS_OWNER_KEY_PASS",
            "--v1-signing-enabled", "false", "--v2-signing-enabled", "true",
            "--v3-signing-enabled", "true", "--v4-signing-enabled", "false",
            "--out", str(output), str(source)]


def verify_existing_key(keytool, keystore, secrets, env):
    text = run([keytool, "-J-Duser.language=en", "-J-Duser.country=US", "-list", "-v",
                "-storetype", "PKCS12", "-keystore", keystore, "-alias", secrets.alias,
                "-storepass:env", "GENESIS_OWNER_STORE_PASS"], env=env, code="existing_owner_key_invalid")
    certs = [re.sub(r"[^0-9a-fA-F]", "", value).lower()
             for value in re.findall(r"SHA256:\s*([^\r\n]+)", text)]
    require(certs == [OWNER_CERT] and text.count("Entry type: PrivateKeyEntry") == 1,
            "existing_owner_certificate_mismatch")


def trusted_inspector(repo, commit, work):
    folder = work / "repository-inspector"
    folder.mkdir(mode=0o700)
    hashes = {}
    for name in ("inspect_release_apk.py", "branding_assets_sha256.json"):
        content = run(["git", "-C", repo, "show", f"{commit}:scripts/{name}"], binary=True,
                      code="repository_inspector_missing")
        path = folder / name
        path.write_bytes(content)
        hashes[name] = hashlib.sha256(content).hexdigest()
    spec = importlib.util.spec_from_file_location("genesis_pinned_release_inspector", folder / "inspect_release_apk.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    require(module.OWNER_CERTIFICATE == OWNER_CERT and module.PERMANENT_PACKAGE == PACKAGE,
            "repository_identity_policy_mismatch")
    return module, hashes


def sign_release(args):
    require(bool(args.authorize_signing) != bool(args.preflight_only), "explicit_action_required")
    require(args.version_code > BASELINE_CODE and bool(args.version_name), "candidate_version_must_advance")
    require(re.fullmatch(r"[A-Za-z0-9_.-]{1,100}", args.version_name) is not None, "unsafe_version_name")
    repo = clean_head(args.repo, args.source_commit)
    artifact, evidence = args.artifact.resolve(strict=True), args.ci_evidence.resolve(strict=True)
    evidence_sha = digest(evidence)
    ci = check_ci_evidence(unique_json(evidence.read_text()), args.source_commit, args.artifact_sha256)
    require(digest(args.baseline_apk) == BASELINE_SHA, "trusted_baseline_digest_mismatch")
    sdk, java = args.sdk.resolve(strict=True), args.java_home.resolve(strict=True)
    tools = sdk / "build-tools/35.0.0"
    require(all((tools / name).is_file() for name in ("apksigner", "aapt", "zipalign")), "sdk_tools_missing")
    require((java / "bin/keytool").is_file(), "keytool_missing")
    # The inspector uses subprocess internally; only non-secret Java configuration is global.
    os.environ["JAVA_HOME"] = str(java)
    output = args.output_dir.absolute()
    require(output.parent.exists() and not output.parent.resolve().is_relative_to(repo),
            "output_must_be_outside_source_checkout")
    require((output.parent.stat().st_mode & 0o077) == 0, "output_parent_must_be_private")
    require(not output.exists() and not output.is_symlink(), "output_already_exists")
    output.mkdir(mode=0o700)  # Exclusive claim, before credentials are read.
    work = Path(tempfile.mkdtemp(prefix="work-", dir=output))
    phase = "public_preflight"
    published = None
    try:
        source, signed = work / "tested-ci.apk", work / "owner-signed.apk"
        input_sha = extract_tested_apk(artifact, args.artifact_sha256, args.apk_entry,
                                       args.source_entry, args.source_commit, source)
        baseline_sig = signature_info(run([tools / "apksigner", "verify", "--verbose", "--print-certs", args.baseline_apk]))
        require(baseline_sig["certificate_sha256"] == OWNER_CERT, "trusted_baseline_certificate_mismatch")
        input_sig = signature_info(run([tools / "apksigner", "verify", "--verbose", "--print-certs", source]))
        input_signature_policy(input_sig)
        inspector, inspector_hashes = trusted_inspector(repo, args.source_commit, work)
        input_inspection = inspector.inspect(source, sdk, PACKAGE, args.version_name, args.version_code, False)
        sdk_levels = check_sdk_levels(run([tools / "aapt", "dump", "badging", source]))
        clean_head(repo, args.source_commit)
        require(digest(artifact) == args.artifact_sha256 and digest(source) == input_sha,
                "input_changed_before_signing")
        require(digest(args.baseline_apk) == BASELINE_SHA, "baseline_changed_before_signing")
        require(digest(evidence) == evidence_sha, "ci_evidence_changed")
        if args.preflight_only:
            report = {"schema_version": 1, "source_commit": args.source_commit,
                      "source_artifact_sha256": args.artifact_sha256, "tested_apk_sha256": input_sha,
                      "helper_sha256": digest(__file__), "ci_evidence_sha256": evidence_sha,
                      "ci": ci, "input_signature": input_sig, "input_inspection": input_inspection,
                      "sdk_levels": sdk_levels, "source_inspector_sha256": inspector_hashes,
                      "all_zip_entries_will_be_compared": True,
                      "native_product_digest_entry": PRODUCT_DIGEST,
                      "private_material_read": False, "signing_performed": False,
                      "status": "public preflight passed; owner signing remains a separate action"}
            path = output / "PREFLIGHT_REPORT.json"
            with path.open("x") as stream:
                json.dump(report, stream, indent=2); stream.write("\n")
            shutil.rmtree(work)
            work = None
            return {"preflight_report": str(path), "signing_performed": False, "private_material_read": False}
        phase = "private_identity_verification"
        require(args.keystore is not None and args.private_config is not None, "private_paths_required")
        require(args.keystore.is_file() and not args.keystore.is_symlink(), "existing_keystore_required")
        require(not args.keystore.resolve().is_relative_to(repo) and
                not args.private_config.resolve().is_relative_to(repo), "private_material_must_be_outside_checkout")
        require((args.keystore.stat().st_mode & 0o077) == 0 and
                (args.private_config.stat().st_mode & 0o077) == 0, "private_file_permissions")
        require(not args.private_config.is_symlink(), "private_config_symlink")
        key_sha = digest(args.keystore)
        secrets = parse_secrets(unique_json(args.private_config.read_text()))
        env = secret_environment(os.environ, secrets)
        verify_existing_key(java / "bin/keytool", args.keystore, secrets, env)
        require(digest(args.keystore) == key_sha, "keystore_changed_before_signing")
        phase = "sign_tested_payload"
        require(not signed.exists(), "staged_output_already_exists")
        run(sign_command(tools / "apksigner", args.keystore, secrets.alias, source, signed),
            env=env, timeout=180, code="owner_signing_failed")
        del env, secrets
        require(digest(args.keystore) == key_sha, "keystore_changed_during_signing")
        phase = "post_sign_verification"
        owner_sig = signature_info(run([tools / "apksigner", "verify", "--verbose", "--print-certs", signed]))
        owner_signature_policy(owner_sig)
        entries = compare_payload(source, signed)
        embedded_commit(signed, args.source_commit)
        output_inspection = inspector.inspect(signed, sdk, PACKAGE, args.version_name, args.version_code, True)
        require(check_sdk_levels(run([tools / "aapt", "dump", "badging", signed])) == sdk_levels,
                "output_sdk_levels_changed")
        clean_head(repo, args.source_commit)
        require(digest(artifact) == args.artifact_sha256 and digest(source) == input_sha,
                "input_changed_during_signing")
        report = {"schema_version": 1, "helper_generation": "versioned local release procedure",
                  "helper_sha256": digest(__file__), "source_commit": args.source_commit,
                  "clean_exact_source_verified": True, "ci_evidence_sha256": evidence_sha, "ci": ci,
                  "source_artifact_sha256": args.artifact_sha256, "artifact_apk_entry": args.apk_entry,
                  "tested_apk_sha256": input_sha, "input_signature": input_sig,
                  "native_product_digest_entry": PRODUCT_DIGEST,
                  "baseline_apk_sha256": BASELINE_SHA, "baseline_owner_signature_verified": True,
                  "existing_owner_key_verified": True, "source_inspector_sha256": inspector_hashes,
                  "input_inspection": input_inspection, "output_inspection": output_inspection,
                  "sdk_levels": sdk_levels, "output_signature": owner_sig,
                  "byte_identical_zip_entries": entries,
                  "zip_signature_entries_excluded": [],
                  "rebuilt": False, "installed": False, "physical_device_verified": False}
        require(digest(evidence) == evidence_sha, "ci_evidence_changed")
        final = output / f"Astravar-{args.version_name}-code{args.version_code}-owner-signed.apk"
        require(re.fullmatch(r"[A-Za-z0-9_.-]+", final.name) is not None, "unsafe_output_filename")
        os.link(signed, final)  # Atomic publication cannot replace an existing path.
        published = final
        final_sha = digest(final)
        require(final_sha == digest(signed), "published_apk_digest_mismatch")
        with (output / "SIGNING_REPORT.json").open("x") as stream:
            json.dump(report, stream, indent=2); stream.write("\n")
        with (output / "SHA256SUMS").open("x") as stream:
            stream.write(f"{final_sha}  {final.name}\n")
        result = {"apk": str(final), "report": str(output / "SIGNING_REPORT.json"),
                  "apk_sha256": final_sha, "source_commit": args.source_commit}
        shutil.rmtree(work)
        work = None
        with (output / "COMPLETE").open("x") as stream:
            stream.write("Signature, identity, provenance, alignment, and payload checks passed.\n")
        return result
    except BaseException as error:
        (output / "COMPLETE").unlink(missing_ok=True)
        if published is not None:
            published.unlink(missing_ok=True)
        code = str(error) if isinstance(error, GateError) else type(error).__name__
        with (output / "FAILED.json").open("x") as stream:
            json.dump({"phase": phase, "error_code": code, "complete": False}, stream); stream.write("\n")
        raise GateError(code) from None
    finally:
        if work is not None:
            shutil.rmtree(work, ignore_errors=True)


def parser():
    p = argparse.ArgumentParser(description=__doc__)
    for option in ("repo", "artifact", "ci-evidence", "sdk", "java-home", "baseline-apk", "output-dir"):
        p.add_argument("--" + option, type=Path, required=True)
    for option in ("source-commit", "artifact-sha256", "apk-entry", "version-name"):
        p.add_argument("--" + option, required=True)
    p.add_argument("--source-entry", default="validation/SOURCE_COMMIT.txt")
    p.add_argument("--version-code", type=int, required=True)
    p.add_argument("--private-config", type=Path)
    p.add_argument("--keystore", type=Path)
    action = p.add_mutually_exclusive_group(required=True)
    action.add_argument("--authorize-signing", action="store_true")
    action.add_argument("--preflight-only", action="store_true")
    return p


if __name__ == "__main__":
    os.umask(0o077)
    try:
        result = sign_release(parser().parse_args())
    except Exception as error:
        print(json.dumps({"complete": False, "error_code": str(error) if isinstance(error, GateError) else type(error).__name__}))
        raise SystemExit(1) from None
    print(json.dumps(result))
