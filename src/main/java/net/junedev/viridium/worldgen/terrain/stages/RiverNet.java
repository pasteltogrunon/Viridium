package net.junedev.viridium.worldgen.terrain.stages;

import java.util.Map;

/**
 * Pre-generated drainage network of one drainage basin cell (see {@link BasinGraph}); rivers coming from upstream basins
 * enter through the shared divide node with their whole flow. Immutable once built.
 */
public final class RiverNet {

    public static final int SEG = 8, NPT = SEG + 1, HALF = SEG >> 1, MS = 14;

    /** Basin cell this network belongs to. */
    public int ci, cj;
    /** Grid origin (blocks), cell size and size. */
    public double x0, y0;
    public int g, nx, ny;
    /** Number of river cells (reaches). */
    public int count;
    /** Reach index of each grid cell, or -1. */
    public int[] slot;
    /** Reach polylines: NPT points (x, y) per reach; bounding boxes (4 per reach). */
    public float[] pts, bb;
    /** Half-width, bed height, accumulated flow (×threshold) at both ends; head-water flag. */
    public float[] w0, w1, z0, z1, acc;
    public byte[] head;
    /** Distance downstream to the mouth along the main stem at both ends. */
    public float[] am0, am1;
    /** How far a curve strays from its cell (search radius in queries). */
    public double reach;
    /** River mouths, MS floats each (x, y, ux, uy, deltaR, hw, flow, cell, estuary, Lest, Wm, reach, estStart, estN). */
    public float[] mouths;
    public int nMouth;
    /** Reaches that belong to estuaries. */
    public int[] estSeg;
    /**
     * Delta distributaries: vertices (x, y, half-width, water level, distance to the branch tip or 1e9), {@link #DV}
     * floats each; channels as (first vertex, vertex count) pairs; per mouth its channels (first, count) and its box
     * (x0, y0, x1, y1, plain radius).
     */
    public float[] dv = new float[0], dbox = new float[0];
    public int[] dch = new int[0], dmc = new int[0];
    public static final int DV = 5;
    /** Reaches replaced by a delta (the main stem between the delta apex and the old mouth). */
    public byte[] skip;
    /**
     * Waterfalls: drop (blocks) at the start of a reach that begins at the foot of a fall (0 none), and 1 for a reach that
     * ends at the lip of one (the next reach, or the sea, lies below it).
     */
    public float[] fallIn = new float[0];
    // lipOut: 1 the next reach lies below, 2 the sea lies below
    public byte[] lipOut = new byte[0];
    /** 1 for a reach whose node lies in a river lake (its water is the lake's: no banks of its own), else 0. */
    public byte[] inLake = new byte[0];
    /** River lakes of this network (lakes a river flows through, full to their outlet). */
    public int riverLakes;
    /** Valley field: distance to nearest river (65535 = none), its bed height ×16, width ×10, meander ×4, relief /2. */
    public char[] vd;
    public short[] vz;
    public byte[] vw, va, vr;
    /** Rock erodibility ×255 per cell (0 = not this plate's land). */
    public byte[] cer;
    /** Lake level per cell (negative = salt flat). */
    public Map<Integer, Double> lakes;
    /** The same lakes as sorted cell indices and levels: looked up per point without boxing. */
    public int[] lakeIdx = new int[0];
    public double[] lakeLv = new double[0];

    /**
     * Channel search index: the grid in buckets of {@link #CB}x{@link #CB} cells; bucket b lists, in [chStart[b],
     * chStart[b + 1]) of chCells, the cells holding a reach that a point in the bucket may have to measure (a reach
     * within the search window of one of its cells whose padded box meets the bucket), in grid order. Null: none.
     */
    public int[] chStart, chCells;
    public int chNbx;
    public static final int CB = 4;

    /** Search window (cells) of the channel search around a point's cell (as in RiverStage.applyNet). */
    int channelWindow() {
        double VMAX = 2 * g;
        return (int) Math.ceil((VMAX + 24 + reach) / g);
    }

    /** Builds {@link #chStart} / {@link #chCells} (after every reach field and {@link #reach} are set). */
    void buildChannelIndex() {
        if (count == 0) return;
        final int RC = channelWindow();
        final double VMAX = 2 * g;
        int nbx = (nx + CB - 1) / CB, nby = (ny + CB - 1) / CB, nb = nbx * nby;
        int[] start = new int[nb + 1];
        int[] tmpB = new int[64], tmpC = new int[64];
        int nt = 0;
        for (int idx = 0; idx < nx * ny; idx++) { // grid order: each bucket's list comes out sorted
            int sl = slot[idx];
            if (sl < 0 || skip[sl] != 0) continue;
            int ci = idx % nx, cj = idx / nx;
            double pad = VMAX + RiverStage.CHANNEL_WIDEN * Math.max(w0[sl], w1[sl]);
            double bx0 = bb[sl * 4] - pad - 1, by0 = bb[sl * 4 + 1] - pad - 1, bx1 = bb[sl * 4 + 2] + pad + 1,
                by1 = bb[sl * 4 + 3] + pad + 1;
            int b0 = Math.max(0, Math.floorDiv(ci - RC, CB)), b1 = Math.min(nbx - 1, Math.floorDiv(ci + RC, CB));
            int c0 = Math.max(0, Math.floorDiv(cj - RC, CB)), c1 = Math.min(nby - 1, Math.floorDiv(cj + RC, CB));
            for (int by = c0; by <= c1; by++) {
                double Y0 = y0 + (double) by * CB * g, Y1 = y0 + (double) (by + 1) * CB * g;
                if (by1 < Y0 || by0 > Y1) continue;
                for (int bx = b0; bx <= b1; bx++) {
                    double X0 = x0 + (double) bx * CB * g, X1 = x0 + (double) (bx + 1) * CB * g;
                    if (bx1 < X0 || bx0 > X1) continue;
                    if (nt == tmpB.length) {
                        tmpB = java.util.Arrays.copyOf(tmpB, nt * 2);
                        tmpC = java.util.Arrays.copyOf(tmpC, nt * 2);
                    }
                    tmpB[nt] = by * nbx + bx;
                    tmpC[nt++] = idx;
                    start[by * nbx + bx + 1]++;
                }
            }
        }
        for (int b = 0; b < nb; b++) start[b + 1] += start[b];
        int[] cells = new int[nt], fill = java.util.Arrays.copyOf(start, nb);
        for (int t = 0; t < nt; t++) cells[fill[tmpB[t]]++] = tmpC[t];
        chNbx = nbx;
        chStart = start;
        chCells = cells;
    }

    /** Lake level of a cell (negative = salt flat), or NaN. */
    public double lakeAt(int idx) {
        int k = java.util.Arrays.binarySearch(lakeIdx, idx);
        return k >= 0 ? lakeLv[k] : Double.NaN;
    }
    /** Water level at the exit node towards the downstream basin (NaN if the basin drains to the sea). */
    public float exitLevel = Float.NaN;
    /** Flow (runoff units) that reaches the exit node: what the downstream basin receives. */
    public float exitFlow;
    /** Approximate retained size, for diagnostics. */
    public long bytes;
}
