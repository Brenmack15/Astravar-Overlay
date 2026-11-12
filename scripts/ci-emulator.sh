#!/usr/bin/env bash
# Ordered release UI acceptance on one freshly created, disposable emulator.
# Never connects to an owner phone, clears product data or uses an owner signing key.
set -euo pipefail
: "${ANDROID_HOME:?SDK is required}"
: "${RUNNER_TEMP:?Use an isolated CI runner}"
astravar_product=${ASTRAVAR_TEST_PACKAGE:-com.yazsras.astravar.debug}
astravar_apk=${ASTRAVAR_TEST_APK:-app/build/outputs/apk/debug/app-debug.apk}
[[ "$astravar_product" =~ ^com\.yazsras\.astravar(\.(debug|v2uidebug))?$ ]]
mkdir -p validation
export ANDROID_AVD_HOME="$RUNNER_TEMP/astravar-avd"
export ANDROID_USER_HOME="$RUNNER_TEMP/astravar-android-user"
mkdir -p "$ANDROID_AVD_HOME" "$ANDROID_USER_HOME"
test -c /dev/kvm
sudo chmod 666 /dev/kvm
printf 'no\n' | timeout -k 5s 40s avdmanager create avd --force -n AstravarCI -p "$ANDROID_AVD_HOME/AstravarCI.avd" -k 'system-images;android-36;google_apis;x86_64' --device pixel_6
test -f "$ANDROID_AVD_HOME/AstravarCI.ini"
astravar_serial=emulator-5580
astravar_verified_emulator=0
astravar_devices=$(timeout 10s adb devices)
if awk -v serial="$astravar_serial" '$1 == serial { found=1 } END { exit !found }' <<<"$astravar_devices"; then
  printf '%s\n' 'Refusing a pre-existing device at the reserved CI emulator serial.' >&2
  exit 1
fi
"$ANDROID_HOME/emulator/emulator" -avd AstravarCI -port 5580 -no-window -no-audio -no-snapshot -no-boot-anim -gpu swiftshader_indirect >validation/emulator.log 2>&1 &
astravar_emulator_pid=$!
cleanup() {
  local astravar_status=$?
  trap - EXIT
  if [ "$astravar_verified_emulator" = 1 ]; then
    timeout 10s adb -s "$astravar_serial" logcat -d >validation/logcat.txt 2>&1 || true
    timeout 15s adb -s "$astravar_serial" pull /sdcard/Android/data/com.yazsras.astravar.target/files/. validation/emulator-evidence >/dev/null 2>&1 || true
    timeout 5s adb -s "$astravar_serial" emu kill >/dev/null 2>&1 || true
  fi
  kill "$astravar_emulator_pid" 2>/dev/null || true
  exit "$astravar_status"
}
trap cleanup EXIT
timeout -k 5s 80s adb -s "$astravar_serial" wait-for-device
astravar_booted=0
for astravar_attempt in $(seq 1 40); do
  if [ "$(timeout 3s adb -s "$astravar_serial" shell getprop sys.boot_completed | tr -d '\r')" = 1 ]; then astravar_booted=1; break; fi
  sleep 2
done
test "$astravar_booted" = 1
test "$(timeout 5s adb -s "$astravar_serial" shell getprop ro.kernel.qemu | tr -d '\r')" = 1
test "$(timeout 5s adb -s "$astravar_serial" emu avd name | tr -d '\r' | head -n 1)" = AstravarCI
astravar_verified_emulator=1
timeout 10s adb -s "$astravar_serial" shell input keyevent 82
for astravar_scale in window_animation_scale transition_animation_scale animator_duration_scale; do
  timeout 10s adb -s "$astravar_serial" shell settings put global "$astravar_scale" 0
