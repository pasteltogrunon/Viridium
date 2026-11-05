package net.junedev.viridium.worldgen.terrain.column;

/** Vertical layout of the Viridium world. One place for every absolute-Y constant. */
public final class TerrainLayout {

    /** Water fills every non-solid block with Y &lt; SEA_LEVEL; terrain height 0 sits at this Y. */
    public static final int SEA_LEVEL = 63;
    /** Lowest generated Y (bottom of the deep crust). Multiple of 16. */
    public static final int MIN_Y = -2048;
    /** Highest generated Y. Multiple of 16. */
    public static final int MAX_Y = 1024;
    /** Deepest block the surface rules can paint below the terrain surface. */
    public static final int SURFACE_RULE_DEPTH = 10;

    private TerrainLayout() {}

    /** Absolute Y of the highest solid block for a terrain height relative to sea level. */
    public static int surfaceY(double height) {
        return (int) Math.ceil(SEA_LEVEL + height) - 1;
    }
}
