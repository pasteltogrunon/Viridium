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

    // 7. relief
    /** Base height and current height (blocks relative to sea level). */
    public double h0, h;
    public double mtnMask;
    /** Volcanic share and type (0 none, 1 arc, 2 hotspot chain, 3 Iceland-like, 4 atoll). */
    public double volc;
    public int volcType;

    // 6. geology
    /** Dominant and secondary surface rock ordinals ({@code Rock}), weight of the dominant one, erodibility. */
    public int rock, rock2;
    public double rockT = 1, erod;
}
