"""Prepare synthetic APKs from the original 90ea run for one diagnosed API-37 method."""
import hashlib,json,os,subprocess,sys,zipfile
from pathlib import Path
ref='90ea56725f4bd11c2741823f8039c0bc37e28203'
original=45953670350
repo='Brenmack15/Astravar-Overlay'
output=Path(sys.argv[1]);output.mkdir(exist_ok=True,parents=True)
def api(path):
    return subprocess.check_output(['gh','api','/repos/'+repo+path])
files=json.loads(api('/actions/runs/'+str(original)+'/artifacts'))['artifacts']
item=next(x for x in files if x['name']=='android-verification')
assert item['workflow_run']['head_sha']==ref and not item['expired']
assert item['id']==15180213783 and item['digest']=='sha256:2c1f7965b7e8d32a130a6f10139e2413d05dfe3208c0b7f3352604cd0cf14012'
zip_path=output/'original.zip'
with zip_path.open('wb') as f:
    subprocess.run(['gh','api','/repos/'+repo+'/actions/artifacts/'+str(item['id'])+'/zip'],stdout=f,check=True)
assert 'sha256:'+hashlib.file_digest(zip_path.open('rb'),'sha256').hexdigest()==item['digest']
root=Path(os.environ['ANDROID_HOME'])/'build-tools/35.0.0'
with zipfile.ZipFile(zip_path) as z:
    paths=['app/build/outputs/apk/debug/app-debug.apk','app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk','app/build/outputs/apk/release/app-release.apk','overlay-target/build/outputs/apk/debug/overlay-target-debug.apk','overlay-target/build/outputs/apk/androidTest/debug/overlay-target-debug-androidTest.apk','validation/upgrade-baseline/code58-ci.apk','validation/upgrade-baseline/code58-provenance.json']
    for p in paths:
        data=z.read(p);target=output/p;target.parent.mkdir(parents=True,exist_ok=True);target.write_bytes(data)
        if p.endswith('.apk'): subprocess.run([str(root/'apksigner'),'verify',str(target)],check=True)
print('Original Android 17 inputs',original,item['id'],item['digest'],ref,flush=True)
