package local.shadowtrackpad;
/** Fuzz build only: a controllable clock. */
final class Clock {
    static boolean fake;
    static long t = 1_000_000;
    static long uptime() { return fake ? t : android.os.SystemClock.uptimeMillis(); }
    static void sleep(long ms) { if (fake) t += ms; else android.os.SystemClock.sleep(ms); }
}
