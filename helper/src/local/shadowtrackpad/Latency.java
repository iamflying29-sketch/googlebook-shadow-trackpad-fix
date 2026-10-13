package local.shadowtrackpad;

/** Running latency statistics (microseconds), cheap enough to record on every frame. */
final class Latency {
    private final String name;
    private long count, sum, max;
    private final long[] buckets = new long[8]; // <0.5, <1, <2, <4, <8, <16, <32, >=32 ms

    Latency(String name) {
        this.name = name;
    }

    synchronized void add(long us) {
        if (us < 0) us = 0;
        count++;
        sum += us;
        if (us > max) max = us;
        long[] edges = {500, 1000, 2000, 4000, 8000, 16000, 32000};
        int b = 0;
        while (b < edges.length && us >= edges[b]) b++;
        buckets[b]++;
    }

    synchronized void reset() {
        count = sum = max = 0;
        java.util.Arrays.fill(buckets, 0);
    }

    /** Smallest bucket edge that covers at least 99% of samples. */
    private String p99() {
        String[] labels = {"<0.5", "<1", "<2", "<4", "<8", "<16", "<32", ">=32"};
        long need = (long) Math.ceil(count * 0.99), acc = 0;
        for (int i = 0; i < buckets.length; i++) {
            acc += buckets[i];
            if (acc >= need) return labels[i];
        }
        return "?";
    }

    @Override
    public synchronized String toString() {
        if (count == 0) return name + ": no samples yet";
        return String.format("%s: n=%d avg=%.2f ms, 99%% %s ms, max=%.2f ms", name, count, sum / 1000.0 / count, p99(), max / 1000.0);
    }
}
