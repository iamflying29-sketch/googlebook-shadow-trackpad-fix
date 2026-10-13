package local.shadowtrackpad;

import java.util.*;

/**
 * Fuzz build only: a model of the kernel's multitouch reporting (protocol B with the kernel's
 * filtering: a slot number only when it differs from the last one sent, a value only when it
 * changed) feeds MtParser, starting at random moments (grab while fingers are down) and with
 * random SYN_DROPPED losses. Every finger the parser reports must be at the exact position of a
 * real finger, and a reported id must always stay with the same real finger (otherwise the
 * cursor would jump).
 */
final class ParserFuzz {
    static final int SLOTS = 5;

    public static void main(String[] a) {
        boolean trace = a[0].equals("trace");
        if (trace) a = new String[]{"1", a[1]};
        long seqs = Long.parseLong(a[0]);
        long seed0 = a.length > 1 ? Long.parseLong(a[1]) : System.nanoTime();
        Map<String, Integer> counts = new TreeMap<>();
        Map<String, Long> example = new HashMap<>();
        long frames = 0, exposedTotal = 0;
        for (long s = 0; s < seqs; s++) {
            long seed = seed0 + s;
            Random r = new Random(seed);
            // kernel state
            boolean[] active = new boolean[SLOTS];
            int[] tid = new int[SLOTS], x = new int[SLOTS], y = new int[SLOTS], tool = new int[SLOTS];
            int[] sentTid = new int[SLOTS], sentX = new int[SLOTS], sentY = new int[SLOTS], sentTool = new int[SLOTS];
            Arrays.fill(tid, -1);
            Arrays.fill(sentTid, -1);
            int lastSlot = 0, nextTid = 0;
            boolean btn = false, sentBtn = false;
            MtParser p = new MtParser();
            boolean reading = false, btnKnown = false;
            int dropFrames = 0;
            Map<Integer, Set<Integer>> idToReal = new HashMap<>();
            String failure = null;
            int steps = 20 + r.nextInt(400);
            for (int step = 0; step < steps && failure == null; step++) {
                // the world changes
                for (int i = 0; i < SLOTS; i++) {
                    if (!active[i]) {
                        if (r.nextInt(12) == 0) {
                            active[i] = true;
                            tid[i] = nextTid = (nextTid + 1) & 0xffff;
                            if (r.nextInt(3) != 0) x[i] = r.nextInt(4626);
                            if (r.nextInt(3) != 0) y[i] = r.nextInt(2890);
                            if (r.nextInt(20) == 0) tool[i] = tool[i] == 0 ? 2 : 0;
                        }
                    } else if (r.nextInt(15) == 0) {
                        active[i] = false;
                        tid[i] = -1;
                    } else {
                        int m = r.nextInt(5);
                        if (m == 0 || m == 1) x[i] = Math.max(0, Math.min(4625, x[i] + r.nextInt(41) - 20));
                        if (m == 0 || m == 2) y[i] = Math.max(0, Math.min(2889, y[i] + r.nextInt(41) - 20));
                        if (r.nextInt(200) == 0) tool[i] = tool[i] == 0 ? 2 : 0;
                    }
                }
                if (r.nextInt(25) == 0) btn = !btn;
                // the kernel emits what changed
                List<int[]> ev = new ArrayList<>();
                for (int i = 0; i < SLOTS; i++) {
                    List<int[]> e = new ArrayList<>();
                    if (tid[i] != sentTid[i]) { e.add(new int[]{3, MtParser.ABS_MT_TRACKING_ID, tid[i]}); sentTid[i] = tid[i]; }
                    if (active[i]) {
                        if (x[i] != sentX[i]) { e.add(new int[]{3, MtParser.ABS_MT_POSITION_X, x[i]}); sentX[i] = x[i]; }
                        if (y[i] != sentY[i]) { e.add(new int[]{3, MtParser.ABS_MT_POSITION_Y, y[i]}); sentY[i] = y[i]; }
                        if (tool[i] != sentTool[i]) { e.add(new int[]{3, MtParser.ABS_MT_TOOL_TYPE, tool[i]}); sentTool[i] = tool[i]; }
                    }
                    if (!e.isEmpty()) {
                        if (i != lastSlot) { ev.add(new int[]{3, MtParser.ABS_MT_SLOT, i}); lastSlot = i; }
                        ev.addAll(e);
                    }
                }
                if (btn != sentBtn) { ev.add(new int[]{1, MtParser.BTN_LEFT, btn ? 1 : 0}); sentBtn = btn; }
                ev.add(new int[]{0, MtParser.SYN_REPORT, 0});
                // the reader: starts (grab) at a random frame; sometimes loses events (SYN_DROPPED)
                if (trace && reading && dropFrames > 0) System.out.println("  (dropping frame)");
                if (!reading) {
                    if (r.nextInt(10) == 0) { if (trace) System.out.println("== GRAB (reader starts)"); reading = true; p.resetUnknown(); idToReal.clear(); btnKnown = false; }
                    continue;
                }
                if (dropFrames > 0) {
                    dropFrames--;
                    if (dropFrames == 0) {
                        // the queue overflowed: SYN_DROPPED, then the tail of this packet
                        if (trace) System.out.println("== SYN_DROPPED");
                        p.onEvent(0, MtParser.SYN_DROPPED, 0);
                        idToReal.clear();
                        btnKnown = false;
                        for (int k = r.nextInt(ev.size()); k < ev.size(); k++) p.onEvent(ev.get(k)[0], ev.get(k)[1], ev.get(k)[2]);
                    }
                    continue;
                }
                if (r.nextInt(150) == 0) { dropFrames = 1 + r.nextInt(4); continue; }
                if (trace) {
                    StringBuilder w = new StringBuilder("WORLD lastSlot=" + lastSlot + ":");
                    for (int i = 0; i < SLOTS; i++) if (active[i]) w.append(" s").append(i).append("[tid ").append(tid[i]).append(" ").append(x[i]).append(",").append(y[i]).append("]");
                    System.out.println(w);
                }
                for (int[] e : ev) {
                    if (trace) System.out.println("  ev " + (e[0] == 3 ? (e[1] == 0x2f ? "SLOT" : e[1] == 0x39 ? "TID" : e[1] == 0x35 ? "X" : e[1] == 0x36 ? "Y" : "TOOL") : e[0] == 0 ? "SYN" : "BTN") + " " + e[2]);
                    if (e[0] == 1) btnKnown = true;
                    if (p.onEvent(e[0], e[1], e[2]) != MtParser.FRAME) continue;
                    frames++;
                    Engine.Frame f = p.frame;
                    if (trace) {
                        StringBuilder w = new StringBuilder("  FRAME:");
                        for (int k = 0; k < Engine.MAX_SLOTS; k++) if (f.id[k] >= 0) w.append(" p").append(k).append("[id ").append(f.id[k]).append(" ").append(Math.round(f.x[k] * p.resX)).append(",").append(Math.round(f.y[k] * p.resY)).append("]");
                        System.out.println(w + "  map=" + idToReal);
                    }
                    for (int k = 0; k < Engine.MAX_SLOTS && failure == null; k++) {
                        if (f.id[k] < 0) continue;
                        exposedTotal++;
                        // all real fingers at exactly this position (two can coincide by chance)
                        List<Integer> at = new ArrayList<>();
                        for (int i = 0; i < SLOTS; i++)
                            if (active[i] && f.x[k] == x[i] / p.resX && f.y[k] == y[i] / p.resY) at.add(tid[i]);
                        if (at.isEmpty()) { failure = "reported finger is not at any real finger's position"; break; }
                        if (f.id[k] < Engine.ADOPTED_ID && !at.contains(f.id[k])) { failure = "real tracking id reported for the wrong finger"; break; }
                        // The real finger behind a reported id must stay the same: keep the set of
                        // fingers it could be and narrow it down frame by frame.
                        Set<Integer> cand = idToReal.get(f.id[k]);
                        if (cand == null) idToReal.put(f.id[k], new HashSet<>(at));
                        else cand.retainAll(at);
                        if (cand != null && cand.isEmpty()) { failure = "reported id moved to a different real finger"; break; }
                    }
                    // The click state is only known once the kernel reported it after the grab.
                    if (failure == null && btnKnown && f.button != btn) failure = "button state wrong";
                }
            }
            if (trace) System.out.println("RESULT: " + failure);
            if (failure != null) {
                counts.merge(failure, 1, Integer::sum);
                example.putIfAbsent(failure, seed);
            }
        }
        System.out.printf("parser fuzz: %d sequences, %d frames, %d reported fingers checked%n", seqs, frames, exposedTotal);
        if (counts.isEmpty()) System.out.println("RESULT: no violations");
        for (Map.Entry<String, Integer> e : counts.entrySet())
            System.out.println("VIOLATION x" + e.getValue() + " (e.g. seed " + example.get(e.getKey()) + "): " + e.getKey());
    }
}
