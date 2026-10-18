#!/usr/bin/env python3
"""Offline, synthetic-emulator-only history upgrade fixture. Never opens an Android device.

Pull astravar.db and matching WAL/SHM after force-stop. `seed` checkpoints the copy
and leaves DELETE journal mode so the runner can restore one complete DB file.
The runner must independently enforce emulator/qemu identity and preserve file
ownership/SELinux labels when replacing its disposable app's database.
"""
from __future__ import annotations

import argparse
import copy
import hashlib
import json
import os
import re
from pathlib import Path
import sqlite3
import sys

MARKER = "ASTRAVAR_CI_SYNTHETIC_HISTORY_V1"
PREFIX = "synthetic-history-"
ARCHIVE_PREFIX = "astravar-preserved-v1:"
TABLES = ("campaign", "events", "rolls", "archives")
SEED_COUNT = 30
WINDOW = 20
KNOWN_TRIGGERS = {"bound_rolls": "rolls", "bound_events": "events",
                  "preserve_archive_no_delete": "archives", "preserve_archive_no_update": "archives",
                  "preserve_archive_no_collision": "archives", "preserve_archive_no_update_collision": "archives"}
KNOWN_TRIGGERS.update({f"preserve_{table}_{action}": table for table in TABLES
                      for action in ("delete", "update_old", "update_new", "insert", "replace")})


def digest(body: str) -> str:
    return hashlib.sha256(body.encode("utf-8")).hexdigest()


def archive_id(table: str, identity: str, body: str) -> str:
    return f"{ARCHIVE_PREFIX}{table}:{identity.encode('utf-8').hex()}:{digest(body)}"


def raw_records(db: sqlite3.Connection) -> list[dict]:
    rows = []
    for table in TABLES:
        for identity, body in db.execute(f"SELECT id,body FROM {table} ORDER BY id"):
            if not isinstance(body, str):
                raise ValueError("Fixture requires TEXT bodies, never implicit binary decoding")
            # Verify existing immutable archive rows directly, without requiring recursive wrapping.
            canonical = str(identity) if table == "archives" and str(identity).startswith(ARCHIVE_PREFIX) else archive_id(table, str(identity), body)
            rows.append({"table": table, "id": str(identity), "sha256": digest(body), "archive_id": canonical})
    return rows


def open_fixture(path: Path, synthetic: bool) -> sqlite3.Connection:
    if not synthetic:
        raise ValueError("Explicit --synthetic-demo acknowledgement is required")
    if path.name != "astravar.db" or path.is_symlink() or not path.is_file():
        raise ValueError("Use an existing regular copied database named astravar.db")
    if any(parent.is_symlink() for parent in path.absolute().parents):
        raise ValueError("Copied fixture paths must not traverse symlinks")
    db = sqlite3.connect(path, timeout=10)
    try:
        if db.execute("PRAGMA user_version").fetchone()[0] != 3:
            raise ValueError("Expected existing combat Room schema 3")
        required = {"campaign": {"id", "body"}, "events": {"id", "revision", "body", "origin", "label", "gameSeconds", "undone"},
                    "rolls": {"id", "sequence", "body"}, "archives": {"id", "body"}}
        for table, names in required.items():
            if not names.issubset({row[1] for row in db.execute(f"PRAGMA table_info({table})")}):
                raise ValueError(f"Unexpected fixture table: {table}")
        row = db.execute("SELECT body FROM campaign WHERE id=1").fetchone()
        if row is None or json.loads(row[0]).get("demo") is not True:
            raise ValueError("Refusing a non-demo campaign; no real campaign may be seeded")
        return db
    except Exception:
        db.close()
        raise


def checkpoint(db: sqlite3.Connection) -> None:
    busy, _, _ = db.execute("PRAGMA wal_checkpoint(TRUNCATE)").fetchone()
    if busy:
        raise ValueError("Copied WAL is busy; stop every writer before creating a fixture")


def shot_fixture(template: dict, identity: str, campaign: dict) -> dict:
    shot = copy.deepcopy(template)
    plan = shot["plan"]
    plan.update(id=identity, ability="SHATTER", blastDiceCount=12, distance=30,
                selectedPayloads=[], immediate=None, elements=[], directImpact=False,
                chargedGauntlet=None, revision=min(plan.get("revision", 0), campaign["revision"]))
    if plan["coreId"] not in {core["id"] for core in campaign.get("cores", [])}:
        raise ValueError("The demo's retained roll must reference a preserved core")
    # Historical, unrolled SHATTER snapshots exercise decoding without inventing dice or costs.
    shot.update(result=None, rolled=None)
    return shot


