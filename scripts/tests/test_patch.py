import importlib.util
from pathlib import Path
import unittest

SCRIPT = Path(__file__).resolve().parents[1] / "apply_inventory_mvp.py"
spec = importlib.util.spec_from_file_location("apply_inventory_mvp", SCRIPT)
mod = importlib.util.module_from_spec(spec)
spec.loader.exec_module(mod)

FILENAME_APP = "app/src/main/java/com/yazsras/astravar/AstravarApp.kt"
FILENAME_SCREEN = "app/src/main/java/com/yazsras/astravar/ui/AppScreen.kt"
FILENAME_GRADLE = "app/build.gradle.kts"

SOURCE_FIXTURE = {
    FILENAME_APP: "package com.yazsras.astravar\nclass AstravarApp {\n    val preferences by lazy { Preferences(this) }\n}\n",
    FILENAME_SCREEN: (
        'when(morePage) {\n'
        '                            "Resources" -> ResourcesPage(current,history,actions)\n'
        '}\n'
        'fun ControlRoomPage() {\n'
        '        SectionCaption("SAFEKEEPING & SUPPORT")\n'
        '}\n'
    ),
    FILENAME_GRADLE: 'android { buildTypes { debug {applicationIdSuffix = ".debug"; versionNameSuffix = "-test" } } }'
}

class PatchTests(unittest.TestCase):
    def test_patch_app_creates_isolated_database_lazily(self):
        result = mod.revised_sources(SOURCE_FIXTURE)
        app = result[FILENAME_APP]
        self.assertIn('CampaignLedgerRepository(CampaignLedgerDb.open(this))', app)
        self.assertIn('val preferences by lazy { Preferences(this) }', app)

    def test_debug_only_navigation(self):
        screen = mod.revised_sources(SOURCE_FIXTURE)[FILENAME_SCREEN]
        self.assertIn('BuildConfig.DEBUG', screen)
        self.assertIn('CampaignLedgerPage(app.campaignLedger)', screen)
        self.assertIn('Campaign inventory · local beta', screen)
        self.assertIn('"Resources" -> ResourcesPage(current,history,actions)', screen)

    def test_debug_version_suffix_is_distinct(self):
        gradle = mod.revised_sources(SOURCE_FIXTURE)[FILENAME_GRADLE]
        self.assertIn('versionNameSuffix = "-inventory-beta"', gradle)
        self.assertIn('applicationIdSuffix = ".debug"', gradle)
        self.assertNotIn('versionNameSuffix = "-test"', gradle)

    def test_wrong_source_is_blocked(self):
        bad = {**SOURCE_FIXTURE, FILENAME_APP: "class AstravarApp {}"}
        with self.assertRaisesRegex(ValueError, 'Unexpected v1.7.0 source'):
            mod.revised_sources(bad)

    def test_duplicate_anchor_is_blocked(self):
        bad = dict(SOURCE_FIXTURE)
        bad[FILENAME_GRADLE] += 'versionNameSuffix = "-test"'
        with self.assertRaisesRegex(ValueError, 'matched 2 times'):
            mod.revised_sources(bad)

    def test_bundle_has_required_files(self):
        for relative in mod.ADDITIONS:
            self.assertTrue((mod.BUNDLE_ROOT / relative).is_file(), relative)

    def test_v2_network_is_debug_only_and_explicit(self):
        manifest = (mod.BUNDLE_ROOT / 'app/src/debug/AndroidManifest.xml').read_text()
        self.assertIn('android.permission.INTERNET', manifest)
        self.assertIn('usesCleartextTraffic="false"', manifest)
        self.assertIn('android:label="${astravarDebugLabel}"', manifest)
        main = (mod.BUNDLE_ROOT / 'app/src/main/AndroidManifest.xml').read_text()
        self.assertNotIn('android.permission.INTERNET', main)
        gradle = (mod.BUNDLE_ROOT / FILENAME_GRADLE).read_text()
        self.assertIn('applicationIdSuffix = ".v2uidebug"', gradle)
        self.assertIn('applicationIdSuffix = ".v2statdebug"', gradle)
        self.assertIn('Astravar 2.0 Stat Test', gradle)

if __name__ == "__main__": unittest.main()