done
git rev-parse HEAD >validation/SOURCE_COMMIT.txt
sha256sum "$astravar_apk" >validation/product-apk.sha256
timeout -k 5s 35s adb -s "$astravar_serial" install "${ASTRAVAR_BASELINE_APK:-$astravar_apk}"
timeout -k 5s 35s adb -s "$astravar_serial" install overlay-target/build/outputs/apk/debug/overlay-target-debug.apk
timeout -k 5s 35s adb -s "$astravar_serial" install overlay-target/build/outputs/apk/androidTest/debug/overlay-target-debug-androidTest.apk
: >validation/native-stages.tsv
run_stage() {
  local astravar_class=$1 astravar_count=$2 astravar_budget=$3 astravar_log=$4
  # Each named stage runs exactly once. A failed prerequisite stops its dependents;
  # no JUnit hash order, automatic retry, assertion suppression or data reset.
  if timeout -k 5s "${astravar_budget}s" adb -s "$astravar_serial" shell am instrument -w -r -e astravarPackage "$astravar_product" -e class "com.yazsras.astravar.$astravar_class" com.yazsras.astravar.target.test/androidx.test.runner.AndroidJUnitRunner | tee "validation/$astravar_log"; then
    if grep -Eq "^OK \\($astravar_count tests?\\)" "validation/$astravar_log" && ! grep -Eq 'FAILURES|INSTRUMENTATION_FAILED|Process crashed' "validation/$astravar_log"; then
      printf 'PASS\t%s\t%s\n' "$astravar_class" "$astravar_log" >>validation/native-stages.tsv
      return 0
    fi
  fi
  printf 'FAIL\t%s\t%s\n' "$astravar_class" "$astravar_log" >>validation/native-stages.tsv
  return 1
}
stop_product() { timeout 10s adb -s "$astravar_serial" shell am force-stop "$astravar_product"; }
copy_campaign_database() {
  local astravar_directory=$1 astravar_file
  mkdir -p "$astravar_directory"
  for astravar_file in astravar-campaign-ledger.db astravar-campaign-ledger.db-wal astravar-campaign-ledger.db-shm; do
    if timeout 5s adb -s "$astravar_serial" shell test -f "/data/user/0/$astravar_product/databases/$astravar_file"; then
      timeout 60s adb -s "$astravar_serial" pull "/data/user/0/$astravar_product/databases/$astravar_file" "$astravar_directory/$astravar_file" >/dev/null
    fi
  done
  test -s "$astravar_directory/astravar-campaign-ledger.db"
}
snapshot_stores() {
  timeout 30s adb -s "$astravar_serial" shell 'cd /data/user/0/'"$astravar_product"' && for d in files databases shared_prefs no_backup; do if test -d "$d"; then find "$d" -type f; fi; done | sort | while read f; do sha256sum "$f"; done' | tr -d '\r' >"$1"
  test -s "$1"
  grep -q 'databases/' "$1"
}
verify_mapped() {
  python3 - "$1" "$2" "$3" "$4" "$5" <<'ASTRAVAR_MAPPED_READBACK'
import json,runpy,sys
from pathlib import Path
helper=runpy.run_path('scripts/native-campaign-import-readback.py')
result=helper['verify_mapped'](Path(sys.argv[1]),Path(sys.argv[2]),Path(sys.argv[3]),rolled_back=sys.argv[4]=='rollback',synthetic_demo=True,expected_room_schema=8)
Path(sys.argv[5]).write_text(json.dumps(result,indent=2)+'\n')
print(json.dumps(result))
ASTRAVAR_MAPPED_READBACK
}
copy_upgrade_stores() {
  local astravar_directory=$1 astravar_database astravar_suffix astravar_file
  mkdir -p "$astravar_directory"
  for astravar_database in astravar.db astravar-campaign-ledger.db astravar-reforge.db; do
    for astravar_suffix in '' -wal -shm -journal; do
      astravar_file="$astravar_database$astravar_suffix"
      if timeout 5s adb -s "$astravar_serial" shell test -f "/data/user/0/$astravar_product/databases/$astravar_file"; then
        timeout 60s adb -s "$astravar_serial" pull "/data/user/0/$astravar_product/databases/$astravar_file" "$astravar_directory/$astravar_file" >/dev/null
      fi
    done
    test -s "$astravar_directory/$astravar_database"
  done
}
if [ -n "${ASTRAVAR_BASELINE_APK:-}" ]; then
  # The workflow builds the frozen code49 source and code50 with the same
  # disposable CI key. This is a real older-version update; it is distinct from
  # the later same-version reinstall and from private owner-signing acceptance.
  sha256sum "$ASTRAVAR_BASELINE_APK" >validation/upgrade-baseline-apk.sha256
  run_stage GenesisUpgradeInstrumentationTest#seedCode49 1 720 native-genesis-upgrade-seed.txt
  stop_product
  timeout 15s adb -s "$astravar_serial" root
  timeout 15s adb -s "$astravar_serial" wait-for-device
  timeout 10s adb -s "$astravar_serial" shell dumpsys package "$astravar_product" >validation/genesis-upgrade-package-before.txt
  snapshot_stores validation/genesis-upgrade-files-before.sha256
  copy_upgrade_stores validation/genesis-upgrade-before
  python3 scripts/genesis-upgrade-readback.py validation/genesis-upgrade-before --synthetic-emulator \
    --output validation/genesis-upgrade-before.json >validation/genesis-upgrade-seed-result.json
  timeout -k 5s 45s adb -s "$astravar_serial" install -r "$astravar_apk" | tee validation/genesis-upgrade-install.txt
  grep -q Success validation/genesis-upgrade-install.txt
  snapshot_stores validation/genesis-upgrade-files-after.sha256
  diff -u validation/genesis-upgrade-files-before.sha256 validation/genesis-upgrade-files-after.sha256 >validation/genesis-upgrade-file-diff.txt
  timeout 10s adb -s "$astravar_serial" shell dumpsys package "$astravar_product" >validation/genesis-upgrade-package-after.txt
  python3 scripts/genesis-package-readback.py \
    --before validation/genesis-upgrade-package-before.txt \
    --after validation/genesis-upgrade-package-after.txt --package "$astravar_product" \
    --old-version 49 --new-version 50 --output validation/genesis-upgrade-package-result.json
  run_stage GenesisUpgradeInstrumentationTest#verifyAfterUpgrade 1 360 native-genesis-upgrade-readback.txt
  stop_product
  copy_upgrade_stores validation/genesis-upgrade-after
  python3 scripts/genesis-upgrade-readback.py validation/genesis-upgrade-after --synthetic-emulator \
    --compare validation/genesis-upgrade-before.json --output validation/genesis-upgrade-after.json \
    >validation/genesis-upgrade-readback-result.json
  # This write is deliberately separate from the unchanged-cutover comparison:
  # heal one point in the real UI, restart, and prove only canonical schema8 changed.
  run_stage GenesisUpgradeInstrumentationTest#canonicalWriteSurvivesRestart 1 240 native-genesis-canonical-write.txt
  stop_product
  copy_upgrade_stores validation/genesis-upgrade-after-write
  python3 scripts/genesis-upgrade-readback.py validation/genesis-upgrade-after-write --synthetic-emulator \
    --verify-write validation/genesis-upgrade-after.json --output validation/genesis-upgrade-after-write.json \
    >validation/genesis-upgrade-canonical-write-result.json
