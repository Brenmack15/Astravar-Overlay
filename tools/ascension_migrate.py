#!/usr/bin/env python3
"""Offline, single-continuity migration and recovery for tool-created rehearsal stores."""
import argparse
import base64
import binascii
import fcntl
import hashlib
import io
import json
import os
import re
import sqlite3
import subprocess
import sys
import time
import uuid
from contextlib import closing, contextmanager
from pathlib import Path
from zipfile import ZIP_DEFLATED, BadZipFile, ZipFile

from ascension_migrate_reviewed import FORMAT as REVIEW_FORMAT, project as reviewed_graph, template as mapping_template, scoped_identity
from ascension_migrate_parity import mechanical_parity
from ascension_migrate_legacy import (HASH, MAX_BYTES, compare_legacy, encode, file_hash, legacy_review, loads,
                                     read_limited, review_bytes, sha, verify, weapon_review)

ROOT = Path(__file__).resolve().parents[1]
SQL = ROOT / "backend/owner-bridge/schema.sql"
FORMAT = "astravar-cloud-recovery"
LAB = "astravar-isolated-migration-v1"
TABLES = {
    "cloud_grant_audit": ("event_id",),
    "cloud_media_uploads": ("upload_id",),
    "cloud_media_previews": ("upload_id",),
    "cloud_entities": ("id",),
    "cloud_links": ("from_id", "relation", "to_id"),
    "cloud_requests": ("command_id",),
    "cloud_receipts": ("command_id",),
    "cloud_changes": ("revision", "ordinal"),
    "cloud_tombstones": ("id",),
    "cloud_evidence_segments": ("command_id", "part"),
    "cloud_evidence_subjects": ("command_id", "part", "entity_id"),
    "cloud_evidence_erased": ("command_id", "part"),
    "cloud_media_gc": ("command_id", "upload_id"),
}
OMITTED = ["account and device credentials", "standing grants", "legacy connection outboxes and mirrors"]
LAB_SCHEMA = """
CREATE TABLE rehearsal_sources(digest TEXT PRIMARY KEY, content BLOB NOT NULL, map_json TEXT NOT NULL);
"""
LIMIT = 256 * 1024 * 1024
SELECT_TENANT = " WHERE owner_id=? AND continuity_id=?"
MODE = {"legacy", "staged", "cloud"}


def now():
    return int(time.time() * 1000)


def db_connect(path):
    db = sqlite3.connect(path, isolation_level=None, timeout=10)
    db.row_factory = sqlite3.Row
    db.execute("PRAGMA foreign_keys=ON")
    return db


def make_database(path):
    db = db_connect(path)
    try:
        db.executescript(SQL.read_text() + LAB_SCHEMA)
    except BaseException:
        db.close()
        raise
    return db


def columns(db, table):
    return tuple(r["name"] for r in db.execute(f"PRAGMA table_info({table})"))


def generation():
    return uuid.uuid4().hex + ".sqlite"


def create_private(path):
    return os.fdopen(os.open(path, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600), "wb")


def sync_directory(folder):
    directory = os.open(folder, os.O_RDONLY)
    try:
        os.fsync(directory)
    finally:
        os.close(directory)


def store_marker(folder, marker):
    temp = folder / (uuid.uuid4().hex + ".tmp")
    with create_private(temp) as out:
        out.write(encode(marker))
        out.flush()
        os.fsync(out.fileno())
    os.replace(temp, folder / "rehearsal.json")
    sync_directory(folder)


def init(folder, owner, continuity):
    verify(all(isinstance(key, str) and 0 < len(key.strip()) <= 200 for key in (owner, continuity)), "Invalid scope")
    folder.mkdir(mode=0o700, parents=False, exist_ok=False)
    (folder / "objects").mkdir(mode=0o700)
    (folder / ".lock").touch(mode=0o600, exist_ok=False)
    filename = generation()
    with closing(make_database(folder / filename)) as db:
        db.execute("INSERT INTO cloud_continuities VALUES (?,?, 'staged',1,0,1)", (owner, continuity))
    store_marker(folder, {"format": LAB, "owner": owner, "continuity": continuity, "current": filename,
                          "previous": None, "import": None, "accepted": None, "receipt": None})
    return {"status": "STAGED", "epoch": 1, "revision": 0, "production": False}


@contextmanager
def locked(folder):
    verify(folder.is_dir() and not folder.is_symlink(), "A local rehearsal directory is required")
    lock = folder / ".lock"
    verify(lock.is_file() and not lock.is_symlink(), "Unknown rehearsal directory")
    with lock.open("rb") as stream:
        fcntl.flock(stream, fcntl.LOCK_EX)
        marker = loads(read_limited(folder / "rehearsal.json", 8192))
        verify(marker.get("format") == LAB, "Only tool-created isolated stores are accepted")
        filename = marker["current"]
        verify(re.fullmatch("[a-f0-9]{32}[.]sqlite", filename), "Invalid local generation")
        path = folder / filename
        verify(path.is_file() and not path.is_symlink(), "Missing reviewed local generation")
        with closing(db_connect(path)) as db:
            db.execute("BEGIN IMMEDIATE")
            try:
                yield marker, db
            finally:
                if db.in_transaction:
                    db.rollback()


def scope(marker):
    return (marker["owner"], marker["continuity"])


def state(db, tenant):
    r = db.execute("SELECT * FROM cloud_continuities" + SELECT_TENANT, tenant).fetchone()
    verify(r is not None, "Missing continuity")
    return dict(r)


