#!/usr/bin/env python3
"""Reject broad or unexpected evidence publication; never discovers APK inputs."""
from pathlib import Path
import re
import sys

import owner_upgrade_envelope as E

METHODS = ("seedCode49", "verifyAfterUpgrade", "canonicalWriteSurvivesRestart")
PNGS = ("genesis-before-code49-sheet.png", "genesis-after-code50-sheet.png",
        "genesis-upgrade-companion.png", "genesis-canonical-write-after-restart.png")
ALLOWED = {"recipient.json", "transport-result.json", "native-evidence/native-result.json"}
ALLOWED.update("native-evidence/native-" + method + ".txt" for method in METHODS)
ALLOWED.update("native-evidence/" + name for name in PNGS)
TRANSPORT_FIELDS = {"format", "validated", "owner_signing_key_received", "native_installation_verified",
                    "candidate_payload_matches_source_artifact", "receiver", "transfer_commit", "candidate_source",
                    "baseline_source", "baseline_equivalence", "owner_certificate_sha256", "apks", "source_artifact", "gates"}
NATIVE_FIELDS = {"format", "passed", "synthetic_emulator_only", "physical_device_verified", "cleanup_complete",
                 "exit_code", "last_stage", "owner_signing_key_received", "elapsed_seconds", "native_stages",
                 "package", "old_version", "new_version", "api_level_expected", "emulator_identity_verified",
                 "acceleration_required", "uninstall_or_clear_data_used", "baseline", "preserved", "written",
                 "package_retention", "files-before_sha256", "files-after_sha256",
                 "all_stopped_store_bytes_unchanged_during_install", "screenshots"}


def integer(value, maximum=7200):
    E.require(type(value) is int and 0 <= value <= maximum, "Unexpected evidence integer")


def check_binding(value, config=None):
    E.exact_keys(value, ("repository", "workflow_path", "receiver_commit", "run_id", "run_attempt",
                         "config_sha256", "transfer_branch"))
    E.require(value["repository"] == E.REPOSITORY and value["workflow_path"] == E.WORKFLOW, "Wrong evidence repository")
    E.hex_value(value["receiver_commit"], 40); E.hex_value(value["config_sha256"])
    E.positive(value["run_id"]); E.positive(value["run_attempt"])
    E.require(value["transfer_branch"] == f"owner-upgrade-transfer/{value['run_id']}-{value['run_attempt']}",
              "Wrong evidence transfer branch")
    if config is not None:
        E.require(value == E.binding(config, value["run_id"], value["run_attempt"], value["receiver_commit"]),
                  "Evidence differs from reviewed config")


def check_transport(value, config):
    E.exact_keys(value, TRANSPORT_FIELDS)
    E.require(value["format"] == "astravar-owner-upgrade-transport-v1" and value["validated"] is True and
              value["owner_signing_key_received"] is False and value["native_installation_verified"] is False and
              value["candidate_payload_matches_source_artifact"] is True, "Unexpected transport evidence")
    E.require(config is not None, "Transport evidence requires reviewed config")
    E.validate_config(config)
    check_binding(value["receiver"], config)
    E.hex_value(value["transfer_commit"], 40)
    for field in ("candidate_source", "baseline_source", "baseline_equivalence", "owner_certificate_sha256", "gates"):
        E.require(value[field] == config[field], "Transport provenance differs from reviewed config")
    E.exact_keys(value["apks"], E.ALL_ROLES)
    for role, metadata in value["apks"].items():
        fields = ("sha256", "package", "version_code", "certificate_sha256")
        if role == "observer-test":
            fields += ("instrumentation_target", "instrumentation_runner")
        E.exact_keys(metadata, fields)
        E.require(metadata == {key: config["apks"][role][key] for key in fields}, "Unexpected APK evidence")
    expected = config["observer_artifact"]
    E.require(value["source_artifact"] == {"id": expected["artifact_id"], "digest": expected["digest"],
              "run_id": expected["run_id"], "run_attempt": expected["run_attempt"]}, "Unexpected artifact evidence")


