#!/usr/bin/env python3
"""Strictly verify copied synthetic stores across additive storage cutover and one UI write."""
# Schema 9 support reconstructed from the recovery handoff; verification pending.
import argparse
import hashlib
import json
from pathlib import Path
import sqlite3
import struct

ROOT = Path(__file__).resolve().parents[1]
ACTOR = "Genesis Legacy Retention Actor"
ALLY = "Genesis Legacy Retention Ally"
ITEM = "Genesis Legacy Retention Compass"
SUPPLIES = "Genesis Legacy Retention Supplies"
RESOURCE = "Genesis Legacy Retention Charges"
WEAPON = "astravar.db"
CAMPAIGN = "astravar-campaign-ledger.db"
LEGACY = "astravar-reforge.db"
DATABASES = (WEAPON, CAMPAIGN, LEGACY)
AUTHORITY = "campaign-ledger-reforge-v1"
SAVE_STATE = "Saved on this device · not synchronized"
RAW_TABLES = {
    "reforge_characters": "profileId,characterJson,revision,originJson,createdAt,updatedAt".split(","),
    "reforge_changes": "commandId,profileId,operation,fingerprint,priorRevision,resultingRevision,createdAt,beforeJson,afterJson,outcomeType".split(","),
    "reforge_rolls": "id,profileId,characterRevision,label,formula,components,manual,ownership,createdAt".split(","),
    "reforge_note_drafts": "profileId,expectedRevision,text,updatedAt".split(","),
}
RECEIPT = "genesis_storage_receipt"
AUTHORITY_TABLES = {"genesis_campaigns", "genesis_actor_authority", "genesis_item_authority", "genesis_authority_receipts"}
ENCOUNTER_TABLES = {"genesis_encounters", "genesis_encounter_participants"}


def require(condition, message):
    if not condition:
        raise ValueError(message)


def canonical_hash(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, separators=(",", ":"),
                                     ensure_ascii=False).encode("utf-8")).hexdigest()


def field(value):
    """Match Kotlin rawDigest's typed, length-prefixed bytes exactly."""
    if value is None:
        kind, data = 0, b""
    elif isinstance(value, int):
        kind, data = 1, str(value).encode("utf-8")
    elif isinstance(value, float):
        kind, data = 2, str(struct.unpack(">q", struct.pack(">d", value))[0]).encode("utf-8")
    elif isinstance(value, bytes):
        kind, data = 4, value
    else:
        require(isinstance(value, str), "Unsupported SQLite value")
        kind, data = 3, value.encode("utf-8", errors="strict")
    return bytes([kind]) + struct.pack(">q", len(data)) + data


def connect(path):
    connection = sqlite3.connect(path.resolve().as_uri() + "?mode=ro", uri=True)
    connection.row_factory = sqlite3.Row
    connection.execute("PRAGMA query_only=ON")
    return connection


def raw_digest(connection):
    digest, counts = hashlib.sha256(), {}
    for table, columns in RAW_TABLES.items():
        actual = [row[1] for row in connection.execute(f'PRAGMA table_info("{table}")')]
        require(actual == columns, f"Unexpected playable column layout: {table}")
        digest.update(field(table))
        count = 0
        for row in connection.execute(f'SELECT * FROM "{table}" ORDER BY "{columns[0]}"'):
            for value in row:
                digest.update(field(value))
            count += 1
        digest.update(field(count))
        counts[table] = count
    return digest.hexdigest(), counts


def schema(name, version):
    path = ROOT / "app/schemas" / f"com.yazsras.astravar.data.{name}" / f"{version}.json"
    return json.loads(path.read_text())["database"]


