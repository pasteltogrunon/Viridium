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
        plan.finish();
        return plan;
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
        plan.temperature[i] = (float) p.T;
        plan.precipitation[i] = (float) p.P;
        plan.slope[i] = (float) p.slope;
        plan.rock[i] = (byte) (p.rockT >= 0.5 ? p.rock : p.rock2);
        plan.biome[i] = (byte) p.biome;
        plan.vanillaBiome[i] = (byte) biomes.biomeId(p);
        gen.crust.planColumn(plan.crust, i, x, z, p, surface);
    }
}
