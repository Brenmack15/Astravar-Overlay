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
    'ci-ascension-cloud-native.sh',
    'actualSmallEmblemInStatusBarAndShadeAcrossLightAndDarkThemes',
    'ci-ascension-api37.sh',
    'androidAccessibilityOptionsOpenWithoutRolling',
    'aSecondPackagePastesTheOriginalNamedRollFromTheSystemClipboard',
]


class NativeBootstrapGateTest(unittest.TestCase):
    def run_gate(self, failure='', enabled=True, initially_hidden=True):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        root = Path(directory.name)
        (root/'silent-policy.txt').write_text('true' if initially_hidden else 'false')
        env = dict(os.environ, ASTRAVAR_VERSION_CODE='54', TEST_OUTPUT=str(root), TEST_FAILURE=failure,
                   TEST_ENABLED='true' if enabled else 'false')
        shell = '''set -euo pipefail
astravar_output=$TEST_OUTPUT
astravar_serial=emulator-5584
astravar_bootstrap_gate=$TEST_ENABLED
python3() {
  if [[ "$1" != scripts/ci-android17-silent-icons.py ]]; then command python3 "$@"; return; fi
  [[ "$2" == emulator-5584 && "$3" == AstravarHpCI ]] || return 1
  printf '%s\\n' "$4" >>"$astravar_output/icon-policy-actions.txt"
  case "$4" in
    read) [[ "$TEST_FAILURE" != icon-read ]] && cat "$astravar_output/silent-policy.txt" ;;
    show)
      # A Settings click can commit before its following readback times out.
      printf false >"$astravar_output/silent-policy.txt"
      [[ "$TEST_FAILURE" != icon-show ]] ;;
    hide)
      [[ "$TEST_FAILURE" != icon-restore ]] || return 1
      printf true >"$astravar_output/silent-policy.txt" ;;
    *) return 1 ;;
  esac
}
run_test() {
  printf '%s\\n' "$1" >>"$astravar_output/executed.txt"
  [[ -z "$TEST_FAILURE" || "$1" != *"$TEST_FAILURE" ]]
}
source() {
  [[ "$1" == scripts/ci-ascension-cloud-native.sh ]]
  printf '%s\\n' "${1##*/}" >>"$astravar_output/executed.txt"
  [[ "$TEST_FAILURE" != "${1##*/}" ]]
}
bash() {
  [[ "$1" == scripts/ci-ascension-api37.sh ]]
  printf '%s\\n' "${1##*/}" >>"$astravar_output/executed.txt"
  [[ "$TEST_FAILURE" != "${1##*/}" ]] || return 1
  mkdir -p validation/ascension-api37
  printf 37 >validation/ascension-api37/sdk.txt
  printf 17 >validation/ascension-api37/release.txt
}
git() { printf '%s\\n' 0123456789012345678901234567890123456789; }
''' + GATE + '\nprintf continued >"$astravar_output/legacy-path.txt"\n'
        result = subprocess.run(['bash', '-c', shell], env=env, cwd=root, capture_output=True, text=True)
        executed = (root/'executed.txt').read_text().splitlines() if (root/'executed.txt').exists() else []
        return result, root, [line.split('#')[-1] for line in executed]

    def test_success_requires_all_native_categories_before_scope_evidence(self):
        result, root, methods = self.run_gate()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(METHODS, methods)
        self.assertEqual(['read', 'show', 'hide'], (root/'icon-policy-actions.txt').read_text().splitlines())
        self.assertEqual('true', (root/'silent-policy.txt').read_text())
        scope = json.loads((root/'SCOPE.json').read_text())
        self.assertEqual(37, scope['emulatorApi'])
        self.assertTrue(scope['android17'])
        self.assertTrue(scope['api37ForceStoppedCloudReconnect'])
        self.assertTrue(scope['frameworkRollOptionsWithoutRoll'])
        self.assertTrue(scope['crossPackageNamedRollPaste'])
        self.assertTrue(scope['nativePublicNotionImport'])
        self.assertTrue(scope['nativeCampaignHttpsAndIndependentReadback'])
        self.assertFalse(scope['actualOwnerSigningIdentityTestedInCi'])
        self.assertFalse(scope['realChatGptHostInvocation'])
        self.assertFalse(scope['physicalGalaxy'])
        self.assertFalse((root/'legacy-path.txt').exists())

    def test_notification_failure_checks_upgrade_without_marking_release_successful(self):
        result, root, methods = self.run_gate('actualSmallEmblemInStatusBarAndShadeAcrossLightAndDarkThemes')
        self.assertNotEqual(0, result.returncode)
        self.assertEqual(METHODS, methods)
        self.assertTrue((root/'validation/ascension-api37/sdk.txt').is_file())
        self.assertFalse((root/'SCOPE.json').exists())
        self.assertFalse((root/'legacy-path.txt').exists())

        self.assertEqual(['read', 'show', 'hide'], (root/'icon-policy-actions.txt').read_text().splitlines())
        self.assertEqual('true', (root/'silent-policy.txt').read_text())

    def test_owner_visible_silent_icons_stay_visible(self):
        result, root, methods = self.run_gate(initially_hidden=False)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(METHODS, methods)
        self.assertEqual(['read', 'show', 'show'], (root/'icon-policy-actions.txt').read_text().splitlines())
        self.assertEqual('false', (root/'silent-policy.txt').read_text())

    def test_visibility_failure_preserves_upgrade_evidence_and_blocks_success(self):
        method = 'actualSmallEmblemInStatusBarAndShadeAcrossLightAndDarkThemes'
        for failure, actions, checked, final in [
                ('icon-read', ['read'], False, 'true'),
                ('icon-show', ['read', 'show', 'hide'], False, 'true'),
                ('icon-restore', ['read', 'show', 'hide'], True, 'false')]:
            with self.subTest(failure=failure):
                result, root, methods = self.run_gate(failure)
                self.assertNotEqual(0, result.returncode)
                self.assertEqual(METHODS if checked else [m for m in METHODS if m != method], methods)
                self.assertEqual(actions, (root/'icon-policy-actions.txt').read_text().splitlines())
                self.assertEqual(final, (root/'silent-policy.txt').read_text())
                self.assertTrue((root/'validation/ascension-api37/sdk.txt').is_file())
                self.assertFalse((root/'SCOPE.json').exists())

    def test_other_failures_stop_dependents_and_cannot_write_success_evidence(self):
        for index, method in enumerate(METHODS):
            if method == 'actualSmallEmblemInStatusBarAndShadeAcrossLightAndDarkThemes':
                continue
            with self.subTest(method=method):
                result, root, methods = self.run_gate(method)
                self.assertNotEqual(0, result.returncode)
                self.assertEqual(METHODS[:index+1], methods)
                self.assertFalse((root/'SCOPE.json').exists())
                self.assertFalse((root/'legacy-path.txt').exists())

    def test_only_focused_lane_uses_android17_and_four_gib_guest(self):
        start = SCRIPT.index("astravar_image=")
        end = SCRIPT.index('test -c /dev/kvm', start)
        selector = SCRIPT[start:end]
        for enabled, image, gpu, memory, api in [
                ('true', 'system-images;android-37.0;default;x86_64', 'swiftshader', '-memory 4096', '37'),
                ('false', 'system-images;android-36;google_apis;x86_64', 'swiftshader', '', '36')]:
            shell = 'astravar_bootstrap_gate='+enabled+'\n'+selector+\
                'printf "%s|%s|%s|%s" "$astravar_image" "$astravar_gpu" "${astravar_memory_args[*]}" "$astravar_expected_api"'
            result = subprocess.run(['bash', '-c', shell], capture_output=True, text=True, check=True)
            self.assertEqual('|'.join((image, gpu, memory, api)), result.stdout)

    def test_historical_lane_retains_api36_with_supported_software_renderer(self):
        historical = (ROOT / 'scripts/ci-emulator.sh').read_text()
        self.assertIn('system-images;android-36;google_apis;x86_64', historical)
        self.assertIn('-gpu swiftshader >validation/emulator.log', historical)
        self.assertNotIn('swiftshader_indirect', historical)

    def test_legacy_runner_remains_available(self):
        result, root, methods = self.run_gate(enabled=False)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual([], methods)
        self.assertFalse((root/'SCOPE.json').exists())
        self.assertTrue((root/'legacy-path.txt').exists())


if __name__ == '__main__':
    unittest.main()
