#!/usr/bin/env python3
"""Explicit, source-pinned GitHub recipient/sender/receiver; no signing capability."""
import argparse
import base64
from datetime import datetime
import hashlib
import io
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import zipfile

import owner_upgrade_envelope as E
from owner_upgrade_diagnostics import failure_id

CRITICAL_STEPS = {
    ".github/workflows/android.yml": {
        "Require a fresh Genesis baseline and candidate pair",
        "Build native APKs; full regressions stay in the exact-head Reforge gate",
        "Ordered release UI, import, recovery and in-place update on Android 16",
    },
    ".github/workflows/astravar-3.0.yml": {
        "Authenticated bridge protocol regression",
        "Verify migration boundaries and retained provenance",
        "Rules and debug Room/Compose regressions",
        "Release regressions, lint and debug APK",
        "Inspect actual QA package",
        "Index executed evidence even on failure",
    },
}
ARTIFACT_LIMIT = 768 * 1024 * 1024
ARTIFACT_PATHS = {
    "observer": "overlay-target/build/outputs/apk/debug/overlay-target-debug.apk",
    "observer-test": "overlay-target/build/outputs/apk/androidTest/debug/overlay-target-debug-androidTest.apk",
    "reference": "app/build/outputs/apk/release/app-release.apk",
}


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        return None


class GitHub:
    def __init__(self, token=None):
        self.token = token if token is not None else os.environ.get("GH_TOKEN", os.environ.get("GITHUB_TOKEN", ""))
        self.opener = urllib.request.build_opener(NoRedirect())

    def request(self, path):
        E.require(path.startswith("/") and ".." not in path, "Invalid API path")
        headers = {"Accept": "application/vnd.github+json", "X-GitHub-Api-Version": "2022-11-28",
                   "User-Agent": "Astravar-owner-upgrade-verifier"}
        if self.token:
            headers["Authorization"] = "Bearer " + self.token
        return self.opener.open(urllib.request.Request("https://api.github.com" + path, headers=headers), timeout=30)

    def get(self, suffix, limit=E.MAX_JSON):
        with self.request(f"/repos/{E.REPOSITORY}/{suffix}") as response:
            data = response.read(limit + 1)
        E.require(len(data) <= limit, "API JSON exceeds bound")
        # Git blob base64 may exceed the protocol JSON bound, but remains bounded here.
        if limit > E.MAX_JSON:
            return json.loads(data)
        return E.strict_json(data)

    def artifact_zip(self, artifact_id, target, expected_digest, max_bytes=ARTIFACT_LIMIT):
        E.positive(artifact_id); E.hex_value(expected_digest)
        try:
            response = self.request(f"/repos/{E.REPOSITORY}/actions/artifacts/{artifact_id}/zip")
        except urllib.error.HTTPError as error:
            E.require(error.code in (301, 302, 303, 307), "Artifact download redirect unavailable")
            location = error.headers.get("Location", "")
            parsed = urllib.parse.urlsplit(location)
            host = parsed.hostname or ""
            E.require(parsed.scheme == "https" and not parsed.username and not parsed.password and
                      (host.endswith(".blob.core.windows.net") or host.endswith(".githubusercontent.com") or
                       host.endswith(".amazonaws.com")), "Untrusted artifact redirect")
            # Never forward GitHub Authorization to the signed storage URL.
            response = self.opener.open(urllib.request.Request(location), timeout=30)
        total = 0; sha = hashlib.sha256()
        with response, target.open("xb") as output:
            os.chmod(target, 0o600)
            while True:
                block = response.read(1024 * 1024)
                if not block:
                    break
                total += len(block); E.require(total <= max_bytes, "Artifact archive exceeds bound")
                sha.update(block); output.write(block)
        E.require(sha.hexdigest() == expected_digest, "Artifact archive digest mismatch")


def timestamp(value):
    return datetime.fromisoformat(value.replace("Z", "+00:00")).timestamp()


