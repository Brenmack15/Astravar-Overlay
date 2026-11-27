#!/usr/bin/env python3
"""Set or read Android's actual silent status icon preference on a disposable test AVD."""
import argparse
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

parser = argparse.ArgumentParser()
parser.add_argument("serial")
parser.add_argument("avd_name")
parser.add_argument("mode", choices=["read", "show", "hide"])
args = parser.parse_args()

def adb(*arguments):
    return subprocess.check_output(["adb", "-s", args.serial, *arguments], text=True, timeout=18).strip()

def hidden():
    result = re.findall(r"^\s*hideSilentStatusBar=(true|false)\s*$", adb("shell", "dumpsys", "notification"), re.M)
    if len(set(result)) != 1:
        raise RuntimeError("Cannot determine the framework’s silent status icon preference")
    return result[0] == "true"

def location(node):
    bounds = tuple(map(int, re.findall(r"\d+", node.get("bounds", ""))))
    return bounds if len(bounds) == 4 and bounds[2] > bounds[0] and bounds[3] > bounds[1] else None

def tap(bounds):
    left, top, right, bottom = bounds
    adb("shell", "input", "tap", str((left + right) // 2), str((top + bottom) // 2))

def hierarchy():
    path = "/data/local/tmp/ascension-notification-settings.xml"
    adb("shell", "uiautomator", "dump", path)
    return ET.fromstring(adb("exec-out", "cat", path))

def target_row(root, pkg):
    parents = {child: parent for parent in root.iter() for child in parent}
    matches = set()
    for label in root.iter("node"):
        words = " ".join((label.get("text", ""), label.get("content-desc", ""))).strip().lower()
        if label.get("package") != pkg or "silent" not in words or "status bar" not in words:
            continue
        ancestor = label
        for _ in range(8):
            candidates = [c for c in ancestor.iter("node") if c.get("checkable") == "true"]
            bounds = location(ancestor)
            if bounds and ancestor.get("enabled") == "true" and ancestor.get("clickable") == "true" and len(candidates) == 1:
                matches.add(bounds)
                break
            if ancestor not in parents:
                break
            ancestor = parents[ancestor]
    if len(matches) > 1:
        raise RuntimeError(f"Silent status bar switch is ambiguous: {matches}")
    return next(iter(matches), None)

assert args.avd_name in {"AstravarHpCI", "AscensionNotificationCI"}
assert re.fullmatch(r"emulator-[0-9]+", args.serial)
assert adb("shell", "getprop", "ro.kernel.qemu") == "1"
assert adb("shell", "getprop", "ro.build.version.sdk") == "37"
assert adb("emu", "avd", "name").splitlines()[0] == args.avd_name
current = hidden()
if args.mode == "read":
    print("true" if current else "false")
    sys.exit(0)
wanted = args.mode == "hide"
if current == wanted:
    print(f"Android hideSilentStatusBar already {wanted}", file=sys.stderr)
    sys.exit(0)
action = "android.settings.NOTIFICATION_SETTINGS"
pkg = adb("shell", "cmd", "package", "resolve-activity", "--brief", "-a", action).splitlines()[-1].partition("/")[0]
assert re.fullmatch(r"[\w.]+", pkg), pkg
adb("shell", "cmd", "statusbar", "collapse")
launch = adb("shell", "am", "start", "-W", "-a", action)
assert "Status: ok" in launch, launch
end = time.monotonic() + 64
visited = set()
while time.monotonic() < end:
    root = hierarchy()
    nodes = [n for n in root.iter("node") if n.get("package") == pkg]
    if not nodes:
        time.sleep(.3)
        continue
    row = target_row(root, pkg)
    if row:
        tap(row)
        break
    labels = tuple(n.get("text") for n in nodes if n.get("text"))
    if labels in visited:
        raise RuntimeError(f"Missing the silent status-bar switch on the observed Settings page: {labels}")
    visited.add(labels)
    print("Settings labels:", ", ".join(labels)[:1000], file=sys.stderr)
    viewports = [b for n in nodes if n.get("scrollable") == "true" and (b := location(n))]
    if not viewports:
        raise RuntimeError(f"Visible Settings tree contains no silent-icon switch or scrollable content: {labels}")
    left, top, right, bottom = max(viewports, key=lambda b: (b[2]-b[0])*(b[3]-b[1]))
    x = str((left+right)//2)
    adb("shell", "input", "swipe", x, str(bottom-(bottom-top)//8), x, str(top+(bottom-top)//8), "450")
else:
    raise TimeoutError("Settings did not expose the user’s silent status-bar switch")
for _ in range(24):
    if hidden() == wanted:
        print(f"Verified Android hideSilentStatusBar={str(wanted).lower()} via the Settings switch", file=sys.stderr)
        sys.exit(0)
    time.sleep(.2)
raise RuntimeError("The framework did not confirm the requested silent-icon preference")