def rows_by_family(db, tenant):
    actual = {r[0] for r in db.execute("SELECT name FROM sqlite_master WHERE type='table' AND name GLOB 'cloud_*'")}
    # Rate-limit reservations are installation-local and can be absent in a pre-0013 snapshot.
    # Recreating traffic debt after recovery could strand clients without preserving any gameplay.
    verify(actual - {"cloud_rate_buckets"} == set(TABLES) | {"cloud_grants", "cloud_guards", "cloud_continuities"},
           "Unclassified or missing canonical table; update the versioned recovery mapping")
    result = {}
    for table, pk in TABLES.items():
        cols = ",".join(columns(db, table))
        query = f"SELECT {cols} FROM {table}" + SELECT_TENANT + " ORDER BY " + ",".join(pk)
        result[table] = [dict(item) for item in db.execute(query, tenant)]
    return result


def media_locations(rows, tenant):
    """Derive the private Worker object keys for each reviewed asset and pending ready upload."""
    assets = []
    for entity in rows["cloud_entities"]:
        if entity["kind"] == "media_asset":
            value = loads(entity["data_json"])
            assets.append((value, value.get("uploadId")))
    for row in rows["cloud_media_uploads"]:
        verify(row["state"] == "ready", "An image transfer or cleanup is in progress")
        value = loads(row["metadata_json"])
        verify(row["digest"] == value.get("sha256"), "Media reservation digest differs from the stored metadata")
        assets.append((value, row["upload_id"]))
    result = {}
    for value, upload in assets:
        verify(isinstance(upload, str) and upload and value.get("mime") == "image/png", "Unknown private image identity or MIME")
        prefix = "ascension/media/" + sha(encode([*tenant, upload, value.get("sha256")]))
        for h, n, width, height, variant in [
                ("sha256", "byteLength", "width", "height", "original"),
                ("thumbnailSha256", "thumbnailByteLength", "thumbnailWidth", "thumbnailHeight", "thumbnail")]:
            digest, size, w, hgt = value.get(h), value.get(n), value.get(width), value.get(height)
            verify(isinstance(digest, str) and HASH.fullmatch(digest) and type(size) is int and 0 < size <= 4 << 20
                   and type(w) is int and type(hgt) is int and w > 0 and hgt > 0, "Unresolved private media metadata")
            path = prefix + "/" + variant + ".png"
            verify(path not in result or result[path] == (digest, size, w, hgt), "Conflicting private asset versions")
            result[path] = (digest, size, w, hgt)
    return result


def sources(db):
    result = {}
    for digest, content, report in db.execute("SELECT digest,content,map_json FROM rehearsal_sources ORDER BY digest"):
        original, mapping = bytes(content), loads(report)
        verify(sha(original) == digest == mapping.get("sha256"), "Staged original or source mapping digest changed")
        result[digest] = (original, mapping)
    return result


def body_digest(rows, evidence, media):
    return sha(encode({"tables": {t: sha(encode(rows[t])) for t in TABLES},
                       "sources": {k: sha(encode(v[1])) for k, v in sorted(evidence.items())},
                       "media": sorted(media)}))


def marks(db, tenant, rows=None):
    rows = rows if rows is not None else rows_by_family(db, tenant)
    # A stale approval must also notice a permission/legacy-inbox change since review.
    grants = [dict(r) for r in db.execute("SELECT * FROM cloud_grants" + SELECT_TENANT + " ORDER BY subject", tenant)]
    legacy = {}
    for table in ("bridge_campaign_commands", "bridge_campaign_receipts", "bridge_hp_proposals",
                  "bridge_hp_receipts", "bridge_proposals", "bridge_receipts"):
        legacy[table] = [dict(r) for r in db.execute(f"SELECT * FROM {table} WHERE owner_id=? AND campaign_id=? ORDER BY command_id", tenant)]
    return sha(encode([state(db, tenant), body_digest(rows, sources(db), media_locations(rows, tenant)), grants, legacy]))


def package_manifest(tenant, origin, entries):
    return {"format": FORMAT, "version": 1, "owner": tenant[0], "continuity": tenant[1],
            "source": origin, "excluded": OMITTED, "entries": entries}


def bundle_contents(tenant, origin, rows, evidence, objects):
    payloads = {}
    items = {}
    def add(key, body, count):
        verify(len(body) <= MAX_BYTES, "An individual transport entry exceeds 64 MiB")
        payloads[key] = body
        items[key] = {"bytes": len(body), "sha256": sha(body), "count": count}
    for table in TABLES:
        add("rows/" + table + ".json", encode(rows[table]), len(rows[table]))
    for key, (raw, report) in evidence.items():
        verify(sha(raw) == key and report["sha256"] == key, "Source evidence digest mismatch")
        add("sources/" + key + ".bin", raw, 1)
        add("sources/" + key + ".json", encode(report), 1)
    for key, raw in objects.items():
        verify(sha(raw) == key and 0 < len(raw) <= MAX_BYTES, "Private asset digest mismatch")
        add("media/" + key, raw, 1)
    verify(sum(len(data) for data in payloads.values()) <= LIMIT, "Package exceeds the 256 MiB budget")
    payloads["manifest.json"] = encode(package_manifest(tenant, origin, items))
    return payloads


