"""Deliver a restricted set of physical gestures to the authenticated, local QA emulator."""
import os
import pathlib
import re
import subprocess
import sys
import time

import grpc
import emulator_controller_pb2 as pb
import emulator_controller_pb2_grpc as stubs

def control():
    roots = [pathlib.Path.home()/".android"/"avd"/"running",
             pathlib.Path(os.environ.get("XDG_RUNTIME_DIR", "/run/user/"+str(os.getuid())))/"avd"/"running",
             pathlib.Path("/tmp")/("android-"+os.environ["USER"])/"avd"/"running"]
    for root in roots:
        for file in root.glob("pid_*.ini"):
            values=dict(line.split("=",1) for line in file.read_text().splitlines() if "=" in line)
            if values.get("avd.name")!="AstravarStockReader" and values.get("avd.id")!="AstravarStockReader": continue
            assert values.get("port.serial")=="5582" and values["grpc.token"]
            address="127.0.0.1:"+str(int(values["grpc.port"]))
            ch=grpc.insecure_channel(address)
            grpc.channel_ready_future(ch).result(timeout=8)
            return stubs.EmulatorControllerStub(ch),[("authorization","Bearer "+values["grpc.token"])]
    raise RuntimeError("Missing authenticated advertisement for the single owned emulator")

controller,metadata=control()
def touch(x,y,p):
    assert 0<=x<2000 and 0<=y<4000 and 0<=p<=255
    controller.sendTouch(pb.TouchEvent(touches=[pb.Touch(x=x,y=y,identifier=0,pressure=p)]),metadata=metadata,timeout=5)

def chord(keys):
    allowed={"KEYCODE_META_LEFT":125,"KEYCODE_ALT_LEFT":56,"KEYCODE_SHIFT_LEFT":42,"KEYCODE_ENTER":28,"KEYCODE_SPACE":57,"KEYCODE_CTRL_LEFT":29,"KEYCODE_C":46,"KEYCODE_BACK":158}
    assert 1<=len(keys)<=3 and all(k in allowed for k in keys)
    for k in keys:
        controller.sendKey(pb.KeyboardEvent(codeType=pb.KeyboardEvent.Evdev,eventType=pb.KeyboardEvent.keydown,keyCode=allowed[k]),metadata=metadata,timeout=5)
        time.sleep(.030)
    for k in reversed(keys):
        controller.sendKey(pb.KeyboardEvent(codeType=pb.KeyboardEvent.Evdev,eventType=pb.KeyboardEvent.keyup,keyCode=allowed[k]),metadata=metadata,timeout=5)
        time.sleep(.020)

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
