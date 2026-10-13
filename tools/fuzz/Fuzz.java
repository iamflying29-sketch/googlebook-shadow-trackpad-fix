package local.shadowtrackpad;

import java.util.*;

/**
 * Fuzz build only: random finger / click / timing sequences through the real Engine + Injector
 * (dry run, nothing is injected). After every sequence all fingers are lifted and timers drained;
 * then nothing may be left held (buttons, keys, synthetic drag) and the engine must be idle.
 * The Injector validator also checks every event as it would be sent.
 */
final class Fuzz {
    static final float W = 4625 / 31f, H = 2889 / 31f;
    static long traceSeed;

    public static void main(String[] a) throws Exception {
        if (a[0].equals("trace")) { Injector.trace = true; traceSeed = Long.parseLong(a[1]); a = new String[]{"1", a[1]}; }
        long seqs = Long.parseLong(a[0]);
        long seed0 = a.length > 1 ? Long.parseLong(a[1]) : System.nanoTime();
        Clock.fake = true;
        Injector.dryRun = true;
        Main.verbose = false;
        Config config = new Config();
        Map<String, Integer> counts = new TreeMap<>();
        Map<String, Long> example = new HashMap<>();
        long frames = 0, t0 = System.nanoTime();
        for (long s = 0; s < seqs; s++) {
            long seed = seed0 + s;
            Random r = new Random(seed);
            config.naturalScroll = r.nextBoolean() ? "true" : "false";
            config.tapToClick = r.nextBoolean() ? "true" : "false";
            config.tapDrag = r.nextBoolean() ? "true" : "false";
            config.notchScroll = r.nextInt(5) == 0;
            config.gestures = r.nextInt(5) != 0;
            config.momentum = r.nextInt(4) != 0;
            config.lowLatency = r.nextBoolean();
            Injector out = new Injector();
            out.scrollHoldMs = 130;
            Engine engine = new Engine(config, out);
            engine.padHeightMm = r.nextBoolean() ? H : 0;
            engine.switcherAllowed = r.nextInt(6) != 0;
            engine.backForwardAllowed = r.nextInt(6) != 0;
            engine.lastKeyUptime = r.nextInt(4) == 0 ? Clock.t : 0;
            Injector.violations.clear();
            Engine.fuzzPinching = false;
            String failure = null;
            try {
                Engine.Frame f = new Engine.Frame();
                int nextId = 1;
                int steps = 1 + r.nextInt(250);
                for (int i = 0; i < steps; i++) {
                    advance(engine, out, pickDt(r));
                    int n = f.count();
                    // fingers land / lift
                    if (n < 5 && r.nextFloat() < 0.15f) {
                        int slot = freeSlot(f, r);
                        if (slot >= 0) {
                            // sometimes a finger the helper didn't see land (grab mid-contact)
                            f.id[slot] = r.nextInt(25) == 0 ? Engine.ADOPTED_ID + nextId++ : nextId++;
                            f.x[slot] = r.nextFloat() * W;
                            f.y[slot] = r.nextFloat() * H;
                            f.palm[slot] = r.nextInt(30) == 0;
                        }
                    }
                    if (n > 0 && r.nextFloat() < 0.12f) {
                        int slot = usedSlot(f, r);
                        if (slot >= 0) { f.id[slot] = -1; f.palm[slot] = false; }
                    }
                    // movement: mostly smooth, sometimes jumps; often fingers move together
                    float cdx = (float) r.nextGaussian() * 0.8f, cdy = (float) r.nextGaussian() * 0.8f;
                    boolean together = r.nextBoolean();
                    for (int k = 0; k < Engine.MAX_SLOTS; k++) {
                        if (f.id[k] < 0) continue;
                        float dx = together ? cdx : (float) r.nextGaussian() * 0.8f;
                        float dy = together ? cdy : (float) r.nextGaussian() * 0.8f;
                        if (r.nextInt(25) == 0) { dx *= 15; dy *= 15; }
                        f.x[k] = Math.max(0, Math.min(W, f.x[k] + dx));
                        f.y[k] = Math.max(0, Math.min(H, f.y[k] + dy));
                        if (r.nextInt(150) == 0) f.palm[k] = !f.palm[k];
                    }
                    if (r.nextFloat() < (f.count() > 0 ? 0.06f : 0.01f)) f.button = !f.button;
                    if (r.nextInt(400) == 0) {
                        if (Injector.trace) System.out.println("   ** engine.reset() (capture lost / SYN_DROPPED)");
                        // capture lost / re-grab or SYN_DROPPED: the daemon resets the engine and frame
                        engine.reset();
                        if (r.nextBoolean()) f = new Engine.Frame();
                    }
                    f.time = Clock.t / 1000.0;
                    Engine.Mode before = engine.mode();
                    engine.onFrame(f);
                    engine.tick();
                    frames++;
                    if (Injector.trace) System.out.println("t=" + Clock.t + " fingers=" + f.count() + " btn=" + f.button + " mode " + before + "->" + engine.mode()
                            + (engine.momentum() ? " [momentum]" : "") + (engine.tapPending() != 0 ? " [tap-hold]" : "") + " | " + out.stuckState());
                }
                // end: lift everything, then let timers (tap release, synthetic lift, flick) run out
                Arrays.fill(f.id, -1);
                Arrays.fill(f.palm, false);
                f.button = false;
                advance(engine, out, 8);
                f.time = Clock.t / 1000.0;
                engine.onFrame(f);
                engine.tick();
                int guard = 0;
                while (engine.nextTimerMs() >= 0 && guard++ < 5000) advance(engine, out, 8);
                String stuck = out.stuckState();
                if (guard >= 5000) failure = "timers never finished";
                else if (!stuck.isEmpty()) failure = "left held after sequence:" + stuck;
                else if (engine.mode() != Engine.Mode.IDLE) failure = "engine not idle: " + engine.mode();
                else if (engine.momentum() || engine.tapPending() != 0) failure = "momentum/tap still pending";
            } catch (Throwable t) {
                StackTraceElement[] st = t.getStackTrace();
                failure = "EXCEPTION " + t + (st.length > 0 ? " at " + st[0] : "");
            }
            List<String> all = new ArrayList<>(new LinkedHashSet<>(Injector.violations));
            if (failure != null) all.add(failure);
            for (String v : all) {
                String key = v.replaceAll("\\d+ ms", "N ms").replaceAll("=\\d+(/\\d+)?", "=N");
                counts.merge(key, 1, Integer::sum);
                example.putIfAbsent(key, seed);
            }
        }
        double secs = (System.nanoTime() - t0) / 1e9;
        System.out.printf("fuzz: %d sequences, %d frames, %.1f s%n", seqs, frames, secs);
        if (counts.isEmpty()) System.out.println("RESULT: no violations, no stuck state, no exceptions");
        for (Map.Entry<String, Integer> e : counts.entrySet())
            System.out.println("VIOLATION x" + e.getValue() + " (e.g. seed " + example.get(e.getKey()) + "): " + e.getKey());
    }

    /** Advance the clock like the daemon loop does: timers fire no later than every 8 ms. */
    static void advance(Engine engine, Injector out, long dt) {
        while (dt > 0) {
            long step = Math.min(dt, 8);
            Clock.t += step;
            dt -= step;
            engine.tick();
        }
    }

    static long pickDt(Random r) {
        int p = r.nextInt(100);
        if (p < 70) return 4 + r.nextInt(9);
        if (p < 85) return r.nextInt(2);          // same timestamp / 1 ms
        if (p < 95) return 13 + r.nextInt(68);
        return 200 + r.nextInt(1300);
    }

    static int freeSlot(Engine.Frame f, Random r) {
        for (int tries = 0; tries < 20; tries++) {
            int s = r.nextInt(5);
            if (f.id[s] < 0) return s;
        }
        return -1;
    }

    static int usedSlot(Engine.Frame f, Random r) {
        for (int tries = 0; tries < 20; tries++) {
            int s = r.nextInt(Engine.MAX_SLOTS);
            if (f.id[s] >= 0) return s;
        }
        return -1;
    }
}
