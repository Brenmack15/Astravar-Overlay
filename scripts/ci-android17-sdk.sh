#!/usr/bin/env bash
# Focused real Android17 compatibility; compile SDK and historical API36 stay intact.
set -euo pipefail
: "${ANDROID_HOME:?Android SDK required}"
astravar_packages=()
astravar_current_emulator() {
python3 - "$ANDROID_HOME/emulator/package.xml" <<'PY'
import pathlib,sys,xml.etree.ElementTree as ET
p=pathlib.Path(sys.argv[1])
if not p.is_file(): raise SystemExit(1)
root=ET.parse(p).getroot()
revision=root.find('.//revision')
if revision is None: raise SystemExit(1)
version=tuple(int(revision.findtext(x,'0')) for x in ('major','minor','micro'))
raise SystemExit(0 if version >= (37,3,10) else 1)
PY
}
if ! astravar_current_emulator; then
  astravar_packages+=('emulator')
fi
[[ -f "$ANDROID_HOME/system-images/android-37.0/default/x86_64/package.xml" ]] || astravar_packages+=('system-images;android-37.0;default;x86_64')
if (( ${#astravar_packages[@]} )); then
  timeout -k 5s 60s sdkmanager --licenses < <(printf 'y\n%.0s' {1..100}) >/dev/null
  timeout -k 10s 480s sdkmanager "${astravar_packages[@]}"
fi
astravar_current_emulator
test -f "$ANDROID_HOME/system-images/android-37.0/default/x86_64/package.xml"
"$ANDROID_HOME/emulator/emulator" -version
