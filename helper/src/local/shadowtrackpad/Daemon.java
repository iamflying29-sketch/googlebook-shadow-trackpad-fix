package local.shadowtrackpad;

import android.os.SystemClock;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructPollfd;

import java.io.BufferedReader;
import java.io.FileDescriptor;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Watches Shadow's pointer capture, grabs/releases the trackpad and feeds the engine. */
final class Daemon {
    private static final int EVIOCGRAB = 0x40044590;
    private static final int EV_KEY = 1;
    private static final int EVENT_SIZE = 24; // struct input_event on 64-bit

    private final Config config;
    private final Injector injector;
    private final Engine engine;
    private final Object os;
    private final Method ioctlInt;

    private volatile String touchpadPath, keyboardPath;
    private volatile boolean needsReset; // set by the watchdog; the main thread releases keys/buttons
    private Thread mainThread;
    private static final java.io.File STANDDOWN_FILE = new java.io.File("/data/local/tmp/shadowtrackpad/standdown");
    private volatile float resX = 31f, resY = 31f;
    // Problems worth reporting (shown in status as HEALTH; the app turns them into notifications).
    final java.util.Set<String> health = java.util.Collections.synchronizedSet(new java.util.TreeSet<>());
    private volatile String shadowVersion = "?", verified = "none";
    private volatile boolean standDown; // fail-safe: stop taking over the trackpad
    private volatile long standDownUntil; // wall clock end of a timed stand-down, 0 = none
    private volatile long loopTick = SystemClock.uptimeMillis();
    volatile boolean stallTest; // "test-stall": freeze the main loop once to prove the watchdog works
    private volatile int watchdogFires;
    private volatile String notes = ""; // informational, not problems
    private volatile String shortcutNote = "";
    private volatile long pollTick = SystemClock.uptimeMillis(); // last completed capture check
    private volatile boolean shadowCaptured, paused, running = true;
    private volatile String captureWindow = "None", captureMode = "?";
    private volatile String lastDump = "";
    private volatile FileDescriptor tpFd, kbdFd; // shared with the watchdog thread
    // Hardware event (kernel timestamp) -> output handed to Android. Kernel time is CLOCK_REALTIME
    // with microsecond resolution, compared against currentTimeMillis (ms resolution).
    final Latency endToInject = new Latency("trackpad->injected (incl. wakeup)");
    // Pure processing time of a frame in this helper (read + gesture logic + injection calls).
    final Latency processing = new Latency("helper processing per frame");
    private int grabs;

    Daemon(Config config, Injector injector) throws ReflectiveOperationException {
        this.config = config;
        this.injector = injector;
        this.engine = new Engine(config, injector);
        os = Class.forName("libcore.io.Libcore").getField("os").get(null);
        ioctlInt = os.getClass().getMethod("ioctlInt", FileDescriptor.class, int.class);
    }

    void setPaused(boolean p) {
        paused = p;
        Main.log(p ? "paused" : "resumed");
    }

    String status() {
        return status(true);
    }

    /** full=false: for the localhost status port - nothing about other apps (focus, capture window). */
    String status(boolean full) {
        String h;
        synchronized (health) {
            h = health.isEmpty() ? "ok" : String.join(", ", health);
        }
        return "running, " + (standDown ? "STOOD DOWN (fail-safe)" : paused ? "PAUSED" : tpFd != null ? "ACTIVE (trackpad grabbed for Shadow)" : "waiting for Shadow")
                + "\nHEALTH: " + h
                + "\nshadow: " + shadowVersion + "  android: " + android.os.Build.FINGERPRINT
                + "\nverified: " + readVerified()
                + "\ninfo: watchdog releases " + watchdogFires + notes + shortcutNote
                + "\ninjection: " + injector.describe()
                + "\ncapture: " + captureMode + " / " + (full ? captureWindow : (shadowCaptured ? "Shadow" : "not Shadow"))
                + "\ntouchpad: " + touchpadPath + "  keyboard: " + keyboardPath
                + "\ngrabs so far: " + grabs + "  engine mode: " + engine.mode()
                + "\n" + endToInject + "\n" + processing
                + "\nconfig: " + config;
    }

    /**
     * If the main loop ever stalls while it holds the trackpad, the trackpad would feel dead.
     * After 1.5 s the grab is dropped (Android gets the trackpad back); after 5 s the process
     * exits and the supervisor starts a fresh one.
     */
    private long firedForTick = -1;

