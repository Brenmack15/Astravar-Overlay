"""Independently read the completed native gate on its pinned source. No Android build or gameplay."""
import hashlib, io, json, math, os, pathlib, re, sqlite3, struct, subprocess, tempfile, zipfile, zlib
from xml.etree import ElementTree as ET

repo = "Brenmack15/Astravar-Overlay"
head = "9ded49ee06b22f389c4d5f5553f975cb02fa762f"
artifact = 15185466555
digest = "f34d8562f6c13fe775bd4ae79ed79f8d837227cfa07091c39332ab622accf309"
run = 45962475021
ns = "{http://schemas.android.com/apk/res/android}"
xml = ET.parse("app/src/main/res/drawable/ic_astravar_notification.xml").getroot()
assert xml.get(ns+"viewportWidth") == xml.get(ns+"viewportHeight") == "24"
path = xml.find("path")
assert path.get(ns+"fillType") == "evenOdd"
poly = [[tuple(map(float,t)) for t in re.findall(r"[ML](-?[\d.]+),(-?[\d.]+)", part)] for part in path.get(ns+"pathData").split("Z") if part]
def inside(x,y):
    state=False
    for contour in poly:
        for a,b in zip(contour,contour[1:]+contour[:1]):
            if (a[1]>y)!=(b[1]>y) and x<(b[0]-a[0])*(y-a[1])/(b[1]-a[1])+a[0]:
                state=not state
    return state
samples=[]
for y in range(2,23,2):
    for x in range(3,22,2):
        alpha=sum(inside(x+(i+.5)/8,y+(j+.5)/8) for i in range(8) for j in range(8))*255/64
        if alpha>=220 or alpha<=12: samples.append((x,y,int(alpha>=220)))