def check_native(value):
    E.require(set(value) <= NATIVE_FIELDS and value.get("format") == "astravar-owner-upgrade-native-v1" and
              value.get("synthetic_emulator_only") is True and value.get("physical_device_verified") is False,
              "Unexpected native evidence fields")
    boolean_fields = {"passed", "synthetic_emulator_only", "physical_device_verified", "cleanup_complete",
                      "owner_signing_key_received", "emulator_identity_verified", "uninstall_or_clear_data_used",
                      "all_stopped_store_bytes_unchanged_during_install"}
    for name in boolean_fields & set(value):
        E.require(type(value[name]) is bool, "Unexpected evidence boolean")
    for name in ("owner_signing_key_received", "uninstall_or_clear_data_used"):
        E.require(value.get(name, False) is False, "Forbidden acceptance claim")
    for name in ("exit_code", "elapsed_seconds"):
        if name in value: integer(value[name])
    E.require(value.get("last_stage") in {"preflight", "boot", "install_baseline_and_observers", "complete",
        "baseline_independent_readback", "normal_owner_upgrade_install", "preserved_independent_readback",
        "canonical_write_independent_readback", *METHODS}, "Unexpected native stage")
    fixed = {"package": "com.yazsras.astravar", "old_version": 49, "new_version": 50,
             "api_level_expected": 36, "acceleration_required": "KVM"}
    for name, expected in fixed.items():
        if name in value: E.require(value[name] == expected, "Unexpected native identity")
    stages = value.get("native_stages", [])
    E.require(isinstance(stages, list) and len(stages) <= 3, "Unexpected native stage list")
    for stage in stages:
        E.exact_keys(stage, ("method", "status", "exit_code", "elapsed_seconds", "private_log_sha256"))
        E.require(stage["method"] in METHODS and stage["status"] in {"PASS", "FAIL"}, "Unexpected stage status")
        integer(stage["exit_code"]); integer(stage["elapsed_seconds"]); E.hex_value(stage["private_log_sha256"])
    for name in ("files-before_sha256", "files-after_sha256"):
        if name in value: E.hex_value(value[name])
    for phase in ("baseline", "preserved", "written"):
        if phase not in value: continue
        proof = value[phase]
        if proof == {"independent_readback_complete": False}: continue
        E.exact_keys(proof, ("synthetic_ui_fixture_verified", "phase", "additive_storage_cutover_verified",
                             "canonical_write_and_retained_source_verified"))
        E.require(proof["phase"] == phase and proof["synthetic_ui_fixture_verified"] is True and
                  type(proof["additive_storage_cutover_verified"]) is bool and
                  type(proof["canonical_write_and_retained_source_verified"]) is bool, "Unexpected readback proof")
    if "package_retention" in value:
        E.require(value["package_retention"] == {"package": "com.yazsras.astravar", "old_version": 49,
            "new_version": 50, "android_user_id": 0, "same_android_uid": True, "same_first_install_time": True},
            "Unexpected package retention evidence")
    screenshots = value.get("screenshots", {})
    E.require(isinstance(screenshots, dict) and set(screenshots) <= set(PNGS), "Unexpected screenshot metadata")
    for sha in screenshots.values(): E.hex_value(sha)


def verify_publication(root, config=None):
    E.require(root.is_dir() and not root.is_symlink(), "Public evidence directory missing or redirected")
    found = set()
    for path in root.rglob("*"):
        E.require(not path.is_symlink(), "Publication symlinks forbidden")
        name = path.relative_to(root).as_posix()
        if path.is_dir():
            E.require(name == "native-evidence", "Unexpected publication directory")
            continue
        E.require(name in ALLOWED and path.is_file(), "Unexpected publication file")
        size = path.stat().st_size
        E.require(0 < size <= (16 * 1024 * 1024 if name.endswith(".png") else E.MAX_JSON), "Publication size exceeds bound")
        data = path.read_bytes()
        if name.endswith(".png"):
            from owner_upgrade_png import sanitize_png
            E.require(sanitize_png(data) == data, "Expected metadata-stripped native PNG without suffix")
        else:
            E.require(not any(marker in data for marker in (b"PRIVATE KEY", b"SQLite format 3", b"PK\x03\x04")),
                      "Private material detected in publication")
            if name.endswith(".json"):
                value = E.strict_json(data)
                if name == "transport-result.json":
                    check_transport(value, config)
                elif name.endswith("native-result.json"):
                    check_native(value)
                else:
                    check_binding(value["binding"], config)
                    E.validate_recipient(value, value["binding"], now=value["created_at"])
            else:
                for line in data.decode().splitlines():
                    E.require(bool(re.fullmatch(r"(?:INSTRUMENTATION_(?:STATUS_CODE|CODE): -?\d+|"
                        r"INSTRUMENTATION_STATUS: (?:numtests|current)=\d+|"
                        r"INSTRUMENTATION_STATUS: class=com\.yazsras\.astravar\.GenesisUpgradeInstrumentationTest|"
                        r"INSTRUMENTATION_STATUS: test=(?:" + "|".join(METHODS) + r")|OK \(1 tests?\)|FAILURES!!!|"
                        r"Recorded process exit code: \d+|Full private transcript SHA-256: [0-9a-f]{64})", line)),
                        "Unexpected native transcript contents")
        found.add(name)
    E.require("recipient.json" in found, "No generated public recipient")
    return sorted(found)


if __name__ == "__main__":
    try:
        config = E.read_json(Path(__file__).with_name("owner-upgrade-config.json"))
        verify_publication(Path(sys.argv[1]), config)
        print("Only allowlisted synthetic evidence and public metadata are present.")
    except Exception:
        raise SystemExit("Publication rejected; no private output is eligible for upload.")
