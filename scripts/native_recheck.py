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

BASE_COMMIT = "55a6b2db8065152606a421ca6e3f7a5b46da55c1"
BASE_TREE = "cfa8022812ba104db55baeaa725e0c9024d93e5e"
RUN_ID = 39254633105
SOURCE_JOB_ID = 118066694474
SOURCE_BUILD_STEP = "Build fresh baseline and migration APKs with one disposable CI signer"
SOURCE_NATIVE_STEP = "Native update, complete archive recovery and mapped campaign interfaces"
ARTIFACT_ID = 12187774293
ARTIFACT_NAME = "Astravar-2.1-migration-native-source"
ARTIFACT_SHA256 = "39a5b21c1610ab18393dba0eaa0571cdc656e2aa2df4dc14e41d3e0649cfab18"
ARTIFACT_BYTES = 43312032
CI_CERTIFICATE = "d20336a85953ee13a5f8566755dc1a0653440a55d04d56e6aee8051bfd18d77e"
APK_HASHES = {
    "Astravar-2.1-CI-ONLY-code24.apk": "9894fc4a262d0fb77ad785b0ba2de952be8d59c46bd56348b8ecf192af9c9493",
    "Astravar-2.1-CI-ONLY-code31.apk": "00f3203df318a38b520bc41e58a728f8f262961d5ba2e2fb1ac325ff08c45b8d",
}
MAPPING_ARTIFACT_PATH = "app/build/outputs/mapping/release/mapping.txt"
MAPPING_SHA256 = "d01ace9fee7d2307779af4b24c1cce3e1e6346b707f36b59a9f8ad5079ab637f"
ALLOWED_FILES = {
    ".github/workflows/android-2.1-native-recheck.yml",
    "scripts/native_recheck.py",
    "scripts/ci-player-first-emulator.sh",
    "app/src/test/java/com/yazsras/astravar/ui/RebuildVisualAcceptanceTest.kt",
    "scripts/tests/test_native_recheck.py",
    "docs/ASTRAVAR_2_1_NATIVE_RECHECK.md",
    "overlay-target/src/androidTest/java/com/yazsras/astravar/OverlayInstrumentationTest.kt",
    "overlay-target/src/androidTest/java/com/yazsras/astravar/UpdatePreservationInstrumentationTest.kt",
}


# Preserve the historical code31 workflow/default pins. The new profile can
# reuse only the independently inspected code32 build and this exact harness delta.
VERSION_CODE = 31
STRICT_CHANGED_FILES = False
PROFILE_NAME = "code31"
SOURCE_FAILURE_LIMITATION = "Original run stopped at the overlay fixture; importer acceptance requires this new native recheck."
CODE32_PROFILE = {
    "BASE_COMMIT": "80e5dd2dc6645338ece59a7bb2c11cba0a3f7c06",
    "BASE_TREE": "f957c7ac94f68d611cec02eaa85a3d8ed6456661",
    "RUN_ID": 39270984985,
    "SOURCE_JOB_ID": 118122894843,
    "ARTIFACT_ID": 12196741246,
    "ARTIFACT_NAME": "Astravar-2.1-migration-native-source",
    "ARTIFACT_SHA256": "6810561146a964a905b842c9cf64d5d2c85e8d20d47f7ee0559edae25a6d6132",
    "ARTIFACT_BYTES": 44824691,
    "CI_CERTIFICATE": "50ea42fd9bf86bc444039ea8ff35aeb6ed1e75e4410fd65658dbaa4d5624717f",
    "APK_HASHES": {
        "Astravar-2.1-CI-ONLY-code24.apk": "978182dbc1c3938b05aaa6247b88ae4b0a6947b0f64b7fecf62a479c2fcde75e",
        "Astravar-2.1-CI-ONLY-code32.apk": "7afff785ed1b722f37426ea1d9b9a753493b7acb5844b1add6074b9d0a6be7d4",
    },
    "MAPPING_ARTIFACT_PATH": "app/build/outputs/mapping/release/mapping.txt",
    "MAPPING_SHA256": "942fd19dd90b10e1cd79c42722af858fe50e4b908403c9662aa4414e961caa1b",
    "ALLOWED_FILES": {
        "overlay-target/src/androidTest/java/com/yazsras/astravar/CampaignImportInstrumentationTest.kt",
        "scripts/native_recheck.py",
        "scripts/tests/test_native_recheck.py",
        ".github/workflows/android-2.1-native-recheck32.yml",
        ".github/workflows/android-2.1.yml",
        "docs/ASTRAVAR_2_1_NATIVE_RECHECK_CODE32.md",
    },
    "VERSION_CODE": 32,
    "STRICT_CHANGED_FILES": True,
    "PROFILE_NAME": "code32",
    "SOURCE_FAILURE_LIMITATION": "Original run verified the full second backup but its offscreen status was not observed; all native and typed acceptance requires this new strict recheck.",
}
CODE31_PROFILE = {name: globals()[name] for name in CODE32_PROFILE}


