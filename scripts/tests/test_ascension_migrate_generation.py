import json
from pathlib import Path
import sys
import tempfile
import unittest
import uuid

sys.path.insert(0, str(Path(__file__).resolve().parents[2]/"tools"))
import ascension_migrate as migration
import ascension_migrate_generation as generation


class OfflineProviderGeneration(unittest.TestCase):
    def setUp(self):
        ctx = tempfile.TemporaryDirectory()
        self.addCleanup(ctx.cleanup)
        self.base = Path(ctx.name)
        self.store = self.base/"store"
        migration.init(self.store, "isolated-test-owner", "qa-offline-proof")
        self.package = self.base/"input.zip"
        migration.export(self.store, self.package)
        self.sha = generation.file_hash(self.package)
        self.folder = self.base/"release"
        self.identities = (str(uuid.uuid4()), str(uuid.uuid4()), "qa-offline-files")

    def candidate(self):
        return generation.export(self.package, self.folder, "isolated", *self.identities, self.sha)

    def test_independent_readback_and_foreign_state(self):
        result = self.candidate()
        plan = json.loads((self.folder/"generation.json").read_bytes())
        self.assertGreater(len(plan["schemaObjects"]), len(plan["tables"]))
        actual = self.base/"separately-exported.zip"
        migration.export(self.store, actual)
        census = {k: plan[k] for k in ("generation", "d1DatabaseId", "privateBucket", "schemaSqlSha256")}
        census.update(routed=False, private=True, tenantCount=1, unpackedForeignRows=0, liveGrants=0, credentials=0, objectKeys=[])
        inventory = self.base/"observed.json"
        inventory.write_text(json.dumps(census))
        verify = lambda: generation.check_readback(self.folder, result["generationManifestSha256"], actual, inventory)
        self.assertEqual(verify()["authority"], "staged")
        for key, value in (("routed", True), ("credentials", 1), ("objectKeys", ["unclassified"]), ("d1DatabaseId", str(uuid.uuid4()))):
            with self.subTest(key=key):
                inventory.write_text(json.dumps({**census, key: value}))
                with self.assertRaisesRegex(ValueError, "isolation or full inventory"):
                    verify()
        inventory.write_text(json.dumps(census))
        (self.folder/"d1-batch.json").write_text("[]")
        with self.assertRaisesRegex(ValueError, "plan changed"):
            verify()

    def test_source_fingerprint_and_no_release_overwrite(self):
        with self.assertRaisesRegex(ValueError, "fingerprint"):
            generation.export(self.package, self.folder, "isolated", *self.identities, "0"*64)
        self.assertFalse(self.folder.exists())
        self.candidate()
        with self.assertRaisesRegex(ValueError, "new output directory"):
            self.candidate()
