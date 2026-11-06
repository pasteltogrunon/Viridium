package net.junedev.viridium.compat.dh;

import static net.junedev.viridium.worldgen.terrain.column.ColumnPlan.NONE;

import net.junedev.viridium.worldgen.terrain.column.ColumnPlan;

/**
 * Geometry of one LOD column: a short stack of uniform vertical runs built from a single {@link ColumnPlan} slot, with
 * the same layering {@code CubeFiller} uses (crust rock, surface-rule "under" layer, top block, water, ice, snow). Runs
 * carry a material {@link #kind}; turning kinds into blocks is the caller's job, which keeps this class free of
 * Minecraft and Distant Horizons types (testable on its own).
 * <p>
 * Runs are stored bottom-up, contiguous, clipped to {@code [minY, maxY)} and end with an air run when there is room.
 * A column whose ground lies below {@code minY} keeps a one-block floor at {@code minY}, so seas deeper than the LOD
 * range still read as sea floor under water.
 */
public final class LodColumn {

    public static final int ROCK = 0, UNDER = 1, TOP = 2, WATER = 3, ICE = 4, PACKED_ICE = 5, SNOW = 6, AIR = 7,
        LOG = 8, LEAVES = 9;
    public static final int MAX_RUNS = 32;

    public int size;
    public final int[] bottom = new int[MAX_RUNS], top = new int[MAX_RUNS], kind = new int[MAX_RUNS],
        skyLight = new int[MAX_RUNS];
    /** Exclusive top of the ground (first Y above the top block). */
    public int solidTop;

    private int logTop = NONE, leafBottom = NONE, leafTop = NONE;

    /**
     * Adds a tree to the next {@link #build}: a trunk from the ground up to {@code logTop} (exclusive) and a crown
     * filling {@code [leafBottom, leafTop)}; NONE for a missing part. Parts below the ground or water are clipped.
     */
    public LodColumn tree(int logTop, int leafBottom, int leafTop) {
        this.logTop = logTop;
        this.leafBottom = leafBottom;
        this.leafTop = leafTop;
        return this;
    }

    /**
     * @param underDepth blocks of the "under" layer below the top block (0 = none)
     * @param splitUnder one run per block in the under layer (for materials that vary with Y, like clay bands)
     * @param snow       a snow layer on dry ground
     */
    public LodColumn build(ColumnPlan plan, int i, int minY, int maxY, int underDepth, boolean splitUnder,
        boolean snow) {
        size = 0;
        int sy = plan.surfaceY[i];
        int water = plan.waterY[i], iceTop = plan.iceTop[i], iceBottom = plan.iceBottom[i];
        boolean wet = water != NONE || iceTop != NONE;

        // ground
        int st = solidTop = Math.min(Math.max(sy + 1, minY + 1), maxY);
        int underBottom = Math.max(st - 1 - underDepth, minY);
        add(minY, underBottom, ROCK);
        if (splitUnder) for (int y = underBottom; y < st - 1; y++) add(y, y + 1, UNDER);
        else add(underBottom, st - 1, UNDER);
        add(st - 1, st, TOP);

        // water and ice above it (floes and bergs float inside the water)
        int y = st;
        int waterTop = water == NONE ? NONE : clamp(water + 1, minY, maxY);
        if (iceTop != NONE) {
            boolean packed = plan.has(i, ColumnPlan.F_SHELF) || plan.has(i, ColumnPlan.F_BERG);
            int ib = clamp(Math.max(iceBottom, y), minY, maxY), it = clamp(iceTop + 1, minY, maxY);
            y = add(y, ib, WATER); // sea under the ice (or filling the gap under a floe)
            y = add(y, it, packed ? PACKED_ICE : ICE);
        }
        boolean tree = logTop != NONE || leafTop != NONE;
        if (waterTop != NONE) y = add(y, waterTop, WATER);
        else if (iceTop == NONE && snow && !tree && y < maxY) y = add(y, y + 1, SNOW);

        // a tree: trunk, the gap under the crown, the crown, and a trunk tip poking out of it
        if (tree) {
            int lt = logTop == NONE ? NONE : clamp(logTop, minY, maxY);
            int fb = leafTop == NONE ? NONE : clamp(leafBottom, minY, maxY);
            int ft = leafTop == NONE ? NONE : clamp(leafTop, minY, maxY);
            if (lt != NONE) y = add(y, fb == NONE ? lt : Math.min(lt, fb), LOG);
            if (ft != NONE) {
                y = add(y, fb, AIR);
                y = add(y, ft, LEAVES);
            }
            if (lt != NONE) y = add(y, lt, LOG);
            logTop = leafBottom = leafTop = NONE;
        }

        add(y, maxY, AIR);
        // the top block sees the sky directly when dry, through water otherwise
        for (int k = 0; k < size; k++)
            skyLight[k] = kind[k] == ROCK || kind[k] == UNDER ? 0 : kind[k] == TOP && wet ? 12 : 15;
        return this;
    }

    /** Appends [from, to) when not empty, merging with an identical run below; returns the column's new top. */
    private int add(int from, int to, int k) {
        if (to <= from) return from;
        int n = size;
        if (n > 0 && kind[n - 1] == k && k != UNDER && top[n - 1] == from) {
            top[n - 1] = to;
            return to;
        }
        if (n == MAX_RUNS) throw new IllegalStateException("LOD column has too many runs");
        bottom[n] = from;
        top[n] = to;
        kind[n] = k;
        size = n + 1;
        return to;
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
