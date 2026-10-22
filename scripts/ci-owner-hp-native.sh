#!/usr/bin/env bash
# Isolated emulator only: no physical phone, live Site, owner signing key or real records.
set -euo pipefail
: "${ANDROID_HOME:?Android SDK required}"
: "${RUNNER_TEMP:?Isolated CI runner required}"
: "${ASTRAVAR_BASELINE_DEBUG_APK:?Previous published debug APK required}"
: "${ASTRAVAR_VERSION_CODE:?Exact candidate version code required}"
test -c /dev/kvm
sudo chmod 666 /dev/kvm
astravar_private=$(mktemp -d "$RUNNER_TEMP/astravar-hp-native.XXXXXX")
chmod 700 "$astravar_private"
astravar_output=validation/owner-hp-native
mkdir -p "$astravar_output"
astravar_serial=emulator-5584
astravar_product=com.yazsras.astravar.v2uidebug
astravar_server_pid=""
astravar_emulator_pid=""
astravar_verified_emulator=0
cleanup() {
  local astravar_result=$?
  trap - EXIT
  if [[ -n "$astravar_emulator_pid" ]]; then
    # A failed launch or port collision must never target another connected
    # emulator. Device commands are allowed only after this fresh AVD is proven.
    if [[ "$astravar_verified_emulator" == 1 ]]; then
      timeout 10s adb -s "$astravar_serial" shell dumpsys connectivity >"$astravar_output/connectivity-final.txt" 2>&1 || true
      timeout 10s adb -s "$astravar_serial" logcat -d >"$astravar_output/logcat-final.txt" 2>&1 || true
      timeout 10s adb -s "$astravar_serial" shell dumpsys window windows >"$astravar_output/windows-final.txt" 2>&1 || true
      timeout 10s adb -s "$astravar_serial" pull "/sdcard/Android/data/$astravar_product/files/." "$astravar_output/emulator-evidence" >/dev/null 2>&1 || true
      timeout 8s adb -s "$astravar_serial" emu kill >/dev/null 2>&1 || true
    fi
    kill "$astravar_emulator_pid" 2>/dev/null || true
  fi
  if [[ -n "$astravar_server_pid" ]]; then sudo kill "$astravar_server_pid" 2>/dev/null || true; fi
  # This path was allocated in the runner's temporary directory in this process.
  [[ "$astravar_private" == "$RUNNER_TEMP"/astravar-hp-native.* ]] && rm -rf -- "$astravar_private"
  exit "$astravar_result"
}
trap cleanup EXIT
# Refuse an existing online, offline, or unauthorized device with this serial.
# Do not infer ownership merely from the fact that a device is an emulator.
astravar_devices=$(timeout 10s adb devices)
if awk -v serial="$astravar_serial" '$1 == serial { found=1 } END { exit !found }' <<<"$astravar_devices"; then
  printf '%s\n' 'Refusing a pre-existing device at the reserved CI emulator serial.' >&2
  exit 1
fi
openssl req -x509 -newkey rsa:2048 -nodes -days 1 -sha256 -subj '/CN=Astravar ephemeral fictional CI CA' \
  -keyout "$astravar_private/ca.key" -out "$astravar_private/ca.crt" >/dev/null 2>&1
openssl req -newkey rsa:2048 -nodes -subj '/CN=hp-bridge.example' \
  -keyout "$astravar_private/server.key" -out "$astravar_private/server.csr" >/dev/null 2>&1
printf '%s\n' 'subjectAltName=DNS:hp-bridge.example' 'extendedKeyUsage=serverAuth' >"$astravar_private/server.ext"
openssl x509 -req -in "$astravar_private/server.csr" -CA "$astravar_private/ca.crt" -CAkey "$astravar_private/ca.key" \
  -CAcreateserial -out "$astravar_private/server.crt" -days 1 -sha256 -extfile "$astravar_private/server.ext" >/dev/null 2>&1
