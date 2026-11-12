#!/usr/bin/env python3
"""Compare package identity in copied synthetic-emulator dumpsys output; no device access."""
import argparse
from datetime import datetime
import json
from pathlib import Path
import re


def one_field(text, key, *, required=True):
    values = re.findall(r"^\s+" + re.escape(key) + r"=([^\r\n]+)$", text, re.MULTILINE)
    assert len(values) <= 1, "Ambiguous package field " + key
    assert values or not required, "Missing package field " + key
    return values[0].strip() if values else None


def package_metadata(text, package):
    text = text.replace("\r\n", "\n")
    sections = re.findall(r"^Packages:\s*\n(.*?)(?=^\S|\Z)", text, re.MULTILINE | re.DOTALL)
    assert len(sections) == 1, "Missing or ambiguous Packages section"
    section = sections[0]
    headers = list(re.finditer(r"^  Package \[([^\]]+)\][^\r\n]*:\s*$", section, re.MULTILINE))
    matches = [index for index, header in enumerate(headers) if header.group(1) == package]
    assert len(matches) == 1, "Missing or ambiguous expected package " + package
    index = matches[0]
    block = section[headers[index].end():headers[index + 1].start() if index + 1 < len(headers) else len(section)]
    users = list(re.finditer(r"^    User (\d+):([^\r\n]*)$", block, re.MULTILINE))
    owner = [index for index, user in enumerate(users) if user.group(1) == "0"]
    assert len(owner) == 1, "Package must have one explicit Android user 0 state"
    user_index = owner[0]
    assert re.search(r"\binstalled=true(?:\s|$)", users[user_index].group(2)), "Package is not installed for Android user 0"
    user_block = block[users[user_index].end():users[user_index + 1].start() if user_index + 1 < len(users) else len(block)]
    package_fields = block[:users[0].start()]
    ids = {key: one_field(package_fields, key, required=False) for key in ("appId", "userId")}
    ids = {key: value for key, value in ids.items() if value is not None}
    assert ids, "Missing package appId or userId"
    assert all(re.fullmatch(r"[0-9]+", value) for value in ids.values()), "Package identity must be decimal"
    assert len({int(value) for value in ids.values()}) == 1, "Package appId and userId disagree"
    app_id = int(next(iter(ids.values())))
    assert 0 < app_id < 100000, "Package identity is not an Android user 0 app ID"
    version = one_field(package_fields, "versionCode").split()[0]
    assert re.fullmatch(r"[0-9]+", version), "Invalid package versionCode"
    install_times = [value for value in (
        one_field(package_fields, "firstInstallTime", required=False),
        one_field(user_block, "firstInstallTime", required=False)) if value is not None]
    assert install_times, "Missing package firstInstallTime for Android user 0"
    assert len(set(install_times)) == 1, "Ambiguous package firstInstallTime"
    assert re.fullmatch(r"\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}", install_times[0]), "Invalid package firstInstallTime"
    datetime.strptime(install_times[0], "%Y-%m-%d %H:%M:%S")
    return {"package": package, "version_code": int(version), "android_user_id": 0,
            "android_uid": app_id, "identity_fields": sorted(ids), "first_install_time": install_times[0]}


def verify_upgrade(before, after, package, old_version=49, new_version=50):
    prior = package_metadata(before, package)
    current = package_metadata(after, package)
    assert prior["version_code"] == old_version, "Unexpected baseline versionCode"
    assert current["version_code"] == new_version, "Unexpected candidate versionCode"
    assert new_version > old_version, "Expected an older-version upgrade"
    assert prior["android_uid"] == current["android_uid"], "Upgrade changed Android UID"
    assert prior["first_install_time"] == current["first_install_time"], "Upgrade changed firstInstallTime"
    return {"package": package, "old_version": old_version, "new_version": new_version,
            "android_user_id": 0, "android_uid": current["android_uid"],
            "same_android_uid": True, "same_first_install_time": True,
            "first_install_time": current["first_install_time"],
            "before_identity_fields": prior["identity_fields"], "after_identity_fields": current["identity_fields"]}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--before", type=Path, required=True)
    parser.add_argument("--after", type=Path, required=True)
    parser.add_argument("--package", required=True)
    parser.add_argument("--old-version", type=int, default=49)
    parser.add_argument("--new-version", type=int, default=50)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    result = verify_upgrade(args.before.read_text(), args.after.read_text(), args.package, args.old_version, args.new_version)
    args.output.write_text(json.dumps(result, indent=2) + "\n")
    print(json.dumps(result))
