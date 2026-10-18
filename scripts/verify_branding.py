"""Verify supplied launcher bytes and, optionally, the assembled APK's identity.

The APK is a verification artifact, not proof it can update an installed app.
Signing compatibility must be checked separately against that installation.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import xml.etree.ElementTree as ET


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--badging", type=Path)
    parser.add_argument("--package")
    parser.add_argument("--version-name")
    parser.add_argument("--version-code")
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    assets = json.loads((root / "scripts/branding_assets_sha256.json").read_text())
    for name, expected in assets["files"].items():
        actual = hashlib.sha256((root / name).read_bytes()).hexdigest()
        if actual != expected:
            raise SystemExit(f"Supplied branding resource changed: {name}")
    android = "{http://schemas.android.com/apk/res/android}"
    app = ET.parse(root / "app/src/main/AndroidManifest.xml").getroot().find("application")
    for key, value in {"label": "Astravar", "icon": "@mipmap/ic_launcher",
                       "roundIcon": "@mipmap/ic_launcher_round"}.items():
        if app.get(android + key) != value:
            raise SystemExit(f"Incorrect manifest branding: {key}")
    print(f"Verified all {len(assets['files'])} supplied launcher resources and manifest branding")
    if args.badging:
        if not all((args.package, args.version_name, args.version_code)):
            parser.error("APK verification requires package, version-name and version-code")
        badging = args.badging.read_text()
        package = re.search(r"^package: (.*)$", badging, re.MULTILINE)
        identity = dict(re.findall(r"(\w+)='([^']*)'", package.group(1))) if package else {}
        expected = {"name": args.package, "versionName": args.version_name,
                    "versionCode": args.version_code}
        if any(identity.get(key) != value for key, value in expected.items()):
            raise SystemExit(f"Unexpected packaged identity: {identity}")
        labels = re.findall(r"^application-label(?:-[^:]*)?:'([^']*)'", badging, re.MULTILINE)
        icons = re.findall(r"^application-icon-\d+:'([^']*)'", badging, re.MULTILINE)
        if not labels or set(labels) != {"Astravar"}:
            raise SystemExit(f"Unexpected packaged launcher labels: {labels}")
        if not icons or any(not re.search(r"/ic_launcher\.(xml|png)$", icon) for icon in icons):
            raise SystemExit(f"Unexpected packaged launcher icons: {icons}")
        print(f"Verified packaged Astravar label, launcher icons and {expected}")


if __name__ == "__main__":
    main()