def check_run(info, run_id, attempt, source, workflow, active=False):
    E.require(info.get("id") == run_id and info.get("run_attempt") == attempt and
              info.get("head_sha") == source and info.get("path", "").split("@")[0] == workflow,
              "Workflow run/attempt/source/path mismatch")
    E.require(info.get("repository", {}).get("full_name") == E.REPOSITORY and
              info.get("head_repository", {}).get("full_name") == E.REPOSITORY,
              "Unexpected or forked workflow repository")
    if active:
        E.require(info.get("status") == "in_progress" and info.get("conclusion") is None,
                  "Recipient run is not currently in progress")
    else:
        E.require(info.get("status") == "completed" and info.get("conclusion") == "success",
                  "Required source workflow did not pass")


def fetch_run(api, run_id, attempt, source, workflow, active=False):
    # Both current and exact attempt must match, so a rerun invalidates old envelopes.
    current = api.get(f"actions/runs/{run_id}")
    check_run(current, run_id, attempt, source, workflow, active)
    exact = api.get(f"actions/runs/{run_id}/attempts/{attempt}")
    check_run(exact, run_id, attempt, source, workflow, active)
    return exact


def check_artifact(info, artifact_id, name, expected_digest, run):
    E.require(info.get("id") == artifact_id and info.get("name") == name and info.get("expired") is False,
              "Artifact identity or expiration mismatch")
    E.require(info.get("digest") == "sha256:" + expected_digest, "Artifact API digest mismatch")
    provenance = info.get("workflow_run", {})
    E.require(provenance.get("id") == run["id"] and provenance.get("head_sha") == run["head_sha"],
              "Artifact source run mismatch")
    # Artifacts from older attempts can remain attached to the same run ID.
    created = timestamp(info["created_at"])
    E.require(timestamp(run["run_started_at"]) <= created <= (time.time() + 60 if run.get("status") == "in_progress" else timestamp(run["updated_at"])),
              "Artifact creation is outside the exact run attempt")
    E.require(type(info.get("size_in_bytes")) is int and 0 < info["size_in_bytes"] <= ARTIFACT_LIMIT,
              "Artifact size outside bound")


def check_jobs(info, gate):
    E.require(info.get("total_count") == 1 and len(info.get("jobs", [])) == 1,
              "Expected exactly one source verification job")
    job = info["jobs"][0]
    E.require(job.get("name") == "verify" and job.get("status") == "completed" and
              job.get("conclusion") == "success" and job.get("head_sha") == gate["source_sha"] and
              job.get("run_id") == gate["run_id"], "Source verification job skipped or failed")
    names = [step.get("name") for step in job.get("steps", [])]
    for required in CRITICAL_STEPS[gate["workflow_path"]]:
        E.require(names.count(required) == 1, "Missing or ambiguous source verification step")
        step = next(step for step in job["steps"] if step.get("name") == required)
        E.require(step.get("status") == "completed" and step.get("conclusion") == "success",
                  "Required source verification step skipped or failed")


def validate_gates(api, config):
    E.validate_config(config)
    # Original owner49 was built at7bb03; unchanged native baseline is3543.
    # Verify both product trees from immutable Git objects, never invent provenance.
    for source in (config["baseline_source"], config["baseline_equivalence"]["frozen_source"]):
        commit = api.get("git/commits/" + source)
        tree = api.get("git/trees/" + commit["tree"]["sha"])
        entries = {e["path"]: e for e in tree["tree"]}
        for name in ("app", "rules"):
            E.require(entries.get(name, {}).get("type") == "tree" and
                      entries[name]["sha"] == config["baseline_equivalence"][name + "_tree"],
                      "Original owner baseline differs from frozen native product trees")
    runs = {}
    for gate in config["gates"]:
        runs[gate["run_id"]] = fetch_run(api, gate["run_id"], gate["run_attempt"],
                                       gate["source_sha"], gate["workflow_path"])
        check_jobs(api.get(f"actions/runs/{gate['run_id']}/attempts/{gate['run_attempt']}/jobs?per_page=100"), gate)
    expected = config["observer_artifact"]
    artifact = api.get(f"actions/artifacts/{expected['artifact_id']}")
    check_artifact(artifact, expected["artifact_id"], expected["name"], expected["digest"], runs[expected["run_id"]])
    return artifact


