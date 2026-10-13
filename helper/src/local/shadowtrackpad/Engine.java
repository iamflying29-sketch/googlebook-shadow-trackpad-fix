package local.shadowtrackpad;

import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.MotionEvent;

/**
 * Turns raw multitouch frames from the trackpad into events for Shadow.
 *
 * Coordinates are in millimetres (pad resolution: 31 units/mm). Wheel units are Windows wheel
 * deltas (120 = one notch); positive Y scrolls up, positive X scrolls right.
 */
final class Engine {
    static final int MAX_SLOTS = 10;
    /** Ids from here on mark fingers that were already down when the helper started seeing them. */
    static final int ADOPTED_ID = 1_000_000;

    /** One frame of the pad: per-slot finger state, as reported at SYN_REPORT. */
    static final class Frame {
        final int[] id = new int[MAX_SLOTS];
        final float[] x = new float[MAX_SLOTS], y = new float[MAX_SLOTS];
        final boolean[] palm = new boolean[MAX_SLOTS];
        boolean button;
        double time; // seconds

        Frame() {
            java.util.Arrays.fill(id, -1);
        }

        int count() {
            int n = 0;
            for (int i = 0; i < MAX_SLOTS; i++) if (id[i] >= 0 && !palm[i]) n++;
            return n;
        }

        void copyFrom(Frame f) {
            System.arraycopy(f.id, 0, id, 0, MAX_SLOTS);
            System.arraycopy(f.x, 0, x, 0, MAX_SLOTS);
            System.arraycopy(f.y, 0, y, 0, MAX_SLOTS);
            System.arraycopy(f.palm, 0, palm, 0, MAX_SLOTS);
            button = f.button;
            time = f.time;
        }
    }

    enum Mode { IDLE, POINTER, POINTER_REST, TWO_PENDING, SCROLL, PINCH, SWIPE_PENDING, SWIPE, BUTTON, LINGER, TAP_DRAG }

    private final Config config;
    private final Injector out;

    private final Frame prev = new Frame();
    private Mode mode = Mode.IDLE;

    // Contact bookkeeping (from first finger down until all fingers are up).
    private double contactStart;
    private int maxFingers;
    private boolean contactMoved, contactClicked;
    private final float[] startX = new float[MAX_SLOTS], startY = new float[MAX_SLOTS];
    private final int[] startId = new int[MAX_SLOTS];
    private final int[] landId = new int[MAX_SLOTS];       // finger in each slot and when it landed
    private final double[] landTime = new double[MAX_SLOTS];
    private boolean wasPointing;  // the cursor was moving when more fingers landed
    private float resumeSlop;     // mm the remaining finger must move before the cursor follows again

    // Pointer motion remainder (Shadow only takes whole counts).
    private float remX, remY;
    private int buttonHeld;

    // Two-finger classification / scroll.
    private float twoStartCx, twoStartCy, twoStartDist;
    private int axisLock; // 0 free, 1 vertical, 2 horizontal
    private float accX, accY;
    private final double[] velT = new double[16];
    private final float[] velX = new float[16], velY = new float[16];
    private int velN;
    private float momVx, momVy;
    private long momLast;
    private boolean momentumActive;

    // Pinch: Ctrl + fine-grained wheel, which is how Windows precision touchpads report pinch-zoom.
    private float pinchRefDist;
    private boolean ctrlHeld;
    private float pinchAcc;
    private static final float PINCH_UNITS_PER_LN = 1060f; // ~120 wheel units (1 notch) per 12% size change

    // Three/four-finger swipes.
    private int swipeFingers;
    private float swipeStartCx, swipeStartCy, swipeRefX;
    private boolean switcherStepping; // Ctrl+Alt+Tab switcher opened by a sideways swipe
    private long switcherOpenedAt;    // uptime when a swipe-up opened the switcher, 0 = not open

