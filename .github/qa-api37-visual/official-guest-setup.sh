verify_owned_emulator() {
  [[ "$astravar_verified_emulator" == 1 ]] || return 1
  kill -0 "$astravar_emulator_pid" 2>/dev/null || return 1
  [[ "$(timeout 5s adb -s "$astravar_serial" shell getprop ro.kernel.qemu | tr -d '\r')" == 1 ]] || return 1
  [[ "$(timeout 5s adb -s "$astravar_serial" emu avd name | tr -d '\r' | awk 'NR == 1 { print }')" == AstravarHpCI ]] || return 1
  [[ "$(timeout 5s adb -s "$astravar_serial" shell getprop ro.build.version.sdk | tr -d '\r')" == "$astravar_expected_api" ]] || return 1
}
root_owned_emulator() {
  local astravar_phase=$1 astravar_root_status=0 astravar_uid astravar_attempt
  local astravar_root_log="$astravar_output/$astravar_phase.txt"
  verify_owned_emulator || return 1
  timeout 15s adb -s "$astravar_serial" root >"$astravar_root_log" 2>&1 || astravar_root_status=$?
  cat "$astravar_root_log"
  if [[ "$astravar_root_status" != 0 ]]; then
    # Reject timeouts and explicit denials. The observed Android 17 restart can
    # return 1/255 with a closed transport (or no response at all).
    [[ "$astravar_root_status" == 1 || "$astravar_root_status" == 255 ]] || return 1
    if [[ -s "$astravar_root_log" ]]; then
      grep -Eq '^(adb: unable to connect for root: closed|error: closed)$' "$astravar_root_log" || return 1
      if grep -Evq '^(adb: unable to connect for root: closed|error: closed)$' "$astravar_root_log"; then return 1; fi
    fi
  fi
  if grep -Eqi 'cannot run as root|permission denied|unauthorized' "$astravar_root_log"; then return 1; fi
  timeout 15s adb -s "$astravar_serial" wait-for-device || return 1
  verify_owned_emulator || return 1
  for astravar_attempt in $(seq 1 5); do
    kill -0 "$astravar_emulator_pid" 2>/dev/null || return 1
    astravar_uid=$(timeout 3s adb -s "$astravar_serial" shell id -u 2>/dev/null | tr -d '\r' || true)
    if [[ "$astravar_uid" == 0 ]]; then
      verify_owned_emulator || return 1
      printf '%s\n' 'Verified owned emulator adbd uid=0.' >>"$astravar_root_log"
      return 0
    fi
    sleep 1
  done
  printf '%s\n' 'Owned emulator root restart did not establish uid=0.' >&2
  return 1
}
root_owned_emulator initial-root
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
root_owned_emulator remounted-root
timeout -k 5s 30s adb -s "$astravar_serial" remount >>"$astravar_output/hosts-remount.txt" 2>&1
