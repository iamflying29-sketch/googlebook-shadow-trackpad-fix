#!/usr/bin/env python3
"""shadow-trackpad doctor: re-verifies the Shadow Trackpad Fix after Shadow PC or Android updates.

Usage: doctor.py ADB_SERIAL
Environment: ANDROID_USER_ID (default 10), JADX (default ~/tools/jadx/bin/jadx).
Needs ../trackpad-probe/TrackpadProbe.apk and dex/keytest.dex (built by their build.sh scripts).

1. Helper health on the Googlebook (status port / supervisor).
2. Live tests with the Trackpad Probe window: capture detection + exclusive grab, the full
   gesture self-test (delivery latency, zero lost movement, flush events, pinch key order),
   and that Android still lets the gesture shortcuts through to apps.
3. Decompiles the installed Shadow PC and checks every assumption the helper relies on.
   A broken assumption switches the affected feature to its safe fallback.
4. If everything critical passes, records the versions as verified (silences the app's
   "updated" notification).
"""
import os, re, subprocess, sys, tempfile, time

SERIAL = sys.argv[1]
TOOLS = os.path.dirname(os.path.abspath(__file__))
JADX = os.environ.get("JADX", os.path.expanduser("~/tools/jadx/bin/jadx"))
# Your Android user ID (adb shell am get-current-user); 10 on my Googlebook.
USER = os.environ.get("ANDROID_USER_ID", "10")
if not USER.isdigit():
    sys.exit("ANDROID_USER_ID must be a number")
PROBE_APK = os.path.join(TOOLS, "..", "trackpad-probe", "TrackpadProbe.apk")
DIR = "/data/local/tmp/shadowtrackpad"
APP = "local.shadowtrackpadfix"
SHADOW = "com.blade.shadowcloudgaming"
PROBE = "local.trackpadprobe"

results = []  # (ok, critical, name, detail)
fallbacks = {}


def sh(cmd, timeout=120):
    return subprocess.run(["adb", "-s", SERIAL, "shell", cmd], capture_output=True, text=True, timeout=timeout).stdout


def check(ok, name, detail="", critical=True):
    results.append((bool(ok), critical, name, detail))
    mark = "PASS" if ok else ("FAIL" if critical else "WARN")
    print(f"  [{mark}] {name}" + (f": {detail}" if detail else ""), flush=True)
    return ok


def helper(cmd):
    return sh(f"CLASSPATH=$(pm path --user current {APP} | head -1 | cut -d: -f2) app_process / local.shadowtrackpad.Main {cmd}")


def logcat_probe():
    return sh("logcat -d -s TrackpadProbe:I")


def flush_is_safe(mbp, rvf):
    """Shadow must handle BUTTON_PRESS (11) / BUTTON_RELEASE (12) exactly like DOWN (0): in its
    MouseButtonProcessor, which only acts on a change of buttonState - so the helper's no-op
    BUTTON_RELEASE after each move changes nothing. Matched without relying on register names."""
    u = rvf.find("Unhandled mouse action")
    if "getButtonState() ^" not in mbp or u < 0:
        return False
    blk = rvf[max(0, u - 2500):u]
    m11 = re.search(r"(\w+) = 11\s+if \((\w+) == \1\) goto (L\w+)", blk)
    m12 = re.search(r"(\w+) = 12\s+if \((\w+) == \1\) goto (L\w+)", blk)
    if not (m11 and m12) or m11.group(2) != m12.group(2) or m11.group(3) != m12.group(3):
        return False
    var, target = m11.group(2), m11.group(3)
    down = re.search(re.escape(var) + r" = \w+\.getActionMasked\(\)\s+if \(" + re.escape(var) + r" == 0\) goto " + target + r"\b", blk)
    i = rvf.find("        " + target + ":")
    return down is not None and i >= 0 and "MouseButtonProcessor" in rvf[i:i + 400]


import atexit, shutil
_cleanup = {"work": None, "probe": False}


