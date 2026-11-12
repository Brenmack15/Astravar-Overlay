#!/usr/bin/env bash
# Narrow original-owner APK upgrade experiment on a new, accelerated CI emulator.
# APK validation/decryption is performed by the receiver before this script.
# No signing material, APK, raw database or unrestricted diagnostic is published.
set +x
set -euo pipefail
umask 077
: "${RUNNER_TEMP:?An isolated GitHub Actions runner is required}"
: "${ANDROID_HOME:?A prepared Android SDK is required}"
: "${OWNER_UPGRADE_PRIVATE:?Private receiver output is required}"
: "${OWNER_UPGRADE_EVIDENCE:?A fresh synthetic evidence directory is required}"
test "${GITHUB_ACTIONS:-}" = true
test "${OWNER_UPGRADE_APPROVED:-}" = 1
test -c /dev/kvm && test -r /dev/kvm && test -w /dev/kvm

# Refuse symlinked, overlapping or non-temporary plaintext locations. Do not
# write raw output to the checkout (which ordinary workflows may upload).
python3 - <<'PY'
import hashlib, json, os, re
from pathlib import Path

root = Path(os.environ['RUNNER_TEMP']).resolve(strict=True)
private = Path(os.environ['OWNER_UPGRADE_PRIVATE'])
evidence = Path(os.environ['OWNER_UPGRADE_EVIDENCE'])
for path in (private, evidence):
    if not path.is_absolute() or path.resolve() != path or root not in path.parents:
        raise SystemExit('Owner upgrade paths must be real, absolute children of RUNNER_TEMP')
if private == evidence or private in evidence.parents or evidence in private.parents:
    raise SystemExit('Plaintext and public evidence directories must be separate')
if not private.is_dir() or not (private / 'apk-validation.json').is_file():
    raise SystemExit('Receiver APK validation is required before emulator launch')
marker = private / 'apk-validation.json'
if marker.is_symlink() or marker.stat().st_size > 65536:
    raise SystemExit('Unsafe receiver validation record')
validation = json.loads(marker.read_text())
if validation.get('schema') != 1 or validation.get('validated') is not True:
    raise SystemExit('Receiver has not validated these inputs')
for key in ('candidate_source', 'baseline_source'):
    if not re.fullmatch(r'[0-9a-f]{40}', validation.get(key, '')):
        raise SystemExit('Receiver source binding is missing')
certificate = validation.get('owner_certificate_sha256', '')
if certificate != '4c5cb0113cae5d0dee2619ee30f5b3c593d4aa897e1fa38dee7ed5561ec4e847':
    raise SystemExit('Complete original-owner certificate binding is required')
expected = {'baseline': ('com.yazsras.astravar', 49),
            'candidate': ('com.yazsras.astravar', 50),
            'observer': ('com.yazsras.astravar.target', 1),
            'observer-test': ('com.yazsras.astravar.target.test', 1)}
for name in ('baseline.apk', 'candidate.apk', 'observer.apk', 'observer-test.apk'):
    path = private / name
    if path.is_symlink() or not path.is_file() or path.stat().st_size == 0:
        raise SystemExit('A validated input APK is missing or unsafe')
    role = name[:-4]
    proof = validation.get('apks', {}).get(role, {})
    if (proof.get('package'), proof.get('version_code')) != expected[role]:
        raise SystemExit('Receiver package/version binding is incorrect')
    if not re.fullmatch(r'[0-9a-f]{64}', proof.get('certificate_sha256', '')):
        raise SystemExit('An APK certificate binding is missing')
    if role in ('baseline', 'candidate') and proof['certificate_sha256'] != certificate:
        raise SystemExit('Owner APK certificate bindings disagree')
    with path.open('rb') as handle:
        digest = hashlib.file_digest(handle, 'sha256').hexdigest()
    if digest != proof.get('sha256'):
        raise SystemExit('An APK no longer matches receiver validation')
if validation['apks']['observer']['certificate_sha256'] != validation['apks']['observer-test']['certificate_sha256']:
    raise SystemExit('Observer and instrumentation signer bindings disagree')
if evidence.exists():
    raise SystemExit('Native evidence directory must be new for this attempt')
evidence.mkdir(mode=0o700)
PY

