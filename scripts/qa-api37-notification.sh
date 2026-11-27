#!/usr/bin/env bash
# A fresh, anonymous AOSP device and a single synthetic Android notification.
set -euo pipefail
dir=validation/api37-notification
mkdir -p "$dir"
qa_sdk="$ANDROID_HOME"
export ANDROID_AVD_HOME="$RUNNER_TEMP/notification-avd"
mkdir -p "$ANDROID_AVD_HOME"
avd=AscensionNotificationCI
serial=emulator-5576
device() { adb -s "$serial" "$@"; }
printf 'no\n' | avdmanager create avd -f -n "$avd" -k 'system-images;android-37.0;default;x86_64' --device pixel_6
"$qa_sdk/emulator/emulator" -avd "$avd" -port 5576 -accel on -no-window -no-audio -no-snapshot -no-boot-anim -gpu swiftshader_indirect > "$RUNNER_TEMP/notification-emulator.log" 2>&1 &
qemu_pid=$!
trap 'kill "$qemu_pid" 2>/dev/null || true' EXIT
timeout 140 adb -s "$serial" wait-for-device
for i in $(seq 1 100); do
  kill -0 "$qemu_pid"
  if test "$(device shell getprop sys.boot_completed | tr -d '\r')" = 1; then break; fi
  sleep 2
done
test "$(device shell getprop ro.kernel.qemu | tr -d '\r')" = 1
test "$(device shell getprop ro.build.version.sdk | tr -d '\r')" = 37
test "$(device emu avd name | tr -d '\r' | head -1)" = "$avd"
product=com.yazsras.astravar.v2uidebug
test -z "$(device shell pm path "$product" | tr -d '\r')"
device install "$RUNNER_TEMP/notification-target.apk"
device install "$RUNNER_TEMP/notification-tests.apk"
device shell input keyevent KEYCODE_WAKEUP
device shell wm dismiss-keyguard
device shell wm size
device shell wm density
for key in "global device_provisioned" "secure user_setup_complete" "global zen_mode"; do
  read -r table name <<< "$key"
  echo "Before $table $name: $(device shell settings get "$table" "$name")"
done
# The standard notification UI is not available to third-party apps before user setup.
device shell settings put global device_provisioned 1
device shell settings put secure user_setup_complete 1
test "$(device shell settings get global device_provisioned | tr -d '\r')" = 1
test "$(device shell settings get secure user_setup_complete | tr -d '\r')" = 1
device shell am start -W -a android.intent.action.MAIN -c android.intent.category.HOME | head -8
# Restrict saved diagnostics to notification visibility and this one disposable app.
(
  while ! test -e "$dir/collector-finished"; do
    echo "===== sample $(date -u +%T)"
    device shell dumpsys notification 2>/dev/null | awk '
      /NotificationRecord\(/ {own = index($0,"com.yazsras.astravar.v2uidebug") != 0}
      own && (/NotificationRecord\(/ || /mImportance=|mIntercept=|mHidden=|mSuppressedVisualEffects=|mChannel=NotificationChannel/) {print}
      tolower($0) ~ /hidesilentstatusicons|hidegentle|hide_gentle|hidesilentstatusbar|misterrupt|mzenmode=/ {print}
    '
    device shell cmd statusbar get-status-icons 2>/dev/null | head -4
    sleep 1
  done
) > "$dir/framework-ranking.txt" 2>&1 &
collector_pid=$!
set +e
timeout 180 adb -s "$serial" shell am instrument -w -r -e class 'com.yazsras.astravar.data.AscensionNotificationNativeTest#actualSmallEmblemInStatusBarAndShadeAcrossLightAndDarkThemes' "$product.test/androidx.test.runner.AndroidJUnitRunner" | tee "$dir/test-result.txt"
result=${PIPESTATUS[0]}
set -e
touch "$dir/collector-finished"
wait "$collector_pid" || true
device pull "/sdcard/Android/data/$product/files/ascension-notification/." "$dir/frames" >/dev/null || true
awk '!seen[$0]++' "$dir/framework-ranking.txt" | head -45 || true
find "$dir/frames" -maxdepth 1 -type f -printf '%f %s bytes\n' | sort || true
test "$result" = 0
grep -Eq '^OK \(1 test\)' "$dir/test-result.txt"
for theme in light dark; do
  for area in status shade; do
    test -s "$dir/frames/api37-$theme-$area.png"
    test -s "$dir/frames/api37-$theme-$area-icon.png"
  done
done