    private void watchdog() {
        while (running) {
            SystemClock.sleep(250);
            long tick = loopTick;
            long stalled = SystemClock.uptimeMillis() - tick;
            FileDescriptor fd = tpFd;
            // Fire once per stall: the loop must have moved on (new tick) before it can fire again.
            if (stalled > 1500 && fd != null && tick != firedForTick) {
                firedForTick = tick;
                Main.log("WATCHDOG: main loop stalled " + stalled + " ms - releasing the trackpad");
                watchdogFires++;
                needsReset = true; // held keys/buttons/drag are released as soon as the main loop runs
                try { Os.close(fd); } catch (ErrnoException ignored) { }
                tpFd = null;
                FileDescriptor k = kbdFd;
                kbdFd = null;
                if (k != null) try { Os.close(k); } catch (ErrnoException ignored) { }
            }
            if (stalled > 5000) {
                Main.log("WATCHDOG: main loop stuck " + stalled + " ms - exiting for a clean restart");
                try {
                    injector.resetAll(); // best effort: never leave Ctrl or a button down in Windows
                } catch (Throwable ignored) {
                }
                Runtime.getRuntime().halt(3);
            }
        }
    }

    /** Stops the main loop and lets it release everything itself (no injection after the release). */
    void shutdown() {
        running = false;
        Thread t = mainThread;
        if (t != null && t != Thread.currentThread()) {
            try {
                t.join(1000);
            } catch (InterruptedException ignored) {
            }
        }
        synchronized (this) {
            release("shutdown");
        }
    }

