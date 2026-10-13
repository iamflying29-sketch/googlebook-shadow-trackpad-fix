package local.shadowtrackpad;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Properties;

/**
 * Settings from /data/local/tmp/shadowtrackpad/config.properties (re-read when it changes).
 * "auto" values follow the Android trackpad settings, read from dumpsys input.
 */
final class Config {
    static final File FILE = new File("/data/local/tmp/shadowtrackpad/config.properties");

    volatile String[] packages = {"com.blade.shadowcloudgaming"};
    volatile float pointerSpeed = 1f;   // multiplier on top of the Android pointer speed
    volatile float scrollSpeed = 1f;    // multiplier; 1.0 ~ Windows precision-touchpad feel
    volatile boolean momentum = true;
    volatile float momentumFriction = 0.28f; // seconds for the flick speed to fall to ~37 %
    volatile String naturalScroll = "auto";
    volatile String tapToClick = "auto";
    volatile String tapDrag = "auto";
    volatile boolean notchScroll = false; // scroll_mode=notch: whole wheel notches, delivered instantly
    volatile boolean lowLatency = true;   // flush each move to Shadow immediately instead of at the next frame
    volatile float pinchSpeed = 1f;
    volatile int scrollHoldMs = 130;      // min two-finger contact (Shadow opens its keyboard below 100 ms)
    volatile float flickBoost = 1.25f;  // flick glide speed relative to the finger's lift-off speed
    volatile boolean gestures = true;   // 3/4-finger swipes and pinch-zoom
    volatile int typingTapBlockMs = 400;

    // Resolved from Android settings.
    boolean androidNatural = true, androidTap = true, androidTapDrag = false;
    int androidPointerSensitivity = 3;

    private long loadedMtime = -1;

    Config() {
        load();
    }

    synchronized void reloadIfChanged() {
        long m = FILE.exists() ? FILE.lastModified() : 0;
        if (m != loadedMtime) load();
    }

    synchronized void load() {
        loadedMtime = FILE.exists() ? FILE.lastModified() : 0;
        Properties p = new Properties();
        if (FILE.exists()) {
            try (FileInputStream in = new FileInputStream(FILE)) {
                p.load(in);
            } catch (IOException | RuntimeException e) {
                // e.g. a malformed unicode escape: keep the defaults rather than failing to start
                Main.log("config: unreadable (" + e + "), using defaults");
                p = new Properties();
            }
        }
        java.util.List<String> pk = new java.util.ArrayList<>();
        for (String s : p.getProperty("packages", "com.blade.shadowcloudgaming").split(",")) if (!s.trim().isEmpty()) pk.add(s.trim());
        if (pk.isEmpty()) pk.add("com.blade.shadowcloudgaming");
        packages = pk.toArray(new String[0]);
        // Every number is kept within a safe range, so a typo can't freeze the cursor or bring back
        // Shadow's two-finger keyboard pop-up.
        pointerSpeed = f(p, "pointer_speed", 1f, 0.1f, 10f);
        scrollSpeed = f(p, "scroll_speed", 1f, 0.1f, 10f);
        momentum = Boolean.parseBoolean(p.getProperty("momentum", "true").trim());
        momentumFriction = f(p, "momentum_friction", 0.28f, 0.05f, 3f);
        naturalScroll = p.getProperty("natural_scroll", "auto").trim();
        tapToClick = p.getProperty("tap_to_click", "auto").trim();
        tapDrag = p.getProperty("tap_drag", "auto").trim();
        notchScroll = p.getProperty("scroll_mode", "smooth").trim().equalsIgnoreCase("notch");
        lowLatency = Boolean.parseBoolean(p.getProperty("low_latency", "true").trim());
        pinchSpeed = f(p, "pinch_speed", 1f, 0.1f, 10f);
        scrollHoldMs = (int) f(p, "scroll_hold_ms", 130, 120, 1000);
        flickBoost = f(p, "flick_boost", 1.25f, 0.3f, 4f);
        gestures = Boolean.parseBoolean(p.getProperty("gestures", "true").trim());
        typingTapBlockMs = (int) f(p, "typing_tap_block_ms", 400, 0, 5000);
        Main.log("config: " + this);
    }

    private static float f(Properties p, String k, float d, float min, float max) {
        try {
            float v = Float.parseFloat(p.getProperty(k, Float.toString(d)).trim());
            if (Float.isNaN(v)) throw new NumberFormatException();
            if (v < min || v > max) Main.log("config: " + k + "=" + v + " out of range, using " + Math.max(min, Math.min(max, v)));
            return Math.max(min, Math.min(max, v));
        } catch (NumberFormatException e) {
            Main.log("config: bad " + k + ", using " + d);
            return d;
        }
    }

    /** Reads the live Android trackpad settings from the touchpad's gesture properties. */
    synchronized void readAndroidSettings(String dumpsysInput) {
        androidNatural = prop(dumpsysInput, "Invert Scrolling", androidNatural);
        androidTap = prop(dumpsysInput, "Tap Enable", androidTap);
        androidTapDrag = prop(dumpsysInput, "Tap Drag Enable", androidTapDrag);
        int i = dumpsysInput.indexOf("Pointer Sensitivity (integer): [");
        if (i >= 0) {
            int s = i + "Pointer Sensitivity (integer): [".length();
            try {
                int v = Integer.parseInt(dumpsysInput.substring(s, dumpsysInput.indexOf(']', s)).trim());
                androidPointerSensitivity = Math.max(1, Math.min(5, v)); // Android's slider is 1..5
            } catch (RuntimeException ignored) {
            }
        }
    }

    private static boolean prop(String dump, String name, boolean def) {
        int i = dump.indexOf(name + " (boolean): [");
        if (i < 0) return def;
        return dump.startsWith("true", i + name.length() + " (boolean): [".length());
    }

    synchronized boolean natural() {
        return resolve(naturalScroll, androidNatural);
    }

    synchronized boolean tapToClick() {
        return resolve(tapToClick, androidTap);
    }

    synchronized boolean tapDrag() {
        return resolve(tapDrag, androidTapDrag);
    }

    /** Overall cursor gain: Android sensitivity 1..5 maps to 0.6x..1.6x, times pointer_speed. */
    synchronized float pointerGain() {
        return (0.35f + 0.25f * androidPointerSensitivity) * pointerSpeed;
    }

    private static boolean resolve(String v, boolean auto) {
        return v.equalsIgnoreCase("auto") ? auto : Boolean.parseBoolean(v);
    }

    @Override
    public synchronized String toString() {
        return "packages=" + String.join(",", packages) + " pointer_speed=" + pointerSpeed + " scroll_speed=" + scrollSpeed
                + " scroll_mode=" + (notchScroll ? "notch" : "smooth") + " low_latency=" + lowLatency + " pinch_speed=" + pinchSpeed + " momentum=" + momentum + " friction=" + momentumFriction + " flick_boost=" + flickBoost + " natural=" + naturalScroll + "(" + androidNatural
                + ") tap=" + tapToClick + "(" + androidTap + ") tap_drag=" + tapDrag + "(" + androidTapDrag
                + ") gestures=" + gestures + " android_sensitivity=" + androidPointerSensitivity;
    }
}