fi
# Collect independent roll, SQLite and player journeys once before dependent
# update/import stages. Every failure remains release-blocking.
astravar_prerequisites_ok=1
run_stage OverlayInstrumentationTest 3 420 native-overlay.txt || astravar_prerequisites_ok=0
# Run the >32m SQLite regression inside the separate disposable QA application.
# The optimized release journeys above and this fixture exercise distinct packages.
timeout -k 5s 45s adb -s "$astravar_serial" install app/build/outputs/apk/debug/app-debug.apk
timeout -k 5s 45s adb -s "$astravar_serial" install app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
if timeout -k 5s 420s adb -s "$astravar_serial" shell am instrument -w -r -e class com.yazsras.astravar.data.ReforgeLargeCampaignSelectionNativeTest,com.yazsras.astravar.data.ReforgeRecoveryNativeTest com.yazsras.astravar.v2uidebug.test/androidx.test.runner.AndroidJUnitRunner | tee validation/native-large-campaign-selection.txt &&
  grep -Eq '^OK \(2 tests\)' validation/native-large-campaign-selection.txt &&
  ! grep -Eq 'FAILURES|INSTRUMENTATION_FAILED|Process crashed' validation/native-large-campaign-selection.txt; then
  printf 'PASS\tReforgeLargeCampaignSelectionNativeTest\tnative-large-campaign-selection.txt\n' >>validation/native-stages.tsv
