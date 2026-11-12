import json
from pathlib import Path
import runpy
import shutil
import sqlite3
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
HELPER = runpy.run_path(str(ROOT / "scripts/genesis-upgrade-readback.py"))
SAVE_STATE = "Saved on this device · not synchronized"
ACTOR_ID = "actor-id"


def schema(name, version):
    return json.loads((ROOT / f"app/schemas/com.yazsras.astravar.data.{name}/{version}.json").read_text())["database"]


def create_entities(connection, database):
    for entity in database["entities"]:
        table = entity["tableName"]
        connection.execute(entity["createSql"].replace("${TABLE_NAME}", table))
        for index in entity.get("indices", []):
            connection.execute(index["createSql"].replace("${TABLE_NAME}", table))
    for query in database["setupQueries"]:
        connection.execute(query)
    connection.execute(f'PRAGMA user_version={database["version"]}')


def insert(connection, table, **values):
    """Require relevant fixture values; all other required fields get synthetic defaults."""
    for column in connection.execute(f'PRAGMA table_info("{table}")'):
        _, name, affinity, required, default, _ = column
        if name not in values and required and default is None:
            values[name] = 0 if affinity in ("INTEGER", "REAL") else "Synthetic upgrade fixture"
    columns = ",".join(f'"{name}"' for name in values)
    connection.execute(f'INSERT INTO "{table}" ({columns}) VALUES ({",".join("?" for _ in values)})',
                       tuple(values.values()))


def saved_character(row):
    return {"character": json.loads(row["characterJson"]), "revision": row["revision"],
            "origin": json.loads(row["originJson"]), "createdAt": row["createdAt"],
            "updatedAt": row["updatedAt"], "saveState": SAVE_STATE}


class GenesisStorageDigestEncodingTest(unittest.TestCase):
    def test_typed_length_prefix_golden_vectors(self):
        vectors = [
            (None, bytes.fromhex("00 0000000000000000")),
            (-2, bytes.fromhex("01 0000000000000002 2d32")),
            (-0.0, bytes.fromhex("02 0000000000000014") + b"-9223372036854775808"),
            ("é", bytes.fromhex("03 0000000000000002 c3a9")),
            (b"\x00\xff", bytes.fromhex("04 0000000000000002 00ff")),
        ]
        for value, expected in vectors:
            with self.subTest(value=value):
                self.assertEqual(expected, HELPER["field"](value))