CODE33_PROFILE = {
    "BASE_COMMIT": "f35e18c83b07cdb5dd4f73925d110abd68812390",
    "BASE_TREE": "337f10402b53b08e87c7748da8f2b103e3e280f7",
    "RUN_ID": 39301833455,
    "SOURCE_JOB_ID": 118228773043,
    "ARTIFACT_ID": 12212558254,
    "ARTIFACT_NAME": "Astravar-2.1-migration-native-source",
    "ARTIFACT_SHA256": "f41d163eb7597b267a2109b25d0981c3c4fbaf98d4760ea575d444b24e16d8cf",
    "ARTIFACT_BYTES": 48981256,
    "CI_CERTIFICATE": "3509b880d0214fc87aacbd3b78b796cccf7283395a93aacd32c7265c09aa1f54",
    "APK_HASHES": {
        "Astravar-2.1-CI-ONLY-code24.apk": "defb8d3b2680dfc261ee3d950fb2ecf6f91298116f402ecf5b5ef4caee6c83b2",
        "Astravar-2.1-CI-ONLY-code33.apk": "0c2d9fa2ec613653ae428a1a3cddabaf961b3307c74b18936791e203241b2bac",
    },
    "MAPPING_ARTIFACT_PATH": "app/build/outputs/mapping/release/mapping.txt",
    "MAPPING_SHA256": "9ce9d379b3cc694a30ba5c6a77cf5d00579a8fdd4cf7a31cba64865c3439b903",
    "ALLOWED_FILES": {
        "overlay-target/src/androidTest/java/com/yazsras/astravar/CampaignImportInstrumentationTest.kt",
        "scripts/native_recheck.py",
        "scripts/tests/test_native_recheck.py",
        ".github/workflows/android-2.1-native-recheck33.yml",
        ".github/workflows/android-2.1.yml",
        "docs/ASTRAVAR_2_1_NATIVE_RECHECK_CODE33.md",
    },
    "VERSION_CODE": 33,
    "STRICT_CHANGED_FILES": True,
    "PROFILE_NAME": "code33",
    "SOURCE_FAILURE_LIMITATION": "Original run passed the nine native scenarios, then typed companion-header observation stopped before the remaining typed views and recovery; all eleven checks require this new strict recheck.",
}


def configure_profile(name):
    profiles = {"code31": CODE31_PROFILE, "code32": CODE32_PROFILE, "code33": CODE33_PROFILE}
    if name not in profiles:
        raise ValueError("Only the pinned code31, code32 or code33 profile is supported")
    globals().update(profiles[name])


def run_git(repo, *args):
    return subprocess.check_output(["git", "-C", str(repo), *args], stderr=subprocess.STDOUT)


def allowed_path(path):
    return path in ALLOWED_FILES


