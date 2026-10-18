#!/usr/bin/env bash
# Disposable accelerated emulator only. No owner phone, real campaign, or owner signing key.
set -euo pipefail
: "${ANDROID_HOME:?SDK is required}"
: "${RUNNER_TEMP:?Use an isolated CI runner}"
mkdir -p validation
export ANDROID_AVD_HOME="$RUNNER_TEMP/astravar-21-avd"
export ANDROID_USER_HOME="$RUNNER_TEMP/astravar-21-android-user"
mkdir -p "$ANDROID_AVD_HOME" "$ANDROID_USER_HOME"
astravar_accel_args=()
if [[ "${ASTRAVAR_EMULATOR_ACCEL:-on}" == off ]]; then
  # Optional local software probe; CI requires acceleration by default.
  astravar_accel_args=(-accel off)
else
  test -c /dev/kvm
  sudo chmod 666 /dev/kvm
fi
printf 'no\n' | timeout -k 5s 40s avdmanager create avd --force -n Astravar21CI -p "$ANDROID_AVD_HOME/Astravar21CI.avd" -k 'system-images;android-36;google_apis;x86_64' --device pixel_6
"$ANDROID_HOME/emulator/emulator" -avd Astravar21CI -no-window -no-audio -no-snapshot -no-boot-anim -gpu swiftshader_indirect "${astravar_accel_args[@]}" >validation/emulator.log 2>&1 &
astravar_emulator_pid=$!
cleanup() {
  timeout 10s adb logcat -d >validation/emulator-logcat.txt 2>&1 || true
  timeout 15s adb pull /sdcard/Android/data/com.yazsras.astravar.target/files validation/emulator-evidence >/dev/null 2>&1 || true
  timeout 5s adb emu kill >/dev/null 2>&1 || true
  kill "$astravar_emulator_pid" 2>/dev/null || true
}
trap cleanup EXIT
timeout -k 5s 100s adb wait-for-device
astravar_booted=0
for astravar_attempt in $(seq 1 "${ASTRAVAR_BOOT_ATTEMPTS:-50}"); do
  if [ "$(timeout 3s adb shell getprop sys.boot_completed | tr -d '\r')" = 1 ]; then astravar_booted=1; break; fi
  sleep 2
done
test "$astravar_booted" = 1
timeout 10s adb shell input keyevent 82
for astravar_scale in window_animation_scale transition_animation_scale animator_duration_scale; do
  timeout 10s adb shell settings put global "$astravar_scale" 0
done
timeout -k 5s 40s adb install "${ASTRAVAR_UPDATE_BASE_APK:-validation/Astravar-2.1-CI-ONLY-code24.apk}"
timeout -k 5s 40s adb install overlay-target/build/outputs/apk/debug/overlay-target-debug.apk
timeout -k 5s 40s adb install overlay-target/build/outputs/apk/androidTest/debug/overlay-target-debug-androidTest.apk
run_class() {
  local astravar_class=$1 astravar_count=$2 astravar_log=$3
  timeout -k 5s 420s adb shell am instrument -w -r -e astravarPackage com.yazsras.astravar -e class "com.yazsras.astravar.$astravar_class" com.yazsras.astravar.target.test/androidx.test.runner.AndroidJUnitRunner | tee "$astravar_log"
  grep -q "^OK ($astravar_count test" "$astravar_log"
  ! grep -q 'FAILURES\|INSTRUMENTATION_FAILED\|Process crashed' "$astravar_log"
}
run_class OverlayInstrumentationTest 3 validation/native-overlay.txt
run_class UpdateFixtureInstrumentationTest 1 validation/native-update-fixture.txt
timeout 10s adb shell am force-stop com.yazsras.astravar
# Google APIs userdebug emulator supports adb root; the product remains non-debuggable.
# Hash every persisted store before and after Package Manager's in-place replacement,
# before application startup can checkpoint a WAL or update last-open metadata.
timeout 15s adb root
timeout 15s adb wait-for-device
snapshot() {
  local astravar_destination=$1
  adb shell 'cd /data/user/0/com.yazsras.astravar && for p in files databases shared_prefs no_backup; do if [ -d "$p" ]; then find "$p" -type f; fi; done | sort | while read p; do sha256sum "$p"; done' | tr -d '\r' >"$astravar_destination"
  test -s "$astravar_destination"
  grep -q 'databases/' "$astravar_destination"
}
snapshot validation/update-before.sha256
timeout -k 5s 40s adb install -r "${ASTRAVAR_UPDATE_NEXT_APK:-validation/Astravar-2.1-CI-ONLY-code25.apk}" | tee validation/update-install.txt
grep -q Success validation/update-install.txt
snapshot validation/update-after.sha256
diff -u validation/update-before.sha256 validation/update-after.sha256 >validation/update-store-diff.txt
timeout 10s adb shell dumpsys package com.yazsras.astravar >validation/updated-package.txt
grep -q 'versionCode=25' validation/updated-package.txt
run_class UpdatePreservationInstrumentationTest 1 validation/native-update-readback.txt
printf '%s\n' "PASS: matching certificate verified separately (${ASTRAVAR_TEST_SIGNER_LABEL:-CI-only}), version24→25, all persisted store bytes retained before startup; independent native readback verified fictional inventory and raw roll history." >validation/update-preservation-result.txt
