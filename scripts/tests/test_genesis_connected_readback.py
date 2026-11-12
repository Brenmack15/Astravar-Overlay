import hashlib
import json
from pathlib import Path
import runpy
import sqlite3
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
HELPER = runpy.run_path(str(ROOT / "scripts/genesis-connected-readback.py"))
FIXTURE = runpy.run_path(str(ROOT / "scripts/tests/test_genesis_upgrade_readback.py"))
compact, sha = HELPER["compact"], HELPER["sha"]


class GenesisConnectedReadbackTest(unittest.TestCase):
    """Adversarial host proof fixtures; these are not Android UI executions."""

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.database = self.root / "astravar-campaign-ledger.db"
        self.document = self.root / "connected.json"
        with sqlite3.connect(self.database) as db:
            FIXTURE["create_entities"](db, FIXTURE["schema"]("CampaignLedgerDb", 8))
            add = lambda table, **values: FIXTURE["insert"](db, table, **values)
            add("genesis_campaigns", id="campaign", name=HELPER["CAMPAIGN"], notes="Fictional Ω campaign", archived=0,
                commandId="campaign-create", revision=1, createdAt=1, updatedAt=1)
            definition = {"id": "definition", "name": HELPER["ITEM"], "effects": [], "description": "Fictional compass"}
            instance = {"id": "compass", "definitionId": "definition", "quantity": 1, "state": "CARRIED", "attuned": False, "containerId": None}
            character = {"id": "hero", "name": HELPER["HERO"], "revision": 3, "currentHp": 14,
                         "baseMaxHp": 20, "baseAc": 13, "itemDefinitions": [definition], "items": [instance]}
            payload = compact(character)
            add("reforge_characters", profileId="hero", characterJson=payload, revision=3,
                originJson=compact({"label": "Fictional origin Ω"}), createdAt=2, updatedAt=5)
            for identity, name, role in (("hero", HELPER["HERO"], "PLAYER"), ("npc", HELPER["NPC"], "NPC"),
                                         ("companion", HELPER["COMPANION"], "COMPANION")):
                playable = identity == "hero"
                add("character_profiles", id=identity, name=name, currentHp=14 if playable else None,
                    maximumHp=20 if playable else None, temporaryHp=0 if playable else None,
                    armorClass=13 if playable else None, level=1 if playable else None,
                    revision=1, source="Fictional creation", updatedAt=2)
                add("genesis_actor_authority", actorId=identity, role=role, campaignId="campaign",
                    ownerActorId="hero" if identity == "companion" else None, playable=int(playable),
                    revision=1, activationCommandId=identity + "-create", lastCommandId=identity + "-create",
                    playableRevision=3 if playable else None, projectionFingerprint=sha(payload) if playable else None,
                    resourceBindingsJson="{}", createdAt=2, updatedAt=2)
            add("connection_entities", id="npc", name=HELPER["NPC"], kind="Person")
            add("companion_states", companionId="companion", name=HELPER["COMPANION"])
            add("campaign_links", id="owner-link", fromType="companion", fromId="companion",
                toType="character-profile", toId="hero", label="Companion of")
            add("genesis_item_authority", itemId="compass", ownerActorId="hero", definitionJson=compact(definition),
                instanceJson=compact(instance), activationCommandId="item-create", revision=1, updatedAt=5)
            add("ledger_items", id="compass", title=HELPER["ITEM"], owner=HELPER["HERO"], quantity=1,
                itemState="Carried", archived=0, createdAt=5, updatedAt=5)
            add("item_placements", itemId="compass", holderProfileId="hero", containerItemId=None,
                installedProjectId=None, state="Carried", revision=1, updatedAt=5)
            db.row_factory = sqlite3.Row
            campaign = dict(db.execute("SELECT * FROM genesis_campaigns").fetchone())
            actor_rows = [dict(row) for row in db.execute("SELECT * FROM genesis_actor_authority")]
            for command, kind, outcome in [("campaign-create", "create-campaign", campaign)] + [
                    (row["activationCommandId"], "create-actor", {"actor": row}) for row in actor_rows]:
                request = compact(["Fictional request Ω", command])
                add("genesis_authority_receipts", commandId=command, kind=kind, requestJson=request,
                    fingerprint=sha(compact(["genesis-authority-v1", kind, request])), outcomeJson=compact(outcome), createdAt=2)
        self.export_document()

    def tearDown(self):
        self.temp.cleanup()

    def export_document(self):
        with sqlite3.connect(self.database) as db:
            db.row_factory = sqlite3.Row
            rows = lambda table: [dict(row) for row in db.execute(f'SELECT * FROM "{table}"')]
            self.backup = {"format": "astravar-connected-campaign", "schema": 1, "exportedAt": 6,
                           "campaign": {"exportedAt": 6, "items": rows("ledger_items"),
                                        "records": {"characterProfiles": rows("character_profiles"), "placements": rows("item_placements")}},
                           "playable": {"exportedAt": 6, "characters": rows("reforge_characters")},
                           "authority": {"campaigns": rows("genesis_campaigns"), "actors": rows("genesis_actor_authority"),
                                         "items": rows("genesis_item_authority"), "receipts": rows("genesis_authority_receipts")}}
            fingerprint = HELPER["backup_fingerprint"](self.backup)
            db.execute("DELETE FROM command_receipts WHERE commandId='restore'")
            db.execute("DELETE FROM campaign_audit WHERE id='restore'")
            FIXTURE["insert"](db, "command_receipts", commandId="restore", fingerprint=sha("GENESIS_RESTORE:v1:" + fingerprint),
                              kind="RESTORE", recordId=fingerprint, resultingRevision=1, createdAt=7)
            FIXTURE["insert"](db, "campaign_audit", id="restore", commandId="restore", recordType="backup", recordId=fingerprint,
                              kind="RESTORE", source="User-reviewed complete connected backup merge", priorRevision=0, resultingRevision=1, createdAt=7)
        self.document.write_text(compact(self.backup, ascii_only=True))

    def update(self, sql, parameters=()):
        with sqlite3.connect(self.database) as db:
            db.execute(sql, parameters)

    def inspect(self, **kwargs):
        return HELPER["inspect"](self.database, self.document, synthetic_emulator=kwargs.get("synthetic_emulator", True))

    def test_valid_readback_preserves_database_and_exact_file(self):
        before = hashlib.sha256(self.database.read_bytes()).hexdigest(), self.document.read_bytes()
        result = self.inspect()
        self.assertEqual("PASS", result["status"])
        self.assertEqual("hero", result["hero_id"])
        self.assertEqual("compass", result["item_id"])
        self.assertFalse(result["physical_device_acceptance"])
        self.assertEqual(before, (hashlib.sha256(self.database.read_bytes()).hexdigest(), self.document.read_bytes()))

    def test_requires_explicit_fictional_emulator_scope(self):
        with self.assertRaisesRegex(ValueError, "fictional emulator"):
            self.inspect(synthetic_emulator=False)

    def test_missing_authority_domain_is_rejected(self):
        del self.backup["authority"]
        self.document.write_text(compact(self.backup))
        with self.assertRaisesRegex(ValueError, "domain is missing"):
            self.inspect()

    def test_backup_cannot_omit_an_actor(self):
        self.backup["authority"]["actors"].pop()
        self.document.write_text(compact(self.backup))
        with self.assertRaisesRegex(ValueError, "exact rows"):
            self.inspect()

    def test_shared_wrong_owner_still_fails(self):
        self.update("UPDATE genesis_actor_authority SET ownerActorId='npc' WHERE actorId='companion'")
        self.export_document()
        with self.assertRaisesRegex(ValueError, "Wrong role, campaign, owner"):
            self.inspect()

    def test_shared_unknown_statistics_are_not_accepted(self):
        self.update("UPDATE character_profiles SET maximumHp=10 WHERE id='npc'")
        self.export_document()
        with self.assertRaisesRegex(ValueError, "statistics were invented"):
            self.inspect()

    def test_missing_same_identity_npc_connection_fails(self):
        self.update("UPDATE connection_entities SET id='duplicated-npc-id' WHERE id='npc'")
        self.export_document()
        with self.assertRaisesRegex(ValueError, "same-ID NPC"):
            self.inspect()

    def test_source_health_disagreement_is_rejected_even_in_backup(self):
        self.update("UPDATE character_profiles SET currentHp=13 WHERE id='hero'")
        self.export_document()
        with self.assertRaisesRegex(ValueError, "health or armor"):
            self.inspect()

    def test_item_mechanics_mismatch_is_rejected_even_in_backup(self):
        changed = dict(self.backup["authority"]["items"][0])
        instance = json.loads(changed["instanceJson"])
        instance["quantity"] = 2
        self.update("UPDATE genesis_item_authority SET instanceJson=?", (compact(instance),))
        self.export_document()
        with self.assertRaisesRegex(ValueError, "Physical item authority"):
            self.inspect()

    def test_placement_mismatch_is_rejected_even_in_backup(self):
        self.update("UPDATE item_placements SET holderProfileId='npc'")
        self.export_document()
        with self.assertRaisesRegex(ValueError, "Source placement"):
            self.inspect()

    def test_receipt_request_tamper_is_rejected_even_in_backup(self):
        self.update("UPDATE genesis_authority_receipts SET requestJson='[]' WHERE commandId='hero-create'")
        self.export_document()
        with self.assertRaisesRegex(ValueError, "exact request"):
            self.inspect()

    def test_stale_projection_fingerprint_is_rejected(self):
        self.update("UPDATE genesis_actor_authority SET projectionFingerprint='wrong' WHERE actorId='hero'")
        self.export_document()
        with self.assertRaisesRegex(ValueError, "projection digest"):
            self.inspect()

    def test_merge_of_another_document_does_not_prove_this_file(self):
        self.update("UPDATE command_receipts SET recordId='another-backup' WHERE commandId='restore'")
        with self.assertRaisesRegex(ValueError, "complete merge receipt"):
            self.inspect()

    def test_duplicate_json_member_is_rejected(self):
        self.document.write_text(self.document.read_text().replace('"schema":1', '"schema":1,"schema":1', 1))
        with self.assertRaisesRegex(ValueError, "Duplicate JSON member"):
            self.inspect()

    def test_sparse_oversized_file_rejected_before_decode(self):
        with self.document.open("wb") as output:
            output.truncate(HELPER["DOCUMENT_LIMIT"] + 1)
        with self.assertRaisesRegex(ValueError, "64 MiB"):
            self.inspect()


if __name__ == "__main__":
    unittest.main()