def write_bundle(path, payloads):
    # Publish only a verified completed file. Never replace an existing backup.
    temp = path.with_name("." + uuid.uuid4().hex + ".partial")
    try:
        with create_private(temp) as output:
            with ZipFile(output, "w", compression=ZIP_DEFLATED) as archive:
                for key in sorted(payloads):
                    archive.writestr(key, payloads[key])
            output.flush()
            os.fsync(output.fileno())
        bundle = read_bundle(temp)
        check_database(bundle)
        os.link(temp, path)
        sync_directory(path.parent)
    finally:
        temp.unlink(missing_ok=True)
    return summary(bundle)


def save_private_copy(destination, output):
    """Publish a new 0600 file after independent byte verification."""
    temporary = destination.with_name(uuid.uuid4().hex + ".partial")
    try:
        with create_private(temporary) as stream:
            stream.write(output)
            stream.flush()
            os.fsync(stream.fileno())
        verify(file_hash(temporary) == sha(output), "Recovery file failed independent byte readback")
        os.link(temporary, destination)
        sync_directory(destination.parent)
    finally:
        temporary.unlink(missing_ok=True)


def extract_source(package, destination, digest):
    """Recover the exact original native artifact without exposing it through gameplay APIs."""
    verify(isinstance(digest, str) and HASH.fullmatch(digest), "Invalid native source digest")
    bundle = read_bundle(package)
    check_database(bundle)
    verify(digest in bundle["sources"], "No original with this digest in the scoped recovery package")
    original, report = bundle["sources"][digest]
    verify(sha(original) == digest, "Native source digest differs")
    save_private_copy(destination, original)
    return {"status":"EXTRACTED_ORIGINAL", "sourceSha256":digest, "bytes":len(original), "format":report["format"]}


def protect(source, destination, key_file, decrypt=False):
    """Publish an authenticated backup or a validated plaintext copy using a separate recovery key."""
    try:
        from cryptography.fernet import Fernet, InvalidToken
    except ImportError as error:
        raise ValueError("Install Python cryptography to use encrypted recovery files") from error
    verify(key_file.is_file() and not key_file.is_symlink() and key_file.stat().st_mode & 0o077 == 0,
           "A private recovery key file is required")
    try:
        cipher = Fernet(read_limited(key_file, 128).strip())
    except (ValueError, TypeError) as error:
        raise ValueError("Invalid recovery key format") from error
    if decrypt:
        try:
            token = read_limited(source, 2 * (LIMIT + (2 << 20)))
            binary = base64.b64decode(token, altchars=b"-_", validate=True)
            if base64.urlsafe_b64encode(binary) != token:
                raise InvalidToken
            output = cipher.decrypt(token)
        except (InvalidToken, binascii.Error) as error:
            raise ValueError("Backup authentication failed") from error
    else:
        # Validate the exact original bytes that enter encryption even if the source changes concurrently.
        output = read_limited(source, LIMIT + (2 << 20))
    bundle = read_bundle(output)
    check_database(bundle)
    if not decrypt:
        output = cipher.encrypt(output)
    save_private_copy(destination, output)
    return {"status": "UNSEALED" if decrypt else "SEALED", "fileSha256": sha(output), **summary(bundle)}


def read_objects(folder, locations):
    directory = folder / "objects"
    verify(directory.is_dir() and not directory.is_symlink(), "Invalid rehearsal object directory")
    result = {}
    for name, (key, size, _, _) in sorted(locations.items()):
        path = directory / name
        verify(path.resolve().is_relative_to(directory.resolve()), "Private asset escaped its root")
        body = read_limited(path)
        verify(sha(body) == key and len(body) == size, "Stored media digest or length mismatch")
        result[key] = body
    return result


def export(folder, path):
    with locked(folder) as (marker, db):
        tenant = scope(marker)
        s = state(db, tenant)
        rows = rows_by_family(db, tenant)
        objects = read_objects(folder, media_locations(rows, tenant))
        origin = {"authority": s["authority"], "epoch": s["epoch"], "revision": s["revision"], "schema": 1}
        return write_bundle(path, bundle_contents(tenant, origin, rows, sources(db), objects))


def convert(path, output, tenant, shared_ids, known_records=()):
    report, entities, original = legacy_review(path, *tenant, shared_ids, known_records)
    verify(not report["blockers"], "Native records remain held; inspect-legacy lists each unresolved mapping")
    rows = {table: [] for table in TABLES}
    rows["cloud_entities"] = sorted(entities, key=lambda e: e["id"])
    rows["cloud_links"] = report["links"]
    origin = {"authority": "legacy", "epoch": 1, "revision": 0, "schema": 1}
    return write_bundle(output, bundle_contents(tenant, origin, rows, {report["sha256"]: (original, report)}, {}))


def convert_reviewed(paths, plan_path, output, tenant):
    originals = [read_limited(p) for p in paths]
    raw = {sha(body): body for body in originals}
    verify(len(raw) == len(originals), "Duplicate original export")
    plan = loads(read_limited(plan_path))
    reports, entities, links, objects = reviewed_graph(raw, plan, tenant)
    rows = {table: [] for table in TABLES}
    rows["cloud_entities"], rows["cloud_links"] = entities, links
    evidence = {digest: (body, reports[digest]) for digest, body in raw.items()}
    origin = {"authority": "legacy", "epoch": 1, "revision": 0, "schema": 1}
    return write_bundle(output, bundle_contents(tenant, origin, rows, evidence, objects))


