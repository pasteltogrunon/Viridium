package net.junedev.viridium.compat.dh;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.function.Consumer;

import net.junedev.viridium.Viridium;
import net.junedev.viridium.worldgen.terrain.column.ColumnPlan;
import net.junedev.viridium.worldgen.terrain.column.ColumnPlanCache;
import net.junedev.viridium.worldgen.terrain.column.ColumnPlanner;
import net.junedev.viridium.worldgen.terrain.column.TerrainLayout;
import net.junedev.viridium.worldgen.terrain.crust.CrustModel;
import net.junedev.viridium.worldgen.terrain.cube.LithologyBlocks;
import net.junedev.viridium.worldgen.terrain.cube.SurfaceRules;
import net.junedev.viridium.worldgen.terrain.cube.ViridiumWorldGenerator;
import net.junedev.viridium.worldgen.terrain.cube.flora.Feature;
import net.junedev.viridium.worldgen.terrain.cube.flora.FeatureType;
import net.junedev.viridium.worldgen.terrain.cube.flora.FloraPlanner;
import net.junedev.viridium.worldgen.terrain.stages.Biome;
import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.world.WorldServer;
import net.minecraft.world.biome.BiomeGenBase;

import com.seibel.distanthorizons.api.DhApi;
import com.seibel.distanthorizons.api.enums.EDhApiDetailLevel;
import com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiDistantGeneratorMode;
import com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiWorldGenerationStep;
import com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiWorldGeneratorReturnType;
import com.seibel.distanthorizons.api.interfaces.block.IDhApiBiomeWrapper;
import com.seibel.distanthorizons.api.interfaces.block.IDhApiBlockStateWrapper;
import com.seibel.distanthorizons.api.interfaces.override.worldGenerator.IDhApiWorldGenerator;
import com.seibel.distanthorizons.api.interfaces.world.IDhApiLevelWrapper;
import com.seibel.distanthorizons.api.objects.data.DhApiTerrainDataPoint;
import com.seibel.distanthorizons.api.objects.data.IDhApiFullDataSource;

/**
 * Distant Horizons world generator for a Viridium world. Fills DH's LOD data sources directly from the 2D pipeline:
 * one {@link ColumnPlanner#planPoint} per LOD column, sampled at the LOD's own resolution (a column 64 blocks wide is
 * one evaluation at 64 blocks per sample, like the map), turned into a handful of vertical runs by {@link LodColumn}.
 * No cube, chunk or world access is involved, so it runs on DH's worker threads at any distance.
 * <p>
 * Height range: DH on 1.7.10 maps LOD Y to {@code [0, world height)}; with Cubic Chunks the height is the cubic world's
 * maximum, while terrain below Y 0 (deep sea floor) is flattened to a one-block floor at Y 0.
 */
public final class ViridiumLodGenerator implements IDhApiWorldGenerator {

    /** Coarsest LOD this generator fills itself (same reach as DH's own surface generator). */
    private static final byte LARGEST_DETAIL = (byte) (EDhApiDetailLevel.BLOCK.detailLevel + 12);
    /** Chunk plans kept for block-resolution LODs (a 64-block section with its tree margin needs 36). */
    private static final int LOD_PLAN_CACHE = 512;

    private final WorldServer world;
    private final IDhApiLevelWrapper level;

    private volatile Pipeline pipeline;
    private final ConcurrentHashMap<Integer, IDhApiBlockStateWrapper> blockStates = new ConcurrentHashMap<>();
    private final AtomicReferenceArray<IDhApiBiomeWrapper> biomes = new AtomicReferenceArray<>(256);
    private final ThreadLocal<Scratch> scratch = new ThreadLocal<>();
    private volatile boolean errorLogged;

    public ViridiumLodGenerator(WorldServer world, IDhApiLevelWrapper level) {
        this.world = world;
        this.level = level;
    }

    // ------------------------------------------------------------------ IDhApiWorldGenerator

    @Override
    public byte getSmallestDataDetailLevel() {
        return EDhApiDetailLevel.BLOCK.detailLevel;
    }

