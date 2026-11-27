#!/usr/bin/env bash
set -euo pipefail
evidence=validation/preservation-installed
mkdir -p "$evidence"
export ANDROID_AVD_HOME="$RUNNER_TEMP/qa-preservation-avd"
mkdir -p "$ANDROID_AVD_HOME"
serial=emulator-5578
package=com.yazsras.astravar.v2uidebug
run_id=${GITHUB_RUN_ID:?}
test -c /dev/kvm
sudo chmod 666 /dev/kvm
printf 'no\n' | avdmanager create avd -k 'system-images;android-37.0;default;x86_64' -n AscensionPreservation -p "$ANDROID_AVD_HOME/AscensionPreservation.avd" --device pixel_6
"$ANDROID_HOME/emulator/emulator" -avd AscensionPreservation -port 5578 -accel on -no-window -no-audio -no-snapshot -no-boot-anim -gpu swiftshader -memory 4096 >"$evidence/avd.log" 2>&1 &
emulator_pid=$!
python3 scripts/qa-ascension-preservation-gateway.py >"$RUNNER_TEMP/preservation-gateway.log" 2>&1 &
provider_pid=$!
qa() { adb -s "$serial" "$@"; }
cleanup() {
  code=$?
  trap - EXIT
  if [[ "$(qa emu avd name 2>/dev/null | tr -d '\r' | head -1)" == AscensionPreservation ]]; then
    qa pull "/sdcard/Android/data/$package/files/qa-preservation/." "$evidence/" >/dev/null 2>&1 || true
    if (( code != 0 )); then
      qa shell uiautomator dump /sdcard/qa-preservation-failure.xml >/dev/null 2>&1 && qa pull /sdcard/qa-preservation-failure.xml "$evidence/" >/dev/null 2>&1 || true
      qa exec-out screencap -p > "$evidence/failure-screen.png" 2>/dev/null || true
    fi
    qa emu kill >/dev/null 2>&1 || true
  fi
  kill "$provider_pid" "$emulator_pid" 2>/dev/null || true
  if (( code != 0 )); then sed -n '/Traceback/,$p' "$RUNNER_TEMP/preservation-gateway.log" | tail -20; fi
  exit "$code"
}
trap cleanup EXIT
timeout 120s adb -s "$serial" wait-for-device
for i in $(seq 1 120); do test "$(qa shell getprop sys.boot_completed | tr -d '\r')" = 1 && break; kill -0 "$emulator_pid"; sleep 2; done
test "$(qa shell getprop sys.boot_completed | tr -d '\r')" = 1
test "$(qa emu avd name | tr -d '\r' | head -1)" = AscensionPreservation
test "$(qa shell getprop ro.kernel.qemu | tr -d '\r')" = 1
test "$(qa shell getprop ro.build.version.sdk | tr -d '\r')" = 37
test "$(qa shell getprop ro.build.version.release | tr -d '\r')" = 17
qa shell getprop ro.build.fingerprint >"$evidence/guest-fingerprint.txt"
qa shell input keyevent 82
qa shell settings put global window_animation_scale 0
qa shell settings put global transition_animation_scale 0
qa shell settings put global animator_duration_scale 0
qa reverse tcp:38271 tcp:38271
test -z "$(qa shell pm path "$package" | tr -d '\r')"
qa install baseline58/app/build/outputs/apk/debug/app-debug.apk
qa install app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
method() {
  local klass="$1" logfile="$2"
  timeout -k 5s 230s adb -s "$serial" shell am instrument -w -r -e class "com.yazsras.astravar.data.$klass" \
     "$package.test/androidx.test.runner.AndroidJUnitRunner" | tee "$evidence/$logfile.txt"
  grep -Eq '^OK \(1 test\)' "$evidence/$logfile.txt"
}
method 'PreservationLegacySeed#createArchivedSource' code58-seed
uid() { qa shell dumpsys package "$package" | sed -n -E 's/^ *(userId|appId)=([0-9]+).*$/\2/p' | head -1 | tr -d '\r'; }
prior_uid=$(uid); test -n "$prior_uid"
qa shell am force-stop "$package"
qa install -r app/build/outputs/apk/debug/app-debug.apk | tee "$evidence/upgrade.txt"
grep -qx 'Success' "$evidence/upgrade.txt"
test "$prior_uid" = "$(uid)"
printf 'Version 58 to 59; retained Android UID %s\n' "$prior_uid" > "$evidence/uid.txt"
archive_result=0
method 'InstalledPreservationGate#upgradedArchiveIsAbsentFromFindButStillInOwnerBackupAndHistory' upgraded-archive || archive_result=$?
for i in $(seq 1 120); do grep -q RESTORED_PROVIDER_READY "$RUNNER_TEMP/preservation-gateway.log" && break; kill -0 "$provider_pid"; sleep 1; done
grep -q RESTORED_PROVIDER_READY "$RUNNER_TEMP/preservation-gateway.log"
forms_result=0
method 'InstalledPreservationGate#restoredActorAndFormPortraitsAndUnknownPoolSurviveInstalledReadback' restored-forms || forms_result=$?
test "$archive_result" -eq 0 && test "$forms_result" -eq 0