def read_bundle(path):
    if isinstance(path, bytes):
        verify(len(path) <= LIMIT + (2 << 20), "Oversized local package")
        source = io.BytesIO(path)
    elif isinstance(path, io.BufferedReader):
        verify(0 < os.fstat(path.fileno()).st_size <= LIMIT + (2 << 20), "Oversized local package")
        source = path
    else:
        verify(path.is_file() and not path.is_symlink() and path.stat().st_size <= LIMIT + (2 << 20),
               "Missing, symbolic or oversized local package")
        source = path
    with ZipFile(source) as archive:
        infos = archive.infolist()
        names = {i.filename for i in infos}
        verify(len(names) == len(infos) <= 10_000 and sum(i.file_size for i in infos) <= LIMIT + (2 << 20),
               "Duplicate entries or expanded budget exceeded")
        verify("manifest.json" in names and archive.getinfo("manifest.json").file_size <= 2 << 20, "Invalid manifest size")
        manifest = loads(archive.read("manifest.json"))
        verify(set(manifest) == {"format", "version", "owner", "continuity", "source", "excluded", "entries"} and
               manifest["format"] == FORMAT and type(manifest["version"]) is int and manifest["version"] == 1 and
               manifest["excluded"] == OMITTED, "Unsupported bundle version or coverage declaration")
        tenant = (manifest["owner"], manifest["continuity"])
        verify(all(type(k) is str and 0 < len(k.strip()) <= 200 for k in tenant), "Invalid tenant scope")
        origin = manifest["source"]
        verify(set(origin) == {"authority", "epoch", "revision", "schema"} and origin["authority"] in MODE and
               type(origin["epoch"]) is int and origin["epoch"] >= 1 and type(origin["revision"]) is int and
               origin["revision"] >= 0 and origin["schema"] == 1, "Invalid source authority")
        descriptors = manifest["entries"]
        verify(isinstance(descriptors, dict) and set(descriptors) == names - {"manifest.json"} and
               all("rows/" + t + ".json" in names for t in TABLES), "Incomplete or undeclared bundle entries")
        rows, media, raw_sources, mappings = {}, {}, {}, {}
        for name, meta in descriptors.items():
            verify(type(meta) is dict and set(meta) == {"bytes", "sha256", "count"} and
                   type(meta["bytes"]) is int and 0 <= meta["bytes"] == archive.getinfo(name).file_size <= MAX_BYTES and
                   type(meta["count"]) is int and meta["count"] >= 0, "Entry metadata mismatch")
            body = archive.read(name)
            verify(sha(body) == meta["sha256"], "Entry digest mismatch")
            if name.startswith("rows/") and name.endswith(".json"):
                table = name[5:-5]
                verify(table in TABLES, "Unsupported table")
                values = loads(body)
                verify(isinstance(values, list) and len(values) == meta["count"], "Table count mismatch")
                rows[table] = values
            else:
                match = re.fullmatch(r"(media)/([0-9a-f]{64})|sources/([0-9a-f]{64})\.(bin|json)", name)
                verify(match is not None and meta["count"] == 1, "Invalid entry name or count")
                if match[1]:
                    verify(sha(body) == match[2] and body, "Media identity mismatch")
                    media[match[2]] = body
                elif match[4] == "bin":
                    verify(sha(body) == match[3], "Original source bytes mismatch")
                    raw_sources[match[3]] = body
                else:
                    report = loads(body)
                    verify(report["sha256"] == match[3] and not report.get("blockers") and
                           (report.get("owner"), report.get("continuity")) == tenant,
                           "Unreviewed or foreign source mapping")
                    mappings[match[3]] = report
        verify(raw_sources.keys() == mappings.keys(), "Missing original source or mapping")
        locations = media_locations(rows, tenant)
        verify({key: n for key, n, _, _ in locations.values()} == {k: len(v) for k, v in media.items()},
               "Missing, mis-sized or unreferenced private asset")
        for digest, length, width, height in set(locations.values()):
            # Decode through the same bounded PNG implementation that accepts the upload.
            script = """import {inspectMediaPng} from './backend/owner-bridge/cloud-media-codec.mjs';
                import fs from 'node:fs';
                const {meta}=await inspectMediaPng(new Uint8Array(fs.readFileSync(0)));
                process.stdout.write(JSON.stringify([meta.width,meta.height]));"""
            result = subprocess.run(["node", "--input-type=module", "-e", script], cwd=ROOT, input=media[digest],
                                    capture_output=True, timeout=15, check=False)
            verify(result.returncode == 0 and loads(result.stdout) == [width, height], "Private image decoding or dimensions failed")
    return {"manifest": manifest, "tenant": tenant, "rows": rows, "objects": media,
            "sources": {k: (v, mappings[k]) for k, v in raw_sources.items()},
            "digest": sha(encode(manifest))}


