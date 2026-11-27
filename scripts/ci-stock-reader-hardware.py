"""Deliver a restricted set of physical gestures to the authenticated, local QA emulator."""
import os
import pathlib
import re
import subprocess
import sys
import time

from google.protobuf.empty_pb2 import Empty
import grpc
import emulator_controller_pb2 as pb
import emulator_controller_pb2_grpc as stubs

adb=["adb","-s","emulator-5582"]

def read_host_advertisement():
    # Pin the advertised local file to this runner's owned QEMU launch log.
    log=pathlib.Path(os.environ["RUNNER_TEMP"])/"stock-reader-emulator.log"
    root=pathlib.Path(os.environ.get("XDG_RUNTIME_DIR","/run/user/"+str(os.getuid())))
    paths=set(root.rglob("pid_*.ini"))
    for line in log.read_text(errors="replace").splitlines():
        if "Advertising in:" in line:
            paths.update(pathlib.Path(p) for p in re.findall(r'(/[^\s\x1b"<>]*pid_[0-9]+\.ini)',line))
    for folder in [pathlib.Path.home()/".android",pathlib.Path("/run/user")/str(os.getuid()),
                   pathlib.Path("/tmp")/("android-"+os.environ["USER"]),pathlib.Path("/tmp/avd/running")]:
        if folder.exists():
            paths.update(folder.glob("avd/running/pid_*.ini"))
            paths.update(folder.glob("pid_*.ini"))
    for file in sorted(paths):
        if not file.is_file() or file.is_symlink() or file.stat().st_uid != os.getuid(): continue
        values=dict(line.split("=",1) for line in file.read_text().splitlines() if "=" in line)
        name=values.get("avd.id") or values.get("avd.name")
        serial=values.get("port.serial")
        port=values.get("grpc.port")
        token=values.get("grpc.token")
        print("Local QEMU advertisement: owned AVD="+str(name=="AstravarStockReader")+
              " console="+str(serial=="5582")+" port="+str(bool(port))+" authenticated="+str(bool(token)),flush=True)
        if serial!="5582" or name not in (None,"AstravarStockReader") or not token or not port: continue
        ch=grpc.insecure_channel("127.0.0.1:"+str(int(port)))
        try:
            grpc.channel_ready_future(ch).result(timeout=6)
            endpoint=stubs.EmulatorControllerStub(ch)
            auth=[("authorization","Bearer "+token)]
            endpoint.getStatus(Empty(),metadata=auth,timeout=5)
            return endpoint,auth
        except (grpc.RpcError,grpc.FutureTimeoutError):
            print("SDK controller did not accept the private session; using authenticated owned console",flush=True)
            ch.close()
    return None,None

controller,metadata=read_host_advertisement()
name=subprocess.run(adb+["emu","avd","name"],stdout=subprocess.PIPE,stderr=subprocess.DEVNULL,text=True,timeout=8)
boot_name=subprocess.check_output(adb+["shell","getprop","ro.boot.qemu.avd_name"],text=True,timeout=8).strip()
assert "AstravarStockReader" in name.stdout.splitlines() or boot_name=="AstravarStockReader"

def console_events(events):
    raw=subprocess.run(adb+["emu","event","send"]+["%d:%d:%d"%event for event in events]+["0:0:0"],
                       timeout=4,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True,check=True)
    if "OK" not in raw.stdout or "KO:" in raw.stdout: raise ValueError("Emulator refused the hardware event")

screen=tuple(map(int,re.search(r"Physical size: (\d+)x(\d+)",subprocess.check_output(adb+["shell","wm","size"],text=True)).groups()))
def axes_for_console():
    # Use the actual x/y raw caps recorded by the OS, never assume they are screen pixels.
    desc=subprocess.check_output(adb+["shell","dumpsys","input"],text=True,timeout=8)
    axes=re.search(r"Raw Touch Axes:.*?\bX:\s*min=(\d+),\s*max=(\d+).*?\bY:\s*min=(\d+),\s*max=(\d+)",desc,re.S)
    if not axes: raise RuntimeError("No reported raw touch coordinates on the owned emulator")
    return tuple(map(int,axes.groups()))

