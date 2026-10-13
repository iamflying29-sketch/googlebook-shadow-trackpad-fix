#!/usr/bin/env python3
"""Adds fuzz-only hooks to a COPY of the helper sources (never the real ones):
   - Injector: dry-run send() with an event validator and optional trace
   - Engine: accessors + a flag marking pinch-driven drags (Ctrl is expected there)"""
import sys, os

d = sys.argv[1]


def patch(name, pairs):
    p = os.path.join(d, name)
    s = open(p).read()
    for a, b in pairs:
        if s.count(a) != 1:
            sys.exit(f"hooks.py: anchor not found exactly once in {name}: {a[:70]!r}")
        s = s.replace(a, b)
    open(p, "w").write(s)


patch("Injector.java", [(
    '''    private void send(InputEvent e) {
        boolean ok;''',
    '''    // ---- fuzz build: dry run + protocol validator ----
    static boolean dryRun, trace;
    static final java.util.List<String> violations = new java.util.ArrayList<>();
    private final java.util.Set<Integer> vKeys = new java.util.HashSet<>();
    private boolean vTpActive;
    private int vButtons;
    private long vMouseDown = -1;
    private final java.util.Map<Integer, Long> vKeyDown = new java.util.HashMap<>();
    static void violation(String v) { violations.add(v); }

    private void validate(InputEvent ev) {
        if (ev instanceof KeyEvent) {
            KeyEvent k = (KeyEvent) ev;
            if (trace) System.out.println("   >> key " + (k.getAction() == 0 ? "DOWN " : "UP   ") + KeyEvent.keyCodeToString(k.getKeyCode()));
            if (k.getAction() == KeyEvent.ACTION_DOWN && k.getKeyCode() == KeyEvent.KEYCODE_TAB
                    && !(vKeys.contains(KeyEvent.KEYCODE_CTRL_LEFT) && vKeys.contains(KeyEvent.KEYCODE_ALT_LEFT)))
                violation("Tab sent without Ctrl+Alt held (Alt+Tab would open Android's switcher)");
            if (k.getAction() == KeyEvent.ACTION_DOWN && (k.getKeyCode() == KeyEvent.KEYCODE_ESCAPE || k.getKeyCode() == KeyEvent.KEYCODE_ENTER)
                    && vKeys.contains(KeyEvent.KEYCODE_CTRL_LEFT) && !vKeys.contains(KeyEvent.KEYCODE_ALT_LEFT))
                violation("Esc/Enter sent with only Ctrl held");
            if (k.getAction() == KeyEvent.ACTION_DOWN && (k.getKeyCode() == KeyEvent.KEYCODE_DPAD_LEFT || k.getKeyCode() == KeyEvent.KEYCODE_DPAD_RIGHT)
                    && vKeys.contains(KeyEvent.KEYCODE_CTRL_LEFT) != vKeys.contains(KeyEvent.KEYCODE_ALT_LEFT) && vKeys.contains(KeyEvent.KEYCODE_CTRL_LEFT))
                violation("arrow sent with a stray Ctrl");
            if (k.getAction() == KeyEvent.ACTION_DOWN) {
                if (!vKeys.add(k.getKeyCode())) violation("key pressed twice without release: " + KeyEvent.keyCodeToString(k.getKeyCode()));
                vKeyDown.put(k.getKeyCode(), k.getDownTime());
            } else {
                if (!vKeys.remove(k.getKeyCode())) violation("key released while not pressed: " + KeyEvent.keyCodeToString(k.getKeyCode()));
                Long d = vKeyDown.remove(k.getKeyCode());
                if (d != null && d != k.getDownTime()) violation("key UP downTime differs from its DOWN");
            }
            int own = k.getKeyCode() == KeyEvent.KEYCODE_CTRL_LEFT ? KeyEvent.META_CTRL_ON : k.getKeyCode() == KeyEvent.KEYCODE_ALT_LEFT ? KeyEvent.META_ALT_ON : 0;
            if (own != 0 && ((k.getMetaState() & own) != 0) != (k.getAction() == KeyEvent.ACTION_DOWN))
                violation("modifier meta state wrong on " + (k.getAction() == 0 ? "DOWN" : "UP"));
            return;
        }
        MotionEvent m = (MotionEvent) ev;
        int a = m.getActionMasked();
        boolean flush = a == MotionEvent.ACTION_BUTTON_RELEASE && m.getActionButton() == MotionEvent.BUTTON_FORWARD;
        if (trace && !flush && a != MotionEvent.ACTION_MOVE)
            System.out.println("   >> " + (m.getSource() == InputDevice.SOURCE_TOUCHPAD ? "TP " : "MOUSE ") + MotionEvent.actionToString(m.getAction())
                    + " btn=" + m.getButtonState() + " held=" + (m.getEventTime() - m.getDownTime()));
        if (m.getEventTime() < m.getDownTime()) violation("eventTime < downTime action=" + a);
        if (m.getSource() == InputDevice.SOURCE_TOUCHPAD) {
            if (m.getButtonState() != 0) violation("touchpad event with buttonState");
            if (a == MotionEvent.ACTION_DOWN) { if (vTpActive) violation("touchpad DOWN while active"); vTpActive = true; }
            else if (!vTpActive) violation("touchpad action " + a + " while not active");
            if (a == MotionEvent.ACTION_POINTER_UP && m.getEventTime() - m.getDownTime() < 100)
                violation("two-finger contact shorter than 100 ms (" + (m.getEventTime() - m.getDownTime()) + " ms) -> Shadow keyboard pop-up");
            if (a == MotionEvent.ACTION_UP) vTpActive = false;
            if (a == MotionEvent.ACTION_MOVE && vKeys.contains(KeyEvent.KEYCODE_CTRL_LEFT) && !Engine.fuzzPinching) violation("scroll while Ctrl held (would zoom)");
        } else {
            int b = m.getButtonState();
            if (a == MotionEvent.ACTION_DOWN && (vButtons != 0 || b == 0)) violation("mouse DOWN with prev=" + vButtons + " now=" + b);
            if (a == MotionEvent.ACTION_UP && b != 0) violation("mouse UP with buttons " + b);
            if (a == MotionEvent.ACTION_DOWN) vMouseDown = m.getDownTime();
            else if (vMouseDown >= 0 && m.getDownTime() != vMouseDown && a != MotionEvent.ACTION_SCROLL)
                violation("mouse event inside a press with a different downTime (action " + a + ")");
            if (a == MotionEvent.ACTION_UP) vMouseDown = -1;
            if (a == MotionEvent.ACTION_BUTTON_PRESS && (b & m.getActionButton()) == 0) violation("BUTTON_PRESS not in state");
            if (a == MotionEvent.ACTION_SCROLL && vKeys.contains(KeyEvent.KEYCODE_CTRL_LEFT) && !Engine.fuzzPinching) violation("wheel notch while Ctrl held (would zoom)");
            if (flush) { if (b != vButtons) violation("flush changed button state"); }
            else if (a == MotionEvent.ACTION_DOWN || a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_BUTTON_PRESS || a == MotionEvent.ACTION_BUTTON_RELEASE) vButtons = b;
        }
    }

    String stuckState() {
        StringBuilder s = new StringBuilder();
        if (buttons != 0 || vButtons != 0) s.append(" buttons=" + buttons + "/" + vButtons);
        if (!vKeys.isEmpty()) s.append(" keys=" + vKeys);
        if (metaState != 0) s.append(" meta=" + metaState);
        if (tpActive || vTpActive) s.append(" drag-open");
        return s.toString();
    }

    private void send(InputEvent e) {
        if (dryRun) {
            validate(e);
            if (e instanceof MotionEvent) ((MotionEvent) e).recycle();
            return;
        }
        boolean ok;''')])

patch("Engine.java", [
    ('''    volatile long lastActivityUptime;''', '''    volatile long lastActivityUptime;
    static boolean fuzzPinching; // fuzz build only'''),
    ('''    private void pinchFrame(Frame f) {''', '''    private void pinchFrame(Frame f) {
        fuzzPinching = true;'''),
    ('''    private void scrollFrame(Frame f) {''', '''    private void scrollFrame(Frame f) {
        fuzzPinching = false;'''),
    ('''    void tick() {''', '''    boolean momentum() { return momentumActive; }
    long tapPending() { return tapReleaseAt; }

    void tick() {'''),
])
