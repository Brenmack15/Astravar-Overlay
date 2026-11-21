"""Exercise the real owned-AVD restart handshake without a device or credentials."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
SCRIPT = (ROOT / 'scripts/ci-owner-hp-native.sh').read_text()
HELPERS = SCRIPT[SCRIPT.index('verify_owned_emulator() {'):SCRIPT.index('export ANDROID_AVD_HOME=')]


class NativeAdbdRestartTest(unittest.TestCase):
    def run_restart(self, mode):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        output = Path(directory.name)
        shell = r'''set -euo pipefail
astravar_output=$TEST_OUTPUT
astravar_verified_emulator=1
astravar_emulator_pid=1234
astravar_serial=emulator-5584
astravar_expected_api=37
sleep() { :; }
kill() {
  [[ "$TEST_MODE" != crash || ! -f "$TEST_OUTPUT/root-called" ]]
}
timeout() { shift; "$@"; }
adb() {
  [[ "$1" == -s && "$2" == emulator-5584 ]] || return 1
  shift 2
  printf '%s\n' "$*" >>"$TEST_OUTPUT/commands.txt"
  case "$*" in
    'shell getprop ro.kernel.qemu') printf '1\n';;
    'emu avd name')
      if [[ "$TEST_MODE" == wrong_avd && -f "$TEST_OUTPUT/root-called" ]]; then
        printf 'UnrelatedDevice\nOK\n'
      else printf 'AstravarHpCI\nOK\n'; fi;;
    'shell getprop ro.build.version.sdk')
      if [[ "$TEST_MODE" == wrong_api && -f "$TEST_OUTPUT/root-called" ]]; then
        printf '36\n'
      else printf '37\n'; fi;;
    root)
      touch "$TEST_OUTPUT/root-called"
      case "$TEST_MODE" in
        closed|nonroot|crash|wrong_avd|wrong_api|reconnect_timeout)
          printf 'adb: unable to connect for root: closed\n' >&2; return 1;;
        empty) return 255;;
        root_timeout) return 124;;
        denied) printf 'adbd cannot run as root in production builds\n'; return 0;;
        other_error) printf 'unknown failure\n' >&2; return 1;;
        *) printf 'adbd is already running as root\n';;
      esac;;
    wait-for-device) [[ "$TEST_MODE" != reconnect_timeout ]] || return 124;;
    'shell id -u')
      if [[ "$TEST_MODE" == nonroot ]]; then printf '2000\n'; else printf '0\n'; fi;;
    *) return 1;;
  esac
}
''' + HELPERS + '\nroot_owned_emulator initial-root\nprintf verified >"$TEST_OUTPUT/success"\n'
        result = subprocess.run(['bash', '-c', shell], env=dict(os.environ, TEST_MODE=mode,
                                TEST_OUTPUT=str(output)), capture_output=True, text=True, timeout=5)
        return result, output

    def test_closed_or_missing_root_ack_requires_real_uid_zero_after_reconnect(self):
        for mode in ['ready', 'closed', 'empty']:
            with self.subTest(mode=mode):
                result, output = self.run_restart(mode)
                self.assertEqual(0, result.returncode, result.stderr)
                self.assertTrue((output/'success').exists())
                commands = (output/'commands.txt').read_text().splitlines()
                self.assertEqual(1, commands.count('root'))
                self.assertIn('wait-for-device', commands)
                self.assertIn('shell id -u', commands)
                self.assertGreaterEqual(commands.count('emu avd name'), 3)
                self.assertIn('Verified owned emulator adbd uid=0.', (output/'initial-root.txt').read_text())

    def test_denial_nonroot_timeout_guest_crash_or_identity_change_fail_closed(self):
        for mode in ['denied', 'nonroot', 'root_timeout', 'reconnect_timeout',
                     'crash', 'wrong_avd', 'wrong_api', 'other_error']:
            with self.subTest(mode=mode):
                result, output = self.run_restart(mode)
                self.assertNotEqual(0, result.returncode)
                self.assertFalse((output/'success').exists())
                self.assertNotIn('Verified owned emulator adbd uid=0.', (output/'initial-root.txt').read_text())

    def test_both_root_transitions_use_verified_helper(self):
        self.assertIn('root_owned_emulator initial-root', SCRIPT)
        self.assertIn('root_owned_emulator remounted-root', SCRIPT)
        self.assertEqual(1, SCRIPT.count('adb -s "$astravar_serial" root'))


if __name__ == '__main__':
    unittest.main()
