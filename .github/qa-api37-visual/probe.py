"""Replay the stale original guest window; distinguish live AVD observations from that replay."""
import ast
import json
from pathlib import Path
import re
import subprocess
import xml.etree.ElementTree as ET
import zipfile

out=Path("validation/visual-original")
out.mkdir(parents=True,exist_ok=True)
source=Path("scripts/ci-android17-silent-icons.py")
functions={"visible_anrs","system_ui_wait","location"}
parser=ast.parse(source.read_text())
ns={"re":re}
exec(compile(ast.Module(body=[n for n in parser.body if getattr(n,"name","") in functions],type_ignores=[]),str(source),"exec"),ns)
with zipfile.ZipFile("original.zip") as z:
    windows=z.read("validation/owner-hp-native/windows-final.txt").decode()
    activity=z.read("validation/owner-hp-native/logcat-final.txt").decode(errors="replace")
    seed=z.read("validation/ascension-api37/seedAscensionCode58-light.txt").decode()
assert ns['visible_anrs'](windows)=={"com.android.systemui"}
assert ns['visible_anrs'](windows.replace("Application Not Responding: com.android.systemui","Application Not Responding: com.yazsras.astravar"))=={"com.yazsras.astravar"}
assert re.search(r"Reason: Process ProcessRecord\{.*:com\.android\.systemui/[^}]+\} failed to complete startup",activity)
assert "Astravar or its restored tool must become accessible within 60 seconds" in seed
window=next(l for l in windows.splitlines() if "Application Not Responding: com.android.systemui}:" in l)
(out/"visible-window.txt").write_text(window+"\n")
# The original archive only records WindowManager, not an accessibility XML.
# These controlled nodes model the framework resource IDs, tested separately from the live UI.
xml=ET.fromstring("""<hierarchy><node package="android" resource-id="android:id/alertTitle" text="System UI isn't responding"/><node package="android" resource-id="android:id/aerr_close" text="Close app" clickable="true" enabled="true" bounds="[98,200][999,300]"/><node package="android" resource-id="android:id/aerr_wait" text="Wait" clickable="true" enabled="true" bounds="[98,300][999,400]"/></hierarchy>""")
assert ns["system_ui_wait"](xml)==(98,300,999,400)
for alien in ["Astravar isn't responding", "Process system isn't responding"]:
    xml.find('node').set('text',alien)
    try:
        ns["system_ui_wait"](xml)
    except RuntimeError:
        pass
    else:
        raise AssertionError("Must never acknowledge another error")
print("Original release startup ANR and independent failed 60-second app launch verified in the archived evidence; scoped dialog classifier passed.")
serial="emulator-5584"
def adb(*argv):
    return subprocess.check_output(["adb","-s",serial,*argv],text=True,timeout=18).strip()
assert adb("emu","avd","name").splitlines()[0]=="AstravarHpCI"
assert adb("shell","getprop","ro.kernel.qemu")=="1" and adb("shell","getprop","ro.build.version.sdk")=="37"
present=ns['visible_anrs'](adb("shell","dumpsys","window","windows"))
assert present in (set(),{"com.android.systemui"}), present
before="clear foreground" if not present else "SystemUI ANR"
live_original=None
if present:
    old=out/"original-helper.py"
    old.write_bytes(subprocess.check_output(["git","show","90ea56725f4bd11c2741823f8039c0bc37e28203:scripts/ci-android17-silent-icons.py"]))
    prior=subprocess.check_output(["python3",str(old),serial,"AstravarHpCI","read"],text=True).strip()
    assert prior in ("true","false")
    result=subprocess.run(["python3",str(old),serial,"AstravarHpCI","show" if prior=="true" else "hide"],capture_output=True,text=True,timeout=120)
    live_original="original foreground accessible" if result.returncode==0 else "original switch unreachable"
    if result.returncode: assert "Settings did not expose" in result.stderr, result.stderr[-600:]
print("Clean guest before correction:",before,live_original or "",flush=True)
(out/"original-status.json").write_text(json.dumps({"archivedVisibleANR":"com.android.systemui","archiveLaunchTimeoutSeconds":60,"archivedWindowParser":True,"controlledSemanticGuards":True,"freshGuest":before,"freshGuestOriginalHelper":live_original}))