class GenesisUpgradeReadbackTest(unittest.TestCase):
    """Independent checks of copied fictional stores, never production phone data."""

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.before_folder = self.root / "before"
        self.after_folder = self.root / "after"
        self.before_folder.mkdir()
        self.campaign6 = schema("CampaignLedgerDb", 6)
        self.campaign8 = schema("CampaignLedgerDb", 8)
        self.reforge1 = schema("ReforgeDb", 1)
        self.playable_tables = [entity["tableName"] for entity in self.reforge1["entities"]]
        with sqlite3.connect(self.before_folder / "astravar.db") as db:
            create_entities(db, schema("AstravarDb", 3))
            insert(db, "campaign", id=1, body=' { "opaque": "weapon snapshot" }\n')
            insert(db, "archives", id="weapon-history", body=b"\x00original weapon archive\xff")
        character = {"id": ACTOR_ID, "name": HELPER["ACTOR"], "revision": 4,
                     "currentHp": 17, "baseMaxHp": 32, "temporaryHp": 3, "baseAc": 16, "hpDamage": 15,
                     "notes": "Keep exact approved facts, including Ω and unknown values.",
                     "itemDefinitions": [{"id": "definition-id", "name": HELPER["ITEM"]},
                                         {"id": "supplies-definition-id", "name": HELPER["SUPPLIES"]}],
                     "items": [{"id": "item-id", "definitionId": "definition-id", "quantity": 1, "state": "WORN"},
                               {"id": "supplies-id", "definitionId": "supplies-definition-id", "quantity": 3, "state": "CARRIED"}],
                     "resources": [{"id": "pool-id", "name": HELPER["RESOURCE"], "current": 2, "maximum": 5}]}
        origin = {"label": "Synthetic native fixture", "sourceProfileId": ACTOR_ID,
                  "sourceFingerprint": None, "sourceSnapshotJson": None, "warnings": []}
        with sqlite3.connect(self.before_folder / "astravar-reforge.db") as db:
            create_entities(db, self.reforge1)
            insert(db, "reforge_characters", profileId=ACTOR_ID,
                   characterJson=json.dumps(character, ensure_ascii=False, indent=2), revision=4,
                   originJson=json.dumps(origin, ensure_ascii=False, indent=1), createdAt=1000, updatedAt=1004)
            other = dict(character, id="other-actor", name="Other fictional actor")
            insert(db, "reforge_characters", profileId=other["id"], characterJson=json.dumps(other), revision=4,
                   originJson=json.dumps(origin), createdAt=2000, updatedAt=2004)
            insert(db, "reforge_changes", commandId="historical-command", profileId=ACTOR_ID,
                   operation="Retired opaque historical outcome", fingerprint="1" * 64,
                   priorRevision=0, resultingRevision=1, createdAt=1000,
                   beforeJson=None, afterJson=' { "retired": [true, null], "unknown": "Ω" } \n',
                   outcomeType="FUTURE_OPAQUE_TYPE")
            insert(db, "reforge_rolls", id="historical-roll", profileId=ACTOR_ID, characterRevision=2,
                   label="Historical roll", formula="1d20", components=' [ { "legacy": 17 } ] ',
                   manual=1, ownership="PLAYER", createdAt=1002)
            insert(db, "reforge_note_drafts", profileId=ACTOR_ID, expectedRevision=2,
                   text="Unfinished draft\n  preserve whitespace\tΩ", updatedAt=1003)
        with sqlite3.connect(self.before_folder / "astravar-campaign-ledger.db") as db:
            create_entities(db, self.campaign6)
            insert(db, "character_profiles", id=ACTOR_ID, name=HELPER["ACTOR"], currentHp=None,
                   maximumHp=None, revision=1, source="Reviewed name-only identity", updatedAt=1001)
            insert(db, "character_profiles", id="ally-id", name=HELPER["ALLY"], currentHp=7,
                   maximumHp=18, revision=2, source="Synthetic native fixture", updatedAt=1002)
            insert(db, "companion_states", companionId="ally-id", name=HELPER["ALLY"], condition="Unknown",
                   notes="Synthetic companion", revision=1, source="Synthetic native fixture", updatedAt=1002)
            insert(db, "campaign_links", id="link-id", fromType="companion", fromId="ally-id",
                   toType="character-profile", toId=ACTOR_ID, label="Fictional retained ally", revision=1,
                   source="Synthetic native fixture", updatedAt=1003)

    def tearDown(self):
        self.temp.cleanup()

    def migrate(self):
        """Model additive DDL and raw transfer using exported schemas, not migration code."""
        shutil.copytree(self.before_folder, self.after_folder)
        source_uri = (self.after_folder / "astravar-reforge.db").resolve().as_uri() + "?mode=ro"
        with sqlite3.connect(source_uri, uri=True) as source:
            source_digest, counts = HELPER["raw_digest"](source)
            with sqlite3.connect(self.after_folder / "astravar-campaign-ledger.db") as destination:
                create_entities(destination, self.campaign8)
                for table in self.playable_tables:
                    rows = source.execute(f'SELECT * FROM "{table}"').fetchall()
                    columns = [entry[1] for entry in source.execute(f'PRAGMA table_info("{table}")')]
                    destination.executemany(f'INSERT INTO "{table}" VALUES ({",".join("?" for _ in columns)})', rows)
                insert(destination, "genesis_storage_receipt", id=1, sourceVersion=1,
                       sourceDigest=source_digest, countsJson=json.dumps(counts), completedAt=5000,
                       authority="campaign-ledger-reforge-v1")

    def migrated_pair(self):
        before = HELPER["inspect"](self.before_folder, phase="baseline")
        self.migrate()
        after = HELPER["inspect"](self.after_folder, phase="preserved")
        return before, after

    def write_healing(self):
        with sqlite3.connect(self.after_folder / "astravar-campaign-ledger.db") as db:
            db.row_factory = sqlite3.Row
            before = dict(db.execute("SELECT * FROM reforge_characters WHERE profileId=?", (ACTOR_ID,)).fetchone())
            character = json.loads(before["characterJson"])
            character.update(currentHp=18, hpDamage=14, revision=before["revision"] + 1)
            after = dict(before, characterJson=json.dumps(character, ensure_ascii=False),
                         revision=before["revision"] + 1, updatedAt=before["updatedAt"] + 1)
            db.execute("UPDATE reforge_characters SET characterJson=?,revision=?,updatedAt=? WHERE profileId=?",
                       (after["characterJson"], after["revision"], after["updatedAt"], ACTOR_ID))
            insert(db, "reforge_changes", commandId="new-healing-command", profileId=ACTOR_ID,
                   operation="Apply healing", fingerprint="a" * 64, priorRevision=before["revision"],
                   resultingRevision=after["revision"], createdAt=after["updatedAt"],
                   beforeJson=json.dumps(saved_character(before), ensure_ascii=False),
                   afterJson=json.dumps(saved_character(after), ensure_ascii=False), outcomeType="CHARACTER")

    def assert_rejected(self, action):
        with self.assertRaises((AssertionError, ValueError)):
            action()

    def test_additive_cutover_preserves_all_tables_and_both_inventory_identities(self):
        before, after = self.migrated_pair()
        HELPER["verify"](before, after)
        self.assertEqual(31, len(self.campaign6["entities"]))
        self.assertEqual(40, len(self.campaign8["entities"]))
        self.assertEqual("item-id", after["fixture"]["item_id"])
        self.assertEqual("supplies-id", after["fixture"]["supplies_item_id"])
        self.assertEqual(before["fixture"], after["fixture"])

    def test_upgrade_does_not_silently_create_authority(self):
        before, after = self.migrated_pair()
        for table in HELPER["AUTHORITY_TABLES"]:
            self.assertEqual(0, after["databases"][HELPER["CAMPAIGN"]]["tables"][table]["rows"])
        with sqlite3.connect(self.after_folder / "astravar-campaign-ledger.db") as db:
            insert(db, "genesis_campaigns", id="invented-campaign", name="Unexpected enrollment", notes="",
                   revision=1, createdAt=1, updatedAt=1, commandId="invented-command")
        with self.assertRaisesRegex(ValueError, "must not automatically enroll"):
            HELPER["inspect"](self.after_folder, phase="preserved")

    def test_large_opaque_blob_history_and_json_whitespace_are_copied_verbatim(self):
        opaque = b"\x00" + b"unsupported historical binary\xff" * 120000
        with sqlite3.connect(self.before_folder / "astravar-reforge.db") as db:
            db.execute("UPDATE reforge_changes SET afterJson=?", (opaque,))
        before, after = self.migrated_pair()
        HELPER["verify"](before, after)
        with sqlite3.connect(self.after_folder / "astravar-campaign-ledger.db") as db:
            self.assertEqual(opaque, db.execute("SELECT afterJson FROM reforge_changes").fetchone()[0])

    def test_committed_source_wal_is_read_without_checkpointing_or_discarding_it(self):
        with sqlite3.connect(self.before_folder / "astravar-reforge.db") as writer:
            writer.execute("PRAGMA journal_mode=WAL")
            writer.execute("PRAGMA wal_autocheckpoint=0")
            writer.execute("UPDATE reforge_note_drafts SET text='Committed WAL draft retained exactly'")
            writer.commit()
            self.assertTrue((self.before_folder / "astravar-reforge.db-wal").is_file())
            before, after = self.migrated_pair()
            HELPER["verify"](before, after)
            self.assertEqual((self.before_folder / "astravar-reforge.db-wal").read_bytes(),
                             (self.after_folder / "astravar-reforge.db-wal").read_bytes())
            with sqlite3.connect(self.after_folder / "astravar-campaign-ledger.db") as db:
                self.assertEqual("Committed WAL draft retained exactly", db.execute("SELECT text FROM reforge_note_drafts").fetchone()[0])

    def test_modified_original_campaign_table_is_rejected(self):
        before, _ = self.migrated_pair()
        with sqlite3.connect(self.after_folder / "astravar-campaign-ledger.db") as db:
            db.execute("UPDATE character_profiles SET level=1 WHERE id=?", (ACTOR_ID,))
        self.assert_rejected(lambda: HELPER["verify"](before, HELPER["inspect"](self.after_folder, phase="preserved")))

    def test_modified_original_weapon_archive_is_rejected(self):
        before, _ = self.migrated_pair()
        with sqlite3.connect(self.after_folder / "astravar.db") as db:
            db.execute("UPDATE archives SET body=?", (b"rewritten",))
        self.assert_rejected(lambda: HELPER["verify"](before, HELPER["inspect"](self.after_folder, phase="preserved")))

    def test_retained_source_mutation_is_rejected(self):
        before, _ = self.migrated_pair()
        with sqlite3.connect(self.after_folder / "astravar-reforge.db") as db:
            db.execute("UPDATE reforge_note_drafts SET text='rewritten source'")
        self.assert_rejected(lambda: HELPER["verify"](before, HELPER["inspect"](self.after_folder, phase="preserved")))

    def test_missing_or_extra_colocated_table_is_rejected(self):
        for mutation in ('DROP TABLE reforge_rolls', 'CREATE TABLE unexpected_new_table (id TEXT PRIMARY KEY)'):
            with self.subTest(mutation=mutation):
                if self.after_folder.exists():
                    shutil.rmtree(self.after_folder)
                before, _ = self.migrated_pair()
                with sqlite3.connect(self.after_folder / "astravar-campaign-ledger.db") as db:
                    db.execute(mutation)
                self.assert_rejected(lambda: HELPER["verify"](before, HELPER["inspect"](self.after_folder, phase="preserved")))

    def test_wrong_room_identity_is_rejected(self):
        self.migrate()
        with sqlite3.connect(self.after_folder / "astravar-campaign-ledger.db") as db:
            db.execute("UPDATE room_master_table SET identity_hash='unvalidated schema'")
        self.assert_rejected(lambda: HELPER["inspect"](self.after_folder, phase="preserved"))

    def test_receipt_identity_digest_counts_and_singleton_are_required(self):
        mutations = [
            "DELETE FROM genesis_storage_receipt",
            "UPDATE genesis_storage_receipt SET authority='different-authority'",
            "UPDATE genesis_storage_receipt SET sourceVersion=0",
            "UPDATE genesis_storage_receipt SET sourceDigest='" + "0" * 64 + "'",
            "UPDATE genesis_storage_receipt SET countsJson='{}'",
            "UPDATE genesis_storage_receipt SET completedAt=0",
            "INSERT INTO genesis_storage_receipt SELECT 2,sourceVersion,sourceDigest,countsJson,completedAt,authority FROM genesis_storage_receipt",
        ]
        for mutation in mutations:
            with self.subTest(mutation=mutation):
                if self.after_folder.exists():
                    shutil.rmtree(self.after_folder)
                self.migrate()
                with sqlite3.connect(self.after_folder / "astravar-campaign-ledger.db") as db:
                    db.execute(mutation)
                self.assert_rejected(lambda: HELPER["inspect"](self.after_folder, phase="preserved"))

    def test_reencoded_copied_opaque_history_is_rejected(self):
        before, _ = self.migrated_pair()
        with sqlite3.connect(self.after_folder / "astravar-campaign-ledger.db") as db:
            value = db.execute("SELECT afterJson FROM reforge_changes").fetchone()[0]
            db.execute("UPDATE reforge_changes SET afterJson=?", (json.dumps(json.loads(value)),))
        self.assert_rejected(lambda: HELPER["verify"](before, HELPER["inspect"](self.after_folder, phase="preserved")))

    def test_missing_original_store_never_proves_preservation(self):
        (self.before_folder / "astravar.db").unlink()
        self.assert_rejected(lambda: HELPER["inspect"](self.before_folder, phase="baseline"))

    def test_inventory_quantities_and_equipment_state_remain_required(self):
        for index, field, value in [(1, "quantity", 1), (0, "state", "CARRIED")]:
            with self.subTest(field=field):
                if self.after_folder.exists():
                    shutil.rmtree(self.after_folder)
                before, _ = self.migrated_pair()
                with sqlite3.connect(self.after_folder / "astravar-campaign-ledger.db") as db:
                    actor = json.loads(db.execute("SELECT characterJson FROM reforge_characters WHERE profileId=?", (ACTOR_ID,)).fetchone()[0])
                    actor["items"][index][field] = value
                    db.execute("UPDATE reforge_characters SET characterJson=? WHERE profileId=?", (json.dumps(actor), ACTOR_ID))
                self.assert_rejected(lambda: HELPER["verify"](before, HELPER["inspect"](self.after_folder, phase="preserved")))

    def test_single_canonical_healing_write_preserves_originals_and_history(self):
        _, preserved = self.migrated_pair()
        source_before = (self.after_folder / "astravar-reforge.db").read_bytes()
        self.write_healing()
        written = HELPER["inspect"](self.after_folder, phase="written")
        HELPER["verify_write"](preserved, written)
        self.assertEqual(source_before, (self.after_folder / "astravar-reforge.db").read_bytes())

    def test_missing_canonical_write_cannot_be_replaced_by_old_store_write(self):
        _, preserved = self.migrated_pair()
        self.assert_rejected(lambda: HELPER["verify_write"](preserved, HELPER["inspect"](self.after_folder, phase="written")))
        with sqlite3.connect(self.after_folder / "astravar-reforge.db") as db:
            actor = json.loads(db.execute("SELECT characterJson FROM reforge_characters WHERE profileId=?", (ACTOR_ID,)).fetchone()[0])
            actor.update(currentHp=18, hpDamage=14, revision=5)
            db.execute("UPDATE reforge_characters SET characterJson=?,revision=5 WHERE profileId=?", (json.dumps(actor), ACTOR_ID))
        self.assert_rejected(lambda: HELPER["verify_write"](preserved, HELPER["inspect"](self.after_folder, phase="written")))

    def test_write_to_unrelated_actor_is_rejected(self):
        _, preserved = self.migrated_pair()
        self.write_healing()
        with sqlite3.connect(self.after_folder / "astravar-campaign-ledger.db") as db:
            actor = json.loads(db.execute("SELECT characterJson FROM reforge_characters WHERE profileId='other-actor'").fetchone()[0])
            actor["notes"] = "unrelated mutation"
            db.execute("UPDATE reforge_characters SET characterJson=? WHERE profileId='other-actor'", (json.dumps(actor),))
        self.assert_rejected(lambda: HELPER["verify_write"](preserved, HELPER["inspect"](self.after_folder, phase="written")))

    def test_healing_does_not_authorize_unrelated_fields_on_the_same_actor(self):
        _, preserved = self.migrated_pair()
        self.write_healing()
        with sqlite3.connect(self.after_folder / "astravar-campaign-ledger.db") as db:
            actor = json.loads(db.execute("SELECT characterJson FROM reforge_characters WHERE profileId=?", (ACTOR_ID,)).fetchone()[0])
            actor["notes"] = "Unrelated accepted fact rewritten"
            db.execute("UPDATE reforge_characters SET characterJson=? WHERE profileId=?", (json.dumps(actor), ACTOR_ID))
        self.assert_rejected(lambda: HELPER["verify_write"](preserved, HELPER["inspect"](self.after_folder, phase="written")))

    def test_canonical_write_does_not_authorize_physical_rewriting_of_retained_source(self):
        _, preserved = self.migrated_pair()
        self.write_healing()
        source = self.after_folder / "astravar-reforge.db"
        original_bytes = source.read_bytes()
        with sqlite3.connect(source) as db:
            # Restore the same logical version and rows after a physical write.
            # Table equality alone must not call this untouched recovery data.
            db.execute("PRAGMA user_version=2")
            db.execute("PRAGMA user_version=1")
        self.assertNotEqual(original_bytes, source.read_bytes())
        self.assert_rejected(lambda: HELPER["verify_write"](preserved, HELPER["inspect"](self.after_folder, phase="written")))

    def test_canonical_heal_does_not_authorize_old_history_or_draft_changes(self):
        for mutation in ["UPDATE reforge_changes SET afterJson='rewritten' WHERE commandId='historical-command'",
                         "UPDATE reforge_note_drafts SET text='lost unsaved draft'",
                         "UPDATE character_profiles SET source='rewritten provenance' WHERE id='actor-id'"]:
            with self.subTest(mutation=mutation):
                if self.after_folder.exists():
                    shutil.rmtree(self.after_folder)
                _, preserved = self.migrated_pair()
                self.write_healing()
                with sqlite3.connect(self.after_folder / "astravar-campaign-ledger.db") as db:
                    db.execute(mutation)
                self.assert_rejected(lambda: HELPER["verify_write"](preserved, HELPER["inspect"](self.after_folder, phase="written")))

    def test_extra_event_or_wrong_healing_outcome_is_rejected(self):
        mutations = [
            "INSERT INTO reforge_changes SELECT 'extra-command',profileId,operation,fingerprint,priorRevision,resultingRevision,createdAt,beforeJson,afterJson,outcomeType FROM reforge_changes WHERE commandId='new-healing-command'",
            "UPDATE reforge_changes SET operation='Unapproved change' WHERE commandId='new-healing-command'",
            "UPDATE reforge_changes SET priorRevision=3 WHERE commandId='new-healing-command'",
            "UPDATE reforge_changes SET beforeJson='{}' WHERE commandId='new-healing-command'",
            "UPDATE reforge_changes SET afterJson='{}' WHERE commandId='new-healing-command'",
        ]
        for mutation in mutations:
            with self.subTest(mutation=mutation):
                if self.after_folder.exists():
                    shutil.rmtree(self.after_folder)
                _, preserved = self.migrated_pair()
                self.write_healing()
                with sqlite3.connect(self.after_folder / "astravar-campaign-ledger.db") as db:
                    db.execute(mutation)
                self.assert_rejected(lambda: HELPER["verify_write"](preserved, HELPER["inspect"](self.after_folder, phase="written")))


if __name__ == "__main__":
    unittest.main()
