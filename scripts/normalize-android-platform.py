#!/usr/bin/env python3
"""Normalize the optional extra platform directory in Google's API36 SDK package.

Run only against an isolated SDK downloaded by sdkmanager. Do not remove packages,
rewrite platform metadata, or silently accept a different API level.
"""
from pathlib import Path
import os

sdk = Path(os.environ["ANDROID_HOME"]).resolve()
platform = sdk / "platforms/android-36"
nested = platform / "android-36"
if not (platform / "android.jar").is_file() and (nested / "android.jar").is_file():
    properties = (nested / "source.properties").read_text()
    assert "AndroidVersion.ApiLevel=36" in properties, "Expected verified API36 package"
    # A package with both platform and layoutlib roots can escape sdkmanager's
    # usual single-root removal. Preserve all files and its package.xml receipt.
    for folder in (nested, platform / "android-15"):
        if not folder.is_dir():
            continue
        for child in list(folder.iterdir()):
            destination = platform / child.name
            assert not destination.exists(), f"Refusing SDK collision: {destination.name}"
            child.rename(destination)
        folder.rmdir()
assert (platform / "android.jar").is_file(), "API36 android.jar is missing"
assert "AndroidVersion.ApiLevel=36" in (platform / "source.properties").read_text()
print("Verified API36 SDK platform layout")