def verify_evidence(rows):
    """Every immutable header must be backed by its exact stored bytes or a deletion proof."""
    available = {}
    for row in rows["cloud_evidence_segments"]:
        digest = sha(row["payload_json"].encode("utf-8"))
        verify(digest == row["digest"], "Immutable evidence segment digest mismatch")
        available[row["command_id"], row["part"]] = digest
    tombstones = {r["id"]: r["deleted_revision"] for r in rows["cloud_tombstones"]}
    for row in rows["cloud_evidence_erased"]:
        identity = (row["command_id"], row["part"])
        verify(identity not in available and tombstones.get(row["entity_id"]) == row["deleted_revision"],
               "Erased evidence differs from its reviewed deletion marker")
        available[identity] = row["digest"]
    declared = {}
    for table, column in [("cloud_requests", "request_json"), ("cloud_receipts", "result_json")]:
        for row in rows[table]:
            header = loads(row[column])
            if "evidenceVersion" not in header:
                continue
            parts = header.get("parts")
            verify(header["evidenceVersion"] == 1 and isinstance(parts, dict) and parts,
                   "Unsupported immutable evidence format")
            if table == "cloud_requests":
                verify(set(parts) == {"request"}, "Invalid immutable request parts")
            else:
                count = header.get("changeCount")
                verify(type(count) is int and 0 <= count < len(parts) and type(header.get("hadChanges")) is bool
                       and (count == 0 or header["hadChanges"]) and set(parts) == {"result", *(f"c{i}" for i in range(count))},
                       "Invalid immutable receipt change count")
            for part, digest in parts.items():
                identity = row["command_id"], part
                verify(isinstance(digest, str) and HASH.fullmatch(digest) and identity not in declared,
                       "Duplicate or invalid immutable evidence reference")
                declared[identity] = digest
    verify(available == declared, "Missing, unexpected or mismatched immutable evidence")


def stage(db, bundle):
    tenant, rows = bundle["tenant"], bundle["rows"]
    origin = bundle["manifest"]["source"]
    db.execute("UPDATE cloud_continuities SET epoch=?,revision=?"+ SELECT_TENANT,
               (origin["epoch"], origin["revision"], *tenant))
    # All tables and columns originate in checked-in schema, never in an input path or row.
    for table, keys in TABLES.items():
        fields = columns(db, table)
        placeholders = ",".join("?" for _ in fields)
        previous = None
        for row in rows[table]:
            verify(isinstance(row, dict) and set(row) == set(fields), "Missing or unknown portable column")
            verify((row["owner_id"], row["continuity_id"]) == tenant, "Cross-continuity record")
            identity = tuple(row[k] for k in keys)
            verify(previous is None or previous < identity, "Duplicate or unordered canonical identity")
            previous = identity
            db.execute(f"INSERT INTO {table} ({','.join(fields)}) VALUES ({placeholders})", [row[k] for k in fields])
    for digest, (raw, report) in bundle["sources"].items():
        db.execute("INSERT INTO rehearsal_sources VALUES(?,?,?)", (digest, raw, encode(report).decode()))
    verify(not db.execute("PRAGMA foreign_key_check").fetchone(), "Broken campaign relationship")
    mismatch = db.execute("""
      SELECT 1 FROM cloud_requests q LEFT JOIN cloud_receipts r USING(owner_id,continuity_id,command_id)
      WHERE q.owner_id=? AND q.continuity_id=? AND
        ((q.status='PENDING_APPROVAL' AND r.command_id IS NOT NULL) OR
         (q.status<>'PENDING_APPROVAL' AND (r.command_id IS NULL OR r.status<>q.status OR r.access_json<>q.access_json)))
      LIMIT 1""", tenant).fetchone()
    verify(not mismatch, "Requests and immutable terminal outcomes disagree")
    verify(all(r["epoch"] <= origin["epoch"] and r["revision"] <= origin["revision"] for r in rows["cloud_receipts"])
           and all(1 <= r["epoch"] <= origin["epoch"] and r["grant_revision"] > 0 for r in rows["cloud_grant_audit"])
           and all(r["revision"] <= origin["revision"] for r in rows["cloud_changes"])
           and all(r["deleted_revision"] <= origin["revision"] for r in rows["cloud_tombstones"]),
           "Receipt, change or deletion exceeds the source authority boundary")
    verify(not set(r["id"] for r in rows["cloud_tombstones"]).intersection(r["id"] for r in rows["cloud_entities"]),
           "Deleted identity also appears as a playable record")
    verify_evidence(rows)


def check_database(bundle):
    mapped, links = [], []
    reviewed = {}
    converted_objects = {}
    for digest, (original, report) in bundle["sources"].items():
        if report.get("mappingFormat") == REVIEW_FORMAT:
            reviewed.setdefault(report["mappingSha256"], {})[digest] = original
            continue
        actual, entities, _ = review_bytes(original, *bundle["tenant"], report.get("sharedWallets", []), report.get("knownRecords", []))
        verify(actual == report and not actual["blockers"], "Source disposition or scope does not match original bytes")
        mapped.extend(entities)
        links.extend(actual["links"])
    for group in reviewed.values():
        plan = bundle["sources"][next(iter(group))][1]["reviewedMapping"]
        reports, graph, edges, objects = reviewed_graph(group, plan, bundle["tenant"])
        converted_objects.update(objects)
        verify(all(exact_report == bundle["sources"][digest][1] for digest, exact_report in reports.items()),
               "Reviewed mapping changed after source reconciliation")
        mapped.extend(graph)
        links.extend(edges)
    if bundle["manifest"]["source"]["authority"] == "legacy":
        verify(bundle["objects"] == converted_objects, "Private media does not match the reviewed native sources")
        verify(bundle["sources"] and all(not values for table, values in bundle["rows"].items()
                                        if table not in ("cloud_entities", "cloud_links")) and
               sorted(links, key=lambda r: (r["from_id"], r["relation"], r["to_id"])) == bundle["rows"]["cloud_links"] and
               sorted(mapped, key=lambda r: r["id"]) == bundle["rows"]["cloud_entities"],
               "Native conversion differs from independently mapped source records")
    with closing(make_database(":memory:")) as scratch:
        scratch.execute("INSERT INTO cloud_continuities VALUES (?,?,'staged',?,0,1)",
                        (*bundle["tenant"], bundle["manifest"]["source"]["epoch"]))
        scratch.execute("BEGIN")
        stage(scratch, bundle)
        scratch.commit()
        verify(rows_by_family(scratch, bundle["tenant"]) == bundle["rows"], "Independent logical readback differs")


