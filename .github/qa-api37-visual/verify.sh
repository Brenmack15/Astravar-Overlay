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
"$ANDROID_HOME/emulator/emulator" -avd AstravarHpCI -port 5584 -accel on -no-window -no-audio -no-snapshot -no-boot-anim -gpu swiftshader -memory 4096 >"$RUNNER_TEMP/owned-visual-avd.log" 2>&1 &
guest=$!
cleanup() {
  local status=$?
  trap - EXIT
  if [[ "$status" != 0 ]] && [[ "$(adb -s "$serial" emu avd name 2>/dev/null | tr -d '\r' | head -1)" == AstravarHpCI ]]; then
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
adb -s "$serial" root || true
timeout 20 adb -s "$serial" wait-for-device
test "$(adb -s "$serial" shell id -u | tr -d '\r')" = 0
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
test "$comparison" = 0
if grep -q '"freshGuestOriginalHelper": "original switch unreachable"' "$art/original-status.json"; then
  grep -Fq 'selected Android Wait and verified dismissal' "$art/policy.txt"
fi
