#!/usr/bin/env python3
"""Reuse fixed historical CI APK payloads with a fresh, disposable test signer.

The caller must restore/save this directory through a trusted exact-key cache.
Checksums detect corruption; they do not make an untrusted cache authoritative.
No key, password or user campaign data belongs in the cache.
"""
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import time
import zipfile

ROOT = Path(__file__).resolve().parents[1]
BASELINES = {
    56: ("d4240a2c90bec04e1395e7f8b3a3a12d1ba94794", "4.0.0-convergence3"),
    49: ("3543e83bfca5e589f1bbedb09964431ce94a2319", "3.2.0-rc1"),
    54: ("613360f61121390ec925d8229587a7dd95a354b2", "4.0.0-convergence1"),
    50: ("a86ab0fa87ce47a4f99550dcfa8fe01ed0e22928", "4.0.0-genesis1"),
}
FORMAT = "astravar-frozen-ci-baseline-v1"
OWNER_CERTIFICATE = "4c5cb0113cae5d0dee2619ee30f5b3c593d4aa897e1fa38dee7ed5561ec4e847"


def sha256(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def expected_inputs(version_code):
    source, name = BASELINES[version_code]
    return dict(format=FORMAT, source_sha=source, package="com.yazsras.astravar",
                version_code=version_code, version_name=name, jdk=17,
                sdk_platform=36, build_tools="35.0.0", build_variant="release",
                # Changes to validation/packaging invalidate earlier cache entries.
                validator_sha256=sha256(Path(__file__)),
                inspector_sha256=sha256(ROOT / "scripts/inspect_release_apk.py"))


def payload_entries(apk):
    """Ignore only direct JAR signing entries; retain other META-INF content."""
    with zipfile.ZipFile(apk) as archive:
        names = archive.namelist()
        if len(names) != len(set(names)):
            raise ValueError("Duplicate APK ZIP entries are not a valid baseline")
        result = {}
        for name in names:
            if re.fullmatch(r"META-INF/(?:MANIFEST\.MF|[^/]+\.(?:SF|RSA|DSA|EC))", name, re.I):
                continue
            info = archive.getinfo(name)
            result[name] = dict(size=info.file_size,
                                sha256=hashlib.sha256(archive.read(name)).hexdigest())
        return result


def validate_cache(apk, manifest, inputs):
    """Reject partial, mismatched, tampered or owner-signed cache records."""
    document = json.loads(Path(manifest).read_text())
    if document.get("inputs") != inputs:
        raise ValueError("Frozen baseline source/build inputs differ; use the matching exact cache key")
    certificate = document.get("certificate_sha256", "")
    if not re.fullmatch(r"[0-9a-f]{64}", certificate) or certificate == OWNER_CERTIFICATE:
        raise ValueError("The baseline cache must contain a disposable CI signer")
    if document.get("apk_sha256") != sha256(apk):
        raise ValueError("Frozen baseline APK checksum mismatch")
    if document.get("payload") != payload_entries(apk):
        raise ValueError("Frozen baseline payload differs from its manifest")
    return document


def run(*command, timeout=60, env=None, capture=False):
    return subprocess.run(list(map(str, command)), check=True, timeout=timeout,
                          cwd=ROOT, env=env, stdout=subprocess.PIPE if capture else None,
                          text=capture).stdout


def certificate(apk, tools):
    output = run(tools / "apksigner", "verify", "--verbose", "--print-certs", apk, capture=True)
    digests = re.findall(r"Signer #\d+ certificate SHA-256 digest: ([0-9a-fA-F]{64})", output)
    if len(digests) != 1:
        raise ValueError("Exactly one APK signing certificate is required")
    return digests[0].lower(), output


def require_ci_signer(candidate, tools):
    java = subprocess.check_output(["java", "-version"], stderr=subprocess.STDOUT, text=True, timeout=15)
    if not re.search(r'version "17(?:[.\"])', java):
        raise ValueError("Frozen baseline packaging requires the documented JDK 17")
    for name in ("ASTRAVAR_KEYSTORE", "ASTRAVAR_STORE_PASSWORD", "ASTRAVAR_KEY_PASSWORD", "ASTRAVAR_KEY_ALIAS"):
        if not os.environ.get(name):
            raise ValueError(f"Missing disposable CI signing environment: {name}")
    if os.environ["ASTRAVAR_KEY_ALIAS"] != "astravar-ci":
        raise ValueError("Baseline signing accepts only the documented disposable astravar-ci alias")
    expected, _ = certificate(candidate, tools)
    if expected == OWNER_CERTIFICATE:
        raise ValueError("Historical CI baseline preparation must never use the owner certificate")
    # Password remains in the environment, not in argv or logs. Check before signing.
    exported = subprocess.check_output([
        "keytool", "-exportcert", "-keystore", os.environ["ASTRAVAR_KEYSTORE"],
        "-storepass:env", "ASTRAVAR_STORE_PASSWORD", "-alias", "astravar-ci"], timeout=30)
    if hashlib.sha256(exported).hexdigest() != expected:
        raise ValueError("Disposable keystore and candidate APK certificates differ")
    return expected


def inspect(apk, sdk, inputs):
    spec = importlib.util.spec_from_file_location("baseline_apk_inspection", ROOT / "scripts/inspect_release_apk.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module.inspect(apk, sdk, inputs["package"], inputs["version_name"], inputs["version_code"])


def build_baseline(inputs, apk):
    source = inputs["source_sha"]
    run("git", "fetch", "--depth=1", "origin", source, timeout=90)
    with tempfile.TemporaryDirectory(prefix="astravar-frozen-baseline-") as temp:
        tree = Path(temp) / "source"
        run("git", "worktree", "add", "--detach", tree, source)
        try:
            actual = run("git", "-C", tree, "rev-parse", "HEAD", capture=True).strip()
            if actual != source:
                raise ValueError("Historical checkout differs from the pinned source")
            environment = dict(os.environ, ASTRAVAR_VERSION_CODE=str(inputs["version_code"]),
                               ASTRAVAR_VERSION_NAME=inputs["version_name"])
            run("timeout", "-k", "10s", "1200s", "bash", tree / "gradlew", "-p", tree,
                "--no-daemon", "--console=plain", "--build-cache", ":app:assembleRelease",
                timeout=1220, env=environment)
            shutil.copy2(tree / "app/build/outputs/apk/release/app-release.apk", apk)
        finally:
            # Only this newly created disposable worktree is removed.
            run("git", "worktree", "remove", "--force", tree)


def prepare(version_code, cache_dir, output_dir, candidate, sdk):
    started = time.monotonic()
    inputs = expected_inputs(version_code)
    tools = sdk / "build-tools/35.0.0"
    expected_certificate = require_ci_signer(candidate, tools)
    cache_dir.mkdir(parents=True, exist_ok=True)
    output_dir.mkdir(parents=True, exist_ok=True)
    cached_apk = cache_dir / f"code{version_code}.apk"
    manifest = cache_dir / f"code{version_code}.json"
    cache_hit = cached_apk.exists() or manifest.exists()
    if cache_hit:
        if not cached_apk.is_file() or not manifest.is_file():
            raise ValueError("Incomplete frozen baseline cache; refusing partial reuse")
        document = validate_cache(cached_apk, manifest, inputs)
        cached_certificate, _ = certificate(cached_apk, tools)
        if cached_certificate != document["certificate_sha256"]:
            raise ValueError("Cached APK signature differs from recorded provenance")
        inspect(cached_apk, sdk, inputs)
    else:
        # A failed build or inspection never becomes a reusable cache entry.
        with tempfile.TemporaryDirectory(prefix="astravar-baseline-build-") as temp:
            built = Path(temp) / "baseline.apk"
            build_baseline(inputs, built)
            inspection = inspect(built, sdk, inputs)
            if inspection["certificate_sha256"] != expected_certificate:
                raise ValueError("Fresh baseline and candidate have different CI certificates")
            document = dict(inputs=inputs, apk_sha256=sha256(built), payload=payload_entries(built),
                            certificate_sha256=expected_certificate,
                            build_run_id=os.environ.get("GITHUB_RUN_ID"),
                            build_run_attempt=os.environ.get("GITHUB_RUN_ATTEMPT"))
            shutil.copy2(built, cached_apk)
            manifest.write_text(json.dumps(document, indent=2) + "\n")

    output = output_dir / f"code{version_code}-ci.apk"
    run(tools / "apksigner", "sign", "--ks", os.environ["ASTRAVAR_KEYSTORE"],
        "--ks-key-alias", "astravar-ci", "--ks-pass", "env:ASTRAVAR_STORE_PASSWORD",
        "--key-pass", "env:ASTRAVAR_KEY_PASSWORD", "--out", output, cached_apk)
    if payload_entries(cached_apk) != payload_entries(output):
        raise ValueError("Signing changed a non-signature APK entry")
    inspection = inspect(output, sdk, inputs)
    if inspection["certificate_sha256"] != expected_certificate:
        raise ValueError("Re-signed baseline and candidate have different CI certificates")
    _, signature = certificate(output, tools)
    prefix = "" if version_code == 49 else "genesis-"
    (output_dir / f"{prefix}package.json").write_text(json.dumps(inspection, indent=2) + "\n")
    (output_dir / f"{prefix}signature.txt").write_text(signature)
    source_name = "SOURCE_COMMIT.txt" if version_code == 49 else "GENESIS_SOURCE_COMMIT.txt"
    (output_dir / source_name).write_text(inputs["source_sha"] + "\n")
    proof = dict(inputs=inputs, cache_hit=cache_hit, original_apk_sha256=sha256(cached_apk),
                 output_apk_sha256=sha256(output), payload_entries_verified=len(document["payload"]),
                 certificate_sha256=expected_certificate, owner_compatible=False,
                 duration_seconds=round(time.monotonic() - started, 3))
    (output_dir / f"code{version_code}-provenance.json").write_text(json.dumps(proof, indent=2) + "\n")
    print(json.dumps(proof))
    return proof


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--version-code", type=int, choices=BASELINES, required=True)
    parser.add_argument("--cache-dir", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--candidate", type=Path, required=True)
    parser.add_argument("--sdk", type=Path, default=os.environ.get("ANDROID_HOME"))
    args = parser.parse_args()
    if args.sdk is None:
        parser.error("--sdk or ANDROID_HOME is required")
    prepare(args.version_code, args.cache_dir, args.output_dir, args.candidate, args.sdk)
