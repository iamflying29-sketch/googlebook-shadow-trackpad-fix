package local.shadowtrackpad;

import android.os.SystemClock;

/**
 * Drives the real Engine + Injector with synthetic finger frames at the trackpad's ~120 Hz
 * (no trackpad needed): a one-finger cursor sweep, then a two-finger scroll ending in a flick.
 * Events go to whichever window holds pointer capture.
 */
final class SelfTest {
    static void run() throws Exception {
        android.os.Process.setThreadPriority(-8);
        Config config = new Config();
        Injector out = new Injector();
        // The doctor uses this test to check the instant-delivery mechanism itself, so it is always
        // on here (whatever low_latency is set to); the hold time follows the real setting.
        out.lowLatency = true;
        out.scrollHoldMs = config.scrollHoldMs;
        Engine engine = new Engine(config, out);
        engine.padHeightMm = 2889 / 31f; // this Googlebook's pad (93 mm)
        Latency perFrame = new Latency("engine+injection per frame");
        Engine.Frame f = new Engine.Frame();
        long periodNs = 8_333_333L;
        long next = System.nanoTime();
        int sentCountsX = 0;

        // 1) one finger: 150 frames moving 0.4 mm right each.
        f.id[0] = 1; f.x[0] = 20; f.y[0] = 40;
        for (int i = 0; i < 150; i++) {
            f.x[0] += 0.4f;
            f.time = SystemClock.uptimeMillis() / 1000.0;
            long t = System.nanoTime();
            engine.onFrame(f);
            engine.tick();
            perFrame.add((System.nanoTime() - t) / 1000);
            next += periodNs;
            sleepUntil(next);
        }
        f.id[0] = -1;
        f.time = SystemClock.uptimeMillis() / 1000.0;
        engine.onFrame(f);
        SystemClock.sleep(400);

        // 2) two fingers: scroll down 60 frames, accelerating, then lift (flick).
        f.id[0] = 2; f.x[0] = 60; f.y[0] = 20;
        f.id[1] = 3; f.x[1] = 80; f.y[1] = 20;
        for (int i = 0; i < 60; i++) {
            float step = 0.3f + i * 0.03f;
            f.y[0] += step;
            f.y[1] += step;
            f.time = SystemClock.uptimeMillis() / 1000.0;
            long t = System.nanoTime();
            engine.onFrame(f);
            engine.tick();
            perFrame.add((System.nanoTime() - t) / 1000);
            next = System.nanoTime() + periodNs;
            sleepUntil(next);
        }
        f.id[0] = -1; f.id[1] = -1;
        f.time = SystemClock.uptimeMillis() / 1000.0;
        engine.onFrame(f);
        long glideStart = SystemClock.uptimeMillis();
        while (engine.nextTimerMs() >= 0 && SystemClock.uptimeMillis() - glideStart < 5000) {
            SystemClock.sleep(Math.max(1, engine.nextTimerMs()));
            long t = System.nanoTime();
            engine.tick();
            perFrame.add((System.nanoTime() - t) / 1000);
        }
        SystemClock.sleep(400);

        // 3) pinch: two fingers moving apart for 40 frames (zoom in).
        f.id[0] = 4; f.x[0] = 60; f.y[0] = 40;
        f.id[1] = 5; f.x[1] = 80; f.y[1] = 40;
        for (int i = 0; i < 40; i++) {
            f.x[0] -= 0.5f;
            f.x[1] += 0.5f;
            f.time = SystemClock.uptimeMillis() / 1000.0;
            long t = System.nanoTime();
            engine.onFrame(f);
            engine.tick();
            perFrame.add((System.nanoTime() - t) / 1000);
            next = System.nanoTime() + periodNs;
            sleepUntil(next);
        }
        f.id[0] = -1; f.id[1] = -1;
        f.time = SystemClock.uptimeMillis() / 1000.0;
        engine.onFrame(f);
        long end = SystemClock.uptimeMillis();
        while (engine.nextTimerMs() >= 0 && SystemClock.uptimeMillis() - end < 2000) {
            SystemClock.sleep(Math.max(1, engine.nextTimerMs()));
            engine.tick();
        }
        SystemClock.sleep(400);

        // 4) two quick pinches in a row (the first shorter than the synthetic-finger hold time):
        //    Ctrl must stay down across both and be released once at the end.
        for (int round = 0; round < 2; round++) {
            f.id[0] = 10 + round; f.x[0] = 60; f.y[0] = 40;
            f.id[1] = 20 + round; f.x[1] = 80; f.y[1] = 40;
            int frames = round == 0 ? 6 : 25;
            for (int i = 0; i < frames; i++) {
                f.x[0] -= 1.0f;
                f.x[1] += 1.0f;
                f.time = SystemClock.uptimeMillis() / 1000.0;
                engine.onFrame(f);
                engine.tick();
                next = System.nanoTime() + periodNs;
                sleepUntil(next);
            }
            f.id[0] = -1; f.id[1] = -1;
            f.time = SystemClock.uptimeMillis() / 1000.0;
            engine.onFrame(f);
            engine.tick();
            SystemClock.sleep(20);
        }
        end = SystemClock.uptimeMillis();
        while (engine.nextTimerMs() >= 0 && SystemClock.uptimeMillis() - end < 2000) {
            SystemClock.sleep(Math.max(1, engine.nextTimerMs()));
            engine.tick();
        }
        SystemClock.sleep(300);

        // 5) physical clicks: index finger + thumb resting in the bottom zone -> LEFT click;
        //    two fingers above the zone -> RIGHT click (two-finger click).
        float[][] clicks = {{60, 40, 50, 89}, {55, 40, 75, 40}};
        for (float[] c : clicks) {
            f.id[0] = 30; f.x[0] = c[0]; f.y[0] = c[1];
            f.id[1] = 31; f.x[1] = c[2]; f.y[1] = c[3];
            f.button = false;
            f.time = SystemClock.uptimeMillis() / 1000.0;
            engine.onFrame(f);
            SystemClock.sleep(30);
            f.button = true;
            f.time = SystemClock.uptimeMillis() / 1000.0;
            engine.onFrame(f);
            SystemClock.sleep(60);
            f.button = false;
            f.time = SystemClock.uptimeMillis() / 1000.0;
            engine.onFrame(f);
            f.id[0] = -1; f.id[1] = -1;
            f.time = SystemClock.uptimeMillis() / 1000.0;
            engine.onFrame(f);
            SystemClock.sleep(150);
        }
        System.out.println(perFrame);
        System.out.println("flick glide lasted " + (SystemClock.uptimeMillis() - glideStart) + " ms");
    }

    private static void sleepUntil(long ns) {
        long d;
        while ((d = ns - System.nanoTime()) > 0) {
            if (d > 1_500_000) SystemClock.sleep(d / 1_000_000 - 1);
            else Thread.onSpinWait();
        }
    }
}