    @Override
    public byte getLargestDataDetailLevel() {
        return LARGEST_DETAIL;
    }

    @Override
    public EDhApiWorldGeneratorReturnType getReturnType() {
        return EDhApiWorldGeneratorReturnType.API_DATA_SOURCES;
    }

    @Override
    public boolean runApiValidation() {
        return false; // LodColumn always emits contiguous, non-empty runs
    }

    @Override
    public CompletableFuture<Void> generateLod(int chunkPosMinX, int chunkPosMinZ, int lodPosX, int lodPosZ,
        byte detailLevel, IDhApiFullDataSource dataSource, EDhApiDistantGeneratorMode generatorMode,
        ExecutorService pool, Consumer<IDhApiFullDataSource> resultConsumer) {
        // "pre-existing only" means: do not generate anything new
        if (generatorMode == EDhApiDistantGeneratorMode.PRE_EXISTING_ONLY)
            return CompletableFuture.completedFuture(null);
        return CompletableFuture.runAsync(() -> {
            try {
                fill(chunkPosMinX << 4, chunkPosMinZ << 4, detailLevel, dataSource);
                resultConsumer.accept(dataSource);
            } catch (RuntimeException e) {
                if (!errorLogged) {
                    errorLogged = true;
                    Viridium.LOGGER.error(
                        "Viridium LOD generation failed at chunk {},{} (detail {})",
                        chunkPosMinX,
                        chunkPosMinZ,
                        detailLevel,
                        e);
                }
                throw e;
            }
        }, pool);
    }

    @Override
    public void preGeneratorTaskStart() {}

    @Override
    public void close() {}

    // ------------------------------------------------------------------ filling

    private void fill(int blockMinX, int blockMinZ, byte detailLevel, IDhApiFullDataSource ds) {
        Pipeline p = pipeline();
        Scratch s = scratch.get();
        if (s == null) scratch.set(s = new Scratch(p.planner.newScratchPlan(), ds.getWidthInDataColumns()));
        int width = ds.getWidthInDataColumns();
        // Cubic Chunks reports a near-unbounded world height (~2^30): nothing is generated above MAX_Y, and DH rejects
        // runs taller than its packed height limit
        int minY = level.getMinHeight(), maxY = Math.min(level.getMaxHeight(), TerrainLayout.MAX_Y);
        if (detailLevel == 0) fillExact(p, s, blockMinX, blockMinZ, width, minY, maxY, ds);
        else fillSampled(p, s, blockMinX, blockMinZ, width, 1 << detailLevel, minY, maxY, ds);
    }

    /**
     * Block resolution: the column plans of the chunks underneath and the exact trees the world will grow there (the
     * same plans and shapes as {@code FloraPlanner}), so nothing changes when the real chunks replace the LOD.
     */
    private void fillExact(Pipeline p, Scratch s, int blockMinX, int blockMinZ, int width, int minY, int maxY,
        IDhApiFullDataSource ds) {
        CanopySink canopy = s.canopy;
        canopy.reset(blockMinX, blockMinZ);
        int cx0 = blockMinX >> 4, cz0 = blockMinZ >> 4, chunks = width >> 4;
        int ax1 = blockMinX + width - 1, az1 = blockMinZ + width - 1;
        // trees rooted in the neighbouring chunks reach in too (crowns are at most 7 blocks wide)
        for (int cz = cz0 - 1; cz <= cz0 + chunks; cz++) {
            for (int cx = cx0 - 1; cx <= cx0 + chunks; cx++) {
                for (Feature f : p.flora.features(cx, cz)) {
                    if (f.type.height <= 3 || f.maxX < blockMinX || f.minX > ax1 || f.maxZ < blockMinZ || f.minZ > az1)
                        continue; // plants, or outside the section
                    f.type.draw(canopy, f);
                }
            }
        }

        for (int rz = 0; rz < width; rz++) {
            for (int rx = 0; rx < width; rx++) {
                int x = blockMinX + rx, z = blockMinZ + rz;
                ColumnPlan plan = p.lodPlans.get(x >> 4, z >> 4);
                int i = ColumnPlan.index(x & 15, z & 15);
                int ci = canopy.index(x, z);
                if (canopy.hasTree(ci)) {
                    int leafTop = canopy.leafMax[ci] == ColumnPlan.NONE ? ColumnPlan.NONE : canopy.leafMax[ci] + 1;
                    s.column.tree(canopy.logTop[ci], canopy.leafMin[ci], leafTop);
                }
                emitColumn(p, s, plan, i, x, z, minY, maxY, rx, rz, ds, canopy.leafBlock[ci], canopy.leafMeta[ci],
                    canopy.logBlock[ci], canopy.logMeta[ci]);
            }
        }
    }