def summary(bundle):
    return {"manifestSha256": bundle["digest"], "source": bundle["manifest"]["source"],
            "counts": {t: len(r) for t, r in bundle["rows"].items()}, "media": len(bundle["objects"]),
            "originalSources": len(bundle["sources"]), "excluded": OMITTED}


def review(marker, db, bundle):
    tenant = scope(marker)
    verify(tenant == bundle["tenant"], "Destination owner or continuity differs")
    baseline = marks(db, tenant)
    actual = state(db, tenant)
    if marker["import"] == bundle["digest"]:
        return {"status": "ALREADY_IMPORTED", "receipt": marker["receipt"], "current": actual,
                "advanced": marker["accepted"] != baseline, **summary(bundle)}
    verify(actual["authority"] == "staged" and actual["revision"] == 0 and actual["epoch"] == 1 and
           not any(rows_by_family(db, tenant).values()) and not sources(db),
           "Import requires a fresh, empty staging generation")
    verify(db.execute("SELECT count(*) FROM cloud_grants" + SELECT_TENANT, tenant).fetchone()[0] == 0,
           "Review an empty destination without pre-existing grants")
    proof = sha(encode(["import", marker["current"], baseline, bundle["digest"]]))
    return {"status": "PREVIEW", "approvalSha256": proof, **summary(bundle)}


def inspect(folder, path=None):
    if path is None:
        with locked(folder) as (marker, db):
            return {"state": state(db, scope(marker)), "fingerprint": marks(db, scope(marker)),
                    "database": str(folder / marker["current"]), "receipt": marker["receipt"]}
    bundle = read_bundle(path)
    check_database(bundle)
    with locked(folder) as (marker, db):
        return review(marker, db, bundle)


def install_objects(folder, bundle):
    root = (folder / "objects").resolve()
    for name, (key, _, _, _) in media_locations(bundle["rows"], bundle["tenant"]).items():
        body = bundle["objects"][key]
        path = root / name
        verify(path.resolve().is_relative_to(root), "Private asset escaped its target")
        path.parent.mkdir(parents=True, mode=0o700, exist_ok=True)
        if not path.exists():
            partial = path.with_name(uuid.uuid4().hex + ".partial")
            try:
                with create_private(partial) as out:
                    out.write(body)
                    out.flush()
                    os.fsync(out.fileno())
                os.link(partial, path)
            finally:
                partial.unlink(missing_ok=True)
        verify(read_limited(path) == body, "Existing private asset differs")


def clone_staging(before, staged, bundle):
    # Enroll the new physical generation at the recovered epoch. Preserve local legacy tables,
    # triggers and sequence state without temporarily relaxing the active DB's writer fences.
    verify(before.execute("SELECT count(*) FROM cloud_continuities").fetchone()[0] == 1 and
           not before.execute("PRAGMA foreign_key_check").fetchone(), "Invalid isolated staging source")
    enrolled = False
    for statement in before.iterdump():
        if statement.startswith('INSERT INTO "cloud_continuities" VALUES('):
            verify(not enrolled, "Unexpected second continuity in staging source")
            staged.execute("INSERT INTO cloud_continuities VALUES (?,?,'staged',?,0,1)",
                           (*bundle["tenant"], bundle["manifest"]["source"]["epoch"]))
            enrolled = True
        else:
            staged.execute(statement)
            if statement == 'BEGIN TRANSACTION;':
                staged.execute('PRAGMA defer_foreign_keys=ON')
    verify(enrolled, "Missing initial staged continuity")


def apply(folder, path, approval):
    bundle = read_bundle(path)
    check_database(bundle)
    with locked(folder) as (marker, db):
        result = review(marker, db, bundle)
        if result["status"] == "ALREADY_IMPORTED":
            return result
        verify(approval == result["approvalSha256"], "Approval does not match the reviewed source and destination")
        dest = generation()
        new_path = folder / dest
        # Backup a consistent old generation. The separate connection can read while the old writer is reserved.
        with closing(db_connect(folder / marker["current"])) as before, closing(db_connect(new_path)) as staged:
            clone_staging(before, staged, bundle)
            staged.execute("BEGIN IMMEDIATE")
            try:
                stage(staged, bundle)
                staged.commit()
            except BaseException:
                staged.rollback()
                raise
        with closing(db_connect(new_path)) as replay:
            verify(rows_by_family(replay, bundle["tenant"]) == bundle["rows"] and sources(replay) == bundle["sources"],
                   "Reopened staging DB failed independent comparison")
            fingerprint = marks(replay, bundle["tenant"])
        install_objects(folder, bundle)
        verify(read_objects(folder, media_locations(bundle["rows"], bundle["tenant"])) == bundle["objects"],
               "Reopened media verification failed")
        receipt = {"operation": "stage_import", "id": uuid.uuid4().hex, "manifestSha256": bundle["digest"],
                   "sourceAuthority": bundle["manifest"]["source"]["authority"],
                   "reviewSha256": approval, "resultSha256": fingerprint, "at": now()}
        store_marker(folder, {**marker, "current": dest, "previous": marker["current"],
                              "previousHash": marks(db, bundle["tenant"]), "import": bundle["digest"],
                              "accepted": fingerprint, "receipt": receipt})
        return {"status": "STAGED", "receipt": receipt, **summary(bundle)}