def _at_exit():
    """Runs however the doctor ends: temp files removed, Linux Terminal back in front."""
    if _cleanup["work"]:
        shutil.rmtree(_cleanup["work"], ignore_errors=True)
    if _cleanup["probe"]:
        try:
            sh(f"am start --user {USER} -n com.android.virtualization.terminal/.new2.ui.MainActivity")
        except Exception:
            pass


atexit.register(_at_exit)


def open_probe(capture=True):
    _cleanup["probe"] = True
    sh("logcat -c")
    sh(f"am start --user {USER} -S -n {PROBE}/.MainActivity" + (" --ez capture true" if capture else ""))
    time.sleep(2.5)


def set_config(key, value):
    sh(f"touch {DIR}/config.properties; grep -v '^{key}=' {DIR}/config.properties > {DIR}/c.tmp; "
       f"echo '{key}={value}' >> {DIR}/c.tmp; mv {DIR}/c.tmp {DIR}/config.properties")


def get_config():
    return sh(f"cat {DIR}/config.properties 2>/dev/null")


def backup_config():
    """Exact copy of the settings file (or None if there is none). Raises if adb didn't answer."""
    out = sh(f"if [ -f {DIR}/config.properties ]; then echo EXISTS; base64 {DIR}/config.properties | tr -d '\\n'; echo; else echo NONE; fi")
    lines = out.splitlines()
    if not lines or lines[0] not in ("EXISTS", "NONE"):
        raise RuntimeError("could not read the settings file over adb")
    return lines[1].strip() if lines[0] == "EXISTS" and len(lines) > 1 else ("" if lines[0] == "EXISTS" else None)


def restore_config(b64):
    if b64 is None:
        sh(f"rm -f {DIR}/config.properties")
    else:
        sh(f"umask 077; echo '{b64}' | base64 -d > {DIR}/config.tmp && mv {DIR}/config.tmp {DIR}/config.properties")


FALLBACK_FILE = f"{DIR}/doctor-fallbacks"


def doctor_fallbacks():
    """Settings the doctor itself switched to a fallback earlier (so it may switch them back)."""
    return set(l.strip() for l in sh(f"cat {FALLBACK_FILE} 2>/dev/null").splitlines() if l.strip())


def remove_config_key(key):
    sh(f"touch {DIR}/config.properties; grep -v '^{key}=' {DIR}/config.properties > {DIR}/c.tmp; mv {DIR}/c.tmp {DIR}/config.properties")


print("== 1. Helper on the Googlebook")
status = sh(f"sh {DIR}/start.sh status")
check(status.startswith("running"), "helper running", status.splitlines()[0] if status else "no answer")
check("supervisor: running" in status, "supervisor running")
health = next((l for l in status.splitlines() if l.startswith("HEALTH:")), "HEALTH: ?")
check(health == "HEALTH: ok", "helper health", health)
tp = next((l for l in status.splitlines() if l.startswith("touchpad:")), "")
check("/dev/input/" in tp, "trackpad detected", tp)
inj = next((l for l in status.splitlines() if l.startswith("injection:")), "")
check(bool(re.search(r"rejected 0\b", inj)), "no rejected injections", inj, critical=False)

procs = sh("dumpsys activity processes")
bad_section = re.search(r"Bad processes:\n((?:[ \t]+.*\n?)*)", procs)
listed = bool(bad_section and re.search(r"Bad process " + re.escape(APP) + r"\b", bad_section.group(1)))
if not procs.strip():
    check(False, "app not blocked by Android (no 'bad process' lockout)", "could not read the process list", critical=False)
else:
    check(not listed, "app not blocked by Android (no 'bad process' lockout)", "open the Shadow Trackpad Fix app once to clear it" if listed else "")
resp = sh(f"am broadcast --user {USER} -n {APP}/.ControlReceiver -a local.shadowtrackpadfix.CONFIG")
check("keepUsbDebugging=" in resp, "app responds to background requests (boot/health checks will run)", resp.strip().splitlines()[-1] if resp.strip() else "no answer")