python3 - "$astravar_private" <<'PY'
import json,pathlib,secrets,sys
p=pathlib.Path(sys.argv[1]); origin='https://hp-bridge.example'
c={'origin':origin,'profileId':'hp-native-player','ownerId':'ci-fictional-'+secrets.token_hex(8),
   'controlToken':secrets.token_urlsafe(48),'serviceBearer':secrets.token_urlsafe(48)}
(p/'server-config.json').write_text(json.dumps(c))
(p/'client-config.json').write_text(json.dumps({k:c[k] for k in ['origin','profileId','controlToken']}|
                                            {'certificatePem':(p/'ca.crt').read_text()}))
PY
start_server() {
  rm -f "$astravar_private/server-ready.json"
  sudo env ASTRAVAR_CI_STATE_DIR="$astravar_private" ASTRAVAR_CI_PORT=18443 ASTRAVAR_CI_DNS_PORT=53 \
    "$(command -v node)" scripts/ci-owner-native-server.mjs --synthetic-only >>"$astravar_output/gateway.log" 2>&1 &
  astravar_server_pid=$!
  for astravar_attempt in $(seq 1 40); do
    if test -s "$astravar_private/server-ready.json"; then return 0; fi
    kill -0 "$astravar_server_pid" 2>/dev/null || return 1
    sleep 0.25
  done
  return 1
}
start_server
export ANDROID_AVD_HOME="$astravar_private/avd"
export ANDROID_USER_HOME="$astravar_private/android-user"
mkdir -p "$ANDROID_AVD_HOME" "$ANDROID_USER_HOME"
printf 'no\n' | timeout -k 5s 40s avdmanager create avd --force -n AstravarHpCI -p "$ANDROID_AVD_HOME/AstravarHpCI.avd" \
  -k 'system-images;android-36;google_apis;x86_64' --device pixel_6
# Keep the emulator's normal upstream DNS so Android can genuinely validate network
# connectivity for WorkManager's CONNECTED constraint. Only the two HTTPS fixture
# names are overridden in the owned guest hosts file below; production TLS is unchanged.
"$ANDROID_HOME/emulator/emulator" -avd AstravarHpCI -port 5584 -accel on \
  -no-window -no-audio -no-snapshot -no-boot-anim -writable-system -gpu swiftshader_indirect >"$astravar_output/emulator.log" 2>&1 &
astravar_emulator_pid=$!
kill -0 "$astravar_emulator_pid"
timeout -k 5s 120s adb -s "$astravar_serial" wait-for-device
astravar_booted=0
for astravar_attempt in $(seq 1 90); do
  kill -0 "$astravar_emulator_pid"
  if [[ "$(timeout 3s adb -s "$astravar_serial" shell getprop sys.boot_completed | tr -d '\r')" == 1 ]]; then astravar_booted=1; break; fi
  sleep 2
done
test "$astravar_booted" = 1
test "$(adb -s "$astravar_serial" shell getprop ro.kernel.qemu | tr -d '\r')" = 1
astravar_avd_name=$(timeout 10s adb -s "$astravar_serial" emu avd name | tr -d '\r' | awk 'NR == 1 { print }')
test "$astravar_avd_name" = AstravarHpCI
kill -0 "$astravar_emulator_pid"
astravar_verified_emulator=1
timeout 15s adb -s "$astravar_serial" root
timeout 15s adb -s "$astravar_serial" wait-for-device
# Resolve only the two reserved fixture names through the owned emulator OS.
# The session-only writable image is destroyed with this fresh AVD. No device
# credentials exist yet, no CA is installed system-wide, and TLS hostname
# verification still receives the original hp-bridge.example URL.
# Official support: developer.android.com/studio/run/emulator-commandline
# Do not use remount -R: intentional adbd disconnect during its automatic reboot
# can return 255 before the caller can wait. A plain successful remount reports
# whether a reboot is required, matching AOSP's explicit remount test lifecycle.
astravar_previous_boot=$(timeout 5s adb -s "$astravar_serial" shell cat /proc/sys/kernel/random/boot_id | tr -d '\r')
test -n "$astravar_previous_boot"
timeout -k 5s 60s adb -s "$astravar_serial" remount >"$astravar_output/hosts-remount.txt" 2>&1
astravar_requires_reboot=0
if grep -Fq 'Now reboot your device for settings to take effect' "$astravar_output/hosts-remount.txt"; then
  astravar_requires_reboot=1
  timeout 15s adb -s "$astravar_serial" reboot
