import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location('ci_selection', Path(__file__).resolve().parents[1] / 'ci-select-tests.py')
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)
TESTS = ['app/src/test/java/' + x.replace('.', '/') + '.kt' for x in m.CRITICAL] + ['app/src/test/java/com/yazsras/astravar/ui/GenesisPagerTest.kt']

class CiSelectionTest(unittest.TestCase):
    def test_docs_retain_critical_without_full(self):
        result = m.plan(['docs/guide.md'], TESTS)
        self.assertFalse(result['full_required'])
        self.assertEqual(set(result['tests']), m.CRITICAL)

    def test_known_ui_selects_journey(self):
        result = m.plan(['app/src/main/java/com/yazsras/astravar/ui/GenesisPager.kt'], TESTS)
        self.assertFalse(result['full_required'])
        self.assertIn('com.yazsras.astravar.ui.GenesisPagerTest', result['tests'])

    def test_atomic_purchase_and_spell_resource_journeys_always_run(self):
        root = Path(__file__).resolve().parents[2]
        files = [p.relative_to(root) for p in (root / 'app/src/test').rglob('*.kt')]
        result = m.plan(['docs/guide.md'], files)
        self.assertIn('com.yazsras.astravar.ui.PartyPlayerFirstTest', result['tests'])
        self.assertIn('com.yazsras.astravar.ui.GenesisSpellReservoirUiTest', result['tests'])
        self.assertNotIn('com.yazsras.astravar.ui.ChatGptPromptManagementTest', result['tests'])

    def test_sensitive_and_unknown_inputs_require_full(self):
        for path in ['app/src/main/java/com/yazsras/astravar/data/CampaignConnection.kt', 'app/schemas/8.json', 'rules/src/main/Foo.kt', 'build.gradle.kts', '.github/workflows/android.yml', 'app/src/main/AndroidManifest.xml', 'backend/owner-bridge/index.mjs', 'app/src/main/java/com/yazsras/astravar/ui/NewFlow.kt']:
            with self.subTest(path=path):
                self.assertTrue(m.plan([path], TESTS)['full_required'])

    def test_changed_test_is_executed(self):
        result = m.plan(['app/src/test/java/com/yazsras/astravar/ui/NewTest.kt'], TESTS + ['app/src/test/java/com/yazsras/astravar/ui/NewTest.kt'])
        self.assertIn('com.yazsras.astravar.ui.NewTest', result['tests'])

    def test_shared_test_fixture_requires_full(self):
        self.assertTrue(m.plan(['app/src/test/java/com/yazsras/astravar/ui/AstravarComposeTestRule.kt'], TESTS)['full_required'])

    def test_ui_suffix_matches_covered_production_screen(self):
        files = TESTS + ['app/src/test/java/com/yazsras/astravar/ui/GenesisSpellReservoirUiTest.kt']
        result = m.plan(['app/src/main/java/com/yazsras/astravar/ui/GenesisSpellReservoir.kt'], files)
        self.assertFalse(result['full_required'])
        self.assertIn('com.yazsras.astravar.ui.GenesisSpellReservoirUiTest', result['tests'])
        self.assertTrue(m.CRITICAL.issubset(result['tests']))

    def test_ui_suffix_does_not_exempt_canonical_data_from_full_gate(self):
        files = TESTS + ['app/src/test/java/com/yazsras/astravar/ui/GenesisSpellReservoirUiTest.kt']
        result = m.plan(['app/src/main/java/com/yazsras/astravar/data/GenesisSpellReservoir.kt'], files)
        self.assertTrue(result['full_required'])
