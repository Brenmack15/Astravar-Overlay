"""Prove fixture ownership, stopped-package controls and error propagation before native re-entry."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

SCRIPT=Path(__file__).resolve().parents[1]/"ci-ascension-cloud-native.sh"


class ForceStopGateTest(unittest.TestCase):
    def exercise(self, stopped="true", process="stopped", role="emulator-5584", fail=""):
        with tempfile.TemporaryDirectory() as folder:
            env=dict(os.environ,ROOT=folder,GATE=str(SCRIPT),STOP=stopped,PROCESS=process,SERIAL=role,FAIL=fail)
            s=r"""set -eu
astravar_bootstrap_gate=true
astravar_serial=$SERIAL
astravar_product=com.yazsras.astravar.v2uidebug
astravar_expected_api=37
astravar_output=$ROOT/evidence
astravar_private=$ROOT/private
mkdir -p "$astravar_private" "$astravar_output"
verify_owned_emulator() { return 0; }
node() { echo "$2" >> "$ROOT/events"; test "$2" != "$FAIL"; }
run_test() { echo "${1##*#}" >> "$ROOT/events"; [[ "$1" != *reconnectVerify* ]]; }
timeout() { shift; "$@"; }
adb() {
  case "$*" in
    *"shell dumpsys package"*) echo "User 0: installed=true stopped=$STOP" ;;
    *"command -v pidof"*) echo "$PROCESS" ;;
    *"shell am force-stop"*) echo stopped-command >> "$ROOT/events" ;;
    *) return 71 ;;
  esac
}
source "$GATE"
"""
            p=subprocess.run(["bash","-c",s],env=env,capture_output=True,text=True)
            events=Path(folder,"events")
            return p.returncode,events.read_text().splitlines() if events.exists() else []

    def test_host_never_commits_when_a_process_is_running_or_the_android_flag_is_clear(self):
        for stopped,proc in (("true","running"),("false","stopped")):
            status,events=self.exercise(stopped,proc)
            self.assertNotEqual(0,status)
            self.assertEqual(["setup","pairAndCachePreStopState","stopped-command"],events)

    def test_wrong_serial_is_rejected_before_any_gateway_or_device_action(self):
        self.assertEqual((1,[]),self.exercise(role="other"))

    def test_successful_stopping_allows_host_then_native_reentry(self):
        status,events=self.exercise()
        self.assertNotEqual(0,status)  # Last simulated instrument call intentionally signals failure.
        self.assertEqual(["setup","pairAndCachePreStopState","stopped-command","offline","reconnectVerifyEachReceiptAndCommitDeviceEdit"],events)

    def test_aborted_cloud_commit_cannot_advance_to_the_device(self):
        status,events=self.exercise(fail="offline")
        self.assertNotEqual(0,status)
        self.assertEqual(["setup","pairAndCachePreStopState","stopped-command","offline"],events)