    volatile long lastActivityUptime;
    volatile boolean switcherAllowed = true, backForwardAllowed = true; // off if Android reserves them
    // Pad height in mm (0 = unknown). Like Android's own trackpad driver ("Bottom Zone Size" 10 mm),
    // a finger resting in the bottom zone - typically a thumb - doesn't count for click type.
    volatile float padHeightMm;
    private static final float BOTTOM_ZONE_MM = 10f;

    // Tap handling.
    private long tapReleaseAt; // uptime at which a held tap-drag button is released, 0 = none
    volatile long lastKeyUptime;

    Engine(Config config, Injector out) {
        this.config = config;
        this.out = out;
        java.util.Arrays.fill(startId, -1);
        java.util.Arrays.fill(landId, -1);
    }

    Mode mode() {
        return mode;
    }

    /** Ends everything cleanly (used when releasing the trackpad). */
    void reset() {
        momentumActive = false;
        switcherStepping = false; // capture lost: don't confirm a window switch
        finishGesture(false);
        endScroll(false);
        if (ctrlHeld) { out.key(KeyEvent.KEYCODE_CTRL_LEFT, false); ctrlHeld = false; }
        switcherOpenedAt = 0;
        tapReleaseAt = 0;
        out.resetAll();
        buttonHeld = 0;
        mode = Mode.IDLE;
        prev.copyFrom(new Frame());
    }

    /** Milliseconds until the engine needs a timer tick, or -1. */
    long nextTimerMs() {
        if (momentumActive) return 8;
        long lift = out.scrollLiftDelayMs();
        if (lift >= 0) return lift;
        if (tapReleaseAt != 0) return Math.max(1, tapReleaseAt - SystemClock.uptimeMillis());
        return -1;
    }

    void tick() {
        long now = SystemClock.uptimeMillis();
        out.tick();
        if (momentumActive) {
            lastActivityUptime = now;
            stepMomentum(now);
        }
        if (tapReleaseAt != 0 && now >= tapReleaseAt && mode != Mode.TAP_DRAG) {
            tapReleaseAt = 0;
            out.button(MotionEvent.BUTTON_PRIMARY, false);
        }
    }

    // ---- frame processing -------------------------------------------------------------------

    void onFrame(Frame f) {
        int n = f.count();
        int pn = prev.count();
        if (n > 0 || f.button) lastActivityUptime = SystemClock.uptimeMillis();

        if (n > 0 && pn == 0) contactBegin(f);
        if (n > 0) {
            for (int i = 0; i < MAX_SLOTS; i++) {
                if (f.id[i] >= 0 && f.id[i] != landId[i]) { landId[i] = f.id[i]; landTime[i] = f.time; }
                // A finger that was already down when the helper started seeing it: its contact
                // can't be judged as a tap.
                if (f.id[i] >= ADOPTED_ID && !f.palm[i]) contactClicked = true;
            }
            maxFingers = Math.max(maxFingers, n);
            if (!contactMoved && maxDisplacement(f) > 2f) contactMoved = true;
        }
        if (n > pn && stopMomentum()) {
            // Touching the pad - or adding a finger - stops a flick (like a Mac/Windows precision
            // touchpad); that touch is not also a click.
            contactClicked = true;
        }

        // Physical click (the pad is a clickpad).
        if (f.button != prev.button) {
            if (f.button) buttonDown(clickFingers(f, n)); else buttonUp();
        }

        if (mode == Mode.BUTTON) {
            float[] d = fastestDelta(f);
            if (d != null) pointer(d[0], d[1], d[2]);
        } else if (n == 0) {
            if (pn > 0) contactEnd(f);
        } else {
            handleFingers(f, n, pn);
        }
        prev.copyFrom(f);
    }

