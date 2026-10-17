#!/usr/bin/env python3
"""Safely overlay the offline inventory MVP on the *exact* recovered v1.7.0 source.

Usage: python3 path/to/scripts/apply_inventory_mvp.py /path/to/Astravar-Overlay
The target checkout must be a CLEAN new feature branch pointing at recovery SHA.
Never run on main, feat/astravar-android, or the recovery branch itself.
"""
from __future__ import annotations
import argparse
import pathlib
import shutil
import subprocess
import sys

EXPECTED_SHA = "b4f539bca7965b40153bc5e237f66b458ee1578e"
BUNDLE_ROOT = pathlib.Path(__file__).resolve().parents[1]
ADDITIONS = [
    "app/src/main/java/com/yazsras/astravar/data/CampaignLedger.kt",
    "app/src/main/java/com/yazsras/astravar/ui/CampaignLedgerPage.kt",
    "app/src/test/java/com/yazsras/astravar/data/CampaignLedgerTest.kt",
    "app/src/debug/AndroidManifest.xml",
    "docs/CAMPAIGN_INVENTORY_BETA.md",
]


def patch_once(source: str, old: str, new: str, path: str) -> str:
    count = source.count(old)
    if count != 1:
        raise ValueError(f"Unexpected v1.7.0 source ({path}): required patch point matched {count} times")
    return source.replace(old, new, 1)


def revised_sources(sources: dict[str, str]) -> dict[str, str]:
    """Pure transformation; testable without Git or the Android toolchain."""
    app_path = "app/src/main/java/com/yazsras/astravar/AstravarApp.kt"
    app = patch_once(sources[app_path],
        '    val preferences by lazy { Preferences(this) }',
        '    val preferences by lazy { Preferences(this) }\n'
        '    // Campaign items are isolated from the existing Room v3 weapon/combat database.\n'
        '    val campaignLedger by lazy { CampaignLedgerRepository(CampaignLedgerDb.open(this)) }',
        app_path)
    screen_path = "app/src/main/java/com/yazsras/astravar/ui/AppScreen.kt"
    screen = sources[screen_path]
    screen = patch_once(screen,
        '                            "Resources" -> ResourcesPage(current,history,actions)',
        '                            "Campaign Inventory" -> if (com.yazsras.astravar.BuildConfig.DEBUG) '\
        'CampaignLedgerPage(app.campaignLedger) else ControlRoomPage { morePage=it }\n'
        '                            "Resources" -> ResourcesPage(current,history,actions)',
        screen_path)
    screen = patch_once(screen,
        '        SectionCaption("SAFEKEEPING & SUPPORT")',
        '        if (com.yazsras.astravar.BuildConfig.DEBUG) {\n'
        '            SectionCaption("CAMPAIGN RECORDS")\n'
        '            ControlRoomLink("Campaign inventory · local beta", '
        '"Item profiles, artwork and research history", "History") { open("Campaign Inventory") }\n'
        '        }\n'
        '        SectionCaption("SAFEKEEPING & SUPPORT")',
        screen_path)
    gradle_path = "app/build.gradle.kts"
    gradle = patch_once(sources[gradle_path],
        'versionNameSuffix = "-test"',
        'versionNameSuffix = "-inventory-beta"',
        gradle_path)
    return {app_path:app,screen_path:screen,gradle_path:gradle}


def git(folder: pathlib.Path, *args: str) -> str:
    return subprocess.check_output(["git", "-C", str(folder), *args], text=True).strip()


def run(folder: pathlib.Path) -> None:
    folder = folder.resolve()
    if not (folder / "app/build.gradle.kts").is_file():
        raise RuntimeError(f"Not an Astravar Android checkout: {folder}")
    branch = git(folder, "branch", "--show-current")
    head = git(folder, "rev-parse", "HEAD")
    status = git(folder, "status", "--porcelain")
    if not branch.startswith("feat/") or branch in {"feat/astravar-android", "main"}:
        raise RuntimeError(f"Refusing to patch non-new feature branch: {branch!r}")
    if head != EXPECTED_SHA:
        raise RuntimeError(f"Refusing to patch commit {head}. Expected recovered v1.7.0 tip {EXPECTED_SHA}.")
    if status:
        raise RuntimeError("Working tree is not clean. Preserve uncommitted work before applying the bundle.")
    originals = {}
    for filename in ["app/src/main/java/com/yazsras/astravar/AstravarApp.kt",
                     "app/src/main/java/com/yazsras/astravar/ui/AppScreen.kt",
                     "app/build.gradle.kts"]:
        originals[filename] = (folder / filename).read_text()
    modified = revised_sources(originals)
    for relative in ADDITIONS:
        if (folder / relative).exists():
            raise RuntimeError(f"Refusing to overwrite pre-existing source: {relative}")
        if not (BUNDLE_ROOT / relative).is_file():
            raise RuntimeError(f"Bundle is incomplete, missing: {relative}")
    for relative, text in modified.items():
        (folder / relative).write_text(text)
    for relative in ADDITIONS:
        target = folder / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(BUNDLE_ROOT / relative, target)
    print(f"Applied offline Campaign Inventory MVP to {branch} at preserved base {head}")
    print("Changes are UNCOMMITTED. Review git diff, run tests/build, then commit on the feature branch.")
    print("DO NOT install over the owner-signed release: build the .debug / -inventory-beta variant.")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("checkout", help="Clean feature checkout branched from recovered v1.7.0 commit")
    args = parser.parse_args()
    try:
        run(pathlib.Path(args.checkout))
    except Exception as error:
        print(f"BLOCKED: {error}", file=sys.stderr)
        sys.exit(2)
