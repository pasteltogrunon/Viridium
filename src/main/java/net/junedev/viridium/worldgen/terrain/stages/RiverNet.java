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