def table_snapshot(connection, table):
    quoted = '"' + table.replace('"', '""') + '"'
    shape = [list(row) for row in connection.execute(f"PRAGMA table_info({quoted})")]
    rows = list(connection.execute(f"SELECT * FROM {quoted}"))
    encoded = sorted(b"".join(field(value) for value in row) for row in rows)
    digest = hashlib.sha256()
    digest.update(field(json.dumps(shape, separators=(",", ":"))))
    for row in encoded:
        digest.update(field(row))
    result = {"columns": [row[1] for row in shape], "shape": shape,
              "rows": len(rows), "sha256": digest.hexdigest()}
    if table in RAW_TABLES:
        result["row_hashes"] = {str(row[0]): hashlib.sha256(b"".join(field(value) for value in row)).hexdigest()
                                for row in rows}
        require(len(result["row_hashes"]) == len(rows), f"Duplicate playable identity in {table}")
    return result


def database_snapshot(path, schema_name, version):
    expected = schema(schema_name, version)
    with connect(path) as connection:
        require(tuple(connection.execute("PRAGMA quick_check").fetchone()) == ("ok",), f"SQLite integrity failed: {path.name}")
        require(connection.execute("PRAGMA user_version").fetchone()[0] == version, f"Wrong database version: {path.name}")
        names = {row[0] for row in connection.execute("SELECT name FROM sqlite_master WHERE type='table'")}
        entities = {entity["tableName"]: entity for entity in expected["entities"]}
        require(names - {"room_master_table", "android_metadata"} - {name for name in names if name.startswith("sqlite_")} == set(entities),
                f"Unexpected or missing database tables: {path.name}")
        room = [tuple(row) for row in connection.execute("SELECT id,identity_hash FROM room_master_table ORDER BY id")]
        require(room == [(42, expected["identityHash"])], f"Wrong Room identity: {path.name}")
        for name, entity in entities.items():
            columns = list(connection.execute(f'PRAGMA table_info("{name}")'))
            fields = entity["fields"]
            require([row[1] for row in columns] == [item["columnName"] for item in fields], f"Column identities changed: {name}")
            require(all(row[2].upper() == item["affinity"] and bool(row[3]) == item.get("notNull", False)
                        for row, item in zip(columns, fields)), f"Column type/nullability changed: {name}")
            keys = [row[1] for row in sorted(columns, key=lambda row: row[5]) if row[5]]
            require(keys == entity["primaryKey"]["columnNames"], f"Primary key changed: {name}")
        return {"user_version": version, "identity_hash": expected["identityHash"],
                "tables": {name: table_snapshot(connection, name) for name in sorted(names)}}


def source_file_hashes(folder):
    # SHM is a disposable locking/index file. Preserve actual original DB,
    # committed WAL and rollback journal bytes, never just a decoded projection.
    return {LEGACY + suffix: hashlib.sha256((folder / (LEGACY + suffix)).read_bytes()).hexdigest()
            for suffix in ("", "-wal", "-journal") if (folder / (LEGACY + suffix)).is_file()}


def actor_proof(row, character):
    unchanged = {key: value for key, value in character.items() if key not in {"currentHp", "hpDamage", "revision"}}
    saved = {"character": character, "revision": row["revision"], "origin": json.loads(row["originJson"]),
             "createdAt": row["createdAt"], "updatedAt": row["updatedAt"], "saveState": SAVE_STATE}
    return {"profileId": row["profileId"], "revision": row["revision"], "currentHp": character["currentHp"],
            "hpDamage": character["hpDamage"], "updatedAt": row["updatedAt"],
            "unchanged_character_sha256": canonical_hash(unchanged),
            "immutable_row_sha256": hashlib.sha256(b"".join(field(row[key]) for key in ("profileId", "originJson", "createdAt"))).hexdigest(),
            "saved_sha256": canonical_hash(saved)}


