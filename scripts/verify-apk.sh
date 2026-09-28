#!/usr/bin/env bash
set -euo pipefail
apk=${1:?Pass the actual signed APK path}
: "${ANDROID_HOME:?Set ANDROID_HOME to your SDK}"
"$ANDROID_HOME/build-tools/35.0.0/apksigner" verify --verbose --print-certs "$apk"
"$ANDROID_HOME/build-tools/35.0.0/zipalign" -c -P 16 4 "$apk"
sha256sum "$apk"
"$ANDROID_HOME/build-tools/35.0.0/aapt" dump badging "$apk"
