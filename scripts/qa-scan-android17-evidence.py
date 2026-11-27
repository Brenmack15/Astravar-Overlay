import re,sys,zipfile
from pathlib import Path
job='Historical upgrade and native acceptance'
out=Path('report')
out.mkdir(exist_ok=True)
def diagnostic(lines):
    last_test=None
    for i,line in enumerate(lines):
        if 'INSTRUMENTATION_STATUS: test=' in line:
            last_test=line.split('test=')[-1].strip()
        if re.search(r'INSTRUMENTATION_STATUS: stack=|FAILURES!!!|^.*?\b(?:AssertionError|AssertionFailedError|IllegalStateException|INSTRUMENTATION_FAILED|Process crashed)\b|##\[error\]Process completed',line) and '\x1b' not in line:
            print('observed method:',last_test,'line:',line[:950])
            for l in lines[max(0,i-2):i+9]:
                if re.search(r'Expected|Actual|androidTest|\.kt:\d+|Assertion|FAILURES|permission denied|No such file|was not|never appeared',l):
                    print(l[:800])
    print('Last observed Android 17 method:',last_test)
def inspect_run(path):
    if not zipfile.is_zipfile(path):
        print(path,'has no downloadable ZIP body');return
    with zipfile.ZipFile(path) as arch:
        members=[n for n in arch.namelist() if job.casefold() in n.casefold() or ('native' in n.lower() and '14_' in n)]
        print('Partial Actions archive:',len(arch.namelist()),'members; native entries:',members[:40])
        for name in members:
            if re.search(r'[/_]14[_-]|Android 17 authorization',name,re.I):
                data=arch.read(name).decode('utf-8-sig',errors='replace');diagnostic(data.splitlines())
def inspect_product(path):
    if not zipfile.is_zipfile(path):
        print(path,'has no published native artifact');return
    with zipfile.ZipFile(path) as arch:
        names=arch.namelist()
        members=[n for n in names if n.endswith('.txt') and (n.startswith('validation/owner-hp-native/') or n.startswith('validation/ascension-api37/'))]
        for name in members:
            if not re.search(r'(?:bootstrap-|notification-emblem|notion-public-import|native-campaign-https|native-roll-|cloud-native-|seedAscensionCode58|verifyAscensionOnAndroid17)',Path(name).name): continue
            data=arch.read(name).decode('utf-8-sig',errors='replace')
            if 'OK (1 test)' in data: print('PASS',name)
            else:
                print('UNVERIFIED/FAILED',name, 'length',len(data)); diagnostic(data.splitlines())
        print('API37 screenshot members:',[Path(n).name for n in names if re.search(r'api37-(light|dark)',n)])
if __name__=='__main__':
    inspect_run('attempt.zip');inspect_product('native.zip')