# The live tests need the probe window in front: impossible while the screen is off or locked.
# Then nothing is tested or changed (no fallbacks, the last verification stays as it is).
power, windows = sh("dumpsys power"), sh("dumpsys window")
if "mWakefulness=Awake" not in power or "isKeyguardShowing=true" in windows or "mDreamingLockscreen=true" in windows:
    print("== The Googlebook's screen is off or locked: the live tests need it awake and unlocked.")
    print("   Nothing was tested or changed. Unlock it, then run 'shadow-trackpad doctor' again.")
    sys.exit(2)

print("== 2. Live tests with the Trackpad Probe window")
if PROBE not in sh(f"pm list packages --user {USER} {PROBE}"):
    subprocess.run(["adb", "-s", SERIAL, "install", "-r", "--user", USER, PROBE_APK],
                   capture_output=True)
cfg_before = get_config()
cfg_backup = backup_config()  # aborts the run (before changing anything) if adb didn't answer
try:
    set_config("packages", f"{SHADOW},{PROBE}")
    time.sleep(3)
    st = ""
    for attempt in range(4):  # the helper may be restarting (e.g. right after an app update)
        open_probe(True)
        time.sleep(1.5)
        st = helper("status")
        if "ACTIVE" in st:
            break
        time.sleep(2)
    check("ACTIVE" in st, "capture detection + exclusive trackpad grab", st.splitlines()[0] if st else "no answer")
finally:
    restore_config(cfg_backup)  # byte-for-byte what was there before

open_probe(True)
self_test = helper("selftest")
time.sleep(1)
log = logcat_probe()
mv = [int(m) for m in re.findall(r"CAPTURED ACTION_MOVE src=MOUSE_RELATIVE.*?lat=(-?\d+)", log)]
tpd = [int(m) for m in re.findall(r"CAPTURED ACTION_MOVE src=TOUCHPAD.*?lat=(-?\d+)", log)]
merged = sum(int(h) for h in re.findall(r"CAPTURED ACTION_MOVE.*?hist=(\d+)", log))
flushes = len(re.findall(r"CAPTURED ACTION_BUTTON_RELEASE", log))
check(len(mv) >= 140, "cursor moves delivered", f"{len(mv)} of ~149")
check(merged == 0, "no lost (merged) movement", f"{merged} merged samples")
if mv:
    check(sum(mv) / len(mv) < 3, "cursor delivery latency", f"avg {sum(mv)/len(mv):.2f} ms", critical=False)
check(len(tpd) >= 150, "smooth scroll / flick / pinch events delivered", f"{len(tpd)} events")
check(flushes > 100, "instant-delivery flush events accepted", f"{flushes}")
order = re.findall(r"key (DOWN|UP)\s+KEYCODE_CTRL_LEFT|src=TOUCHPAD (?:.*?)ACTION_(DOWN|UP) ", log)
downs = log.count("key DOWN KEYCODE_CTRL_LEFT")
ups = log.count("key UP KEYCODE_CTRL_LEFT")
last_ctrl_up = log.rfind("key UP KEYCODE_CTRL_LEFT")
last_tp_up = log.rfind("CAPTURED ACTION_UP src=TOUCHPAD")
# Self-test: one pinch, then two quick pinches in a row -> exactly 2 Ctrl press/release pairs,
# Ctrl never stuck, and released only after the synthetic fingers lifted.
check(downs == 2 and ups == 2 and last_tp_up < last_ctrl_up, "pinch: Ctrl held through each zoom (incl. two quick pinches), never stuck",
      f"{downs} presses / {ups} releases")
presses = re.findall(r"CAPTURED ACTION_BUTTON_PRESS .*?btn=(0x[0-9a-f]+)", log)
check(presses[-2:] == ["0x1", "0x2"], "clicks: thumb resting in bottom zone + click = LEFT, two fingers = RIGHT",
      f"button presses seen: {presses[-2:]}")
check(any(l.startswith("engine+injection") for l in self_test.splitlines()), "self-test ran",
      next((l for l in self_test.splitlines() if l.startswith("engine+injection")), self_test.strip()[:80]), critical=False)

# Android must still let the gesture shortcuts reach apps (not swallow them as system shortcuts).
pushed = subprocess.run(["adb", "-s", SERIAL, "push", os.path.join(TOOLS, "dex", "keytest.dex"), "/data/local/tmp/keytest.dex"],
                        capture_output=True).returncode == 0
