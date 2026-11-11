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
    /**
     * Climate-dependent erosion (Java-only): gully depth scales with the relief, crests and valley floors follow the
     * climate and the rock. 0 = the prototype filter (fixed depth).
     */
    public double erosionClimate = 1.0;
    /** Climate erosion only: spacing of the main gullies (× erosionScale) and how strongly finer gullies branch off. */
    public double erosionWidth = 1.6, erosionBranch = 1.8, erosionGain = 0.6, erosionDepth = 0.75;
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
    /** Extra half-width per unit of flow (threshold units): large rivers get much wider than the square root alone. */
    public double riverWidthGrowth = 0.16;
    /**
     * Continents measure their distance to the real plate edges (bisectors clipped at triple junctions). Off = the
     * prototype's infinite bisector lines, which cut straight ocean wedges past triple junctions.
     */
    public boolean exactPlateEdges = true;
    /**
     * Flow (in threshold units) at which a river starts: higher = fewer, longer tributaries (width does not change).
     */
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
    /**
     * Landforms (Java-only, 0 turns each off). Plateaus: frequency of uplifted tablelands with an escarpment around
     * them
     * (scales the threshold of their mask), their height multiplier, and the terraced mesas of arid sedimentary land.
     */
    public double plateaus = 1.0, plateauHeight = 1.0, mesas = 1.0;
    /**
     * Coastal cliffs (frequency and height multiplier) and the block-level detail of every cliff: notch at its foot,
     * talus and fallen boulders (0 turns the detail off).
     */
    public double cliffs = 1.0, cliffDetail = 1.0;
    /** Continental rifts: share of continents with one (scaled 0.35 per unit) and depth multiplier of their graben. */
    public double rifts = 1.0, riftDepth = 1.0;
    /** Waterfalls where rivers cross cliffs (0 off; higher also allows lower falls). */
    public double waterfalls = 1.0;
    /**
     * River landforms (Java-only, 0 turns each off): terraces along incised valleys, oxbow lakes on meandering
     * floodplains, alluvial fans at mountain fronts, braided channels, incised meanders and slot canyons.
     */
    public double terraces = 1.0, oxbows = 1.0, fans = 1.0, braids = 1.0, incision = 1.0;
    /**
     * Coast landforms (Java-only, 0 turns each off): sea stacks off rocky cliffs, barrier islands with lagoons and
     * spits,
     * rias (drowned river valleys), coral reefs, submarine canyons on the continental slope.
     */
    public double stacks = 1.0, barriers = 1.0, rias = 1.0, reefs = 1.0, seaCanyons = 1.0;
    /**
     * Latitude in the moisture supply (Java-only, 0 = the prototype's precipitation along x only): the wet equatorial
     * belt, the dry subtropical belts of the Hadley cells (~20-32°), the wet westerlies, the dry poles.
     */
    public double hadley = 1.0;
    /**
     * Volcanoes (Java-only, 0 turns each off): stratovolcanoes and calderas on the arcs, volcanic fields (cinder cones,
     * maars, lava flows, tuyas), continental hotspots (Yellowstone), geothermal fields (hot springs, geysers), basalt
     * causeways.
     */
    public double stratovolcanoes = 1.0, volcanicFields = 1.0, continentalHotspots = 1.0, geothermal = 1.0,
        causeways = 1.0;
    /**
     * Deserts (Java-only, 0 turns each off): dune seas, yardangs, inselbergs, hoodoos, tepuis, gravel desert pavement.
     */
    public double dunes = 1.0, yardangs = 1.0, inselbergs = 1.0, hoodoos = 1.0, tepuis = 1.0, desertPavement = 1.0;
    /**
     * Caves (Java-only, 0 off): amount of noise caves (&gt; 1 more void), entrances to the surface, aquifers (flooded
     * and
     * lava cells), karst dolines and shafts, planned systems (giant chambers and galleries), ground fissures (long
     * narrow
     * cracks in the land, ~1 per 3 km² of land per unit).
     */
    public double caves = 1.0, caveEntrances = 1.0, aquifers = 1.0, dolines = 1.0, plannedCaves = 1.0, fissures = 1.0;

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
