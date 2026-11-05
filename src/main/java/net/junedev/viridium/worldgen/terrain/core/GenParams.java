package net.junedev.viridium.worldgen.terrain.core;

/**
 * Tunable parameters of the terrain pipeline. Defaults match {@code DEFAULTS} in worldgen.js. Distances are in blocks;
 * heights are blocks relative to sea level.
 *
 * <p>
 * Instances are treated as immutable once handed to a {@link net.junedev.viridium.worldgen.terrain.WorldGen}: copy
 * and modify, then build a new generator.
 */
public final class GenParams implements Cloneable {

    public int seed = 12345;

    // climate
    public double tempPeriod = 48000; // full equator→pole→equator cycle along z (prototype: 16000)
    public double precipPeriod = 36000; // full wet→dry cycle along x (prototype: 12000)

    // tectonics
    public double plateSize = 12000; // continents are one per plate (prototype: 4000)
    public double plateWarp = 0.28;
    public double continentSize = 0.55;
    public double ridgeStrength = 1.0;
    public double mountainStrength = 1.0;
    public double collisionChance = 0.5;
    public double collisionHeight = 1.0;
    public double islands = 1.0;
    public double microChance = 0.35;
    public double hotspotChance = 0.55;

    // geology
    public double rockPatch = 420;

    // later stages (ported in following phases)
    public double lapse = 0.07;
    public double erosionStrength = 1.0;
    public double erosionScale = 96;
    public double riverThreshold = 1.0;
    public int riverGrid = 32;
    /** Size (blocks) of the drainage basin cells that divide the continents. */
    public double basinSize = 3000;
    /** How strongly cells converge into large basins (0 = each cell runs straight to the nearest coast). */
    public double basinMerge = 0.8;
    /** Upper bound of a river half-width (blocks) and of a delta radius: big basins make huge mouths. */
    public double riverMaxHalfWidth = 48, deltaMaxRadius = 1600;
    /**
     * Routing noise (blocks): biases the flow towards gentle noise valleys so that water converges into few rivers
     * instead of running in parallel down smooth slopes and flats (one river every grid node).
     */
    public double riverRouteNoise = 8;
    /** Flow (in threshold units) at which a river starts: higher = fewer, longer tributaries (width does not change). */
    public double riverStart = 1.6;
    public double biomeSharpness = 3;
    public double monsoon = 1.0;
    public double deltaSize = 1.0;
    public double estuaries = 1.0;
    public double meanders = 1.0;
    public double valleyWidth = 1.0;
    public double currents = 1.0;
    public double glacialCooling = 9;
    public double lakes = 1.0;
    public double valleyRock = 1.0;

    // implementation
    /**
     * Use cached coarse fields for the neighbourhood samples of the climate and erosion stages (much faster, tiny
     * differences). False = exact port of the prototype (parity tests).
     */
    public boolean fastFields = true;

    public GenParams copy() {
        try {
            return (GenParams) clone();
        } catch (CloneNotSupportedException e) {
            throw new AssertionError(e);
        }
    }

    /** Folds a Minecraft 64-bit world seed into the 32-bit seed used by the noise. */
    public static int foldSeed(long worldSeed) {
        return (int) (worldSeed ^ (worldSeed >>> 32));
    }
}
