package local.shadowtrackpad;

import android.os.SystemClock;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.MotionEvent;

import java.lang.reflect.Method;

/**
 * Builds and injects the events Shadow receives. Injection uses the hidden
 * InputManagerGlobal.injectInputEvent, available to the shell user (INJECT_EVENTS).
 * Captured-pointer events (MOUSE_RELATIVE / TOUCHPAD sources) are routed by Android only to the
 * window holding pointer capture, so nothing leaks to other apps.
 */
final class Injector {
    private final Object inputManager;
    private final Method inject;
    private final Method setActionButton;

    private int buttons; // currently held mouse buttons (MotionEvent.BUTTON_*)
    private long mouseDownTime;
    private boolean mouseGesture; // from ACTION_DOWN until ACTION_UP has been sent

    /*
     * Android batches consecutive ACTION_MOVEs that arrive within one display frame, and Shadow
     * only reads the newest sample of a batch, silently dropping the rest of the movement
     * (measured: ~4% lost at idle, more under load). Moves whose pointer id differs are never
     * merged (and Shadow ignores the id), so every move alternates id 0/1 and is delivered
     * immediately, with nothing held back.
     */
    private int moveId;

    /*
     * Android holds every ACTION_MOVE until the app's next display frame (measured: ~9 ms from
     * injection to the app). A motion event that cannot be merged makes Android hand the pending
     * move over immediately (measured: ~0.8 ms). BUTTON_RELEASE of a button that is not held is
     * such an event, and Shadow ignores it completely (its button logic only acts on a change of
     * buttonState, and it logs nothing), so each move is followed by one.
     */
    boolean lowLatency = true;
    int scrollHoldMs = 130;

    private void flush(MotionEvent e) {
        if (setActionButton == null) {
            e.recycle();
            return;
        }
        try {
            setActionButton.invoke(e, MotionEvent.BUTTON_FORWARD);
        } catch (ReflectiveOperationException ex) {
            e.recycle();
            return;
        }
        send(e);
    }

    /**
     * Uptime (ms) of the hardware frame being processed, so injected events carry the time the
     * finger actually moved (as native events do); 0 = use the current time (e.g. flick glide).
     */
    long frameUptime;

    private long now() {
        return frameUptime != 0 ? frameUptime : SystemClock.uptimeMillis();
    }

    // Synthetic two-finger touchpad drag (drives Shadow's own trackpad scroll handling).
    private boolean tpActive;
    private long tpDownTime;
    private float tpX, tpY;
    private boolean tpLeftSide;

    private final String injectPath;
    private long injected, rejected;
    private int consecutiveFailures;

    /**
     * Hidden injection API, with fallbacks for other Android versions: InputManagerGlobal
     * (Android 14+), InputManager.getInstance() (older), or the raw "input" binder service.
     */
    Injector() throws ReflectiveOperationException {
        Object im = null;
        Method inj = null;
        String path = null;
        try {
            Class<?> g = Class.forName("android.hardware.input.InputManagerGlobal");
            im = g.getMethod("getInstance").invoke(null);
            inj = g.getMethod("injectInputEvent", InputEvent.class, int.class);
            path = "InputManagerGlobal";
        } catch (ReflectiveOperationException | RuntimeException e) {
            Main.log("injection: InputManagerGlobal unavailable (" + e + ")");
        }
        if (inj == null) {
            try {
                Class<?> c = Class.forName("android.hardware.input.InputManager");
                im = c.getMethod("getInstance").invoke(null);
                inj = c.getMethod("injectInputEvent", InputEvent.class, int.class);
                path = "InputManager";
            } catch (ReflectiveOperationException | RuntimeException e) {
                Main.log("injection: InputManager.getInstance unavailable (" + e + ")");
            }
        }
        if (inj == null) {
            Object binder = Class.forName("android.os.ServiceManager").getMethod("getService", String.class).invoke(null, "input");
            Class<?> stub = Class.forName("android.hardware.input.IInputManager$Stub");
            im = stub.getMethod("asInterface", android.os.IBinder.class).invoke(null, binder);
            inj = Class.forName("android.hardware.input.IInputManager").getMethod("injectInputEvent", InputEvent.class, int.class);
            path = "IInputManager";
        }
        inputManager = im;
        inject = inj;
        injectPath = path;
        Method sab = null;
        try {
            sab = MotionEvent.class.getMethod("setActionButton", int.class);
        } catch (NoSuchMethodException e) {
            Main.log("injection: setActionButton unavailable - low-latency flush off, simpler button events");
        }
        setActionButton = sab;
    }

