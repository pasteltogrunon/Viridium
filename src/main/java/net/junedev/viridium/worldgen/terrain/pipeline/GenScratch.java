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
    /** Plate stage: distance to each neighbour's bisector, whether it is a non-collisional boundary, segment distances. */
    public final double[] LD = new double[25], SEGD = new double[25];
    public final boolean[] LNC = new boolean[25], LDONE = new boolean[25];

    /** Small output buffer for field lookups. */
    public final double[] tmp1 = new double[1];

    // erosion: last gully value and gradient
    public double EV, EGX, EGY;

    // rivers: cubic B-spline weights and the sampled network field
    public final double[] BWX = new double[4], BWY = new double[4];
    public double FD, FZ, FW, FA, FR, FE, FEW;
    public boolean FOK;

    // volcanism: hotspots near the last evaluated hotspot cell
    public boolean hotValid;
    public long hotKey;
    public List<VolcanismStage.Hotspot> hotList;
}
