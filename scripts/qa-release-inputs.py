#!/usr/bin/env python3
"""Verify the repository's final native QA ZIP. Writes two small JSON receipts and no APK."""
import argparse
import datetime
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import zipfile


def check(condition, message):
    if not condition:
        raise ValueError(message)


def run_gh(path, stdout=subprocess.PIPE):
    return subprocess.run(["gh", "api", "--method", "GET", path], stdout=stdout, check=True).stdout


def github(path):
    return json.loads(run_gh(path))


def instant(value):
    return datetime.datetime.fromisoformat(value.replace("Z", "+00:00"))


def write_receipt(path, data):
    with path.open("x", encoding="utf-8") as out:
        json.dump(data, out, ensure_ascii=False, indent=2, sort_keys=True)
        out.write("\n")


def get_qa_receipt(repo, commit, run_id, artifact_id, checksum, policy):
    api = "repos/" + repo
    run = github(f"{api}/actions/runs/{run_id}")
    check(run["repository"]["full_name"] == repo and run["head_repository"]["full_name"] == repo,
          "Inspect only this repository's own release run")
    check(run["id"] == run_id and run["head_sha"] == commit and
          run["path"] == ".github/workflows/astravar-development.yml",
          "Run, source or Tier B workflow mismatch")
    attempt = run["run_attempt"]
    records = github(f"{api}/actions/runs/{run_id}/attempts/{attempt}/jobs?per_page=100")
    check(records["total_count"] == len(records["jobs"]), "Incomplete job list")
    evidence = {
        "schema_version": 2, "source_commit": commit, "artifact_sha256": checksum,
        "run": {"run_id": run_id, **{key: run[key] for key in
                  ("run_attempt", "head_sha", "status", "conclusion", "html_url")}},
        "jobs": [],
    }
    native = None
    for kind, name in policy.TIER_B_JOBS.items():
        job, = [j for j in records["jobs"] if j["name"] == name]
        evidence["jobs"].append({"kind": kind, "job_id": job["id"], **{key: job[key] for key in
            ("run_id", "run_attempt", "head_sha", "name", "status", "conclusion", "html_url")}})
        if kind == "native":
            native = job
    artifact = github(f"{api}/actions/artifacts/{artifact_id}")
    binding = artifact["workflow_run"]
    check(binding["id"] == run_id and binding["head_sha"] == commit and
          binding["repository_id"] == run["repository"]["id"] and
          binding["head_repository_id"] == run["head_repository"]["id"] and
          artifact["id"] == artifact_id and not artifact["expired"] and
          artifact["name"] == "android-verification" and artifact["digest"] == "sha256:" + checksum,
          "Artifact identity, source, lifetime or digest mismatch")
    check(instant(native["started_at"]) <= instant(artifact["created_at"]) <= instant(native["completed_at"]),
          "Archive was not produced during the successful native job attempt")
    evidence["artifact"] = {"run_id": run_id, "artifact_id": artifact_id,
                            "name": artifact["name"], "sha256": checksum}
    policy.check_ci_evidence(evidence, commit, checksum)
    for name in (
        "Android 17 authorization, force-stopped cloud gameplay, code 58 upgrade and visual verification",
        "Ordered release UI, import, recovery and in-place update on Android 16",
    ):
        step, = [step for step in native["steps"] if step["name"] == name]
        check(step["status"] == "completed" and step["conclusion"] == "success", name)
    return evidence


def read_small(archive, name, limit=65536):
    item = archive.getinfo(name)
    check(item.file_size <= limit, "Oversized evidence " + name)
    return archive.read(item).decode("utf-8")


def verify_native_coverage(artifact, commit, policy):
    with zipfile.ZipFile(artifact) as archive:
        stages = [line.split("\t") for line in read_small(archive, "validation/native-stages.tsv").splitlines()]
        check(stages and all(len(s) == 3 and s[0] == "PASS" for s in stages), "Native stage did not pass")
        required = {
            "native-genesis-canonical-write.txt", "native-large-campaign-selection.txt",
            "native-player-first.txt", "native-genesis-consolidation.txt", "native-visual-rebirth.txt",
            "native-genesis.txt", "native-genesis-notion-preview.txt", "native-genesis-crafting.txt",
            "native-genesis-encounter.txt", "native-update-readback.txt", "native-campaign-import.txt",
            "native-campaign-rollback.txt", "native-mapped-campaign-ui.txt", "native-mapped-campaign-rollback.txt",
        }
        check(required <= {s[2] for s in stages}, "Missing ordered acceptance evidence")
        check(read_small(archive, "validation/RELEASE-UI-RESULT.txt").startswith("PASS:"), "Indeterminate native completion")
        check(read_small(archive, "validation/owner-hp-native/SOURCE_COMMIT.txt").strip() == commit,
              "Android 17 source mismatch")
        scope = policy.unique_json(read_small(archive, "validation/owner-hp-native/SCOPE.json"))
        check(scope["fictionalOnly"] is True and scope["emulatorApi"] == 37 and scope["android17"] is True and
              scope["api37ForceStoppedCloudReconnect"] is True and scope["physicalGalaxy"] is False and
              scope["actualOwnerSigningIdentityTestedInCi"] is False, "Unknown Android 17 scope")
        package = policy.unique_json(read_small(archive, "validation/ascension-api37/package-result.json"))
        check((package["package"], package["old_version"], package["new_version"]) == (policy.PACKAGE, 58, 59) and
              package["same_android_uid"] is True and package["same_first_install_time"] is True,
              "Unexpected Android 17 installed package or version range")
        return {"orderedAndroid16Stages": len(stages), "android17": True, "forceStoppedCloudReconnect": True,
                "android17PackageUpgrade": [58, 59], "syntheticEmulators": True, "physicalDevice": False}


