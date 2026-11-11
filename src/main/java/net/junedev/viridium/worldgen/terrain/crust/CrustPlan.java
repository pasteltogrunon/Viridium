package net.junedev.viridium.worldgen.terrain.crust;

/**
 * Crust profile of the 256 columns of a chunk, in absolute Y. Written once by {@link CrustModel#planColumn}, then
 * read-only. Index = {@code localZ * 16 + localX}.
 */
public final class CrustPlan {

    public static final int FLAG_RIDGE = 1, FLAG_BLUESCHIST = 2, FLAG_ECLOGITE_ROOT = 4, FLAG_ATOLL = 8, FLAG_ARID = 16,
        FLAG_WARM = 32, FLAG_DIKE = 64, FLAG_PLUTONS = 128;

    final byte[] province = new byte[256];
    final byte[] flags = new byte[256];
    /** Top of the crystalline basement (bottom of the cover). */
    final short[] basementTop = new short[256];
    /** Top of the middle and lower crust; Moho. */
    final short[] midTop = new short[256], lowerTop = new short[256], moho = new short[256];
    /** Strata: vertical offset (folding/dip) and unit thickness. */
    final float[] fold = new float[256];
    final byte[] strataT = new byte[256];
    /** Region cell (for strata sequences) and surface rock bias. */
    final int[] region = new int[256];
    final byte[] bias = new byte[256];
    /** Basement and pluton lithologies. */
    final byte[] upper = new byte[256], pluton = new byte[256];
    /** Kimberlite pipe: horizontal distance to the axis (NaN = none) and radius at depth. */
    final float[] pipeDist = new float[256], pipeR = new float[256];
    /** Surface Y (copied from the column plan). */
    final short[] surface = new short[256];

    public Province province(int i) {
        return Province.VALUES[province[i]];
    }

    public int basementTop(int i) {
        return basementTop[i];
    }

    public int moho(int i) {
        return moho[i];
    }

    /** Lowest Y at which this column can still change lithology by cover/zone (for uniform-run fast paths). */
    public int surface(int i) {
        return surface[i];
    }
}
