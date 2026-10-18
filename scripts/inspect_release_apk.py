#!/usr/bin/env python3
"""Inspect a real APK. Never equate a disposable CI signer with the owner key."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess

OWNER_CERTIFICATE = "4c5cb0113cae5d0dee2619ee30f5b3c593d4aa897e1fa38dee7ed5561ec4e847"


def inspect(apk: Path, sdk: Path, package: str, version_name: str, version_code: int,
            owner_signed: bool = False) -> dict:
    tools = sdk / "build-tools/35.0.0"
    def run(tool, *args):
        return subprocess.check_output([str(tools / tool), *map(str, args)], text=True)
    signature = run("apksigner", "verify", "--verbose", "--print-certs", apk)
    run("zipalign", "-c", "-P", "16", "4", apk)
    badging = run("aapt", "dump", "badging", apk)
    metadata = re.search(r"package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", badging)
    if not metadata:
        raise ValueError("APK metadata missing")
    actual_package, actual_code, actual_name = metadata.groups()
    if (actual_package, int(actual_code), actual_name) != (package, version_code, version_name):
        raise ValueError(f"Unexpected package/version: {metadata.group(0)}")
    labels = re.findall(r"^application-label(?:-[^:]+)?:'([^']+)'$", badging, re.MULTILINE)
    if not labels or any(label != "Astravar" for label in labels):
        raise ValueError("Every application label must be Astravar")
    icons = re.findall(r"^application-icon-[^:]+:'([^']+)'$", badging, re.MULTILINE)
    if not icons or not any("ic_launcher" in icon for icon in icons):
        raise ValueError("Supplied launcher icon resource missing")
    certificates = re.findall(r"Signer #\d+ certificate SHA-256 digest: ([0-9a-fA-F]+)", signature)
    if len(certificates) != 1:
        raise ValueError("Exactly one signing certificate is required")
    certificate = certificates[0].lower()
    if owner_signed and certificate != OWNER_CERTIFICATE:
        raise ValueError("APK certificate differs from the historical owner identity")
    return dict(package=actual_package, version_code=int(actual_code), version_name=actual_name,
                label="Astravar", launcher_icons=icons, certificate_sha256=certificate,
                owner_signature_verified=certificate == OWNER_CERTIFICATE,
                signing_status="owner fingerprint matches" if certificate == OWNER_CERTIFICATE else "test signer; not a permanent owner release",
                apk_sha256=hashlib.sha256(apk.read_bytes()).hexdigest(), alignment_16kb_verified=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    parser.add_argument("--sdk", type=Path, default=os.environ.get("ANDROID_HOME"))
    parser.add_argument("--package", required=True)
    parser.add_argument("--version-name", required=True)
    parser.add_argument("--version-code", required=True, type=int)
    parser.add_argument("--owner-signed", action="store_true")
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    if not args.sdk:
        parser.error("--sdk or ANDROID_HOME is required")
    result = inspect(args.apk, args.sdk, args.package, args.version_name, args.version_code, args.owner_signed)
    encoded = json.dumps(result, indent=2) + "\n"
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(encoded)
    print(encoded, end="")
