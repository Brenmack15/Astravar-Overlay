#!/usr/bin/env bash
# Invoked on the verified Android 17 AOSP AVD. Its permanent release package is independent from the debug TLS suite.
set -euo pipefail
: "${ASTRAVAR_VERSION_CODE:?Candidate code required}"
astravar_serial=emulator-5584
astravar_package=com.yazsras.astravar
astravar_dir=validation/ascension-api37
mkdir -p "$astravar_dir"
test "$(adb -s "$astravar_serial" emu avd name | tr -d '\r' | head -1)" = AstravarHpCI
test "$(adb -s "$astravar_serial" shell getprop ro.kernel.qemu | tr -d '\r')" = 1
adb -s "$astravar_serial" shell getprop ro.build.version.sdk | tr -d '\r' > "$astravar_dir/sdk.txt"
adb -s "$astravar_serial" shell getprop ro.build.version.release | tr -d '\r' > "$astravar_dir/release.txt"
test "$(cat "$astravar_dir/sdk.txt")" = 37 && test "$(cat "$astravar_dir/release.txt")" = 17
adb -s "$astravar_serial" shell getprop ro.build.fingerprint | tr -d '\r' > "$astravar_dir/guest-fingerprint.txt"
test -z "$(adb -s "$astravar_serial" shell pm path "$astravar_package" | tr -d '\r')"
adb -s "$astravar_serial" install validation/upgrade-baseline/code58-ci.apk
adb -s "$astravar_serial" install overlay-target/build/outputs/apk/debug/overlay-target-debug.apk
adb -s "$astravar_serial" install overlay-target/build/outputs/apk/androidTest/debug/overlay-target-debug-androidTest.apk
astravar_test() {
  local method=$1 version=$2 theme=$3
  local log="$astravar_dir/$method-$theme.txt"
  python3 scripts/ci-android17-silent-icons.py "$astravar_serial" AstravarHpCI ready
  timeout -k 5s 360s adb -s "$astravar_serial" shell am instrument -w -r -e class "com.yazsras.astravar.GenesisUpgradeInstrumentationTest#$method" \
    -e astravarPackage "$astravar_package" -e astravarExpectedVersion "$version" -e ascensionTheme "$theme" \
    com.yazsras.astravar.target.test/androidx.test.runner.AndroidJUnitRunner | tee "$log"
  grep -Eq '^OK \(1 test\)' "$log"
  ! grep -Eq 'FAILURES|INSTRUMENTATION_FAILED|Process crashed' "$log"
}
astravar_copy() {
  local stage=$1 db suffix remote
  local dst="$astravar_dir/$stage"
  mkdir -p "$dst"
  adb -s "$astravar_serial" shell am force-stop "$astravar_package"
  for db in astravar-campaign-ledger.db astravar-cloud-cache.db; do
    for suffix in '' '-wal' '-shm'; do
      remote="/data/user/0/$astravar_package/databases/$db$suffix"
      if adb -s "$astravar_serial" shell test -f "$remote"; then
        timeout 45s adb -s "$astravar_serial" pull "$remote" "$dst/$db$suffix" >/dev/null
      fi
    done
  done
  test -s "$dst/astravar-campaign-ledger.db"
  adb -s "$astravar_serial" shell dumpsys package "$astravar_package" > "$dst/package.txt"
}
adb -s "$astravar_serial" shell cmd uimode night no
astravar_test seedAscensionCode58 58 light
astravar_copy before
timeout 60s adb -s "$astravar_serial" install -r app/build/outputs/apk/release/app-release.apk | tee "$astravar_dir/upgrade.txt"
grep -q Success "$astravar_dir/upgrade.txt"
for astravar_theme in light dark; do
  if [[ "$astravar_theme" == dark ]]; then adb -s "$astravar_serial" shell cmd uimode night yes; fi
  adb -s "$astravar_serial" shell cmd uimode night > "$astravar_dir/theme-$astravar_theme.txt"
  astravar_test verifyAscensionOnAndroid17 "$ASTRAVAR_VERSION_CODE" "$astravar_theme"
done
astravar_copy after
python3 scripts/genesis-package-readback.py --before "$astravar_dir/before/package.txt" --after "$astravar_dir/after/package.txt" \
  --package "$astravar_package" --old-version 58 --new-version "$ASTRAVAR_VERSION_CODE" --output "$astravar_dir/package-result.json"
python3 scripts/ascension-upgrade-readback.py "$astravar_dir"
adb -s "$astravar_serial" pull /sdcard/Android/data/com.yazsras.astravar.target/files/. "$astravar_dir/screenshots" >/dev/null
for astravar_theme in light dark; do
  for astravar_tab in Character Inventory Connections Chronicle; do
    test -s "$astravar_dir/screenshots/api37-$astravar_theme-$astravar_tab.png"
  done
done
astravar_icons="$astravar_dir/notification"
mkdir -p "$astravar_icons"
adb -s "$astravar_serial" pull /sdcard/Android/data/com.yazsras.astravar.v2uidebug/files/ascension-notification/. "$astravar_icons" >/dev/null
for astravar_theme in light dark; do
  for astravar_surface in status shade; do
    test -s "$astravar_icons/api37-$astravar_theme-$astravar_surface.png"
    test -s "$astravar_icons/api37-$astravar_theme-$astravar_surface-icon.png"
  done
done
