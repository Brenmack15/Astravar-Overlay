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
# Never operate on a connected owner's phone or an unverified device.
test "$(timeout 10s adb shell getprop ro.kernel.qemu | tr -d '\r')" = 1
case "$(timeout 10s adb get-serialno | tr -d '\r')" in emulator-*) ;; *) exit 1 ;; esac
timeout 10s adb shell input keyevent 82
for astravar_scale in window_animation_scale transition_animation_scale animator_duration_scale; do
  timeout 10s adb shell settings put global "$astravar_scale" 0
done
timeout -k 5s "${ASTRAVAR_INSTALL_TIMEOUT:-40}s" adb install "${ASTRAVAR_UPDATE_BASE_APK:-validation/Astravar-2.1-CI-ONLY-code24.apk}"
timeout -k 5s "${ASTRAVAR_INSTALL_TIMEOUT:-40}s" adb install overlay-target/build/outputs/apk/debug/overlay-target-debug.apk
timeout -k 5s "${ASTRAVAR_INSTALL_TIMEOUT:-40}s" adb install overlay-target/build/outputs/apk/androidTest/debug/overlay-target-debug-androidTest.apk
run_class() {
  local astravar_class=$1 astravar_count=$2 astravar_log=$3
  timeout -k 5s "${ASTRAVAR_NATIVE_CLASS_TIMEOUT:-420}s" adb shell am instrument -w -r -e astravarPackage com.yazsras.astravar -e class "com.yazsras.astravar.$astravar_class" com.yazsras.astravar.target.test/androidx.test.runner.AndroidJUnitRunner | tee "$astravar_log"
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
# Exercise upgrade of an unprotected legacy store, including >20 old Shatter
# snapshots, malformed raw history and a legacy archive. Only copied synthetic
# DEMO data is accepted by the host helper; no record is sent to a service.
copy_combat_database() {
  local astravar_directory=$1 astravar_file
  mkdir -p "$astravar_directory"
  for astravar_file in astravar.db astravar.db-wal astravar.db-shm; do
    if timeout 15s adb shell "test -f /data/user/0/com.yazsras.astravar/databases/$astravar_file"; then
      timeout 90s adb pull "/data/user/0/com.yazsras.astravar/databases/$astravar_file" "$astravar_directory/$astravar_file" >/dev/null
    fi
  done
  test -s "$astravar_directory/astravar.db"
}
copy_combat_database validation/history-before
python3 scripts/history-preservation-fixture.py seed --database validation/history-before/astravar.db --manifest validation/history-fixture.json --synthetic-demo | tee validation/history-seed-result.json
astravar_db_stat=$(timeout 15s adb shell 'stat -c "%u %g %a" /data/user/0/com.yazsras.astravar/databases/astravar.db' | tr -d '\r')
read -r astravar_db_uid astravar_db_gid astravar_db_mode <<<"$astravar_db_stat"
[[ "$astravar_db_uid" =~ ^[0-9]+$ && "$astravar_db_gid" =~ ^[0-9]+$ && "$astravar_db_mode" =~ ^[0-7]{3,4}$ ]]
printf '%s\n' "$astravar_db_stat" >validation/history-database-ownership.txt
# The app is stopped, the host copy is checkpointed into one complete DB, and
# only this emulator package's matching stale sidecars are removed.
timeout 15s adb shell 'rm -f /data/user/0/com.yazsras.astravar/databases/astravar.db-wal /data/user/0/com.yazsras.astravar/databases/astravar.db-shm'
timeout 90s adb push validation/history-before/astravar.db /data/user/0/com.yazsras.astravar/databases/astravar.db >/dev/null
timeout 15s adb shell "chown $astravar_db_uid:$astravar_db_gid /data/user/0/com.yazsras.astravar/databases/astravar.db && chmod $astravar_db_mode /data/user/0/com.yazsras.astravar/databases/astravar.db && restorecon /data/user/0/com.yazsras.astravar/databases/astravar.db"
test "$(timeout 15s adb shell 'stat -c "%u %g %a" /data/user/0/com.yazsras.astravar/databases/astravar.db' | tr -d '\r')" = "$astravar_db_stat"
snapshot() {
  local astravar_destination=$1
  adb shell 'cd /data/user/0/com.yazsras.astravar && for p in files databases shared_prefs no_backup; do if [ -d "$p" ]; then find "$p" -type f; fi; done | sort | while read p; do sha256sum "$p"; done' | tr -d '\r' >"$astravar_destination"
  test -s "$astravar_destination"
  grep -q 'databases/' "$astravar_destination"
}
snapshot validation/update-before.sha256
timeout -k 5s "${ASTRAVAR_INSTALL_TIMEOUT:-40}s" adb install -r "${ASTRAVAR_UPDATE_NEXT_APK:-validation/Astravar-2.1-CI-ONLY-code30.apk}" | tee validation/update-install.txt
grep -q Success validation/update-install.txt
snapshot validation/update-after.sha256
diff -u validation/update-before.sha256 validation/update-after.sha256 >validation/update-store-diff.txt
timeout 10s adb shell dumpsys package com.yazsras.astravar >validation/updated-package.txt
grep -q 'versionCode=30' validation/updated-package.txt
run_class UpdatePreservationInstrumentationTest 1 validation/native-update-readback.txt
# Exercise the updated source without reseeding demo data or executing another
# campaign action; the strict current-state/hash verification below still applies.
run_class OverlayInstrumentationTest#bNotificationStop 1 validation/native-updated-notification-stop.txt
run_class OverlayInstrumentationTest#cRollRestorationMainHistoryAndLivePermissionRecovery 1 validation/native-updated-overlay-recovery.txt
timeout 15s adb shell am force-stop com.yazsras.astravar
copy_combat_database validation/history-after
python3 scripts/history-preservation-fixture.py verify --database validation/history-after/astravar.db --manifest validation/history-fixture.json --synthetic-demo | tee validation/history-verification-result.json
# Importer acceptance uses only the explicitly fictional native fixture. Pull the
# campaign ledger separately so host SQLite reads can detect row/hash changes.
copy_campaign_database() {
  local astravar_directory=$1 astravar_file
  mkdir -p "$astravar_directory"
  for astravar_file in astravar-campaign-ledger.db astravar-campaign-ledger.db-wal astravar-campaign-ledger.db-shm; do
    if timeout 15s adb shell "test -f /data/user/0/com.yazsras.astravar/databases/$astravar_file"; then
      timeout 90s adb pull "/data/user/0/com.yazsras.astravar/databases/$astravar_file" "$astravar_directory/$astravar_file" >/dev/null
    fi
  done
  test -s "$astravar_directory/astravar-campaign-ledger.db"
}
copy_campaign_database validation/campaign-before
python3 scripts/native-campaign-import-readback.py snapshot --database validation/campaign-before/astravar-campaign-ledger.db --manifest validation/campaign-before.json --synthetic-demo >validation/campaign-baseline-result.json
ASTRAVAR_NATIVE_CLASS_TIMEOUT=720 run_class CampaignImportInstrumentationTest#aDocumentPickerPreviewConfirmationAndLibrary 1 validation/native-campaign-import.txt
timeout 15s adb shell am force-stop com.yazsras.astravar
copy_campaign_database validation/campaign-applied
timeout 30s adb pull /sdcard/Android/data/com.yazsras.astravar.target/files/native-campaign-fixture.json validation/native-campaign-fixture.json >/dev/null
timeout 30s adb pull /sdcard/Download/astravar-native-preimport-backup.json validation/native-campaign-preimport-backup.json >/dev/null
python3 scripts/native-campaign-import-readback.py applied --database validation/campaign-applied/astravar-campaign-ledger.db --manifest validation/campaign-before.json --fixture validation/native-campaign-fixture.json --backup-document validation/native-campaign-preimport-backup.json --synthetic-demo | tee validation/campaign-applied-result.json
run_class CampaignImportInstrumentationTest#bGuardedRollbackRestoresVerifiedSnapshot 1 validation/native-campaign-rollback.txt
timeout 15s adb shell am force-stop com.yazsras.astravar
copy_campaign_database validation/campaign-rolled-back
python3 scripts/native-campaign-import-readback.py rolled-back --database validation/campaign-rolled-back/astravar-campaign-ledger.db --manifest validation/campaign-before.json --fixture validation/native-campaign-fixture.json --synthetic-demo | tee validation/campaign-rollback-result.json
printf '%s\n' "PASS: matching certificate verified separately (${ASTRAVAR_TEST_SIGNER_LABEL:-CI-only}), version24→30, campaign schema3→4, all persisted store bytes retained before startup; independent native UI readback verified fictional inventory and recent rolls; copied-database readback verified every seeded raw historical hash, including old Shatter and malformed records." >validation/update-preservation-result.txt
