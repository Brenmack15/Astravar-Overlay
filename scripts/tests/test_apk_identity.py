"""Merged APK label policy keeps QA distinct without weakening release identity."""
import importlib.util
from pathlib import Path
import unittest


SPEC = importlib.util.spec_from_file_location(
    "inspect_release_apk", Path(__file__).resolve().parents[1] / "inspect_release_apk.py")
release = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(release)


class ApkIdentityTests(unittest.TestCase):
    def badging(self, label, *, debug=False, localized=None, launcher=None):
        lines = [f"application-label:'{label}'"]
        if localized is not None:
            lines.append(f"application-label-en:'{localized}'")
        launcher_label = label if launcher is None else launcher
        lines.append(f"launchable-activity: name='app.MainActivity' label='{launcher_label}' icon='res/icon.xml'")
        if debug:
            lines.append("application-debuggable")
        return "\n".join(lines)

    def test_current_qa_variants_have_distinct_verified_labels(self):
        for package, label in (("com.yazsras.astravar.v2uidebug", "Astravar QA"),
                               ("com.yazsras.astravar.v2statdebug", "Astravar Remote QA")):
            with self.subTest(package=package):
                self.assertEqual((label, True), release.verify_application_identity(
                    self.badging(label, debug=True, localized=label), package, 44))

    def test_qa_cannot_use_production_or_other_variant_label(self):
        for package, label in (("com.yazsras.astravar.v2uidebug", "Astravar"),
                               ("com.yazsras.astravar.v2uidebug", "Astravar Remote QA"),
                               ("com.yazsras.astravar.v2statdebug", "Astravar QA")):
            with self.subTest(package=package, label=label), self.assertRaises(ValueError):
                release.verify_application_identity(self.badging(label, debug=True), package, 44)

    def test_launcher_can_inherit_the_verified_application_label(self):
        for package, label, debug in ((release.PERMANENT_PACKAGE, "Astravar", False),
                                      ("com.yazsras.astravar.v2uidebug", "Astravar QA", True)):
            with self.subTest(package=package):
                self.assertEqual((label, debug), release.verify_application_identity(
                    self.badging(label, debug=debug, launcher=""), package, 44))

    def test_mixed_locale_or_launcher_override_is_rejected(self):
        for package, label, debug in ((release.PERMANENT_PACKAGE, "Astravar", False),
                                      ("com.yazsras.astravar.v2uidebug", "Astravar QA", True)):
            for overrides in ({"localized": "Imposter"}, {"localized": ""},
                              {"launcher": "Imposter"}):
                with self.subTest(package=package, overrides=overrides), self.assertRaises(ValueError):
                    release.verify_application_identity(self.badging(label, debug=debug, **overrides), package, 44)

    def test_permanent_package_keeps_strict_release_label_and_debug_policy(self):
        self.assertEqual(("Astravar", False), release.verify_application_identity(
            self.badging("Astravar"), release.PERMANENT_PACKAGE, 44, owner_signed=True))
        for label, debug in (("Astravar QA", False), ("Astravar Remote QA", False), ("Astravar", True)):
            with self.subTest(label=label, debug=debug), self.assertRaises(ValueError):
                release.verify_application_identity(self.badging(label, debug=debug), release.PERMANENT_PACKAGE, 44)

    def test_owner_mode_cannot_accept_a_qa_package(self):
        with self.assertRaises(ValueError):
            release.verify_application_identity(self.badging("Astravar QA", debug=True),
                                                "com.yazsras.astravar.v2uidebug", 44, owner_signed=True)

    def test_qa_identity_requires_debug_build_flag(self):
        with self.assertRaises(ValueError):
            release.verify_application_identity(self.badging("Astravar QA"),
                                                "com.yazsras.astravar.v2uidebug", 44)

    def test_unknown_package_cannot_opt_into_qa_label(self):
        with self.assertRaises(ValueError):
            release.verify_application_identity(self.badging("Astravar QA", debug=True),
                                                "com.yazsras.astravar.imposter", 44)

    def test_missing_merged_application_label_is_rejected(self):
        with self.assertRaises(ValueError):
            release.verify_application_identity("application-debuggable", "com.yazsras.astravar.v2uidebug", 44)

    def test_historical_debug_apks_keep_their_original_label(self):
        self.assertEqual(("Astravar", True), release.verify_application_identity(
            self.badging("Astravar", debug=True), "com.yazsras.astravar.v2uidebug", 43))


if __name__ == "__main__":
    unittest.main()