    private void send(InputEvent e) {
        boolean ok;
        try {
            Object r = inject.invoke(inputManager, e, 0 /* async */);
            ok = !(r instanceof Boolean) || (Boolean) r;
        } catch (ReflectiveOperationException | RuntimeException ex) {
            ok = false;
            if (rejected % 100 == 0) Main.log("inject failed: " + (ex.getCause() != null ? ex.getCause() : ex));
        }
        synchronized (this) {
            injected++;
            if (ok) {
                consecutiveFailures = 0;
            } else {
                rejected++;
                consecutiveFailures++;
            }
        }
        if (e instanceof MotionEvent) ((MotionEvent) e).recycle();
    }

    synchronized int consecutiveFailures() {
        return consecutiveFailures;
    }

    synchronized String describe() {
        return injectPath + ", sent " + injected + ", rejected " + rejected
                + (setActionButton == null ? ", no setActionButton (flush off)" : "");
    }

    // Reused buffers (MotionEvent.obtain copies them), so steady-state input allocates little.
    private final MotionEvent.PointerProperties[] pp1 = props(1), pp2 = props(2);
    private final MotionEvent.PointerCoords[] pc1 = coords(1), pc2 = coords(2);

    private static MotionEvent.PointerProperties[] props(int n) {
        MotionEvent.PointerProperties[] p = new MotionEvent.PointerProperties[n];
        for (int i = 0; i < n; i++) p[i] = new MotionEvent.PointerProperties();
        return p;
    }

    private static MotionEvent.PointerCoords[] coords(int n) {
        MotionEvent.PointerCoords[] c = new MotionEvent.PointerCoords[n];
        for (int i = 0; i < n; i++) c[i] = new MotionEvent.PointerCoords();
        return c;
    }

    /** Relative-mouse event; pointerId alternates for motion so Android never merges two moves. */
    private MotionEvent mouse(int action, int pointerId, float dx, float dy, float vscroll) {
        return mouse(action, pointerId, dx, dy, vscroll, 0);
    }

    private MotionEvent mouse(int action, int pointerId, float dx, float dy, float vscroll, float hscroll) {
        MotionEvent.PointerProperties p = pp1[0];
        p.clear();
        p.id = pointerId;
        p.toolType = MotionEvent.TOOL_TYPE_MOUSE;
        MotionEvent.PointerCoords c = pc1[0];
        c.clear();
        c.x = dx;
        c.y = dy;
        c.pressure = buttons != 0 ? 1f : 0f;
        c.setAxisValue(MotionEvent.AXIS_RELATIVE_X, dx);
        c.setAxisValue(MotionEvent.AXIS_RELATIVE_Y, dy);
        if (vscroll != 0) c.setAxisValue(MotionEvent.AXIS_VSCROLL, vscroll);
        if (hscroll != 0) c.setAxisValue(MotionEvent.AXIS_HSCROLL, hscroll);
        long now = now();
        // Like Android: every event from the press to the release (incl. ACTION_UP) carries the press time.
        return MotionEvent.obtain(mouseGesture ? Math.min(mouseDownTime, now) : now, now, action, 1, pp1, pc1, 0, buttons,
                1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE_RELATIVE, 0);
    }

    private MotionEvent mouse(int action) {
        return mouse(action, 0, 0, 0, 0);
    }

    /** Relative cursor motion in whole counts (Shadow truncates fractions). */
    void move(int dx, int dy) {
        if (dx == 0 && dy == 0) return;
        moveId ^= 1;
        send(mouse(MotionEvent.ACTION_MOVE, moveId, dx, dy, 0));
        if (lowLatency) flush(mouse(MotionEvent.ACTION_BUTTON_RELEASE));
    }

    int buttons() {
        return buttons;
    }

    /** Press or release one mouse button, using the same event sequence Android sends. */
    void button(int button, boolean press) {
        if (press == ((buttons & button) != 0)) return;
        try {
            if (press) {
                settlePendingKey(0); // a pinch's Ctrl must not turn this into Ctrl+click
                boolean first = buttons == 0;
                buttons |= button;
                if (first) {
                    mouseDownTime = now();
                    mouseGesture = true;
                    send(mouse(MotionEvent.ACTION_DOWN));
                }
                if (setActionButton != null) {
                    MotionEvent e = mouse(MotionEvent.ACTION_BUTTON_PRESS);
                    setActionButton.invoke(e, button);
                    send(e);
                }
            } else {
                buttons &= ~button;
                if (setActionButton != null) {
                    MotionEvent e = mouse(MotionEvent.ACTION_BUTTON_RELEASE);
                    setActionButton.invoke(e, button);
                    send(e);
                }
                if (buttons == 0) {
                    send(mouse(MotionEvent.ACTION_UP));
                    mouseGesture = false;
                }
            }
        } catch (ReflectiveOperationException ex) {
            Main.log("button failed: " + ex);
        }
    }

