#!/usr/bin/env python3
"""Seed isolated emulator QA databases and compare WAL-aware table snapshots.

Never target a phone backup or real campaign. Seeding requires a marker written
by the emulator operator after verifying ro.boot.qemu=1. No APK, key or database
contents are emitted by this tool; snapshots contain row counts and hashes.
"""
import argparse
import hashlib
import json
from pathlib import Path
import sqlite3

MARKER = ".astravar-isolated-emulator-fixture"
SOURCE = "Isolated synthetic package-upgrade QA fixture"


def digest(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, separators=(",", ":"),
                                     ensure_ascii=False).encode()).hexdigest()


def snapshot(folder):
    result = {}
    for file in sorted(folder.glob("*.db")):
        with sqlite3.connect(file) as connection:
            if connection.execute("PRAGMA quick_check").fetchone() != ("ok",):
                raise ValueError(f"SQLite integrity failed for {file.name}")
            tables = {}
            names = connection.execute("SELECT name FROM sqlite_master WHERE type='table' ORDER BY name").fetchall()
            for (name,) in names:
                query = connection.execute('SELECT * FROM "' + name.replace('"', '""') + '"')
                columns = [column[0] for column in query.description]
                rows = [[{"blob_hex": value.hex()} if isinstance(value, bytes) else value
                         for value in row] for row in query.fetchall()]
                rows.sort(key=lambda row: json.dumps(row, sort_keys=True, ensure_ascii=False))
                tables[name] = {"columns": columns, "rows": len(rows), "sha256": digest([columns, rows])}
            result[file.name] = {"user_version": connection.execute("PRAGMA user_version").fetchone()[0],
                                 "tables": tables}
    if not result:
        raise ValueError("No SQLite databases found; empty readback cannot establish preservation")
    return result


def seed(folder):
    if not (folder / MARKER).is_file():
        raise ValueError("Missing isolated-emulator marker; refusing fixture insertion")
    # Canonicalize a stopped emulator's complete DB/WAL/SHM copy before returning
    # it to that same isolated emulator. This reads and checkpoints the copied
    # WAL rather than dropping its pending records.
    for file in folder.glob("*.db"):
        with sqlite3.connect(file) as connection:
            connection.execute("PRAGMA wal_checkpoint(TRUNCATE)")
            connection.execute("PRAGMA journal_mode=DELETE")
    file = folder / "astravar-campaign-ledger.db"
    if not file.is_file():
        raise ValueError("The code-41 application must create its real database first")
    with sqlite3.connect(file) as connection:
        if connection.execute("PRAGMA user_version").fetchone() != (6,):
            raise ValueError("Expected the original code-41 Room schema 6")

        def insert(table, **values):
            columns = {row[1]: row for row in connection.execute(f'PRAGMA table_info("{table}")')}
            for key in ("revision", "source", "updatedAt"):
                if key in columns and key not in values:
                    values[key] = {"revision": 7, "source": SOURCE, "updatedAt": 1_800_000_000_000}[key]
            missing = [name for name, row in columns.items() if row[3] and row[4] is None and name not in values]
            if missing:
                raise ValueError(f"Fixture must explicitly supply required {table} fields: {missing}")
            connection.execute('INSERT INTO "' + table + '" (' + ','.join('"' + name + '"' for name in values) +
                               ') VALUES (' + ','.join('?' for _ in values) + ')', tuple(values.values()))

        profile = "local-player"
        insert("character_profiles", id=profile, name="QA upgrade artificer", level=9,
               currentHp=None, maximumHp=66, temporaryHp=0, armorClass=None)
        for key, score in (("STR", 0), ("CON", None), ("INT", 21)):
            insert("character_abilities", profileId=profile, key=key, score=score, magicModifier=0,
                   notes="Zero and unknown are distinct; do not infer current HP or a rest.")
        item = dict(id="qa-upgrade-item", title="QA preserved component", category="Equipment",
                    owner="QA upgrade artificer", itemState="Carried", location="QA satchel",
                    evidenceStatus="Confirmed", quantity=None,
                    description='<mention url="https://example.invalid/qa">QA source</mention>\n<mention malformed>',
                    knownProperties="Narrative only; retired prototype, no active bonus.", possibleUses="",
                    limitations="QA fixture only", source=SOURCE, notionPageId="qa-original-reference",
                    imageBase64="iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+j3S8AAAAASUVORK5CYII=",
                    createdAt=1_700_000_000_000, updatedAt=1_800_000_000_000, revision=7, archived=0)
        insert("ledger_items", **item)
        insert("item_details", itemId=item["id"], weightGrams=None, notes="Preserved original thumbnail and identity.")
        insert("ledger_events", commandId="qa-upgrade-history", itemId=item["id"], kind="QA_FIXTURE",
               evidenceStatus="Confirmed", detail="Historical retired mechanism retained; no roll or rest.",
               source=SOURCE, payloadFingerprint=digest(item), priorRevision=6, resultingRevision=7,
               createdAt=1_800_000_000_000, beforeJson=None, afterJson=json.dumps(item))
        insert("connection_entities", id="qa-upgrade-npc", name="QA preserved patron", kind="Person",
               notes=item["description"], aliases="QA patron", relationship="Historical")
        insert("companion_states", companionId="qa-upgrade-companion", name="QA mechanical owl",
               condition="Unknown", notes="Synthetic companion; no real homebrew is overwritten.")
        insert("companion_resources", id="qa-upgrade-charges", companionId="qa-upgrade-companion",
               name="QA charge pool", current=0, maximum=3)
        insert("party_wallets", id="qa-upgrade-wallet", holderProfileId=profile, name="QA purse",
               copper=0, silver=None, gold=7, electrum=None, platinum=0, campaignId="qa-upgrade-campaign")
        for namespace in ("qa-original-a", "qa-original-b"):
            insert("external_record_references", id=namespace, namespace=namespace,
                   externalId="shared-external-id", recordType="item", recordId=item["id"],
                   url="https://example.invalid/qa", campaignId="qa-upgrade-campaign")
        insert("campaign_audit", id="qa-upgrade-audit", commandId="qa-upgrade-history",
               recordType="item", recordId=item["id"], kind="QA_FIXTURE", source=SOURCE,
               priorRevision=6, resultingRevision=7, createdAt=1_800_000_000_000,
               beforeJson=None, afterJson=json.dumps(item))
    return snapshot(folder)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("folder", type=Path)
    parser.add_argument("--seed", action="store_true")
    parser.add_argument("--compare", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    current = seed(args.folder) if args.seed else snapshot(args.folder)
    if args.compare:
        before = json.loads(args.compare.read_text())
        for name, database in before.items():
            if name not in current:
                raise ValueError(f"Original database disappeared: {name}")
            if database["user_version"] != current[name]["user_version"]:
                raise ValueError(f"Unexpected historical database version change: {name}")
            for table, state in database["tables"].items():
                if state != current[name]["tables"].get(table):
                    raise ValueError(f"Original table content changed: {name}/{table}")
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(current, indent=2) + "\n")
    print(json.dumps({"databases": len(current), "tables": sum(len(db["tables"]) for db in current.values()),
                      "rows": sum(t["rows"] for db in current.values() for t in db["tables"].values()),
                      "prior_tables_equal": bool(args.compare), "snapshot_sha256": digest(current)}))


if __name__ == "__main__":
    main()
