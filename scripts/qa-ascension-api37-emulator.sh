#!/usr/bin/env bash
# Hosted synthetic guest only. The project release workflow is independent.
set -euo pipefail
: "${ANDROID_HOME:?}"
: "${RUNNER_TEMP:?}"
export ASTRAVAR_VERSION_CODE=59
serial=emulator-5584
report=validation/ascension-api37
mkdir -p "$report"
export ANDROID_AVD_HOME
ANDROID_AVD_HOME=$(mktemp -d "$RUNNER_TEMP/ascension-upgrade-avd.XXXXXX")
export ANDROID_USER_HOME
ANDROID_USER_HOME=$(mktemp -d "$RUNNER_TEMP/ascension-upgrade-user.XXXXXX")
test -c /dev/kvm
sudo chmod 666 /dev/kvm
adb start-server
test -z "$(adb devices | awk 'NR > 1 && NF {print $1}')"
printf 'no\n' | timeout -k 5s 50s avdmanager create avd -n AstravarHpCI -p "$ANDROID_AVD_HOME/AstravarHpCI.avd" \
  -k 'system-images;android-37.0;default;x86_64' --device pixel_6
"$ANDROID_HOME/emulator/emulator" -avd AstravarHpCI -port 5584 -accel on -no-window -no-audio -no-snapshot \
  -no-boot-anim -gpu swiftshader -memory 4096 > "$report/emulator.log" 2>&1 &
emulator_pid=$!
owned=0
finish() {
  local outcome=$?
  trap - EXIT
  set +e
  if [[ "$owned" == 1 ]] && kill -0 "$emulator_pid"; then
    timeout 20s adb -s "$serial" pull /sdcard/Android/data/com.yazsras.astravar.target/files/. "$report/screenshots" >/dev/null 2>&1
    if (( outcome != 0 )); then
      timeout 8s adb -s "$serial" shell dumpsys activity activities > "$report/foreground-at-failure.txt"
      timeout 12s adb -s "$serial" exec-out screencap -p > "$report/failure-screen.png"
      python3 - "$report" <<'PY'
import pathlib, sys, xml.etree.ElementTree as ET
root = pathlib.Path(sys.argv[1])
for item in sorted(root.glob('screenshots/failure*.xml')):
    print(item.name)
    for node in ET.parse(item).getroot().iter('node'):
        if node.get('package') == 'com.yazsras.astravar':
            label = node.get('text') or node.get('content-desc')
            if label:
                print(node.get('class'), node.get('bounds'), label[:180])
PY
    fi
    timeout 10s adb -s "$serial" emu kill >/dev/null 2>&1
  fi
  kill "$emulator_pid" 2>/dev/null
  exit "$outcome"
}
trap finish EXIT
timeout -k 5s 150s adb -s "$serial" wait-for-device
for attempt in $(seq 1 100); do
  kill -0 "$emulator_pid"
  if [[ "$(timeout 4s adb -s "$serial" shell getprop sys.boot_completed | tr -d '\r')" == 1 ]]; then break; fi
  sleep 2
done
test "$(adb -s "$serial" shell getprop sys.boot_completed | tr -d '\r')" = 1
test "$(adb -s "$serial" emu avd name | tr -d '\r' | awk 'NR == 1 {print}')" = AstravarHpCI
test "$(adb -s "$serial" shell getprop ro.kernel.qemu | tr -d '\r')" = 1
test "$(adb -s "$serial" shell getprop ro.build.version.sdk | tr -d '\r')" = 37
test "$(adb -s "$serial" shell getprop ro.build.version.release | tr -d '\r')" = 17
owned=1
# adbd can close its client as it restarts. Only the observed uid passes this check.
timeout 15s adb -s "$serial" root || true
timeout 25s adb -s "$serial" wait-for-device
test "$(adb -s "$serial" shell id -u | tr -d '\r')" = 0
test "$(adb -s "$serial" emu avd name | tr -d '\r' | awk 'NR == 1 {print}')" = AstravarHpCI
for attempt in $(seq 1 50); do
  if [[ "$(timeout 4s adb -s "$serial" shell am get-started-user-state 0 | tr -d '\r')" == RUNNING_UNLOCKED ]]; then break; fi
  sleep 2
done
test "$(adb -s "$serial" shell am get-started-user-state 0 | tr -d '\r')" = RUNNING_UNLOCKED
adb -s "$serial" shell input keyevent KEYCODE_WAKEUP
adb -s "$serial" shell wm dismiss-keyguard
adb -s "$serial" shell cmd connectivity airplane-mode enable
test "$(adb -s "$serial" shell settings get global airplane_mode_on | tr -d '\r')" = 1
for scale in window_animation_scale transition_animation_scale animator_duration_scale; do
  adb -s "$serial" shell settings put global "$scale" 0
done
bash scripts/ci-ascension-api37.sh --package-only
cat "$report/package-result.json" "$report/readback.json"