def source_guard(repo, base_commit=None, base_tree=None):
    """Use final tree differences, including removals/modes; reject dirty source."""
    base_commit = BASE_COMMIT if base_commit is None else base_commit
    base_tree = BASE_TREE if base_tree is None else base_tree
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
    if STRICT_CHANGED_FILES and set(paths) != ALLOWED_FILES:
        raise ValueError(f"Native {PROFILE_NAME} recheck requires its exact reviewed harness changes; missing: " + ", ".join(sorted(ALLOWED_FILES - set(paths))))
    return {
        "profile": PROFILE_NAME, "reused_product_version_code": VERSION_CODE,
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


def verify_source_run(run, jobs):
    """A known fixture failure must not hide incomplete or failed product builds."""
    expected = {"id": RUN_ID, "head_sha": BASE_COMMIT, "status": "completed",
                "conclusion": "failure", "run_attempt": 1,
                "path": ".github/workflows/android-2.1-migration-native.yml",
                "head_branch": "feat/astravar-2.1-player-first"}
    if any(run.get(k) != v for k, v in expected.items()):
        raise ValueError("Pinned source run is incomplete or has different identity/outcome")
    source_jobs = jobs.get("jobs", [])
    if len(source_jobs) != 1:
        raise ValueError("Pinned source run must contain its one completed build/native job")
    job = source_jobs[0]
    expected_job = {"id": SOURCE_JOB_ID, "run_id": RUN_ID, "head_sha": BASE_COMMIT,
                    "status": "completed", "conclusion": "failure", "name": "migration-native"}
    if any(job.get(k) != v for k, v in expected_job.items()):
        raise ValueError("Pinned source job is incomplete or has different identity/outcome")
    steps = job.get("steps", [])
    builds = [step for step in steps if step.get("name") == SOURCE_BUILD_STEP]
    if len(builds) != 1 or builds[0].get("status") != "completed" or builds[0].get("conclusion") != "success":
        raise ValueError("Pinned product build/inspection step did not complete successfully")
    failures = [step.get("name") for step in steps if step.get("conclusion") != "success"]
    if any(step.get("status") != "completed" for step in steps) or failures != [SOURCE_NATIVE_STEP]:
        raise ValueError("Pinned source run has failures beyond its recorded native fixture check")
    return {"source_run_id": RUN_ID, "source_job_id": SOURCE_JOB_ID,
            "source_run_conclusion": "failure", "product_build_step_conclusion": "success",
            "recorded_failed_step": SOURCE_NATIVE_STEP,
            "limitation": SOURCE_FAILURE_LIMITATION}


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
        source = json.loads(z.read("validation/fresh-native-source-proof.json"))
        expected = {"sourceCommit": BASE_COMMIT, "sourceTree": BASE_TREE,
                    "ownerSigningUsed": False, "realCampaignDataUsed": False}
        if any(source.get(k) != v for k, v in expected.items()):
            raise ValueError("Artifact source tree or private-data/signing scope differs")
        payloads = {name: z.read("validation/" + name) for name in APK_HASHES}
        for name, data in payloads.items():
            if hashlib.sha256(data).hexdigest() != APK_HASHES[name]:
                raise ValueError("Pinned product APK checksum mismatch: " + name)
        mapping = z.read(MAPPING_ARTIFACT_PATH)
        if hashlib.sha256(mapping).hexdigest() != MAPPING_SHA256:
            raise ValueError("Pinned product R8 mapping checksum mismatch")
        payloads["mapping.txt"] = mapping
        return payloads


def verify_product_inspection(info, code):
    expected = {"package": "com.yazsras.astravar", "version_code": code, "version_name": "2.1.0",
                "label": "Astravar", "certificate_sha256": CI_CERTIFICATE, "debuggable": False,
                "owner_signature_verified": False, "launcher_artwork_bytes_verified": True,
                "alignment_16kb_verified": True,
                "apk_sha256": APK_HASHES[f"Astravar-2.1-CI-ONLY-code{code}.apk"]}
    if any(info.get(k) != v for k, v in expected.items()):
        raise ValueError(f"Reused product APK{code} package/version/signature/artwork is not the pinned CI build")


def prepare(repo, archive, metadata_path, source_run_path, source_jobs_path, sdk, output):
    proof = source_guard(repo)
    metadata = json.loads(metadata_path.read_text())
    verify_artifact_metadata(metadata)
    run = json.loads(source_run_path.read_text())
    jobs = json.loads(source_jobs_path.read_text())
    run_proof = verify_source_run(run, jobs)
    payloads = verified_archive_payloads(archive)
    if output.exists():
        raise ValueError("Evidence directory already exists; refusing to mix recheck runs")
    # Write only known APKs, never extract arbitrary archive paths or old failures.
    output.mkdir(parents=True)
    from inspect_release_apk import inspect
    for code in (24, VERSION_CODE):
        name = f"Astravar-2.1-CI-ONLY-code{code}.apk"
        apk = output / name
        apk.write_bytes(payloads[name])
        info = inspect(apk, sdk, "com.yazsras.astravar", "2.1.0", code)
        verify_product_inspection(info, code)
        (output / f"release-code{code}.json").write_text(json.dumps(info, indent=2) + "\n")
    (output / "reused-product-mapping.txt").write_bytes(payloads["mapping.txt"])
    proof.update(reused_product_mapping_sha256=MAPPING_SHA256, reused_artifact_id=ARTIFACT_ID, reused_run_id=RUN_ID, reused_artifact_sha256=ARTIFACT_SHA256,
                 reused_product_certificate_sha256=CI_CERTIFICATE, owner_signed=False)
    (output / "native-recheck-source.json").write_text(json.dumps(proof, indent=2) + "\n")
    (output / "reused-artifact-metadata.json").write_text(json.dumps(metadata, indent=2) + "\n")
    (output / "reused-source-run.json").write_text(json.dumps(run, indent=2) + "\n")
    (output / "reused-source-jobs.json").write_text(json.dumps(jobs, indent=2) + "\n")
    (output / "reused-source-build-proof.json").write_text(json.dumps(run_proof, indent=2) + "\n")
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
    parser.add_argument("--profile", choices=["code31", "code32", "code33"], default="code31")
    parser.add_argument("--repo", type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument("--archive", type=Path)
    parser.add_argument("--artifact-metadata", type=Path)
    parser.add_argument("--source-run-metadata", type=Path)
    parser.add_argument("--source-jobs-metadata", type=Path)
    parser.add_argument("--sdk", type=Path, default=os.environ.get("ANDROID_HOME"))
    parser.add_argument("--output", type=Path, default=Path("validation"))
    parser.add_argument("--database", type=Path)
    parser.add_argument("--manifest", type=Path)
    parser.add_argument("--fixture", type=Path)
    parser.add_argument("--rolled-back", action="store_true")
    parser.add_argument("--synthetic-demo", action="store_true")
    parser.add_argument("--expected-room-schema", type=int, choices=(4, 5, 6), default=4)
    args = parser.parse_args()
    configure_profile(args.profile)
    if args.command == "guard":
        result = source_guard(args.repo)
    elif args.command == "prepare":
        if not all((args.archive, args.artifact_metadata, args.source_run_metadata, args.source_jobs_metadata, args.sdk)):
            parser.error("prepare requires --archive, --artifact-metadata, --source-run-metadata, --source-jobs-metadata and --sdk/ANDROID_HOME")
        result = prepare(args.repo, args.archive, args.artifact_metadata, args.source_run_metadata,
                         args.source_jobs_metadata, args.sdk, args.output)
    elif args.command == "verify-targets":
        if not args.sdk:
            parser.error("verify-targets requires --sdk/ANDROID_HOME")
        result = verify_targets(args.repo, args.sdk, args.output)
    elif args.command == "snapshot-mapped":
        if not args.database or args.output == Path("validation"):
            parser.error("snapshot-mapped requires --database and a JSON --output path")
        helper = runpy.run_path(str(Path(__file__).with_name("native-campaign-import-readback.py")))
        result = helper["run"](argparse.Namespace(action="snapshot", database=args.database,
                                                  manifest=args.output, synthetic_demo=args.synthetic_demo,
                                                  expected_room_schema=args.expected_room_schema))
    else:
        if not args.database or not args.manifest or not args.fixture or args.output == Path("validation"):
            parser.error("verify-mapped requires --database, --manifest, --fixture and a JSON --output path")
        helper = runpy.run_path(str(Path(__file__).with_name("native-campaign-import-readback.py")))
        result = helper["verify_mapped"](args.database, args.manifest, args.fixture,
                                         rolled_back=args.rolled_back, synthetic_demo=args.synthetic_demo,
                                         expected_room_schema=args.expected_room_schema)
        args.output.write_text(json.dumps(result, indent=2) + "\n")
    print(json.dumps(result, indent=2))


if __name__ == "__main__":
    main()