fi
timeout -k 5s 120s adb -s "$astravar_serial" wait-for-device
astravar_remounted_booted=0
for astravar_attempt in $(seq 1 90); do
  kill -0 "$astravar_emulator_pid"
  astravar_current_boot=$(timeout 3s adb -s "$astravar_serial" shell cat /proc/sys/kernel/random/boot_id 2>/dev/null | tr -d '\r' || true)
  astravar_boot_complete=$(timeout 3s adb -s "$astravar_serial" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' || true)
  if [[ "$astravar_boot_complete" == 1 && -n "$astravar_current_boot" ]] &&
    [[ "$astravar_requires_reboot" == 0 || "$astravar_current_boot" != "$astravar_previous_boot" ]]; then
    astravar_remounted_booted=1; break
  fi
  sleep 2
done
test "$astravar_remounted_booted" = 1
kill -0 "$astravar_emulator_pid"
test "$(adb -s "$astravar_serial" shell getprop ro.kernel.qemu | tr -d '\r')" = 1
test "$(timeout 10s adb -s "$astravar_serial" emu avd name | tr -d '\r' | awk 'NR == 1 { print }')" = AstravarHpCI
timeout 15s adb -s "$astravar_serial" root
timeout 15s adb -s "$astravar_serial" wait-for-device
timeout -k 5s 30s adb -s "$astravar_serial" remount >>"$astravar_output/hosts-remount.txt" 2>&1
timeout 10s adb -s "$astravar_serial" shell "printf '\n127.0.0.1 hp-bridge.example wrong-hp-bridge.example\n' >> /system/etc/hosts"
timeout 10s adb -s "$astravar_serial" shell "grep -F '127.0.0.1 hp-bridge.example wrong-hp-bridge.example' /system/etc/hosts" >"$astravar_output/hosts-mapping.txt"
for astravar_fixture_host in hp-bridge.example wrong-hp-bridge.example; do
  timeout 10s adb -s "$astravar_serial" shell ping -c 1 -W 2 "$astravar_fixture_host" >"$astravar_output/resolve-$astravar_fixture_host.txt"
  grep -Fq '(127.0.0.1)' "$astravar_output/resolve-$astravar_fixture_host.txt"
done
timeout 10s adb -s "$astravar_serial" reverse tcp:443 tcp:18443
timeout 10s adb -s "$astravar_serial" shell settings put global private_dns_mode off
timeout 10s adb -s "$astravar_serial" shell input keyevent 82
for astravar_scale in window_animation_scale transition_animation_scale animator_duration_scale; do
  timeout 10s adb -s "$astravar_serial" shell settings put global "$astravar_scale" 0
done
timeout 45s adb -s "$astravar_serial" install "$ASTRAVAR_BASELINE_DEBUG_APK"
timeout 45s adb -s "$astravar_serial" install app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
run_test() {
  local astravar_test=$1 astravar_log=$2 astravar_timeout=${3:-240}
  [[ "$astravar_timeout" =~ ^[1-9][0-9]*$ ]] || return 1
  # Explicit status checks are essential: errexit is disabled throughout functions
  # invoked by an if-condition, as the independent evidence collector does below.
  if ! timeout -k 5s "${astravar_timeout}s" adb -s "$astravar_serial" shell am instrument -w -r -e class "$astravar_test" \
    "$astravar_product.test/androidx.test.runner.AndroidJUnitRunner" | tee "$astravar_output/$astravar_log"; then
    return 1
  fi
  grep -q '^OK (1 test' "$astravar_output/$astravar_log" || return 1
  if grep -q 'FAILURES\|INSTRUMENTATION_FAILED\|Process crashed' "$astravar_output/$astravar_log"; then return 1; fi
  return 0
}
timeout 10s adb -s "$astravar_serial" shell dumpsys connectivity >"$astravar_output/connectivity-before-tests.txt"
run_test com.yazsras.astravar.data.LiveHpBaselineSeedTest baseline-seed.txt
timeout 10s adb -s "$astravar_serial" shell am force-stop "$astravar_product"
snapshot() {
  adb -s "$astravar_serial" shell "run-as $astravar_product sh -c 'for d in files databases shared_prefs no_backup; do if test -d \"\$d\"; then find \"\$d\" -type f; fi; done | sort | while read f; do sha256sum \"\$f\"; done'" | tr -d '\r' >"$1"
  test -s "$1"
  grep -q 'databases/' "$1"
}
snapshot "$astravar_output/update-before.sha256"
timeout 45s adb -s "$astravar_serial" install -r app/build/outputs/apk/debug/app-debug.apk | tee "$astravar_output/update-install.txt"
grep -q Success "$astravar_output/update-install.txt"
snapshot "$astravar_output/update-after.sha256"
diff -u "$astravar_output/update-before.sha256" "$astravar_output/update-after.sha256" >"$astravar_output/update-store-diff.txt"
adb -s "$astravar_serial" shell dumpsys package "$astravar_product" >"$astravar_output/updated-package.txt"
grep -q "versionCode=${ASTRAVAR_VERSION_CODE} " "$astravar_output/updated-package.txt"
# After proving fresh AVD ownership again, explicitly align only this guest clock
# to the CI host after update preservation is verified and before any credentials.
# A two-second lead covers second-resolution setting/ADB latency without changing
# the production ten-minute challenge, expiry or nonce checks.
kill -0 "$astravar_emulator_pid"
test "$(adb -s "$astravar_serial" shell getprop ro.kernel.qemu | tr -d '\r')" = 1
test "$(timeout 10s adb -s "$astravar_serial" emu avd name | tr -d '\r' | awk 'NR == 1 { print }')" = AstravarHpCI
sample_clock() {
  local astravar_phase=$1 astravar_host_before astravar_guest_epoch astravar_host_after
  astravar_host_before=$(date +%s%N)
  astravar_guest_epoch=$(timeout 10s adb -s "$astravar_serial" shell toybox date +%s.%N | tr -d '\r')
  astravar_host_after=$(date +%s%N)
  python3 - "$astravar_output/clock-$astravar_phase.json" "$astravar_host_before" "$astravar_guest_epoch" "$astravar_host_after" <<'PY'
import decimal,json,pathlib,re,sys
p,before,guest,after=sys.argv[1:]
assert re.fullmatch(r'[0-9]+\.[0-9]{9}',guest), 'Native epoch sample required'
host_ms=(int(before)+int(after))//2//1_000_000
guest_ms=int(decimal.Decimal(guest)*1000)
value={'fictionalOnly':True,'hostEpochMs':host_ms,'guestEpochMs':guest_ms,
       'guestMinusHostMs':guest_ms-host_ms,'sampleRoundTripMs':(int(after)-int(before))//1_000_000}
pathlib.Path(p).write_text(json.dumps(value,indent=2)+'\n')
PY
}
sample_clock before
timeout 10s adb -s "$astravar_serial" shell settings put global auto_time 0
astravar_clock_target=$(( $(date +%s) + 2 ))
timeout 10s adb -s "$astravar_serial" shell toybox date -u -s "@$astravar_clock_target" >"$astravar_output/clock-set.txt"
sample_clock after
python3 - "$astravar_output/clock-after.json" <<'PY'
import json,sys
value=json.load(open(sys.argv[1]))
assert 0 <= value['guestMinusHostMs'] <= 5000, 'Owned guest clock synchronization not verified'
PY
# No credentials appear in command arguments/logs or retained artifacts.
adb -s "$astravar_serial" shell "run-as $astravar_product sh -c 'mkdir -p files; cat >files/ci-https-config.json'" <"$astravar_private/client-config.json"
run_test 'com.yazsras.astravar.data.LiveHpNativeHttpsTest#aNativeKeystorePublicationApprovalRejectionAndOfflineIntent' native-hp-first.txt
timeout 10s adb -s "$astravar_serial" shell am force-stop "$astravar_product"
# Keep the same durable temporary SQLite file while restarting the gateway.
sudo kill "$astravar_server_pid"
wait "$astravar_server_pid" || true
astravar_server_pid=""
start_server
run_test 'com.yazsras.astravar.data.LiveHpNativeHttpsTest#bRestartRetryAndValidatedBackupRestore' native-hp-restart-restore.txt
timeout 10s adb -s "$astravar_serial" shell am force-stop "$astravar_product"
run_test 'com.yazsras.astravar.data.LiveHpNativeHttpsTest#cSimultaneousLocalEditAndAuthorizationRevocation' native-hp-negative.txt
# Preserve failure evidence from independent suites instead of allowing one new
# fixture assertion to hide power controls, notification actions or clipboard checks.
# The baseline and ordered legacy HP recovery prerequisites above remain fail-fast.
astravar_independent_failures=0
: >"$astravar_output/independent-tests.tsv"
run_independent_test() {
  local astravar_test=$1 astravar_log=$2
  if run_test "$@"; then
    printf 'PASS\t%s\t%s\n' "$astravar_test" "$astravar_log" >>"$astravar_output/independent-tests.tsv"
  else
    printf 'FAIL\t%s\t%s\n' "$astravar_test" "$astravar_log" >>"$astravar_output/independent-tests.tsv"
    astravar_independent_failures=$((astravar_independent_failures + 1))
  fi
  return 0
}
# Production campaign HTTPS transport, explicit grant, Auto-Sync and immutable readback.
run_independent_test 'com.yazsras.astravar.data.CampaignNativeHttpsTest#authenticatedAutoSyncCommitsAllRecordCategoriesAndVerifiesReceipts' native-campaign-https.txt 360
# Real native Room/domain transactions in a separately named synthetic database.
run_independent_test 'com.yazsras.astravar.data.CampaignSyncNativeTest#nativeCommittedHpSlotAndCheckpointSurviveDatabaseReopen' native-campaign-hp-slot-checkpoint.txt
run_independent_test 'com.yazsras.astravar.data.CampaignSyncNativeTest#nativeAtomicPurchaseRollbackAndRetryPreserveSinglePhysicalIdentity' native-campaign-purchase-move.txt
run_independent_test 'com.yazsras.astravar.data.CampaignSyncNativeTest#nativeCompanionAndStageChangesKeepIndependentResourcesAndLaterWork' native-campaign-companion-stage.txt
# Native 2.3 wake authentication, encrypted token storage, durable duplicate rejection,
# real WorkManager pause controls and ordinary quiet notification. These fixtures do
# not simulate Pushy transport delivery or establish Galaxy/Doze latency.
run_independent_test 'com.yazsras.astravar.data.CampaignWakeNativeTest#nativeWakeAuthenticationAndReplayRemainStrictWithScreenOff' native-campaign-wake-auth-screen-off.txt
run_independent_test 'com.yazsras.astravar.data.CampaignWakeNativeTest#nativePauseBlocksAutomaticWorkAndRetainsManualChoice' native-campaign-pause-manual.txt
run_independent_test 'com.yazsras.astravar.data.CampaignWakeNativeTest#nativeInjectedWakeSchedulingRespectsBatterySaverDozeAndPause' native-campaign-power-state-scheduling.txt
run_independent_test 'com.yazsras.astravar.data.CampaignWakeNativeTest#nativeQuietNotificationHasImmutableActionsAndPausedScreenshot' native-campaign-notification.txt
# Production Compose component, native clipboard, keyboard and font-scale checks.
run_independent_test 'com.yazsras.astravar.ui.PairingCodeClipboardTest#tap_copies_exact_displayed_code_and_announces_feedback' native-pairing-code-copy.txt
run_independent_test 'com.yazsras.astravar.ui.PairingCodeClipboardTest#keyboard_activation_and_large_text_preserve_the_exact_code' native-pairing-code-keyboard.txt
if (( astravar_independent_failures > 0 )); then
  printf 'Independent native test failures: %s; see independent-tests.tsv and individual logs.\n' "$astravar_independent_failures" >&2
  exit 1
fi
timeout 10s adb -s "$astravar_serial" pull "/sdcard/Android/data/$astravar_product/files/campaign-power-state-scheduling.json" "$astravar_output/campaign-power-state-scheduling.json" >/dev/null
git rev-parse HEAD >"$astravar_output/SOURCE_COMMIT.txt"
python3 - "$astravar_output" <<'PY'
import json,math,os,pathlib,statistics,sys
p=pathlib.Path(sys.argv[1]); power=json.loads((p/'campaign-power-state-scheduling.json').read_text())
(p/'SCOPE.json').write_text(json.dumps({'fictionalOnly':True,'emulatorApi':36,
 'nativeHttps':True,'nativeAndroidKeyStore':True,'productionComposeApprovalAndRejection':True,
 'forceStopRestart':True,'separateDatabaseValidatedBackupRestore':True,'sameDebugKeyUpdate':'published35 to candidate'+os.environ['ASTRAVAR_VERSION_CODE'],
 'actualOwnerSigningIdentityTestedInCi':False,'realChatGptHostInvocation':False,'physicalGalaxy':False,'nativeClipboardExactCopy':True,'nativeKeyboardActivation':True,'nativeLargeFontScale':2,'nativeCampaignRoomDomainTransactions':True,'campaignRoomPersistenceAfterCloseReopen':True,'campaignHttps':True,'campaignHostedMcpAcceptance':False,'nativeWakeAuthentication':True,'nativeEncryptedPushToken':True,'nativeDuplicateWakeDurability':True,'nativeWorkManagerPause':True,'nativeQuietNotification':True,'actualPushyDelivery':False,'actualDozeWakeLatency':False,'nativePowerStateScheduling':power,
 'isolatedGatewaySimulatesSitesIdentity':True,'noProductionTlsChanges':True,'temporaryEmulatorHosts':True,'normalResolverForOsValidation':True,'networkValidationForced':False},indent=2)+'\n')
rows=[]
for line in (p/'gateway.log').read_text().splitlines():
    try:
        row=json.loads(line)
        if isinstance(row,dict) and row.get('syntheticOnly') and 'elapsedMs' in row: rows.append(row)
    except (ValueError,TypeError): pass
latency=sorted(row['elapsedMs'] for row in rows)
assert latency, 'Actual synthetic HTTPS protocol timing evidence required'
(p/'PROTOCOL-METRICS.json').write_text(json.dumps({'syntheticOnly':True,'requestCount':len(rows),
 'medianMs':statistics.median(latency),'p95Ms':latency[math.ceil(len(latency)*0.95)-1],'maximumMs':max(latency),
 'expectedNegativeTestResponses':sum(row['status']>=400 for row in rows),
 'scope':'Local native TLS fixture includes intentional offline/security failures; not production reliability or ChatGPT latency.'},indent=2)+'\n')
PY
