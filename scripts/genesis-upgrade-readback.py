#!/usr/bin/env python3
"""Read copied, stopped synthetic emulator stores; never writes or seeds phone data."""
import argparse
import json
from pathlib import Path
import runpy
import sqlite3

ACTOR = "Genesis Legacy Retention Actor"
ALLY = "Genesis Legacy Retention Ally"
ITEM = "Genesis Legacy Retention Compass"
SUPPLIES = "Genesis Legacy Retention Supplies"
RESOURCE = "Genesis Legacy Retention Charges"
DATABASES = ("astravar.db", "astravar-campaign-ledger.db", "astravar-reforge.db")


def connect(path):
    connection = sqlite3.connect(path.resolve().as_uri() + "?mode=ro", uri=True)
    connection.row_factory = sqlite3.Row
    connection.execute("PRAGMA query_only=ON")
    return connection


def inspect(folder):
    for name in DATABASES:
        if not (folder / name).is_file():
            raise ValueError(f"Required baseline store missing: {name}")
    # Reuse the established WAL-aware logical table hashing. All inputs are
    # disposable host copies; no database is copied back into the application.
    helper = runpy.run_path(str(Path(__file__).with_name("reforge_upgrade_fixture.py")))
    tables = helper["snapshot"](folder)
    with connect(folder / "astravar-reforge.db") as connection:
        characters = [(dict(row), json.loads(row["characterJson"])) for row in
                      connection.execute("SELECT * FROM reforge_characters")]
        matches = [(row, character) for row, character in characters if character["name"] == ACTOR]
        if len(matches) != 1:
            raise ValueError("Expected exactly one UI-created synthetic legacy actor")
        row, character = matches[0]
        assert row["profileId"] == character["id"]
        assert character["currentHp"] == 17 and character["baseMaxHp"] == 32
        assert character["temporaryHp"] == 3 and character["baseAc"] == 16
        definitions = [item for item in character["itemDefinitions"] if item["name"] == ITEM]
        assert len(definitions) == 1
        items = [item for item in character["items"] if item["definitionId"] == definitions[0]["id"]]
        assert len(items) == 1 and items[0]["quantity"] == 1 and items[0]["state"] == "WORN"
        supply_definitions = [item for item in character["itemDefinitions"] if item["name"] == SUPPLIES]
        assert len(supply_definitions) == 1
        supplies = [item for item in character["items"] if item["definitionId"] == supply_definitions[0]["id"]]
        assert len(supplies) == 1 and supplies[0]["quantity"] == 3 and supplies[0]["state"] == "CARRIED"
        assert supplies[0]["id"] != items[0]["id"]
        resources = [pool for pool in character["resources"] if pool["name"] == RESOURCE]
        assert len(resources) == 1 and resources[0]["current"] == 2 and resources[0]["maximum"] == 5
    with connect(folder / "astravar-campaign-ledger.db") as connection:
        companions = [dict(row) for row in connection.execute("SELECT * FROM character_profiles WHERE name=?", (ALLY,))]
        assert len(companions) == 1
        companion = companions[0]
        assert companion["currentHp"] == 7 and companion["maximumHp"] == 18
        assert connection.execute("SELECT count(*) FROM companion_states WHERE companionId=?", (companion["id"],)).fetchone()[0] == 1
        source_actor = connection.execute("SELECT * FROM character_profiles WHERE id=? AND name=?", (character["id"], ACTOR)).fetchone()
        assert source_actor is not None
        relationships = [dict(row) for row in connection.execute("SELECT * FROM campaign_links WHERE label=?", ("Fictional retained ally",))]
        assert len(relationships) == 1
        relationship = relationships[0]
        assert {relationship["fromId"], relationship["toId"]} == {character["id"], companion["id"]}
    return {"databases": tables, "fixture": {
        "actor_id": character["id"], "actor_revision": character["revision"],
        "item_id": items[0]["id"], "definition_id": definitions[0]["id"],
        "supplies_item_id": supplies[0]["id"], "supplies_definition_id": supply_definitions[0]["id"],
        "resource_id": resources[0]["id"], "companion_id": companion["id"],
        "companion_revision": companion["revision"], "relationship_id": relationship["id"],
        "source_identity_matches": True,
    }}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("folder", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--compare", type=Path)
    parser.add_argument("--synthetic-emulator", action="store_true", required=True)
    args = parser.parse_args()
    current = inspect(args.folder)
    if args.compare:
        before = json.loads(args.compare.read_text())
        verify(before, current)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(current, indent=2) + "\n")
    print(json.dumps({"synthetic_ui_fixture_verified": True, "unchanged_after_upgrade": bool(args.compare),
                      "databases": len(current["databases"]),
                      "tables": sum(len(value["tables"]) for value in current["databases"].values()),
                      "fixture": current["fixture"]}))


def verify(before, current):
    if before != current:
        changed = [name for name, value in before["databases"].items()
                   if current["databases"].get(name) != value]
        raise ValueError("Upgrade changed saved identities or logical database contents: " + ", ".join(changed))


if __name__ == "__main__":
    main()