    private void contactBegin(Frame f) {
        contactStart = f.time;
        maxFingers = 0;
        contactMoved = false;
        contactClicked = false;
        wasPointing = false;
        resumeSlop = 0;
        for (int i = 0; i < MAX_SLOTS; i++) {
            startId[i] = f.id[i];
            startX[i] = f.x[i];
            startY[i] = f.y[i];
        }
        if (buttonHeld != 0) {
            // The pad is still physically pressed (fingers were lifted and put back while
            // clicking): stay in click mode - no taps or gestures until the click is released.
            contactClicked = true;
            enterMode(Mode.BUTTON);
        } else if (tapReleaseAt != 0) {
            // Finger came back while a tap's button is still held: tap-and-drag.
            enterMode(Mode.TAP_DRAG);
            tapReleaseAt = 0;
        } else {
            enterMode(Mode.IDLE);
        }
    }

    /**
     * Every mode change goes through here: a running scroll, pinch or swipe is always finished
     * first, so nothing (Ctrl, an open synthetic drag, the window switcher) can be left behind.
     */
    private void enterMode(Mode m) {
        if (mode == Mode.SCROLL || mode == Mode.PINCH || mode == Mode.SWIPE || mode == Mode.SWIPE_PENDING) finishGesture(false);
        mode = m;
    }

    private void handleFingers(Frame f, int n, int pn) {
        recordNewStarts(f);
        if (mode == Mode.TAP_DRAG) {
            if (n >= 2 && !contactMoved) {
                // Not a drag after all: a two-finger scroll/pinch (or a swipe) right after a tap.
                // The tap's click is completed and the gesture is handled normally.
                out.button(MotionEvent.BUTTON_PRIMARY, false);
                mode = Mode.IDLE;
                startMulti(f, n);
                return;
            }
            if (n == 1) {
                float[] d = singleDelta(f);
                if (d != null) pointer(d[0], d[1], d[2]);
            }
            return;
        }
        if (n > pn && pn > 0 && n >= 2) {
            // More fingers landed: restart classification for the new finger count.
            if (pn == 1) wasPointing = mode == Mode.POINTER;
            finishGesture(false);
            startMulti(f, n);
            return;
        }
        if (mode == Mode.POINTER_REST) {
            if (n == 1) mode = Mode.POINTER;
            float[] d = n == 1 ? singleDelta(f) : fastestDelta(f);
            if (d != null && n <= 2) pointer(d[0], d[1], d[2]);
            return;
        }
        if (n < pn && pn >= 2) {
            if (mode == Mode.TWO_PENDING || mode == Mode.SWIPE_PENDING) {
                // Nothing was recognized yet (e.g. a thumb brushed the pad while the cursor was
                // moving): carry on with the fingers that are left instead of freezing the cursor.
                if (n == 1) {
                    if (mode == Mode.TWO_PENDING) restingThumbTap(f);
                    mode = Mode.POINTER;
                    // After a two-finger tap the last finger may roll a little while lifting: don't
                    // nudge the cursor then. A finger that was already moving the cursor goes on at once.
                    resumeSlop = wasPointing ? 0 : 0.6f;
                    if (wasPointing) {
                        float[] d = singleDelta(f);
                        if (d != null) pointer(d[0], d[1], d[2]);
                    }
                } else {
                    startMulti(f, n);
                }
                return;
            }
            // A recognized gesture lost fingers: finish it; ignore the rest of this contact.
            finishGesture(true);
            mode = Mode.LINGER;
            return;
        }
        switch (mode) {
            case IDLE:
            case POINTER:
                if (n == 1) {
                    mode = Mode.POINTER;
                    float[] d = singleDelta(f);
                    if (d != null) {
                        if (resumeSlop > 0) {
                            resumeSlop -= (float) Math.hypot(d[0], d[1]);
                            if (resumeSlop > 0) break;
                        }
                        pointer(d[0], d[1], d[2]);
                    }
                } else {
                    startMulti(f, n);
                }
                break;
            case TWO_PENDING: classifyTwo(f); break;
            case SCROLL: scrollFrame(f); break;
            case PINCH: pinchFrame(f); break;
            case SWIPE_PENDING:
            case SWIPE: swipeFrame(f); break;
            default: break; // LINGER: wait for all fingers to lift
        }
    }