def event_fixture(template: dict, identity: str, shot: dict, campaign: dict) -> dict:
    event = copy.deepcopy(template)
    event.update(id=identity, label=f"CI FICTIONAL historical Shatter {identity}",
                 origin="synthetic-history", undone=False, compact=False, rollUndo=[])
    event["before"] = copy.deepcopy(campaign)
    event["before"]["pending"] = [copy.deepcopy(shot)]
    event["after"] = copy.deepcopy(campaign)
    event["after"]["pending"] = []
    return event


def encode(value: object) -> str:
    # Deliberate whitespace and non-ASCII text help detect byte-rewriting rather than semantic preservation.
    return json.dumps(value, ensure_ascii=False, indent=1)


def seed(path: Path, manifest_path: Path, synthetic: bool) -> dict:
    if manifest_path.exists() or manifest_path.is_symlink():
        raise ValueError("Refusing to replace an existing expected-hash manifest")
    db = open_fixture(path, synthetic)
    wrote_manifest = False
    try:
        checkpoint(db)
        if db.execute("PRAGMA journal_mode=DELETE").fetchone()[0].lower() != "delete":
            raise ValueError("Could not checkpoint the copied database into a single file")
        campaign_raw = db.execute("SELECT body FROM campaign WHERE id=1").fetchone()[0]
        campaign = json.loads(campaign_raw)
        original_rolls = list(db.execute("SELECT id,sequence,body FROM rolls ORDER BY sequence DESC,id DESC"))
        original_events = list(db.execute("SELECT id,revision,body FROM events ORDER BY revision DESC,id DESC"))
        if len(original_rolls) < 2 or not original_events:
            raise ValueError("First create the real synthetic native two-roll demo fixture")
        if db.execute("SELECT id FROM archives WHERE id LIKE ?", (PREFIX + "%",)).fetchone() or any(str(row[0]).startswith(PREFIX) for row in original_rolls + original_events):
            raise ValueError("This copied database was already seeded")
        shot_template = json.loads(original_rolls[0][2])
        event_template = json.loads(original_events[0][2])
        if not isinstance(shot_template.get("plan"), dict) or not isinstance(event_template.get("before"), dict) or not isinstance(event_template.get("after"), dict):
            raise ValueError("Native fixture templates are not serialized combat records")
        original_count = len(raw_records(db))
        with db:
            # Simulate an unprotected legacy store. New preservation triggers must not
            # manufacture success before the upgraded application actually starts.
            dropped = []
            for name, table, sql in db.execute("SELECT name,tbl_name,sql FROM sqlite_master WHERE type='trigger'").fetchall():
                if name in KNOWN_TRIGGERS:
                    if KNOWN_TRIGGERS[name] != table:
                        raise ValueError("Known history trigger has an unexpected table identity")
                    db.execute(f'DROP TRIGGER "{name}"')
                    dropped.append({"name": name, "sql_sha256": digest(sql or "")})
                elif name.startswith("preserve_"):
                    raise ValueError("Unknown preservation trigger requires explicit fixture review")
            sequence = min(row[1] for row in original_rolls) - SEED_COUNT - 100
            revision = min(row[1] for row in original_events) - SEED_COUNT - 100
            first_event = None
            for index in range(SEED_COUNT):
                shot = shot_fixture(shot_template, f"{PREFIX}roll-{index:03}", campaign)
                event = event_fixture(event_template, f"{PREFIX}event-{index:03}", shot, campaign)
                first_event = first_event or event
                db.execute("INSERT INTO rolls(id,sequence,body) VALUES(?,?,?)", (shot["plan"]["id"], sequence + index, encode(shot)))
                db.execute("INSERT INTO events(id,revision,body,origin,label,gameSeconds,undone) VALUES(?,?,?,?,?,?,0)",
                           (event["id"], revision + index, encode(event), "synthetic-history", event["label"], campaign.get("gameSeconds", 0)))
            malformed = '{ "CI FICTIONAL malformed historical event": [ SHATTER, unfinished — Ω'
            db.execute("INSERT INTO events(id,revision,body,origin,label,gameSeconds,undone) VALUES(?,?,?,?,?,0,0)",
                       (PREFIX + "malformed-event", revision - 1, malformed, "synthetic-history", "CI FICTIONAL malformed record"))
            legacy = {"id": PREFIX + "legacy-archive", "history": [first_event]}
            db.execute("INSERT INTO archives(id,body) VALUES(?,?)", (legacy["id"], encode(legacy)))
            manifest = {"fixture_marker": MARKER, "schema": 1, "synthetic_only": True,
                        "seeded_rows": SEED_COUNT * 2 + 2, "original_raw_rows": original_count,
                        "dropped_history_triggers": dropped,
                        "campaign": campaign, "campaign_raw_sha256": digest(campaign_raw),
                        "latest_original_roll_ids": [row[0] for row in original_rolls[:2]],
                        "recent_original_event_ids": [row[0] for row in original_events[:WINDOW]],
                        "expected_recent_count": WINDOW, "records": raw_records(db)}
            manifest_path.parent.mkdir(parents=True, exist_ok=True)
            with manifest_path.open("x", encoding="utf-8") as target:
                wrote_manifest = True
                json.dump(manifest, target, ensure_ascii=False, indent=2)
                target.write("\n"); target.flush(); os.fsync(target.fileno())
        checkpoint(db)
        return {"fixture_marker": MARKER, "seeded_rows": manifest["seeded_rows"], "expected_raw_rows": len(manifest["records"])}
    except Exception:
        db.rollback()
        if wrote_manifest:
            manifest_path.unlink(missing_ok=True)
        raise
    finally:
        db.close()


