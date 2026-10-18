#!/usr/bin/env python3
"""Inspect a real APK. Never equate a disposable CI signer with the owner key."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import struct
import zipfile

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
    debuggable = "application-debuggable" in badging
    if owner_signed and debuggable:
        raise ValueError("An owner release candidate must not be debuggable")
    labels = re.findall(r"^application-label(?:-[^:]+)?:'([^']+)'$", badging, re.MULTILINE)
    if not labels or any(label != "Astravar" for label in labels):
        raise ValueError("Every application label must be Astravar")
    icons = re.findall(r"^application-icon-[^:]+:'([^']+)'$", badging, re.MULTILINE)
    if not icons:
        raise ValueError("Supplied launcher icon resource missing")
    # Optimized release resource paths are legitimately shortened (res/BW.xml,
    # for example). Resolve the resource table instead of trusting filenames.
    resources = run("aapt", "dump", "--values", "resources", apk)
    foreground = re.search(
        r'^\s*resource (0x[0-9a-fA-F]+) [^\n]*:drawable/astravar_launcher_foreground:[^\n]*\n\s+\(string\d*\) "([^\"]+)"',
        resources, re.MULTILINE)
    if not foreground or ":mipmap/ic_launcher:" not in resources:
        raise ValueError("Approved launcher resource identity missing")
    foreground_id, foreground_file = foreground.groups()
    approved = json.loads((Path(__file__).parent / "branding_assets_sha256.json").read_text())
    expected_foreground = approved["files"]["app/src/main/res/drawable-nodpi/astravar_launcher_foreground.png"]
    with zipfile.ZipFile(apk) as archive:
        if any(icon not in archive.namelist() for icon in icons):
            raise ValueError("Launcher references an absent APK entry")
        foreground_hash = hashlib.sha256(archive.read(foreground_file)).hexdigest()
        if foreground_hash != expected_foreground:
            raise ValueError("Packaged portrait differs from approved supplied artwork")
        native_alignment = {}
        for name in archive.namelist():
            if not name.startswith(("lib/arm64-v8a/", "lib/x86_64/")) or not name.endswith(".so"):
                continue
            data = archive.read(name)
            if data[:4] != b"\x7fELF" or data[4] != 2:
                raise ValueError(f"Invalid 64-bit native library: {name}")
            endian = "<" if data[5] == 1 else ">"
            offset = struct.unpack_from(endian + "Q", data, 32)[0]
            size, count = struct.unpack_from(endian + "HH", data, 54)
            segments = [struct.unpack_from(endian + "Q", data, offset + i * size + 48)[0]
                        for i in range(count)
                        if struct.unpack_from(endian + "I", data, offset + i * size)[0] == 1]
            if not segments or any(alignment < 16384 for alignment in segments):
                raise ValueError(f"Native library is not compatible with 16 KB pages: {name}")
            native_alignment[name] = segments
    adaptive_icons = [icon for icon in set(icons) if icon.endswith(".xml")]
    if not adaptive_icons:
        raise ValueError("Adaptive launcher icon missing")
    for icon in adaptive_icons:
        tree = run("aapt", "dump", "xmltree", apk, icon)
        if "adaptive-icon" not in tree or f"@{foreground_id}" not in tree:
            raise ValueError("Launcher does not reference the verified supplied portrait")
    certificates = re.findall(r"Signer #\d+ certificate SHA-256 digest: ([0-9a-fA-F]+)", signature)
    if len(certificates) != 1:
        raise ValueError("Exactly one signing certificate is required")
    certificate = certificates[0].lower()
    if owner_signed and certificate != OWNER_CERTIFICATE:
        raise ValueError("APK certificate differs from the historical owner identity")
    return dict(package=actual_package, version_code=int(actual_code), version_name=actual_name,
                label="Astravar", launcher_icons=icons, launcher_foreground_sha256=foreground_hash,
                launcher_artwork_bytes_verified=True, certificate_sha256=certificate,
                debuggable=debuggable, native_64bit_load_alignment=native_alignment,
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
