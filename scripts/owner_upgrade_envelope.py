#!/usr/bin/env python3
"""Bounded RSA-OAEP/AES-GCM transport. This is NEVER an APK signing key.

Only fixed owner APK roles are transported. Expected bytes and provenance come
from the reviewed local config, never from an untrusted transfer manifest.
"""
import base64
import hashlib
import json
import os
from pathlib import Path
import re
import time

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding, rsa
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

SCHEMA = 1
REPOSITORY = "Brenmack15/Astravar-Overlay"
WORKFLOW = ".github/workflows/owner-upgrade-private.yml"
OWNER_CERT = "4c5cb0113cae5d0dee2619ee30f5b3c593d4aa897e1fa38dee7ed5561ec4e847"
BASELINE = "3543e83bfca5e589f1bbedb09964431ce94a2319"
MAX_APK = 128 * 1024 * 1024
CHUNK = 512 * 1024
MAX_CHUNKS = 1026
MAX_JSON = 1024 * 1024
ROLES = ("baseline", "candidate")
ALL_ROLES = (*ROLES, "observer", "observer-test")
PACKAGES = {"baseline": ("com.yazsras.astravar", 49),
            "candidate": ("com.yazsras.astravar", 50),
            "observer": ("com.yazsras.astravar.target", 1),
            "observer-test": ("com.yazsras.astravar.target.test", 1)}


def require(condition, message):
    if not condition:
        raise ValueError(message)


def exact_keys(value, keys):
    require(isinstance(value, dict) and set(value) == set(keys), "Unexpected object fields")


def canonical(value):
    return json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=True).encode()


def digest(data):
    return hashlib.sha256(data).hexdigest()


def hex_value(value, length=64):
    require(isinstance(value, str) and re.fullmatch("[0-9a-f]{%d}" % length, value), "Invalid digest")


def positive(value):
    require(type(value) is int and 0 < value < 2**63, "Expected positive integer")


def strict_json(data):
    require(len(data) <= MAX_JSON, "JSON exceeds bound")
    def pairs(items):
        result = {}
        for key, value in items:
            require(key not in result, "Duplicate JSON field")
            result[key] = value
        return result
    return json.loads(data, object_pairs_hook=pairs,
                      parse_constant=lambda _: (_ for _ in ()).throw(ValueError("Nonfinite JSON")))


def read_json(path):
    require(path.is_file() and not path.is_symlink(), "Expected a regular JSON file")
    require(path.stat().st_size <= MAX_JSON, "JSON exceeds bound")
    return strict_json(path.read_bytes())


