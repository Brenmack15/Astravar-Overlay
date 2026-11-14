import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

SCRIPT = Path(__file__).resolve().parents[1] / "genesis-package-readback.py"
spec = importlib.util.spec_from_file_location("genesis_package_readback", SCRIPT)
readback = importlib.util.module_from_spec(spec)
spec.loader.exec_module(readback)
PACKAGE = "com.yazsras.astravar"


def dump(version=49, identity="appId=10234", *, legacy=False):
    # Android16 structure observed in hosted run42942460297, with fictional values.
    # Older dumps put userId and firstInstallTime at package scope instead.
    first_time = "firstInstallTime=2026-01-02 03:04:05"
    return f"""Permissions:
  Permission [unrelated.permission] (abc):
    uid=99999

Packages:
  Package [{PACKAGE}] (abc123):
    {identity}
    pkg=Package{{123abc {PACKAGE}}}
    versionCode={version} minSdk=26 targetSdk=36
    versionName=fictional-test
    {first_time if legacy else 'lastUpdateTime=2026-02-03 04:05:06'}
    User 0: installed=true hidden=false stopped=true notLaunched=false
      dataDir=/data/user/0/{PACKAGE}
      {'installReason=0' if legacy else first_time}

Queries:
  queryable via interaction:
    User 0:
      unrelated.package
"""


class GenesisPackageReadbackTest(unittest.TestCase):
    def verify(self, before=None, after=None):
        return readback.verify_upgrade(before or dump(), after or dump(50), PACKAGE)

    def test_android16_app_id_and_user0_install_time_preserve_upgrade_identity(self):
        result = self.verify()
        self.assertEqual((49, 50, 0, 10234), tuple(result[key] for key in
                         ("old_version", "new_version", "android_user_id", "android_uid")))
        self.assertTrue(result["same_first_install_time"])
        self.assertEqual(["appId"], result["after_identity_fields"])

    def test_legacy_user_id_and_package_install_time_are_supported(self):
        result = self.verify(dump(identity="userId=10234", legacy=True),
                             dump(50, identity="userId=10234", legacy=True))
        self.assertEqual(["userId"], result["before_identity_fields"])
        self.assertTrue(result["same_android_uid"])

    def test_installed_genesis50_to_genesis51_preserves_android_identity(self):
        result = readback.verify_upgrade(dump(50), dump(51), PACKAGE, old_version=50, new_version=51)
        self.assertEqual((50, 51), (result["old_version"], result["new_version"]))
        self.assertTrue(result["same_android_uid"])
        self.assertTrue(result["same_first_install_time"])
        with self.assertRaises(AssertionError):
            readback.verify_upgrade(dump(50), dump(50), PACKAGE, old_version=50, new_version=51)

    def test_legacy_to_current_format_and_crlf_preserve_the_same_identity(self):
        self.assertTrue(self.verify(dump(identity="userId=10234", legacy=True).replace("\n", "\r\n"),
                                    dump(50))["same_android_uid"])

    def test_both_identity_names_must_agree(self):
        result = self.verify(after=dump(50, identity="appId=10234\n    userId=10234"))
        self.assertEqual(["appId", "userId"], result["after_identity_fields"])
        with self.assertRaisesRegex(AssertionError, "appId and userId disagree"):
            self.verify(after=dump(50, identity="appId=10234\n    userId=10235"))

    def test_changed_uid_is_rejected_even_when_both_dumps_are_valid(self):
        with self.assertRaisesRegex(AssertionError, "changed Android UID"):
            self.verify(after=dump(50, identity="appId=10235"))

    def test_changed_install_time_is_rejected(self):
        with self.assertRaisesRegex(AssertionError, "changed firstInstallTime"):
            self.verify(after=dump(50).replace("2026-01-02 03:04:05", "2026-01-02 03:04:06"))

    def test_missing_duplicate_invalid_or_non_user0_identity_is_rejected(self):
        for identity in ("unknownId=10234", "appId=10234\n    appId=10234", "appId=ten",
                         "appId=100234", "appId=0", "appId=-1"):
            with self.subTest(identity=identity), self.assertRaises(AssertionError):
                self.verify(after=dump(50, identity=identity))

    def test_user0_installation_is_required_in_the_package_block(self):
        for text in (dump(50).replace("User 0: installed", "User 10: installed"),
                     dump(50).replace("installed=true", "installed=false"),
                     dump(50).replace("    User 0: installed", "    Other 0: installed")):
            with self.subTest(text=text), self.assertRaises(AssertionError):
                self.verify(after=text)

    def test_other_user_install_time_cannot_substitute_for_user0(self):
        changed = dump(50).replace("      firstInstallTime=", "    User 10: installed=true\n      firstInstallTime=")
        with self.assertRaisesRegex(AssertionError, "Missing package firstInstallTime for Android user 0"):
            self.verify(after=changed)

    def test_expected_package_cannot_be_borrowed_from_permissions_or_queries(self):
        with self.assertRaisesRegex(AssertionError, "Missing or ambiguous expected package"):
            self.verify(after=dump(50).replace(f"Package [{PACKAGE}]", "Package [unrelated.package]"))

    def test_ambiguous_package_or_user_blocks_are_rejected(self):
        for text in (dump(50).replace("Queries:", f"  Package [{PACKAGE}] (duplicate):\n    appId=10234\n\nQueries:"),
                     dump(50).replace("Queries:", "    User 0: installed=true\n\nQueries:")):
            with self.subTest(text=text), self.assertRaises(AssertionError):
                self.verify(after=text)

    def test_wrong_or_missing_versions_do_not_pass_as_upgrade(self):
        for text in (dump(49), dump(51), dump(50).replace("versionCode=50", "missingVersion=50")):
            with self.subTest(text=text), self.assertRaises(AssertionError):
                self.verify(after=text)

    def test_unknown_or_conflicting_install_times_are_rejected(self):
        for text in (dump(50).replace("2026-01-02 03:04:05", "unknown"),
                     dump(50).replace("lastUpdateTime=2026-02-03 04:05:06", "firstInstallTime=2026-03-04 05:06:07")):
            with self.subTest(text=text), self.assertRaises(AssertionError):
                self.verify(after=text)

    def test_cli_writes_the_verified_metadata_used_by_native_workflow(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            before = root / "before.txt"; before.write_text(dump())
            after = root / "after.txt"; after.write_text(dump(50))
            output = root / "result.json"
            subprocess.check_call([sys.executable, str(SCRIPT), "--before", str(before), "--after", str(after),
                                   "--package", PACKAGE, "--output", str(output)], stdout=subprocess.DEVNULL)
            self.assertEqual(self.verify(), json.loads(output.read_text()))