else
  printf 'FAIL\tReforgeLargeCampaignSelectionNativeTest\tnative-large-campaign-selection.txt\n' >>validation/native-stages.tsv
  astravar_prerequisites_ok=0
fi
run_stage PlayerFirstJourneyInstrumentationTest 1 1050 native-player-first.txt || astravar_prerequisites_ok=0
run_stage VisualRebirthJourneyInstrumentationTest 1 900 native-visual-rebirth.txt || astravar_prerequisites_ok=0
run_stage GenesisJourneyInstrumentationTest 1 1500 native-genesis.txt || astravar_prerequisites_ok=0
test "$astravar_prerequisites_ok" = 1
# Copy only the fresh fictional emulator's closed database and its actual SAF
# document. The independent host proof must pass before later journeys continue.
stop_product
timeout 15s adb -s "$astravar_serial" root
timeout 15s adb -s "$astravar_serial" wait-for-device
copy_campaign_database validation/genesis-connected
timeout 30s adb -s "$astravar_serial" pull /sdcard/Download/astravar-genesis-native-connected.json validation/genesis-connected-backup.json >/dev/null
python3 scripts/genesis-connected-readback.py \
  --database validation/genesis-connected/astravar-campaign-ledger.db \
  --backup-document validation/genesis-connected-backup.json --synthetic-emulator \
  --output validation/genesis-connected-readback.json
# Craft only after the complete-backup proof has compared its unchanged snapshot.
# This stage uses the same fictional hero and adds its own material and invention.
run_stage GenesisCraftingInstrumentationTest 1 900 native-genesis-crafting.txt
stop_product
copy_campaign_database validation/genesis-crafting
python3 scripts/genesis-crafting-readback.py \
  --before-database validation/genesis-connected/astravar-campaign-ledger.db \
  --database validation/genesis-crafting/astravar-campaign-ledger.db --synthetic-emulator \
  --output validation/genesis-crafting-readback.json