def inspect(folder, phase="baseline", expected_room_schema=8):
    folder = Path(folder)
    require(phase in {"baseline", "preserved", "written"}, "Unknown verification phase")
    require(expected_room_schema in (8, 9), "Supported upgrade targets are schemas 8 and 9")
    require(all((folder / name).is_file() for name in DATABASES), "A required original store is missing")
    version = 6 if phase == "baseline" else expected_room_schema
    files = source_file_hashes(folder)
    tables = {WEAPON: database_snapshot(folder / WEAPON, "AstravarDb", 3),
              LEGACY: database_snapshot(folder / LEGACY, "ReforgeDb", 1),
              CAMPAIGN: database_snapshot(folder / CAMPAIGN, "CampaignLedgerDb", version)}
    with connect(folder / LEGACY) as source:
        source_digest, counts = raw_digest(source)
    receipt = None
    with connect(folder / CAMPAIGN) as campaign:
        if version == 9:
            require(all(campaign.execute(f'SELECT count(*) FROM "{table}"').fetchone()[0] == 0
                        for table in ENCOUNTER_TABLES), "Upgrade must not invent encounter state")
        if version >= 7:
            require(all(campaign.execute(f'SELECT count(*) FROM "{table}"').fetchone()[0] == 0
                        for table in AUTHORITY_TABLES), "Upgrade must not automatically enroll existing campaign records")
            receipts = [dict(row) for row in campaign.execute(f"SELECT * FROM {RECEIPT}")]
            require(len(receipts) == 1, "Storage authority must have exactly one receipt")
            receipt = receipts[0]
            require(receipt["id"] == 1 and receipt["sourceVersion"] == 1 and receipt["authority"] == AUTHORITY and
                    receipt["sourceDigest"] == source_digest and json.loads(receipt["countsJson"]) == counts and
                    isinstance(receipt["completedAt"], int) and receipt["completedAt"] > 0,
                    "Storage receipt does not prove this retained original")
            if phase == "preserved":
                require(raw_digest(campaign) == (source_digest, counts), "Canonical rows differ from the original at cutover")
        active_file = CAMPAIGN if version >= 7 else LEGACY
        with connect(folder / active_file) as active:
            matches = [(dict(row), json.loads(row["characterJson"])) for row in active.execute("SELECT * FROM reforge_characters")
                       if json.loads(row["characterJson"])["name"] == ACTOR]
            require(len(matches) == 1, "Expected exactly one UI-created synthetic legacy actor in active storage")
            row, character = matches[0]
            require(row["profileId"] == character["id"] and row["revision"] == character["revision"], "Actor identity/revision disagreement")
            hp, damage = (18, 14) if phase == "written" else (17, 15)
            require(character["currentHp"] == hp and character["hpDamage"] == damage and character["baseMaxHp"] == 32 and
                    character["temporaryHp"] == 3 and character["baseAc"] == 16, "Unexpected actor health or defenses")
            def item(name, quantity, state):
                definitions = [value for value in character["itemDefinitions"] if value["name"] == name]
                require(len(definitions) == 1, f"Missing/duplicate definition: {name}")
                items = [value for value in character["items"] if value["definitionId"] == definitions[0]["id"]]
                require(len(items) == 1 and items[0]["quantity"] == quantity and items[0]["state"] == state,
                        f"Item identity, quantity or equipment state changed: {name}")
                return definitions[0], items[0]
            definition, equipment = item(ITEM, 1, "WORN")
            supply_definition, supplies = item(SUPPLIES, 3, "CARRIED")
            require(supplies["id"] != equipment["id"], "Distinct fixture items share one identity")
            resources = [pool for pool in character["resources"] if pool["name"] == RESOURCE]
            require(len(resources) == 1 and resources[0]["current"] == 2 and resources[0]["maximum"] == 5, "Resource changed")
            actor = actor_proof(row, character)
            history = {}
            if phase == "written":
                for event in active.execute("SELECT * FROM reforge_changes WHERE profileId=? AND operation='Apply healing' "
                                            "AND priorRevision=? AND resultingRevision=?",
                                            (character["id"], row["revision"] - 1, row["revision"])):
                    event = dict(event)
                    history[event["commandId"]] = {key: event[key] for key in
                        ("profileId", "operation", "fingerprint", "priorRevision", "resultingRevision", "createdAt", "outcomeType")}
                    history[event["commandId"]].update(before_saved_sha256=canonical_hash(json.loads(event["beforeJson"])),
                                                      after_saved_sha256=canonical_hash(json.loads(event["afterJson"])))
        companions = [dict(value) for value in campaign.execute("SELECT * FROM character_profiles WHERE name=?", (ALLY,))]
        require(len(companions) == 1, "Missing/duplicate companion")
        companion = companions[0]
        require(companion["currentHp"] == 7 and companion["maximumHp"] == 18, "Companion health changed")
        require(campaign.execute("SELECT count(*) FROM companion_states WHERE companionId=?", (companion["id"],)).fetchone()[0] == 1,
                "Companion association missing")
        require(campaign.execute("SELECT id FROM character_profiles WHERE id=? AND name=?", (character["id"], ACTOR)).fetchone() is not None,
                "Source identity no longer matches playable actor")
        links = [dict(value) for value in campaign.execute("SELECT * FROM campaign_links WHERE label=?", ("Fictional retained ally",))]
        require(len(links) == 1 and {links[0]["fromId"], links[0]["toId"]} == {character["id"], companion["id"]}, "Relationship changed")
    return {"phase": phase, "active_store": active_file, "databases": tables, "source_files": files,
            "source_digest": source_digest, "source_counts": counts, "receipt": receipt, "actor": actor, "write_history": history,
            "fixture": {"actor_id": character["id"], "actor_revision": character["revision"],
                "item_id": equipment["id"], "definition_id": definition["id"],
                "supplies_item_id": supplies["id"], "supplies_definition_id": supply_definition["id"],
                "resource_id": resources[0]["id"], "companion_id": companion["id"],
                "companion_revision": companion["revision"], "relationship_id": links[0]["id"], "source_identity_matches": True}}