    void run() {
        mainThread = Thread.currentThread();
        try {
            android.os.Process.setThreadPriority(-8); // like Android's own display/input threads
        } catch (RuntimeException e) {
            Main.log("priority: " + e);
        }
        findDevices();
        readVersions();
        checkStandDownHistory();
        Thread watchdog = new Thread(this::watchdog, "watchdog");
        watchdog.setDaemon(true);
        watchdog.start();
        Thread poller = new Thread(this::pollCapture, "capture-poller");
        poller.setDaemon(true);
        poller.start();
        MtParser parser = new MtParser();
        byte[] buf = new byte[EVENT_SIZE * 64];
        ByteBuffer bb = ByteBuffer.wrap(buf).order(ByteOrder.nativeOrder());
        while (running) {
            loopTick = SystemClock.uptimeMillis();
            if (stallTest) {
                stallTest = false;
                Main.log("test-stall: freezing main loop for 4 s");
                SystemClock.sleep(4000);
                loopTick = SystemClock.uptimeMillis();
            }
            // After a watchdog release: let go of held keys/buttons/drag before anything else
            // (in particular before grabbing the trackpad again).
            if (needsReset) {
                needsReset = false;
                try {
                    engine.reset();
                } catch (Throwable t) {
                    Main.log("reset after watchdog: " + t);
                }
            }
            try {
                if (standDown && standDownUntil != 0 && System.currentTimeMillis() > standDownUntil) {
                    standDown = false;
                    standDownUntil = 0;
                    health.removeIf(h -> h.startsWith("stood-down"));
                    Main.log("FAIL-SAFE pause is over: taking over the trackpad for Shadow again");
                }
                if (!standDown && injector.consecutiveFailures() > 20) {
                    standDown = true;
                    health.add("stood-down:injection-failing");
                    synchronized (this) { release("fail-safe"); }
                    recordStandDown();
                    // A fresh process gets fresh connections to Android's services (e.g. after a
                    // system_server restart); the supervisor starts it in 2 s.
                    Main.log("FAIL-SAFE: Android keeps rejecting injected events - released the trackpad, restarting the helper");
                    Runtime.getRuntime().halt(4);
                }
                // If the capture check itself stalls (e.g. a hung system command), its last answer can't
                // be trusted: give the trackpad back rather than risk holding it outside Shadow.
                boolean checkFresh = SystemClock.uptimeMillis() - pollTick < 3000;
                boolean want = shadowCaptured && checkFresh && !paused && !standDown && touchpadPath != null;
                synchronized (this) {
                    injector.lowLatency = config.lowLatency;
                    injector.scrollHoldMs = config.scrollHoldMs;
                    if (want && tpFd == null) {
                        grab();
                        parser.resX = resX;
                        parser.resY = resY;
                        parser.resetUnknown();
                    } else if (!want && tpFd != null) {
                        release(paused ? "paused" : !checkFresh ? "capture check not responding" : "Shadow no longer has pointer capture");
                    }
                }
                // Local copies: the watchdog may close and clear the shared fields at any moment.
                FileDescriptor tp = tpFd, kb = kbdFd;
                if (tp == null) {
                    SystemClock.sleep(100);
                    continue;
                }
                long timer = engine.nextTimerMs();
                int timeout = (int) (timer < 0 ? 100 : Math.min(100, timer));
                StructPollfd[] fds = kb != null ? new StructPollfd[]{pfd(tp), pfd(kb)} : new StructPollfd[]{pfd(tp)};
                Os.poll(fds, timeout);
                if (fds.length > 1 && (fds[1].revents & (OsConstants.POLLERR | OsConstants.POLLHUP | OsConstants.POLLNVAL)) != 0) {
                    // Keyboard went away (e.g. an external keyboard unplugged): stop polling it.
                    if (kbdFd == kb) kbdFd = null;
                    try { Os.close(kb); } catch (ErrnoException ignored) { }
                } else if (fds.length > 1 && (fds[1].revents & OsConstants.POLLIN) != 0) {
                    drainKeyboard(kb, buf);
                }
                if ((fds[0].revents & (OsConstants.POLLERR | OsConstants.POLLHUP)) != 0) {
                    synchronized (this) { release("touchpad error"); }
                    SystemClock.sleep(500);
                    continue;
                }
                if ((fds[0].revents & OsConstants.POLLIN) != 0) {
                    long readStart = System.nanoTime();
                    int n = Os.read(tp, buf, 0, buf.length);
                    for (int off = 0; off + EVENT_SIZE <= n; off += EVENT_SIZE) {
                        long sec = bb.getLong(off), usec = bb.getLong(off + 8);
                        int type = bb.getShort(off + 16) & 0xffff, code = bb.getShort(off + 18) & 0xffff;
                        int value = bb.getInt(off + 20);
                        int r = parser.onEvent(type, code, value);
                        if (r == MtParser.DROPPED) {
                            Main.logLimited("syn-dropped", "SYN_DROPPED: events were lost, resetting");
                            engine.reset();
                        } else if (r == MtParser.FRAME) {
                            Engine.Frame cur = parser.frame;
                            cur.time = sec + usec / 1e6;
                            // Kernel timestamps are CLOCK_REALTIME; events need uptime.
                            long nowUp = SystemClock.uptimeMillis();
                            long frameUptime = nowUp - (System.currentTimeMillis() - (sec * 1000 + usec / 1000));
                            // Implausible (clock changed): stamp with "now" instead.
                            injector.frameUptime = (frameUptime > nowUp || nowUp - frameUptime > 1000) ? nowUp : frameUptime;
                            engine.onFrame(cur);
                            injector.frameUptime = 0;
                            processing.add((System.nanoTime() - readStart) / 1000);
                            endToInject.add(System.currentTimeMillis() * 1000 - (sec * 1000000 + usec));
                        }
                    }
                }
                engine.tick();
            } catch (ErrnoException e) {
                if (e.errno == OsConstants.EINTR) continue;
                Main.logLimited("io", "io: " + e);
                synchronized (this) { release("io error"); }
                SystemClock.sleep(500);
            } catch (Throwable t) {
                Main.logLimited("error:" + t, "error: " + android.util.Log.getStackTraceString(t));
                synchronized (this) { release("error"); }
                SystemClock.sleep(500);
            }
        }
        // Leaving (stop / shutdown): release keys, buttons and the trackpad from this thread.
        synchronized (this) {
            needsReset = true;
            release("shutdown");
        }
    }

    private static StructPollfd pfd(FileDescriptor fd) {
        StructPollfd p = new StructPollfd();
        p.fd = fd;
        p.events = (short) OsConstants.POLLIN;
        return p;
    }

    /** Only the timing of key presses is used (to ignore accidental taps while typing). */
    private void drainKeyboard(FileDescriptor kb, byte[] buf) throws ErrnoException, java.io.InterruptedIOException {
        int n = Os.read(kb, buf, 0, buf.length);
        ByteBuffer bb = ByteBuffer.wrap(buf).order(ByteOrder.nativeOrder());
        for (int off = 0; off + EVENT_SIZE <= n; off += EVENT_SIZE) {
            if ((bb.getShort(off + 16) & 0xffff) == EV_KEY && bb.getInt(off + 20) == 1) {
                engine.lastKeyUptime = SystemClock.uptimeMillis();
            }
        }
    }