def png(raw, crop=False):
    assert raw.startswith(b'\x89PNG\r\n\x1a\n')
    index=8; b=bytearray(); palette=b""
    while index<len(raw):
        n=struct.unpack_from(">I",raw,index)[0];typ=raw[index+4:index+8];value=raw[index+8:index+8+n]
        assert (zlib.crc32(typ+value)&0xFFFFFFFF) == struct.unpack_from(">I",raw,index+8+n)[0]
        if typ==b'IHDR':
            w,h,depth,model,compression,filt,interlace=struct.unpack(">IIBBBBB",value)
        elif typ==b"IDAT":b.extend(value)
        elif typ==b"PLTE":palette=value
        index+=12+n
    assert typ==b'IEND' and depth==8 and compression==filt==interlace==0
    count={0:1,2:3,3:1,4:2,6:4}[model]
    scan=zlib.decompress(b);stride=w*count
    assert len(scan)==(stride+1)*h
    if not crop:return w,h
    assert w==h and 12<=w<=110
    prev=[0]*stride;pixels=[]
    for y in range(h):
        sy=y*(stride+1);f=scan[sy];row=[]
        for i,value in enumerate(scan[sy+1:sy+1+stride]):
            above=prev[i];left=row[i-count] if i>=count else 0;ul=prev[i-count] if i>=count else 0
            if f==0: delta=0
            elif f==1: delta=left
            elif f==2: delta=above
            elif f==3: delta=(above+left)//2
            elif f==4:
                p=left+above-ul
                delta=min((left,above,ul),key=lambda x:abs(p-x))
            else: raise AssertionError(f)
            row.append((value+delta)&255)
        prev=row;vals=[]
        for x in range(w):
            i=x*count
            if model==3: r,g,blue=palette[row[i]*3:row[i]*3+3]
            elif model in (0,4): r=g=blue=row[i]
            else:r,g,blue=row[i:i+3]
            vals.append((r*54+g*183+blue*19)//256)
        pixels.append(vals)
    v=[pixels[int((y+.5)*h/24)][int((x+.5)*w/24)] for x,y,mask in samples]
    m=[mask for x,y,mask in samples]
    n=len(v);d=abs(n*sum(c*k for c,k in zip(v,m))-sum(v)*sum(m))
    correlation=d/math.sqrt((n*sum(c*c for c in v)-sum(v)**2)*(n*sum(m)-sum(m)**2))
    return [w,h,round(correlation,5)]
def gh(endpoint):
    return subprocess.check_output(["gh","api","repos/"+repo+endpoint])
meta=json.loads(gh("/actions/artifacts/"+str(artifact)))
assert meta["workflow_run"]["id"]==run and meta["workflow_run"]["head_sha"]==head and not meta["expired"]
assert meta["name"]=="android-verification" and meta["size_in_bytes"]==195398797 and meta["digest"]=="sha256:"+digest
result={"scope":"isolated published emulator archive only","source":head,"run":run,"artifact":artifact,"archiveSha256":digest}
base="validation/ascension-api37/"; native="validation/owner-hp-native/"
with tempfile.TemporaryDirectory() as tmp:
    working=pathlib.Path(tmp);archive=working/"original.zip"
    with archive.open("wb") as f:
        subprocess.run(["gh","api","repos/"+repo+"/actions/artifacts/"+str(artifact)+"/zip"],stdout=f,check=True)
    with archive.open("rb") as f: assert hashlib.file_digest(f,"sha256").hexdigest()==digest
    assert archive.stat().st_size==meta["size_in_bytes"]
    with zipfile.ZipFile(archive) as z:
        names=z.namelist()
        assert z.read(native+"SOURCE_COMMIT.txt").decode().strip()==head
        assert z.read(base+"sdk.txt").strip()==b"37" and z.read(base+"release.txt").strip()==b"17"
        package=json.loads(z.read(base+"package-result.json"));expected=json.loads(z.read(base+"readback.json"))
        assert package["old_version"]==58 and package["new_version"]==59 and package["package"]=="com.yazsras.astravar"
        assert package["same_android_uid"] and package["same_first_install_time"]
        result["package"]={k:package[k] for k in ("package","old_version","new_version","android_uid","same_android_uid","same_first_install_time")}
        assert expected["roomSchema"]==9 and not expected["cloudWorkspaceBound"] and expected["fixtureOnly"]
        result["emblems"]={}; result["screens"]={}
        for theme in ("light","dark"):
            for surface in ("status","shade"):
                stem=base+"notification/api37-"+theme+"-"+surface
                value={"frame":png(z.read(stem+".png")),"crop":png(z.read(stem+"-icon.png"),True)}
                assert value["frame"][0]>=320 and value["frame"][1]>=640
                assert value["crop"][2]>=.72,value
                result["emblems"][theme+"-"+surface]=value
            for tab in ("Character","Inventory","Connections","Chronicle"):
                frame=png(z.read(base+"screenshots/api37-"+theme+"-"+tab+".png"))
                assert frame[0]>=320 and frame[1]>=640
                result["screens"][theme+"-"+tab]=frame
        selected=[n for n in names if n.endswith(".txt") and (n in [native+"notification-emblem.txt",native+"native-roll-accessibility.txt",native+"native-roll-clipboard-paste.txt"] or n.startswith(base+"seedAscensionCode58-") or n.startswith(base+"verifyAscensionOnAndroid17-"))]
        assert len(selected)==6,selected
        for n in selected:
            t=z.read(n).decode()
            assert re.search(r"^OK \(1 test\)",t,re.M) and not re.search("FAILURES|INSTRUMENTATION_FAILED|Process crashed",t),n
        result["witnesses"]=[n.split("/")[-1] for n in sorted(selected)]
        observations=[]
        for stage in ("before","after"):
            directory=working/stage;directory.mkdir()
            for n in names:
                if n.startswith(base+stage+"/") and re.search(r"astravar-(?:campaign-ledger|cloud-cache)\.db(?:-shm|-wal)?$",n):
                    (directory/pathlib.Path(n).name).write_bytes(z.read(n))
            file=directory/"astravar-campaign-ledger.db"
            db=sqlite3.connect(file.resolve().as_uri()+"?mode=ro",uri=True)
            assert db.execute("pragma integrity_check").fetchall()==[("ok",)] and db.execute("pragma foreign_key_check").fetchall()==[]
            actors=[(i,json.loads(s)) for i,s in db.execute("select profileId,characterJson from reforge_characters")]
            matches=[(i,v) for i,v in actors if v.get("name")=="Ascension upgrade fixture"]
            assert len(matches)==1
            who,row=matches[0]
            assert who==row["id"] and (row["currentHp"],row["baseMaxHp"],row["temporaryHp"],row["baseAbilities"]["INT"])==(17,32,3,15)
            tables={}
            for table,reviewed in expected["tables"].items():
                assert re.fullmatch("[a-z_]+",table)
                data=[json.dumps(tuple(r),ensure_ascii=False,separators=(",",":"),default=lambda v:{"hex":v.hex()}) for r in db.execute("select * from "+table)]
                val=hashlib.sha256(json.dumps(sorted(data),separators=(",",":")).encode()).hexdigest()
                assert reviewed=={"count":len(data),"sha256":val},table
                tables[table]=val
            assert len(tables)==10
            db.close()
            cache=directory/"astravar-cloud-cache.db"
            if cache.exists():
                db=sqlite3.connect(cache.resolve().as_uri()+"?mode=ro",uri=True)
                for table in ("workspace","records","commands","fenced"):
                    assert db.execute("select count(*) from "+table).fetchone()[0]==0
                db.close()
            observations.append((who,tables))
        assert observations[0]==observations[1]
        result["room"]={"independentlyMatchedTableHashes":10,"sameActor":True,"hp":17,"maxHp":32,"tempHp":3,"baseInt":15,"cloudAuthorityActivated":False}
pathlib.Path("native-archive-measurement.json").write_text(json.dumps(result,indent=2,sort_keys=True)+"\n")
print("ARCHIVE_MEASUREMENT "+json.dumps(result,sort_keys=True),flush=True)
