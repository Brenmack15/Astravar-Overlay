"""Reproduce the original Settings obstruction only on the fresh, isolated API 37 AVD."""
import ast
import json
import os
import re
import subprocess
import time
from pathlib import Path

out = Path("validation/visual-original")
out.mkdir(parents=True, exist_ok=True)
serial = "emulator-5584"
def adb(*args, timeout=18):
    return subprocess.check_output(["adb", "-s", serial, *args], text=True, timeout=timeout).strip()
assert os.environ.get("GITHUB_ACTIONS") == "true"
assert adb("emu","avd","name").splitlines()[0] == "AstravarHpCI"
assert adb("shell","getprop","ro.kernel.qemu") == "1"
assert adb("shell","getprop","ro.build.version.sdk") == "37"
assert adb("shell","id","-u") == "0"
source = Path("scripts/ci-android17-silent-icons.py")
tree = ast.parse(source.read_text())
ns = {"re":re}
exec(compile(ast.Module(body=[n for n in tree.body if getattr(n, "name", "") == "visible_anrs"], type_ignores=[]), str(source), "exec"), ns)
def windows():
    raw = adb("shell","dumpsys","window","windows")
    return ns["visible_anrs"](raw), raw
def visible():
    return windows()[0]
def until(test, seconds, why):
    end=time.monotonic()+seconds
    while time.monotonic() < end:
        if test(): return
        time.sleep(.4)
    raise RuntimeError(why)
anrs, original = windows()
origin = "cold-boot"
if not anrs:
    origin = "foreground-control"
    name = "com.android.systemui"
    until(lambda: "CentralSurfaces" in adb("shell","dumpsys","-t","8","activity","service",name+"/.SystemUIService","-l"),32,"SystemUI did not start")
    adb("shell","input","keyevent","KEYCODE_WAKEUP")
    adb("shell","wm","dismiss-keyguard")
    adb("shell","cmd","statusbar","expand-notifications")
    until(lambda: bool(re.search(r"mCurrentFocus=.*(?:NotificationShade|ShadeWindow)",adb("shell","dumpsys","window","displays"))),
          15,"AOSP notification shade did not own input")
    pid = adb("shell","pidof",name)
    assert pid.isdecimal() and adb("shell","cat",f"/proc/{pid}/cmdline").rstrip("\0") == name
    # Pause and resume the *single verified foreground process* in an otherwise
    # empty throwaway guest. The real OS InputDispatcher, ANR and buttons are used.
    adb("shell","kill","-STOP",pid)
    pointer = None
    try:
        pointer = subprocess.Popen(["adb","-s",serial,"shell","input","tap","300","450"],stdout=subprocess.DEVNULL)
        until(lambda: visible() == {name},32,"AOSP did not publish the controlled System UI ANR")
        anrs, original = windows()
    finally:
        adb("shell","kill","-CONT",pid)
        if pointer: pointer.wait(timeout=18)
assert anrs == {"com.android.systemui"}, anrs
(out/"visible-window.txt").write_text(next(l for l in original.splitlines() if "Application Not Responding: com.android.systemui}:" in l)+"\n")
until(lambda: "CentralSurfaces" in adb("shell","dumpsys","-t","8","activity","service","com.android.systemui/.SystemUIService","-l"),25,"SystemUI did not resume")
old = str(out/"original-helper.py")
Path(old).write_bytes(subprocess.check_output(["git","show","90ea56725f4bd11c2741823f8039c0bc37e28203:scripts/ci-android17-silent-icons.py"]))
prior = subprocess.check_output(["python3",old,serial,"AstravarHpCI","read"],text=True).strip()
assert prior in {"true","false"}
op = "show" if prior=="true" else "hide"
failure = subprocess.run(["python3",old,serial,"AstravarHpCI",op],capture_output=True,text=True,timeout=120)
assert failure.returncode !=0 and "Settings did not expose" in failure.stderr, failure.stderr[-1200:]
assert visible()=={"com.android.systemui"}
assert subprocess.check_output(["python3",old,serial,"AstravarHpCI","read"],text=True).strip()==prior
(out/"original-status.json").write_text(json.dumps({"cause":origin,"before":prior,"failedSwitch":op,"verifiedFailure":"Settings did not expose the user’s silent status-bar switch","occludingProcess":"com.android.systemui"}))
print("ORIGINAL timeout reproduced with a visible, independently confirmed Android SystemUI ANR",origin,flush=True)