    private void startMulti(Frame f, int n) {
        float[] c = centroid(f);
        if (n == 2) {
            mode = Mode.TWO_PENDING;
            twoStartCx = c[0];
            twoStartCy = c[1];
            twoStartDist = spread(f);
            for (int i = 0; i < MAX_SLOTS; i++) { startId[i] = f.id[i]; startX[i] = f.x[i]; startY[i] = f.y[i]; }
        } else if (n == 3 || n == 4) {
            mode = config.gestures ? Mode.SWIPE_PENDING : Mode.LINGER;
            swipeFingers = n;
            swipeStartCx = c[0];
            swipeStartCy = c[1];
        } else {
            mode = Mode.LINGER;
        }
    }

    // ---- pointer ----------------------------------------------------------------------------

    private void pointer(float dxMm, float dyMm, float dt) {
        if (dt <= 0) dt = 0.008f;
        float dist = (float) Math.hypot(dxMm, dyMm);
        if (dist < 0.02f) return; // sensor jitter of a resting finger
        float speed = dist / dt; // mm/s
        // Counts per mm: gentle at low speed for precision, faster for long fast swipes.
        float gain = 6.5f * (1f + Math.min(2.4f, Math.max(0f, (speed - 25f) / 100f))) * config.pointerGain();
        remX += dxMm * gain;
        remY += dyMm * gain;
        int ix = (int) remX, iy = (int) remY;
        remX -= ix;
        remY -= iy;
        out.move(ix, iy);
    }

    /** Stops flick momentum and closes the synthetic drag; returns true if one was running. */
    private boolean stopMomentum() {
        if (!momentumActive) return false;
        momentumActive = false;
        accX = accY = 0;
        out.scrollEnd();
        return true;
    }

    // ---- clicks and taps --------------------------------------------------------------------

    /** Fingers that count for the click type: a resting thumb in the bottom zone is ignored. */
    private int clickFingers(Frame f, int n) {
        float h = padHeightMm;
        if (n < 2 || h <= 0) return n;
        int outside = 0;
        for (int i = 0; i < MAX_SLOTS; i++)
            if (f.id[i] >= 0 && !f.palm[i] && f.y[i] < h - BOTTOM_ZONE_MM) outside++;
        return outside == 0 ? n : outside; // all in the zone: count them all
    }

    /**
     * Tapping with one finger while a thumb rests in the bottom zone (like Android's own trackpad
     * driver): the thumb must have been resting before the finger landed, so a two-finger tap
     * (both fingers land together) stays a right click.
     */
    private void restingThumbTap(Frame f) {
        float h = padHeightMm;
        if (h <= 0 || contactClicked || !config.tapToClick()
                || SystemClock.uptimeMillis() - lastKeyUptime <= config.typingTapBlockMs) return;
        int lifted = -1, rest = -1;
        for (int i = 0; i < MAX_SLOTS; i++) {
            if (prev.id[i] >= 0 && !prev.palm[i] && f.id[i] != prev.id[i]) lifted = i;
            if (f.id[i] >= 0 && !f.palm[i]) rest = i;
        }
        if (lifted < 0 || rest < 0 || landId[lifted] != prev.id[lifted] || landId[rest] != f.id[rest]
                || startId[lifted] != prev.id[lifted]) return;
        boolean thumbResting = f.y[rest] >= h - BOTTOM_ZONE_MM && landTime[lifted] - landTime[rest] > 0.15;
        boolean tapper = prev.y[lifted] < h - BOTTOM_ZONE_MM;
        boolean quick = f.time - landTime[lifted] < 0.22;
        boolean still = Math.hypot(prev.x[lifted] - startX[lifted], prev.y[lifted] - startY[lifted]) <= 2f;
        if (thumbResting && tapper && quick && still) {
            out.click(MotionEvent.BUTTON_PRIMARY);
            contactClicked = true; // lifting the thumb afterwards is not another tap
        }
    }

