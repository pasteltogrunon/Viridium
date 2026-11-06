package net.junedev.viridium.worldgen.terrain.cube.flora;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import net.junedev.viridium.worldgen.terrain.GenStats;
import net.junedev.viridium.worldgen.terrain.column.ColumnPlan;
import net.junedev.viridium.worldgen.terrain.column.ColumnPlanCache;
import net.junedev.viridium.worldgen.terrain.cube.SurfaceRules;
import net.junedev.viridium.worldgen.terrain.stages.Biome;
import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;

/**
 * Plans the vegetation of each column (owner = the column holding the base) from its plan alone, and draws into a cube
 * the parts of every feature of the 3×3 surrounding columns that fall inside it. Replaces the world-based population:
 * no {@code setBlock}, no neighbour cubes generated, no light updates queued — the blocks are simply part of the cube
 * when it is created.
 */
public final class FloraPlanner {

    /** Largest feature reach, in blocks: 3×3 columns always cover it. */
    private static final int MAX_RADIUS = 7, MAX_HEIGHT = 32;

    private final ColumnPlanCache plans;
    private final SurfaceRules rules;
    private final long seed;
    private final boolean hasSky;
    private final Cache<Long, List<Feature>> cache = CacheBuilder.newBuilder()
        .maximumSize(4096)
        .concurrencyLevel(4)
        .build();
    private final ThreadLocal<SurfaceRules.Column> column = ThreadLocal.withInitial(SurfaceRules.Column::new);
    private final ThreadLocal<BlockSink> sinks = ThreadLocal.withInitial(BlockSink::new);
    private net.junedev.viridium.worldgen.terrain.column.CliffDetail cliffs;
    private net.junedev.viridium.worldgen.terrain.crust.CrustModel crust;
    private Block[] lith;
    private ThreadLocal<net.junedev.viridium.worldgen.terrain.crust.CrustModel.Sampler> rockSampler;
    private double boulders;

    public FloraPlanner(ColumnPlanCache plans, SurfaceRules rules, long worldSeed, boolean hasSky) {
        this.plans = plans;
        this.rules = rules;
        this.seed = worldSeed;
        this.hasSky = hasSky;
    }

    /** Cliff detail (talus columns grow nothing; boulders fall at the foot of walls) and the rock of the boulders. */
    public void setCliffs(net.junedev.viridium.worldgen.terrain.column.CliffDetail cliffs,
        net.junedev.viridium.worldgen.terrain.crust.CrustModel crust, Block[] lith, double boulders) {
        this.cliffs = cliffs;
        this.crust = crust;
        this.lith = lith;
        this.boulders = boulders;
        this.rockSampler = ThreadLocal.withInitial(() -> crust.new Sampler());
    }

    // ------------------------------------------------------------------ densities per biome

    /** Trees per 16×16 column. */
    public static double treeDensity(Biome b) {
        return switch (b) {
            case TROPICAL_RAINFOREST -> 12;
            case TEMPERATE_RAINFOREST -> 10;
            case TEMPERATE_FOREST -> 8;
            case TAIGA -> 7;
            case SNOWY_TAIGA -> 5;
            case SWAMP, MANGROVE -> 3;
            case KARST -> 2;
            case SAVANNA, DELTA -> 1;
            case ALPINE_MEADOW -> 0.5;
            case GRASSLAND, BEACH -> 0.3;
            case COLD_STEPPE -> 0.1;
            default -> 0;
        };
    }

    public static FeatureType tree(Biome b, Random r, float temperature) {
        return switch (b) {
            case TROPICAL_RAINFOREST -> r.nextInt(5) == 0 ? FeatureType.MEGA_JUNGLE : FeatureType.JUNGLE;
            case TEMPERATE_RAINFOREST -> r.nextInt(3) == 0 ? FeatureType.PINE : FeatureType.SPRUCE;
            case TAIGA, SNOWY_TAIGA -> r.nextInt(3) == 0 ? FeatureType.PINE : FeatureType.SPRUCE;
            case SWAMP, MANGROVE -> FeatureType.SWAMP_OAK;
            case SAVANNA -> FeatureType.ACACIA;
            case BEACH -> temperature > 20 ? FeatureType.PALM : null;
            case TEMPERATE_FOREST -> {
                int k = r.nextInt(10);
                yield k < 4 ? FeatureType.PEDUNCULATE_OAK
                    : k < 6 ? FeatureType.CHESTNUT : k < 8 ? FeatureType.BIRCH : FeatureType.OAK;
            }
            default -> r.nextInt(10) == 0 ? FeatureType.PEDUNCULATE_OAK : FeatureType.OAK;
        };
    }