native_private=$(mktemp -d "$OWNER_UPGRADE_PRIVATE/native.XXXXXXXX")
export OWNER_NATIVE_PRIVATE="$native_private"
export ANDROID_AVD_HOME="$native_private/avd"
export ANDROID_USER_HOME="$native_private/android-user"
export ANDROID_ADB_SERVER_PORT=5049
export ADB_SERVER_SOCKET=tcp:127.0.0.1:5049
unset ADBHOST ANDROID_ADB_SERVER_ADDRESS ADB_VENDOR_KEYS
mkdir -m 700 "$ANDROID_AVD_HOME" "$ANDROID_USER_HOME"
adb_bin="$ANDROID_HOME/platform-tools/adb"
avdmanager_bin="$ANDROID_HOME/cmdline-tools/19.0/bin/avdmanager"
serial=emulator-5580
product=com.yazsras.astravar
observer=com.yazsras.astravar.target
avd_name=GenesisOwnerUpgradeCI
emulator_pid=
owns_adb=0
verified_emulator=0
native_passed=0
stage=preflight
started=$SECONDS
: >"$native_private/stages.tsv"

# Only these four successful-assertion captures may become public. Failure
# screenshots, hierarchy dumps, raw logs and all copied stores remain private.
publish_screenshots() {
  local image
  for image in genesis-before-code49-sheet.png genesis-after-code50-sheet.png genesis-upgrade-companion.png genesis-canonical-write-after-restart.png; do
    if timeout 5s "$adb_bin" -P 5049 -s "$serial" shell test -f "/sdcard/Android/data/$observer/files/$image"; then
      timeout 20s "$adb_bin" -P 5049 -s "$serial" pull "/sdcard/Android/data/$observer/files/$image" "$native_private/$image" >"$native_private/pull-$image.log" 2>&1 || continue
    fi
  done
  python3 - <<'PY'
import os, sys
from pathlib import Path
sys.path.insert(0, str(Path('scripts').resolve()))
from owner_upgrade_png import MAX_PNG_BYTES, sanitize_png
source = Path(os.environ['OWNER_NATIVE_PRIVATE'])
destination = Path(os.environ['OWNER_UPGRADE_EVIDENCE'])
names = ('genesis-before-code49-sheet.png', 'genesis-after-code50-sheet.png',
         'genesis-upgrade-companion.png', 'genesis-canonical-write-after-restart.png')
for name in names:
    path = source / name
    if not path.is_file() or path.is_symlink() or not 32 < path.stat().st_size <= MAX_PNG_BYTES:
        continue
    try:
        cleaned = sanitize_png(path.read_bytes())
    except ValueError:
        continue
    (destination / name).write_bytes(cleaned)
PY
}

