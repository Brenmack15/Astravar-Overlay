#!/usr/bin/env python3
"""Verify an isolated API 37 upgrade from its stopped Room stores without launching Android."""
import argparse
import hashlib
import json
import sqlite3
from contextlib import closing
from pathlib import Path

TABLES = ("reforge_characters", "reforge_changes", "reforge_rolls", "ledger_items", "ledger_events",
          "party_wallets", "genesis_campaigns", "genesis_actor_authority", "genesis_item_authority", "genesis_authority_receipts")


def check(value, message):
    if not value:
        raise ValueError(message)


def snapshot(directory):
    database = directory / "astravar-campaign-ledger.db"
    check(database.is_file(), "Missing stopped canonical Room database")
    with closing(sqlite3.connect(database.resolve().as_uri() + "?mode=ro", uri=True)) as db:
        check(db.execute("PRAGMA integrity_check").fetchall() == [("ok",)], "Room integrity failure")
        check(not db.execute("PRAGMA foreign_key_check").fetchone(), "Broken native foreign key")
        check(db.execute("PRAGMA user_version").fetchone()[0] == 9, "Unexpected canonical Room schema")
        characters = [(identity, json.loads(value)) for identity, value in db.execute("SELECT profileId, characterJson FROM reforge_characters")]
        matches = [(actor, row) for actor, row in characters if row.get("name") == "Ascension upgrade fixture"]
        check(len(matches) == 1, "Missing or duplicated code 58 fixture")
        actor, row = matches[0]
        check(actor == row["id"] and (row.get("currentHp"), row.get("baseMaxHp"), row.get("temporaryHp")) == (17, 32, 3), "Unexpected retained actor state")
        check(row.get("baseAbilities", {}).get("INT") == 15, "Retained Intelligence changed")
        table_info = {}
        for table in TABLES:
            # Preserve integer/unknown distinctions, source JSON bytes, immutable receipts and every stored column.
            data = [json.dumps(tuple(r), ensure_ascii=False, separators=(",", ":"), default=lambda v: {"hex":v.hex()})
                    for r in db.execute(f"SELECT * FROM {table}")]
            table_info[table] = {"count": len(data), "sha256": hashlib.sha256(json.dumps(sorted(data), separators=(",", ":")).encode()).hexdigest()}
        check(table_info["reforge_changes"]["count"] > 0, "No native creation history")
    cache = directory / "astravar-cloud-cache.db"
    if cache.exists():
        with closing(sqlite3.connect(cache.resolve().as_uri() + "?mode=ro", uri=True)) as db:
            for table in ("workspace", "records", "commands", "fenced"):
                check(db.execute(f"SELECT count(*) FROM {table}").fetchone()[0] == 0, "Upgrade unexpectedly bound a cloud continuity, submitted a command or populated a cache")
    return table_info


def verify(path):
    sdk, release = [(path / leaf).read_text().strip() for leaf in ("sdk.txt", "release.txt")]
    check((sdk, release) == ("37", "17"), "Incorrect Android guest version")
    previous, current = snapshot(path / "before"), snapshot(path / "after")
    check(previous == current, "An existing gameplay table changed during the installation or read-only journeys")
    return {"sdk":int(sdk), "androidRelease":release, "emulator":True, "guestFingerprint":(path / "guest-fingerprint.txt").read_text().strip(),
            "historicalVersion":58, "roomSchema":9, "tables":current, "ordinaryHp":17, "maxHp":32, "temporaryHp":3,
            "cloudWorkspaceBound":False, "physicalDevice":False, "fixtureOnly":True}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directory", type=Path)
    directory = parser.parse_args().directory
    result = verify(directory)
    with (directory / "readback.json").open("x") as out:
        json.dump(result, out, indent=2)
        out.write("\n")
