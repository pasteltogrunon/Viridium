package net.junedev.viridium.worldgen.terrain.pipeline;

import java.util.List;

import net.junedev.viridium.worldgen.terrain.stages.VolcanismStage;

/**
 * Per-thread scratch buffers used by the stages (the module-level temporaries of worldgen.js). Obtained from
 * {@link net.junedev.viridium.worldgen.terrain.WorldGen#scratch()}; never cache it across threads.
 */
public final class GenScratch {

    // plates: the 5x5 neighbourhood of feature points
    public final double[] FX = new double[25], FY = new double[25], PW = new double[25];
    public final int[] FI = new int[25], FJ = new int[25];
    /** Output of plate velocity lookups. */
    public double VX, VY;

    // geology: squared distances to the 3x3 rock patches
    public final double[] RD = new double[9];

    // helper points for nested evaluations (height lookups, hotspot resolution, land probes)
    public final GenPoint heightPoint = new GenPoint();
    public final GenPoint hotspotPoint = new GenPoint();
    public final GenPoint landPoint = new GenPoint();
    /** Skip the river networks (map overviews too far out for rivers to show): the rivers stage leaves no water. */
    public boolean riversOff;
    /** Blocks per pixel of the current evaluation (1 in game): coarse caches step aside when it outgrows them. */
    public double bpp = 1;
    /**
     * Map previews: use only river networks already built (never build one, or anything that would build one); the
     * river stage sets {@link #riversIncomplete} when it skipped a network that shapes the point. Reset by the caller.
     */
    public boolean riversCachedOnly, riversIncomplete;
    /**
     * Map previews: coarse height lattice of the tile being drawn ({@code heightAt(x, z, false)} on a square grid of
     * {@code latticeN}² nodes from (latticeX0, latticeZ0), spacing latticeStep), from which the erosion gradient is
     * taken
     * instead of 4 heightAt calls per pixel. Null in game; set and cleared (in a finally) by the map renderer.
     */
    public float[] reliefLattice;
    public double latticeX0, latticeZ0, latticeStep;
    public int latticeN;
    /** Plate stage: distance to each neighbour's bisector, segment distances. */
    public final double[] LD = new double[25], SEGD = new double[25];
    /**
     * Plate stage cache of the last 5x5 neighbourhood (FX, FY, FI, FJ above): valid while {@code plateOwner} (the id of
     * the PlateStage, an int: a reference would keep a dead WorldGen alive through every thread-local scratch) and
     * (plateCi, plateCj) match. Velocities and pair values (index a*25+b) are filled lazily; an entry is valid when its
     * stamp equals {@code plateStamp}. Everything here depends only on the cell, so it never changes the output. The
     * arrays (~50 KiB) are allocated on first use ({@link #plateCache}): most scratches never evaluate plates.
     */
    public int plateOwner, plateCi, plateCj, plateStamp;
    public double[] PVX, PVY;
    public int[] PVOK;
    /** Pair geometry: unit normal a→b, length, bisector midpoint. */
    public double[] PNX, PNY, PLEN, PMX, PMY;
    public int[] PGOK;
    /** Pair dynamics: convergence, collision, override sign. */
    public double[] PCONV;
    public boolean[] PCOLL;
    public int[] POVER, PDOK;
    /**
     * Clip interval of the Voronoi edge (bk, k) along its bisector, from the midpoint (NaN: the plates do not touch).
     */
    public double[] PTMIN, PTMAX;
    public int[] PSOK;

    /** Allocates the plate cache arrays (first plate evaluation with this scratch). */
    public void plateCache() {
        if (PVX != null) return;
        PVX = new double[25];
        PVY = new double[25];
        PVOK = new int[25];
        PNX = new double[625];
        PNY = new double[625];
        PLEN = new double[625];
        PMX = new double[625];
        PMY = new double[625];
        PGOK = new int[625];
        PCONV = new double[625];
        PCOLL = new boolean[625];
        POVER = new int[625];
        PDOK = new int[625];
        PTMIN = new double[625];
        PTMAX = new double[625];
        PSOK = new int[625];
    }

    /** Small output buffer for field lookups. */
    public final double[] tmp1 = new double[1];
    /** River stage: nearby basin cells (BasinGraph.nearCells) and their networks. */
    public final double[] NCX = new double[25], NCY = new double[25];
    public final long[] NCK = new long[9];
    public final net.junedev.viridium.worldgen.terrain.stages.RiverNet[] NETS = new net.junedev.viridium.worldgen.terrain.stages.RiverNet[9];

    // erosion: last gully value and gradient
    public double EV, EGX, EGY;
    /**
     * Climate style of the erosion at the current point: depth multiplier, crest exponent, octave gain, valley fill.
     */
    public double ECD, ECP, ECG, ECF;
    /** Gradient output of {@code HeightGrid.gradient}. */
    public final double[] GRAD = new double[2];

    // rivers: cubic B-spline weights and the sampled network field
    public final double[] BWX = new double[4], BWY = new double[4];
    public double FD, FZ, FW, FA, FR, FE, FEW, FQ, FS = 1;
    public boolean FOK;

    // volcanism: hotspots near the last evaluated hotspot cell
    public boolean hotValid;
    public long hotKey;
    public List<VolcanismStage.Hotspot> hotList;
}
