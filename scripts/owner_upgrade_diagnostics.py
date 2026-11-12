#!/usr/bin/env python3
"""Public identifiers only; never emit exception text or untrusted input values.

Run locally to map CHECK identifiers to literal checks in the reviewed source.
This utility reads only the three helper source files, never runtime evidence.
"""
import ast
import json
from pathlib import Path
import subprocess
import urllib.error
import zipfile

from cryptography.exceptions import InvalidTag
import owner_upgrade_envelope as E

SOURCES = ("owner_upgrade_envelope.py", "owner_upgrade_transfer.py", "owner_upgrade_publication.py")
GENERIC = {
    "CRYPTO_TAG_INVALID": "Authenticated decryption rejected the envelope.",
    "HTTP_FAILURE": "A GitHub or artifact HTTP request failed; no URL or response body is published.",
    "NETWORK_FAILURE": "A network request failed; no URL or credentials are published.",
    "OPERATION_TIMEOUT": "A bounded subprocess or network operation timed out.",
    "INPUT_JSON_INVALID": "An input JSON document could not be decoded.",
    "INPUT_ZIP_INVALID": "An input archive could not be read as a ZIP.",
    "INPUT_FIELD_MISSING": "Expected input metadata was absent.",
    "INPUT_VALUE_INVALID": "A parser or cryptographic input validation failed.",
    "FILESYSTEM_FAILURE": "A filesystem operation failed; no private path is published.",
    "INTERNAL_FAILURE": "An unexpected failure occurred; no exception value is published.",
}


def failure_id(error):
    # Only our trusted require() failures receive a message-derived identifier.
    # Unknown exceptions never get str(), repr(), URL, path or response content.
    if isinstance(error, E.RequirementFailure):
        return error.diagnostic_id
    if isinstance(error, InvalidTag):
        return "CRYPTO_TAG_INVALID"
    if isinstance(error, urllib.error.HTTPError):
        return "HTTP_FAILURE"
    if isinstance(error, (subprocess.TimeoutExpired, TimeoutError)):
        return "OPERATION_TIMEOUT"
    if isinstance(error, urllib.error.URLError):
        return "NETWORK_FAILURE"
    if isinstance(error, json.JSONDecodeError):
        return "INPUT_JSON_INVALID"
    if isinstance(error, zipfile.BadZipFile):
        return "INPUT_ZIP_INVALID"
    if isinstance(error, KeyError):
        return "INPUT_FIELD_MISSING"
    if isinstance(error, (ValueError, TypeError)):
        return "INPUT_VALUE_INVALID"
    if isinstance(error, OSError):
        return "FILESYSTEM_FAILURE"
    return "INTERNAL_FAILURE"


def catalog():
    result = {key: {"meaning": value} for key, value in GENERIC.items()}
    for name in SOURCES:
        tree = ast.parse(Path(__file__).with_name(name).read_text())
        for node in ast.walk(tree):
            if not isinstance(node, ast.Call) or len(node.args) < 2:
                continue
            target = node.func
            if not ((isinstance(target, ast.Name) and target.id == "require") or
                    (isinstance(target, ast.Attribute) and target.attr == "require")):
                continue
            message = node.args[1]
            if not isinstance(message, ast.Constant) or not isinstance(message.value, str):
                raise ValueError("Diagnostic checks must use literal source messages")
            identifier = E.RequirementFailure(message.value).diagnostic_id
            entry = result.setdefault(identifier, {"meaning": message.value, "source": []})
            if entry["meaning"] != message.value:
                raise ValueError("Diagnostic identifier collision")
            entry["source"].append(f"scripts/{name}:{node.lineno}")
    return result


if __name__ == "__main__":
    print(json.dumps(catalog(), indent=2, sort_keys=True))