open_probe(False)
sh("CLASSPATH=/data/local/tmp/keytest.dex app_process / KeyTest CTRL_LEFT+ALT_LEFT+TAB ALT_LEFT+DPAD_LEFT ALT_LEFT+DPAD_RIGHT CTRL_LEFT")
time.sleep(1)
keys = logcat_probe()
key_test_ran = pushed and "key DOWN KEYCODE_CTRL_LEFT" in keys  # Ctrl is never reserved: proves the test ran
switcher_ok = "key DOWN KEYCODE_TAB" in keys
backfwd_ok = "key DOWN KEYCODE_DPAD_LEFT" in keys and "key DOWN KEYCODE_DPAD_RIGHT" in keys
if not key_test_ran:
    switcher_ok = backfwd_ok = None  # inconclusive: change nothing
    check(False, "keyboard-shortcut test ran", "the test window didn't receive keys, so the result is inconclusive and nothing was changed", critical=False)
else:
    check(switcher_ok, "Ctrl+Alt+Tab reaches apps (3-finger switcher gestures)", critical=False)
    check(backfwd_ok, "Alt+Left/Right reach apps (4-finger back/forward)", critical=False)
    check(True, "Ctrl reaches apps (pinch-zoom)")
    if not switcher_ok or not backfwd_ok:
        fallbacks["gestures"] = "false (Android now reserves a shortcut the swipes use)"
# Bring the Linux Terminal straight back to the front (leaving it in the background can make the
# Terminal app drop its connection, which ends the shell running there).
sh(f"am start --user {USER} -n com.android.virtualization.terminal/.new2.ui.MainActivity")

print("== 3. Shadow PC code checks")
ver = sh(f"dumpsys package {SHADOW}")
vname = re.search(r"versionName=(\S+)", ver)
vcode = re.search(r"versionCode=(\d+)", ver)
shadow_version = f"{vname.group(1) if vname else '?'}/{vcode.group(1) if vcode else '?'}"
print(f"  Shadow PC {shadow_version}")
apk = sh(f"pm path --user {USER} {SHADOW}").splitlines()
base = next((l.split(":", 1)[1] for l in apk if l.endswith("base.apk")), None)
work = tempfile.mkdtemp(prefix="shadow-doctor-")
_cleanup["work"] = work
classes = {
    "RendererViewModel": "com.blade.shadow.client.renderer.RendererViewModel",
    "TouchpadGestureProcessor": "com.blade.shadow.client.renderer.input.processors.TouchpadGestureProcessor",
    "RendererWindowService": "com.blade.shadow.client.renderer.RendererWindowService",
    "MouseButtonProcessor": "com.blade.shadow.client.renderer.input.processors.MouseButtonProcessor",
    "KeyboardTriggerManager": "com.blade.shadow.client.renderer.input.KeyboardTriggerManager",
    "MouseMotionProcessor": "com.blade.shadow.client.renderer.input.processors.MouseMotionProcessor",
}
src = {}
if base:
    subprocess.run(["adb", "-s", SERIAL, "pull", base, f"{work}/base.apk"], capture_output=True)
    for short, cls in classes.items():
        out = f"{work}/{short}"
        try:
            subprocess.run([JADX, "-d", out, "--no-res", "--show-bad-code", "--single-class", cls, f"{work}/base.apk"],
                           capture_output=True, timeout=600)
        except subprocess.TimeoutExpired:
            pass
        files = [os.path.join(dp, f) for dp, _, fs in os.walk(out) for f in fs if f.endswith(".java")]
        src[short] = "\n".join(open(f, errors="replace").read() for f in files)
    fb = f"{work}/fallback"
    try:
        subprocess.run([JADX, "-d", fb, "--no-res", "--decompilation-mode", "fallback", "--single-class",
                        classes["RendererViewModel"], f"{work}/base.apk"], capture_output=True, timeout=600)
    except subprocess.TimeoutExpired:
        pass
    src["RVM_fallback"] = "\n".join(open(os.path.join(dp, f), errors="replace").read()
                                    for dp, _, fs in os.walk(fb) for f in fs if f.endswith(".java"))
