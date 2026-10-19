#!/usr/bin/env python3
"""Recheck native tests against one immutable, CI-only product build.

This is a harness repair aid. It cannot certify product changes or replace the
complete Android verification workflow. No owner signing material is used.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import runpy
import stat
import subprocess
import zipfile

BASE_COMMIT = "0e71338cf4ac016ccd0025b6a6750811479eb442"
BASE_TREE = "afede2cb1d07bd306e07df002a464ce0de2d52a9"
RUN_ID = 39189882687
ARTIFACT_ID = 12160001923
ARTIFACT_NAME = "Astravar-2.1-migration-native-source"
ARTIFACT_SHA256 = "2ea5ce750ee2e466616b3f8d65d9b554621b9ef5061416d0be322cb44ea9778b"
ARTIFACT_BYTES = 40102824
CI_CERTIFICATE = "b5d9d4e5549c0659806768b9c31e027585c19c350b8b99eed893b020b3b8b98b"
APK_HASHES = {
    "Astravar-2.1-CI-ONLY-code24.apk": "d7487719f6274037966917f773f581b4ce1a09aea8589cc3309fec828ccb47f7",
    "Astravar-2.1-CI-ONLY-code26.apk": "e79cdc612ef1285e31b87db3be05936d7b079b9d118b8b939fe288269a4cd31b",
}
ALLOWED_FILES = {
    ".github/workflows/android-2.1-native-recheck.yml",
    "scripts/native_recheck.py",
    "scripts/tests/test_native_recheck.py",
    "scripts/native-campaign-import-readback.py",
    "scripts/tests/test_native_campaign_import_readback.py",
    "docs/ASTRAVAR_2_1_NATIVE_RECHECK.md",
    # Controlled second-app keyboard/protection fixture, never the product app.
    "overlay-target/src/main/java/com/yazsras/astravar/target/TargetActivity.kt",
}


def run_git(repo, *args):
    return subprocess.check_output(["git", "-C", str(repo), *args], stderr=subprocess.STDOUT)


def allowed_path(path):
    parts = PurePosixPath(path).parts
    return (path in ALLOWED_FILES or
            (len(parts) > 3 and parts[:3] == ("overlay-target", "src", "androidTest")))


def source_guard(repo, base_commit=BASE_COMMIT, base_tree=BASE_TREE):
    """Use final tree differences, including removals/modes; reject dirty source."""
    if run_git(repo, "rev-parse", f"{base_commit}^{{tree}}").decode().strip() != base_tree:
        raise ValueError("Baseline tree does not match the verified product source")
    run_git(repo, "merge-base", "--is-ancestor", base_commit, "HEAD")
    if run_git(repo, "status", "--porcelain", "--untracked-files=all").strip():
        raise ValueError("Native recheck requires a clean committed checkout")
    paths = run_git(repo, "diff", "--name-only", "--no-renames", "-z", base_commit, "HEAD").decode().split("\0")
    paths = [p for p in paths if p]
    forbidden = [p for p in paths if not allowed_path(p)]
    if forbidden:
        raise ValueError("Product/source changes require full verification: " + ", ".join(forbidden))
    return {
        "test_source_commit": run_git(repo, "rev-parse", "HEAD").decode().strip(),
        "test_source_tree": run_git(repo, "rev-parse", "HEAD^{tree}").decode().strip(),
        "reused_product_source_commit": base_commit,
        "reused_product_source_tree": base_tree,
        "changed_paths": paths,
        "scope": "Native harness recheck only; full verification remains the product acceptance gate",
    }


def verify_artifact_metadata(metadata):
    workflow = metadata.get("workflow_run", {})
    expected = {"id": ARTIFACT_ID, "name": ARTIFACT_NAME, "size_in_bytes": ARTIFACT_BYTES,
                "digest": "sha256:" + ARTIFACT_SHA256, "expired": False}
    if any(metadata.get(k) != v for k, v in expected.items()):
        raise ValueError("Artifact ID/digest/size/expiry metadata differs from the pinned artifact")
    if workflow.get("id") != RUN_ID or workflow.get("head_sha") != BASE_COMMIT:
        raise ValueError("Artifact belongs to a different run or source commit")


def verified_archive_payloads(archive):
    if archive.stat().st_size != ARTIFACT_BYTES or hashlib.sha256(archive.read_bytes()).hexdigest() != ARTIFACT_SHA256:
        raise ValueError("Downloaded artifact ZIP checksum or size mismatch")
    with zipfile.ZipFile(archive) as z:
        seen = set()
        for entry in z.infolist():
            path = PurePosixPath(entry.filename)
            if (entry.filename in seen or path.is_absolute() or ".." in path.parts or
                    "\\" in entry.filename or stat.S_ISLNK(entry.external_attr >> 16)):
                raise ValueError("Unsafe or duplicate artifact ZIP entry")
            seen.add(entry.filename)
        if z.read("validation/SOURCE_COMMIT.txt").decode().strip() != BASE_COMMIT:
            raise ValueError("Artifact SOURCE_COMMIT differs from the pinned product source")
        payloads = {name: z.read("validation/" + name) for name in APK_HASHES}
        for name, data in payloads.items():
            if hashlib.sha256(data).hexdigest() != APK_HASHES[name]:
                raise ValueError("Pinned product APK checksum mismatch: " + name)
        return payloads


def verify_product_inspection(info, code):
    expected = {"package": "com.yazsras.astravar", "version_code": code, "version_name": "2.1.0",
                "label": "Astravar", "certificate_sha256": CI_CERTIFICATE, "debuggable": False,
                "owner_signature_verified": False, "launcher_artwork_bytes_verified": True,
                "alignment_16kb_verified": True,
                "apk_sha256": APK_HASHES[f"Astravar-2.1-CI-ONLY-code{code}.apk"]}
    if any(info.get(k) != v for k, v in expected.items()):
        raise ValueError(f"Reused product APK{code} package/version/signature/artwork is not the pinned CI build")


def prepare(repo, archive, metadata_path, sdk, output):
    proof = source_guard(repo)
    metadata = json.loads(metadata_path.read_text())
    verify_artifact_metadata(metadata)
    payloads = verified_archive_payloads(archive)
    if output.exists():
        raise ValueError("Evidence directory already exists; refusing to mix recheck runs")
    # Write only known APKs, never extract arbitrary archive paths or old failures.
    output.mkdir(parents=True)
    from inspect_release_apk import inspect
    for code in (24, 26):
        name = f"Astravar-2.1-CI-ONLY-code{code}.apk"
        apk = output / name
        apk.write_bytes(payloads[name])
        info = inspect(apk, sdk, "com.yazsras.astravar", "2.1.0", code)
        verify_product_inspection(info, code)
        (output / f"release-code{code}.json").write_text(json.dumps(info, indent=2) + "\n")
    proof.update(reused_artifact_id=ARTIFACT_ID, reused_run_id=RUN_ID, reused_artifact_sha256=ARTIFACT_SHA256,
                 reused_product_certificate_sha256=CI_CERTIFICATE, owner_signed=False)
    (output / "native-recheck-source.json").write_text(json.dumps(proof, indent=2) + "\n")
    (output / "reused-artifact-metadata.json").write_text(json.dumps(metadata, indent=2) + "\n")
    (output / "SOURCE_COMMIT.txt").write_text(BASE_COMMIT + "\n")
    (output / "NATIVE_TEST_SOURCE_COMMIT.txt").write_text(proof["test_source_commit"] + "\n")
    (output / "RECHECK_SCOPE.txt").write_text(
        "NATIVE HARNESS RECHECK ONLY. Product APKs are immutable CI-only builds from " + BASE_COMMIT +
        "; native test source is " + proof["test_source_commit"] + ".\n"
        "The immutable source run provides the product APKs; its recorded outcome is reported separately.\n"
        "This recheck does not rebuild or certify product changes, rerun full verification, prove owner signing, or publish a production release.\n"
        "Never install these disposable CI-signed APKs over an owner-signed app or uninstall existing apps to bypass a certificate mismatch.\n")
    return proof


def verify_targets(repo, sdk, output):
    result = {}
    for name, path, package in [
        ("target", "overlay-target/build/outputs/apk/debug/overlay-target-debug.apk", "com.yazsras.astravar.target"),
        ("instrumentation", "overlay-target/build/outputs/apk/androidTest/debug/overlay-target-debug-androidTest.apk", "com.yazsras.astravar.target.test"),
    ]:
        apk = repo / path
        tools = sdk / "build-tools/35.0.0"
        signature = subprocess.check_output([str(tools / "apksigner"), "verify", "--verbose", "--print-certs", str(apk)], text=True)
        certs = re.findall(r"Signer #\d+ certificate SHA-256 digest: ([0-9a-fA-F]+)", signature)
        badging = subprocess.check_output([str(tools / "aapt"), "dump", "badging", str(apk)], text=True)
        if len(certs) != 1 or not re.search(r"package: name='" + re.escape(package) + "'", badging):
            raise ValueError("Fresh native test APK identity or signature mismatch")
        result[name] = {"package": package, "certificate_sha256": certs[0].lower(),
                        "apk_sha256": hashlib.sha256(apk.read_bytes()).hexdigest()}
    if result["target"]["certificate_sha256"] != result["instrumentation"]["certificate_sha256"]:
        raise ValueError("Fresh target and instrumentation must share the new test signer")
    result["test_source_commit"] = run_git(repo, "rev-parse", "HEAD").decode().strip()
    output.write_text(json.dumps(result, indent=2) + "\n")
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=["guard", "prepare", "verify-targets", "snapshot-mapped", "verify-mapped"])
    parser.add_argument("--repo", type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument("--archive", type=Path)
    parser.add_argument("--artifact-metadata", type=Path)
    parser.add_argument("--sdk", type=Path, default=os.environ.get("ANDROID_HOME"))
    parser.add_argument("--output", type=Path, default=Path("validation"))
    parser.add_argument("--database", type=Path)
    parser.add_argument("--manifest", type=Path)
    parser.add_argument("--fixture", type=Path)
    parser.add_argument("--rolled-back", action="store_true")
    parser.add_argument("--synthetic-demo", action="store_true")
    args = parser.parse_args()
    if args.command == "guard":
        result = source_guard(args.repo)
    elif args.command == "prepare":
        if not args.archive or not args.artifact_metadata or not args.sdk:
            parser.error("prepare requires --archive, --artifact-metadata and --sdk/ANDROID_HOME")
        result = prepare(args.repo, args.archive, args.artifact_metadata, args.sdk, args.output)
    elif args.command == "verify-targets":
        if not args.sdk:
            parser.error("verify-targets requires --sdk/ANDROID_HOME")
        result = verify_targets(args.repo, args.sdk, args.output)
    elif args.command == "snapshot-mapped":
        if not args.database or args.output == Path("validation"):
            parser.error("snapshot-mapped requires --database and a JSON --output path")
        helper = runpy.run_path(str(Path(__file__).with_name("native-campaign-import-readback.py")))
        result = helper["run"](argparse.Namespace(action="snapshot", database=args.database,
                                                  manifest=args.output, synthetic_demo=args.synthetic_demo))
    else:
        if not args.database or not args.manifest or not args.fixture or args.output == Path("validation"):
            parser.error("verify-mapped requires --database, --manifest, --fixture and a JSON --output path")
        helper = runpy.run_path(str(Path(__file__).with_name("native-campaign-import-readback.py")))
        result = helper["verify_mapped"](args.database, args.manifest, args.fixture,
                                         rolled_back=args.rolled_back, synthetic_demo=args.synthetic_demo)
        args.output.write_text(json.dumps(result, indent=2) + "\n")
    print(json.dumps(result, indent=2))


if __name__ == "__main__":
    main()