def verify(before, current):
    require(before["phase"] == "baseline" and current["phase"] == "preserved", "Preservation requires explicit before/after phases")
    require(before["active_store"] == LEGACY and current["active_store"] == CAMPAIGN, "Active storage was not cut over")
    for name in (WEAPON, LEGACY):
        require(before["databases"][name] == current["databases"][name], f"Original store changed: {name}")
    require(before["source_files"] == current["source_files"], "Original playable DB/WAL/journal bytes changed")
    old, new = before["databases"][CAMPAIGN], current["databases"][CAMPAIGN]
    require(old["user_version"] == 6 and new["user_version"] in (8, 9), "Expected schema6 to explicitly inspected schema8 or schema9")
    additions = set(RAW_TABLES) | {RECEIPT} | AUTHORITY_TABLES
    if new["user_version"] == 9:
        additions |= ENCOUNTER_TABLES
    require(set(new["tables"]) == set(old["tables"]) | additions, "Cutover did not add exactly the playable storage and empty authority tables")
    for name, state in old["tables"].items():
        if name != "room_master_table":
            require(state == new["tables"][name], f"Original campaign table changed: {name}")
    for name in RAW_TABLES:
        require(before["databases"][LEGACY]["tables"][name] == new["tables"][name], f"Raw playable table copy changed: {name}")
    require(before["fixture"] == current["fixture"] and before["actor"] == current["actor"], "Fixture identity/state changed")
    require(before["source_digest"] == current["source_digest"] and before["source_counts"] == current["source_counts"], "Source proof changed")


