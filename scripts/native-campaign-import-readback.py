#!/usr/bin/env python3
"""Independently inspect copied fictional-emulator campaign databases. Never connects to a device."""
import argparse
import hashlib
import json
from pathlib import Path
import sqlite3

ARCHIVE_TABLES = {"campaign_references", "campaign_reference_relations", "campaign_import_receipts",
                  "campaign_import_backups", "campaign_archive_sources"}
MARKER = "ASTRAVAR_FICTIONAL_NATIVE_IMPORT_V1"
SUPPORTED_ROOM_SCHEMAS = (4, 5, 6)
# Prompt settings add Room tables without changing the campaign backup protocol.
SUPPORTED_MAPPED_EXPORT_SCHEMAS = (4, 5)


def sha(value):
    return hashlib.sha256(value.encode("utf-8")).hexdigest()


def normalized(db, *, include_archive=False, omit_keys=None):
    result = {}
    omit_keys = omit_keys or {}
    for (name,) in db.execute("SELECT name FROM sqlite_master WHERE type='table' ORDER BY name"):
        if name.startswith("sqlite_") or (name in ARCHIVE_TABLES and not include_archive) or name == "room_master_table":
            continue
        if not name.replace("_", "").isalnum():
            raise ValueError("Unexpected table name")
        keys = [row[1] for row in sorted(db.execute(f'PRAGMA table_info("{name}")'), key=lambda row: row[5]) if row[5]]
        rows = [json.dumps(list(row), ensure_ascii=False, separators=(",", ":"))
                for row in db.execute(f'SELECT * FROM "{name}"')
                if tuple(row[key] for key in keys) not in omit_keys.get(name, set())]
        result[name] = {"count": len(rows), "sha256": sha("\n".join(sorted(rows)))}
    return result


