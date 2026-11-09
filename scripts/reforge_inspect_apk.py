#!/usr/bin/env python3
"""Inspect an actual Reforge APK, including merged overlay retirement."""
import argparse
import json
from pathlib import Path
import subprocess

from inspect_release_apk import inspect


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    parser.add_argument("--sdk", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--owner-signed", action="store_true")
    parser.add_argument("--version-code", type=int, default=46)
    parser.add_argument("--version-name", default="3.1.0-rc2")
    args = parser.parse_args()
    package = "com.yazsras.astravar" if args.owner_signed else "com.yazsras.astravar.v2uidebug"
    version = args.version_name if args.owner_signed else args.version_name + "-ui-rebuild"
    result = inspect(args.apk, args.sdk, package, version, args.version_code, owner_signed=args.owner_signed)
    manifest = subprocess.check_output([
        str(args.sdk / "build-tools/35.0.0/aapt"), "dump", "xmltree",
        str(args.apk), "AndroidManifest.xml"], text=True)
    retired = (
        "android.permission.SYSTEM_ALERT_WINDOW",
        "android.permission.FOREGROUND_SERVICE_SPECIAL_USE",
        "android.permission.FOREGROUND_SERVICE\"",
        "com.yazsras.astravar.overlay.OverlayService",
        "androidx.work.impl.foreground.SystemForegroundService",
    )
    if any(value in manifest for value in retired):
        raise ValueError("Packaged manifest retains retired overlay permissions or service")
    result["overlay_manifest_retirement_verified"] = True
    result["installation_performed"] = False
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, indent=2) + "\n")
    print(json.dumps(result, indent=2))


if __name__ == "__main__":
    main()