    void click(int button) {
        button(button, true);
        button(button, false);
    }

    void releaseAllButtons() {
        for (int b : new int[]{MotionEvent.BUTTON_PRIMARY, MotionEvent.BUTTON_SECONDARY, MotionEvent.BUTTON_TERTIARY})
            button(b, false);
    }

    /** Whole wheel notches (Ctrl+wheel zoom, and scroll_mode=notch). Delivered without waiting for a frame. */
    void wheelNotches(int v) {
        wheelNotches(0, v);
    }

    void wheelNotches(int h, int v) {
        if (v != 0 || h != 0) send(mouse(MotionEvent.ACTION_SCROLL, 0, 0, 0, v, h));
    }

    // ---- synthetic two-finger touchpad drag -------------------------------------------------

    private long tpEndDue; // uptime when a finished drag's fingers are lifted, 0 = none pending

    private void tpSend(int action, int pointers) {
        send(tpEvent(action, pointers));
    }

    private MotionEvent tpEvent(int action, int pointers) {
        MotionEvent.PointerProperties[] pp = pointers == 1 ? pp1 : pp2;
        MotionEvent.PointerCoords[] pc = pointers == 1 ? pc1 : pc2;
        for (int i = 0; i < pointers; i++) {
            pp[i].clear();
            pp[i].id = i;
            pp[i].toolType = MotionEvent.TOOL_TYPE_FINGER;
            pc[i].clear();
            pc[i].x = tpX + (i == 0 ? -250 : 250);
            pc[i].y = tpY;
            pc[i].pressure = 1f;
        }
        long now = now();
        // buttonState 0: Shadow's touchpad path has its own button tracker (a separate instance),
        // so reporting the mouse buttons here would make it send stray presses/releases.
        return MotionEvent.obtain(Math.min(tpDownTime, now), now, action, pointers, pp, pc, 0, 0,
                1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHPAD, 0);
    }

    boolean scrollActive() {
        return tpActive && tpEndDue == 0;
    }

    long scrollStartUptime() {
        return tpDownTime;
    }