axes=None if controller else axes_for_console()
print("Physical input route "+("local authenticated emulator controller" if controller else "authenticated emulator console, reported touch axes "+str(axes)),flush=True)
finger=False
tracking=0
def touch(x,y,p):
    global finger,tracking
    assert 0<=x<screen[0] and 0<=y<screen[1] and 0<=p<=255
    if controller:
        controller.sendTouch(pb.TouchEvent(touches=[pb.Touch(x=x,y=y,identifier=0,pressure=p)]),metadata=metadata,timeout=5)
        return
    if not p:
        console_events([(3,47,0),(3,57,-1),(1,330,0)])
        finger=False
        return
    rx=axes[0]+round(x*(axes[1]-axes[0])/(screen[0]-1))
    ry=axes[2]+round(y*(axes[3]-axes[2])/(screen[1]-1))
    entries=[(3,47,0)]
    if not finger:
        tracking+=1
        entries.append((3,57,tracking))
    console_events(entries+[(3,53,rx),(3,54,ry),(3,58,p),(1,330,1)])
    finger=True

def chord(keys):
    allowed={"KEYCODE_META_LEFT":125,"KEYCODE_ALT_LEFT":56,"KEYCODE_SHIFT_LEFT":42,"KEYCODE_ENTER":28,"KEYCODE_SPACE":57,"KEYCODE_CTRL_LEFT":29,"KEYCODE_C":46,"KEYCODE_BACK":158}
    assert 1<=len(keys)<=3 and all(k in allowed for k in keys)
    for values,pressed in [(keys,True),(list(reversed(keys)),False)]:
        for key in values:
            if controller:
                event=pb.KeyboardEvent.keydown if pressed else pb.KeyboardEvent.keyup
                controller.sendKey(pb.KeyboardEvent(codeType=pb.KeyboardEvent.Evdev,eventType=event,keyCode=allowed[key]),metadata=metadata,timeout=4)
            else: console_events([(1,allowed[key],int(pressed))])
            time.sleep(.025)

def send(command, args):
    if command=="EXPLORE":
        x,y=map(int,args)
        touch(x,y,80);time.sleep(.12);touch(x+2,y,80);time.sleep(.52);touch(x+3,y+1,80);time.sleep(.12);touch(x+3,y+1,0)
    elif command=="DOUBLE_TAP":
        x,y=map(int,args)
        for i in range(2):
            touch(x,y,80);time.sleep(.055);touch(x,y,0)
            if i==0: time.sleep(.090)
    elif command=="NEXT_SWIPE":
        assert not args
        for i in range(12):
            touch(270+i*38,1100,80);time.sleep(.012)
        touch(688,1100,0)
    elif command=="CHORD": chord(args)
    elif command=="BACK": chord(["KEYCODE_BACK"])
    elif command=="COPY_LAST": chord(["KEYCODE_META_LEFT","KEYCODE_CTRL_LEFT","KEYCODE_C"])
    else: raise ValueError("Unknown physical gesture")
adb=["adb","-s","emulator-5582"]
target="com.yazsras.astravar.v2uidebug"
process=subprocess.Popen(adb+["shell","am","instrument","-w","-r","-e","class","com.yazsras.astravar.ui.AscensionStockTalkBackTest",target+".test/androidx.test.runner.AndroidJUnitRunner"],stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True,bufsize=1)
sequence=0
for line in process.stdout:
    print(line.rstrip(),flush=True)
    req=re.fullmatch(r"INSTRUMENTATION_STATUS: readerHardware=(\d+) ([A-Z_]+)(?: ([A-Z0-9_ ]+))?",line.strip())
    if not req: continue
    sequence+=1
    assert int(req[1])==sequence
    send(req[2],(req[3] or "").split())
    subprocess.run(adb+["shell","run-as "+target+" sh -c 'echo "+str(sequence)+" > files/reader-hardware.ack'"],check=True,timeout=8,stdout=subprocess.DEVNULL)
sys.exit(process.wait())
