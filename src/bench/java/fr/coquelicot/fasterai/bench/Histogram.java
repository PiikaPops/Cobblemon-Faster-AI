package fr.coquelicot.fasterai.bench;

import com.google.gson.JsonObject;

import java.util.Arrays;

/**
 * Records raw nanosecond samples and summarises them (mean, percentiles, max).
 * Raw samples are kept so the percentiles are exact, not bucket approximations.
 */
public final class Histogram {
    private long[] samples = new long[256];
    private int size;
    private long total;
    private long max;

    public void add(long nanos) {
        if (size == samples.length) samples = Arrays.copyOf(samples, size * 2);
        samples[size++] = nanos;
        total += nanos;
        if (nanos > max) max = nanos;
    }

    public int count() { return size; }
    public long totalNanos() { return total; }
    public long maxNanos() { return max; }
    public double meanMs() { return size == 0 ? 0 : total / (double) size / 1e6; }

    public double percentileMs(double p) {
        if (size == 0) return 0;
        long[] sorted = Arrays.copyOf(samples, size);
        Arrays.sort(sorted);
        int idx = (int) Math.ceil(p / 100.0 * size) - 1;
        return sorted[Math.max(0, Math.min(size - 1, idx))] / 1e6;
    }

    /** Number of samples strictly above the threshold. */
    public int countAboveMs(double thresholdMs) {
        long t = (long) (thresholdMs * 1e6);
        int n = 0;
        for (int i = 0; i < size; i++) if (samples[i] > t) n++;
        return n;
    }

    public void reset() { size = 0; total = 0; max = 0; }

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("count", size);
        o.addProperty("totalMs", round(total / 1e6));
        o.addProperty("meanMs", round(meanMs()));
        o.addProperty("p50Ms", round(percentileMs(50)));
        o.addProperty("p95Ms", round(percentileMs(95)));
        o.addProperty("p99Ms", round(percentileMs(99)));
        o.addProperty("maxMs", round(max / 1e6));
        return o;
    }

    public String summary() {
        return String.format("n=%d mean=%.3fms p50=%.3fms p95=%.3fms p99=%.3fms max=%.3fms",
                size, meanMs(), percentileMs(50), percentileMs(95), percentileMs(99), max / 1e6);
    }

    static double round(double v) { return Math.round(v * 1000.0) / 1000.0; }
}