    /**
     * Starts the synthetic drag. Shadow computes wheel = finger travel between events, so the
     * travel of the synthetic fingers is exactly the wheel delta sent to Windows (120 = 1 notch).
     */
    void scrollBegin() {
        if (tpActive && keyAfterLift != 0) {
            // A scroll starting right after a pinch: let go of Ctrl now but keep the synthetic
            // fingers down (lifting them this early could trip Shadow's two-finger keyboard trap).
            key(keyAfterLift, false);
            keyAfterLift = 0;
        }
        if (tpActive) {
            tpEndDue = 0; // a drag still waiting to be lifted simply continues
            return;
        }
        tpActive = true;
        // Alternate start positions far apart so Shadow's GestureDetector never sees a double tap.
        tpLeftSide = !tpLeftSide;
        tpX = tpLeftSide ? 20000 : 60000;
        tpY = 40000;
        tpDownTime = now();
        tpSend(MotionEvent.ACTION_DOWN, 1);
        tpSend(MotionEvent.ACTION_POINTER_DOWN | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT), 2);
    }

    /** Moves the synthetic fingers by (dx, dy) whole units; positive dy scrolls Windows up. */
    void scrollBy(int wheelX, int wheelY) {
        if (!tpActive || (wheelX == 0 && wheelY == 0)) return;
        // Shadow: wheelX = lastX - x, wheelY = -(lastY - y) = y - lastY.
        tpX -= wheelX;
        tpY += wheelY;
        tpSend(MotionEvent.ACTION_MOVE, 2);
        if (lowLatency) flush(tpEvent(MotionEvent.ACTION_BUTTON_RELEASE, 2));
    }

    /**
     * Lifts the synthetic fingers. Shadow opens the on-screen keyboard if a two-finger contact
     * lasts under 100 ms, so a very short drag is lifted a little later by tick() instead of
     * blocking (nothing else is delayed meanwhile).
     */
    void scrollEnd() {
        if (!tpActive || tpEndDue != 0) return;
        long due = tpDownTime + scrollHoldMs;
        if (SystemClock.uptimeMillis() >= due) liftScroll();
        else tpEndDue = due;
    }

    private int keyAfterLift; // key released once the synthetic fingers are lifted (0 = none)

    private void liftScroll() {
        tpSend(MotionEvent.ACTION_POINTER_UP | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT), 2);
        tpSend(MotionEvent.ACTION_UP, 1);
        tpActive = false;
        tpEndDue = 0;
        if (keyAfterLift != 0) {
            key(keyAfterLift, false);
            keyAfterLift = 0;
        }
    }

    /**
     * A new pinch right after the previous one: if that pinch's Ctrl release is still pending,
     * keep Ctrl down (and its drag going) instead of pressing it again. Returns true if so.
     */
    boolean keepKeyDown(int keyCode) {
        if (keyAfterLift != keyCode) return false;
        keyAfterLift = 0;
        return true;
    }

    /**
     * A pinch's Ctrl is normally released when its synthetic fingers lift (up to scroll_hold_ms
     * later). Anything else sent meanwhile - a click, a key - releases it first.
     */
    private void settlePendingKey(int except) {
        if (keyAfterLift != 0 && keyAfterLift != except) {
            int k = keyAfterLift;
            keyAfterLift = 0;
            key(k, false);
        }
    }

    /** Releases a key right after the current synthetic drag ends (or now if there is none). */
    void releaseKeyAfterScroll(int keyCode) {
        if (tpActive) keyAfterLift = keyCode;
        else key(keyCode, false);
    }

    /** Milliseconds until a pending lift, or -1. */
    long scrollLiftDelayMs() {
        return tpEndDue == 0 ? -1 : Math.max(1, tpEndDue - SystemClock.uptimeMillis());
    }

    void tick() {
        if (tpEndDue != 0 && SystemClock.uptimeMillis() >= tpEndDue) liftScroll();
    }

    // ---- keyboard ---------------------------------------------------------------------------

    private int metaState;

    private final java.util.Set<Integer> keysDown = new java.util.HashSet<>();
    private final java.util.Map<Integer, Long> keyDownAt = new java.util.HashMap<>();

    void key(int keyCode, boolean down) {
        if (down && keyCode == keyAfterLift) {
            // Pressed again while its delayed release (after a pinch) is pending: it simply stays
            // down, now owned by the new press (e.g. Ctrl of a Ctrl+Alt+Tab right after a pinch).
            keyAfterLift = 0;
            return;
        }
        // Never press a key that is already down or release one that isn't (Windows would see a
        // stuck or unmatched key otherwise).
        if (down ? keysDown.contains(keyCode) : !keysDown.contains(keyCode)) return;
        if (down) settlePendingKey(keyCode);
        if (down) keysDown.add(keyCode); else keysDown.remove(keyCode);
        // Like a real keyboard: a modifier's own UP event no longer carries that modifier.
        int flag = metaFlag(keyCode);
        if (down) metaState |= flag; else metaState &= ~flag;
        long now = SystemClock.uptimeMillis();
        Long pressed = down ? null : keyDownAt.remove(keyCode);
        if (down) keyDownAt.put(keyCode, now);
        send(new KeyEvent(pressed != null ? pressed : now, now, down ? KeyEvent.ACTION_DOWN : KeyEvent.ACTION_UP, keyCode, 0, metaState,
                KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD));
    }

    void tap(int keyCode) {
        key(keyCode, true);
        key(keyCode, false);
    }

    /** Presses modifiers + key, then releases in reverse order. */
    void chord(int... keys) {
        for (int k : keys) key(k, true);
        for (int i = keys.length - 1; i >= 0; i--) key(keys[i], false);
    }

    void releaseAllKeys() {
        for (Integer k : new java.util.ArrayList<>(keysDown)) key(k, false);
    }

    private static int metaFlag(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_CTRL_LEFT: return KeyEvent.META_CTRL_ON | KeyEvent.META_CTRL_LEFT_ON;
            case KeyEvent.KEYCODE_ALT_LEFT: return KeyEvent.META_ALT_ON | KeyEvent.META_ALT_LEFT_ON;
            case KeyEvent.KEYCODE_SHIFT_LEFT: return KeyEvent.META_SHIFT_ON | KeyEvent.META_SHIFT_LEFT_ON;
            case KeyEvent.KEYCODE_META_LEFT: return KeyEvent.META_META_ON | KeyEvent.META_META_LEFT_ON;
            default: return 0;
        }
    }

    /** Leaves Shadow in a clean state: no held buttons, keys or open scroll drag. */
    void resetAll() {
        if (tpActive) {
            // Even now the synthetic fingers must stay down long enough, or Shadow opens its
            // on-screen keyboard (this blocks for at most scrollHoldMs, only on rare reset paths).
            long wait = tpDownTime + scrollHoldMs - SystemClock.uptimeMillis();
            if (wait > 0) SystemClock.sleep(wait);
            liftScroll();
        }
        releaseAllButtons();
        releaseAllKeys();
    }
}
