package net.junedev.viridium.worldgen.terrain;

import java.util.concurrent.atomic.LongAdder;

/**
 * Cheap global counters of where generation time goes (plans, cube fills, population, river networks). Read with
 * {@code /viridium stats}; thread-safe, no locks.
 */
public final class GenStats {

    /** A counter with total time. */
    public static final class Timer {

        public final String name;
        private final LongAdder count = new LongAdder(), nanos = new LongAdder();
        private volatile long max;

        Timer(String name) {
            this.name = name;
        }

        public void add(long ns) {
            count.increment();
            nanos.add(ns);
            if (ns > max) max = ns; // racy but fine for diagnostics
        }

        public long count() {
            return count.sum();
        }

        public double totalMs() {
            return nanos.sum() / 1e6;
        }

        public double meanMs() {
            long c = count();
            return c == 0 ? 0 : totalMs() / c;
        }

        public double maxMs() {
            return max / 1e6;
        }

        void reset() {
            count.reset();
            nanos.reset();
            max = 0;
        }
    }

    public static final Timer PLAN = new Timer("column plan (computed)");
    public static final Timer PREFETCH = new Timer("column plan prefetched (background)");
    public static final Timer PLAN_WAIT = new Timer("column plan wait on server thread");
    public static final Timer FILL_AIR = new Timer("cube fill: air");
    public static final Timer FILL_CRUST = new Timer("cube fill: crust only");
    public static final Timer FILL_MIXED = new Timer("cube fill: surface");
    public static final Timer FILL_BEDROCK = new Timer("cube fill: bedrock");
    public static final Timer POPULATE = new Timer("populate (total)");
    public static final Timer FLORA = new Timer("vegetation drawn into cubes");
    public static final Timer FLORA_FEATURES = new Timer("vegetation features drawn (parts)");
    public static final Timer RIVER_NET = new Timer("river network build");

    public static final Timer[] ALL = { PLAN, PREFETCH, PLAN_WAIT, FILL_AIR, FILL_CRUST, FILL_MIXED, FILL_BEDROCK, POPULATE, FLORA, FLORA_FEATURES, RIVER_NET };

    private GenStats() {}

    public static String report() {
        StringBuilder sb = new StringBuilder();
        for (Timer t : ALL) {
            if (t.count() == 0) continue;
            sb.append(String.format("%-38s %7d × %8.3f ms (max %7.1f) = %9.0f ms%n", t.name, t.count(), t.meanMs(),
                t.maxMs(), t.totalMs()));
        }
        return sb.length() == 0 ? "no generation recorded yet\n" : sb.toString();
    }

    public static void reset() {
        for (Timer t : ALL) t.reset();
    }
}