def verify(path: Path, manifest_path: Path, synthetic: bool) -> dict:
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    if manifest.get("fixture_marker") != MARKER or manifest.get("schema") != 1 or manifest.get("synthetic_only") is not True:
        raise ValueError("Unrecognized synthetic expected-hash manifest")
    db = open_fixture(path, synthetic)
    try:
        checkpoint(db)
        archives = dict(db.execute("SELECT id,body FROM archives"))
        preserved = retained = 0
        for row in manifest["records"]:
            if row["table"] not in TABLES or not re.fullmatch(r"[0-9a-f]{64}", row["sha256"]):
                raise ValueError("Unexpected manifest source table or hash")
            canonical = (row["id"] if row["table"] == "archives" and row["id"].startswith(ARCHIVE_PREFIX)
                         else f"{ARCHIVE_PREFIX}{row['table']}:{row['id'].encode('utf-8').hex()}:{row['sha256']}")
            if row["archive_id"] != canonical:
                raise ValueError("Manifest archive identity does not match its original raw hash")
            current = db.execute(f"SELECT body FROM {row['table']} WHERE id=?", (row["id"],)).fetchone()
            if current is not None and digest(current[0]) == row["sha256"]:
                retained += 1
            elif row["archive_id"] in archives and digest(archives[row["archive_id"]]) == row["sha256"]:
                preserved += 1
            else:
                raise ValueError(f"Raw history missing or rewritten: {row['table']}/{row['id']}")
        current_campaign = json.loads(db.execute("SELECT body FROM campaign WHERE id=1").fetchone()[0])
        if current_campaign != manifest["campaign"]:
            raise ValueError("Current demo campaign values changed during startup")
        working_rolls = [row[0] for row in db.execute("SELECT id FROM rolls ORDER BY sequence DESC,id DESC LIMIT 20")]
        working_events = [row[0] for row in db.execute("SELECT id FROM events ORDER BY revision DESC,id DESC LIMIT 20")]
        for table, rows in (("rolls", working_rolls), ("events", working_events)):
            if len(rows) != WINDOW or db.execute(f"SELECT COUNT(*) FROM {table}").fetchone()[0] != WINDOW:
                raise ValueError(f"{table} must retain exactly the bounded 20-record working window")
        if working_rolls[:2] != manifest["latest_original_roll_ids"] or not set(manifest["recent_original_event_ids"]).issubset(working_events):
            raise ValueError("The pre-upgrade newest rolls or events were displaced")
        return {"fixture_marker": MARKER, "verified_raw_rows": len(manifest["records"]),
                "unchanged_working_rows": retained, "immutable_archive_rows": preserved,
                "recent_rolls": len(working_rolls), "recent_events": len(working_events), "current_campaign_unchanged": True}
    finally:
        db.close()


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("operation", choices=("seed", "verify"))
    parser.add_argument("--database", type=Path, required=True)
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--synthetic-demo", action="store_true", help="Acknowledge this is a copied disposable emulator demo")
    args = parser.parse_args(argv)
    try:
        result = (seed if args.operation == "seed" else verify)(args.database, args.manifest, args.synthetic_demo)
        print(json.dumps(result, sort_keys=True))
        return 0
    except (ValueError, OSError, sqlite3.Error, KeyError, TypeError) as exc:
        print(f"Synthetic history fixture rejected: {exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