found = all(src.get(k) for k in classes)
check(found, "Shadow's input classes located", "" if found else "class names changed, so the code checks were skipped", critical=True)

if found:
    rvm, tgp, rws = src["RendererViewModel"], src["TouchpadGestureProcessor"], src["RendererWindowService"]
    mbp, ktm, mmp, rvf = src["MouseButtonProcessor"], src["KeyboardTriggerManager"], src["MouseMotionProcessor"], src["RVM_fallback"]
    scroll_ok = ("1048584" in rvm or "SOURCE_TOUCHPAD" in rvm) and "getPointerCount() > 1" in tgp \
        and "OnScroll" in tgp and re.search(r"OnScroll[\s\S]{0,400}MouseEvent\.Wheel", rws) is not None
    check(scroll_ok, "captured-touchpad two-finger drag -> fine-grained wheel (smooth scroll, flick, pinch)")
    if not scroll_ok:
        fallbacks["scroll_mode"] = "notch (Shadow's touchpad scroll path changed)"
    flush_ok = flush_is_safe(mbp, rvf)
    check(flush_ok, "no-op BUTTON_RELEASE is ignored by Shadow (instant delivery is safe)")
    if not flush_ok:
        fallbacks["low_latency"] = "false (Shadow's button handling changed)"
    m = re.search(r"getEventTime\(\) - this\.\w+ <= (\d+)", ktm) or re.search(r"r3 = (\d+)\s+int r0 = \(r6 > r3", ktm)
    threshold = int(m.group(1)) if m else None
    hold = 130
    hm = re.search(r"^scroll_hold_ms=(\d+)", cfg_before, re.M)
    if hm:
        hold = int(hm.group(1))
    if threshold is not None and threshold + 20 > hold:
        fallbacks["scroll_hold_ms"] = f"{threshold + 30} (Shadow's two-finger keyboard trap is now {threshold} ms)"
        hold = threshold + 30
    check(threshold is not None, "two-finger keyboard trap understood (helper holds synthetic fingers longer)",
          f"trap {threshold} ms, helper holds {hold} ms" if threshold else "threshold not found")
    check("131076" in mmp and "getX()" in mmp, "relative cursor moves read from the event's X/Y")
    check("getMouseSpeedRatio" in rws, "Shadow's mouse-speed slider still applied per event",
          "keep Shadow > Settings > Mouse speed at 1.0", critical=False)

print("== 4. Result")
critical_fail = [r for r in results if not r[0] and r[1]]
mine = doctor_fallbacks()
works_again = {"gestures": switcher_ok is True and backfwd_ok is True,
               "scroll_mode": found and scroll_ok, "low_latency": found and flush_ok,
               "scroll_hold_ms": found and threshold is not None and threshold + 20 <= 130}
for key in sorted(mine):
    if works_again.get(key) and key not in fallbacks:
        remove_config_key(key)  # back to the normal behaviour; settings you chose yourself are never touched
        mine.discard(key)
        print(f"  Restored normal behaviour: {key} (verified working again)")
for k, v in fallbacks.items():
    set_config(k, v.split(" ")[0])
    mine.add(k)
    print(f"  Safe fallback applied: {k}={v}")
sh(f"printf '%s\\n' {' '.join(sorted(mine))} > {FALLBACK_FILE}" if mine else f"rm -f {FALLBACK_FILE}")
fp = sh("getprop ro.build.fingerprint").strip()
if not critical_fail:
    sh(f"echo 'shadow={shadow_version} android={fp}' > {DIR}/verified")
    print(f"  ALL CRITICAL CHECKS PASSED: verified for Shadow {shadow_version} on {fp}")
else:
    sh(f"rm -f {DIR}/verified")
    print(f"  {len(critical_fail)} critical check(s) failed:")
    for ok, crit, name, detail in critical_fail:
        print(f"    - {name}: {detail}")
    print("  The fix keeps running with safe fallbacks where possible. The failed checks above show which part of the helper needs adapting.")
sys.exit(0 if not critical_fail else 1)
