#!/usr/bin/env bash
set -euo pipefail
serial=emulator-5584
art=validation/visual-original
mkdir -p "$art"
cd "$GITHUB_WORKSPACE"
test -z "$(adb devices | awk '$1=="emulator-5584" {print $1}')"
test -c /dev/kvm
sudo chmod 666 /dev/kvm
export ANDROID_AVD_HOME="$RUNNER_TEMP/visual-avd" ANDROID_USER_HOME="$RUNNER_TEMP/visual-user"
mkdir -p "$ANDROID_AVD_HOME" "$ANDROID_USER_HOME"
printf 'no\n' | avdmanager create avd --force -n AstravarHpCI -p "$ANDROID_AVD_HOME/AstravarHpCI.avd" -k 'system-images;android-37.0;default;x86_64' --device pixel_6
"$ANDROID_HOME/emulator/emulator" -avd AstravarHpCI -port 5584 -accel on -no-window -no-audio -no-snapshot -no-boot-anim -writable-system -gpu swiftshader -memory 4096 >"$RUNNER_TEMP/owned-visual-avd.log" 2>&1 &
guest=$!
cleanup() {
  local status=$?
  trap - EXIT
  if [[ "$status" != 0 ]] && [[ "$(adb -s "$serial" emu avd name 2>/dev/null | tr -d '\r' | head -1)" == AstravarHpCI ]]; then
    if test -f "$RUNNER_TEMP/visual-continuous.log"; then
      grep -E -m 45 'Abort message:|JNI DETECTED ERROR|Check failed:|Fatal signal|F DEBUG.*#[0-9]+.*\.so|F DEBUG.*TaskSnapshotPer' "$RUNNER_TEMP/visual-continuous.log" >"$art/guest-native-crash.txt" || true
      grep -Ei 'system_server|system server|systemui.*ANR|FATAL EXCEPTION|Zygote|Watchdog|Kernel panic|sys.powerctl|init.*killing|boot completed|starting service.*zygote|lowmemorykiller|Fatal signal' "$RUNNER_TEMP/visual-continuous.log" | tail -240 >"$art/failed-system-service.txt" || true
      cp "$RUNNER_TEMP/owned-visual-avd.log" "$art/failed-emulator.txt" || true
    fi
    adb -s "$serial" shell logcat -d -s ActivityManager:E 'AndroidRuntime:E' 'Watchdog:E' > "$art/failed-guest-services.txt" || true
    adb -s "$serial" shell dumpsys window displays | awk '/mCurrentFocus/ {print}' > "$art/failed-guest-focus.txt" || true
  fi
  kill "$guest" 2>/dev/null || true
  wait "$guest" 2>/dev/null || true
  exit "$status"
}
trap cleanup EXIT
timeout 90 adb -s "$serial" wait-for-device
for n in $(seq 1 100); do
  kill -0 "$guest"
  if [[ "$(adb -s "$serial" shell getprop sys.boot_completed | tr -d '\r')" == 1 ]]; then break; fi
  sleep 2
done
test "$(adb -s "$serial" emu avd name | tr -d '\r' | head -1)" = AstravarHpCI
test "$(adb -s "$serial" shell getprop ro.kernel.qemu | tr -d '\r')" = 1
test "$(adb -s "$serial" shell getprop ro.build.version.sdk | tr -d '\r')" = 37
astravar_serial="$serial"
astravar_output="$art"
astravar_emulator_pid="$guest"
astravar_verified_emulator=1
astravar_expected_api=37
source .github/qa-api37-visual/official-guest-setup.sh
grep -F 'Selecting Vulkan device: SwiftShader Device' "$RUNNER_TEMP/owned-visual-avd.log" > "$art/guest-renderer.txt"
grep -F 'androidboot.hardware.vulkan=ranchu' "$RUNNER_TEMP/owned-visual-avd.log" >> "$art/guest-renderer.txt"
adb -s "$serial" logcat -b all -v threadtime >"$RUNNER_TEMP/visual-continuous.log" &
for k in window_animation_scale transition_animation_scale animator_duration_scale; do
  adb -s "$serial" shell settings put global "$k" 0
done
adb -s "$serial" install app/build/outputs/apk/debug/app-debug.apk
adb -s "$serial" install app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
icon() { python3 scripts/ci-android17-silent-icons.py "$serial" AstravarHpCI "$1"; }
original="$(icon read)"
case "$original" in true) restore=hide ;; false) restore=show ;; *) exit 1 ;; esac
comparison=0
python3 .github/qa-api37-visual/probe.py 2>&1 | tee "$art/comparison.txt" || comparison=$?
(
  trap 'r=$?; trap - EXIT; icon "$restore" || r=1; test "$(icon read)" = "$original" || r=1; exit "$r"' EXIT
  icon show
  timeout 360 adb -s "$serial" shell am instrument -w -r -e class 'com.yazsras.astravar.data.AscensionNotificationNativeTest#actualSmallEmblemInStatusBarAndShadeAcrossLightAndDarkThemes' \
    com.yazsras.astravar.v2uidebug.test/androidx.test.runner.AndroidJUnitRunner | tee "$art/notification.txt"
  grep -q '^OK (1 test)' "$art/notification.txt"
  ! grep -Eq 'FAILURES|INSTRUMENTATION_FAILED|Process crashed' "$art/notification.txt"
) 2>&1 | tee "$art/policy.txt"
test "$(icon read)" = "$original"
export ASTRAVAR_VERSION_CODE=59
bash scripts/ci-ascension-api37.sh 2>&1 | tee "$art/permanent-upgrade.txt"
component=$(adb -s "$serial" shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -p com.yazsras.astravar | tr -d '\r' | tail -1)
[[ "$component" == com.yazsras.astravar/* ]]
adb -s "$serial" shell am start -W -n "$component" | tee "$art/normal-launch.txt"
grep -q 'Status: ok' "$art/normal-launch.txt"
python3 scripts/ci-android17-silent-icons.py "$serial" AstravarHpCI ready 2>&1 | tee "$art/normal-focus.txt"
grep -Fxq 'Verified focused and accessible Android package com.yazsras.astravar' "$art/normal-focus.txt"
python3 - <<'PY'
import pathlib,subprocess,xml.etree.ElementTree as ET
serial="emulator-5584"
path="/data/local/tmp/ascension-notification-settings.xml"
root=ET.fromstring(subprocess.check_output(["adb","-s",serial,"exec-out","cat",path]))
assert any(n.get("package")=="com.yazsras.astravar" and "Ascension upgrade fixture" in (n.get("text","")+" "+n.get("content-desc","")) for n in root.iter("node"))
pathlib.Path("validation/visual-original/normal-actor.txt").write_text("Original seeded actor accessible in the focused optimized code 59 app\n")
PY
test "$comparison" = 0
if grep -q '"freshGuestOriginalHelper": "original switch unreachable"' "$art/original-status.json"; then
  grep -Fq 'selected Android Wait and verified dismissal' "$art/policy.txt"
fi