    private void buttonDown(int fingers) {
        contactClicked = true;
        switcherOpenedAt = 0;
        if (tapReleaseAt != 0) { tapReleaseAt = 0; out.button(MotionEvent.BUTTON_PRIMARY, false); }
        if (mode == Mode.TAP_DRAG) out.button(MotionEvent.BUTTON_PRIMARY, false);
        stopMomentum();
        finishGesture(false);
        if (buttonHeld != 0) out.button(buttonHeld, false); // never two physical clicks at once
        buttonHeld = fingers >= 3 ? MotionEvent.BUTTON_TERTIARY
                : fingers == 2 ? MotionEvent.BUTTON_SECONDARY : MotionEvent.BUTTON_PRIMARY;
        out.button(buttonHeld, true);
        enterMode(Mode.BUTTON);
    }

    private void buttonUp() {
        if (buttonHeld != 0) out.button(buttonHeld, false);
        buttonHeld = 0;
        // Fingers still on the pad keep working (e.g. click, then keep moving the cursor), but this
        // contact can no longer become a tap.
        contactClicked = true;
        enterMode(Mode.IDLE);
    }

    private void contactEnd(Frame f) {
        if (mode == Mode.TAP_DRAG) {
            out.button(MotionEvent.BUTTON_PRIMARY, false);
            // A quick second tap without movement is a double click.
            if (!contactMoved && f.time - contactStart < 0.22 && maxFingers == 1) out.click(MotionEvent.BUTTON_PRIMARY);
            enterMode(Mode.IDLE);
            return;
        }
        finishGesture(true);
        boolean tap = !contactMoved && !contactClicked && f.time - contactStart < 0.22
                && config.tapToClick() && SystemClock.uptimeMillis() - lastKeyUptime > config.typingTapBlockMs;
        if (tap) {
            if (maxFingers == 1) {
                if (config.tapDrag()) {
                    out.button(MotionEvent.BUTTON_PRIMARY, true);
                    tapReleaseAt = SystemClock.uptimeMillis() + 300;
                } else {
                    out.click(MotionEvent.BUTTON_PRIMARY);
                }
            } else if (maxFingers == 2) {
                out.click(MotionEvent.BUTTON_SECONDARY);
            } else if (maxFingers == 3) {
                out.click(MotionEvent.BUTTON_TERTIARY);
            }
        }
        enterMode(Mode.IDLE);
    }

    // ---- two fingers: scroll or pinch ---------------------------------------------------------

    private void classifyTwo(Frame f) {
        float[] c = centroid(f);
        float mx = c[0] - twoStartCx, my = c[1] - twoStartCy;
        float move = (float) Math.hypot(mx, my);
        float spreadChange = Math.abs(spread(f) - twoStartDist);
        if (config.gestures && spreadChange > 3f && spreadChange > 1.2f * move) {
            mode = Mode.PINCH;
            contactMoved = true;
            pinchRefDist = spread(f);
            pinchAcc = 0;
            if (!out.keepKeyDown(KeyEvent.KEYCODE_CTRL_LEFT)) out.key(KeyEvent.KEYCODE_CTRL_LEFT, true);
            ctrlHeld = true;
            Main.debug("pinch start");
            return;
        }
        if (move < 1.2f) return;
        // A resting thumb plus one moving finger is pointer motion, not scrolling.
        float[] v = perFingerVectors(f);
        if (v != null) {
            float a = (float) Math.hypot(v[0], v[1]), b = (float) Math.hypot(v[2], v[3]);
            if (Math.min(a, b) < 0.5f && Math.max(a, b) > 2.5f) {
                mode = Mode.POINTER_REST;
                contactMoved = true;
                return;
            }
            // Fingers moving apart/together (a pinch starting) must not become a scroll.
            if (config.gestures && Math.min(a, b) > 0.5f && v[0] * v[2] + v[1] * v[3] < 0) return;
        }
        mode = Mode.SCROLL;
        contactMoved = true; // a scroll, however short, is never also a two-finger tap
        axisLock = Math.abs(my) > 2f * Math.abs(mx) ? 1 : Math.abs(mx) > 2f * Math.abs(my) ? 2 : 0;
        accX = accY = 0;
        velN = 0;
        Main.debug("scroll start lock=" + axisLock);
        addScroll(mx, my, 0.03f, f.time);
    }