    /**
     * Coarser LODs: one pipeline evaluation per column at the LOD's own resolution. Forests get a statistical canopy:
     * the column is under a crown with the probability the biome's tree density gives, at the height of the species
     * that would grow there.
     */
    private void fillSampled(Pipeline p, Scratch s, int blockMinX, int blockMinZ, int width, int step, int minY,
        int maxY, IDhApiFullDataSource ds) {
        ColumnPlan plan = s.plan;
        for (int rz = 0; rz < width; rz++) {
            for (int rx = 0; rx < width; rx++) {
                int x = blockMinX + rx * step + (step >> 1), z = blockMinZ + rz * step + (step >> 1);
                p.planner.planPoint(plan, 0, x, z, step);
                TreeShapes.Shape tree = sampleTree(p, s, plan, x, z);
                emitColumn(p, s, plan, 0, x, z, minY, maxY, rx, rz, ds, tree == null ? null : tree.leaves(),
                    tree == null ? 0 : tree.leavesMeta(), null, 0);
            }
        }
    }

    /** Statistical tree for a sampled column; sets the crown on {@code s.column} and returns its shape, or null. */
    private TreeShapes.Shape sampleTree(Pipeline p, Scratch s, ColumnPlan plan, int x, int z) {
        if (plan.waterY[0] != ColumnPlan.NONE || plan.iceTop[0] != ColumnPlan.NONE) return null;
        Biome b = Biome.VALUES[plan.biome[0]];
        double density = FloraPlanner.treeDensity(b);
        if (density <= 0) return null;
        SurfaceRules.Column r = s.rules;
        p.rules.resolve(plan, 0, x, z, r);
        Random rnd = s.random;
        rnd.setSeed(p.seed ^ (x * 0x9E3779B97F4A7C15L) ^ (z * 0xC2B2AE3D27D4EB4FL));
        FeatureType type = FloraPlanner.tree(b, rnd, plan.temperature[0]);
        if (type == null) return null;
        boolean ground = type == FeatureType.PALM ? r.top == Blocks.sand || r.top == Blocks.grass
            : r.top == Blocks.grass || r.top == Blocks.dirt;
        TreeShapes.Shape shape = ground ? TreeShapes.of(type) : null;
        if (shape == null || shape.leaves() == null) return null;
        // trees overlap: covered fraction of the ground for this many crowns per 16x16 chunk
        double coverage = 1 - Math.exp(-density * shape.crownArea() / 256);
        if (rnd.nextDouble() >= coverage) return null;
        int base = plan.surfaceY[0] + 1;
        s.column.tree(ColumnPlan.NONE, base + shape.leafBottom(), base + shape.leafTop() + rnd.nextInt(3) - 1);
        return shape;
    }

