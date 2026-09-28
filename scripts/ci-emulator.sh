#!/usr/bin/env bash
set -euo pipefail
mkdir -p validation
test -c /dev/kvm
sudo chmod 666 /dev/kvm
printf 'no\n' | timeout -k 5s 40s avdmanager create avd --force -n AstravarCI -k 'system-images;android-36;google_apis;x86_64' --device pixel_6
"$ANDROID_HOME/emulator/emulator" -avd AstravarCI -no-window -no-audio -no-snapshot -no-boot-anim -gpu swiftshader_indirect >validation/emulator.log 2>&1 &
emulator_pid=$!
trap 'timeout 8s adb logcat -d >validation/logcat.txt 2>&1 || true; timeout 10s adb pull /sdcard/Android/data/com.yazsras.astravar.target/files validation/screenshots >/dev/null 2>&1 || true; timeout 5s adb emu kill >/dev/null 2>&1 || true; kill "$emulator_pid" 2>/dev/null || true' EXIT
timeout -k 5s 80s adb wait-for-device
booted=0
for n in $(seq 1 40); do
  if [ "$(timeout 3s adb shell getprop sys.boot_completed | tr -d '\r')" = 1 ]; then booted=1; break; fi
  sleep 2
done
test "$booted" = 1
timeout 10s adb shell input keyevent 82
timeout 10s adb shell settings put global window_animation_scale 0
timeout 10s adb shell settings put global transition_animation_scale 0
timeout 10s adb shell settings put global animator_duration_scale 0
timeout -k 5s 35s adb install "${ASTRAVAR_TEST_APK:-app/build/outputs/apk/debug/app-debug.apk}"
timeout -k 5s 35s adb install overlay-target/build/outputs/apk/debug/overlay-target-debug.apk
timeout -k 5s 35s adb install overlay-target/build/outputs/apk/androidTest/debug/overlay-target-debug-androidTest.apk
timeout -k 5s 240s adb shell am instrument -w -r -e astravarPackage "${ASTRAVAR_TEST_PACKAGE:-com.yazsras.astravar.debug}" com.yazsras.astravar.target.test/androidx.test.runner.AndroidJUnitRunner | tee validation/instrumentation.txt
grep -q '^OK (3 tests)' validation/instrumentation.txt
! grep -q 'FAILURES\|INSTRUMENTATION_FAILED\|Process crashed' validation/instrumentation.txt
