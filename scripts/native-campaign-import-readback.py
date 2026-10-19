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


def sha(value):
    return hashlib.sha256(value.encode("utf-8")).hexdigest()


def normalized(db):
    result = {}
    for (name,) in db.execute("SELECT name FROM sqlite_master WHERE type='table' ORDER BY name"):
        if name.startswith("sqlite_") or name in ARCHIVE_TABLES or name == "room_master_table":
            continue
        if not name.replace("_", "").isalnum():
            raise ValueError("Unexpected table name")
        rows = [json.dumps(list(row), ensure_ascii=False, separators=(",", ":"))
                for row in db.execute(f'SELECT * FROM "{name}"')]
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
        if db.execute("PRAGMA user_version").fetchone()[0] != 4:
            raise ValueError("Expected additive campaign Room schema 4")
        if args.action == "snapshot":
            result = {"marker": MARKER, "synthetic_only": True, "tables": normalized(db)}
            args.manifest.write_text(json.dumps(result, indent=2) + "\n")
            return {"marker": MARKER, "status": "baseline-captured", "tables": len(result["tables"])}
        expected = json.loads(args.manifest.read_text())
        assert expected["marker"] == MARKER and expected["synthetic_only"] is True
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
                "source_relationships": len(expected_edges), "active_imported_records": len(references),
                "preexisting_table_count": len(expected["tables"]), "preexisting_rows_unchanged": True,
                "original_content_and_source_hashes_verified": True, "backup_integrity_verified": True, "large_source_and_backup_rows_verified": oversized,
                "package_sha256": package_hash, "receipt_states": sorted(row["state"] for row in receipts.values())}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("snapshot", "applied", "rolled-back"))
    parser.add_argument("--database", type=Path, required=True)
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--fixture", type=Path)
    parser.add_argument("--backup-document", type=Path)
    parser.add_argument("--synthetic-demo", action="store_true")
    args = parser.parse_args()
    print(json.dumps(run(args), indent=2))
