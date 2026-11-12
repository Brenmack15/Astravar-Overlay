#!/usr/bin/env python3
"""Read-only proof of the connected native journey's fictional records and SAF file.

This validates copied emulator data independently of Android repository callbacks.
The journey merges its own complete export; it does not simulate deleted-record
recovery or certify any real campaign or physical phone.
"""
import argparse
import copy
import hashlib
import json
from pathlib import Path
import runpy

UPGRADE = runpy.run_path(str(Path(__file__).with_name("genesis-upgrade-readback.py")))
require = UPGRADE["require"]
connect = UPGRADE["connect"]
CAMPAIGN = "Genesis Native Campaign"
HERO = "Genesis Native Hero"
NPC = "Genesis Native Contact"
COMPANION = "Genesis Native Familiar"
ITEM = "Genesis Native Compass Revised"
DOCUMENT_LIMIT = 64 * 1024 * 1024


def unique_object(pairs):
    result = {}
    for key, value in pairs:
        require(key not in result, f"Duplicate JSON member: {key}")
        result[key] = value
    return result


def decode(text):
    return json.loads(text, object_pairs_hook=unique_object,
                      parse_constant=lambda value: require(False, f"Non-finite JSON number: {value}"))


def sha(text):
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


def compact(value, ascii_only=False):
    encoded = json.dumps(value, separators=(",", ":"), ensure_ascii=False, allow_nan=False)
    if not ascii_only:
        return encoded
    # Match CampaignStreamCodec's UTF-16 escaping, including raw ASCII DEL.
    # Python ensure_ascii=True also escapes DEL, which the Android writer retains.
    def ascii_char(char):
        if ord(char) <= 0x7f:
            return char
        utf16 = char.encode("utf-16-be", errors="surrogatepass")
        return "".join("\\u" + utf16[offset:offset + 2].hex() for offset in range(0, len(utf16), 2))
    return "".join(map(ascii_char, encoded))


def index(values, key):
    result = {}
    for row in values:
        require(isinstance(row, dict) and isinstance(row.get(key), str) and row[key], f"Missing {key}")
        require(row[key] not in result, f"Duplicate {key}: {row[key]}")
        result[row[key]] = row
    return result


def one(values, label):
    values = list(values)
    require(len(values) == 1, f"Expected one {label}, got {len(values)}")
    return values[0]


def backup_fingerprint(backup):
    canonical = copy.deepcopy(backup)
    for domain in (canonical, canonical["campaign"], canonical["playable"]):
        domain["exportedAt"] = 0
    return sha(compact(canonical, ascii_only=True))