    private void scrollFrame(Frame f) {
        float[] c = centroid(f, centroidBuf), p = centroid(prev, centroidBuf2);
        if (c == null || p == null) return;
        addScroll(c[0] - p[0], c[1] - p[1], (float) (f.time - prev.time), f.time);
    }

    /** Converts finger travel (mm) to wheel units and sends whole units to Shadow. */
    private void addScroll(float dxMm, float dyMm, float dt, double time) {
        if (axisLock == 1) dxMm = 0;
        if (axisLock == 2) dyMm = 0;
        if (dt <= 0) dt = 0.008f;
        float speed = (float) Math.hypot(dxMm, dyMm) / dt;
        float gain = 13f * (1f + Math.min(1.2f, Math.max(0f, (speed - 40f) / 250f))) * config.scrollSpeed;
        float sign = config.natural() ? 1f : -1f;
        float wx = -dxMm * gain * sign;
        float wy = dyMm * gain * sign;
        recordVelocity(time, wx, wy);
        accX += wx;
        accY += wy;
        if (config.notchScroll) {
            flushScroll();
            return;
        }
        if (!out.scrollActive()) {
            // Shadow ignores drags under its 8-unit touch slop; start once we have more than that.
            if (Math.hypot(accX, accY) < 9) return;
            out.scrollBegin();
        }
        flushScroll();
    }

    private void flushScroll() {
        if (config.notchScroll) {
            int nx = (int) (accX / 120), ny = (int) (accY / 120);
            accX -= nx * 120;
            accY -= ny * 120;
            out.wheelNotches(nx, ny);
            return;
        }
        int ix = (int) accX, iy = (int) accY;
        accX -= ix;
        accY -= iy;
        out.scrollBy(ix, iy);
    }

    private void recordVelocity(double t, float wx, float wy) {
        if (velN == velT.length) {
            System.arraycopy(velT, 1, velT, 0, velN - 1);
            System.arraycopy(velX, 1, velX, 0, velN - 1);
            System.arraycopy(velY, 1, velY, 0, velN - 1);
            velN--;
        }
        velT[velN] = t;
        velX[velN] = wx;
        velY[velN] = wy;
        velN++;
    }

    /** Ends a scroll; with fling=true, keeps scrolling with decaying speed (flick momentum). */
    private void endScroll(boolean fling) {
        if (mode != Mode.SCROLL && !out.scrollActive() && !momentumActive) return;
        if (fling && config.momentum && velN >= 3) {
            double tEnd = velT[velN - 1];
            float sx = 0, sy = 0;
            double t0 = tEnd;
            for (int i = velN - 1; i >= 1 && tEnd - velT[i] < 0.06; i--) {
                sx += velX[i];
                sy += velY[i];
                t0 = velT[i - 1];
            }
            double span = tEnd - t0;
            if (span > 0.008) {
                // Flicks glide a bit faster than the finger left off (feels snappier).
                momVx = (float) (sx / span) * config.flickBoost;
                momVy = (float) (sy / span) * config.flickBoost;
                float v = (float) Math.hypot(momVx, momVy);
                float max = 9500f * config.scrollSpeed;
                if (v > max) { momVx *= max / v; momVy *= max / v; v = max; }
                if (v > 350f) {
                    if (!config.notchScroll && !out.scrollActive()) out.scrollBegin();
                    momentumActive = true;
                    momLast = SystemClock.uptimeMillis();
                    Main.debug(String.format("fling v=%.0f", v));
                    return;
                }
            }
        }
        momentumActive = false;
        accX = accY = 0;
        out.scrollEnd();
    }

