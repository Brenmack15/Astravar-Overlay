# Sourced by the owned API37 native TLS lane. No device, process or gateway may be supplied by a caller.
set -euo pipefail
test "$astravar_bootstrap_gate" = true
test "$astravar_serial" = emulator-5584
test "$astravar_product" = com.yazsras.astravar.v2uidebug
test "$astravar_expected_api" = 37
verify_owned_emulator
astravar_cloud_evidence="$astravar_output/cloud-reconnect"
mkdir -p "$astravar_cloud_evidence"
node scripts/ci-ascension-cloud-gateway.mjs setup "$astravar_private" > "$astravar_cloud_evidence/initial-host.json"
run_test 'com.yazsras.astravar.data.AscensionCloudNativeTest#pairAndCachePreStopState' cloud-native-before.txt 300
verify_owned_emulator
timeout 10s adb -s "$astravar_serial" shell am force-stop "$astravar_product"
cloud_stopped() {
  local stamp=$1
  verify_owned_emulator
  local info
  info=$(timeout 10s adb -s "$astravar_serial" shell dumpsys package "$astravar_product")
  grep -Eq 'User 0:.*stopped=true' <<< "$info"
  local state
  state=$(timeout 10s adb -s "$astravar_serial" shell "command -v pidof >/dev/null || exit 92; if pidof $astravar_product >/dev/null; then echo running; else echo stopped; fi" | tr -d '\r')
  test "$state" = stopped
  date -u +'%FT%TZ' > "$astravar_cloud_evidence/$stamp.txt"
}
cloud_stopped before-cloud-commands
node scripts/ci-ascension-cloud-gateway.mjs offline "$astravar_private" > "$astravar_cloud_evidence/offline-host.json"
cloud_stopped after-cloud-readback
astravar_cache_dir="$astravar_private/ascension-stopped-cache"
mkdir -m 700 "$astravar_cache_dir"
astravar_cache_name=astravar-cloud-cache.db
for suffix in '' '-wal' '-shm'; do
  remote="/data/user/0/$astravar_product/databases/$astravar_cache_name$suffix"
  if timeout 10s adb -s "$astravar_serial" shell test -f "$remote"; then
    timeout 20s adb -s "$astravar_serial" pull "$remote" "$astravar_cache_dir/$astravar_cache_name$suffix" >/dev/null
  fi
done
python3 scripts/ascension-stopped-cache-readback.py "$astravar_cache_dir/$astravar_cache_name" > "$astravar_cloud_evidence/stopped-cache.json"
cloud_stopped after-independent-cache-copy
run_test 'com.yazsras.astravar.data.AscensionCloudNativeTest#reconnectVerifyEachReceiptAndCommitDeviceEdit' cloud-native-after.txt 300
node scripts/ci-ascension-cloud-gateway.mjs device-readback "$astravar_private" > "$astravar_cloud_evidence/device-edit-host.json"
adb -s "$astravar_serial" pull "/sdcard/Android/data/$astravar_product/files/ascension-cloud/." "$astravar_cloud_evidence" >/dev/null
python3 - "$astravar_cloud_evidence" <<'PY'
import json,pathlib,sys
if not __debug__: raise RuntimeError("Cross-client verification requires assertions enabled.")
p=pathlib.Path(sys.argv[1]); read=lambda name: json.loads((p/(name+'.json')).read_text())
a,b,c=map(read,['offline-host','device-edit-host','after-reconnect'])
assert read('before-stop')['revision']==read('stopped-cache')['cachedRevision']==4 and c['entryCacheRevision'] in (4,8) and a['revision']==8
assert a['pending']=='PENDING_APPROVAL' and c['pendingBeforeOwner']==a['pending']
assert [r['transactionId'] for r in a['receiptHashes']]==c['dmTransactionIds']
assert b['deviceTransactionId']==c['deviceTransactionId'] and b['revision']==c['deviceRevision']==10
assert b['priorReceiptsReverified']==4 and c['api']==37
(p/'verified.json').write_text(json.dumps({'synthetic':True,'api':37,'packageForceStoppedDuringCloudCommit':True,
    'remoteCommandsVerified':4,'ownerApprovalRevision':c['ownerRevision'],'nativeEditRevision':b['revision']},indent=2)+'\n')
PY
