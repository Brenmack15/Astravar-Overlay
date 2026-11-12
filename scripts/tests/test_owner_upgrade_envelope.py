"""Synthetic transport only: never opens a release APK or signing key."""
import copy
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

from cryptography.exceptions import InvalidTag

SPEC = importlib.util.spec_from_file_location("envelope", Path(__file__).parents[1] / "owner_upgrade_envelope.py")
E = importlib.util.module_from_spec(SPEC); SPEC.loader.exec_module(E)


def fixture():
    config = json.loads((Path(__file__).parents[1] / "owner-upgrade-config.json").read_text())
    config["enabled"] = True
    for n, gate in enumerate(config["gates"], 10):
        gate.update(run_id=n, run_attempt=1)
    config["observer_artifact"].update(run_id=10, run_attempt=1, artifact_id=100,
        digest="a" * 64, reference_apk_sha256="b" * 64, reference_apk_size=20)
    data = {role: ("FICTIONAL APK BYTES " + role).encode() * 3 for role in E.ALL_ROLES}
    for role, value in data.items():
        config["apks"][role].update(sha256=E.digest(value), size=len(value))
        if role.startswith("observer"):
            config["apks"][role]["certificate_sha256"] = "d" * 64
    return config, data


class EnvelopeTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(); self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.config, self.data = fixture()
        self.context = E.binding(self.config, 123, 2, "c" * 40)
        self.recipient = E.create_recipient(self.config, 123, 2, "c" * 40,
            self.root / "private", self.root / "public", now=1000)
        self.files = {}
        for role in E.ROLES:
            path = self.root / (role + ".apk"); path.write_bytes(self.data[role]); self.files[role] = path
        self.transfer = self.root / "transfer"

    def encrypt(self):
        return E.encrypt(self.config, self.recipient, self.context, self.files, self.transfer, now=1001)

    def decrypt(self, recipient=None, context=None):
        return E.decrypt(self.config, recipient or self.recipient, context or self.context,
            self.root / "private/transport-private.pem", self.transfer, self.root / "decoded", now=1002)

    def save_manifest(self, value):
        (self.transfer / "manifest.json").write_bytes(E.canonical(value))

    def test_round_trip_and_publication_contains_no_plaintext(self):
        manifest = self.encrypt(); self.decrypt()
        for role in E.ROLES:
            self.assertEqual((self.root / "decoded" / (role + ".apk")).read_bytes(), self.data[role])
        self.assertEqual({p.name for p in (self.root / "public").iterdir()}, {"recipient.json"})
        for path in self.transfer.rglob("*"):
            if path.is_file():
                self.assertNotIn(b"PRIVATE KEY", path.read_bytes())
                self.assertNotIn(b"FICTIONAL APK BYTES", path.read_bytes())
        self.assertEqual(manifest["chunk_size"], 512 * 1024)

    def test_disabled_config_prevents_key_creation(self):
        self.config["enabled"] = False
        with self.assertRaisesRegex(ValueError, "disabled"):
            E.create_recipient(self.config, 456, 1, "c" * 40, self.root / "never", self.root / "never-public")
        self.assertFalse((self.root / "never").exists())

    def test_wrong_run_attempt_repository_or_receiver_commit(self):
        self.encrypt()
        for field, value in [("run_id", 124), ("run_attempt", 3), ("repository", "attacker/repo"),
                              ("receiver_commit", "d" * 40)]:
            context = {**self.context, field: value}
            with self.subTest(field=field), self.assertRaisesRegex(ValueError, "binding"):
                self.decrypt(context=context)
        self.assertFalse((self.root / "decoded").exists())

    def test_recipient_expiry_before_any_encryption(self):
        with self.assertRaisesRegex(ValueError, "expired"):
            E.encrypt(self.config, self.recipient, self.context, self.files, self.transfer, now=2800)
        self.assertFalse(self.transfer.exists())

    def test_expected_plaintext_digest_is_required(self):
        self.files["candidate"].write_bytes(b"different")
        with self.assertRaises(ValueError): self.encrypt()
        self.assertFalse(self.transfer.exists())

    def test_plaintext_extra_file_prohibits_publication(self):
        manifest = self.encrypt()
        (self.transfer / "candidate.apk").write_bytes(self.data["candidate"])
        with self.assertRaisesRegex(ValueError, "extra files"):
            E.validate_transfer_tree(self.transfer, manifest)
        with self.assertRaises(ValueError): self.decrypt()

    def test_manifest_path_traversal_rejected(self):
        manifest = self.encrypt(); manifest["files"][0]["chunks"][0]["path"] = "../baseline.apk"
        self.save_manifest(manifest)
        with self.assertRaisesRegex(ValueError, "path/order"): self.decrypt()

    def test_symlink_chunk_rejected(self):
        manifest = self.encrypt(); path = self.transfer / manifest["files"][0]["chunks"][0]["path"]
        path.unlink(); path.symlink_to(self.files["baseline"])
        with self.assertRaisesRegex(ValueError, "symlink"): self.decrypt()

    def test_ciphertext_tamper_rejected_even_if_public_hashes_recomputed(self):
        manifest = self.encrypt(); record = manifest["files"][0]; chunk = record["chunks"][0]
        path = self.transfer / chunk["path"]; data = bytearray(path.read_bytes()); data[0] ^= 1
        path.write_bytes(data); chunk["sha256"] = E.digest(data); record["ciphertext_sha256"] = E.digest(data)
        self.save_manifest(manifest)
        with self.assertRaises(InvalidTag): self.decrypt()
        self.assertFalse((self.root / "decoded").exists())

    def test_wrapped_key_tamper_rejected(self):
        manifest = self.encrypt(); manifest["wrapped_key"] = "A" * 512; self.save_manifest(manifest)
        with self.assertRaises(ValueError): self.decrypt()
        self.assertFalse((self.root / "decoded").exists())

    def test_duplicate_json_keys_rejected(self):
        with self.assertRaisesRegex(ValueError, "Duplicate"):
            E.strict_json(b'{"schema":1,"schema":1}')

    def test_role_reordering_rejected(self):
        manifest = self.encrypt(); manifest["files"].reverse(); self.save_manifest(manifest)
        with self.assertRaisesRegex(ValueError, "reordered"): self.decrypt()

    def test_config_and_gate_cannot_be_redefined_by_manifest(self):
        manifest = self.encrypt(); manifest["binding"]["config_sha256"] = "f" * 64; self.save_manifest(manifest)
        context = E.binding(self.config, 123, 2, "c" * 40)
        with self.assertRaisesRegex(ValueError, "binding"): self.decrypt(context=context)

    def test_owner_certificate_and_artifact_attempt_fail_closed(self):
        for mutate in (lambda c: c.update(owner_certificate_sha256="0" * 64),
                       lambda c: c["observer_artifact"].update(run_attempt=2),
                       lambda c: c["apks"]["candidate"].update(version_code=49)):
            config = copy.deepcopy(self.config); mutate(config)
            with self.assertRaises(ValueError): E.validate_config(config)

    def test_nonce_reuse_rejected(self):
        manifest = self.encrypt(); manifest["files"][1]["nonce"] = manifest["files"][0]["nonce"]
        self.save_manifest(manifest)
        with self.assertRaisesRegex(ValueError, "nonce"): self.decrypt()

    def test_chunk_limit_and_order(self):
        manifest = self.encrypt(); manifest["files"][0]["chunks"][0]["size"] = 2 * 1024 * 1024
        with self.assertRaisesRegex(ValueError, "bound"): E.manifest_paths(manifest)

    def test_no_partial_decryption_when_second_file_corrupt(self):
        manifest = self.encrypt(); chunk = manifest["files"][1]["chunks"][0]
        path = self.transfer / chunk["path"]; path.write_bytes(b"z" * path.stat().st_size)
        with self.assertRaises(ValueError): self.decrypt()
        self.assertFalse((self.root / "decoded").exists())


if __name__ == "__main__":
    unittest.main()