    private void grab() throws Exception {
        config.readAndroidSettings(lastDump);
        FileDescriptor fd;
        try {
            fd = Os.open(touchpadPath, OsConstants.O_RDONLY | OsConstants.O_NONBLOCK, 0);
            health.remove("cannot-open-trackpad");
        } catch (ErrnoException e) {
            // The node may have been renumbered (e.g. after a resume) - look again once.
            findDevices();
            if (touchpadPath == null) throw new Exception("trackpad not found");
            try {
                fd = Os.open(touchpadPath, OsConstants.O_RDONLY | OsConstants.O_NONBLOCK, 0);
            } catch (ErrnoException e2) {
                health.add("cannot-open-trackpad");
                throw new Exception("cannot open trackpad (" + e2 + ") - Android may have removed shell input access");
            }
        }
        try {
            ioctlInt.invoke(os, fd, EVIOCGRAB);
        } catch (java.lang.reflect.InvocationTargetException e) {
            Os.close(fd);
            throw new Exception("grab failed: " + e.getCause());
        }
        tpFd = fd;
        if (keyboardPath != null) {
            try {
                kbdFd = Os.open(keyboardPath, OsConstants.O_RDONLY | OsConstants.O_NONBLOCK, 0);
            } catch (ErrnoException e) {
                kbdFd = null;
            }
        }
        grabs++;
        engine.reset();
        Main.log("ACTIVE: trackpad grabbed for " + captureWindow + " (" + captureMode + ")  natural=" + config.natural()
                + " tap=" + config.tapToClick() + " tapDrag=" + config.tapDrag() + " sensitivity=" + config.androidPointerSensitivity);
    }

    /** Closing the descriptor releases the exclusive grab; Android gets the trackpad back. */
    private void release(String why) {
        if (tpFd == null) {
            if (needsReset) {
                needsReset = false;
                try { engine.reset(); } catch (Throwable t) { Main.log("reset: " + t); }
            }
            return;
        }
        try {
            engine.reset();
        } catch (Throwable t) {
            Main.log("reset: " + t);
        }
        try { Os.close(tpFd); } catch (ErrnoException ignored) { }
        tpFd = null;
        if (kbdFd != null) {
            try { Os.close(kbdFd); } catch (ErrnoException ignored) { }
            kbdFd = null;
        }
        Main.log("released trackpad (" + why + ")");
    }

    // ---- capture state -------------------------------------------------------------------------

    /*
     * Which app is focused comes from a cheap binder call (~0.1 ms). Whether it holds pointer
     * capture is only visible in "dumpsys input" (measured: polling it adds at most ~3-4 ms to an
     * input event), so that runs only while Shadow is focused. Several formats are understood
     * (Android 17 and older AOSP layouts); if none can be read after some future update, the
     * helper stands down (stock trackpad behaviour) and reports it, instead of guessing.
     */
    private static final java.util.regex.Pattern P_CAPTURE_WINDOW = java.util.regex.Pattern.compile("Current Window with Pointer Capture: *(.*)");
    private static final java.util.regex.Pattern P_MODE_REQUESTED = java.util.regex.Pattern.compile("Pointer Capture (?:Mode )?Requested: *(\\w+)");
    private static final java.util.regex.Pattern P_MAPPER_MODE = java.util.regex.Pattern.compile("Pointer capture(?:d| mode): *(\\w+)");
    private static final java.util.regex.Pattern P_FOCUSED_WINDOW = java.util.regex.Pattern.compile("FocusedWindows:\\s*\\n\\s*displayId=\\d+, name='[^ ]* ([\\w.]+)/");
    private static final java.util.regex.Pattern P_SHORTCUT = java.util.regex.Pattern.compile("mKeycode=KEYCODE_(\\w+), mModifierState=(\\d+)");

