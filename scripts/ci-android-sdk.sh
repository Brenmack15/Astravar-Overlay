#!/usr/bin/env bash
# Prefer runner-provided packages; install only absent components. Never disable TLS.
set -euo pipefail
astravar_sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/usr/local/lib/android/sdk}}"
export ANDROID_HOME="$astravar_sdk" ANDROID_SDK_ROOT="$astravar_sdk"
astravar_manager=$(command -v sdkmanager || true)
if [[ -z "$astravar_manager" ]]; then
  astravar_manager=$(find "$astravar_sdk/cmdline-tools" -path '*/bin/sdkmanager' -type f 2>/dev/null | sort -V | tail -1 || true)
fi
if [[ -z "$astravar_manager" ]]; then
  mkdir -p "$astravar_sdk/cmdline-tools"
  curl --connect-timeout 15 --max-time 150 -fsSL https://dl.google.com/android/repository/commandlinetools-linux-13114758_latest.zip -o "$RUNNER_TEMP/astravar-cli.zip"
  echo '7ec965280a073311c339e571cd5de778b9975026cfcbe79f2b1cdcb1e15317ee  '"$RUNNER_TEMP/astravar-cli.zip" | sha256sum -c -
  unzip -q "$RUNNER_TEMP/astravar-cli.zip" -d "$astravar_sdk/cmdline-tools"
  mv "$astravar_sdk/cmdline-tools/cmdline-tools" "$astravar_sdk/cmdline-tools/19.0"
  astravar_manager="$astravar_sdk/cmdline-tools/19.0/bin/sdkmanager"
fi
astravar_packages=()
[[ -f "$astravar_sdk/platforms/android-36/android.jar" ]] || astravar_packages+=('platforms;android-36')
[[ -x "$astravar_sdk/build-tools/35.0.0/apksigner" ]] || astravar_packages+=('build-tools;35.0.0')
[[ -x "$astravar_sdk/platform-tools/adb" ]] || astravar_packages+=('platform-tools')
if [[ "${1:-}" == native ]]; then
  [[ -x "$astravar_sdk/emulator/emulator" ]] || astravar_packages+=('emulator')
  [[ -f "$astravar_sdk/system-images/android-36/google_apis/x86_64/package.xml" ]] || astravar_packages+=('system-images;android-36;google_apis;x86_64')
fi
if (( ${#astravar_packages[@]} )); then
  # sdkmanager consumes only the required number of answers; avoid pipefail SIGPIPE.
  timeout -k 5s 120s "$astravar_manager" --licenses < <(printf 'y\n%.0s' {1..100}) >/dev/null
  timeout -k 10s 360s "$astravar_manager" "${astravar_packages[@]}"
fi
printf 'ANDROID_HOME=%s\nANDROID_SDK_ROOT=%s\n' "$astravar_sdk" "$astravar_sdk" >> "$GITHUB_ENV"
printf '%s\n%s\n' "$(dirname "$astravar_manager")" "$astravar_sdk/platform-tools" >> "$GITHUB_PATH"