    static int grassCount(Biome b) {
        return switch (b) {
            case GRASSLAND -> 20;
            case SAVANNA -> 15;
            case TROPICAL_RAINFOREST, TEMPERATE_RAINFOREST -> 10;
            case COLD_STEPPE -> 8;
            case TEMPERATE_FOREST, SWAMP, ALPINE_MEADOW, DELTA -> 6;
            case TUNDRA, TAIGA, SNOWY_TAIGA, KARST -> 3;
            default -> 0;
        };
    }

    // ------------------------------------------------------------------ planning

    private static long key(int x, int z) {
        return net.junedev.viridium.worldgen.terrain.core.GenMath.mixKey(((long) x << 32) | (z & 0xffffffffL));
    }

    public List<Feature> features(int chunkX, int chunkZ) {
        List<Feature> f = cache.getIfPresent(key(chunkX, chunkZ));
        if (f != null) return f;
        f = plan(chunkX, chunkZ);
        cache.put(key(chunkX, chunkZ), f);
        return f;
    }

    private List<Feature> plan(int chunkX, int chunkZ) {
        ColumnPlan p = plans.get(chunkX, chunkZ);
        Random r = new Random(seed ^ (chunkX * 341873128712L + chunkZ * 132897987541L) ^ 0x5F10AL);
        // the column's dominant biome decides the vegetation (sampled at its centre)
        int ci = ColumnPlan.index(8, 8);
        Biome b = Biome.VALUES[p.biome[ci]];
        float t = p.temperature[ci];
        List<Feature> out = new ArrayList<>();
        double density = treeDensity(b);
        int trees = (int) density + (r.nextDouble() < density - (int) density ? 1 : 0);
        SurfaceRules.Column sc = column.get();
        for (int k = 0; k < trees; k++) {
            int lx = r.nextInt(16), lz = r.nextInt(16);
            FeatureType type = tree(b, r, t);
            long s = r.nextLong();
            if (type == null) continue;
            Block ground = ground(p, lx, lz, sc);
            boolean ok = type == FeatureType.PALM ? ground == Blocks.sand || ground == Blocks.grass
                : ground == Blocks.grass || ground == Blocks.dirt;
            if (ok) out.add(new Feature(type, (chunkX << 4) + lx, p.surfaceY[ColumnPlan.index(lx, lz)] + 1, (chunkZ << 4) + lz, s));
        }
        int grass = grassCount(b);
        boolean ferns = b == Biome.TAIGA || b == Biome.SNOWY_TAIGA || b == Biome.TEMPERATE_RAINFOREST;
        for (int k = 0; k < grass; k++) {
            int lx = r.nextInt(16), lz = r.nextInt(16);
            boolean fern = ferns && r.nextInt(3) == 0;
            long s = r.nextLong();
            if (ground(p, lx, lz, sc) == Blocks.grass && !sc.snow)
                out.add(new Feature(fern ? FeatureType.FERN : FeatureType.TALL_GRASS, (chunkX << 4) + lx,
                    p.surfaceY[ColumnPlan.index(lx, lz)] + 1, (chunkZ << 4) + lz, s));
        }
        if (b == Biome.GRASSLAND || b == Biome.TEMPERATE_FOREST || b == Biome.ALPINE_MEADOW) {
            for (int k = 0; k < 2; k++) {
                int lx = r.nextInt(16), lz = r.nextInt(16);
                long s = r.nextLong();
                if (ground(p, lx, lz, sc) == Blocks.grass && !sc.snow) out.add(new Feature(FeatureType.FLOWER,
                    (chunkX << 4) + lx, p.surfaceY[ColumnPlan.index(lx, lz)] + 1, (chunkZ << 4) + lz, s));
            }
        }
        if (b == Biome.DESERT || b == Biome.BADLANDS) {
            for (int k = 0; k < 3; k++) {
                // cacti only on even x and z: never two side by side (a cactus breaks next to a solid block)
                int lx = r.nextInt(8) * 2, lz = r.nextInt(8) * 2;
                boolean cactus = b == Biome.DESERT && r.nextBoolean();
                long s = r.nextLong();
                if (ground(p, lx, lz, sc) == Blocks.sand) out.add(new Feature(
                    cactus ? FeatureType.CACTUS : FeatureType.DEAD_BUSH, (chunkX << 4) + lx,
                    p.surfaceY[ColumnPlan.index(lx, lz)] + 1, (chunkZ << 4) + lz, s));
            }
        }
        if (cliffs != null) {
            net.junedev.viridium.worldgen.terrain.column.CliffDetail.Cols cc = cliffs.get(chunkX, chunkZ);
            if (cc.any) out.removeIf(f -> cc.talusTop[ColumnPlan.index(f.x & 15, f.z & 15)] != ColumnPlan.NONE);
            boulders(p, cc, r, out);
        }
        return out.isEmpty() ? Collections.emptyList() : out;
    }

