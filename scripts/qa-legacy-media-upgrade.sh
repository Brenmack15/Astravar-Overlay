#!/usr/bin/env bash
set -euo pipefail
export MEDIA_APKS="$RUNNER_TEMP/verified-media-apks"
export MEDIA_EVIDENCE="$GITHUB_WORKSPACE/validation/legacy-media-upgrade"
mkdir -p "$MEDIA_APKS" "$MEDIA_EVIDENCE"
artifact_id=15176732893
expected_artifact=f9ed05d8cd1638d541b949cfd1dda73978672551680f26d7d18145b7ac0162a6
endpoint="/repos/$GITHUB_REPOSITORY/actions/artifacts/$artifact_id"
test "$(gh api "$endpoint" --jq '.workflow_run.id')" = 45945526008
test "$(gh api "$endpoint" --jq '.workflow_run.head_sha')" = 2fb644414e8e57c135ab61ec78ba557f6b30b65e
gh api "$endpoint/zip" > "$MEDIA_APKS/verified.zip"
echo "$expected_artifact  $MEDIA_APKS/verified.zip" | sha256sum -c -
python3 - <<'PY'
import json,os,zipfile,re,subprocess,hashlib
from pathlib import Path
root=Path(os.environ['MEDIA_APKS'])
with zipfile.ZipFile(root/'verified.zip') as a:
    old=a.read('validation/upgrade-baseline/code58-ci.apk')
    new=a.read('app/build/outputs/apk/release/app-release.apk')
    provenance=json.loads(a.read('validation/upgrade-baseline/code58-provenance.json'))
    assert provenance['inputs']['source_sha']=='883198a3140ae2b707b31f81829c94cd821189b9' and provenance['owner_compatible'] is False
    assert provenance['output_apk_sha256']==hashlib.sha256(old).hexdigest()
tools=Path(os.environ['ANDROID_HOME'])/'build-tools'/'35.0.0'
sig=[]
products={}
for name,data,code,version in [('old',old,58,'4.0.0-convergence5'),('new',new,59,'4.1.0')]:
    apk=root/(name+'.apk');apk.write_bytes(data)
    result=subprocess.check_output([str(tools/'apksigner'),'verify','--verbose','--print-certs',str(apk)],text=True)
    assert 'CN=Astravar CI test only' in result, 'Both APKs must use the CI signer'
    certificate=re.search(r'Signer #1 certificate SHA-256 digest: ([0-9a-f]{64})',result).group(1)
    sig.append(certificate)
    badging=subprocess.check_output([str(tools/'aapt'),'dump','badging',str(apk)],text=True)
    assert "package: name='com.yazsras.astravar' versionCode='%s' versionName='%s'"%(code,version) in badging
    assert 'application-debuggable' not in badging
    subprocess.check_call([str(tools/'zipalign'),'-c','-P','16','4',str(apk)])
    products[name]={'versionCode':code,'versionName':version,'apkSha256':hashlib.sha256(data).hexdigest(),'source':provenance['inputs']['source_sha'] if name=='old' else '2fb644414e8e57c135ab61ec78ba557f6b30b65e'}
assert sig[0]==sig[1]==provenance['certificate_sha256']
product={'certificate':sig[0],'ownerSigned':False,'package':'com.yazsras.astravar','artifacts':products,'sourceRun':45945526008,'sourceArtifact':15176732893}
(root/'provenance.json').write_text(json.dumps(product,indent=2))
(Path(os.environ['MEDIA_EVIDENCE'])/'apks.json').write_text(json.dumps(product,indent=2))
print(json.dumps(product))
PY
export ANDROID_AVD_HOME="$RUNNER_TEMP/media-proof-avd"
mkdir -p "$ANDROID_AVD_HOME"
serial=emulator-5576
sudo chmod 666 /dev/kvm
printf 'no\n' | avdmanager create avd -k 'system-images;android-37.0;default;x86_64' -n AscensionMediaOnly -p "$ANDROID_AVD_HOME/AscensionMediaOnly.avd" --device pixel_6
"$ANDROID_HOME/emulator/emulator" -avd AscensionMediaOnly -port 5576 -accel on -no-window -no-audio -no-snapshot -no-boot-anim -gpu swiftshader -memory 4096 >"$RUNNER_TEMP/media-avd.log" 2>&1 &
vm_pid=$!
cleanup() {
  result=$?
  trap - EXIT
  if [[ "$(adb -s "$serial" emu avd name 2>/dev/null | tr -d '\r' | head -1)" == AscensionMediaOnly ]]; then
    if (( result!=0 )); then
      adb -s "$serial" exec-out screencap -p >"$MEDIA_EVIDENCE/last-screen.png" || true
      adb -s "$serial" shell dumpsys window | rg -m3 'mCurrentFocus|mFocusedApp' || true
    fi
    adb -s "$serial" emu kill >/dev/null 2>&1 || true
  fi
  kill "$vm_pid" 2>/dev/null || true
  exit "$result"
}
trap cleanup EXIT
timeout 120s adb -s "$serial" wait-for-device
for i in $(seq 1 120); do [[ "$(adb -s "$serial" shell getprop sys.boot_completed | tr -d '\r')" == 1 ]] && break; kill -0 "$vm_pid"; sleep 2; done
test "$(adb -s "$serial" shell getprop sys.boot_completed | tr -d '\r')" = 1
test "$(adb -s "$serial" emu avd name | tr -d '\r' | head -1)" = AscensionMediaOnly
test "$(adb -s "$serial" shell getprop ro.kernel.qemu | tr -d '\r')" = 1
test "$(adb -s "$serial" shell getprop ro.build.version.sdk | tr -d '\r')" = 37
adb -s "$serial" root
timeout 60s adb -s "$serial" wait-for-device
test "$(adb -s "$serial" shell id -u | tr -d '\r')" = 0
adb -s "$serial" shell cmd connectivity airplane-mode enable
adb -s "$serial" shell settings put global window_animation_scale 0
adb -s "$serial" shell settings put global transition_animation_scale 0
adb -s "$serial" shell settings put global animator_duration_scale 0
adb -s "$serial" shell settings put secure show_ime_with_hard_keyboard 1
adb -s "$serial" shell input keyevent 82
timeout -k 5s 450s python3 scripts/qa-legacy-media-upgrade.py