def runtime_context(config):
    E.require(os.environ.get("GITHUB_ACTIONS") == "true" and
              os.environ.get("GITHUB_REPOSITORY") == E.REPOSITORY, "Receiver requires the intended GitHub runner")
    return E.binding(config, int(os.environ["GITHUB_RUN_ID"]), int(os.environ["GITHUB_RUN_ATTEMPT"]),
                     os.environ["GITHUB_SHA"])


def under_runner(path):
    root = Path(os.environ["RUNNER_TEMP"]).resolve()
    resolved = path.resolve()
    E.require(resolved.is_relative_to(root) and resolved != root and not path.is_symlink(),
              "Sensitive output must be a private child of RUNNER_TEMP")
    return resolved


def verify_apk(path, expected, sdk):
    E.check_file(path, expected)
    tools = sdk / "build-tools/35.0.0"
    def run(tool, *args):
        result = subprocess.run([str(tools / tool), *args, str(path)], capture_output=True, text=True, timeout=45)
        if tool == "apksigner":
            E.require(result.returncode == 0, "APK signature inspection failed")
        elif tool == "aapt":
            E.require(result.returncode == 0, "APK metadata inspection failed")
        else:
            E.require(result.returncode == 0, "APK alignment inspection failed")
        return result.stdout
    signature = run("apksigner", "verify", "--verbose", "--print-certs")
    certs = re.findall(r"^Signer #\d+ certificate SHA-256 digest: ([0-9a-fA-F]{64})$", signature, re.MULTILINE)
    counts = re.findall(r"^Number of signers: (\d+)$", signature, re.MULTILINE)
    E.require(len(certs) == 1 and certs[0].lower() == expected["certificate_sha256"] and counts == ["1"],
              "APK must have exactly the pinned signer")
    # A signing lineage is not an authorized replacement for the original identity.
    E.require(not re.search(r"(?:past|lineage).*certificate", signature, re.IGNORECASE), "Unexpected signer lineage")
    badging = run("aapt", "dump", "badging")
    packages = re.findall(r"^package: name='([^']+)' versionCode='(\d+)'", badging, re.MULTILINE)
    E.require(packages == [(expected["package"], str(expected["version_code"]))], "APK package/version mismatch")
    if expected["package"] == "com.yazsras.astravar":
        E.require("application-debuggable" not in badging.splitlines(), "Owner APK is debuggable")
        E.require("sdkVersion:'26'" in badging.splitlines() and "targetSdkVersion:'36'" in badging.splitlines(),
                  "Owner APK SDK identity mismatch")
        run("zipalign", "-c", "-P", "16", "4")
        with zipfile.ZipFile(path) as archive:
            entry = archive.getinfo("META-INF/version-control-info.textproto")
            E.require(entry.file_size < 8192, "Source provenance entry exceeds bound")
            provenance = archive.read(entry).decode("utf-8")
        revisions = re.findall(r'revision:\s*"([0-9a-f]{40})"', provenance)
        systems = re.findall(r'system:\s*(?:"GIT"|GIT)', provenance)
        E.require(revisions == [expected["source_sha"]] and len(systems) == 1,
                  "APK embedded Git provenance mismatch")
    return {key: expected[key] for key in ("sha256", "package", "version_code", "certificate_sha256")}


def zip_payload(path):
    result = {}; total = 0
    with zipfile.ZipFile(path) as archive:
        E.require(len(archive.infolist()) <= 50000, "APK has too many entries")
        for entry in archive.infolist():
            E.require(entry.filename not in result, "Duplicate APK ZIP entry")
            if entry.is_dir():
                continue
            E.require(not entry.flag_bits & 1 and entry.file_size <= E.MAX_APK, "Invalid APK ZIP entry")
            if re.fullmatch(r"META-INF/(?:MANIFEST\.MF|[^/]+\.(?:SF|RSA|DSA|EC))", entry.filename, re.IGNORECASE):
                continue
            total += entry.file_size; E.require(total <= 512 * 1024 * 1024, "APK expanded payload exceeds bound")
            result[entry.filename] = E.digest(archive.read(entry))
    E.require("AndroidManifest.xml" in result and "classes.dex" in result, "Missing APK executable payload")
    return result