    private void stepMomentum(long now) {
        float dt = (now - momLast) / 1000f;
        momLast = now;
        if (dt <= 0) return;
        float decay = (float) Math.exp(-dt / Math.max(0.05f, config.momentumFriction));
        momVx *= decay;
        momVy *= decay;
        accX += momVx * dt;
        accY += momVy * dt;
        flushScroll();
        if (Math.hypot(momVx, momVy) < 70f) {
            momentumActive = false;
            accX = accY = 0;
            out.scrollEnd();
        }
    }

    // ---- pinch: Ctrl + wheel ------------------------------------------------------------------

    private void pinchFrame(Frame f) {
        float d = spread(f);
        if (d <= 0 || pinchRefDist <= 0) return;
        pinchAcc += (float) Math.log(d / pinchRefDist) * PINCH_UNITS_PER_LN * config.pinchSpeed;
        pinchRefDist = d;
        if (config.notchScroll) {
            int n = (int) (pinchAcc / 120);
            pinchAcc -= n * 120;
            out.wheelNotches(n);
            return;
        }
        if (!out.scrollActive()) {
            if (Math.abs(pinchAcc) < 9) return; // Shadow's 8-unit touch slop
            out.scrollBegin();
        }
        int n = (int) pinchAcc;
        pinchAcc -= n;
        out.scrollBy(0, n); // positive = Ctrl+wheel up = zoom in
    }

    // ---- three / four finger swipes -------------------------------------------------------------

    /*
     * Android reserves Win-key shortcuts and Alt+Tab for itself (verified on this Googlebook:
     * injected Win+Tab opens Android Overview, Alt+Tab opens Android's switcher, Win+D never
     * arrives). Ctrl+Alt+Tab and Alt+Left/Right do reach Shadow, so gestures use those:
     *   3 fingers sideways: Windows app switcher (Ctrl+Alt+Tab), arrows while moving, Enter on lift
     *   3/4 fingers up: open the switcher (pick a window with a click); down: close it (Esc)
     *   4 fingers sideways: Back / Forward (Alt+Left / Alt+Right)
     */
    private void swipeFrame(Frame f) {
        float[] c = centroid(f);
        float dx = c[0] - swipeStartCx, dy = c[1] - swipeStartCy;
        if (mode == Mode.SWIPE_PENDING) {
            if (Math.hypot(dx, dy) < 10f) return;
            mode = Mode.SWIPE;
            contactMoved = true;
            boolean horizontal = Math.abs(dx) > Math.abs(dy);
            Main.debug("swipe" + swipeFingers + (horizontal ? (dx > 0 ? " right" : " left") : (dy < 0 ? " up" : " down")));
            if (!horizontal) {
                if (!switcherAllowed) {
                    // reserved by Android: do nothing
                } else if (dy < 0) {
                    openSwitcher();
                    switcherOpenedAt = SystemClock.uptimeMillis();
                } else if (switcherOpenedAt != 0 && SystemClock.uptimeMillis() - switcherOpenedAt < 30000) {
                    out.tap(KeyEvent.KEYCODE_ESCAPE);
                    switcherOpenedAt = 0;
                }
            } else if (swipeFingers == 4) {
                if (backForwardAllowed) out.chord(KeyEvent.KEYCODE_ALT_LEFT, dx > 0 ? KeyEvent.KEYCODE_DPAD_LEFT : KeyEvent.KEYCODE_DPAD_RIGHT);
            } else if (switcherAllowed) {
                openSwitcher(); // opens with the previous window selected
                switcherStepping = true;
                switcherOpenedAt = 0;
                swipeRefX = c[0];
            }
            return;
        }
        if (switcherStepping) {
            float step = c[0] - swipeRefX;
            if (Math.abs(step) > 18f) {
                out.tap(step > 0 ? KeyEvent.KEYCODE_DPAD_RIGHT : KeyEvent.KEYCODE_DPAD_LEFT);
                swipeRefX = c[0];
            }
        }
    }

    private void openSwitcher() {
        out.chord(KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.KEYCODE_TAB);
    }

