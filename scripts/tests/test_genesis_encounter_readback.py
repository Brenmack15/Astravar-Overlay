"""Reconstructed equivalent of the unavailable local991 suite; not original-blob verified.

These synthetic host checks must be executed before this recovery is accepted.
They do not constitute native or physical-device evidence.
"""
import copy
import hashlib
import json
from pathlib import Path
import runpy
import shutil
import sqlite3
import unittest

ROOT = Path(__file__).resolve().parents[2]
HELPER = runpy.run_path(str(ROOT / "scripts/genesis-encounter-readback.py"))
CONNECTED = runpy.run_path(str(ROOT / "scripts/tests/test_genesis_connected_readback.py"))
UPGRADE = CONNECTED["FIXTURE"]
compact, sha = HELPER["compact"], HELPER["sha"]


def promote_to_nine(path):
    """Use only actual Room-generated definitions, never substitute encounter DDL."""
    with sqlite3.connect(path) as db:
        UPGRADE["create_entities"](db, UPGRADE["schema"]("CampaignLedgerDb", 9))


class GenesisEncounterReadbackTest(unittest.TestCase):
    """Host verifier fixtures only, with the same pre-existing connected identities."""

    def setUp(self):
        self.fixture = CONNECTED["GenesisConnectedReadbackTest"]()
        self.fixture.setUp()
        self.before = self.fixture.database
        promote_to_nine(self.before)
        self.after = self.fixture.root / "encounter.db"
        shutil.copyfile(self.before, self.after)
        with sqlite3.connect(self.after) as db:
            db.row_factory = sqlite3.Row

            def add(table, **values):
                UPGRADE["insert"](db, table, **values)

            def row(table, key, value):
                return dict(db.execute(f'SELECT * FROM "{table}" WHERE "{key}"=?', (value,)).fetchone())

            def receipt(command, kind, request, outcome, time):
                request_json = compact(request)
                add("genesis_authority_receipts", commandId=command, kind=kind, requestJson=request_json,
                    fingerprint=sha(compact(["genesis-authority-v1", kind, request_json])),
                    outcomeJson=compact(outcome), createdAt=time)

            add("character_profiles", id="witness", name=HELPER["NPC"], currentHp=None, maximumHp=None,
                temporaryHp=None, armorClass=None, level=None, revision=1,
                source="Fictional explicit creation", updatedAt=10)
            add("connection_entities", id="witness", name=HELPER["NPC"], kind="Person", revision=1)
            add("genesis_actor_authority", actorId="witness", role="NPC", campaignId="campaign", ownerActorId=None,
                playable=0, revision=1, activationCommandId="witness-create", lastCommandId="witness-create",
                playableRevision=None, projectionFingerprint=None, resourceBindingsJson="{}", createdAt=10, updatedAt=10)
            actors = [row("genesis_actor_authority", "actorId", actor) for actor in ("hero", "witness")]
            receipt("witness-create", "create-actor", [HELPER["NPC"], "NPC", "campaign", None, None],
                    {"actor": actors[1], "profile": row("character_profiles", "id", "witness"), "playable": None}, 10)
            for suffix, kind, table in (("identity", "character-profile", "character_profiles"),
                                        ("npc", "connection", "connection_entities")):
                child_id = "genesis:" + sha("witness-create:" + suffix)
                after = compact(row(table, "id", "witness"))
                add("command_receipts", commandId=child_id, kind=kind, recordId="witness",
                    resultingRevision=1, createdAt=10, outcomeJson=after)
                add("campaign_audit", id=child_id, commandId=child_id, recordType=kind, recordId="witness",
                    kind="CREATE", priorRevision=0, resultingRevision=1, createdAt=10, beforeJson=None, afterJson=after)
            participants = [{"encounterId": "encounter", "actorId": "hero", "initiative": 7, "position": 0},
                            {"encounterId": "encounter", "actorId": "witness", "initiative": 19, "position": 1}]
            members = [{"actorId": actor["actorId"], "authorityRevision": actor["revision"], "role": actor["role"],
                        "campaignId": "campaign", "membershipCommandId": actor["lastCommandId"]} for actor in actors]
            states = [("DRAFT", 0, None), ("ACTIVE", 1, "hero"), ("ACTIVE", 1, "witness"),
                      ("ACTIVE", 2, "hero"), ("ENDED", 2, "hero"), ("DRAFT", 0, None)]
            previous = None
            for revision, (operation, (status, round_number, actor_id)) in enumerate(zip(HELPER["OPERATIONS"], states), 1):
                record = {"id": "encounter", "campaignId": "campaign", "title": HELPER["ENCOUNTER"], "notes": HELPER["NOTES"],
                          "status": status, "round": round_number, "currentActorId": actor_id, "revision": revision,
                          "creationCommandId": "encounter-1", "lastCommandId": f"encounter-{revision}",
                          "createdAt": 11, "updatedAt": 10 + revision}
                current = {"record": record, "participants": participants}
                draft, start = None, None
                if revision == 1:
                    draft = {"campaignId": "campaign", "title": HELPER["ENCOUNTER"], "notes": HELPER["NOTES"], "participants": [
                        {"actorId": actor["actorId"], "expectedAuthorityRevision": actor["revision"], "initiative": value}
                        for actor, value in zip(actors, (7, 19))]}
                if revision == 2:
                    start = {"snapshot": previous, "members": members, "fingerprint": ""}
                    start["fingerprint"] = sha(compact(start))
                request = {"operation": operation, "encounterId": "encounter",
                           "expectedRevision": revision - 1 if revision > 1 else None, "draft": draft, "startReview": start}
                receipt(f"encounter-{revision}", "encounter-v1", request,
                        {"before": previous, "after": current, "members": members if revision <= 2 else []}, 10 + revision)
                previous = copy.deepcopy(current)
            add("genesis_encounters", **previous["record"])
            for participant in participants:
                add("genesis_encounter_participants", **participant)

    def tearDown(self):
        self.fixture.tearDown()

    def update(self, sql, parameters=()):
        with sqlite3.connect(self.after) as db:
            db.execute(sql, parameters)

    def inspect(self, **kwargs):
        return HELPER["inspect"](self.before, self.after, synthetic_emulator=kwargs.get("synthetic_emulator", True))

    def test_exact_manual_history_and_unchanged_stores_pass_without_writing(self):
        hashes = [hashlib.sha256(path.read_bytes()).hexdigest() for path in (self.before, self.after)]
        result = self.inspect()
        self.assertEqual("PASS", result["status"])
        self.assertEqual(6, result["final_revision"])
        self.assertTrue(result["all_prior_records_and_mechanics_unchanged"])
        self.assertFalse(result["physical_device_acceptance"])
        self.assertEqual(hashes, [hashlib.sha256(path.read_bytes()).hexdigest() for path in (self.before, self.after)])

    def test_requires_fictional_scope(self):
        with self.assertRaisesRegex(ValueError, "fictional emulator"):
            self.inspect(synthetic_emulator=False)

    def test_npc_source_creation_proof_must_match_original_identity(self):
        self.update("UPDATE campaign_audit SET afterJson='{}' WHERE recordId='witness'")
        with self.assertRaisesRegex(ValueError, "exact original records"):
            self.inspect()

    def test_npc_source_receipt_must_belong_to_parent_authority_command(self):
        self.update("UPDATE command_receipts SET commandId='unrelated-source-command' WHERE recordId='witness' AND kind='connection'")
        with self.assertRaisesRegex(ValueError, "NPC source creation receipt"):
            self.inspect()

    def test_sorting_by_initiative_instead_of_manual_order_fails(self):
        # Swap only positions while preserving both explicitly entered initiative values.
        # Use a temporary free position to respect the actual unique index.
        with sqlite3.connect(self.after) as db:
            db.execute("UPDATE genesis_encounter_participants SET position=2 WHERE actorId='hero'")
            db.execute("UPDATE genesis_encounter_participants SET position=0 WHERE actorId='witness'")
            db.execute("UPDATE genesis_encounter_participants SET position=1 WHERE actorId='hero'")
        with self.assertRaisesRegex(ValueError, "Manual participant order"):
            self.inspect()

    def test_extra_next_turn_is_rejected_even_if_final_row_was_reset(self):
        with sqlite3.connect(self.after) as db:
            db.row_factory = sqlite3.Row
            row = dict(db.execute("SELECT * FROM genesis_authority_receipts WHERE commandId='encounter-3'").fetchone())
            row["commandId"] = "unexpected-next"
            UPGRADE["insert"](db, "genesis_authority_receipts", **row)
        with self.assertRaisesRegex(ValueError, "Expected one setup"):
            self.inspect()

    def test_missing_ended_history_is_rejected_after_reopening(self):
        self.update("DELETE FROM genesis_authority_receipts WHERE commandId='encounter-5'")
        with self.assertRaisesRegex(ValueError, "Expected one setup"):
            self.inspect()

    def test_current_tracker_cannot_disagree_with_reopen_receipt(self):
        self.update("UPDATE genesis_encounters SET round=1")
        with self.assertRaisesRegex(ValueError, "final immutable receipt"):
            self.inspect()

    def test_source_only_npc_cannot_acquire_statistics(self):
        self.update("UPDATE character_profiles SET maximumHp=10 WHERE id='witness'")
        with self.assertRaisesRegex(ValueError, "invented mechanics"):
            self.inspect()

    def test_cross_campaign_participant_fails(self):
        self.update("UPDATE genesis_actor_authority SET campaignId=NULL WHERE actorId='witness'")
        with self.assertRaisesRegex(ValueError, "explicit fictional campaign"):
            self.inspect()

    def test_hero_hp_or_turn_state_change_fails(self):
        self.update("UPDATE reforge_characters SET characterJson=replace(characterJson, '\"currentHp\":14', '\"currentHp\":15') WHERE profileId='hero'")
        with self.assertRaisesRegex(ValueError, "Existing reforge_characters"):
            self.inspect()

    def test_resource_or_inventory_change_fails(self):
        self.update("UPDATE ledger_items SET quantity=2 WHERE id='compass'")
        with self.assertRaisesRegex(ValueError, "Existing ledger_items"):
            self.inspect()

    def test_appended_mechanical_reset_receipt_is_rejected(self):
        with sqlite3.connect(self.after) as db:
            UPGRADE["insert"](db, "reforge_changes", commandId="unapproved-turn-reset", profileId="hero",
                              operation="Reset turn", fingerprint="a" * 64, priorRevision=3,
                              resultingRevision=4, createdAt=18, beforeJson="{}", afterJson="{}", outcomeType="CHARACTER")
        with self.assertRaisesRegex(ValueError, "Existing reforge_changes"):
            self.inspect()

    def test_removed_prior_source_receipt_is_rejected(self):
        self.update("DELETE FROM command_receipts WHERE commandId='restore'")
        with self.assertRaisesRegex(ValueError, "Existing command_receipts"):
            self.inspect()

    def test_schema_six_to_nine_retains_raw_rows_and_separate_canonical_heal(self):
        fixture = UPGRADE["GenesisUpgradeReadbackTest"]()
        fixture.setUp()
        try:
            helper = UPGRADE["HELPER"]
            before = helper["inspect"](fixture.before_folder, phase="baseline", expected_room_schema=9)
            fixture.migrate()
            promote_to_nine(fixture.after_folder / "astravar-campaign-ledger.db")
            preserved = helper["inspect"](fixture.after_folder, phase="preserved", expected_room_schema=9)
            helper["verify"](before, preserved)
            fixture.write_healing()
            written = helper["inspect"](fixture.after_folder, phase="written", expected_room_schema=9)
            helper["verify_write"](preserved, written)
            with self.assertRaisesRegex(ValueError, "Wrong database version"):
                helper["inspect"](fixture.after_folder, phase="written", expected_room_schema=8)
        finally:
            fixture.tearDown()

    def test_schema_nine_connected_backup_allows_omitted_empty_encounter_fields(self):
        # The stream writer intentionally omits empty added lists. The original
        # document/receipt remain exact; only the empty additive schema changed.
        result = CONNECTED["HELPER"]["inspect"](
            self.before, self.fixture.document, synthetic_emulator=True, expected_room_schema=9)
        self.assertEqual("PASS", result["status"])
        self.assertEqual(9, result["room_schema"])
        with self.assertRaisesRegex(ValueError, "Wrong database version"):
            CONNECTED["HELPER"]["inspect"](self.before, self.fixture.document, synthetic_emulator=True)

    def test_schema_nine_crafting_uses_empty_encounter_snapshots_and_rejects_early_tracker(self):
        crafting = runpy.run_path(str(ROOT / "scripts/tests/test_genesis_crafting_readback.py"))
        fixture = crafting["GenesisCraftingReadbackTest"]()
        fixture.setUp()
        try:
            promote_to_nine(fixture.before)
            promote_to_nine(fixture.after)
            result = crafting["HELPER"]["inspect"](
                fixture.before, fixture.after, synthetic_emulator=True, expected_room_schema=9)
            self.assertEqual("PASS", result["status"])
            with self.assertRaisesRegex(ValueError, "Wrong database version"):
                crafting["HELPER"]["inspect"](fixture.before, fixture.after, synthetic_emulator=True)
            with sqlite3.connect(fixture.after) as db:
                UPGRADE["insert"](db, "genesis_encounters", id="premature-tracker", campaignId="campaign",
                                  title="Premature fictional tracker", notes="", status="DRAFT", round=0,
                                  currentActorId=None, revision=1, creationCommandId="premature-create",
                                  lastCommandId="premature-create", createdAt=20, updatedAt=20)
            with self.assertRaisesRegex(ValueError, "snapshots must precede encounter creation"):
                crafting["HELPER"]["inspect"](
                    fixture.before, fixture.after, synthetic_emulator=True, expected_room_schema=9)
        finally:
            fixture.tearDown()

    def test_import_verifier_requires_both_actual_schema_nine_table_shapes(self):
        helper = runpy.run_path(str(ROOT / "scripts/native-campaign-import-readback.py"))
        encounter_tables = {"genesis_encounters", "genesis_encounter_participants"}
        self.assertTrue(encounter_tables <= helper["storage_tables"](9))
        self.assertTrue(encounter_tables.isdisjoint(helper["storage_tables"](8)))
        for table in sorted(encounter_tables):
            with self.subTest(table=table):
                probe = self.fixture.root / (table + ".db")
                shutil.copyfile(self.before, probe)
                with sqlite3.connect(probe) as db:
                    helper["validate_storage_tables"](db, 9)
                    db.execute(f'DROP TABLE "{table}"')
                    with self.assertRaisesRegex(AssertionError, "Canonical storage table missing or malformed"):
                        helper["validate_storage_tables"](db, 9)


if __name__ == "__main__":
    unittest.main()
