#!/usr/bin/env python3
"""Read copied fictional emulator SQLite records after the native manual encounter journey.

Verify tracker command history independently of UI labels, while proving all
previous source records, canonical mechanics, rolls and histories remain exact.
No owner data, device connection, gameplay calculation or command is performed.
"""
import argparse
import copy
import json
from pathlib import Path
import runpy

COMMON = runpy.run_path(str(Path(__file__).with_name("genesis-connected-readback.py")))
require, connect, decode, compact, sha, one = (COMMON[name] for name in
    ("require", "connect", "decode", "compact", "sha", "one"))
HERO = COMMON["HERO"]
NPC = "Genesis Native Encounter Witness"
ENCOUNTER = "Genesis Native Manual Encounter"
NOTES = "Fictional manual tracker without mechanical changes"
OPERATIONS = ["SAVE_DRAFT", "START", "NEXT", "NEXT", "END", "REOPEN_SETUP"]


def inspect(before_database, database, *, synthetic_emulator=False):
    require(synthetic_emulator, "Only copied fictional emulator records are supported")
    before_database, database = Path(before_database), Path(database)
    require(before_database.is_file() and database.is_file(), "Both closed emulator database snapshots must exist")
    snapshots = [COMMON["UPGRADE"]["database_snapshot"](path, "CampaignLedgerDb", 9)
                 for path in (before_database, database)]
    with connect(before_database) as before, connect(database) as db:
        require(not list(db.execute("PRAGMA foreign_key_check")), "Encounter has an orphaned reference")
        def rows(table, connection=db):
            return [dict(row) for row in connection.execute(f'SELECT * FROM "{table}"')]
        def exact(table, column, value):
            return one((row for row in rows(table) if row[column] == value), f"{table} {value}")
        hero = one((row for row in rows("character_profiles") if row["name"] == HERO), "existing fictional hero")
        npc = one((row for row in rows("character_profiles") if row["name"] == NPC), "new source-only participant")
        hero_id, npc_id = hero["id"], npc["id"]
        actors = {row["actorId"]: row for row in rows("genesis_actor_authority")}
        hero_actor, npc_actor = actors[hero_id], actors[npc_id]
        campaign = exact("genesis_campaigns", "id", hero_actor["campaignId"])
        require(campaign["name"] == COMMON["CAMPAIGN"] and hero_actor["playable"] and hero_actor["role"] == "PLAYER" and
                npc_actor["campaignId"] == campaign["id"] and npc_actor["role"] == "NPC" and not npc_actor["playable"] and
                npc_actor["ownerActorId"] is None, "Encounter participants do not share the explicit fictional campaign")
        require(all(npc[key] is None for key in ("currentHp", "maximumHp", "temporaryHp", "armorClass", "level")) and
                all(row["profileId"] != npc_id for row in rows("reforge_characters")), "Source-only encounter NPC acquired invented mechanics")
        entity = exact("connection_entities", "id", npc_id)
        require(entity["name"] == NPC and entity["kind"] == "Person", "Source NPC lost its same-ID campaign connection")
        receipts = {row["commandId"]: row for row in rows("genesis_authority_receipts")}
        creation = receipts[npc_actor["activationCommandId"]]
        require(creation["kind"] == "create-actor" and decode(creation["requestJson"]) == [NPC, "NPC", campaign["id"], None, None] and
                creation["fingerprint"] == sha(compact(["genesis-authority-v1", "create-actor", creation["requestJson"]])) and
                decode(creation["outcomeJson"]) == {"actor": npc_actor, "profile": npc, "playable": None},
                "NPC creation is not bound to its exact campaign identity and unknown source values")
        record = one((row for row in rows("genesis_encounters") if row["title"] == ENCOUNTER), "single fictional encounter")
        encounter_id = record["id"]
        participants = sorted((row for row in rows("genesis_encounter_participants") if row["encounterId"] == encounter_id),
                              key=lambda row: row["position"])
        ordered = [{"encounterId": encounter_id, "actorId": hero_id, "initiative": 7, "position": 0},
                   {"encounterId": encounter_id, "actorId": npc_id, "initiative": 19, "position": 1}]
        require(participants == ordered, "Manual participant order or entered initiative changed")
        history = []
        for receipt in receipts.values():
            if receipt["kind"] != "encounter-v1":
                continue
            request, outcome = decode(receipt["requestJson"]), decode(receipt["outcomeJson"])
            if request["encounterId"] == encounter_id:
                history.append((receipt, request, outcome))
        history.sort(key=lambda entry: entry[2]["after"]["record"]["revision"])
        require([request["operation"] for _, request, _ in history] == OPERATIONS,
                "Expected one setup, one start, two next turns, one end and one explicit reopen")
        expected_states = [("DRAFT", 0, None), ("ACTIVE", 1, hero_id), ("ACTIVE", 1, npc_id),
                           ("ACTIVE", 2, hero_id), ("ENDED", 2, hero_id), ("DRAFT", 0, None)]
        prior = None
        for revision, ((receipt, request, outcome), (status, round_number, cursor)) in enumerate(zip(history, expected_states), 1):
            require(receipt["fingerprint"] == sha(compact(["genesis-authority-v1", "encounter-v1", receipt["requestJson"]])),
                    "Encounter receipt fingerprint differs from its immutable request")
            require(request["expectedRevision"] == (revision - 1 if revision > 1 else None) and outcome["before"] == prior,
                    "Encounter history is missing, branched or changed its prior snapshot")
            after = outcome["after"]
            expected_record = {"id": encounter_id, "campaignId": campaign["id"], "title": ENCOUNTER, "notes": NOTES,
                               "status": status, "round": round_number, "currentActorId": cursor, "revision": revision,
                               "creationCommandId": history[0][0]["commandId"], "lastCommandId": receipt["commandId"],
                               "createdAt": history[0][0]["createdAt"], "updatedAt": receipt["createdAt"]}
            require(after == {"record": expected_record, "participants": ordered}, "Encounter outcome differs from exact manual turn progression")
            require(prior is None or receipt["createdAt"] > prior["record"]["updatedAt"], "Encounter timestamps did not advance")
            if revision in (1, 2):
                members = outcome["members"]
                require([member["actorId"] for member in members] == [hero_id, npc_id], "Membership proof order differs from participants")
                for member in members:
                    authority = actors[member["actorId"]]
                    require(member == {"actorId": authority["actorId"], "authorityRevision": authority["revision"],
                                       "role": authority["role"], "campaignId": campaign["id"], "membershipCommandId": authority["lastCommandId"]},
                            "Encounter participant membership is not explicitly bound")
                    decision = receipts[member["membershipCommandId"]]
                    require(decision["fingerprint"] == sha(compact(["genesis-authority-v1", decision["kind"], decision["requestJson"]])),
                            "Original participant membership receipt fingerprint changed")
                    require(decision["kind"] in {"create-actor", "register-actor", "accept-actor", "update-association"}, "Invalid membership decision kind")
                    proof = decode(decision["outcomeJson"])
                    if decision["kind"] != "update-association":
                        proof = proof["actor"]
                    require(all(proof[key] == authority[key] for key in ("actorId", "revision", "role", "campaignId", "lastCommandId")),
                            "Encounter membership differs from its immutable association decision")
                if revision == 1:
                    expected_inputs = [{"actorId": actor_id, "expectedAuthorityRevision": actors[actor_id]["revision"], "initiative": initiative}
                                       for actor_id, initiative in ((hero_id, 7), (npc_id, 19))]
                    require(request["startReview"] is None and request["draft"] == {"campaignId": campaign["id"], "title": ENCOUNTER,
                            "notes": NOTES, "participants": expected_inputs}, "Setup receipt changed its explicit participant input")
                else:
                    review = request["startReview"]
                    frozen = copy.deepcopy(review); frozen["fingerprint"] = ""
                    require(request["draft"] is None and review["snapshot"] == prior and review["members"] == members and
                            review["fingerprint"] == sha(compact(frozen)), "Start was not bound to the reviewed setup and membership")
            else:
                require(outcome["members"] == [] and request["draft"] is None and request["startReview"] is None,
                        "A turn/history action unexpectedly rewrote roster membership")
            prior = after
        require(prior == {"record": record, "participants": participants}, "Current tracker differs from its final immutable receipt")
        # Compare raw values and identities in every table. Existing rows may not
        # change; only the new NPC's two source records and their audits, plus this
        # encounter and its immutable commands, may be appended.
        allowed_new = {"character_profiles", "connection_entities", "genesis_actor_authority", "campaign_audit",
                       "command_receipts", "genesis_authority_receipts", "genesis_encounters", "genesis_encounter_participants"}
        appended = {}
        for table in snapshots[0]["tables"]:
            if table not in allowed_new:
                require(snapshots[0]["tables"][table] == snapshots[1]["tables"][table],
                        f"Existing {table} values or history changed")
                continue
            keys = [row[1] for row in sorted(before.execute(f'PRAGMA table_info("{table}")'), key=lambda row: row[5]) if row[5]]
            require(keys, "Table has no stable primary key")
            old = {tuple(row[key] for key in keys): row for row in rows(table, before)}
            new = {tuple(row[key] for key in keys): row for row in rows(table)}
            require(all(new.get(key) == value for key, value in old.items()), f"Existing {table} values or history changed")
            additions = [new[key] for key in new.keys() - old.keys()]
            require(not additions or table in allowed_new, f"Encounter unexpectedly appended mechanical or unrelated {table} records")
            appended[table] = additions
        require(appended["character_profiles"] == [npc] and appended["connection_entities"] == [entity] and
                appended["genesis_actor_authority"] == [npc_actor], "Encounter created another actor or source identity")
        require(appended["genesis_encounters"] == [record] and sorted(appended["genesis_encounter_participants"], key=lambda row: row["position"]) == ordered,
                "Encounter duplicated a tracker or participant")
        require({row["commandId"] for row in appended["genesis_authority_receipts"]} == {creation["commandId"]} | {row[0]["commandId"] for row in history},
                "Encounter added an unexpected authority operation or duplicate turn")
        for table in ("campaign_audit", "command_receipts"):
            require(len(appended[table]) == 2 and all(row["recordId"] == npc_id for row in appended[table]),
                    "Only the explicit new NPC source profile and connection may append source history")
        for suffix, record_type, source_record in (("identity", "character-profile", npc), ("npc", "connection", entity)):
            child_id = "genesis:" + sha(creation["commandId"] + ":" + suffix)
            audit = one((row for row in appended["campaign_audit"] if row["commandId"] == child_id), "NPC source creation audit")
            receipt = one((row for row in appended["command_receipts"] if row["commandId"] == child_id), "NPC source creation receipt")
            require(audit["id"] == child_id and audit["recordType"] == record_type and audit["kind"] == "CREATE" and
                    audit["priorRevision"] == 0 and audit["resultingRevision"] == 1 and audit["beforeJson"] is None and
                    decode(audit["afterJson"]) == source_record and receipt["kind"] == record_type and
                    receipt["resultingRevision"] == 1 and receipt["createdAt"] == audit["createdAt"] and
                    decode(receipt["outcomeJson"]) == source_record,
                    "NPC source history does not prove its exact original records")
    return {"status": "PASS", "room_schema": 9, "scope": "fictional emulator manual encounter and independent immutable-history verification",
            "campaign_id": campaign["id"], "hero_id": hero_id, "source_npc_id": npc_id, "encounter_id": encounter_id,
            "operations": OPERATIONS, "final_revision": 6, "final_status": "DRAFT", "recorded_round_two_preserved_in_history": True,
            "manual_order_not_sorted_by_initiative": True, "all_prior_records_and_mechanics_unchanged": True,
            "source_only_unknowns_preserved": True, "physical_device_acceptance": False}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--before-database", required=True, type=Path)
    parser.add_argument("--database", required=True, type=Path)
    parser.add_argument("--synthetic-emulator", action="store_true")
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    result = inspect(args.before_database, args.database, synthetic_emulator=args.synthetic_emulator)
    args.output.write_text(json.dumps(result, indent=2) + "\n")
    print(json.dumps(result))


if __name__ == "__main__":
    main()