def inspect(database, backup_document, *, synthetic_emulator=False):
    require(synthetic_emulator, "Only copied fictional emulator records are supported")
    database, backup_document = Path(database), Path(backup_document)
    require(database.is_file() and backup_document.is_file(), "Database and actual SAF backup must exist")
    require(0 < backup_document.stat().st_size <= DOCUMENT_LIMIT, "Backup exceeds the 64 MiB supported size")
    raw = backup_document.read_bytes()
    backup = decode(raw.decode("utf-8", errors="strict"))
    require(backup.get("format") == "astravar-connected-campaign" and backup.get("schema") == 1,
            "Expected a complete connected backup, not a partial export")
    require({"campaign", "playable", "authority", "exportedAt"} <= backup.keys(), "A backup domain is missing")
    schema = UPGRADE["database_snapshot"](database, "CampaignLedgerDb", 8)
    authority = backup["authority"]
    with connect(database) as db:
        require(not list(db.execute("PRAGMA foreign_key_check")), "A database relationship is orphaned")
        def rows(table):
            return [dict(row) for row in db.execute(f'SELECT * FROM "{table}"')]
        def equal_backup(table, values, key):
            saved = index(rows(table), key)
            exported = index(values, key)
            require(saved == exported, f"Backup differs from current {table} identities or exact rows")
            return saved
        campaigns = equal_backup("genesis_campaigns", authority["campaigns"], "id")
        actors = equal_backup("genesis_actor_authority", authority["actors"], "actorId")
        items = equal_backup("genesis_item_authority", authority["items"], "itemId")
        receipts = equal_backup("genesis_authority_receipts", authority["receipts"], "commandId")
        playable = equal_backup("reforge_characters", backup["playable"]["characters"], "profileId")
        profiles = index(rows("character_profiles"), "id")
        exported_profiles = index(backup["campaign"]["records"]["characterProfiles"], "id")
        for receipt in receipts.values():
            expected = sha(compact(["genesis-authority-v1", receipt["kind"], receipt["requestJson"]]))
            require(receipt["fingerprint"] == expected, "Authority receipt is not bound to its exact request")
            decode(receipt["requestJson"]); decode(receipt["outcomeJson"])
        campaign = one((row for row in campaigns.values() if row["name"] == CAMPAIGN), "fictional campaign")
        require(not campaign["archived"], "The selected fictional campaign was archived")
        created = receipts.get(campaign["commandId"])
        require(created is not None and created["kind"] == "create-campaign" and decode(created["outcomeJson"]) == campaign,
                "Campaign creation outcome is missing or changed")

        def actor(name, role, is_playable, owner=None):
            profile = one((row for row in profiles.values() if row["name"] == name), name)
            identity = profile["id"]
            require(exported_profiles.get(identity) == profile, f"Backup does not preserve {name}'s source profile")
            record = actors.get(identity)
            require(record is not None and record["role"] == role and bool(record["playable"]) == is_playable and
                    record["campaignId"] == campaign["id"] and record["ownerActorId"] == owner,
                    f"Wrong role, campaign, owner or playable status for {name}")
            activation = receipts.get(record["activationCommandId"])
            require(activation is not None and activation["kind"] in {"create-actor", "accept-actor", "register-actor"},
                    f"Missing explicit activation for {name}")
            require(decode(activation["outcomeJson"])["actor"]["actorId"] == identity, "Activation targets another identity")
            if not is_playable:
                require(identity not in playable, f"Source-only {name} acquired an invented playable sheet")
                require(all(profile[key] is None for key in ("currentHp", "maximumHp", "temporaryHp", "armorClass", "level")),
                        f"Unknown source-only statistics were invented for {name}")
            return profile, record

        hero_profile, hero = actor(HERO, "PLAYER", True)
        npc_profile, npc = actor(NPC, "NPC", False)
        companion_profile, companion = actor(COMPANION, "COMPANION", False, hero["actorId"])
        npc_entity = one((row for row in rows("connection_entities") if row["id"] == npc["actorId"]), "same-ID NPC connection")
        require(npc_entity["name"] == NPC, "NPC connection name differs from campaign membership")
        companion_state = one((row for row in rows("companion_states") if row["companionId"] == companion["actorId"]), "same-ID companion")
        require(companion_state["name"] == COMPANION, "Companion identity differs from its campaign member")
        owner_link = one((row for row in rows("campaign_links") if row["fromType"] == "companion" and
             row["fromId"] == companion["actorId"] and row["toType"] == "character-profile" and
             row["toId"] == hero["actorId"]), "explicit companion owner relationship")
        for domain, key, row in (("entities", "id", npc_entity), ("companionStates", "companionId", companion_state),
                                 ("links", "id", owner_link)):
            exported = index(backup["campaign"]["records"][domain], key)
            require(exported.get(row[key]) == row, f"Backup lost exact source {domain} relationships")
        saved = playable[hero["actorId"]]
        character = decode(saved["characterJson"])
        require(character["id"] == hero["actorId"] and character["name"] == HERO and
                character["revision"] == saved["revision"] == hero["playableRevision"], "Playable identity/revision mismatch")
        require(hero["projectionFingerprint"] == sha(saved["characterJson"]), "Playable projection digest differs")
        require(character["currentHp"] == hero_profile["currentHp"] == 14 and
                character["baseMaxHp"] == hero_profile["maximumHp"] == 20 and
                character["baseAc"] == hero_profile["armorClass"] == 13,
                "Shared health or armor did not propagate to campaign records")
        definition = one((row for row in character["itemDefinitions"] if row["name"] == ITEM), "edited item definition")
        require(all(row["name"] != "Genesis Native Compass" for row in character["itemDefinitions"]), "An obsolete duplicate definition remains")
        instance = one((row for row in character["items"] if row["definitionId"] == definition["id"]), "physical compass")
        require(instance["quantity"] == 1 and instance["state"] == "CARRIED" and not instance["attuned"], "Unexpected compass mechanics")
        item = items.get(instance["id"])
        require(item is not None and item["ownerActorId"] == hero["actorId"] and
                decode(item["definitionJson"]) == definition and decode(item["instanceJson"]) == instance,
                "Physical item authority differs from playable inventory")
        ledger = one((row for row in rows("ledger_items") if row["title"] == ITEM), "source item")
        require(ledger["id"] == instance["id"] and ledger["owner"] == HERO and ledger["quantity"] == 1 and
                ledger["itemState"] == "Carried" and not ledger["archived"], "Source item identity, ownership or quantity differs")
        placement = one((row for row in rows("item_placements") if row["itemId"] == instance["id"]), "source placement")
        require(placement["holderProfileId"] == hero["actorId"] and placement["containerItemId"] is None and
                placement["state"] == "Carried", "Source placement differs from playable ownership")
        exported_item = one((row for row in backup["campaign"]["items"] if row["id"] == instance["id"]), "backup item")
        exported_placement = one((row for row in backup["campaign"]["records"]["placements"] if row["itemId"] == instance["id"]), "backup placement")
        require(exported_item == ledger and exported_placement == placement, "Backup lost physical item or placement data")
        fingerprint = backup_fingerprint(backup)
        restore = one((row for row in rows("command_receipts") if row["kind"] == "RESTORE" and row["recordId"] == fingerprint), "request-bound complete merge receipt")
        require(restore["fingerprint"] == sha("GENESIS_RESTORE:v1:" + fingerprint), "Merge receipt belongs to another backup")
        audit = one((row for row in rows("campaign_audit") if row["id"] == restore["commandId"]), "immutable merge audit")
        require(audit["recordId"] == fingerprint and audit["kind"] == "RESTORE" and
                audit["source"] == "User-reviewed complete connected backup merge", "Merge audit does not prove reviewed complete restore")
    return {"status": "PASS", "scope": "fictional emulator connected journey; same-file protected merge",
            "room_schema": 8, "room_identity": schema["identity_hash"], "backup_bytes": len(raw),
            "backup_sha256": hashlib.sha256(raw).hexdigest(), "backup_fingerprint": fingerprint,
            "campaign_id": campaign["id"], "hero_id": hero["actorId"], "npc_id": npc["actorId"],
            "companion_id": companion["actorId"], "item_id": instance["id"], "definition_id": definition["id"],
            "restore_command_id": restore["commandId"], "source_only_unknowns_preserved": True,
            "physical_device_acceptance": False}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--database", required=True, type=Path)
    parser.add_argument("--backup-document", required=True, type=Path)
    parser.add_argument("--synthetic-emulator", action="store_true")
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    result = inspect(args.database, args.backup_document, synthetic_emulator=args.synthetic_emulator)
    args.output.write_text(json.dumps(result, indent=2) + "\n")
    print(json.dumps(result))


if __name__ == "__main__":
    main()