def validate_config(config):
    exact_keys(config, ("schema", "enabled", "repository", "candidate_source", "baseline_source",
                        "owner_certificate_sha256", "gates", "observer_artifact", "apks"))
    require(config["schema"] == SCHEMA and config["enabled"] is True,
            "Owner upgrade transport is disabled until concrete reviewed pins exist")
    require(config["repository"] == REPOSITORY, "Unexpected repository")
    hex_value(config["candidate_source"], 40)
    require(config["baseline_source"] == BASELINE, "Frozen code49 baseline changed")
    require(config["owner_certificate_sha256"] == OWNER_CERT, "Owner identity changed")
    require(isinstance(config["gates"], list) and len(config["gates"]) == 2, "Require both source gates")
    paths = set()
    for gate in config["gates"]:
        exact_keys(gate, ("run_id", "run_attempt", "workflow_path", "source_sha"))
        positive(gate["run_id"]); positive(gate["run_attempt"])
        require(gate["source_sha"] == config["candidate_source"], "Gate source mismatch")
        paths.add(gate["workflow_path"])
    require(paths == {".github/workflows/android.yml", ".github/workflows/astravar-3.0.yml"},
            "Require exact native and full gate workflow paths")
    artifact = config["observer_artifact"]
    exact_keys(artifact, ("run_id", "run_attempt", "artifact_id", "name", "digest", "source_sha",
                          "reference_apk_sha256", "reference_apk_size"))
    for field in ("run_id", "run_attempt", "artifact_id", "reference_apk_size"):
        positive(artifact[field])
    hex_value(artifact["digest"]); hex_value(artifact["reference_apk_sha256"])
    require(artifact["name"] == "android-verification" and
            artifact["source_sha"] == config["candidate_source"], "Unexpected native artifact")
    native = next(g for g in config["gates"] if g["workflow_path"].endswith("/android.yml"))
    require(all(native[k] == artifact[k] for k in ("run_id", "run_attempt")), "Artifact is from another attempt")
    require(artifact["reference_apk_size"] <= MAX_APK, "Reference APK exceeds limit")
    exact_keys(config["apks"], ALL_ROLES)
    for role, expected in config["apks"].items():
        exact_keys(expected, ("sha256", "size", "package", "version_code", "certificate_sha256", "source_sha"))
        hex_value(expected["sha256"]); hex_value(expected["certificate_sha256"])
        require(type(expected["size"]) is int and 0 < expected["size"] <= MAX_APK, "APK size exceeds bound")
        require((expected["package"], expected["version_code"]) == PACKAGES[role], "APK role identity mismatch")
        expected_source = BASELINE if role == "baseline" else config["candidate_source"]
        require(expected["source_sha"] == expected_source, "APK source mismatch")
        if role in ROLES:
            require(expected["certificate_sha256"] == OWNER_CERT, "Owner signer mismatch")
    require(config["apks"]["observer"]["certificate_sha256"] ==
            config["apks"]["observer-test"]["certificate_sha256"] != OWNER_CERT,
            "Observer pair must share its separate test signer")
    return config


def binding(config, run_id, attempt, receiver_commit):
    validate_config(config)
    positive(run_id); positive(attempt); hex_value(receiver_commit, 40)
    return {"repository": REPOSITORY, "workflow_path": WORKFLOW,
            "receiver_commit": receiver_commit, "run_id": run_id, "run_attempt": attempt,
            "config_sha256": digest(canonical(config)),
            "transfer_branch": f"owner-upgrade-transfer/{run_id}-{attempt}"}


def private_write(path, data):
    with path.open("xb") as handle:
        os.chmod(path, 0o600)
        handle.write(data)


def create_recipient(config, run_id, attempt, receiver_commit, private, public, now=None):
    context = binding(config, run_id, attempt, receiver_commit)
    require(not private.exists() and not public.exists(), "Recipient directories must be fresh")
    private.mkdir(mode=0o700, parents=True); public.mkdir(mode=0o700, parents=True)
    key = rsa.generate_private_key(public_exponent=65537, key_size=3072)
    private_write(private / "transport-private.pem", key.private_bytes(
        serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8, serialization.NoEncryption()))
    pem = key.public_key().public_bytes(serialization.Encoding.PEM, serialization.PublicFormat.SubjectPublicKeyInfo)
    created = int(time.time() if now is None else now)
    recipient = {"schema": SCHEMA, "purpose": "ephemeral-apk-transport-not-app-signing",
                 "binding": context, "created_at": created, "expires_at": created + 1800,
                 "public_key_pem": pem.decode(), "public_key_sha256": digest(pem)}
    private_write(public / "recipient.json", canonical(recipient) + b"\n")
    return recipient


def validate_recipient(recipient, expected_binding, now=None):
    exact_keys(recipient, ("schema", "purpose", "binding", "created_at", "expires_at",
                           "public_key_pem", "public_key_sha256"))
    require(recipient["schema"] == SCHEMA and
            recipient["purpose"] == "ephemeral-apk-transport-not-app-signing", "Unexpected recipient purpose")
    require(recipient["binding"] == expected_binding, "Recipient run/attempt/source/config binding mismatch")
    now = int(time.time() if now is None else now)
    require(type(recipient["created_at"]) is int and type(recipient["expires_at"]) is int and
            recipient["created_at"] <= now < recipient["expires_at"] <= recipient["created_at"] + 1800,
            "Recipient expired or invalid lifetime")
    pem = recipient["public_key_pem"].encode()
    require(len(pem) < 4096 and digest(pem) == recipient["public_key_sha256"], "Recipient key digest mismatch")
    key = serialization.load_pem_public_key(pem)
    require(isinstance(key, rsa.RSAPublicKey) and key.key_size == 3072 and
            key.public_numbers().e == 65537, "Expected RSA-3072 transport recipient")
    return key