    private void pollCapture() {
        long lastConfigCheck = 0, lastDumpAt = 0, lastVersionCheck = -600_000, lastFind = SystemClock.uptimeMillis();
        String lastFocused = "";
        boolean warnedUnknown = false;
        Object atm = null;
        Method focusedTask = null;
        try {
            atm = Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null);
            focusedTask = atm.getClass().getMethod("getFocusedRootTaskInfo");
        } catch (ReflectiveOperationException e) {
            Main.log("focus API unavailable, using dumpsys for focus too: " + e);
            notes += " focus-via-dumpsys";
        }
        while (running) {
            try {
                long now = SystemClock.uptimeMillis();
                String focused = focusedPackage(atm, focusedTask); // null = unknown
                boolean grabbed = tpFd != null;
                boolean maybeShadow = focused == null || isTarget(focused);
                if (!maybeShadow) {
                    if (shadowCaptured) Main.log("capture: Shadow lost focus (" + focused + ")");
                    shadowCaptured = false;
                    captureWindow = "None (focused: " + focused + ")";
                } else if (now - lastDumpAt >= (!grabbed ? 300 : now - engine.lastActivityUptime < 400 ? 2000 : 500)) {
                    // "dumpsys input" briefly holds Android's input locks (measured up to ~3-4 ms on an
                    // event), so while fingers are moving it runs only every 2 s; a focus change is
                    // still noticed at once by the cheap check above.
                    lastDumpAt = now;
                    String dump = exec("dumpsys", "input");
                    lastDump = dump;
                    if (focused == null) {
                        java.util.regex.Matcher fm = P_FOCUSED_WINDOW.matcher(dump);
                        focused = fm.find() ? fm.group(1) : "";
                    }
                    Boolean captured = shadowHasCapture(dump, isTarget(focused));
                    if (captured == null) {
                        if (!warnedUnknown) Main.log("WARNING: cannot read pointer-capture state from dumpsys input; standing down");
                        warnedUnknown = true;
                        health.add("capture-state-unknown");
                        captured = false;
                    } else if (warnedUnknown) {
                        warnedUnknown = false;
                        health.remove("capture-state-unknown");
                    }
                    if (captured != shadowCaptured) Main.log("capture: " + captureWindow + " (" + captureMode + ")");
                    shadowCaptured = captured;
                    checkShortcutConflicts(dump);
                }
                if (!focusedEquals(focused, lastFocused)) {
                    Main.debug("focus: " + focused);
                    lastFocused = focused == null ? "" : focused;
                }
                if (now - lastConfigCheck > 2000) {
                    lastConfigCheck = now;
                    config.reloadIfChanged();
                }
                if (now - lastVersionCheck > 600_000) {
                    lastVersionCheck = now;
                    readVersions();
                }
                pollTick = SystemClock.uptimeMillis();
                health.remove("capture-check-failing");
                if (touchpadPath == null && now - lastFind > 10_000) {
                    lastFind = now; // the trackpad may come back (e.g. re-enumerated after resume)
                    findDevices();
                }
            } catch (Exception e) {
                Main.logLimited("poll", "poll: " + e);
                if (SystemClock.uptimeMillis() - pollTick > 3000) health.add("capture-check-failing");
            }
            SystemClock.sleep(100);
        }
    }

    private static boolean focusedEquals(String a, String b) {
        return (a == null ? "" : a).equals(b == null ? "" : b);
    }

    /** TRUE/FALSE when known, null when no known dumpsys format could be read. */
    private Boolean shadowHasCapture(String dump, boolean shadowFocused) {
        java.util.regex.Matcher m = P_CAPTURE_WINDOW.matcher(dump);
        java.util.regex.Matcher req = P_MODE_REQUESTED.matcher(dump);
        captureMode = req.find() ? req.group(1) : "?";
        if (m.find()) {
            String win = m.group(1).trim();
            captureWindow = win;
            for (String pkg : config.packages) if (!pkg.isEmpty() && win.contains(" " + pkg + "/")) return true;
            return false;
        }
        // Fallbacks: a requested-capture flag or the trackpad mapper's own capture state, combined
        // with Shadow being the focused app (capture always belongs to the focused window).
        Boolean flag = null;
        if (!captureMode.equals("?")) flag = isCapturedWord(captureMode);
        java.util.regex.Matcher mm = P_MAPPER_MODE.matcher(dump);
        if (flag == null && mm.find()) flag = isCapturedWord(mm.group(1));
        if (flag == null) return null;
        captureWindow = (flag ? "captured" : "None") + " (fallback format, focused Shadow=" + shadowFocused + ")";
        return flag && shadowFocused;
    }

    private static boolean isCapturedWord(String w) {
        w = w.toLowerCase();
        return !(w.equals("uncaptured") || w.equals("false") || w.equals("none") || w.equals("disabled"));
    }

    /**
     * Android may one day reserve shortcuts the gestures send. If Ctrl+Alt+Tab or Alt+Left/Right
     * show up as system shortcuts, the matching gestures are switched off (no surprise actions).
     */
    private void checkShortcutConflicts(String dump) {
        boolean switcher = true, backForward = true;
        java.util.regex.Matcher m = P_SHORTCUT.matcher(dump);
        while (m.find()) {
            String key = m.group(1);
            int mods = Integer.parseInt(m.group(2));
            boolean ctrl = (mods & android.view.KeyEvent.META_CTRL_ON) != 0, alt = (mods & android.view.KeyEvent.META_ALT_ON) != 0;
            boolean meta = (mods & android.view.KeyEvent.META_META_ON) != 0, shift = (mods & android.view.KeyEvent.META_SHIFT_ON) != 0;
            if (key.equals("TAB") && ctrl && alt && !meta && !shift) switcher = false;
            if ((key.equals("DPAD_LEFT") || key.equals("DPAD_RIGHT")) && alt && !ctrl && !meta && !shift) backForward = false;
        }
        if (switcher != engine.switcherAllowed || backForward != engine.backForwardAllowed) {
            Main.log("shortcut check: Ctrl+Alt+Tab gestures " + (switcher ? "on" : "OFF (reserved by Android)")
                    + ", Alt+Left/Right gestures " + (backForward ? "on" : "OFF (reserved by Android)"));
        }
        engine.switcherAllowed = switcher;
        engine.backForwardAllowed = backForward;
        // Informational: the affected gestures are already switched off, nothing for the user to fix.
        shortcutNote = (switcher ? "" : " Ctrl+Alt+Tab-gestures-off") + (backForward ? "" : " Alt+Left/Right-gestures-off");
    }

    /** Remembers fail-safe stand-downs; two within 10 minutes pause the helper for an hour. */
    private void recordStandDown() {
        try {
            long now = System.currentTimeMillis();
            String prev = STANDDOWN_FILE.exists() ? new String(java.nio.file.Files.readAllBytes(STANDDOWN_FILE.toPath())).trim() : "";
            String keep = prev.isEmpty() ? "" : prev.split("\\s+")[prev.split("\\s+").length - 1];
            java.nio.file.Files.write(STANDDOWN_FILE.toPath(), ((keep.isEmpty() ? "" : keep + " ") + now).getBytes());
        } catch (Exception e) {
            Main.log("recordStandDown: " + e);
        }
    }

    private void checkStandDownHistory() {
        try {
            if (!STANDDOWN_FILE.exists()) return;
            String[] t = new String(java.nio.file.Files.readAllBytes(STANDDOWN_FILE.toPath())).trim().split("\\s+");
            long now = System.currentTimeMillis();
            if (t.length >= 2) {
                long a = Long.parseLong(t[0]), b = Long.parseLong(t[1]);
                if (b - a < 600_000 && now - b < 3_600_000) {
                    standDown = true;
                    standDownUntil = b + 3_600_000;
                    health.add("stood-down:injection-failing-repeatedly(paused-1h)");
                    Main.log("FAIL-SAFE: injection failed repeatedly - not taking over the trackpad until " + new java.util.Date(standDownUntil));
                    return;
                }
            }
            if (now - Long.parseLong(t[t.length - 1]) > 3_600_000) STANDDOWN_FILE.delete();
        } catch (Exception e) {
            Main.log("checkStandDownHistory: " + e);
        }
    }

    private static String readVerified() {
        try {
            java.io.File f = new java.io.File("/data/local/tmp/shadowtrackpad/verified");
            return f.exists() ? new String(java.nio.file.Files.readAllBytes(f.toPath())).trim() : "none";
        } catch (Exception e) {
            return "none";
        }
    }

    private void readVersions() {
        try {
            String d = exec("dumpsys", "package", "com.blade.shadowcloudgaming");
            java.util.regex.Matcher n = java.util.regex.Pattern.compile("versionName=(\\S+)").matcher(d);
            java.util.regex.Matcher c = java.util.regex.Pattern.compile("versionCode=(\\d+)").matcher(d);
            shadowVersion = (n.find() ? n.group(1) : "?") + "/" + (c.find() ? c.group(1) : "?");
        } catch (Exception e) {
            shadowVersion = "? (" + e + ")";
        }
        // Written by "shadow-trackpad doctor" after it re-verified the helper against both versions.
        try {
            java.io.File f = new java.io.File("/data/local/tmp/shadowtrackpad/verified");
            verified = f.exists() ? new String(java.nio.file.Files.readAllBytes(f.toPath())).trim() : "none";
        } catch (Exception e) {
            verified = "none";
        }
    }

    private boolean isTarget(String pkg) {
        if (pkg == null) return false;
        for (String p : config.packages) if (p.equals(pkg)) return true;
        return false;
    }

    /** Package of the focused task, or null if it cannot be determined. */
    private static String focusedPackage(Object atm, Method focusedTask) {
        if (atm == null) return null;
        try {
            Object info = focusedTask.invoke(atm);
            if (info == null) return "";
            Object top = info.getClass().getField("topActivity").get(info);
            return top == null ? "" : ((android.content.ComponentName) top).getPackageName();
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    /**
     * Finds the trackpad by what it is (multitouch X/Y + a click button + INPUT_PROP_POINTER, not
     * a direct-touch screen) rather than by node number or name, and reads its resolution.
     */
    private void findDevices() {
        String foundPad = null, foundKbd = null, nameOfPad = null;
        float rx = 0, ry = 0, maxY = 0;
        try {
            String out = exec("getevent", "-p");
            String[] blocks = out.split("(?m)^add device ");
            for (String b : blocks) {
                java.util.regex.Matcher pm = java.util.regex.Pattern.compile("^\\d+: (/dev/input/\\S+)").matcher(b);
                if (!pm.find()) continue;
                String path = pm.group(1);
                java.util.regex.Matcher nm = java.util.regex.Pattern.compile("name: *\"([^\"]*)\"").matcher(b);
                String name = nm.find() ? nm.group(1) : "";
                boolean mtX = b.contains(" 0035  :"), mtY = b.contains(" 0036  :");
                boolean btnLeft = java.util.regex.Pattern.compile("KEY \\(0001\\):[^\\n]*\\b0110\\b").matcher(b).find();
                boolean pointerProp = b.contains("INPUT_PROP_POINTER"), direct = b.contains("INPUT_PROP_DIRECT");
                boolean nameSaysPad = name.toLowerCase().matches(".*(touchpad|trackpad).*");
                if (foundPad == null && mtX && mtY && btnLeft && !direct && (pointerProp || nameSaysPad)) {
                    foundPad = path;
                    nameOfPad = name;
                    rx = resolution(b, "0035");
                    ry = resolution(b, "0036");
                    java.util.regex.Matcher mx = java.util.regex.Pattern.compile(" 0036  :[^\\n]*max (\\d+)").matcher(b);
                    if (mx.find()) maxY = Float.parseFloat(mx.group(1));
                }
                if (foundKbd == null && name.toLowerCase().contains("keyboard")) foundKbd = path;
            }
        } catch (Exception e) {
            Main.log("findDevices: " + e);
        }
        touchpadPath = foundPad;
        keyboardPath = foundKbd;
        resX = rx > 0 ? rx : 31f;
        resY = ry > 0 ? ry : resX;
        engine.padHeightMm = maxY > 0 ? maxY / resY : 0;
        if (foundPad == null) health.add("no-trackpad-found"); else health.remove("no-trackpad-found");
        Main.log("touchpad=" + touchpadPath + " (" + nameOfPad + ", " + resX + "x" + resY + " units/mm) keyboard=" + keyboardPath);
    }

    private static float resolution(String block, String code) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(" " + code + "  :[^\\n]*resolution (\\d+)").matcher(block);
        return m.find() ? Float.parseFloat(m.group(1)) : 0;
    }

    /** Runs a system command; it is killed after 5 s so a hung command can never stall the helper. */
    static String exec(String... cmd) throws Exception {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        Thread killer = new Thread(() -> {
            try {
                if (!p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) p.destroyForcibly();
            } catch (InterruptedException ignored) {
            }
        }, "exec-timeout");
        killer.setDaemon(true);
        killer.start();
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
            char[] c = new char[8192];
            for (int n; (n = r.read(c)) > 0; ) sb.append(c, 0, n);
        }
        p.waitFor();
        return sb.toString();
    }
}