def payload_digest(apk, policy):
    with zipfile.ZipFile(apk) as archive:
        files = policy.zip_index(archive)
        manifest = hashlib.sha256()
        for name in sorted(files):
            with archive.open(files[name]) as stream:
                file_hash = hashlib.sha256()
                for block in iter(lambda: stream.read(1024 * 1024), b""):
                    file_hash.update(block)
                digest = file_hash.hexdigest()
            row = [name, files[name].file_size, digest]
            manifest.update((json.dumps(row, ensure_ascii=True, separators=(",", ":")) + "\n").encode("ascii"))
        return {"entries": len(files), "sortedNameSizeContentManifestSha256": manifest.hexdigest()}


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--repo", type=Path, required=True)
    p.add_argument("--source", required=True)
    p.add_argument("--run", type=int, required=True)
    p.add_argument("--artifact", type=int, required=True)
    p.add_argument("--archive-sha256", required=True)
    p.add_argument("--archive-file", type=Path, help="Use an existing original GitHub ZIP verbatim")
    p.add_argument("--output", type=Path, required=True)
    a = p.parse_args()
    check(re.fullmatch("[0-9a-f]{40}", a.source) and re.fullmatch("[0-9a-f]{64}", a.archive_sha256) and
          a.run > 0 and a.artifact > 0, "Invalid immutable inputs")
    src = a.repo.resolve(strict=True)
    sys.dont_write_bytecode = True
    check(subprocess.check_output(["git", "-C", src, "rev-parse", "HEAD"], text=True).strip() == a.source,
          "Incorrect source checkout")
    helper_bytes = subprocess.check_output(["git", "-C", src, "show", a.source + ":scripts/owner_release_sign.py"])
    with tempfile.TemporaryDirectory() as pinned:
        helper_path = Path(pinned) / "owner_release_sign.py"
        helper_path.write_bytes(helper_bytes)
        spec = importlib.util.spec_from_file_location("astravar_signing_policy", helper_path)
        policy = importlib.util.module_from_spec(spec)
        sys.modules[spec.name] = policy
        spec.loader.exec_module(policy)
    policy.clean_head(src, a.source)
    evidence = get_qa_receipt(policy.REPOSITORY, a.source, a.run, a.artifact, a.archive_sha256, policy)
    with tempfile.TemporaryDirectory() as work:
        work = Path(work)
        archive, apk = work / "original-native-qa.zip", work / "tested-ci.apk"
        if a.archive_file is None:
            with archive.open("xb") as out:
                run_gh(f"repos/{policy.REPOSITORY}/actions/artifacts/{a.artifact}/zip", out)
        else:
            check(a.archive_file.is_file() and not a.archive_file.is_symlink(), "Expected a regular original QA ZIP")
            archive = a.archive_file.resolve(strict=True)
        entry = "app/build/outputs/apk/release/app-release.apk"
        checksum = policy.extract_tested_apk(archive, a.archive_sha256, entry, "validation/SOURCE_COMMIT.txt", a.source, apk)
        scope = verify_native_coverage(archive, a.source, policy)
        sdk = Path(os.environ["ANDROID_HOME"])
        tools = sdk / "build-tools/35.0.0"
        signature = policy.signature_info(policy.run([tools / "apksigner", "verify", "--verbose", "--print-certs", apk]))
        policy.input_signature_policy(signature)
        check(signature["certificate_sha256"] != policy.OWNER_CERT, "Expected a disposable CI input")
        levels = policy.check_sdk_levels(policy.run([tools / "aapt", "dump", "badging", apk]))
        inspector, inspector_hashes = policy.trusted_inspector(src, a.source, work)
        inspection = inspector.inspect(apk, sdk, policy.PACKAGE, "4.1.0", 59, False)
        check(inspection["apk_sha256"] == checksum and inspection["debuggable"] is False, "Optimized input required")
        manifest = payload_digest(apk, policy)
        policy.clean_head(src, a.source)
        check(policy.digest(archive) == a.archive_sha256, "Input archive changed during verification")
    a.output.mkdir(mode=0o700, exist_ok=False)
    write_receipt(a.output / "ci-evidence.json", evidence)
    write_receipt(a.output / "tested-input.json", {
        "sourceCommit": a.source, "archiveSha256": a.archive_sha256, "archiveEntry": entry,
        "testedApkSha256": checksum, "package": inspection["package"], "versionName": inspection["version_name"],
        "versionCode": inspection["version_code"], "sdk": levels, "signature": signature,
        "sixteenKiBAligned": inspection["alignment_16kb_verified"], "inspectorSha256": inspector_hashes,
        "sourceSigningHelperSha256": hashlib.sha256(helper_bytes).hexdigest(),
        "apkPayload": manifest, "nativeChecks": scope,
        "ownerSigningPerformed": False, "ownerSigningMaterialAccessed": False,
    })
    print("Verified the native source, package, two release gates, and exact digest " + checksum)


if __name__ == "__main__":
    os.umask(0o077)
    main()
