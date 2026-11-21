"""Execute the focused gate's shell control flow with isolated fake native runs."""
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
SCRIPT = (ROOT / 'scripts/ci-owner-hp-native.sh').read_text()
START = SCRIPT.index('if [[ "$astravar_bootstrap_gate" == true ]]; then', SCRIPT.index('# No credentials appear'))
END = SCRIPT.index("run_test 'com.yazsras.astravar.data.LiveHpNativeHttpsTest#a", START)
GATE = SCRIPT[START:END]
METHODS = [
    'upgradeRepairsLegacyOaepAndPreservesCredentials',
    'freshRenewalAndSingleUseWorkWithWebCrypto',
    'invalidBundlesAndInterruptedPersistenceRemainRetryable',
    'publicContextApplyBackupReimportAndRollbackPreserveIdentity',
    'authenticatedAutoSyncCommitsAllRecordCategoriesAndVerifiesReceipts',
]


class NativeBootstrapGateTest(unittest.TestCase):
    def run_gate(self, failure='', enabled=True):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        root = Path(directory.name)
        env = dict(os.environ, ASTRAVAR_VERSION_CODE='53', TEST_OUTPUT=str(root), TEST_FAILURE=failure,
                   TEST_ENABLED='true' if enabled else 'false')
        shell = '''set -euo pipefail
astravar_output=$TEST_OUTPUT
astravar_bootstrap_gate=$TEST_ENABLED
run_test() {
  printf '%s\\n' "$1" >>"$astravar_output/executed.txt"
  [[ -z "$TEST_FAILURE" || "$1" != *"$TEST_FAILURE" ]]
}
git() { printf '%s\\n' 0123456789012345678901234567890123456789; }
''' + GATE + '\nprintf continued >"$astravar_output/legacy-path.txt"\n'
        result = subprocess.run(['bash', '-c', shell], env=env, capture_output=True, text=True)
        executed = (root/'executed.txt').read_text().splitlines() if (root/'executed.txt').exists() else []
        return result, root, [line.split('#')[-1] for line in executed]

    def test_success_requires_all_native_categories_before_scope_evidence(self):
        result, root, methods = self.run_gate()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(METHODS, methods)
        scope = json.loads((root/'SCOPE.json').read_text())
        self.assertTrue(scope['nativePublicNotionImport'])
        self.assertTrue(scope['nativeCampaignHttpsAndIndependentReadback'])
        self.assertFalse(scope['actualOwnerSigningIdentityTestedInCi'])
        self.assertFalse(scope['realChatGptHostInvocation'])
        self.assertFalse(scope['physicalGalaxy'])
        self.assertFalse((root/'legacy-path.txt').exists())

    def test_each_failure_stops_dependents_and_cannot_write_success_evidence(self):
        for index, method in enumerate(METHODS):
            with self.subTest(method=method):
                result, root, methods = self.run_gate(method)
                self.assertNotEqual(0, result.returncode)
                self.assertEqual(METHODS[:index+1], methods)
                self.assertFalse((root/'SCOPE.json').exists())
                self.assertFalse((root/'legacy-path.txt').exists())

    def test_legacy_runner_remains_available(self):
        result, root, methods = self.run_gate(enabled=False)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual([], methods)
        self.assertFalse((root/'SCOPE.json').exists())
        self.assertTrue((root/'legacy-path.txt').exists())


if __name__ == '__main__':
    unittest.main()