def control(folder, action, approval=None, carry_pending=False):
    with locked(folder) as (marker, db):
        tenant = scope(marker)
        current = state(db, tenant)
        if action == "cutover" and current["authority"] == "cloud" and approval:
            row = db.execute("SELECT 1 FROM sqlite_master WHERE name='rehearsal_decisions'").fetchone()
            if row and db.execute("SELECT 1 FROM rehearsal_decisions WHERE id=? AND kind='cutover'", (approval,)).fetchone():
                return {"status": "ALREADY_COMMITTED", "decision": approval, "state": current}
        if action == "rollback" and approval and (marker["receipt"] or {}).get("decision") == approval:
            return {"status": "ALREADY_ROLLED_BACK", "receipt": marker["receipt"]}
        verify(marker["import"] and marker["previous"] and current["authority"] == "staged",
               "Requires a verified staged generation; preserve any subsequently committed gameplay")
        fingerprint = marks(db, tenant)
        verify(fingerprint == marker["accepted"], "Staging changed after import; obtain a new reconciliation")
        rows = rows_by_family(db, tenant)
        read_objects(folder, media_locations(rows, tenant))
        verify(action in ("cutover", "rollback"), "Unsupported administrative action")
        pending = [r["command_id"] for r in rows["cloud_requests"] if r["status"] == "PENDING_APPROVAL"]
        verify(not carry_pending or (action == "cutover" and marker["receipt"].get("sourceAuthority") == "cloud" and pending),
               "Carrying pending approvals requires a verified prior cloud snapshot with outstanding requests")
        if action == "cutover":
            verify(not any(report.get("cutoverHolds", 0) for _, report in sources(db).values()),
                   "Unresolved source records prevent authority cutover")
            verify(not any(r["kind"] == "continuity_profile" and r["id"] == tenant[1] and r["life"] == "archived"
                           for r in rows["cloud_entities"]), "Archived continuity requires separate owner restoration before cutover")
            verify(not pending or carry_pending, "Resolve pending cloud requests or review carrying prior cloud requests into a new epoch")
            # Run the actual legacy fences under a savepoint even on preview.
            db.execute("SAVEPOINT authority_check")
            db.execute("UPDATE cloud_continuities SET authority='cloud',epoch=epoch+1" + SELECT_TENANT, tenant)
            db.execute("ROLLBACK TO authority_check")
            db.execute("RELEASE authority_check")
        proof = sha(encode([action, marker["current"], marker["import"], fingerprint, carry_pending, pending]))
        if approval is None:
            return {"status": "PREVIEW", "action": action, "approvalSha256": proof, "epoch": current["epoch"],
                    "preservedPending": pending if carry_pending else []}
        verify(proof == approval, "Approval does not match the reviewed administrative decision")
        if action == "cutover":
            db.execute("UPDATE cloud_continuities SET authority='cloud',epoch=epoch+1"+ SELECT_TENANT, tenant)
            resulting = state(db, tenant)
            # Same transaction as the synthetic authority transition; survives a crash/lost acknowledgement.
            db.execute("""CREATE TABLE IF NOT EXISTS rehearsal_decisions(
                id TEXT PRIMARY KEY, kind TEXT NOT NULL, epoch INTEGER NOT NULL, at INTEGER NOT NULL)""")
            db.execute("INSERT INTO rehearsal_decisions VALUES (?,?,?,?)", (proof, action, resulting["epoch"], now()))
            db.commit()
            return {"status": "ISOLATED_CLOUD", "decision": proof, "state": resulting}
        previous = marker["previous"]
        verify(re.fullmatch("[a-f0-9]{32}[.]sqlite", previous) and (folder / previous).is_file(),
               "Original staging generation unavailable")
        verify(not (folder / previous).is_symlink(), "Original staging generation is invalid")
        with closing(db_connect(folder / previous)) as original:
            verify(marks(original, tenant) == marker["previousHash"], "Original staging generation changed")
        receipt = {"operation": "rollback_staging", "decision": proof, "retainedGeneration": marker["current"],
                   "manifestSha256": marker["import"], "at": now()}
        store_marker(folder, {**marker, "current": previous, "previous": None, "import": None,
                              "accepted": None, "receipt": receipt})
        return {"status": "ROLLED_BACK", "receipt": receipt}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="operation", required=True)
    alias = sub.add_parser("mapping-identity", help="Derive a repeatable canonical alias from a native field or a store-local composite identity")
    for field in ("owner","continuity","family","kind","native-value"):
        alias.add_argument("--"+field, required=True)
    alias.add_argument("--identity", required=True, action="append")
    for op in ("mapping-template", "convert-reviewed"):
        p = sub.add_parser(op)
        p.add_argument("--source", type=Path, required=True, action="append", help="Exact native export, repeat for independent sources")
        p.add_argument("--owner", required=True)
        p.add_argument("--continuity", required=True)
        if op == "convert-reviewed":
            p.add_argument("mapping", type=Path)
            p.add_argument("output", type=Path)
    parity = sub.add_parser("parity", help="Compare recorded native mechanics to a reviewed cloud bundle")
    parity.add_argument("native", type=Path)
    parity.add_argument("package", type=Path)
    compare = sub.add_parser("compare-legacy", help="Reconcile two native snapshots by stable identity and exact row digest")
    compare.add_argument("earlier", type=Path)
    compare.add_argument("later", type=Path)
    compare.add_argument("--owner", required=True)
    compare.add_argument("--continuity", required=True)
    original = sub.add_parser("extract-source", help="Recover one digest-verified native original into a new private file")
    original.add_argument("package", type=Path)
    original.add_argument("output", type=Path)
    original.add_argument("--sha256", required=True)
    for op in ("detached-preview", "detached-save", "detached-verify", "detached-recover"):
        p = sub.add_parser(op, help="Review, preserve or verify a canonical package and separate full native weapon archive")
        if op in ("detached-preview", "detached-save"):
            p.add_argument("package", type=Path)
            p.add_argument("native", type=Path)
        if op != "detached-preview":
            p.add_argument("directory", type=Path)
            p.add_argument("--review-sha256", required=True)
        if op == "detached-recover":
            p.add_argument("output", type=Path)
    for op in ("seal", "unseal"):
        p = sub.add_parser(op)
        p.add_argument("file", type=Path)
        p.add_argument("output", type=Path)
        p.add_argument("--key-file", type=Path, required=True)
    for op in ("init", "status", "export", "validate", "inspect-legacy", "convert-legacy",
               "preview", "apply", "cutover", "rollback"):
        p = sub.add_parser(op)
        if op not in ("validate", "inspect-legacy", "convert-legacy"):
            p.add_argument("directory", type=Path)
        if op in ("export", "validate", "preview", "apply", "inspect-legacy", "convert-legacy"):
            p.add_argument("file", type=Path)
        if op == "convert-legacy":
            p.add_argument("output", type=Path)
        if op in ("init", "inspect-legacy", "convert-legacy"):
            p.add_argument("--owner", required=True)
            p.add_argument("--continuity", required=True)
        if op in ("inspect-legacy", "convert-legacy"):
            p.add_argument("--shared-wallet", action="append", default=[], help="Reviewed shared wallet identity")
            p.add_argument("--known-record", action="append", default=[], help="Exact player-known source-row membership review SHA-256")
        if op in ("apply", "cutover", "rollback"):
            p.add_argument("--approve", required=op == "apply")
        if op == "cutover":
            p.add_argument("--carry-pending-for-review", action="store_true",
                           help="Preserve exact pending cloud approvals for separate review under the recovered epoch")
    args = parser.parse_args()
    if args.operation == "mapping-identity":
        return {"canonicalId": scoped_identity((args.owner,args.continuity), args.family, args.identity, args.kind, args.native_value)}
    if args.operation == "mapping-template":
        return mapping_template([read_limited(p) for p in args.source], args.owner, args.continuity)
    if args.operation == "convert-reviewed":
        return convert_reviewed(args.source, args.mapping, args.output, (args.owner, args.continuity))
    if args.operation == "parity":
        snapshot = read_bundle(args.package)
        check_database(snapshot)
        return mechanical_parity(args.native, snapshot)
    if args.operation == "compare-legacy":
        return compare_legacy(args.earlier, args.later, args.owner, args.continuity)
    if args.operation == "extract-source":
        return extract_source(args.package, args.output, args.sha256)
    if args.operation.startswith("detached-"):
        import ascension_migrate_detached as detached
        if args.operation == "detached-preview":
            return detached.preview(args.package, args.native)
        if args.operation == "detached-save":
            return detached.save(args.package, args.native, args.directory, args.review_sha256)
        if args.operation == "detached-verify":
            return detached.check_set(args.directory, args.review_sha256)
        return detached.recover(args.directory, args.output, args.review_sha256)
    if args.operation in ("seal", "unseal"):
        return protect(args.file, args.output, args.key_file, args.operation == "unseal")
    if args.operation == "init": return init(args.directory, args.owner, args.continuity)
    if args.operation == "status": return inspect(args.directory)
    if args.operation == "validate":
        package = read_bundle(args.file)
        check_database(package)
        return summary(package)
    if args.operation == "inspect-legacy":
        with args.file.open("rb") as stream: signature = stream.read(4)
        if signature == b"PK\x03\x04": return weapon_review(args.file)
        return legacy_review(args.file, args.owner, args.continuity, args.shared_wallet, args.known_record)[0]
    if args.operation == "convert-legacy":
        return convert(args.file, args.output, (args.owner, args.continuity), args.shared_wallet, args.known_record)
    if args.operation == "export": return export(args.directory, args.file)
    if args.operation == "preview": return inspect(args.directory, args.file)
    if args.operation == "apply": return apply(args.directory, args.file, args.approve)
    return control(args.directory, args.operation, args.approve, getattr(args, "carry_pending_for_review", False))


if __name__ == "__main__":
    os.umask(0o077)
    try:
        print(json.dumps(main(), ensure_ascii=False, indent=2))
    except (OSError, ValueError, KeyError, TypeError, RecursionError, BadZipFile, sqlite3.Error) as error:
        print("Migration refused: " + str(error), file=sys.stderr)
        sys.exit(2)
