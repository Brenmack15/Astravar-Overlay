#!/usr/bin/env bash
# Frozen code53 debug installation for a real KeyStore-preserving code54 update.
# This isolated CI package/key is never an owner-compatible release artifact.
set -euo pipefail
: "${RUNNER_TEMP:?Isolated CI runner required}"
: "${ANDROID_HOME:?Android SDK required}"
: "${GITHUB_ENV:?CI environment output required}"
astravar_frozen_sha=718a9e24bf856cf7e8dee32673ab75270bdd90c4
astravar_baseline_dir=$(mktemp -d "$RUNNER_TEMP/astravar-bootstrap-baseline.XXXXXX")
astravar_baseline_registered=false
cleanup() {
  if [[ "$astravar_baseline_registered" == true ]]; then
    git worktree remove --force "$astravar_baseline_dir"
  else
    rmdir "$astravar_baseline_dir"
  fi
}
trap cleanup EXIT
timeout -k 5s 60s git fetch --no-tags --depth=1 origin "$astravar_frozen_sha"
git worktree add --detach "$astravar_baseline_dir" "$astravar_frozen_sha"
astravar_baseline_registered=true
test "$(git -C "$astravar_baseline_dir" rev-parse HEAD)" = "$astravar_frozen_sha"
(
  cd "$astravar_baseline_dir"
  ASTRAVAR_VERSION_CODE=53 ASTRAVAR_VERSION_NAME=4.0.0-unification2 \
    timeout -k 10s 900s bash ./gradlew --no-daemon --console=plain --build-cache :app:assembleDebug
)
mkdir -p validation/upgrade-baseline
cp "$astravar_baseline_dir/app/build/outputs/apk/debug/app-debug.apk" validation/upgrade-baseline/code53-debug-ci.apk
python3 - "$ANDROID_HOME" "$astravar_frozen_sha" <<'PY'
import hashlib,json,pathlib,re,subprocess,sys
sdk,source=sys.argv[1:];tools=pathlib.Path(sdk)/'build-tools/35.0.0'
old=pathlib.Path('validation/upgrade-baseline/code53-debug-ci.apk')
candidate=pathlib.Path('app/build/outputs/apk/debug/app-debug.apk')
def cert(apk):
    output=subprocess.check_output([str(tools/'apksigner'),'verify','--verbose','--print-certs',str(apk)],text=True,timeout=30)
    values=re.findall(r'Signer #\d+ certificate SHA-256 digest: ([0-9a-fA-F]{64})',output)
    assert len(values)==1,'Expected one verified debug signer'
    return values[0].lower()
signature=cert(old)
assert signature==cert(candidate),'The update must retain the same disposable debug signer'
assert signature!='4c5cb0113cae5d0dee2619ee30f5b3c593d4aa897e1fa38dee7ed5561ec4e847','Owner signer forbidden in CI'
badging=subprocess.check_output([str(tools/'aapt'),'dump','badging',str(old)],text=True,timeout=30)
assert "package: name='com.yazsras.astravar.v2uidebug' versionCode='53'" in badging
assert "versionName='4.0.0-unification2-ui-rebuild'" in badging
assert 'application-debuggable' in badging
old.with_suffix('.json').write_text(json.dumps({'source_sha':source,'version_code':53,
 'sha256':hashlib.sha256(old.read_bytes()).hexdigest(),'certificate_sha256':signature,
 'variant':'debug','ownerCompatible':False},indent=2)+'\n')
PY
echo 'ASTRAVAR_BASELINE_DEBUG_APK=validation/upgrade-baseline/code53-debug-ci.apk' >> "$GITHUB_ENV"
