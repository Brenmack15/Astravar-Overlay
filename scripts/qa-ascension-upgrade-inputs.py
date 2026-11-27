"""Validate the one disposable APK pair selected for the API 37 upgrade rehearsal."""
import hashlib
import json
import os
import pathlib
import re
import shutil
import subprocess
import sys
import zipfile

SOURCE = "5e65a4c7052fe1697cbcbd03f415b85249d09b7a"
HISTORICAL = "883198a3140ae2b707b31f81829c94cd821189b9"
ARCHIVE = "f69a63624470085a805790ce7831e99134f10e160d974ca60af90759f19cd4d1"
CERTIFICATE = "bc8ccc699ea2829d87a30bc52787389f87e742462b6f95bf88ae05a78438c886"
PACKAGE = "com.yazsras.astravar"
PAIRS = (
    ("validation/upgrade-baseline/code58-ci.apk", 58, "4.0.0-convergence5", "62d979348dec633c48d4bc648dd3d01a2119b791c701cf9f20ffeac5a46f997a"),
    ("app/build/outputs/apk/release/app-release.apk", 59, "4.1.0", "0fc8d3cacf2816cb64c2c0a4068dce5f06c4f8bc27290ba0a0d9d017c042d86d"),
)

def sha256(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()

def main(archive_path, metadata_path):
    metadata = json.loads(metadata_path.read_text())
    run = metadata["workflow_run"]
    assert metadata["id"] == 15166880758 and metadata["name"] == "android-verification"
    assert not metadata["expired"] and metadata["digest"] == "sha256:" + ARCHIVE
    assert run["id"] == 45923727140 and run["head_sha"] == SOURCE
    assert run["repository_id"] == 1391583014 and run["head_repository_id"] == 1391583014
    assert sha256(archive_path) == ARCHIVE
    tools = pathlib.Path(os.environ["ANDROID_HOME"]) / "build-tools/35.0.0"
    evidence = pathlib.Path("validation/ascension-api37")
    evidence.mkdir(parents=True, exist_ok=True)
    verified = []
    with zipfile.ZipFile(archive_path) as archive:
        names = archive.namelist()
        previous = json.loads(archive.read("validation/upgrade-baseline/code58-provenance.json"))
        candidate = json.loads(archive.read("validation/ci-release-package.json"))
        assert archive.read("validation/owner-hp-native/SOURCE_COMMIT.txt").decode().strip() == SOURCE
        assert previous["inputs"]["source_sha"] == HISTORICAL and previous["inputs"]["version_code"] == 58
        assert previous["payload_entries_verified"] == 210 and previous["owner_compatible"] is False
        assert previous["output_apk_sha256"] == PAIRS[0][3] and previous["certificate_sha256"] == CERTIFICATE
        assert candidate["apk_sha256"] == PAIRS[1][3] and candidate["certificate_sha256"] == CERTIFICATE
        assert candidate["owner_signature_verified"] is False and candidate["debuggable"] is False
        for member, number, name, digest in PAIRS:
            assert names.count(member) == 1
            path = pathlib.Path(member)
            path.parent.mkdir(parents=True, exist_ok=True)
            with archive.open(member) as src, path.open("xb") as dst:
                shutil.copyfileobj(src, dst)
            assert sha256(path) == digest
            signature = subprocess.check_output([tools / "apksigner", "verify", "--verbose", "--print-certs", path], text=True)
            assert re.findall(r"Signer #\d+ certificate SHA-256 digest: ([0-9a-f]{64})", signature) == [CERTIFICATE]
            identity = subprocess.check_output([tools / "aapt2", "dump", "badging", path], text=True).splitlines()[0]
            assert f"name='{PACKAGE}' versionCode='{number}' versionName='{name}'" in identity
            verified.append({"versionCode": number, "versionName": name, "sha256": digest})
    result = {"sourceCommit": SOURCE, "historicalSource": HISTORICAL, "artifactId": 15166880758,
              "archiveSha256": ARCHIVE, "package": PACKAGE, "disposableCertificateSha256": CERTIFICATE,
              "packages": verified, "ownerSigned": False}
    (evidence / "provenance.json").write_text(json.dumps(result, indent=2) + "\n")
    print(json.dumps(result, indent=2))

if __name__ == "__main__":
    main(*map(pathlib.Path, sys.argv[1:]))