def check_file(path, expected):
    require(path.is_file() and not path.is_symlink(), "APK must be a regular file")
    require(path.stat().st_size == expected["size"] <= MAX_APK, "APK byte count mismatch")
    data = path.read_bytes()
    require(digest(data) == expected["sha256"], "APK digest mismatch")
    return data


def oaep():
    return padding.OAEP(mgf=padding.MGF1(hashes.SHA256()), algorithm=hashes.SHA256(), label=None)


def associated_data(recipient, config, role):
    return canonical({"schema": SCHEMA, "recipient_sha256": digest(canonical(recipient)),
                      "binding": recipient["binding"], "role": role, "expected_apk": config["apks"][role]})


def encrypt(config, recipient, expected_binding, files, output, now=None, chunk_size=CHUNK):
    validate_config(config)
    key = validate_recipient(recipient, expected_binding, now)
    require(set(files) == set(ROLES), "Only the two pinned owner APKs may be encrypted")
    require(not output.exists(), "Transfer output must be fresh")
    require(type(chunk_size) is int and 256 * 1024 <= chunk_size <= 1024 * 1024, "Chunk size outside limits")
    # Verify both inputs before writing any transport material.
    data = {role: check_file(files[role], config["apks"][role]) for role in ROLES}
    output.mkdir(mode=0o700, parents=True); (output / "chunks").mkdir(mode=0o700)
    aes_key = AESGCM.generate_key(bit_length=256)
    cipher = AESGCM(aes_key)
    manifest = {"schema": SCHEMA, "recipient_sha256": digest(canonical(recipient)),
                "binding": expected_binding, "wrapped_key": base64.b64encode(key.encrypt(aes_key, oaep())).decode(),
                "chunk_size": chunk_size, "files": []}
    number = 0
    nonces = set()
    for role in ROLES:
        nonce = os.urandom(12)
        while nonce in nonces:
            nonce = os.urandom(12)
        nonces.add(nonce)
        encrypted = cipher.encrypt(nonce, data[role], associated_data(recipient, config, role))
        record = {"role": role, "nonce": base64.b64encode(nonce).decode(), "chunks": [],
                  "ciphertext_sha256": digest(encrypted), "ciphertext_size": len(encrypted)}
        for offset in range(0, len(encrypted), chunk_size):
            chunk = encrypted[offset:offset + chunk_size]
            name = f"chunks/{number:05d}.bin"; number += 1
            private_write(output / name, chunk)
            record["chunks"].append({"path": name, "size": len(chunk), "sha256": digest(chunk)})
        manifest["files"].append(record)
    private_write(output / "manifest.json", canonical(manifest) + b"\n")
    validate_transfer_tree(output, manifest)
    return manifest


