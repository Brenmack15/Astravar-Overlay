"""Read the closed, isolated resource fixture after native Visual Rebirth."""
import argparse
import hashlib
import json
import sqlite3
from contextlib import closing
from pathlib import Path

def pool(character):
    selected, = [p for p in character["resources"] if p["name"] == "CI Native Charges"]
    return selected

def fingerprint(value):
    return hashlib.sha256(value.encode()).hexdigest()

def verify(database):
    with closing(sqlite3.connect(database.resolve().as_uri()+"?mode=ro",uri=True)) as connection:
        (actor,raw), = connection.execute(
            "SELECT profileId, characterJson FROM reforge_characters WHERE json_extract(characterJson,'$.name') = ?",
            ("CI Native Hero Revised",)).fetchall()
        (command,prior,revision,previous,result), = connection.execute(
            "SELECT commandId,priorRevision,resultingRevision,beforeJson,afterJson FROM reforge_changes "
            "WHERE profileId=? AND operation=? AND outcomeType='CHARACTER'",(actor,"Use CI Native Charges")).fetchall()
    before,after,current = json.loads(previous),json.loads(result),json.loads(raw)
    assert command and revision == prior + 1 and before["revision"] == prior and after["revision"] == revision
    assert all(c["id"] == actor for c in (before["character"],after["character"],current))
    original,committed,live = map(pool,(before["character"],after["character"],current))
    assert original["id"] == committed["id"] == live["id"]
    assert [p["maximum"] for p in (original,committed,live)] == [3,3,3]
    assert [p["current"] for p in (original,committed,live)] == [2,1,1] and dict(original,current=1) == committed
    return {"syntheticEmulator":True,"actorHash":fingerprint(actor),"poolHash":fingerprint(live["id"]),
            "commandHash":fingerprint(command),"operationCount":1,"priorRevision":prior,"resultRevision":revision,
            "before":2,"after":1,"readback":1,"maximum":3,"screenshot":"rebirth-resource-persistence.png"}

if __name__ == "__main__":
    parser=argparse.ArgumentParser()
    parser.add_argument("--synthetic-emulator",action="store_true",required=True)
    parser.add_argument("database",type=Path)
    print(json.dumps(verify(parser.parse_args().database),sort_keys=True))
