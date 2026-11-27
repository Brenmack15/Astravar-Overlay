"""Read-only preservation witness around an explicit synthetic code-58 image edit."""
import base64
import hashlib
import io
import json
import os
import re
import sqlite3
import subprocess
import sys
import tempfile
import time
import xml.etree.ElementTree as ET
from pathlib import Path
from PIL import Image, ImageDraw
import numpy as np

SERIAL = 'emulator-5576'
PACKAGE = 'com.yazsras.astravar'
ENTITY = 'ArdenQaPortrait'
PICKED_FILE = '/sdcard/Download/AscensionMediaWitness.png'
DIR = Path(os.environ['MEDIA_EVIDENCE'])
APK = Path(os.environ['MEDIA_APKS'])
COLORS = [(209, 39, 62), (33, 190, 77), (55, 89, 207), (218, 157, 41)]
WIDTH = 96
STAGE = 'setup'
LAST = None

def adb(*args, timeout=25, check=True):
    return subprocess.run(['adb','-s',SERIAL,*args],capture_output=True,check=check,timeout=timeout)

def shell(*args, timeout=25):
    return adb('shell',*args,timeout=timeout).stdout.decode().strip()

def stage(label):
    global STAGE
    STAGE = label
    print('Media acceptance:', label, flush=True)

def sha(data):
    return hashlib.sha256(data).hexdigest()

def xml():
    global LAST
    result = adb('shell','uiautomator','dump','/data/local/tmp/qa-media-tree.xml',timeout=12)
    if b'dumped' not in result.stdout.lower():
        raise ValueError('UI hierarchy unavailable')
    LAST = ET.fromstring(adb('exec-out','cat','/data/local/tmp/qa-media-tree.xml').stdout)
    return LAST

def box(node):
    a = list(map(int,re.findall(r'-?\d+',node.get('bounds',''))))
    if len(a) != 4 or a[2]<=a[0] or a[3]<=a[1]: return None
    return a

def entries(tree, lab, pkg=PACKAGE):
    return [n for n in tree.iter('node') if (not pkg or n.get('package')==pkg) and box(n) and
            (n.get('text')==lab or n.get('content-desc')==lab)]

