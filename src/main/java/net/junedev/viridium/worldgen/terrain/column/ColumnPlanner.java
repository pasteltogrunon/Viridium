package net.junedev.viridium.worldgen.terrain.column;

import static net.junedev.viridium.worldgen.terrain.column.ColumnPlan.NONE;
import static net.junedev.viridium.worldgen.terrain.column.TerrainLayout.SEA_LEVEL;

import net.junedev.viridium.worldgen.terrain.WorldGen;
import net.junedev.viridium.worldgen.terrain.pipeline.GenPoint;
import net.junedev.viridium.worldgen.terrain.pipeline.Stage;

/**
 * Pure function from column coordinates to a {@link ColumnPlan}: runs the whole 2D pipeline once per X/Z and turns it
 * into absolute heights (terrain, water, ice) plus the crust profile. Thread-safe; no Minecraft classes.
 */
public final class ColumnPlanner {

    private final WorldGen gen;
    private final BiomeMapper biomes;
    private final ThreadLocal<GenPoint> point = ThreadLocal.withInitial(GenPoint::new);

    public ColumnPlanner(WorldGen gen, BiomeMapper biomes) {
        this.gen = gen;
        this.biomes = biomes;
    }

    public WorldGen gen() {
        return gen;
    }

    public ColumnPlan plan(int chunkX, int chunkZ) {
        ColumnPlan plan = new ColumnPlan(chunkX, chunkZ);
        GenPoint p = point.get();
        int x0 = chunkX * ColumnPlan.SIZE, z0 = chunkZ * ColumnPlan.SIZE;
        for (int lz = 0; lz < ColumnPlan.SIZE; lz++) {
            for (int lx = 0; lx < ColumnPlan.SIZE; lx++) {
                gen.evaluate(x0 + lx, z0 + lz, Stage.BIOMES, p);
                fillColumn(plan, ColumnPlan.index(lx, lz), x0 + lx, z0 + lz, p);
            }
        }
        terrainSlope(plan);
        plan.finish();
        return plan;
    }

    /**
     * The slope the surface rules see: the gradient of the final terrain of the column (central differences, one-sided
     * on the chunk's border). The pipeline's slope is that of the smoothed base
     * relief: it misses the gullies, valley sides and channel banks, which is where bare rock shows.
     */
    private static void terrainSlope(ColumnPlan plan) {
        float[] h = plan.height;
        for (int lz = 0; lz < 16; lz++) for (int lx = 0; lx < 16; lx++) {
            int il = Math.max(lx - 1, 0), ir = Math.min(lx + 1, 15), jl = Math.max(lz - 1, 0),
                jr = Math.min(lz + 1, 15);
            double gx = (h[ColumnPlan.index(ir, lz)] - h[ColumnPlan.index(il, lz)]) / (ir - il);
            double gz = (h[ColumnPlan.index(lx, jr)] - h[ColumnPlan.index(lx, jl)]) / (jr - jl);
            int i = ColumnPlan.index(lx, lz);
            plan.slope[i] = (float) Math.sqrt(gx * gx + gz * gz);
        }
    }

    /** A plan used as scratch by {@link #planPoint}; only the slots written there are meaningful. */
    public ColumnPlan newScratchPlan() {
        return new ColumnPlan(0, 0);
    }

    /**
     * Plans a single column at block (x, z) into slot {@code i} of a scratch plan, seen at {@code bpp} blocks per
     * sample (1 = game resolution). Used by far-away consumers (Distant Horizons LODs) that never need a whole chunk.
     */
    public void planPoint(ColumnPlan plan, int i, int x, int z, double bpp) {
        GenPoint p = point.get();
        gen.evaluate(x, z, Stage.BIOMES, bpp, p);
        fillColumn(plan, i, x, z, p);
    }

    /** Converts one evaluated point into the column's absolute layout. */
    void fillColumn(ColumnPlan plan, int i, int x, int z, GenPoint p) {
        int surface = TerrainLayout.surfaceY(p.h);
        int flags = 0;
        int water = NONE, iceTop = NONE, iceBottom = NONE;

        if (p.lake == 1) {
            flags |= ColumnPlan.F_LAKE;
            water = TerrainLayout.surfaceY(p.lakeZ);
        } else if (p.lake == 2) flags |= ColumnPlan.F_SALT;
        if (p.river != 0) {
            flags |= ColumnPlan.F_RIVER;
            water = Math.max(water, TerrainLayout.surfaceY(p.riverWL));
        }
        int fallTop = NONE;
        if (p.fall != 0) flags |= ColumnPlan.F_FALL;
        if (surface < SEA_LEVEL - 1) water = Math.max(water, SEA_LEVEL - 1);
        if (water != NONE && water <= surface) water = NONE;

        // ice
        if (p.seaIce == 3 || (water != NONE && p.T < -6 && (flags & (ColumnPlan.F_LAKE | ColumnPlan.F_RIVER)) != 0)) {
            flags |= ColumnPlan.F_FROZEN;
            iceTop = iceBottom = water;
        } else if (p.seaIce == 1) {
            flags |= ColumnPlan.F_FLOE;
            iceBottom = SEA_LEVEL - 1;
            iceTop = SEA_LEVEL - 1 + (int) Math.round(p.hs);
        } else if (p.seaIce == 2) {
            flags |= ColumnPlan.F_SHELF;
            iceBottom = SEA_LEVEL - 10;
            iceTop = SEA_LEVEL - 1 + (int) Math.ceil(p.hs);
        }
        if (p.berg > 0) {
            flags |= ColumnPlan.F_BERG;
            double above = Math.max(1, p.hs);
            iceBottom = Math.max(surface + 1, SEA_LEVEL - 1 - (int) Math.round(3 * above));
            iceTop = SEA_LEVEL - 1 + (int) Math.round(above);
        }
        if (iceBottom != NONE) iceBottom = Math.max(iceBottom, surface + 1);

        plan.height[i] = (float) p.h;
        plan.surfaceY[i] = surface;
        plan.waterY[i] = water;
        plan.iceTop[i] = iceTop;
        plan.iceBottom[i] = iceBottom;
        plan.flags[i] = (byte) flags;
        plan.surf[i] = (byte) p.surf;
        if (p.fallTop > -1e8 && water != NONE) {
            int ft = TerrainLayout.surfaceY(p.fallTop);
            if (ft > water) fallTop = ft;
        }
        plan.fallTop[i] = fallTop;
        plan.temperature[i] = (float) p.T;
        plan.precipitation[i] = (float) p.P;
        plan.slope[i] = (float) p.slope;
        plan.rock[i] = (byte) (p.rockT >= 0.5 ? p.rock : p.rock2);
        plan.biome[i] = (byte) p.biome;
        plan.vanillaBiome[i] = (byte) biomes.biomeId(p);
        gen.crust.planColumn(plan.crust, i, x, z, p, surface);
    }
}
