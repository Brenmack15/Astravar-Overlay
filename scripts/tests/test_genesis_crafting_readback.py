import copy
import hashlib
import json
from pathlib import Path
import runpy
import shutil
import sqlite3
import unittest

ROOT = Path(__file__).resolve().parents[2]
HELPER = runpy.run_path(str(ROOT / "scripts/genesis-crafting-readback.py"))
CONNECTED = runpy.run_path(str(ROOT / "scripts/tests/test_genesis_connected_readback.py"))
compact, sha, child = (HELPER[name] for name in ("compact", "sha", "child"))
insert = CONNECTED["FIXTURE"]["insert"]


class GenesisCraftingReadbackTest(unittest.TestCase):
    """Host adversarial fixtures only; Android UI execution remains a separate gate."""

    def setUp(self):
        self.fixture = CONNECTED["GenesisConnectedReadbackTest"]()
        self.fixture.setUp()
        self.before = self.fixture.database
        self.after = self.fixture.root / "crafted.db"
        shutil.copyfile(self.before, self.after)
        with sqlite3.connect(self.after) as db:
            db.row_factory = sqlite3.Row
            def add(table, **values):
                insert(db, table, **values)
            def row(table, key, value):
                return dict(db.execute(f'SELECT * FROM "{table}" WHERE "{key}"=?', (value,)).fetchone())
            def update(table, key, value, **values):
                db.execute(f'UPDATE "{table}" SET ' + ",".join(f'"{field}"=?' for field in values) + f' WHERE "{key}"=?',
                           (*values.values(), value))
            def authority_receipt(command, kind, request, outcome):
                request = compact(request)
                add("genesis_authority_receipts", commandId=command, kind=kind, requestJson=request,
                    fingerprint=sha(compact(["genesis-authority-v1", kind, request])), outcomeJson=compact(outcome), createdAt=7)

            # Start with the earlier connected journey. The new UI creates its
            # material through create-items, then makes exactly one craft delta.
            baseline = row("reforge_characters", "profileId", "hero")
            material_definition = {"id": "material-definition", "name": HELPER["MATERIAL"], "effects": [], "description": ""}
            material_instance = {"id": "material", "definitionId": "material-definition", "quantity": 3,
                                 "state": "CARRIED", "attuned": False, "containerId": None}
            before_character = json.loads(baseline["characterJson"])
            before_character["revision"] += 1
            before_character["itemDefinitions"].append(material_definition)
            before_character["items"].append(material_instance)
            payload = compact(before_character)
            update("reforge_characters", "profileId", "hero", characterJson=payload, revision=4, updatedAt=6)
            update("genesis_actor_authority", "actorId", "hero", playableRevision=4, projectionFingerprint=sha(payload), revision=2)
            add("ledger_items", id="material", title=HELPER["MATERIAL"], owner=HELPER["HERO"], quantity=3,
                itemState="Carried", archived=0, revision=1, createdAt=6, updatedAt=6)
            add("item_placements", itemId="material", holderProfileId="hero", containerItemId=None, installedProjectId=None,
                state="Carried", revision=1, updatedAt=6)
            add("genesis_item_authority", itemId="material", ownerActorId="hero", definitionJson=compact(material_definition),
                instanceJson=compact(material_instance), activationCommandId="material-create", sourceProofJson=None, revision=1, updatedAt=6)
            authority_receipt("material-create", "create-items", {"fictional": "material"}, {"itemId": "material"})
            add("invention_projects", id="design", title=HELPER["PROJECT"], status="Proposed", revision=1, source="Fictional proposal", updatedAt=6)
            add("material_requirements", id="requirement", projectId="design", itemId="material", label=HELPER["REQUIREMENT"], quantity=1, revision=1)
            frozen_material = row("ledger_items", "id", "material")
            frozen_project = row("invention_projects", "id", "design")
            frozen_actor = {"authority": row("genesis_actor_authority", "actorId", "hero"),
                            "playable": HELPER["saved"](row("reforge_characters", "profileId", "hero")),
                            "profile": row("character_profiles", "id", "hero")}
            output_draft = {"title": HELPER["OUTPUT"], "category": "Other", "owner": HELPER["HERO"], "itemState": "Carried",
                            "location": "Fictional carried placement", "quantity": 1, "description": HELPER["DESCRIPTION"],
                            "source": HELPER["SOURCE"]}
            review = {"project": frozen_project, "materials": [{"use": {"itemId": "material", "expectedRevision": 1, "quantity": 1},
                       "item": frozen_material, "placement": row("item_placements", "itemId", "material"),
                       "authority": row("genesis_item_authority", "itemId", "material")}],
                      "output": output_draft, "outputOwnerActorId": "hero", "outputContainerItemId": None,
                      "actors": [frozen_actor], "source": HELPER["SOURCE"], "fingerprint": ""}
            review["fingerprint"] = sha(compact(review))
            parent, craft = "craft-parent", child("craft-parent", "craft")
            update("invention_projects", "id", "design", status="Completed", revision=2, source=HELPER["SOURCE"], updatedAt=7)
            completed_project = row("invention_projects", "id", "design")
            update("ledger_items", "id", "material", quantity=2, revision=2, updatedAt=7)
            add("ledger_items", id="output", **output_draft, archived=0, revision=1, createdAt=7, updatedAt=7)
            source_output = row("ledger_items", "id", "output")
            output_definition = {"id": "output", "name": HELPER["OUTPUT"], "effects": [], "description": HELPER["DESCRIPTION"]}
            output_instance = {"id": "output", "definitionId": "output", "quantity": 1, "state": "CARRIED", "attuned": False, "containerId": None}
            add("item_placements", itemId="output", holderProfileId="hero", containerItemId=None, installedProjectId=None,
                state="Carried", revision=1, updatedAt=7)
            command = {"projectId": "design", "expectedProjectRevision": 1, "materials": [review["materials"][0]["use"]],
                       "output": output_draft, "source": HELPER["SOURCE"], "commandId": ""}
            add("command_receipts", commandId=craft, fingerprint=sha(compact(command)), kind="CRAFT", recordId="design",
                resultingRevision=2, createdAt=7, outputId="output", outcomeJson=compact(completed_project))
            add("craft_executions", commandId=craft, projectId="design", outputItemId="output", source=HELPER["SOURCE"], createdAt=7)
            add("craft_consumptions", id=craft + ":0", executionId=craft, itemId="material", quantity=1,
                beforeQuantity=3, afterQuantity=2, priorRevision=1, resultingRevision=2)
            after_material = row("ledger_items", "id", "material")
            add("ledger_events", commandId="craft-" + sha(craft)[:40] + "-0", itemId="material", kind="CRAFT_CONSUMPTION",
                payloadFingerprint=sha(compact(command)), source=HELPER["SOURCE"], priorRevision=1, resultingRevision=2,
                beforeJson=compact(frozen_material), afterJson=compact(after_material))
            add("campaign_audit", id=craft + ":material:0", commandId=craft, recordType="item", recordId="material",
                kind="CRAFT_CONSUMPTION", beforeJson=compact(frozen_material), afterJson=compact(after_material))
            add("campaign_audit", id=craft, commandId=craft, recordType="project", recordId="design", kind="CONFIRMED_CRAFT",
                beforeJson=compact(frozen_project), afterJson=compact(completed_project))
            add("ledger_events", commandId="craft-output-" + sha(craft)[:40], itemId="output", kind="CREATE",
                beforeJson=None, afterJson=compact(source_output))
            update("ledger_items", "id", "output", revision=2, source="Genesis connected authority v1", updatedAt=8)
            projected_output = row("ledger_items", "id", "output")
            projection = child(child(parent, "craft-project:hero"), "item-project:output")
            add("ledger_events", commandId=projection, itemId="output", kind="UPDATE", priorRevision=1, resultingRevision=2,
                beforeJson=compact(source_output), afterJson=compact(projected_output))
            add("command_receipts", commandId=projection, recordId="output", kind="UPDATE", resultingRevision=2,
                outcomeJson=compact(projected_output))
            link_id = "craft-output-link-" + sha(craft)[:40]
            add("campaign_links", id=link_id, fromType="project", fromId="design", toType="item", toId="output",
                label="Confirmed craft output", source=HELPER["SOURCE"], revision=1, updatedAt=7)
            link = row("campaign_links", "id", link_id)
            add("command_receipts", commandId="craft-link-" + sha(craft)[:40], fingerprint="synthetic", kind="link",
                recordId=link_id, resultingRevision=1, createdAt=7, outcomeJson=compact(link))
            after_character = copy.deepcopy(before_character)
            after_character["revision"] += 1
            after_character["items"][-1]["quantity"] = 2
            after_character["items"].append(output_instance)
            after_character["itemDefinitions"].append(output_definition)
            payload = compact(after_character)
            update("reforge_characters", "profileId", "hero", characterJson=payload, revision=5, updatedAt=7)
            update("genesis_actor_authority", "actorId", "hero", playableRevision=5, projectionFingerprint=sha(payload), revision=3)
            update("genesis_item_authority", "itemId", "material", instanceJson=compact(after_character["items"][-2]), revision=2, updatedAt=7)
            after_actor = HELPER["saved"](row("reforge_characters", "profileId", "hero"))
            add("reforge_changes", commandId=child(parent, "craft-playable:hero"), profileId="hero", outcomeType="CHARACTER",
                priorRevision=4, resultingRevision=5, beforeJson=compact(frozen_actor["playable"]), afterJson=compact(after_actor))
            consumption = row("craft_consumptions", "id", craft + ":0")
            outcome = {"outputId": "output", "craftReceipt": row("command_receipts", "commandId", craft),
                       "execution": row("craft_executions", "commandId", craft), "consumptions": [consumption],
                       "outputLink": link, "sourceOutput": source_output, "actors": [after_actor]}
            authority_receipt(parent, "craft-items", review, outcome)
            proof = {"format": "astravar-crafted-item-v1", "parentCommandId": parent, "craftCommandId": craft,
                     "outputId": "output", "projectId": "design", "reviewFingerprint": review["fingerprint"],
                     "consumptions": [consumption], "outputLinkId": link_id}
            add("genesis_item_authority", itemId="output", ownerActorId="hero", definitionJson=compact(output_definition),
                instanceJson=compact(output_instance), activationCommandId=parent, sourceProofJson=compact(proof), revision=1, updatedAt=7)

    def tearDown(self):
        self.fixture.tearDown()

    def update(self, sql, parameters=()):
        with sqlite3.connect(self.after) as db:
            db.execute(sql, parameters)

    def inspect(self, **kwargs):
        return HELPER["inspect"](self.before, self.after, synthetic_emulator=kwargs.get("synthetic_emulator", True))

    def test_valid_craft_proves_exact_identity_and_leaves_databases_unchanged(self):
        def hashes():
            return [hashlib.sha256(path.read_bytes()).hexdigest() for path in (self.before, self.after)]
        before = hashes()
        result = self.inspect()
        self.assertEqual("PASS", result["status"])
        self.assertEqual("output", result["output_id"])
        self.assertEqual(2, result["material_quantity"])
        self.assertFalse(result["physical_device_acceptance"])
        self.assertEqual(before, hashes())

    def test_requires_fictional_emulator_scope(self):
        with self.assertRaisesRegex(ValueError, "fictional emulator"):
            self.inspect(synthetic_emulator=False)

    def test_double_consumption_fails_even_when_label_would_be_correct(self):
        self.update("UPDATE ledger_items SET quantity=1 WHERE id='material'")
        with self.assertRaisesRegex(ValueError, "Wrong quantity"):
            self.inspect()

    def test_immutable_consumption_tampering_fails(self):
        self.update("UPDATE craft_consumptions SET beforeQuantity=4")
        with self.assertRaisesRegex(ValueError, "Immutable consumption"):
            self.inspect()

    def test_second_execution_fails_despite_unchanged_current_quantity(self):
        with sqlite3.connect(self.after) as db:
            insert(db, "craft_executions", commandId="duplicate", projectId="design", outputItemId="output", source=HELPER["SOURCE"])
        with self.assertRaisesRegex(ValueError, "single craft execution"):
            self.inspect()

    def test_wrong_same_name_output_owner_is_rejected(self):
        self.update("UPDATE item_placements SET holderProfileId='npc' WHERE itemId='output'")
        with self.assertRaisesRegex(ValueError, "Source placement"):
            self.inspect()

    def test_missing_output_proof_is_rejected(self):
        self.update("UPDATE genesis_item_authority SET sourceProofJson='{}' WHERE itemId='output'")
        with self.assertRaisesRegex(ValueError, "exact craft provenance"):
            self.inspect()

    def test_original_creation_does_not_substitute_for_connected_projection(self):
        self.update("DELETE FROM ledger_events WHERE kind='UPDATE'")
        with self.assertRaisesRegex(ValueError, "ledger_events"):
            self.inspect()

    def test_material_original_null_proof_cannot_be_replaced_by_output_proof(self):
        self.update("UPDATE genesis_item_authority SET sourceProofJson='{}' WHERE itemId='material'")
        with self.assertRaisesRegex(ValueError, "original provenance"):
            self.inspect()

    def test_request_hash_cannot_be_rewritten_independently(self):
        self.update("UPDATE genesis_authority_receipts SET fingerprint='incorrect' WHERE kind='craft-items'")
        with self.assertRaisesRegex(ValueError, "exact request"):
            self.inspect()

    def test_missing_material_history_is_rejected(self):
        self.update("DELETE FROM ledger_events WHERE kind='CRAFT_CONSUMPTION'")
        with self.assertRaisesRegex(ValueError, "ledger_events"):
            self.inspect()

    def test_lost_prior_receipt_is_rejected(self):
        self.update("DELETE FROM command_receipts WHERE commandId='restore'")
        with self.assertRaisesRegex(ValueError, "Existing command_receipts"):
            self.inspect()

    def test_unrelated_npc_mutation_is_rejected(self):
        self.update("UPDATE character_profiles SET currentHp=99 WHERE id='npc'")
        with self.assertRaisesRegex(ValueError, "Existing character_profiles"):
            self.inspect()


if __name__ == "__main__":
    unittest.main()