cleanup() {
  local exit_status=$?
  trap - EXIT INT TERM
  # Leave a conservative result even if an outer deadline interrupts cleanup.
  OWNER_NATIVE_EXIT="$exit_status" OWNER_NATIVE_STAGE="$stage" python3 - <<'PY'
import json, os
from pathlib import Path
(Path(os.environ['OWNER_UPGRADE_EVIDENCE']) / 'native-result.json').write_text(json.dumps({
    'format': 'astravar-owner-upgrade-native-v1', 'passed': False,
    'synthetic_emulator_only': True, 'physical_device_verified': False,
    'cleanup_complete': False, 'exit_code': int(os.environ['OWNER_NATIVE_EXIT']),
    'last_stage': os.environ['OWNER_NATIVE_STAGE']}, indent=2) + '\n')
PY
  if [ "$verified_emulator" = 1 ] && [ "$exit_status" != 130 ] && [ "$exit_status" != 143 ]; then
    timeout 15s "$adb_bin" -P 5049 -s "$serial" logcat -d >"$native_private/logcat-final.txt" 2>&1 || true
    publish_screenshots >/dev/null 2>&1 || true
    timeout 5s "$adb_bin" -P 5049 -s "$serial" emu kill >"$native_private/emulator-stop.log" 2>&1 || true
  fi
  if [ -n "$emulator_pid" ]; then
    # The emulator has its own setsid process group; include launcher children.
    kill -TERM -- "-$emulator_pid" 2>/dev/null || true
    for ignored in $(seq 1 5); do
      kill -0 "$emulator_pid" 2>/dev/null || break
      sleep 1
    done
    kill -KILL -- "-$emulator_pid" 2>/dev/null || true
    wait "$emulator_pid" 2>/dev/null || true
  fi
  if [ "$owns_adb" = 1 ]; then
    timeout 5s "$adb_bin" -P 5049 kill-server >"$native_private/adb-stop.log" 2>&1 || true
  fi
  OWNER_NATIVE_EXIT="$exit_status" OWNER_NATIVE_STAGE="$stage" OWNER_NATIVE_PASSED="$native_passed" OWNER_NATIVE_ELAPSED="$((SECONDS - started))" OWNER_NATIVE_VERIFIED="$verified_emulator" python3 - <<'PY'
import hashlib, json, os, re
from pathlib import Path
private = Path(os.environ['OWNER_NATIVE_PRIVATE'])
public = Path(os.environ['OWNER_UPGRADE_EVIDENCE'])
stages = []
for line in (private / 'stages.tsv').read_text().splitlines():
    name, status, code, seconds, digest = line.split('\t')
    stages.append({'method': name, 'status': status, 'exit_code': int(code),
                   'elapsed_seconds': int(seconds), 'private_log_sha256': digest})
    # Export framework status, never arbitrary app stdout or assertion payloads.
    transcript = []
    for line in (private / ('native-' + name + '.txt')).read_text(errors='replace').splitlines():
        if re.fullmatch(r'INSTRUMENTATION_(?:STATUS_CODE|CODE): -?[0-9]+', line) or \
           re.fullmatch(r'INSTRUMENTATION_STATUS: (?:numtests|current)=[0-9]+', line) or \
           line == 'INSTRUMENTATION_STATUS: class=com.yazsras.astravar.GenesisUpgradeInstrumentationTest' or \
           line == 'INSTRUMENTATION_STATUS: test=' + name or \
           re.fullmatch(r'OK \(1 tests?\)', line) or line == 'FAILURES!!!':
            transcript.append(line)
    transcript.append('Recorded process exit code: ' + code)
    transcript.append('Full private transcript SHA-256: ' + digest)
    (public / ('native-' + name + '.txt')).write_text('\n'.join(transcript) + '\n')
result = {'format': 'astravar-owner-upgrade-native-v1', 'synthetic_emulator_only': True,
          'physical_device_verified': False, 'owner_signing_key_received': False,
          'cleanup_complete': True,
          'passed': os.environ['OWNER_NATIVE_PASSED'] == '1' and os.environ['OWNER_NATIVE_EXIT'] == '0',
          'exit_code': int(os.environ['OWNER_NATIVE_EXIT']),
          'last_stage': os.environ['OWNER_NATIVE_STAGE'],
          'elapsed_seconds': int(os.environ['OWNER_NATIVE_ELAPSED']), 'native_stages': stages,
          'package': 'com.yazsras.astravar', 'old_version': 49, 'new_version': 50,
          'api_level_expected': 36, 'emulator_identity_verified': os.environ['OWNER_NATIVE_VERIFIED'] == '1',
          'acceleration_required': 'KVM',
          'uninstall_or_clear_data_used': False}
# These files are produced only after successful independent strict helpers.
for phase in ('baseline', 'preserved', 'written'):
    report = private / (phase + '-result.json')
    if report.is_file():
        try:
            proof = json.loads(report.read_text())
            result[phase] = {key: proof[key] for key in (
                'synthetic_ui_fixture_verified', 'phase', 'additive_storage_cutover_verified',
                'canonical_write_and_retained_source_verified')}
        except (ValueError, KeyError):
            result[phase] = {'independent_readback_complete': False}
package = private / 'package-result.json'
if package.is_file():
    proof = json.loads(package.read_text())
    result['package_retention'] = {key: proof[key] for key in (
        'package', 'old_version', 'new_version', 'android_user_id', 'same_android_uid', 'same_first_install_time')}
for name in ('files-before.sha256', 'files-after.sha256'):
    path = private / name
    if path.is_file():
        result[name.replace('.', '_')] = hashlib.sha256(path.read_bytes()).hexdigest()
if (private / 'files-unchanged.marker').is_file():
    result['all_stopped_store_bytes_unchanged_during_install'] = True
result['screenshots'] = {path.name: hashlib.sha256(path.read_bytes()).hexdigest()
                         for path in sorted(public.glob('*.png')) if path.is_file() and not path.is_symlink()}
(public / 'native-result.json').write_text(json.dumps(result, indent=2) + '\n')
PY
  printf 'Owner upgrade native result: %s (last stage: %s).\n' "$exit_status" "$stage"
  exit "$exit_status"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

# No reuse of an existing server, device, AVD or app data is allowed.
python3 - <<'PY'
import socket
for port in (5049, 5580, 5581):
    with socket.socket() as handle:
        handle.settimeout(0.25)
        if handle.connect_ex(('127.0.0.1', port)) == 0:
            raise SystemExit('An owner-upgrade reserved local port is already occupied')
PY
timeout 15s "$adb_bin" -P 5049 start-server >"$native_private/adb-start.log" 2>&1
owns_adb=1
timeout 10s "$adb_bin" -P 5049 devices >"$native_private/devices-before.txt" 2>&1
if grep -Eq '^emulator-5580[[:space:]]' "$native_private/devices-before.txt"; then exit 1; fi
printf 'no\n' | timeout -k 5s 45s "$avdmanager_bin" create avd --name "$avd_name" --path "$ANDROID_AVD_HOME/$avd_name.avd" --package 'system-images;android-36;google_apis;x86_64' --device pixel_6 >"$native_private/avd-create.log" 2>&1
test -f "$ANDROID_AVD_HOME/$avd_name.ini"
stage=boot
setsid "$ANDROID_HOME/emulator/emulator" -avd "$avd_name" -port 5580 -accel on -no-window -no-audio -no-snapshot -no-boot-anim -gpu swiftshader_indirect >"$native_private/emulator.log" 2>&1 &
emulator_pid=$!
timeout -k 5s 120s "$adb_bin" -P 5049 -s "$serial" wait-for-device >"$native_private/wait-for-device.log" 2>&1
boot_deadline=$((SECONDS + 300))
booted=0
while ((SECONDS < boot_deadline)); do
  kill -0 "$emulator_pid"
  if [ "$(timeout 5s "$adb_bin" -P 5049 -s "$serial" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ]; then booted=1; break; fi
  sleep 2
done
test "$booted" = 1
test "$(timeout 5s "$adb_bin" -P 5049 -s "$serial" shell getprop ro.kernel.qemu | tr -d '\r')" = 1
test "$(timeout 5s "$adb_bin" -P 5049 -s "$serial" shell getprop ro.build.version.sdk | tr -d '\r')" = 36
test "$(timeout 5s "$adb_bin" -P 5049 -s "$serial" emu avd name | tr -d '\r' | head -n 1)" = "$avd_name"
verified_emulator=1
timeout 10s "$adb_bin" -P 5049 -s "$serial" shell input keyevent 82 >"$native_private/unlock.log" 2>&1
for scale in window_animation_scale transition_animation_scale animator_duration_scale; do
  timeout 10s "$adb_bin" -P 5049 -s "$serial" shell settings put global "$scale" 0 >"$native_private/animation-$scale.log" 2>&1
done

install_apk() {
  local name=$1
  timeout -k 5s 90s "$adb_bin" -P 5049 -s "$serial" install "$OWNER_UPGRADE_PRIVATE/$name.apk" >"$native_private/install-$name.txt" 2>&1
  grep -Fxq Success "$native_private/install-$name.txt"
}
run_stage() {
  local method=$1 budget=$2 beginning=$SECONDS code=0 status=FAIL digest
  stage="$method"
  printf 'Running unchanged owner upgrade method: %s\n' "$method"
  timeout -k 5s "${budget}s" "$adb_bin" -P 5049 -s "$serial" shell am instrument -w -r -e astravarPackage "$product" -e class "com.yazsras.astravar.GenesisUpgradeInstrumentationTest#$method" "$observer.test/androidx.test.runner.AndroidJUnitRunner" >"$native_private/native-$method.txt" 2>&1 || code=$?
  if [ "$code" = 0 ] && grep -Eq '^OK \(1 tests?\)' "$native_private/native-$method.txt" && ! grep -Eq 'FAILURES|INSTRUMENTATION_FAILED|Process crashed' "$native_private/native-$method.txt"; then status=PASS; fi
  digest=$(sha256sum "$native_private/native-$method.txt" | cut -d ' ' -f 1)
  printf '%s\t%s\t%s\t%s\t%s\n' "$method" "$status" "$code" "$((SECONDS - beginning))" "$digest" >>"$native_private/stages.tsv"
  test "$status" = PASS
}
stop_product() { timeout 10s "$adb_bin" -P 5049 -s "$serial" shell am force-stop "$product" >"$native_private/force-stop.log" 2>&1; }
snapshot_stores() {
  timeout 30s "$adb_bin" -P 5049 -s "$serial" shell 'cd /data/user/0/'"$product"' && for d in files databases shared_prefs no_backup; do if test -d "$d"; then find "$d" -type f; fi; done | sort | while read f; do sha256sum "$f"; done' | tr -d '\r' >"$1"
  test -s "$1"
  grep -q 'databases/' "$1"
}
copy_stores() {
  local destination=$1 database suffix file
  mkdir -m 700 "$destination"
  for database in astravar.db astravar-campaign-ledger.db astravar-reforge.db; do
    for suffix in '' -wal -shm -journal; do
      file="$database$suffix"
      if timeout 5s "$adb_bin" -P 5049 -s "$serial" shell test -f "/data/user/0/$product/databases/$file"; then
        timeout 45s "$adb_bin" -P 5049 -s "$serial" pull "/data/user/0/$product/databases/$file" "$destination/$file" >"$native_private/pull-store.log" 2>&1
      fi
    done
    test -s "$destination/$database"
  done
}

stage=install_baseline_and_observers
install_apk baseline
install_apk observer
install_apk observer-test
run_stage seedCode49 720
stop_product
stage=baseline_independent_readback
timeout 15s "$adb_bin" -P 5049 -s "$serial" root >"$native_private/adb-root.log" 2>&1
timeout 15s "$adb_bin" -P 5049 -s "$serial" wait-for-device >"$native_private/adb-root-wait.log" 2>&1
timeout 10s "$adb_bin" -P 5049 -s "$serial" shell dumpsys package "$product" >"$native_private/package-before.txt"
snapshot_stores "$native_private/files-before.sha256"
copy_stores "$native_private/before"
python3 scripts/genesis-upgrade-readback.py "$native_private/before" --synthetic-emulator --expected-room-schema 9 --output "$native_private/baseline.json" >"$native_private/baseline-result.json" 2>"$native_private/baseline-readback-error.log"

stage=normal_owner_upgrade_install
# Intentionally no -d, uninstall, clear, wipe, app data copy, or fixture injection.
timeout -k 5s 90s "$adb_bin" -P 5049 -s "$serial" install -r "$OWNER_UPGRADE_PRIVATE/candidate.apk" >"$native_private/install-candidate.txt" 2>&1
grep -Fxq Success "$native_private/install-candidate.txt"
snapshot_stores "$native_private/files-after.sha256"
diff -u "$native_private/files-before.sha256" "$native_private/files-after.sha256" >"$native_private/files-diff.txt"
: >"$native_private/files-unchanged.marker"
timeout 10s "$adb_bin" -P 5049 -s "$serial" shell dumpsys package "$product" >"$native_private/package-after.txt"
python3 scripts/genesis-package-readback.py --before "$native_private/package-before.txt" --after "$native_private/package-after.txt" --package "$product" --old-version 49 --new-version 50 --output "$native_private/package-result.json" >"$native_private/package-helper-output.txt" 2>"$native_private/package-helper-error.log"

run_stage verifyAfterUpgrade 360
stop_product
stage=preserved_independent_readback
copy_stores "$native_private/after"
python3 scripts/genesis-upgrade-readback.py "$native_private/after" --synthetic-emulator --expected-room-schema 9 --compare "$native_private/baseline.json" --output "$native_private/preserved.json" >"$native_private/preserved-result.json" 2>"$native_private/preserved-readback-error.log"
run_stage canonicalWriteSurvivesRestart 240
stop_product
stage=canonical_write_independent_readback
copy_stores "$native_private/after-write"
python3 scripts/genesis-upgrade-readback.py "$native_private/after-write" --synthetic-emulator --expected-room-schema 9 --verify-write "$native_private/preserved.json" --output "$native_private/written.json" >"$native_private/written-result.json" 2>"$native_private/written-readback-error.log"
stage=complete
native_passed=1
