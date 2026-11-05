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
    /** Valley field: distance to nearest river (65535 = none), its bed height ×16, width ×10, meander ×4, relief /2. */
    public char[] vd;
    public short[] vz;
    public byte[] vw, va, vr;
    /** Rock erodibility ×255 per cell (0 = not this plate's land). */
    public byte[] cer;
    /** Lake level per cell (negative = salt flat). */
    public Map<Integer, Double> lakes;
    /** Water level at the exit node towards the downstream basin (NaN if the basin drains to the sea). */
    public float exitLevel = Float.NaN;
    /** Approximate retained size, for diagnostics. */
    public long bytes;
}
