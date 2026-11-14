package net.junedev.viridium.worldgen.nether;

/**
 * Vertical layout of the Viridium Nether: one place for every absolute-Y constant.
 *
 * <p>
 * The volume is organised in three broad <b>tiers</b> (deep, middle, high). Each tier is a sheet of caverns between a
 * floor and a ceiling, deformed by the substrate (tilt, folds) and separated from the next one by a slab of rock that
 * chimneys, abysses and the core cavities cross. Above and below a place there are other relevant places, and their
 * relation is part of the geography (design document §3, §12.1).
 */
public final class NetherLayout {

    /** Lowest generated Y (bedrock floor below). Multiple of 16. */
    public static final int MIN_Y = -256;
    /** Highest generated Y (exclusive; bedrock roof just under it). Multiple of 16. */
    public static final int MAX_Y = 512;
    /** Thickness of the bedrock floor and roof. */
    public static final int BEDROCK = 4;

    public static final int TIERS = 3;
    /** Nominal floor of each tier (before tilt, relief and basins). */
    public static final int[] FLOOR = { -184, 37, 269 };
    /** Nominal ceiling of each tier. */
    public static final int[] CEIL = { -44, 200, 436 };
    /**
     * Lava level of each tier: supported air at or under it is lava. One flat level per tier, so a sea is always
     * contained by its own floor (the floor rises above the level around it) and never hangs over a void.
     */
    public static final int[] LAVA = { -182, 28, 260 };
    /** Level of the former seas of extinct systems: a band of crust left on every wall of the tier (§13.4). */
    public static final int[] OLD_LAVA = { -166, 44, 276 };
    /** Lowest grade a route may have in each tier (always walkable above the lava). */
    public static final int[] DRY = { -179, 31, 263 };

    private NetherLayout() {}

    /** Middle of the slab between tier t and t + 1 (nominal). */
    public static int slabMid(int t) {
        return (CEIL[t] + FLOOR[t + 1]) / 2;
    }
}