def run(args):
    if not args.synthetic_demo:
        raise ValueError("Explicit --synthetic-demo acknowledgement is required")
    path = args.database
    if path.name != "astravar-campaign-ledger.db" or path.is_symlink() or not path.is_file():
        raise ValueError("Use a regular copied fictional campaign ledger database")
    with sqlite3.connect(path) as db:
        db.row_factory = sqlite3.Row
        room_schema = db.execute("PRAGMA user_version").fetchone()[0]
        expected_schema = getattr(args, "expected_room_schema", 4)
        if expected_schema not in SUPPORTED_ROOM_SCHEMAS or room_schema != expected_schema:
            raise ValueError(f"Expected additive campaign Room schema {expected_schema}; found {room_schema}")
        if args.action == "snapshot":
            result = {"marker": MARKER, "synthetic_only": True, "tables": normalized(db),
                      "allTables": normalized(db, include_archive=True), "roomSchema": room_schema}
            args.manifest.write_text(json.dumps(result, indent=2) + "\n")
            return {"marker": MARKER, "status": "baseline-captured", "room_schema": room_schema, "tables": len(result["tables"])}
        expected = json.loads(args.manifest.read_text())
        assert expected["marker"] == MARKER and expected["synthetic_only"] is True
        assert expected.get("roomSchema", 4) == room_schema, "Campaign Room schema changed since the captured baseline"
        assert normalized(db) == expected["tables"], "A preexisting campaign row changed during import/recovery"
        envelope = json.loads(args.fixture.read_text())
        assert envelope["format"] == "astravar-campaign-archive" and envelope["schema"] == 1
        package_hash = sha(envelope["payload"])
        assert package_hash == envelope["payloadSha256"]
        source = json.loads(envelope["payload"])
        assert source["campaignId"] == "fictional-native-migration"
        assert all(row["sourceUrl"].startswith("https://example.invalid/") for row in source["records"])
        receipts = {row["packageHash"]: dict(row) for row in db.execute("SELECT * FROM campaign_import_receipts")}
        assert receipts[package_hash]["state"] == "APPLIED"
        assert receipts[package_hash]["recordCount"] == len(source["records"])
        expected_edges = {(source["campaignId"] + "::" + row["externalId"], source["campaignId"] + "::" + target)
                          for row in source["records"] for target in row.get("relationIds", [])}
        assert receipts[package_hash]["relationCount"] == len(expected_edges)
        backup = db.execute("SELECT * FROM campaign_import_backups WHERE packageHash=?", (package_hash,)).fetchone()
        assert backup and sha(backup["snapshotJson"]) == backup["snapshotSha256"]
        backups = list(db.execute("SELECT * FROM campaign_import_backups"))
        assert all(sha(row["snapshotJson"]) == row["snapshotSha256"] for row in backups)
        oversized = len(envelope["payload"].encode("utf-8")) > 2 * 1024 * 1024
        if oversized:
            assert any(len(row["snapshotJson"].encode("utf-8")) > 2 * 1024 * 1024 for row in backups), "Native stress fixture did not exercise a large verified recovery row"
            for attachment in source["attachments"]:
                import base64
                assert hashlib.sha256(base64.b64decode(attachment["base64"], validate=True)).hexdigest() == attachment["sha256"]
        if args.backup_document:
            assert sha(args.backup_document.read_text()) == backup["snapshotSha256"], "Saved document differs from verified recovery backup"
        references = {row["externalId"]: dict(row) for row in db.execute(
            "SELECT * FROM campaign_references WHERE campaignId=?", (source["campaignId"],))}
        if args.action == "applied":
            assert len(references) == len(source["records"])
            for row in source["records"]:
                actual = references[row["externalId"]]
                for field in ("title", "category", "body", "propertiesJson", "sourceUrl", "sourceRevision", "contentSha256"):
                    assert actual[field] == row[field], (row["externalId"], field)
                assert actual["id"] == source["campaignId"] + "::" + row["externalId"]
                assert actual["packageHash"] == package_hash
                assert sha(actual["body"]) == actual["contentSha256"]
            edges = [dict(row) for row in db.execute("SELECT * FROM campaign_reference_relations")]
            assert {(edge["fromId"], edge["toId"]) for edge in edges} == expected_edges
            for edge in edges:
                assert edge["resolved"] == 1
                assert edge["id"] == sha(edge["fromId"] + "\0" + edge["toId"])
                assert edge["kind"] == "notion-relation"
            stored = db.execute("SELECT payloadJson FROM campaign_archive_sources WHERE packageHash=?", (package_hash,)).fetchone()
            assert stored[0] == envelope["payload"] and sha(stored[0]) == package_hash
            assert "rollback:" + package_hash not in receipts
        else:
            assert not references
            assert db.execute("SELECT COUNT(*) FROM campaign_reference_relations").fetchone()[0] == 0
            assert db.execute("SELECT COUNT(*) FROM campaign_archive_sources").fetchone()[0] == 0
            assert receipts["rollback:" + package_hash]["state"] == "ROLLED_BACK"
            assert receipts["rollback:" + package_hash]["readbackSha256"] == backup["sourceStateSha256"]
        return {"marker": MARKER, "status": args.action + "-verified", "source_records": len(source["records"]),
                "room_schema": room_schema,
                "source_relationships": len(expected_edges), "active_imported_records": len(references),
                "preexisting_table_count": len(expected["tables"]), "preexisting_rows_unchanged": True,
                "original_content_and_source_hashes_verified": True, "backup_integrity_verified": True, "large_source_and_backup_rows_verified": oversized,
                "package_sha256": package_hash, "receipt_states": sorted(row["state"] for row in receipts.values())}


MAPPED_TABLES = {
    "projects": "invention_projects", "stages": "project_stages", "requirements": "material_requirements",
    "entities": "connection_entities", "facts": "connection_facts", "milestones": "connection_milestones",
    "links": "campaign_links", "companionStates": "companion_states", "companionResources": "companion_resources",
    "itemDetails": "item_details", "itemProperties": "item_properties", "audit": "campaign_audit",
    "receipts": "command_receipts", "crafts": "craft_executions", "consumptions": "craft_consumptions",
    "characterProfiles": "character_profiles", "characterAbilities": "character_abilities",
    "partySheets": "party_sheets", "placements": "item_placements", "wallets": "party_wallets",
    "checkpoints": "campaign_checkpoints", "externalReferences": "external_record_references",
}