run_stage UpdateFixtureInstrumentationTest 1 120 native-update-fixture.txt
stop_product
timeout 15s adb -s "$astravar_serial" root
timeout 15s adb -s "$astravar_serial" wait-for-device
snapshot_stores validation/update-before.sha256
# Android's ordinary -r path must support replacing an already installed Code39
# without uninstall/-d/clear-data. Older-version upgrade is also tested separately
# by the native HTTPS workflow; the actual owner certificate is checked privately.
timeout -k 5s 45s adb -s "$astravar_serial" install -r "$astravar_apk" | tee validation/update-install.txt
grep -q Success validation/update-install.txt
snapshot_stores validation/update-after.sha256
diff -u validation/update-before.sha256 validation/update-after.sha256 >validation/update-store-diff.txt
run_stage UpdatePreservationInstrumentationTest 1 420 native-update-readback.txt
stop_product
copy_campaign_database validation/campaign-before
python3 scripts/native-campaign-import-readback.py snapshot --database validation/campaign-before/astravar-campaign-ledger.db --manifest validation/campaign-before.json --expected-room-schema 8 --synthetic-demo >validation/campaign-baseline-result.json
run_stage CampaignImportInstrumentationTest#aDocumentPickerPreviewConfirmationAndLibrary 1 720 native-campaign-import.txt
stop_product
copy_campaign_database validation/campaign-applied
timeout 30s adb -s "$astravar_serial" pull /sdcard/Android/data/com.yazsras.astravar.target/files/native-campaign-fixture.json validation/native-campaign-fixture.json >/dev/null
timeout 30s adb -s "$astravar_serial" pull /sdcard/Download/astravar-native-preimport-backup.json validation/native-campaign-preimport-backup.json >/dev/null
python3 scripts/native-campaign-import-readback.py applied --database validation/campaign-applied/astravar-campaign-ledger.db --manifest validation/campaign-before.json --fixture validation/native-campaign-fixture.json --backup-document validation/native-campaign-preimport-backup.json --expected-room-schema 8 --synthetic-demo >validation/campaign-applied-result.json
run_stage CampaignImportInstrumentationTest#bGuardedRollbackRestoresVerifiedSnapshot 1 240 native-campaign-rollback.txt
stop_product
copy_campaign_database validation/campaign-rolled-back
python3 scripts/native-campaign-import-readback.py rolled-back --database validation/campaign-rolled-back/astravar-campaign-ledger.db --manifest validation/campaign-before.json --fixture validation/native-campaign-fixture.json --expected-room-schema 8 --synthetic-demo >validation/campaign-rollback-result.json
python3 scripts/native-campaign-import-readback.py snapshot --database validation/campaign-rolled-back/astravar-campaign-ledger.db --manifest validation/campaign-mapped-before.json --expected-room-schema 8 --synthetic-demo >validation/campaign-mapped-baseline-result.json
run_stage CampaignImportInstrumentationTest#cMappedCampaignRecordsReachPlayerViews 1 420 native-mapped-campaign-ui.txt
stop_product
copy_campaign_database validation/campaign-mapped
timeout 30s adb -s "$astravar_serial" pull /sdcard/Android/data/com.yazsras.astravar.target/files/native-mapped-fixture.json validation/native-mapped-fixture.json >/dev/null
verify_mapped validation/campaign-mapped/astravar-campaign-ledger.db validation/campaign-mapped-before.json validation/native-mapped-fixture.json applied validation/native-mapped-readback.json
run_stage CampaignImportInstrumentationTest#dMappedCampaignRecordsRollbackAfterReadback 1 240 native-mapped-campaign-rollback.txt
stop_product
copy_campaign_database validation/campaign-mapped-rolled-back
verify_mapped validation/campaign-mapped-rolled-back/astravar-campaign-ledger.db validation/campaign-mapped-before.json validation/native-mapped-fixture.json rollback validation/native-mapped-rollback-readback.json
printf '%s\n' 'PASS: every ordered stage recorded in native-stages.tsv. Genesis exercised guided creation, horizontal page flicks, per-page reading positions, Back, quick-action drag and tap rearrangement, restart persistence, contextual inventory creation and cross-view edits, single-row item identity and Activity rotation. Visual Rebirth captured the primary destinations and forms, checked resource persistence, companion equipment, journal edits and 400dp layouts at three font scales. Standalone weapon rolls, retired overlay contract, character advancement, reviewed gear, generic companion relationships and direct companion combat passed. Companion-only healing survived restart, with source HP and the default character preserved. Same-version reinstall preserved persisted bytes. Native readback retained rolls and inventory. Large and typed imports and separately consented rollback passed independent host SQLite verification. Native QA SQLite fixtures verified scoped selection above the complete-export ceiling, duplicate and stale rejection, interrupted-write rollback, same-command recovery and backup restore after reopen. When a baseline APK is provided, separate genesis-upgrade evidence records code49 to code50 package preservation, unchanged original files, additive schema6 to schema8 cutover with raw row and receipt verification, and a later canonical UI heal that survives restart without changing the retained source. Disposable CI signer and emulator only. Physical owner phone acceptance remains pending.' >validation/RELEASE-UI-RESULT.txt