    private void finishGesture(boolean fling) {
        switch (mode) {
            case SCROLL: endScroll(fling); break;
            case PINCH:
                if (ctrlHeld) {
                    out.scrollEnd();
                    out.releaseKeyAfterScroll(KeyEvent.KEYCODE_CTRL_LEFT);
                    ctrlHeld = false;
                }
                break;
            case SWIPE:
                if (switcherStepping) { out.tap(KeyEvent.KEYCODE_ENTER); switcherStepping = false; }
                break;
            default: return;
        }
        mode = Mode.IDLE; // finished: the caller sets what comes next
    }

    // ---- geometry helpers -----------------------------------------------------------------------

    private void recordNewStarts(Frame f) {
        for (int i = 0; i < MAX_SLOTS; i++) {
            if (f.id[i] >= 0 && f.id[i] != startId[i]) {
                startId[i] = f.id[i];
                startX[i] = f.x[i];
                startY[i] = f.y[i];
            }
        }
    }

    private float maxDisplacement(Frame f) {
        float m = 0;
        for (int i = 0; i < MAX_SLOTS; i++)
            if (f.id[i] >= 0 && f.id[i] == startId[i])
                m = Math.max(m, (float) Math.hypot(f.x[i] - startX[i], f.y[i] - startY[i]));
        return m;
    }

    /** Displacement vectors {ax, ay, bx, by} of the two fingers since they landed. */
    private float[] perFingerVectors(Frame f) {
        float[] d = new float[4];
        int k = 0;
        for (int i = 0; i < MAX_SLOTS && k < 2; i++)
            if (f.id[i] >= 0 && !f.palm[i] && f.id[i] == startId[i]) {
                d[2 * k] = f.x[i] - startX[i];
                d[2 * k + 1] = f.y[i] - startY[i];
                k++;
            }
        return k == 2 ? d : null;
    }

    private final float[] delta = new float[3];
    private final float[] centroidBuf = new float[2], centroidBuf2 = new float[2];

    /** Delta of the only finger, if it is the same finger as in the previous frame. */
    private float[] singleDelta(Frame f) {
        for (int i = 0; i < MAX_SLOTS; i++) {
            if (f.id[i] >= 0 && !f.palm[i]) {
                if (prev.id[i] != f.id[i]) return null;
                delta[0] = f.x[i] - prev.x[i];
                delta[1] = f.y[i] - prev.y[i];
                delta[2] = (float) (f.time - prev.time);
                return delta;
            }
        }
        return null;
    }

    /** Delta of the finger that moved most (thumb clicks while another finger drags). */
    private float[] fastestDelta(Frame f) {
        float[] best = null;
        float bestD = -1;
        for (int i = 0; i < MAX_SLOTS; i++) {
            if (f.id[i] >= 0 && !f.palm[i] && prev.id[i] == f.id[i]) {
                float dx = f.x[i] - prev.x[i], dy = f.y[i] - prev.y[i];
                float d = dx * dx + dy * dy;
                if (d > bestD) {
                    bestD = d;
                    delta[0] = dx;
                    delta[1] = dy;
                    delta[2] = (float) (f.time - prev.time);
                    best = delta;
                }
            }
        }
        return best;
    }

    private static float[] centroid(Frame f) {
        return centroid(f, new float[2]);
    }

    private static float[] centroid(Frame f, float[] out) {
        float sx = 0, sy = 0;
        int n = 0;
        for (int i = 0; i < MAX_SLOTS; i++)
            if (f.id[i] >= 0 && !f.palm[i]) { sx += f.x[i]; sy += f.y[i]; n++; }
        if (n == 0) return null;
        out[0] = sx / n;
        out[1] = sy / n;
        return out;
    }

    private static float spread(Frame f) {
        float[] c = centroid(f);
        if (c == null) return 0;
        float s = 0;
        int n = 0;
        for (int i = 0; i < MAX_SLOTS; i++)
            if (f.id[i] >= 0 && !f.palm[i]) { s += (float) Math.hypot(f.x[i] - c[0], f.y[i] - c[1]); n++; }
        return n == 0 ? 0 : 2 * s / n;
    }
}