    /**
     * Fallen blocks: more of them close to the foot of tall walls, fewer further out (up to 10 blocks), on dry ground or
     * on the sea floor below a sea cliff. Each is made of the rock found under its own column.
     */
    private void boulders(ColumnPlan p, net.junedev.viridium.worldgen.terrain.column.CliffDetail.Cols cc, Random r,
        List<Feature> out) {
        if (boulders <= 0) return;
        for (int i = 0; i < 256; i++) {
            int wd = cc.wallDist[i];
            if (wd <= 0) continue;
            double pr = boulders * 0.03 * (1 - (wd - 1) / 10.0) * Math.min(1, cc.wallH[i] / 30.0);
            if (r.nextDouble() >= pr) continue;
            int lx = i & 15, lz = i >> 4;
            int top = cc.talusTop[i] != ColumnPlan.NONE ? cc.talusTop[i] : p.surfaceY[i];
            int x = (p.chunkX << 4) + lx, z = (p.chunkZ << 4) + lz;
            out.add(new Feature(FeatureType.BOULDER, x, top + 1, z, r.nextLong(), rockAt(p, i, p.surfaceY[i] - 4)));
        }
    }

    private Block rockAt(ColumnPlan p, int i, int y) {
        net.junedev.viridium.worldgen.terrain.crust.CrustModel.Sampler s = rockSampler.get();
        int y0 = y & ~15;
        s.prepare(p.chunkX << 4, y0, p.chunkZ << 4);
        return lith[s.at(p.crust, i, i & 15, y - y0, i >> 4).ordinal()];
    }

    /** Top block of a dry column (null when water/ice covers it or the rock is bare); fills {@code sc}. */
    private Block ground(ColumnPlan p, int lx, int lz, SurfaceRules.Column sc) {
        int i = ColumnPlan.index(lx, lz);
        if (p.waterY[i] != ColumnPlan.NONE || p.iceTop[i] != ColumnPlan.NONE) return null;
        rules.resolve(p, i, (p.chunkX << 4) + lx, (p.chunkZ << 4) + lz, sc);
        if (sc.top == Blocks.dirt && sc.topMeta != 0) return Blocks.dirt; // podzol / coarse dirt: trees only
        return sc.top;
    }

    // ------------------------------------------------------------------ drawing

    /**
     * Draws every feature overlapping cube (cx, cy, cz). {@code ebs} may be null (air cube); returns the storage,
     * allocated only if something was written.
     */
    public ExtendedBlockStorage draw(ColumnPlan own, int cx, int cy, int cz, ExtendedBlockStorage ebs) {
        int y0 = cy << 4;
        // cheap rejection: nothing grows that far above or below this column's surface
        if (y0 > own.maxTopY + MAX_HEIGHT + 16 || y0 + 15 < own.minSurfaceY - 2) return ebs;
        long t0 = System.nanoTime();
        BlockSink sink = sinks.get();
        sink.begin(cx, cy, cz, ebs, hasSky);
        for (int dz = -1; dz <= 1; dz++) for (int dx = -1; dx <= 1; dx++) {
            for (Feature f : features(cx + dx, cz + dz)) {
                if (f.intersectsCube(cx, cy, cz)) {
                    f.type.draw(sink, f);
                    GenStats.FLORA_FEATURES.add(0);
                }
            }
        }
        GenStats.FLORA.add(System.nanoTime() - t0);
        return sink.end();
    }
}