def verify_mapped(database, manifest, fixture, *, rolled_back=False, synthetic_demo=False, expected_room_schema=None):
    """Compare complete fictional typed snapshots and all untouched rows from fresh SQLite queries."""
    if not synthetic_demo:
        raise ValueError("Explicit --synthetic-demo acknowledgement is required")
    if database.name != "astravar-campaign-ledger.db" or database.is_symlink() or not database.is_file():
        raise ValueError("Use a regular copied fictional campaign ledger database")
    baseline = json.loads(manifest.read_text())
    assert baseline["marker"] == MARKER and baseline["synthetic_only"] is True
    assert "allTables" in baseline, "Capture a fresh complete baseline immediately before the typed test"
    envelope = json.loads(fixture.read_text())
    assert envelope["format"] == "astravar-campaign-archive" and envelope["schema"] == 1
    package = sha(envelope["payload"])
    assert package == envelope["payloadSha256"]
    source = json.loads(envelope["payload"])
    assert source["campaignId"] == "fictional-native-typed"
    assert source["sourceRootUrl"].startswith("https://example.invalid/")
    assert all(row["sourceUrl"].startswith("https://example.invalid/") and row["visibility"] == "player" for row in source["records"])
    mapped = source["mapped"]
    assert mapped["format"] == "astravar-campaign-items" and mapped["schema"] in SUPPORTED_MAPPED_EXPORT_SCHEMAS, "Unsupported mapped campaign export schema"
    assert set(mapped["records"]) == set(MAPPED_TABLES)
    groups = {"ledger_items": mapped["items"], "ledger_events": mapped["events"]}
    groups.update({table: mapped["records"][label] for label, table in MAPPED_TABLES.items()})
    assert groups["ledger_items"] and groups["character_profiles"]
    assert all(row.get("source", "").startswith("FICTIONAL ") for rows in groups.values() for row in rows), "Only marked fictional rows are accepted"
    with sqlite3.connect(database) as db:
        db.row_factory = sqlite3.Row
        room_schema = db.execute("PRAGMA user_version").fetchone()[0]
        assert room_schema in SUPPORTED_ROOM_SCHEMAS, "Unsupported campaign Room schema"
        if expected_room_schema is not None:
            assert room_schema == expected_room_schema, "Mapped readback Room schema differs from the explicit expected version"
        assert baseline.get("roomSchema", 4) == room_schema, "Campaign Room schema changed since the captured baseline"
        assert mapped["schema"] <= room_schema, "Mapped fixture requires a newer campaign Room schema"
        omitted = {}
        for table, rows in groups.items():
            columns = list(db.execute(f'PRAGMA table_info("{table}")'))
            keys = [row[1] for row in sorted(columns, key=lambda row: row[5]) if row[5]]
            assert columns and keys, "Missing typed table or primary key: " + table
            expected_columns = {row[1] for row in columns}
            actual = {tuple(row[key] for key in keys): dict(row) for row in db.execute(f'SELECT * FROM "{table}"')}
            incoming_keys = {tuple(row[key] for key in keys) for row in rows}
            assert len(incoming_keys) == len(rows), "Duplicate fixture identity: " + table
            omitted[table] = incoming_keys
            for row in rows:
                key = tuple(row[key] for key in keys)
                if rolled_back:
                    assert key not in actual, "Typed row survived rollback: " + table
                    continue
                expected_row = dict(row)
                if table == "party_wallets" and room_schema >= 5 and mapped["schema"] == 4:
                    # Schema4 has no EP/PP values. Its additive schema5 migration
                    # must preserve both as unknown SQL NULL, never inferred zero.
                    assert row.get("electrum") is None and row.get("platinum") is None, "Legacy schema4 wallet cannot establish electrum or platinum, including zero"
                    expected_row.setdefault("electrum", None)
                    expected_row.setdefault("platinum", None)
                assert set(expected_row) == expected_columns, "Fixture must supply complete typed values: " + table
                assert key in actual, "Imported typed row missing: " + table
                saved = actual[key]
                for field, expected in expected_row.items():
                    found = saved[field]
                    if field in ("recordReferences", "unknowns"):
                        found = json.loads(found)
                    if isinstance(expected, bool):
                        expected = int(expected)
                    assert found == expected, f"Mapped readback differs: {table}.{field} {key}"
        reference_ids = {(source["campaignId"] + "::" + row["externalId"],) for row in source["records"]}
        edge_ids = {(sha(source["campaignId"] + "::" + row["externalId"] + "\0" + source["campaignId"] + "::" + target),)
                    for row in source["records"] for target in row["relationIds"]}
        omitted.update(campaign_references=reference_ids, campaign_reference_relations=edge_ids,
                       campaign_archive_sources={(package,)}, campaign_import_backups={(package,)},
                       campaign_import_receipts={(package,), ("rollback:" + package,)})
        assert normalized(db, include_archive=True, omit_keys=omitted) == baseline["allTables"], "A preexisting typed row or immutable archive evidence changed"
        receipt = db.execute("SELECT * FROM campaign_import_receipts WHERE packageHash=?", (package,)).fetchone()
        assert receipt and receipt["state"] == "APPLIED"
        assert receipt["recordCount"] == len(reference_ids) and receipt["relationCount"] == len(edge_ids)
        backup = db.execute("SELECT * FROM campaign_import_backups WHERE packageHash=?", (package,)).fetchone()
        assert backup and sha(backup["snapshotJson"]) == backup["snapshotSha256"]
        rollback = db.execute("SELECT * FROM campaign_import_receipts WHERE packageHash=?", ("rollback:" + package,)).fetchone()
        actual_references = {row["externalId"]: dict(row) for row in db.execute("SELECT * FROM campaign_references WHERE campaignId=?", (source["campaignId"],))}
        if rolled_back:
            assert rollback and rollback["state"] == "ROLLED_BACK" and rollback["readbackSha256"] == backup["sourceStateSha256"]
            assert not actual_references
            assert not db.execute("SELECT 1 FROM campaign_archive_sources WHERE packageHash=?", (package,)).fetchone()
        else:
            assert not rollback and len(actual_references) == len(source["records"])
            for row in source["records"]:
                saved = actual_references[row["externalId"]]
                assert saved["id"] == source["campaignId"] + "::" + row["externalId"] and saved["packageHash"] == package
                assert all(saved[field] == row[field] for field in ("title", "category", "body", "propertiesJson", "sourceUrl", "sourceRevision", "contentSha256"))
                assert sha(saved["body"]) == saved["contentSha256"]
            retained = db.execute("SELECT payloadJson FROM campaign_archive_sources WHERE packageHash=?", (package,)).fetchone()
            assert retained and retained[0] == envelope["payload"] and sha(retained[0]) == package
        return {"marker": MARKER, "status": "mapped-rollback-verified" if rolled_back else "mapped-applied-verified",
                "room_schema": room_schema, "mapped_fixture_schema": mapped["schema"],
                "synthetic_only": True, "mapped_counts": {table: len(rows) for table, rows in groups.items()},
                "mapped_complete_values_verified": not rolled_back, "null_values_preserved": not rolled_back,
                "preexisting_all_table_count": len(baseline["allTables"]), "preexisting_rows_and_archive_evidence_unchanged": True,
                "source_records": len(source["records"]), "source_payload_and_content_hashes_verified": not rolled_back,
                "backup_integrity_verified": True, "package_sha256": package}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("snapshot", "applied", "rolled-back"))
    parser.add_argument("--database", type=Path, required=True)
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--fixture", type=Path)
    parser.add_argument("--backup-document", type=Path)
    parser.add_argument("--synthetic-demo", action="store_true")
    parser.add_argument("--expected-room-schema", type=int, choices=SUPPORTED_ROOM_SCHEMAS, default=4)
    args = parser.parse_args()
    print(json.dumps(run(args), indent=2))
