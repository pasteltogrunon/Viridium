package net.junedev.viridium.worldgen.nether;

import static net.junedev.viridium.worldgen.nether.NetherLayout.TIERS;

/**
 * Everything the cube filler needs about one 16×16 chunk column of the Nether, computed once ({@link NetherPlanner})
 * and cached: per tier and column, the final floor and ceiling, routes, lava, domain weights and ecosystem; per chunk,
 * the chimneys, core landmarks and lava falls that reach it. Arrays are indexed {@code [tier][lz * 16 + lx]}.
 */
public final class NetherPlan {

    /** Flags. */
    public static final byte LAVA_ON = 1, FISSURE = 2, OLD_MARK = 4, GARDEN = 8, CORRIDOR = 16;

    public final int chunkX, chunkZ;

    /** First void Y and first rock Y above it (equal: closed rock). */
    public final int[][] floor = new int[TIERS][256], ceil = new int[TIERS][256];
    /**
     * Bridges: when a route crosses a low place, rock fills [deck, floor) and the void continues below, from under up
     * to deck. Without a bridge, under == deck == floor.
     */
    public final int[][] under = new int[TIERS][256], deck = new int[TIERS][256];
    /** Route on this column: distance to its axis (huge when none), grade, distance along it, half width. */
    public final float[][] hwDist = new float[TIERS][256], hwGrade = new float[TIERS][256],
        hwAlong = new float[TIERS][256], hwHalf = new float[TIERS][256];
    public final byte[][] flags = new byte[TIERS][256], eco = new byte[TIERS][256], kind = new byte[TIERS][256];
    /** Transformation weights {@code [tier][transform][column]} (scaled by intensity) and intensity. */
    public final float[][][] w = new float[TIERS][Transform.COUNT][256];
    public final float[][] intensity = new float[TIERS][256];
    /** Normalised distance to the primary core, and that core. */
    public final float[][] coreN = new float[TIERS][256];
    public final NetherCore[][] core = new NetherCore[TIERS][256];
    /** Cavity mask (1 inside a core cavity of this tier). */
    public final float[][] cavity = new float[TIERS][256];
    /** Vertical shift of the strata (tilt and folds). */
    public final float[][] strata = new float[TIERS][256];
    /** Boundaries between tiers (middle of each slab). */
    public final int[][] mid = new int[TIERS - 1][256];
    /** Distance to the nearest fracture, half width of the abyss here (0 none), quartz richness. */
    public final float[] fracDist = new float[256], abyss = new float[256], quartz = new float[256];
    public final float[][] activity = new float[TIERS][256];

    public NetherRoutes.Chimney[] chimneys = new NetherRoutes.Chimney[0];
    public NetherCore[] landmarks = new NetherCore[0];
    /** Lava falls: {x, z, tier} triples. */
    public double[] falls = new double[0];

    public NetherPlan(int chunkX, int chunkZ) {
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
    }

    public boolean has(int t, int i, byte flag) {
        return (flags[t][i] & flag) != 0;
    }

    /** Tier whose band holds y in column i. */
    public int tierAt(int i, int y) {
        return y < mid[0][i] ? 0 : y < mid[1][i] ? 1 : 2;
    }

}