def click_box(b):
    shell('input','tap',str((b[0]+b[2])//2),str((b[1]+b[3])//2))

def observe(fn, label, seconds=18):
    end = time.monotonic()+seconds
    last_error=None
    while time.monotonic()<end:
        try:
            value=fn()
            if value is not None and value is not False: return value
        except (ET.ParseError,ValueError,subprocess.TimeoutExpired) as error:
            last_error=error
        time.sleep(.3)
    if LAST is not None:
        visible=[n.get('text') or n.get('content-desc') for n in LAST.iter('node') if (n.get('text') or n.get('content-desc'))]
        print('Observed visible labels at',label,visible[:85],flush=True)
    raise AssertionError('Missing '+label+(' ('+str(last_error)+')' if last_error else ''))

def await_label(lab,pkg=PACKAGE):
    def get():
        found=entries(xml(),lab,pkg)
        return found[-1] if found else None
    return observe(get,lab)

def tap(lab):
    node=await_label(lab)
    assert node.get('enabled')!='false',lab
    click_box(box(node))

def scroll(lab):
    for _ in range(7):
        root=xml()
        found=entries(root,lab)
        if found: return found[-1]
        candidates=[b for n in root.iter('node') if n.get('scrollable')=='true' and n.get('package')==PACKAGE and (b:=box(n))]
        if not candidates: break
        b=max(candidates,key=lambda x:(x[2]-x[0])*(x[3]-x[1]))
        x=(b[0]+b[2])//2; h=b[3]-b[1]
        shell('input','swipe',str(x),str(int(b[1]+h*.78)),str(x),str(int(b[1]+h*.30)),'390')
    return await_label(lab)

def shot():
    return Image.open(io.BytesIO(adb('exec-out','screencap','-p').stdout)).convert('RGB')

def image_location(image, bounds=None):
    region=image if bounds is None else image.crop(bounds)
    n=np.asarray(region)[::3,::3,:].astype(np.int16)
    masks=[np.max(np.abs(n-np.array(c,dtype=np.int16)),axis=2)<30 for c in COLORS]
    # Four independent quadrant samples must match in the same square and order.
    best=None
    for gap in range(2,min(n.shape[0],n.shape[1],141)//2):
        k=2*gap
        hits=masks[0][:-k,:-k]&masks[1][:-k,k:]&masks[2][k:,:-k]&masks[3][k:,k:]
        yy,xx=np.nonzero(hits)
        if len(xx)>=6:
            best=(int(np.median(xx)+gap)*3,int(np.median(yy)+gap)*3,gap*3)
    if best and bounds:
        return (best[0]+bounds[0],best[1]+bounds[1],best[2])
    return best

def new_portrait():
    img=Image.new('RGB',(WIDTH,WIDTH));d=ImageDraw.Draw(img)
    for (x,y), color in zip(((0,0),(48,0),(0,48),(48,48)),COLORS):
        d.rectangle((x,y,x+47,y+47),fill=color)
    f=DIR/'source.png';img.save(f)
    assert not shell('sh','-c',f"'test -e {PICKED_FILE} && echo occupied || true'")
    adb('push',str(f),PICKED_FILE)
    assert sha(adb('exec-out','cat',PICKED_FILE).stdout)==sha(f.read_bytes())
    shell('am','broadcast','-a','android.intent.action.MEDIA_SCANNER_SCAN_FILE','-d','file://'+PICKED_FILE)
    observe(lambda: True if 'AscensionMediaWitness.png' in shell('content','query','--uri','content://media/external/images/media','--projection','_id:_display_name') else None,'original MediaStore image',25)

def choose_picker():
    deadline=time.monotonic()+26
    picked=False
    while time.monotonic()<deadline:
        root=xml()
        if entries(root,'Remove connection image') or entries(root,'Image ready to save.'): return
        nodes=[n for n in root.iter('node') if n.get('package')!=PACKAGE and box(n)]
        if not nodes: time.sleep(.4);continue
        if not picked:
            filename=[n for n in nodes if n.get('text')=='AscensionMediaWitness.png' or n.get('content-desc')=='AscensionMediaWitness.png']
            marker=image_location(shot())
            if filename: click_box(box(filename[-1]));picked=True;continue
            if marker:
                shell('input','tap',str(marker[0]),str(marker[1]));picked=True;continue
            # A pristine platform may open a resolver instead of the document grid.
            if any(n.get('text') in ['Just once','JUST ONCE'] for n in nodes):
                files=[n for n in nodes if n.get('text') in ['Files','Photos']]
                if files: click_box(box(files[0]));continue
        else:
            confirm=[n for n in nodes if n.get('text','').casefold() in ('add','select','choose','done','open') and n.get('enabled')=='true']
            if confirm: click_box(box(confirm[-1]));continue
        time.sleep(.4)
    raise AssertionError('The code-58 image picker did not return the selected original file')

def seed():
    tap('Campaign');tap('Journal');click_box(box(scroll('Connections')));tap('Add connection')
    await_label('New connection')
    def edit_field():
        root=xml();fields=[n for n in root.iter('node') if n.get('package')==PACKAGE and n.get('class')=='android.widget.EditText' and box(n)]
        preferred=[n for n in fields if any('Connection name' in (child.get('text','')+' '+child.get('content-desc','')+' '+child.get('hint','')) for child in n.iter())]
        return (preferred or fields)[0] if fields else None
    field=observe(edit_field,'actual native Connection name input')
    click_box(box(field))
    observe(lambda: True if any(n.get('class')=='android.widget.EditText' and n.get('focused')=='true' for n in xml().iter('node')) else None,'focused native field')
    shell('input','text',ENTITY)
    observe(lambda: True if any(n.get('text')==ENTITY and n.get('class')=='android.widget.EditText' for n in xml().iter('node')) else None,'native name input')
    if re.search(r'\bmInputShown=true\b|\bisInputViewShown=true\b',shell('dumpsys','input_method')):
        shell('input','keyevent','4')
    click_box(box(scroll('Choose connection image')))
    choose_picker()
    click_box(box(scroll('Save new connection')))
    await_label('Portrait for '+ENTITY)

def launched():
    shell('am','start','-W','-n',PACKAGE+'/.MainActivity')
    assert PACKAGE in shell('dumpsys','activity','activities')

def post_ui():
    launched();tap('Connections');tap('World')
    click_box(box(scroll(ENTITY)))
    await_label('Portrait for '+ENTITY)

def rendered(tag):
    def current():
        nodes=entries(xml(),'Portrait for '+ENTITY)
        if not nodes:return None
        im=shot()
        for n in nodes:
            b=box(n);position=image_location(im,b)
            if position:
                im.save(DIR/(tag+'.png'))
                im.crop(b).save(DIR/(tag+'-portrait.png'))
                return {'bounds':b,'pixelWitness':list(position),'screenshotSha256':sha((DIR/(tag+'.png')).read_bytes())}
        return None
    return observe(current,'actual four-color bitmap in the accessible portrait for '+ENTITY,20)

def package_record(code):
    s=shell('dumpsys','package',PACKAGE)
    version=re.search(r'\bversionCode=(\d+)',s)
    uid=re.search(r'^\s*(?:userId|appId)=(\d+)',s,re.M)
    assert version and int(version.group(1))==code and uid,(code,version,uid)
    return int(uid.group(1))

def db_read(tag):
    # Read Room's original disk bytes while the synthetic app process is stopped.
    shell('am','force-stop',PACKAGE)
    assert not adb('shell','pidof',PACKAGE,check=False).stdout.strip()
    private='/data/user/0/'+PACKAGE+'/databases/astravar-campaign-ledger.db'
    assert shell('id','-u')=='0'
    with tempfile.TemporaryDirectory() as tmp:
        db=Path(tmp)/'campaign.db'
        for ext in ('','-wal'):
            res=adb('exec-out','cat',private+ext,check=False)
            if res.returncode==0 and (ext or res.stdout.startswith(b'SQLite format 3')):
                Path(str(db)+ext).write_bytes(res.stdout)
            elif not ext: raise AssertionError('Expected original private Room database')
        con=sqlite3.connect(db);con.row_factory=sqlite3.Row
        assert con.execute('PRAGMA quick_check').fetchone()[0]=='ok'
        matches=con.execute('select * from connection_entities where name=?',(ENTITY,)).fetchall()
        assert len(matches)==1
        data=dict(matches[0]);raw=base64.b64decode(data.pop('imageBase64'),validate=True)
        assert data['name']==ENTITY and data['source'] and data['id'] and data['revision']==1
        assert raw[:4]==b'RIFF' and raw[8:12]==b'WEBP'
        pix=Image.open(io.BytesIO(raw)).convert('RGB')
        assert pix.size==(96,96) and image_location(pix)
        rgba=[pix.getpixel((x,y)) for x,y in ((20,20),(75,20),(20,75),(75,75))]
        assert all(max(abs(p[i]-c[i]) for i in range(3))<12 for p,c in zip(rgba,COLORS))
        audit_table=con.execute("select name from sqlite_master where type='table' and name='campaign_audit'").fetchone()
        assert audit_table
        cols={row[1] for row in con.execute('pragma table_info(campaign_audit)')}
        id_col=next(k for k in ('recordId','entityId','subjectId') if k in cols)
        evidence=[dict(x) for x in con.execute('select * from campaign_audit where '+id_col+'=?',(data['id'],))]
        assert evidence
        encoded=lambda v:json.dumps(v,sort_keys=True,separators=(',',':')).encode()
        result={'row':data,'storedWebpSha256':sha(raw),'storedBytes':len(raw),'decodedSize':list(pix.size),'sampleRgb':rgba,
                'originalAuditRows':len(evidence),'originalAuditSha256':sha(encoded(evidence))}
        con.close()
    (DIR/(tag+'-room.json')).write_text(json.dumps(result,indent=2))
    return result

def main():
    assert shell('getprop','ro.kernel.qemu')=='1' and shell('getprop','ro.build.version.sdk')=='37'
    assert adb('emu','avd','name').stdout.decode().splitlines()[0].strip()=='AscensionMediaOnly'
    assert shell('settings','get','global','airplane_mode_on')=='1'
    assert not adb('shell','pm','path',PACKAGE,check=False).stdout.strip()
    stage('install historical optimized 58')
    assert 'Success' in adb('install',str(APK/'old.apk')).stdout.decode()
    old_uid=package_record(58)
    new_portrait();launched()
    stage('select and save the source image through code 58')
    seed()
    before_view=rendered('code58')
    before=db_read('code58')
    assert sha(adb('exec-out','cat',PICKED_FILE).stdout)==sha((DIR/'source.png').read_bytes())
    shell('rm',PICKED_FILE)
    assert not shell('sh','-c',f"'test -e {PICKED_FILE} && echo occupied || true'")
    stage('install optimized 59 over 58 using the original package')
    assert 'Success' in adb('install','-r',str(APK/'new.apk')).stdout.decode()
    assert old_uid==package_record(59)
    assert before==db_read('code59-before-launch')
    stage('open actual 59 Connections profile')
    post_ui();after_view=rendered('code59');assert before==db_read('code59')
    stage('reopen after verified process stop')
    post_ui();restart_view=rendered('code59-restarted');assert before==db_read('code59-restarted')
    out={'status':'VERIFIED','guest':shell('getprop','ro.build.fingerprint'),'package':PACKAGE,
        'uid':old_uid,'before':58,'after':59,'certificate':json.loads((APK/'provenance.json').read_text())['certificate'],
        'originalSourceSha256':sha((DIR/'source.png').read_bytes()),'recordId':before['row']['id'],'storedImageSha256':before['storedWebpSha256'],
        'originalAuditSha256':before['originalAuditSha256'],'source':before['row']['source'],'recordRevision':1,'fileRemovedBeforeUpgrade':True,
        'beforeView':before_view,'afterView':after_view,'restartedView':restart_view,'rawStoredBytes':before['storedBytes']}
    (DIR/'observations.json').write_text(json.dumps(out,indent=2));print(json.dumps(out,sort_keys=True))

if __name__=='__main__':
    try: main()
    except Exception:
        print('Failed during',STAGE,flush=True)
        try:
            (DIR/'failure.xml').write_bytes(adb('exec-out','cat','/data/local/tmp/qa-media-tree.xml').stdout)
            shot().save(DIR/'failure.png')
        except Exception: pass
        raise
