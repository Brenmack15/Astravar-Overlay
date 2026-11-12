#!/usr/bin/env python3
"""Independently verify one fictional native craft from copied, closed emulator databases.

This does not trust UI labels or repository return values. It checks the frozen
request, immutable children, canonical inventory and source identity, preserving
the preceding connected journey's records. It is not physical-device evidence.
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
MATERIAL = "Genesis Native Craft Material"
PROJECT = "Genesis Native Craft Design"
REQUIREMENT = "Genesis Native Craft Requirement"
OUTPUT = "Genesis Native Crafted Token"
SOURCE = "Fictional emulator resolved craft"
DESCRIPTION = "Fictional plain token with no mechanical effects"


def child(parent, label):
    return "genesis:" + sha(parent + ":" + label)


def saved(row):
    return {"character": decode(row["characterJson"]), "revision": row["revision"],
            "origin": decode(row["originJson"]), "createdAt": row["createdAt"], "updatedAt": row["updatedAt"],
            "saveState": "Saved on this device · not synchronized"}


def inspect(before_database, database, *, synthetic_emulator=False):
    require(synthetic_emulator, "Only copied fictional emulator records are supported")
    before_database, database = Path(before_database), Path(database)
    require(before_database.is_file() and database.is_file(), "Both closed database snapshots must exist")
    for path in (before_database, database):
        COMMON["UPGRADE"]["database_snapshot"](path, "CampaignLedgerDb", 8)
    with connect(before_database) as before, connect(database) as db:
        require(not list(db.execute("PRAGMA foreign_key_check")), "A crafting relationship is orphaned")
        def rows(table, connection=db):
            return [dict(row) for row in connection.execute(f'SELECT * FROM "{table}"')]
        def exact(table, key, value):
            return one((row for row in rows(table) if row[key] == value), f"{table} {value}")
        hero = one((row for row in rows("character_profiles") if row["name"] == HERO), "fictional hero")
        actor_id = hero["id"]
        actor = exact("genesis_actor_authority", "actorId", actor_id)
        campaign = exact("genesis_campaigns", "id", actor["campaignId"])
        require(actor["playable"] and actor["role"] == "PLAYER" and campaign["name"] == COMMON["CAMPAIGN"],
                "Craft owner is not the connected fictional campaign hero")
        base = one((row for row in rows("reforge_characters", before) if row["profileId"] == actor_id), "prior hero")
        current = exact("reforge_characters", "profileId", actor_id)
        canonical = decode(current["characterJson"])
        require(current["revision"] == canonical["revision"] == actor["playableRevision"] == base["revision"] + 2,
                "Creating the material and crafting must each advance the actor once")
        require(actor["projectionFingerprint"] == sha(current["characterJson"]), "Canonical projection fingerprint changed")

        def physical(title, quantity):
            ledger = one((row for row in rows("ledger_items") if row["title"] == title), title)
            authority = exact("genesis_item_authority", "itemId", ledger["id"])
            instance = one((row for row in canonical["items"] if row["id"] == ledger["id"]), f"canonical {title}")
            definition = one((row for row in canonical["itemDefinitions"] if row["id"] == instance["definitionId"]), f"definition {title}")
            placement = exact("item_placements", "itemId", ledger["id"])
            require(ledger["quantity"] == instance["quantity"] == quantity and ledger["owner"] == HERO and
                    ledger["itemState"] == "Carried" and not ledger["archived"] and instance["state"] == "CARRIED" and
                    not instance["attuned"] and instance["containerId"] is None, f"Wrong quantity, owner or state for {title}")
            require(authority["ownerActorId"] == actor_id and decode(authority["instanceJson"]) == instance and
                    decode(authority["definitionJson"]) == definition and definition["name"] == title and
                    definition["effects"] == [], f"Canonical and authority identities differ for {title}")
            require(placement["holderProfileId"] == actor_id and placement["containerItemId"] is None and
                    placement["installedProjectId"] is None and placement["state"] == "Carried", f"Source placement differs for {title}")
            return ledger, authority, instance, definition
        material, material_authority, material_instance, material_definition = physical(MATERIAL, 2)
        output, output_authority, output_instance, output_definition = physical(OUTPUT, 1)
        require(output_definition["description"] == output["description"] == DESCRIPTION, "Output description did not propagate")
        untouched = copy.deepcopy(canonical)
        untouched["revision"] = base["revision"]
        untouched["items"] = [row for row in untouched["items"] if row["id"] not in (material["id"], output["id"])]
        untouched["itemDefinitions"] = [row for row in untouched["itemDefinitions"] if row["id"] not in
                                        (material_definition["id"], output_definition["id"])]
        require(untouched == decode(base["characterJson"]), "Craft changed unrelated character mechanics or existing items")

        project = one((row for row in rows("invention_projects") if row["title"] == PROJECT), "fictional invention")
        requirement = one((row for row in rows("material_requirements") if row["projectId"] == project["id"]), "linked requirement")
        require(requirement["label"] == REQUIREMENT and requirement["itemId"] == material["id"] and requirement["quantity"] == 1,
                "The proposed requirement lost its actual material identity")
        receipt = one((row for row in rows("genesis_authority_receipts") if row["kind"] == "craft-items" and
                       decode(row["requestJson"])["project"]["id"] == project["id"]), "single authority craft receipt")
        parent = receipt["commandId"]
        review, outcome = decode(receipt["requestJson"]), decode(receipt["outcomeJson"])
        require(receipt["fingerprint"] == sha(compact(["genesis-authority-v1", "craft-items", receipt["requestJson"]])),
                "Craft receipt fingerprint is not bound to the exact request")
        frozen = copy.deepcopy(review); frozen["fingerprint"] = ""
        require(review["fingerprint"] == sha(compact(frozen)), "Frozen craft review fingerprint changed")
        require(review["source"] == SOURCE and review["outputOwnerActorId"] == actor_id and review["outputContainerItemId"] is None,
                "The craft did not retain the explicitly reviewed output owner and source")
        use = one(review["materials"], "reviewed material")
        require(use["use"] == {"itemId": material["id"], "expectedRevision": use["item"]["revision"], "quantity": 1} and
                use["item"]["quantity"] == 3 and use["authority"]["ownerActorId"] == actor_id,
                "Frozen material review does not prove three available and one consumed")
        require(material_authority["activationCommandId"] == use["authority"]["activationCommandId"] and
                material_authority["sourceProofJson"] == use["authority"]["sourceProofJson"], "Craft rewrote the material's original provenance")
        activation = exact("genesis_authority_receipts", "commandId", material_authority["activationCommandId"])
        require(activation["kind"] == "create-items", "Material was not created through connected inventory")
        craft = child(parent, "craft")
        legacy = exact("command_receipts", "commandId", craft)
        command = {"projectId": project["id"], "expectedProjectRevision": review["project"]["revision"],
                   "materials": [use["use"]], "output": review["output"], "source": SOURCE, "commandId": ""}
        require(legacy == outcome["craftReceipt"] and legacy["kind"] == "CRAFT" and legacy["recordId"] == project["id"] and
                legacy["outputId"] == outcome["outputId"] == output["id"] and legacy["fingerprint"] == sha(compact(command)),
                "Legacy craft receipt differs from the reviewed command or output")
        require(project == decode(legacy["outcomeJson"]) and project["status"] == "Completed" and
                project["revision"] == review["project"]["revision"] + 1 == legacy["resultingRevision"], "Project completion differs from its receipt")
        execution = one((row for row in rows("craft_executions") if row["projectId"] == project["id"]), "single craft execution")
        require(execution == outcome["execution"] and execution["commandId"] == craft and
                execution["outputItemId"] == output["id"] and execution["source"] == SOURCE, "Craft execution points at another output")
        consumption = one((row for row in rows("craft_consumptions") if row["executionId"] == craft), "single immutable consumption")
        expected_consumption = {"id": craft + ":0", "executionId": craft, "itemId": material["id"], "quantity": 1,
                                "beforeQuantity": 3, "afterQuantity": 2, "priorRevision": use["item"]["revision"],
                                "resultingRevision": use["item"]["revision"] + 1}
        require(consumption == expected_consumption and outcome["consumptions"] == [consumption] and
                material["revision"] == consumption["resultingRevision"], "Immutable consumption does not prove exactly three minus one")
        event = exact("ledger_events", "commandId", "craft-" + sha(craft)[:40] + "-0")
        require(event["kind"] == "CRAFT_CONSUMPTION" and event["itemId"] == material["id"] and
                event["payloadFingerprint"] == legacy["fingerprint"] and decode(event["beforeJson"]) == use["item"] and
                decode(event["afterJson"]) == material, "Immutable material event differs from source quantity readback")
        audit = exact("campaign_audit", "id", craft + ":material:0")
        require(audit["commandId"] == craft and audit["kind"] == "CRAFT_CONSUMPTION" and
                audit["beforeJson"] == event["beforeJson"] and audit["afterJson"] == event["afterJson"], "Material audit lost its immutable event")
        project_audit = exact("campaign_audit", "id", craft)
        require(project_audit["kind"] == "CONFIRMED_CRAFT" and decode(project_audit["beforeJson"]) == review["project"] and
                decode(project_audit["afterJson"]) == project, "Project audit changed its frozen before/after state")
        output_event = exact("ledger_events", "commandId", "craft-output-" + sha(craft)[:40])
        require(output_event["kind"] == "CREATE" and output_event["itemId"] == output["id"] and output_event["beforeJson"] is None and
                decode(output_event["afterJson"]) == outcome["sourceOutput"] and outcome["sourceOutput"]["revision"] == 1,
                "Output lost its original physical creation identity")
        # The legacy craft creates revision 1. Adding the connected placement is
        # a separately audited projection, even when owner/location already agree.
        projection_id = child(child(parent, "craft-project:" + actor_id), "item-project:" + output["id"])
        projection = exact("ledger_events", "commandId", projection_id)
        require(projection["kind"] == "UPDATE" and projection["itemId"] == output["id"] and
                projection["priorRevision"] == 1 and projection["resultingRevision"] == output["revision"] == 2 and
                decode(projection["beforeJson"]) == outcome["sourceOutput"] and decode(projection["afterJson"]) == output,
                "Connected output placement lost its exact projection event")
        projected = dict(outcome["sourceOutput"], revision=2, source="Genesis connected authority v1", updatedAt=output["updatedAt"])
        require(output == projected and output["updatedAt"] > outcome["sourceOutput"]["updatedAt"],
                "Output projection changed original craft content")
        projection_receipt = exact("command_receipts", "commandId", projection_id)
        require(projection_receipt["recordId"] == output["id"] and projection_receipt["resultingRevision"] == 2 and
                projection_receipt["outcomeJson"] == projection["afterJson"], "Output placement projection lost its receipt")
        link = one((row for row in rows("campaign_links") if row["fromType"] == "project" and
                    row["fromId"] == project["id"] and row["label"] == "Confirmed craft output"), "single output relationship")
        require(link == outcome["outputLink"] and link["toType"] == "item" and link["toId"] == output["id"], "Campaign output relationship differs from inventory")
        link_receipt = exact("command_receipts", "commandId", "craft-link-" + sha(craft)[:40])
        require(link_receipt["recordId"] == link["id"] and decode(link_receipt["outcomeJson"]) == link, "Output relationship lost its immutable receipt")
        proof = decode(output_authority["sourceProofJson"])
        require(output_authority["activationCommandId"] == parent and proof == {
            "format": "astravar-crafted-item-v1", "parentCommandId": parent, "craftCommandId": craft,
            "outputId": output["id"], "projectId": project["id"], "reviewFingerprint": review["fingerprint"],
            "consumptions": [consumption], "outputLinkId": link["id"]}, "Output authority lost exact craft provenance")
        frozen_actor = one(review["actors"], "affected actor")
        final_actor = one(outcome["actors"], "saved actor")
        change = exact("reforge_changes", "commandId", child(parent, "craft-playable:" + actor_id))
        require(frozen_actor["authority"]["actorId"] == actor_id and frozen_actor["authority"]["projectionFingerprint"] ==
                sha(compact(frozen_actor["playable"]["character"])) and
                change["profileId"] == actor_id and change["outcomeType"] == "CHARACTER" and
                change["priorRevision"] == frozen_actor["playable"]["revision"] == base["revision"] + 1 and
                change["resultingRevision"] == current["revision"] and decode(change["beforeJson"]) == frozen_actor["playable"] and
                decode(change["afterJson"]) == final_actor == saved(current), "Canonical craft delta lost its immutable actor before/after proof")
        expected = copy.deepcopy(frozen_actor["playable"]["character"])
        expected["revision"] += 1
        previous_material = one((row for row in expected["items"] if row["id"] == material["id"]), "frozen canonical material")
        require(previous_material["quantity"] == 3, "Frozen canonical material did not start at three")
        previous_material["quantity"] = 2
        expected["items"].append(output_instance); expected["itemDefinitions"].append(output_definition)
        require(expected == canonical, "Craft did more than consume one material and add one plain output")
        # All earlier physical records, receipts and immutable history survive the
        # new journey byte-for-byte. Only the selected hero's inventory advances.
        for table, key in (("ledger_items", "id"), ("item_placements", "itemId"),
                           ("genesis_item_authority", "itemId"), ("genesis_campaigns", "id"),
                           ("genesis_authority_receipts", "commandId"), ("command_receipts", "commandId"),
                           ("ledger_events", "commandId"), ("campaign_audit", "id"), ("reforge_changes", "commandId"),
                           ("reforge_characters", "profileId"), ("genesis_actor_authority", "actorId"), ("character_profiles", "id")):
            after_rows = {row[key]: row for row in rows(table)}
            for row in rows(table, before):
                if table in {"reforge_characters", "genesis_actor_authority", "character_profiles"} and row[key] == actor_id:
                    continue
                require(after_rows.get(row[key]) == row, f"Existing {table} record changed during fictional crafting")
    return {"status": "PASS", "scope": "fictional emulator crafting with independent source, canonical and immutable receipt readback",
            "hero_id": actor_id, "project_id": project["id"], "material_id": material["id"], "material_quantity": 2,
            "output_id": output["id"], "output_quantity": 1, "authority_command_id": parent, "craft_command_id": craft,
            "one_execution": True, "prior_records_preserved": True, "physical_device_acceptance": False}


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
