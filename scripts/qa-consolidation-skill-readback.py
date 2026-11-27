"""Read a closed synthetic code 59 Room database after the consolidation skill journey."""
import argparse
import hashlib
import json
import sqlite3
import uuid
from contextlib import closing
from pathlib import Path


def require(condition, message):
    if not condition:
        raise ValueError(message)


def unique(values, message):
    values = list(values)
    require(len(values) == 1, f"{message} (found {len(values)})")
    return values[0]


def listed(actor):
    return {entry["id"]: entry for entry in actor.get("customSkills", [])}


def pins(actor):
    return set(actor.get("pinnedSkillIds", []))


def named(actor, name):
    return {key for key, skill in listed(actor).items() if skill["name"] == name}


def check_id(value):
    require(value.startswith("skill:"), "Missing typed custom skill identity")
    require(str(uuid.UUID(value[6:])) == value[6:], "Invalid saved custom skill UUID")


def read(path):
    require(path.name == "astravar-campaign-ledger.db" and path.is_file(), "Missing closed Room store")
    with closing(sqlite3.connect(path.resolve().as_uri() + "?mode=ro", uri=True)) as db:
        db.row_factory = sqlite3.Row
        require(db.execute("PRAGMA integrity_check").fetchone()[0] == "ok", "SQLite integrity failed")
        require(db.execute("PRAGMA foreign_key_check").fetchone() is None, "SQLite foreign key failed")
        require(db.execute("PRAGMA user_version").fetchone()[0] == 9, "Unexpected Room schema")
        rows = db.execute("SELECT profileId, characterJson, revision FROM reforge_characters")
        actor, profile_id, revision = unique(((value, row["profileId"], row["revision"])
            for row in rows if (value := json.loads(row["characterJson"])).get("name") == "CI Native Hero Revised"),
            "Expected one synthetic actor")
        require(actor["id"] == profile_id and actor["revision"] == revision, "Source actor/revision changed")
        current = unique(named(actor, "Alchemy native"), "Retained custom skill")
        check_id(current)
        require("Arcana" in pins(actor) and current in pins(actor) and not named(actor, "Zoology native"),
                "Final standard pin, new custom pin or removed skill incorrect")
        require(listed(actor)[current]["notes"] == "Retained custom skill for complete backup verification",
                "Retained custom skill does not match the final fixture")
        rows = db.execute("SELECT operation, priorRevision, resultingRevision, beforeJson, afterJson "
            "FROM reforge_changes WHERE profileId=? AND outcomeType='CHARACTER' "
            "AND operation IN ('Add custom skill','Edit custom skill','Remove custom skill','Pin skill','Unpin skill') "
            "ORDER BY resultingRevision", (profile_id,))
        changes = []
        for row in rows:
            before = json.loads(row["beforeJson"])["character"]
            after = json.loads(row["afterJson"])["character"]
            require(before["id"] == after["id"] == profile_id, "Change belongs to another actor")
            require(before["revision"] == row["priorRevision"] and after["revision"] == row["resultingRevision"]
                    and after["revision"] == before["revision"] + 1, "Change revision mismatch")
            changes.append((row["operation"], before, after))
        def change(operation, predicate):
            return unique(((b, a) for op, b, a in changes if op == operation and predicate(b, a)), operation)
        before, after = change("Edit custom skill", lambda b, a: bool(named(b, "Alchemy native") & named(a, "Zoology native")))
        original = unique(named(before, "Alchemy native") & named(after, "Zoology native"), "Stable renamed identity")
        check_id(original)
        require(original != current and original not in listed(actor) and original not in pins(actor), "Deleted identity reused")
        require(listed(before)[original].get("bonus") == 1 and listed(before)[original]["training"].get("proficient") is True,
                "Original trained skill and bonus differ")
        def entered(b, a, key):
            return key not in pins(b) and key in pins(a)
        steps = [
            change("Add custom skill", lambda b, a: original not in listed(b) and original in named(a, "Alchemy native")),
            change("Pin skill", lambda b, a: entered(b, a, original)),
            change("Pin skill", lambda b, a: entered(b, a, "Arcana") and original in pins(a)),
            (before, after),
            change("Unpin skill", lambda b, a: original in pins(b) and original not in pins(a) and "Arcana" in pins(a)),
            change("Remove custom skill", lambda b, a: original in named(b, "Zoology native") and original not in listed(a)),
            change("Add custom skill", lambda b, a: current not in listed(b) and current in named(a, "Alchemy native")),
            change("Pin skill", lambda b, a: entered(b, a, current) and "Arcana" in pins(a)),
        ]
        revisions = [a["revision"] for _, a in steps]
        require(revisions == sorted(set(revisions)), "Skill transitions are out of order or duplicated")
        require({"Arcana", original}.issubset(pins(before)) and {"Arcana", original}.issubset(pins(after)),
                "Rename changed a standard or custom pin")
        roll = unique(db.execute("SELECT id, characterRevision, components, manual, ownership FROM reforge_rolls "
            "WHERE profileId=? AND label='Zoology native'", (profile_id,)), "Historical named roll")
        component = unique(json.loads(roll["components"]), "Expected one check component")
        die = unique(component["dice"], "One physical d20")
        require(roll["manual"] == 1 and roll["ownership"] == "Player"
                and component.get("checkKind") == "SKILL" and component.get("subjectId") == original
                and die["sides"] == 20 and die["face"] == 13 and die.get("kept", True)
                and die.get("sign", 1) == 1 and component["modifier"] == 7, "Roll source, ownership, modifier or face changed")
        require(revisions[3] <= roll["characterRevision"] < revisions[4], "Roll was outside the renamed skill interval")
        command = unique(db.execute("SELECT afterJson, priorRevision, resultingRevision FROM reforge_changes "
            "WHERE commandId=? AND profileId=? AND outcomeType='ROLL'", (roll["id"], profile_id)), "Durable roll receipt")
        receipt = json.loads(command["afterJson"])
        require(receipt["id"] == roll["id"] and receipt["components"] == [component]
                and command["priorRevision"] == command["resultingRevision"] == roll["characterRevision"],
                "Roll receipt differs from independently read outcome")
        digest = lambda value: hashlib.sha256(value.encode()).hexdigest()
        return {"fixtureOnly": True, "source": "stopped Room database", "roomSchema": 9,
            "actorRevision": revision, "actorIdSha256": digest(profile_id), "standardPin": "Arcana",
            "originalCustomIdSha256": digest(original), "retainedCustomIdSha256": digest(current),
            "retainedCustomName": "Alchemy native", "renamedOriginal": "Zoology native",
            "originalRemoved": True, "standardAndCustomPinsPreserved": True, "skillCommitRevisions": revisions,
            "rollIdSha256": digest(roll["id"]), "recordedAtCharacterRevision": roll["characterRevision"],
            "roll": {"ownership": roll["ownership"], "physicalFace": die["face"], "modifier": component["modifier"],
                     "total": die["face"] + component["modifier"], "linksRemovedOriginalIdentity": True}}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--synthetic-emulator", action="store_true", required=True)
    parser.add_argument("database", type=Path)
    args = parser.parse_args()
    print(json.dumps(read(args.database), indent=2))
