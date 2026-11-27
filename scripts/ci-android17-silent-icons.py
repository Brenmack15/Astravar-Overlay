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
parser.add_argument("mode", choices=["read", "show", "hide", "ready"])
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
    adb("shell", "rm", "-f", path)
    adb("shell", "uiautomator", "dump", path)
    return ET.fromstring(adb("exec-out", "cat", path))

def visible_anrs(windows):
    found = set()
    for block in re.split(r"(?m)^\s*Window #\d+ Window\{", windows)[1:]:
        title = re.search(r"Application Not Responding: ([^}]+)\}", block.splitlines()[0])
        if title and re.search(r"(?m)^\s*isVisible=true\s*$", block):
            found.add(title[1])
    return found

def system_ui_wait(root):
    nodes = [n for n in root.iter("node") if n.get("package") == "android"]
    titles = [n.get("text", "").replace("’", "'").strip().casefold() for n in nodes
              if n.get("resource-id") == "android:id/alertTitle"]
    if titles != ["system ui isn't responding"]:
        raise RuntimeError(f"Unexpected guest error dialog; no action taken: {titles}")
    buttons = [location(n) for n in nodes if n.get("resource-id") == "android:id/aerr_wait"
               and n.get("text", "").strip().casefold() == "wait"
               and n.get("clickable") == "true" and n.get("enabled") == "true"]
    if len(buttons) != 1 or not buttons[0]:
        raise RuntimeError("The System UI error window has no unambiguous, enabled Wait control")
    return buttons[0]

def require_clear_error_windows():
    def errors():
        return visible_anrs(adb("shell", "dumpsys", "window", "windows"))
    current = errors()
    if not current:
        return
    if current != {"com.android.systemui"}:
        raise RuntimeError(f"Visible ANR requires investigation; leaving it open: {sorted(current)}")
    # The archived cold-boot failure outlived SystemUI startup. Only choose Wait once
    # the actual service in that process answers a client dump and its own dialog agrees.
    service = adb("shell", "dumpsys", "-t", "8", "activity", "service",
                  "com.android.systemui/.SystemUIService", "-l")
    if not (re.search(r"SERVICE com\.android\.systemui/[^\s]+ .*?pid=\d+", service)
            and re.search(r"(?ms)^\s*Client:\s*\n\s*\S", service)
            and "CentralSurfaces" in service.partition("Client:")[2]
            and not re.search(r"(?:Failure while dumping|Timed? out|TimeoutException)", service, re.I)):
        raise RuntimeError("System UI did not answer its service dump; leaving its ANR open")
    bounds = system_ui_wait(hierarchy())
    if errors() != current:
        raise RuntimeError("The error window changed before Wait; no action taken")
    tap(bounds)
    end = time.monotonic() + 12
    while time.monotonic() < end:
        if not errors():
            print("Observed System UI ANR; its service responded, selected Android Wait and verified dismissal",
                  file=sys.stderr)
            return
        time.sleep(.25)
    raise RuntimeError("The acknowledged System UI ANR is still visible")

def prepare_visual_guest(expected_package=None):
    if adb("shell", "getprop", "sys.boot_completed") != "1":
        raise RuntimeError("The Android guest has not completed boot")
    # WAKEUP is idempotent; POWER would put a guest that was already awake back to sleep.
    adb("shell", "input", "keyevent", "KEYCODE_WAKEUP")
    adb("shell", "wm", "dismiss-keyguard")
    if not re.search(r"(?m)^\s*mWakefulness=Awake\s*$", adb("shell", "dumpsys", "power")):
        raise RuntimeError("Guest did not report an awake display")
    require_clear_error_windows()
    home = adb("shell", "cmd", "package", "resolve-activity", "--brief", "-a",
               "android.intent.action.MAIN", "-c", "android.intent.category.HOME").splitlines()[-1]
    launcher = home.partition("/")[0]
    if "/" not in home or launcher in ("android", "com.android.settings") or not re.fullmatch(r"[\w.]+", launcher):
        raise RuntimeError(f"Expected the installed launcher, got {home}")
    allowed = {expected_package} if expected_package else {
        launcher, "com.android.settings", "com.yazsras.astravar",
        "com.yazsras.astravar.target", "com.yazsras.astravar.v2uidebug"}
    # The real UI tests launch their own activity. Forcing HOME from Settings
    # crashes API 37's task-snapshot writer on the release AVD.
    adb("shell", "cmd", "statusbar", "collapse")
    def foreground():
        displays = adb("shell", "dumpsys", "window", "displays")
        match = re.search(r"(?m)^\s*mCurrentFocus=Window\{[^}\n]*\bu0\s+([\w.]+)/[^}\n]+\}", displays)
        return match[1] if match else None
    end = time.monotonic() + 20
    observed = set()
    while time.monotonic() < end:
        require_clear_error_windows()
        current = foreground()
        if current in allowed:
            observed = {n.get("package") for n in hierarchy().iter("node")}
            if current in observed and foreground() == current:
                print(f"Verified focused and accessible Android package {current}", file=sys.stderr)
                return
        time.sleep(.25)
    raise RuntimeError(f"Expected a focused accessible activity; focus={foreground()}, observed={sorted(observed)}")

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
if args.mode == "read":
    print("true" if hidden() else "false")
    sys.exit(0)
prepare_visual_guest()
if args.mode == "ready":
    sys.exit(0)
current = hidden()
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
        require_clear_error_windows()
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
        prepare_visual_guest(pkg)
        sys.exit(0)
    time.sleep(.2)
raise RuntimeError("The framework did not confirm the requested silent-icon preference")