def verify_write(preserved, current):
    require(preserved["phase"] == "preserved" and current["phase"] == "written", "UI write proof requires separate preserved/written phases")
    require(current["active_store"] == CAMPAIGN, "The new UI did not read canonical storage")
    for name in (WEAPON, LEGACY):
        require(preserved["databases"][name] == current["databases"][name], f"UI write changed original store: {name}")
    require(preserved["source_files"] == current["source_files"], "UI write changed retained original bytes")
    require(preserved["receipt"] == current["receipt"], "A later write replaced the cutover authority")
    before, after = preserved["databases"][CAMPAIGN], current["databases"][CAMPAIGN]
    require(before["user_version"] == after["user_version"] and before["user_version"] in (8, 9) and before["identity_hash"] == after["identity_hash"], "Canonical schema changed during UI write")
    require(set(before["tables"]) == set(after["tables"]), "Tables changed during UI write")
    for name in before["tables"]:
        if name not in {"reforge_characters", "reforge_changes"}:
            require(before["tables"][name] == after["tables"][name], f"UI heal changed unrelated table: {name}")
        else:
            require(before["tables"][name]["shape"] == after["tables"][name]["shape"],
                    f"UI heal changed a playable table definition: {name}")
    actor_before, actor_after = preserved["actor"], current["actor"]
    for key in ("profileId", "unchanged_character_sha256", "immutable_row_sha256"):
        require(actor_before[key] == actor_after[key], f"UI heal changed unrelated actor values: {key}")
    require(actor_after["revision"] == actor_before["revision"] + 1 and actor_after["updatedAt"] > actor_before["updatedAt"] and
            (actor_before["currentHp"], actor_before["hpDamage"], actor_after["currentHp"], actor_after["hpDamage"]) == (17, 15, 18, 14),
            "Canonical UI heal was not exactly one persisted revision")
    expected_fixture = dict(preserved["fixture"], actor_revision=actor_after["revision"])
    require(current["fixture"] == expected_fixture, "UI heal changed fixture relationships")
    old_actors, new_actors = (value["tables"]["reforge_characters"]["row_hashes"] for value in (before, after))
    require(set(old_actors) == set(new_actors), "UI heal added/removed an actor")
    for identity in old_actors:
        require((old_actors[identity] != new_actors[identity]) == (identity == actor_before["profileId"]), "UI heal changed another actor or no actor")
    old_events, new_events = (value["tables"]["reforge_changes"]["row_hashes"] for value in (before, after))
    require(all(new_events.get(key) == value for key, value in old_events.items()), "UI heal rewrote historical events")
    added = set(new_events) - set(old_events)
    require(len(added) == 1, "UI heal must append exactly one command receipt")
    event = current["write_history"].get(next(iter(added)))
    require(event is not None and event["profileId"] == actor_before["profileId"] and event["operation"] == "Apply healing" and
            event["priorRevision"] == actor_before["revision"] and event["resultingRevision"] == actor_after["revision"] and
            event["createdAt"] == actor_after["updatedAt"] and event["outcomeType"] == "CHARACTER" and
            len(event["fingerprint"]) == 64 and all(char in "0123456789abcdef" for char in event["fingerprint"]) and
            event["before_saved_sha256"] == actor_before["saved_sha256"] and event["after_saved_sha256"] == actor_after["saved_sha256"],
            "The appended command does not match the observed canonical UI heal")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("folder", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--compare", type=Path)
    parser.add_argument("--verify-write", type=Path)
    parser.add_argument("--synthetic-emulator", action="store_true", required=True)
    parser.add_argument("--expected-room-schema", type=int, choices=(8, 9), default=8)
    args = parser.parse_args()
    require(not (args.compare and args.verify_write), "Preservation and later UI writes are separate verification stages")
    phase = "written" if args.verify_write else "preserved" if args.compare else "baseline"
    current = inspect(args.folder, phase, expected_room_schema=args.expected_room_schema)
    if args.compare:
        verify(json.loads(args.compare.read_text()), current)
    if args.verify_write:
        verify_write(json.loads(args.verify_write.read_text()), current)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(current, indent=2) + "\n")
    print(json.dumps({"synthetic_ui_fixture_verified": True, "phase": phase,
                      "additive_storage_cutover_verified": bool(args.compare),
                      "canonical_write_and_retained_source_verified": bool(args.verify_write),
                      "fixture": current["fixture"]}))


if __name__ == "__main__":
    main()
