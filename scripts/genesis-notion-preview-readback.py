#!/usr/bin/env python3
"""Prove that actual native Notion file preview/cancel changed no saved campaign row."""
import argparse
import json
from pathlib import Path
import runpy

COMMON = runpy.run_path(str(Path(__file__).with_name("genesis-upgrade-readback.py")))


def inspect(before_database, database, *, synthetic_emulator=False):
    COMMON["require"](synthetic_emulator, "Only copied fictional emulator records are supported")
    before_database, database = Path(before_database), Path(database)
    COMMON["require"](before_database.is_file() and database.is_file(), "Both closed snapshots must exist")
    before = COMMON["database_snapshot"](before_database, "CampaignLedgerDb", 9)
    after = COMMON["database_snapshot"](database, "CampaignLedgerDb", 9)
    with COMMON["connect"](database) as db:
        hero = list(db.execute("SELECT id FROM character_profiles WHERE name = ?", ("Genesis Native Hero",)))
        COMMON["require"](len(hero) == 1, "Expected the existing fictional connected-journey hero")
    COMMON["require"](before == after, "Notion preview/cancel changed saved records, mechanics, currency or history")
    return {"result": "PASS", "scope": "native-file-preview-cancel-only", "tables_verified": len(after["tables"]),
            "rows_verified": sum(table["rows"] for table in after["tables"].values()),
            "all_campaign_rows_unchanged": True, "native_apply_performed": False}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--before-database", required=True)
    parser.add_argument("--database", required=True)
    parser.add_argument("--synthetic-emulator", action="store_true")
    parser.add_argument("--output", required=True)
    args = parser.parse_args()
    result = inspect(args.before_database, args.database, synthetic_emulator=args.synthetic_emulator)
    Path(args.output).write_text(json.dumps(result, indent=2) + "\n")
    print(json.dumps(result))


if __name__ == "__main__":
    main()