    /** Builds one LOD column from plan slot {@code i} and hands it to DH. */
    private void emitColumn(Pipeline p, Scratch s, ColumnPlan plan, int i, int x, int z, int minY, int maxY, int rx,
        int rz, IDhApiFullDataSource ds, Block leaves, int leavesMeta, Block log, int logMeta) {
        SurfaceRules.Column r = s.rules;
        p.rules.resolve(plan, i, x, z, r);
        LodColumn c = s.column.build(plan, i, minY, maxY, r.under != null ? r.depth : 0, r.bands, r.snow);
        // the rock right under the soil, as CubeFiller would sample it
        int rockY = Math.max(c.solidTop - 2 - r.depth, minY);
        Block rock = p.lith[p.crust.lithologyAt(plan.crust, i, x, rockY, z)
            .ordinal()];
        IDhApiBiomeWrapper biome = biome(plan.vanillaBiome[i] & 0xff);

        List<DhApiTerrainDataPoint> points = s.points;
        points.clear();
        for (int k = 0; k < c.size; k++) {
            IDhApiBlockStateWrapper state = switch (c.kind[k]) {
                case LodColumn.ROCK -> blockState(rock, 0);
                case LodColumn.UNDER -> blockState(
                    r.under,
                    r.bands ? SurfaceRules.bandMeta(c.bottom[k], x, z) : r.underMeta);
                case LodColumn.TOP -> r.top != null ? blockState(r.top, r.topMeta) : blockState(rock, 0);
                case LodColumn.WATER -> blockState(Blocks.water, 0);
                case LodColumn.ICE -> blockState(Blocks.ice, 0);
                case LodColumn.PACKED_ICE -> blockState(Blocks.packed_ice, 0);
                case LodColumn.SNOW -> blockState(Blocks.snow_layer, 0);
                case LodColumn.LEAVES -> blockState(leaves != null ? leaves : Blocks.leaves, leavesMeta);
                case LodColumn.LOG -> blockState(log != null ? log : Blocks.log, logMeta);
                default -> air();
            };
            points.add(DhApiTerrainDataPoint.create((byte) 0, 0, c.skyLight[k], c.bottom[k], c.top[k], state, biome));
        }
        ds.setApiDataPointColumn(rx, rz, EDhApiWorldGenerationStep.LIGHT, points);
    }

    private Pipeline pipeline() {
        Pipeline p = pipeline;
        if (p != null) return p;
        ViridiumWorldGenerator g = ViridiumWorldGenerator.forWorld(world);
        if (g == null) throw new IllegalStateException("Viridium generator not created yet for " + world);
        ColumnPlanner planner = g.plans()
            .planner();
        SurfaceRules rules = g.filler()
            .rules();
        // separate caches: LOD work far from the players must not evict the plans the real world is using
        ColumnPlanCache lodPlans = new ColumnPlanCache(planner, LOD_PLAN_CACHE);
        FloraPlanner flora = new FloraPlanner(lodPlans, rules, world.getSeed(), true);
        return pipeline = new Pipeline(planner, rules, g.gen().crust, LithologyBlocks.get(), lodPlans, flora,
            world.getSeed());
    }

    private static IDhApiBlockStateWrapper air() {
        return DhApi.Delayed.wrapperFactory.getAirBlockStateWrapper();
    }

    private IDhApiBlockStateWrapper blockState(Block block, int meta) {
        int id = Block.getIdFromBlock(block);
        return blockStates.computeIfAbsent(
            id << 4 | meta & 15,
            k -> DhApi.Delayed.wrapperFactory.getBlockStateWrapper(new Object[] { block, meta, id }, level));
    }

    private IDhApiBiomeWrapper biome(int id) {
        IDhApiBiomeWrapper w = biomes.get(id);
        if (w != null) return w;
        BiomeGenBase b = BiomeGenBase.getBiomeGenArray()[id];
        if (b == null) b = BiomeGenBase.plains;
        w = DhApi.Delayed.wrapperFactory.getBiomeWrapper(new Object[] { b }, level);
        biomes.set(id, w);
        return w;
    }

    private record Pipeline(ColumnPlanner planner, SurfaceRules rules, CrustModel crust, Block[] lith,
        ColumnPlanCache lodPlans, FloraPlanner flora, long seed) {}

    private static final class Scratch {

        final ColumnPlan plan;
        final CanopySink canopy;
        final LodColumn column = new LodColumn();
        final SurfaceRules.Column rules = new SurfaceRules.Column();
        final List<DhApiTerrainDataPoint> points = new ArrayList<>(LodColumn.MAX_RUNS);
        final Random random = new Random();

        Scratch(ColumnPlan plan, int width) {
            this.plan = plan;
            this.canopy = new CanopySink(width);
        }
    }
}