def extract_native_artifact(archive_path, private, config):
    expected = config["observer_artifact"]
    outputs = {}
    with zipfile.ZipFile(archive_path) as archive:
        names = archive.namelist()
        E.require(len(names) == len(set(names)) <= 50000, "Artifact contains duplicate or excessive entries")
        for role, name in ARTIFACT_PATHS.items():
            entry = archive.getinfo(name)
            pin = ({"size": expected["reference_apk_size"], "sha256": expected["reference_apk_sha256"]}
                   if role == "reference" else config["apks"][role])
            E.require(entry.file_size == pin["size"] <= E.MAX_APK and not entry.flag_bits & 1,
                      "Artifact APK byte count mismatch")
            data = archive.read(entry); E.require(E.digest(data) == pin["sha256"], "Artifact APK digest mismatch")
            target = private / (role + ".apk"); E.private_write(target, data); outputs[role] = target
    return outputs


def fetch_transfer(api, context, output, deadline):
    ref = urllib.parse.quote(context["transfer_branch"], safe="/")
    while time.monotonic() < deadline:
        try:
            found = api.get("git/ref/heads/" + ref)
            break
        except urllib.error.HTTPError as error:
            if error.code != 404:
                E.require(False, "Transfer ref lookup failed")
            time.sleep(15)
    else:
        E.require(False, "Bounded transfer wait expired")
    E.require(found.get("object", {}).get("type") == "commit", "Transfer ref is not a commit")
    commit = found["object"]["sha"]; E.hex_value(commit, 40)
    commit_info = api.get("git/commits/" + commit)
    tree_sha = commit_info["tree"]["sha"]; E.hex_value(tree_sha, 40)
    tree = api.get("git/trees/" + tree_sha + "?recursive=1")
    E.require(tree.get("truncated") is False, "Transfer tree truncated")
    entries = tree.get("tree", [])
    E.require(len(entries) <= E.MAX_CHUNKS + 2, "Transfer tree exceeds bound")
    blobs = {}
    for entry in entries:
        if entry["type"] == "tree":
            E.require(entry["path"] == "chunks" and entry["mode"] == "040000", "Unexpected transfer tree")
            continue
        E.require(entry["type"] == "blob" and entry["mode"] == "100644" and entry["path"] not in blobs,
                  "Transfer requires unique regular blobs")
        E.require(type(entry["size"]) is int and 0 < entry["size"] <= E.MAX_JSON, "Transfer blob exceeds bound")
        blobs[entry["path"]] = entry
    def read_blob(entry):
        E.hex_value(entry["sha"], 40)
        blob = api.get("git/blobs/" + entry["sha"], limit=2 * E.MAX_JSON)
        E.require(blob.get("encoding") == "base64", "Unexpected Git blob encoding")
        data = base64.b64decode("".join(blob["content"].split()), validate=True)
        E.require(len(data) == entry["size"], "Git blob size mismatch")
        actual = hashlib.sha1(b"blob " + str(len(data)).encode() + b"\0" + data).hexdigest()
        E.require(actual == entry["sha"] == blob.get("sha"), "Git blob identity mismatch")
        return data
    E.require("manifest.json" in blobs, "Missing envelope manifest")
    raw = read_blob(blobs["manifest.json"]); manifest = E.strict_json(raw)
    E.require(set(blobs) == E.manifest_paths(manifest), "Transfer tree contains extra or missing publication files")
    E.require(manifest["binding"] == context, "Transfer is bound to another run/attempt")
    output.mkdir(mode=0o700); (output / "chunks").mkdir(mode=0o700)
    E.private_write(output / "manifest.json", raw)
    for record in manifest["files"]:
        for chunk in record["chunks"]:
            E.require(time.monotonic() < deadline, "Transfer download exceeded bound")
            entry = blobs[chunk["path"]]
            E.require(entry["size"] == chunk["size"], "Chunk tree size mismatch")
            data = read_blob(entry); E.require(E.digest(data) == chunk["sha256"], "Chunk transport digest mismatch")
            E.private_write(output / chunk["path"], data)
    return commit


