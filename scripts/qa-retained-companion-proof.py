"""Independent read-only checks on a stopped synthetic Android 16 native journey."""
import hashlib
import json
from pathlib import Path
import sqlite3
import sys

database, stage, output = sys.argv[1:]
assert stage in {"adopted", "equipped"}
db = sqlite3.connect(Path(database).resolve().as_uri() + "?mode=ro", uri=True)
db.row_factory = sqlite3.Row
assert db.execute("PRAGMA user_version").fetchone()[0] == 9
assert not list(db.execute("PRAGMA foreign_key_check"))

def one(rows, what):
    values = list(rows)
    assert len(values) == 1, (what, len(values))
    return values[0]

def actor(name):
    profile = one(db.execute("SELECT * FROM character_profiles WHERE name = ?", (name,)), name)
    row = one(db.execute("SELECT * FROM reforge_characters WHERE profileId = ?", (profile["id"],)), name)
    data = json.loads(row["characterJson"])
    assert data["id"] == profile["id"] and data["name"] == name
    assert data["currentHp"] == profile["currentHp"] and data["baseMaxHp"] == profile["maximumHp"]
    authority = one(db.execute("SELECT * FROM genesis_actor_authority WHERE actorId=?", (profile["id"],)), name)
    assert authority["playable"] == 1 and authority["playableRevision"] == row["revision"]
    assert hashlib.sha256(row["characterJson"].encode()).hexdigest() == authority["projectionFingerprint"]
    return data, row, authority

ally, saved, authority = actor("CI Native Ally")
hero, _, _ = actor("CI Native Hero Revised")
assert (hero["currentHp"], hero["baseMaxHp"]) == (31,37)
assert (ally["currentHp"], ally["baseMaxHp"], authority["role"]) == (6,12,"COMPANION")
assert authority["ownerActorId"] is None
historic = one(db.execute("SELECT * FROM character_profiles WHERE name='Genesis Legacy Retention Ally'"),"retained source")
assert historic["id"] != ally["id"] and (historic["currentHp"],historic["maximumHp"]) == (7,18)
origin = json.loads(saved["originJson"])
snapshot = origin["sourceSnapshotJson"]
assert origin["sourceProfileId"] == ally["id"]
assert hashlib.sha256(snapshot.encode()).hexdigest() == origin["sourceFingerprint"]
profile = json.loads(snapshot)["profile"]
assert (profile["id"],profile["name"],profile["currentHp"],profile["maximumHp"]) == (ally["id"],"CI Native Ally",4,12)
heal = one(db.execute("SELECT * FROM reforge_changes WHERE profileId=? AND operation='Apply healing'", (ally["id"],)),"one healing event")
before, after = map(json.loads, (heal["beforeJson"],heal["afterJson"]))
assert heal["outcomeType"] == "CHARACTER"
assert before["character"]["id"] == after["character"]["id"] == ally["id"]
assert (before["character"]["currentHp"],after["character"]["currentHp"]) == (4,6)
assert before["revision"] == heal["priorRevision"] and after["revision"] == heal["resultingRevision"]
assert heal["resultingRevision"] == heal["priorRevision"] + 1
receipt = one(db.execute("SELECT * FROM genesis_authority_receipts WHERE commandId=?", (authority["activationCommandId"],)), "explicit playable activation")
assert receipt["kind"] in ("register-actor","create-actor","accept-actor")
assert json.loads(receipt["outcomeJson"])["actor"]["actorId"] == ally["id"]
summary = {"scope":"isolated native Android 16","stage":stage,"room":9,"sourceBeforeAdoption":[4,12],
           "identity":ally["id"],"activation":receipt["commandId"],"healCommand":heal["commandId"],
           "afterHealing":[6,12],"mainActor":[31,37],"historicalAlly":[7,18],"unassignedOwner":True}
if stage == "equipped":
    definition = one((d for d in ally["itemDefinitions"] if d["name"]=="CI Native Ally Charm"),"companion charm")
    item = one((i for i in ally["items"] if i["definitionId"]==definition["id"]),"one physical charm")
    assert item["quantity"] == 1 and item["state"]=="WORN"
    canonical = one(db.execute("SELECT * FROM genesis_item_authority WHERE itemId=?", (item["id"],)), "canonical holding")
    assert canonical["ownerActorId"] == ally["id"] and json.loads(canonical["instanceJson"]) == item
    assert not any(d["name"]=="CI Native Ally Charm" for d in hero["itemDefinitions"])
    summary["equippedItem"] = item["id"]
Path(output).write_text(json.dumps(summary,indent=2)+"\n")
print(json.dumps(summary))
