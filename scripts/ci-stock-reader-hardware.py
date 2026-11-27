"""Send a fixed set of touchscreen and keyboard events to the stock, owned SDK guest."""
import os
import re
import struct
import subprocess
import sys
import time

adb=["adb","-s","emulator-5582"]
def device(*args):
    return subprocess.check_output(adb+["shell",*args],text=True,timeout=10).strip()

assert device("id","-u")=="0" and device("getprop","ro.kernel.qemu")=="1"
assert device("getprop","ro.product.cpu.abi")=="x86_64"
assert device("getprop","ro.build.version.sdk")=="37"
assert "AstravarStockReader" in subprocess.check_output(adb+["emu","avd","name"],text=True,timeout=8).splitlines() or device("getprop","ro.boot.qemu.avd_name")=="AstravarStockReader"
screen=tuple(map(int,re.search(r"Physical size: (\d+)x(\d+)",device("wm","size")).groups()))
caps=device("getevent","-lp")
devices=[]
for block in re.split(r"(?=add device \d+: )",caps):
    path=re.search(r"add device \d+: (/dev/input/event\d+)",block)
    name=re.search(r'name:\s*"([^"]+)"',block)
    if path and name:
        devices.append((path[1],name[1],block))
print("QA guest virtual input devices: "+", ".join(x[1] for x in devices),flush=True)
touch_devs=[x for x in devices if "ABS_MT_POSITION_X" in x[2] and "ABS_MT_TRACKING_ID" in x[2] and "touch" in x[1].lower() and "pad" not in x[1].lower()]
assert touch_devs,"No enumerated virtual touchscreen"
# The emulator's primary display is wired to the first numbered multitouch device.
touchdev=next((x for x in touch_devs if re.search(r"(?:multi.?touch|touchscreen)[ _-]?1$",x[1],re.I)),touch_devs[0])
keyboards=[x for x in devices if "KEY_ENTER" in x[2] and "KEY_LEFTALT" in x[2] and "KEY_RIGHT" in x[2] and "KEY_SPACE" in x[2]]
assert keyboards,"No enumerated virtual keyboard"
keydev=next((x for x in keyboards if "keyboard" in x[1].lower()),keyboards[0])
def axis(which):
    match=re.search(r"ABS_MT_POSITION_"+which+r"\s*:.*?\bmin\s+(\d+),\s*max\s+(\d+)",touchdev[2])
    if not match: raise ValueError("No reported axis "+which)
    return tuple(map(int,match.groups()))
xaxis,yaxis=axis("X"),axis("Y")
print("Actual emulated touchscreen "+touchdev[1]+" "+str((xaxis,yaxis))+" screen "+str(screen)+" keyboard "+keydev[1],flush=True)
def stream(dev):
    # adb's raw stream preserves Linux input_event bytes. The official debuggable SDK image supplies adb root.
    assert re.fullmatch(r"/dev/input/event\d+",dev)
    return subprocess.Popen(adb+["shell","-T","cat > "+dev],stdin=subprocess.PIPE,stdout=subprocess.DEVNULL,bufsize=0)
touch_stream=stream(touchdev[0]); key_stream=stream(keydev[0])
def write(proc,entries):
    assert proc.poll() is None
    packet=b"".join(struct.pack("<qqHHi",0,0,t,c,v) for t,c,v in entries+[(0,0,0)])
    proc.stdin.write(packet)
    proc.stdin.flush()

finger=False
tracking=0
def touch(x,y,p):
    global finger,tracking
    assert 0<=x<screen[0] and 0<=y<screen[1] and 0<=p<256
    records=[(3,47,0)]
    if p==0:
        records.extend([(3,57,-1),(1,330,0)])
        finger=False
    else:
        if not finger:
            tracking+=1
            records.append((3,57,tracking))
        rx=xaxis[0]+round(x*(xaxis[1]-xaxis[0])/(screen[0]-1))
        ry=yaxis[0]+round(y*(yaxis[1]-yaxis[0])/(screen[1]-1))
        records.extend([(3,53,rx),(3,54,ry)])
        if "ABS_MT_PRESSURE" in touchdev[2]: records.append((3,58,p))
        if "ABS_MT_TOUCH_MAJOR" in touchdev[2]: records.append((3,48,6))
        if "BTN_TOUCH" in touchdev[2]: records.append((1,330,1))
        finger=True
    write(touch_stream,records)
def chord(keys):
    allowed={"KEYCODE_META_LEFT":125,"KEYCODE_ALT_LEFT":56,"KEYCODE_SHIFT_LEFT":42,"KEYCODE_ENTER":28,"KEYCODE_SPACE":57,"KEYCODE_CTRL_LEFT":29,"KEYCODE_C":46,"KEYCODE_BACK":158,"KEYCODE_DPAD_RIGHT":106,"KEYCODE_DPAD_LEFT":105}
    assert 1<=len(keys)<=3 and all(k in allowed for k in keys)
    for order,pressed in [(keys,1),(reversed(keys),0)]:
        for k in order:
            write(key_stream,[(1,allowed[k],pressed)])
            time.sleep(.035)
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
    elif command=="BACK":
        if "KEY_BACK " in keydev[2]: chord(["KEYCODE_BACK"])
        else: subprocess.run(adb+["shell","input","keyevent","KEYCODE_BACK"],check=True,timeout=4)
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
