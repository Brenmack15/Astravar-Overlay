#!/usr/bin/env bash
# Isolated emulator only: no physical phone, live Site, owner signing key or real records.
set -euo pipefail
: "${ANDROID_HOME:?Android SDK required}"
: "${RUNNER_TEMP:?Isolated CI runner required}"
: "${ASTRAVAR_BASELINE_DEBUG_APK:?Previous published debug APK required}"
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
"$ANDROID_HOME/emulator/emulator" -avd AstravarHpCI -port 5584 -accel on -dns-server 127.0.0.1 \
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
# adb remount -R performs the reboot only if required by this debug image.
timeout -k 5s 60s adb -s "$astravar_serial" remount -R >"$astravar_output/hosts-remount.txt" 2>&1
timeout -k 5s 120s adb -s "$astravar_serial" wait-for-device
astravar_remounted_booted=0
for astravar_attempt in $(seq 1 90); do
  kill -0 "$astravar_emulator_pid"
  if [[ "$(timeout 3s adb -s "$astravar_serial" shell getprop sys.boot_completed | tr -d '\r')" == 1 ]]; then astravar_remounted_booted=1; break; fi
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
  local astravar_test=$1 astravar_log=$2
  timeout -k 5s 240s adb -s "$astravar_serial" shell am instrument -w -r -e class "$astravar_test" \
    "$astravar_product.test/androidx.test.runner.AndroidJUnitRunner" | tee "$astravar_output/$astravar_log"
  grep -q '^OK (1 test' "$astravar_output/$astravar_log"
  ! grep -q 'FAILURES\|INSTRUMENTATION_FAILED\|Process crashed' "$astravar_output/$astravar_log"
}
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
grep -q 'versionCode=35' "$astravar_output/updated-package.txt"
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
git rev-parse HEAD >"$astravar_output/SOURCE_COMMIT.txt"
python3 - "$astravar_output" <<'PY'
import json,pathlib,sys
p=pathlib.Path(sys.argv[1]); (p/'SCOPE.json').write_text(json.dumps({'fictionalOnly':True,'emulatorApi':36,
 'nativeHttps':True,'nativeAndroidKeyStore':True,'productionComposeApprovalAndRejection':True,
 'forceStopRestart':True,'separateDatabaseValidatedBackupRestore':True,'sameDebugKeyUpdate':'published34 to candidate35',
 'actualOwnerSigningIdentityTestedInCi':False,'realChatGptHostInvocation':False,'physicalGalaxy':False,
 'isolatedGatewaySimulatesSitesIdentity':True,'noProductionTlsChanges':True,'temporaryEmulatorHosts':True},indent=2)+'\n')
PY