def manifest_paths(manifest):
    exact_keys(manifest, ("schema", "recipient_sha256", "binding", "wrapped_key", "chunk_size", "files"))
    require(manifest["schema"] == SCHEMA, "Unsupported envelope schema")
    require(type(manifest["chunk_size"]) is int and 256 * 1024 <= manifest["chunk_size"] <= 1024 * 1024, "Chunk size outside limits")
    require(isinstance(manifest["files"], list) and len(manifest["files"]) == 2, "Unexpected APK roles")
    paths = {"manifest.json"}; number = 0
    nonces = set()
    for role, record in zip(ROLES, manifest["files"]):
        exact_keys(record, ("role", "nonce", "chunks", "ciphertext_sha256", "ciphertext_size"))
        require(record["role"] == role, "APK roles reordered or duplicated")
        nonce = base64.b64decode(record["nonce"], validate=True)
        require(len(nonce) == 12 and nonce not in nonces, "Invalid/repeated AES-GCM nonce")
        nonces.add(nonce); hex_value(record["ciphertext_sha256"])
        require(type(record["ciphertext_size"]) is int and 16 < record["ciphertext_size"] <= MAX_APK + 16,
                "Ciphertext size exceeds limit")
        require(isinstance(record["chunks"], list) and 0 < len(record["chunks"]) <= MAX_CHUNKS,
                "Invalid chunk count")
        total = 0
        for index, chunk in enumerate(record["chunks"]):
            exact_keys(chunk, ("path", "size", "sha256"))
            require(chunk["path"] == f"chunks/{number:05d}.bin", "Chunk path/order mismatch")
            require(type(chunk["size"]) is int and 0 < chunk["size"] <= manifest["chunk_size"], "Chunk exceeds bound")
            require(index == len(record["chunks"]) - 1 or chunk["size"] == manifest["chunk_size"], "Nonfinal partial chunk")
            hex_value(chunk["sha256"]); paths.add(chunk["path"])
            total += chunk["size"]; number += 1
        require(total == record["ciphertext_size"], "Chunk total mismatch")
    require(number <= MAX_CHUNKS, "Envelope exceeds total chunk limit")
    return paths


def validate_transfer_tree(directory, manifest):
    expected = manifest_paths(manifest)
    actual = set()
    for path in directory.rglob("*"):
        require(not path.is_symlink(), "Transfer symlink forbidden")
        relative = path.relative_to(directory).as_posix()
        if path.is_dir():
            require(relative == "chunks", "Unexpected transfer directory")
        else:
            require(path.is_file(), "Nonregular transfer file")
            actual.add(relative)
    require(actual == expected, "Transfer contains missing or extra files; publication prohibited")


def decrypt(config, recipient, expected_binding, private_key, transfer, destination, now=None):
    validate_config(config)
    validate_recipient(recipient, expected_binding, now)
    manifest = read_json(transfer / "manifest.json")
    validate_transfer_tree(transfer, manifest)
    require(manifest["binding"] == expected_binding and
            manifest["recipient_sha256"] == digest(canonical(recipient)), "Envelope binding mismatch")
    require(not destination.exists(), "Decryption destination must be fresh")
    require(private_key.is_file() and not private_key.is_symlink(), "Private transport key must be regular")
    key = serialization.load_pem_private_key(private_key.read_bytes(), password=None)
    require(isinstance(key, rsa.RSAPrivateKey) and key.key_size == 3072, "Unexpected private transport key")
    public = key.public_key().public_bytes(serialization.Encoding.PEM, serialization.PublicFormat.SubjectPublicKeyInfo)
    require(digest(public) == recipient["public_key_sha256"], "Wrong private transport key")
    wrapped = base64.b64decode(manifest["wrapped_key"], validate=True)
    require(len(wrapped) == 384, "Invalid wrapped key length")
    aes_key = key.decrypt(wrapped, oaep()); require(len(aes_key) == 32, "Expected AES-256 key")
    verified = {}
    for record in manifest["files"]:
        role = record["role"]
        require(record["ciphertext_size"] == config["apks"][role]["size"] + 16, "Unexpected plaintext size")
        parts = []
        for chunk in record["chunks"]:
            path = transfer / chunk["path"]
            require(path.stat().st_size == chunk["size"], "Chunk byte count mismatch")
            part = path.read_bytes(); require(digest(part) == chunk["sha256"], "Chunk digest mismatch")
            parts.append(part)
        encrypted = b"".join(parts)
        require(digest(encrypted) == record["ciphertext_sha256"], "Ciphertext digest mismatch")
        plaintext = AESGCM(aes_key).decrypt(base64.b64decode(record["nonce"], validate=True), encrypted,
                                           associated_data(recipient, config, role))
        require(digest(plaintext) == config["apks"][role]["sha256"], "Decrypted APK digest mismatch")
        verified[role] = plaintext
    # Nothing is exposed to installation until both tags and expected hashes pass.
    destination.mkdir(mode=0o700, parents=True)
    for role, plaintext in verified.items():
        private_write(destination / (role + ".apk"), plaintext)
    return manifest
