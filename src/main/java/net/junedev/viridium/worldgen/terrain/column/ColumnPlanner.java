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
        float[] riverD = new float[ColumnPlan.AREA];
        int x0 = chunkX * ColumnPlan.SIZE, z0 = chunkZ * ColumnPlan.SIZE;
        for (int lz = 0; lz < ColumnPlan.SIZE; lz++) {
            for (int lx = 0; lx < ColumnPlan.SIZE; lx++) {
                gen.evaluate(x0 + lx, z0 + lz, Stage.BIOMES, p);
                int i = ColumnPlan.index(lx, lz);
                fillColumn(plan, i, x0 + lx, z0 + lz, p);
                riverD[i] = (float) p.riverD;
            }
        }
        buildBanks(plan, riverD);
        plan.finish();
        return plan;
    }

    /** Distance to a river (blocks) under which a column may border river water. */
    private static final double NEAR_RIVER = 4;

    /**
     * No hanging water: every dry column next to higher water (river or lake) is raised up to that water level, like
     * a natural levee. The rule only reads the unmodified water level and surface of the column and its 4 neighbours,
     * all pure functions of position, so both sides of a chunk border agree. Neighbours outside the chunk are only
     * evaluated for edge columns close to a river.
     */
    void buildBanks(ColumnPlan plan, float[] riverD) {
        java.util.Map<Long, Integer> halo = null;
        int x0 = plan.chunkX * ColumnPlan.SIZE, z0 = plan.chunkZ * ColumnPlan.SIZE;
        int[] raise = new int[ColumnPlan.AREA];
        for (int i = 0; i < ColumnPlan.AREA; i++) {
            raise[i] = NONE;
            if (plan.waterY[i] != NONE || plan.iceTop[i] != NONE) continue; // only dry columns get banks
            int lx = i & 15, lz = i >> 4, w = NONE;
            if (lx > 0) w = Math.max(w, plan.waterY[i - 1]);
            if (lx < 15) w = Math.max(w, plan.waterY[i + 1]);
            if (lz > 0) w = Math.max(w, plan.waterY[i - 16]);
            if (lz < 15) w = Math.max(w, plan.waterY[i + 16]);
            if ((lx == 0 || lx == 15 || lz == 0 || lz == 15) && riverD[i] < NEAR_RIVER + 30) {
                if (halo == null) halo = new java.util.HashMap<>();
                if (lx == 0) w = Math.max(w, outsideWater(halo, x0 - 1, z0 + lz));
                if (lx == 15) w = Math.max(w, outsideWater(halo, x0 + 16, z0 + lz));
                if (lz == 0) w = Math.max(w, outsideWater(halo, x0 + lx, z0 - 1));
                if (lz == 15) w = Math.max(w, outsideWater(halo, x0 + lx, z0 + 16));
            }
            if (w != NONE && w > plan.surfaceY[i] && w >= SEA_LEVEL - 1) raise[i] = w;
        }
        for (int i = 0; i < ColumnPlan.AREA; i++) {
            if (raise[i] == NONE) continue;
            plan.surfaceY[i] = raise[i];
            plan.height[i] = raise[i] + 1 - SEA_LEVEL;
        }
    }

    private final ThreadLocal<GenPoint> haloPoint = ThreadLocal.withInitial(GenPoint::new);

    /** Unmodified water level of a column outside the chunk (same rule as {@link #fillColumn}), or NONE. */
    private int outsideWater(java.util.Map<Long, Integer> memo, int x, int z) {
        return memo.computeIfAbsent(((long) x << 32) | (z & 0xffffffffL), k -> {
            GenPoint q = gen.evaluate(x, z, Stage.BIOMES, haloPoint.get());
            return waterLevel(q, TerrainLayout.surfaceY(q.h));
        });
    }

    /** Water level of a column from its evaluated point (river, lake, sea), or NONE when dry. */
    static int waterLevel(GenPoint p, int surface) {
        int water = NONE;
        if (p.lake == 1) water = TerrainLayout.surfaceY(p.lakeZ);
        if (p.river != 0) water = Math.max(water, TerrainLayout.surfaceY(p.riverWL));
        if (surface < SEA_LEVEL - 1) water = Math.max(water, SEA_LEVEL - 1);
        return water != NONE && water > surface ? water : NONE;
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
