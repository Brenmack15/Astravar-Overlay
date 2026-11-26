"""Prove fixture ownership, stopped-package controls and error propagation before native re-entry."""
import os
import json
import sqlite3
import runpy
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
    *"shell test -f"*) return 0 ;;
    *" pull "*) return 0 ;;
    *"shell am force-stop"*) echo stopped-command >> "$ROOT/events" ;;
    *) return 71 ;;
  esac
}
python3() { [[ "$1" == scripts/ascension-stopped-cache-readback.py ]]; }
source "$GATE"
"""
            p=subprocess.run(["bash","-c",s],env=env,capture_output=True,text=True)
            events=Path(folder,"events")
            return p.returncode,events.read_text().splitlines() if events.exists() else []

    def test_stopped_cache_is_read_independently_with_unknown_or_advanced_store_rejected(self):
        verify=runpy.run_path(str(SCRIPT.with_name("ascension-stopped-cache-readback.py")))["verify"]
        with tempfile.TemporaryDirectory() as home:
            path=Path(home,"cache.sqlite")
            db=sqlite3.connect(path)
            try:
                db.executescript("CREATE TABLE workspace(slot,continuity,epoch,revision,token); CREATE TABLE records(token,id,body);")
                db.execute("INSERT INTO workspace VALUES(1,'qa-api37-reconnect',1,4,'cached')")
                rows={"native-holder":{"abilities":{"INT":12}},"native-wallet":{"coins":{"cp":0,"sp":0,"ep":0,"gp":12,"pp":0}},
                      "native-pool":{"current":3}}
                db.executemany("INSERT INTO records VALUES('cached',?,?)",[(i,json.dumps({"data":d})) for i,d in rows.items()])
                db.commit()
                self.assertEqual(4,verify(path)["cachedRevision"])
                db.execute("UPDATE workspace SET revision=8");db.commit()
                with self.assertRaises(ValueError): verify(path)
                db.execute("UPDATE workspace SET revision=4")
                db.execute("INSERT INTO records VALUES('cached','native-key',?)",(json.dumps({"data":{}}),));db.commit()
                with self.assertRaises(ValueError): verify(path)
            finally:
                db.close()

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
