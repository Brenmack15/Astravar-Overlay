import base64
import json
import sqlite3
import subprocess
import sys
import tempfile
import unittest
import zipfile
from contextlib import closing
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "tools"))
import ascension_migrate as migration
from ascension_migrate_legacy import compare_legacy, encode, legacy_review, sha, weapon_review


class OfflineMigrationTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.base = Path(self.temp.name)
        self.lab = self.base / "rehearsal"
        self.tenant = ("fixture-owner", "fixture-campaign")
        migration.init(self.lab, *self.tenant)
        self.source = self.base / "ledger.json"
        self.bundle = self.base / "snapshot.zip"
        self.wallet = {"id": "shared-fixture", "name": "Workshop float", "campaignId": self.tenant[1],
                       "source": "synthetic record", "revision": 7, "updatedAt": 123, "copper": 0,
                       "platinum": 18, "gold": 25, "silver": None, "electrum": None}
        self.checkpoint = {"id": "scene-fixture", "title": "Open drawer", "scene": "Closed workshop",
                           "unresolvedChoice": "Choose a drawer", "gameTime": "afternoon", "revision": 3,
                           "campaignId": self.tenant[1], "updatedAt": 112, "source": "synthetic record"}
        self.native = {"format": "astravar-campaign-items", "schema": 5, "exportedAt": 123,
                       "items": [], "events": [], "records": {"wallets": [self.wallet], "checkpoints": [self.checkpoint]}}
        self.source.write_bytes(encode(self.native))

    def convert(self):
        return migration.convert(self.source, self.bundle, self.tenant, [self.wallet["id"]])

    def apply(self):
        proof = migration.inspect(self.lab, self.bundle)["approvalSha256"]
        return migration.apply(self.lab, self.bundle, proof)

    def current_db(self, folder=None):
        return Path(migration.inspect(folder or self.lab)["database"])

    def rows(self, folder=None):
        with closing(migration.db_connect(self.current_db(folder))) as db:
            return migration.rows_by_family(db, self.tenant)

    def test_rehearse_native_exact_values_identity_and_original_integrity(self):
        receipt = self.convert()
        self.assertEqual(2, receipt["counts"]["cloud_entities"])
        self.assertEqual(1, receipt["originalSources"])
        dry = migration.inspect(self.lab, self.bundle)
        self.assertEqual("PREVIEW", dry["status"])
        self.assertEqual([], self.rows()["cloud_entities"])
        with self.assertRaisesRegex(ValueError, "Approval"):
            migration.apply(self.lab, self.bundle, "0" * 64)
        staged = self.apply()
        self.assertEqual("STAGED", staged["status"])
        saved = self.rows()["cloud_entities"]
        self.assertEqual(["scene-fixture", "shared-fixture"], [r["id"] for r in saved])
        self.assertEqual(self.checkpoint["unresolvedChoice"], json.loads(saved[0]["data_json"])["unresolved"])
        self.assertEqual({"cp": 0, "sp": None, "ep": None, "gp": 25, "pp": 18}, json.loads(saved[1]["data_json"])["coins"])
        self.assertEqual(self.wallet["revision"], saved[1]["revision"])
        verified = migration.apply(self.lab, self.bundle, dry["approvalSha256"])
        self.assertEqual(staged["receipt"], verified["receipt"])
        self.assertFalse(verified["advanced"])
        readback = migration.read_bundle(self.bundle)
        self.assertEqual([self.source.read_bytes()], [s[0] for s in readback["sources"].values()])
        self.assertEqual("staged", migration.inspect(self.lab)["state"]["authority"])
        self.assertEqual([], self.rows()["cloud_changes"])
        self.assertEqual([], self.rows()["cloud_receipts"])

    def test_conversion_refuses_ambiguous_or_unmapped_mechanics_and_duplicates(self):
        report, _, _ = legacy_review(self.source, *self.tenant, [])
        self.assertEqual(1, report["families"]["records.wallets"]["held"])
        self.assertFalse(report["families"]["records.projects"]["declared"])
        self.assertTrue(report["families"]["campaign.items"]["declared"])
        self.native["records"]["projects"] = [{"id": "project-fixture", "status": "Completed"}]
        self.source.write_bytes(encode(self.native))
        with self.assertRaisesRegex(ValueError, "remain held"):
            self.convert()
        self.assertFalse(self.bundle.exists())
        self.native["records"]["wallets"].append(self.wallet)
        self.source.write_bytes(encode(self.native))
        with self.assertRaisesRegex(ValueError, "Repeated"):
            self.convert()
        self.assertEqual([], self.rows()["cloud_entities"])

    def test_format_and_tenant_declarations_are_strict(self):
        self.native["schema"] = 4
        self.source.write_bytes(encode(self.native))
        with self.assertRaisesRegex(ValueError, "EP/PP"):
            self.convert()
        self.native["schema"] = 5
        self.native["records"]["newMechanics"] = [{"id": "unknown"}]
        self.source.write_bytes(encode(self.native))
        with self.assertRaisesRegex(ValueError, "Unrecognized"):
            self.convert()
        self.source.write_text('{"format":"a","format":"b"}')
        with self.assertRaisesRegex(ValueError, "Duplicate"):
            self.convert()

    def test_reconciliation_keeps_distinct_same_balance_events_and_revision_conflicts(self):
        self.native["events"] = [{"commandId":"earlier-purchase", "value":25}]
        self.source.write_bytes(encode(self.native))
        later = self.base / "later.json"
        self.native["events"] = [{"commandId":"different-purchase", "value":25}]
        self.native["records"]["wallets"][0]["copper"] = 1
        later.write_bytes(encode(self.native))
        result = compare_legacy(self.source,later,*self.tenant)
        self.assertEqual({"identical":1,"onlyEarlier":1,"onlyLater":1,"conflictingSameRevision":1},result["counts"])
        wallet = next(d for d in result["differences"] if d["family"] == "records.wallets")
        self.assertEqual(7,wallet["earlier"]["revision"])
        self.assertNotEqual(wallet["earlier"]["sha256"],wallet["later"]["sha256"])
        self.assertEqual([],self.rows()["cloud_receipts"])

    def test_rollback_restores_before_generation_and_preserves_receipt_history(self):
        self.convert()
        baseline = migration.inspect(self.lab)
        result = self.apply()
        after_file = self.current_db()
        proof = migration.control(self.lab, "rollback")["approvalSha256"]
        old = migration.control(self.lab, "rollback", proof)
        self.assertEqual("ROLLED_BACK", old["status"])
        self.assertEqual("ALREADY_ROLLED_BACK", migration.control(self.lab, "rollback", proof)["status"])
        self.assertEqual(baseline["fingerprint"], migration.inspect(self.lab)["fingerprint"])
        self.assertTrue(after_file.exists())
        self.assertEqual(result["manifestSha256"], old["receipt"]["manifestSha256"])

    def test_cutover_is_explicit_and_stale_rollback_cannot_discard_new_gameplay(self):
        self.convert()
        self.apply()
        rollback = migration.control(self.lab, "rollback")["approvalSha256"]
        proof = migration.control(self.lab, "cutover")["approvalSha256"]
        with self.assertRaisesRegex(ValueError, "Approval"):
            migration.control(self.lab, "cutover", rollback)
        actual = migration.control(self.lab, "cutover", proof)
        self.assertEqual(2, actual["state"]["epoch"])
        with closing(migration.db_connect(self.current_db())) as db:
            db.execute("UPDATE cloud_continuities SET revision=revision+1")
        self.assertEqual("ALREADY_COMMITTED", migration.control(self.lab, "cutover", proof)["status"])
        self.assertEqual("ALREADY_IMPORTED", migration.apply(self.lab, self.bundle, "old")["status"])
        with self.assertRaisesRegex(ValueError, "subsequently committed"):
            migration.control(self.lab, "rollback", rollback)
        self.assertEqual(1, migration.inspect(self.lab)["state"]["revision"])

    def test_rollback_refuses_modified_prior_generation_or_revised_staging(self):
        self.convert()
        before = self.current_db()
        self.apply()
        proof = migration.control(self.lab, "rollback")["approvalSha256"]
        with closing(migration.db_connect(before)) as db:
            db.execute("UPDATE cloud_continuities SET revision=1")
        with self.assertRaisesRegex(ValueError, "Original staging generation changed"):
            migration.control(self.lab, "rollback", proof)
        with closing(migration.db_connect(self.current_db())) as db:
            db.execute("UPDATE cloud_continuities SET revision=1")
        with self.assertRaisesRegex(ValueError, "Staging changed"):
            migration.control(self.lab, "cutover")

    def test_tampered_bytes_and_semantically_inconsistent_source_rejected(self):
        self.convert()
        package = migration.read_bundle(self.bundle)
        wallet = package["rows"]["cloud_entities"][1]
        wallet["data_json"] = wallet["data_json"].replace('"gp":25', '"gp":35')
        body = migration.bundle_contents(self.tenant, package["manifest"]["source"], package["rows"], package["sources"], {})
        changed = self.base / "changed.zip"
        with self.assertRaisesRegex(ValueError, "independently mapped"):
            migration.write_bundle(changed, body)
        self.assertFalse(changed.exists())
        body["rows/cloud_entities.json"] += b" "
        with zipfile.ZipFile(changed, "w") as archive:
            for key, v in body.items():
                archive.writestr(key, v)
        with self.assertRaisesRegex(ValueError, "(digest|metadata) mismatch"):
            migration.inspect(self.lab, changed)
        self.assertEqual([], self.rows()["cloud_entities"])

    def test_partial_or_cross_continuity_import_never_activates_staging(self):
        self.convert()
        other = self.base / "other"
        migration.init(other, self.tenant[0], "other-continuity")
        with self.assertRaisesRegex(ValueError, "continuity differs"):
            migration.inspect(other, self.bundle)
        old = self.current_db()
        intent = migration.inspect(self.lab, self.bundle)["approvalSha256"]
        with patch.object(migration, "install_objects", side_effect=OSError("interrupted")):
            with self.assertRaisesRegex(OSError, "interrupted"):
                migration.apply(self.lab, self.bundle, intent)
        self.assertEqual(old, self.current_db())
        self.assertEqual("STAGED", self.apply()["status"])

    def test_separate_complete_and_character_envelopes_inventory_all_declared_scopes(self):
        playable = {"format": "astravar-reforge", "schema": 2, "exportedAt": 123, "characters": [],
                    "changes": [], "rolls": [], "drafts": []}
        connected = {"format": "astravar-connected-campaign", "schema": 1, "exportedAt": 123,
                     "campaign": self.native, "playable": playable, "authority": {}}
        self.source.write_bytes(encode(connected))
        report, entities, _ = legacy_review(self.source, *self.tenant, [self.wallet["id"]])
        self.assertFalse(report["blockers"])
        self.assertEqual(2, len(entities))
        self.assertTrue(report["families"]["playable.drafts"]["declared"])
        self.assertFalse(report["families"]["authority.encounters"]["declared"])

    def test_composite_native_ability_identity_and_future_store_preservation(self):
        self.native["records"]["characterAbilities"] = [{"profileId": actor, "key": "STR", "score": 11}
                                                     for actor in ("first", "second")]
        self.source.write_bytes(encode(self.native))
        report, _, _ = legacy_review(self.source, *self.tenant, [self.wallet["id"]])
        abilities = [r["identity"] for r in report["records"] if r["family"] == "records.characterAbilities"]
        self.assertEqual([["first", "STR"], ["second", "STR"]], abilities)
        with closing(migration.db_connect(self.current_db())) as db:
            db.execute("CREATE TABLE cloud_new_subsystem(id TEXT PRIMARY KEY)")
        with self.assertRaisesRegex(ValueError, "Unclassified"):
            migration.export(self.lab, self.bundle)

    def test_source_bound_membership_known_world_fact_and_project_preview(self):
        common = {"revision": 3, "updatedAt": 50, "source": "synthetic ledger row"}
        records = self.native["records"]
        records.update(entities=[{**common, "id": "bench", "name": "Workbench", "kind": "Location", "notes": "Known location."}],
                       facts=[{**common, "id": "mark", "entityId": "bench", "detail": "A brass marker is visible.",
                               "category": "Markings", "evidenceStatus": "Confirmed"}],
                       projects=[{**common, "id": "vise", "title": "Bench vise", "description": "Test clamping.", "status": "Proposed"}])
        self.source.write_bytes(encode(self.native))
        report, _, _ = legacy_review(self.source, *self.tenant, [self.wallet["id"]])
        tokens = [r["scopeReviewSha256"] for r in report["records"] if "scopeReviewSha256" in r]
        self.assertEqual(3, len(tokens))
        with self.assertRaisesRegex(ValueError, "remain held"):
            self.convert()
        migration.convert(self.source, self.bundle, self.tenant, [self.wallet["id"]], tokens)
        self.apply()
        self.assertEqual(5, len(self.rows()["cloud_entities"]))
        self.assertEqual(("mark", "known_about", "bench"), tuple(self.rows()["cloud_links"][0][k] for k in ("from_id", "relation", "to_id")))
        self.assertEqual([], self.rows()["cloud_receipts"])
        self.native["records"]["facts"][0]["detail"] = "Corrected detail."
        self.source.write_bytes(encode(self.native))
        with self.assertRaisesRegex(ValueError, "Stale"):
            legacy_review(self.source, *self.tenant, [self.wallet["id"]], tokens)

    def test_genesis_continuity_metadata_is_scoped_and_archived_cutover_is_held(self):
        campaign = {"id": self.tenant[1], "name":"Synthetic campaign", "notes":None,
                    "revision":3, "createdAt":None, "updatedAt":89, "commandId":"legacy-command", "archived":True}
        playable = {"format":"astravar-reforge", "schema":2, "exportedAt":100, "characters":[], "changes":[], "rolls":[]}
        connected = {"format":"astravar-connected-campaign", "schema":1, "exportedAt":102,
                     "campaign":self.native, "playable":playable, "authority":{"campaigns":[campaign]}}
        self.source.write_bytes(encode(connected))
        self.convert()
        self.apply()
        meta = next(r for r in self.rows()["cloud_entities"] if r["kind"] == "continuity_profile")
        self.assertEqual(meta["id"],self.tenant[1])
        self.assertEqual("archived",meta["life"])
        data = json.loads(meta["data_json"])
        self.assertEqual({"sourceCommandId":"legacy-command","sourceUpdatedAt":89,"createdAt":None},
                         {k:data[k] for k in ("sourceCommandId","sourceUpdatedAt","createdAt")})
        with self.assertRaisesRegex(ValueError, "Archived continuity"):
            migration.control(self.lab,"cutover")

    def test_receipt_segment_corruption_or_erasure_without_matching_deletion_is_refused(self):
        body = encode({"reason":"Fixture result."}).decode()
        digest = sha(body.encode())
        rows = {table: [] for table in migration.TABLES}
        rows["cloud_receipts"] = [{"command_id":"r", "result_json":encode({"evidenceVersion":1, "parts":{"result":digest}, "hadChanges":False, "changeCount":0})}]
        with self.assertRaisesRegex(ValueError, "Missing"):
            migration.verify_evidence(rows)
        rows["cloud_evidence_segments"] = [{"command_id":"r", "part":"result", "payload_json":body, "digest":digest}]
        migration.verify_evidence(rows)
        rows["cloud_evidence_segments"][0]["payload_json"] += " "
        with self.assertRaisesRegex(ValueError, "digest mismatch"):
            migration.verify_evidence(rows)
        rows["cloud_evidence_segments"] = []
        rows["cloud_evidence_erased"] = [{"command_id":"r", "part":"result", "digest":digest, "entity_id":"old", "deleted_revision":5}]
        with self.assertRaisesRegex(ValueError, "deletion marker"):
            migration.verify_evidence(rows)
        rows["cloud_tombstones"] = [{"id":"old", "deleted_revision":5}]
        migration.verify_evidence(rows)

    def test_worker_mcp_commit_cold_restore_new_grant_and_authoritative_readback(self):
        self.convert()
        self.apply()
        cutover = migration.control(self.lab, "cutover")["approvalSha256"]
        migration.control(self.lab, "cutover", cutover)
        script = Path(__file__).with_name("ascension_migrate_worker_fixture.mjs")
        def run_worker(folder, action, epoch):
            result = subprocess.run(["node", str(script), str(self.current_db(folder)), action, str(epoch)],
                                    text=True, capture_output=True, timeout=30)
            self.assertEqual(0, result.returncode, result.stderr)
            return json.loads(result.stdout)
        committed = run_worker(self.lab, "seed", 2)
        package = self.base / "cold.zip"
        migration.export(self.lab, package)
        rebuilt = self.base / "cold"
        migration.init(rebuilt, *self.tenant)
        migrate = migration.inspect(rebuilt, package)["approvalSha256"]
        migration.apply(rebuilt, package, migrate)
        proof = migration.control(rebuilt, "cutover")["approvalSha256"]
        migration.control(rebuilt, "cutover", proof)
        self.assertEqual(committed, run_worker(rebuilt, "read", 3))
        grants = self.rows(rebuilt)["cloud_grant_audit"]
        self.assertEqual([2, 3], sorted(r["epoch"] for r in grants))
        self.assertEqual(2, len({r["event_id"] for r in grants}))
        second = self.base / "next-generation.zip"
        migration.export(rebuilt, second)
        final = self.base / "fourth-epoch"
        migration.init(final, *self.tenant)
        migrate = migration.inspect(final, second)["approvalSha256"]
        migration.apply(final, second, migrate)
        proof = migration.control(final, "cutover")["approvalSha256"]
        self.assertEqual(4,migration.control(final,"cutover",proof)["state"]["epoch"])
        self.assertEqual(committed,run_worker(final,"read",4))

    def test_native_weapon_zip_hash_and_unindexed_asset(self):
        original = b'{"campaign":{}}'
        index = b''
        head = {"format": "astravar-complete-history", "version": 1, "indexSha256": sha(index),
                "workingSha256": sha(original), "archiveBytes": 0, "archiveCount": 0, "originalBytes": 0, "originalFileCount": 0}
        with zipfile.ZipFile(self.bundle, "w") as z:
            z.writestr("manifest.json", encode(head)); z.writestr("backup.json", original); z.writestr("archives.ndjson", index)
        result = weapon_review(self.bundle)
        self.assertEqual(0, result["archiveCount"])
        self.assertTrue(result["blockers"])
        with zipfile.ZipFile(self.bundle, "a") as z: z.writestr("extra", b"stray")
        with self.assertRaisesRegex(ValueError, "Unexpected"):
            weapon_review(self.bundle)

    def test_character_transfer_inventory_keeps_currency_history_non_spendable(self):
        value = {"format":"astravar-notion-character-transfer-v2", "transferId":"fixture-transfer",
                 "capturedDate":"2026-01-01", "character":{"name":"Fixture"}, "inventory":[{"sourceId":"fixture-part"}],
                 "currency":[{"eventId":"past-income", "gpEquivalentDelta":17}], "provisionalGpEquivalent":17,
                 "walletVerified":False, "reviewedSpellbook":{"classVersion":"pinned", "spells":[]}}
        self.source.write_bytes(encode(value))
        report, entities, raw = legacy_review(self.source, *self.tenant)
        self.assertEqual({"inventory":1,"currency":1,"spells":0}, report["counts"])
        self.assertEqual([], entities)
        self.assertEqual(sha(raw), report["sha256"])
        self.assertTrue(report["blockers"])
        with self.assertRaisesRegex(ValueError, "remain held"):
            migration.convert(self.source, self.bundle, self.tenant, [])

    def test_recovery_encryption_wrong_key_damage_and_exact_package_restore(self):
        from cryptography.fernet import Fernet
        self.convert()
        key, other = self.base / "key", self.base / "different-key"
        for file in (key, other):
            file.write_bytes(Fernet.generate_key())
            file.chmod(0o600)
        protected, opened = self.base / "backup.protected", self.base / "restored.zip"
        evidence = migration.protect(self.bundle, protected, key)
        self.assertNotIn(b"shared-fixture", protected.read_bytes())
        for setup in ("wrong-key", "corrupt"):
            bad = self.base / setup
            bad.write_bytes(protected.read_bytes() + b"invalid" if setup == "wrong-key" else protected.read_bytes()[:100])
            with self.assertRaisesRegex(ValueError, "authentication failed"):
                migration.protect(bad, opened, other if setup == "wrong-key" else key, decrypt=True)
            self.assertFalse(opened.exists())
        read = migration.protect(protected, opened, key, decrypt=True)
        self.assertEqual(evidence["manifestSha256"], read["manifestSha256"])
        self.assertEqual(self.bundle.read_bytes(), opened.read_bytes())
        self.assertEqual(0, opened.stat().st_mode & 0o077)

    def test_canonical_cold_recovery_media_tombstones_receipts_retry_and_pending_review(self):
        script = """import {encodeMediaPng,inspectMediaPng} from './backend/owner-bridge/cloud-media-codec.mjs';
            const raw=await encodeMediaPng(180,1,new Uint8Array(180*4).fill(173));
            const {meta,thumbnail}=await inspectMediaPng(raw);
            console.log(JSON.stringify([meta,Buffer.from(raw).toString('base64'),Buffer.from(thumbnail).toString('base64')]));"""
        media, encoded, preview = json.loads(subprocess.check_output(["node", "--input-type=module", "-e", script], cwd=migration.ROOT))
        full, thumb = base64.b64decode(encoded), base64.b64decode(preview)
        media["uploadId"] = "image-upload-fixture"
        with closing(migration.db_connect(self.current_db())) as db:
            db.execute("BEGIN")
            db.execute("INSERT INTO cloud_entities (owner_id,continuity_id,id,kind,actor_id,title,data_json,life,revision) VALUES(?,?,?,'media_asset',NULL,'Fixture',?,'active',1)",
                       (*self.tenant, "art", encode(media).decode()))
            db.execute("UPDATE cloud_continuities SET authority='cloud',epoch=2,revision=1")
            db.execute("INSERT INTO cloud_tombstones VALUES(?,?,'old','item_instance',1)", self.tenant)
            db.execute("INSERT INTO cloud_changes VALUES(?,?,1,0,'old','item_instance',NULL,'delete')", self.tenant)
            for cmd, status in (("previous", "COMMITTED"), ("review", "PENDING_APPROVAL")):
                db.execute("INSERT INTO cloud_requests(owner_id,continuity_id,command_id,subject,digest,request_json,access_json,status,submitted_at) VALUES(?,?,?,?,?,'{}','[]',?,102)",
                           (*self.tenant, cmd, "fixture-principal", "a"*64, status))
            db.execute("INSERT INTO cloud_receipts VALUES(?,?,'previous','transaction-id','COMMITTED',2,1,'{}','[]',103)", self.tenant)
            db.execute("INSERT INTO cloud_grants VALUES(?,?,'fixture-principal','[]','[]','[]',999999,0,1)", self.tenant)
            db.commit()
        with self.assertRaisesRegex(ValueError, "regular local source"):
            migration.export(self.lab, self.bundle)
        relative = Path("ascension/media") / sha(encode([*self.tenant, media["uploadId"], sha(full)]))
        objects = self.lab / "objects" / relative
        objects.mkdir(parents=True)
        (objects / "original.png").write_bytes(full)
        (objects / "thumbnail.png").write_bytes(thumb)
        result = migration.export(self.lab, self.bundle)
        self.assertEqual(2, result["media"])
        self.assertIn("standing grants", result["excluded"])
        dest = self.base/"restored"
        migration.init(dest, *self.tenant)
        proof = migration.inspect(dest, self.bundle)["approvalSha256"]
        migration.apply(dest, self.bundle, proof)
        self.assertEqual(self.rows(), self.rows(dest))
        self.assertEqual("staged", migration.inspect(dest)["state"]["authority"])
        with self.assertRaisesRegex(ValueError, "pending cloud requests"):
            migration.control(dest, "cutover")
        with closing(migration.db_connect(self.current_db(dest))) as db:
            self.assertEqual(0, db.execute("SELECT count(*) FROM cloud_grants").fetchone()[0])
            self.assertRaisesRegex(sqlite3.IntegrityError, "IMMUTABLE", db.execute, "DELETE FROM cloud_receipts")
        decision = migration.control(dest, "cutover", carry_pending=True)
        self.assertEqual(["review"], decision["preservedPending"])
        migration.control(dest, "cutover", decision["approvalSha256"], carry_pending=True)
        self.assertEqual("PENDING_APPROVAL", next(row["status"] for row in self.rows(dest)["cloud_requests"] if row["command_id"] == "review"))
        self.assertEqual(full, (dest / "objects" / relative / "original.png").read_bytes())
        self.assertEqual(thumb, (dest / "objects" / relative / "thumbnail.png").read_bytes())


if __name__ == "__main__":
    unittest.main()