def check_receiver_jobs(info, context):
    E.require(info.get("total_count") == 1 and len(info.get("jobs", [])) == 1,
              "Expected one active receiver job")
    job = info["jobs"][0]
    E.require(job.get("name") == "receive-and-verify" and job.get("run_id") == context["run_id"] and
              job.get("head_sha") == context["receiver_commit"] and job.get("status") == "in_progress",
              "Unexpected or inactive receiver job")
    for name in ("Verify live source gates and create ephemeral transport recipient",
                 "Publish only the public recipient descriptor"):
        steps = [step for step in job.get("steps", []) if step.get("name") == name]
        E.require(len(steps) == 1 and steps[0].get("status") == "completed" and
                  steps[0].get("conclusion") == "success", "Recipient preparation/upload step did not pass")


def recipient_from_archive(path, digest, artifact, run, context, artifact_id, attempt, jobs):
    E.require(path.stat().st_size <= E.MAX_JSON and E.digest(path.read_bytes()) == digest,
              "Recipient archive digest mismatch")
    name = f"owner-upgrade-recipient-{context['run_id']}-{context['run_attempt']}"
    check_run(run, context["run_id"], context["run_attempt"], context["receiver_commit"], E.WORKFLOW, active=True)
    check_run(attempt, context["run_id"], context["run_attempt"], context["receiver_commit"], E.WORKFLOW, active=True)
    check_receiver_jobs(jobs, context)
    check_artifact(artifact, artifact_id, name, digest, attempt)
    with zipfile.ZipFile(path) as archive:
        E.require(archive.namelist() == ["recipient.json"], "Recipient artifact must contain only public descriptor")
        entry = archive.getinfo("recipient.json")
        E.require(entry.file_size < 8192 and not entry.flag_bits & 1, "Invalid recipient descriptor size")
        recipient = E.strict_json(archive.read(entry))
    E.validate_recipient(recipient, context)
    E.require(recipient["created_at"] <= timestamp(artifact["created_at"]) < recipient["expires_at"],
              "Public artifact is outside recipient lifetime")
    return recipient


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("prepare", "send", "receive", "check"))
    parser.add_argument("--config", type=Path, default=Path(__file__).with_name("owner-upgrade-config.json"))
    parser.add_argument("--private", type=Path); parser.add_argument("--public", type=Path)
    parser.add_argument("--sdk", type=Path, default=os.environ.get("ANDROID_HOME"))
    parser.add_argument("--run-id", type=int); parser.add_argument("--attempt", type=int)
    parser.add_argument("--receiver-commit")
    parser.add_argument("--recipient-archive", type=Path)
    parser.add_argument("--recipient-artifact-info", type=Path)
    parser.add_argument("--recipient-run-info", type=Path)
    parser.add_argument("--recipient-attempt-info", type=Path)
    parser.add_argument("--recipient-jobs-info", type=Path)
    parser.add_argument("--recipient-artifact-sha256")
    parser.add_argument("--recipient-artifact-id", type=int)
    parser.add_argument("--baseline", type=Path); parser.add_argument("--candidate", type=Path)
    parser.add_argument("--output", type=Path); parser.add_argument("--chunk-size", type=int, default=E.CHUNK)
    args = parser.parse_args()
    config = E.validate_config(E.read_json(args.config))
    if args.command == "check":
        print("Enabled config is structurally valid; live source gates are not implied."); return
    if args.command == "send":
        context = E.binding(config, args.run_id, args.attempt, args.receiver_commit)
        # Inputs are the exact archive and API metadata fetched by the trusted
        # operator using the authenticated GitHub connector. No PAT is required
        # or accepted as a command-line argument. Digest comes from that API.
        recipient = recipient_from_archive(args.recipient_archive, args.recipient_artifact_sha256,
            E.read_json(args.recipient_artifact_info), E.read_json(args.recipient_run_info), context, args.recipient_artifact_id,
            E.read_json(args.recipient_attempt_info), E.read_json(args.recipient_jobs_info))
        for role in E.ROLES:
            verify_apk(getattr(args, role), config["apks"][role], args.sdk)
        E.encrypt(config, recipient, context, {role: getattr(args, role) for role in E.ROLES},
                  args.output, chunk_size=args.chunk_size)
        print("Ciphertext-only transfer prepared; nothing published."); return
    api = GitHub(); context = runtime_context(config)
    E.require(api.token, "Receiver requires its read-only ephemeral GitHub job token")
    fetch_run(api, context["run_id"], context["run_attempt"], context["receiver_commit"], E.WORKFLOW, active=True)
    validate_gates(api, config)
    private = under_runner(args.private); public = under_runner(args.public)
    E.require(private != public and not public.is_relative_to(private) and not private.is_relative_to(public),
              "Private and public directories must be separate")
    if args.command == "prepare":
        E.create_recipient(config, context["run_id"], context["run_attempt"], context["receiver_commit"], private, public)
        print("Public transport recipient prepared; private key stays on this runner."); return
    recipient = E.read_json(public / "recipient.json")
    E.validate_recipient(recipient, context)
    deadline = time.monotonic() + min(1500, recipient["expires_at"] - time.time())
    transfer_commit = fetch_transfer(api, context, private / "ciphertext", deadline)
    validate_gates(api, config)
    E.decrypt(config, recipient, context, private / "transport-private.pem", private / "ciphertext", private / "apks")
    apk_dir = private / "apks"
    expected = config["observer_artifact"]
    api.artifact_zip(expected["artifact_id"], private / "native-artifact.zip", expected["digest"])
    extract_native_artifact(private / "native-artifact.zip", apk_dir, config)
    inspected = {role: verify_apk(apk_dir / (role + ".apk"), config["apks"][role], args.sdk) for role in E.ALL_ROLES}
    E.require(zip_payload(apk_dir / "candidate.apk") == zip_payload(apk_dir / "reference.apk"),
              "Owner code50 differs from the exact verified native artifact payload")
    validate_gates(api, config)
    receipt = {"schema": 1, "validated": True, "candidate_source": config["candidate_source"],
               "baseline_source": config["baseline_source"], "owner_certificate_sha256": E.OWNER_CERT,
               "apks": inspected, "source_artifact": {"id": expected["artifact_id"], "digest": expected["digest"],
                   "run_id": expected["run_id"], "run_attempt": expected["run_attempt"]},
               "gates": config["gates"], "receiver": context, "transfer_commit": transfer_commit,
               "candidate_payload_matches_source_artifact": True}
    E.private_write(apk_dir / "apk-validation.json", E.canonical(receipt) + b"\n")
    E.private_write(public / "transport-result.json", E.canonical({
        "format": "astravar-owner-upgrade-transport-v1", "validated": True,
        "owner_signing_key_received": False, "native_installation_verified": False,
        "candidate_payload_matches_source_artifact": True, "receiver": context,
        "transfer_commit": transfer_commit, "candidate_source": config["candidate_source"],
        "baseline_source": config["baseline_source"], "baseline_equivalence": config["baseline_equivalence"],
        "owner_certificate_sha256": E.OWNER_CERT, "apks": inspected,
        "source_artifact": receipt["source_artifact"], "gates": config["gates"]}) + b"\n")
    print("Exact owner APKs and source artifact validated; native installation has not run.")


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        # Never dump private paths, signed download URLs, APK bytes or request credentials.
        print("Owner upgrade transfer failed: " + failure_id(error), file=sys.stderr)
        sys.exit(1)
