package net.junedev.viridium.worldgen.terrain.pipeline;

/**
 * Mutable result of evaluating the pipeline at one (x, z) position. One instance per thread is reused; never share an
 * instance between threads. Field names follow worldgen.js (its "y" axis is Minecraft's z axis).
 */
public final class GenPoint {

    public double x, z;

    // 1-2. base climate
    /** Base temperature (°C) and precipitation (mm/year), before relief. */
    public double Tb, Pb;
    /** Equivalent latitude in radians (0 equator .. π/2 pole) and poleward direction sign (≈ hemisphere). */
    public double lat, pole;
    /** General circulation wind and final wind. */
    public double windX, windY, wfx, wfy;
    /** Final temperature and precipitation (equal to the base ones until the climate stage runs). */
    public double T, P;

    // 3-4. plates and boundaries
    /** Owning plate cell and its feature point; warped position. */
    public int pi, pj;
    public double fx, fy, wx, wy;
    /** Distance to the nearest boundary, and to the nearest non-collisional boundary. */
    public double edge, edgeNC;
    /** Plate across the nearest boundary. */
    public int ni, nj;
    public double conv, ridge, orog, trench, arc;
    /** Continental collision: zone weight, intensity, signed distance to the suture, collision relief share. */
    public double collZ, collK, collS, collR;

    // 5. continents
    /** Continentality: &gt; 0 land, &lt; 0 sea. */
    public double L;

    /**
     * Continental rift: strength along the rift line (0 beyond its ends), distance across it in half-widths (&lt; 1
     * inside
     * the graben), position along it (blocks) and stage (0 young with lakes, 1 failed and worn down, 2 mature: a narrow
     * sea).
     */
    public double rift, riftD = 9, riftU;
    public int riftType;

    // 7. relief
    /** Base height and current height (blocks relative to sea level). */
    public double h0, h;
    public double mtnMask;
    /** Plateau share (0..1, the escarpment rises across it) and type (0 uplifted tableland, 1 basalt traps). */
    public double plat;
    public int platType;
    /**
     * Nearness to an escarpment or cliff (1 on it, 0 beyond ~25 blocks): erosion leaves the scarp and its foot alone.
     */
    public double scarp;
    /** Coastal cliff share (0..1: the raised coast behind a sea cliff). */
    public double cliff;
    /** Volcanic share and type (0 none, 1 arc, 2 hotspot chain, 3 Iceland-like, 4 atoll). */
    public double volc;
    public int volcType;

    // 6. geology
    /** Dominant and secondary surface rock ordinals ({@code Rock}), weight of the dominant one, erodibility. */
    public int rock, rock2;
    public double rockT = 1, erod;

    // 8. final climate
    /** Thermal (monsoon) wind strength, relief deflection, how much land the wind crossed. */
    public double thermal, deflect, upLand;
    /** Land fraction ~800 blocks east / west. */
    public double lE, lW;
    /** Sea surface temperature anomaly from currents, upwelling, current vector. */
    public double sst, upwell, curX, curY;
    /** Rain shadow and orographic lift. */
    public double shadow, oro;

    // 9. erosion
    public double slope, gx, gy, eroDelta;
    /** Weight of the arid terracing (mesas, buttes, ledges). */
    public double mesa;

    // 10. rivers, lakes, valleys, deltas, estuaries
    public double riverD = 1e9, riverHW, riverZ, riverAcc, valley, delta, estu, lakeZ, glac, gorge;
    /** Water surface of the river channel (never above the uncarved terrain; Java-only, not in the JS). */
    public double riverWL;
    /**
     * Surface material set by a landform ({@link #SURF_NONE} = the biome decides): alluvium of river terraces, gravel
     * bars, fan deposits, beach and dune sand, reef limestone.
     */
    public int surf;
    public static final int SURF_NONE = 0, SURF_ALLUVIUM = 1, SURF_GRAVEL = 2, SURF_FAN = 3, SURF_SAND = 4,
        SURF_REEF = 5, SURF_LAVA = 6, SURF_CINDER = 7, SURF_SINTER = 8, SURF_MAT = 9, SURF_MAT2 = 10, SURF_REG = 11,
        SURF_ASH = 12, SURF_DUNE = 13;
    /** Geothermal activity (0..1): hot springs and geysers where high. */
    public double geo;
    /** Inselberg share (0..1): a dome of massive granite. */
    public double insel;
    /** River terraces: weight of the stepped valley sides (0..1). */
    public double terrace;
    /** 1 inside a river channel. */
    public int river;
    /** 0 none, 1 lake, 2 salt flat. */
    public int lake;
    /** Depth (blocks) by which a karst doline lowered the ground here. */
    public double doline;

    /** Waterfall: top of the falling water curtain (-1e9 none), and 1 within a few blocks of a waterfall lip. */
    public double fallTop = -1e9;
    public int fall;

    // 11. cryosphere
    /** 0 none, 1 floe, 2 ice shelf, 3 frozen lake. */
    public int seaIce;
    public double berg, perma, iceFrac;
    /** Visible surface height (lake level, ice, iceberg top). */
    public double hs;

    // 12. biomes
    /** Normalised weight of every {@code Biome}. */
    public final float[] biomeW = new float[64];
    /** Dominant biome ordinal and blended colour (RGB). */
    public int biome, color;
}
