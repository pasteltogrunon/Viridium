package net.junedev.viridium.worldgen.terrain.cube;

import java.util.Collections;
import java.util.List;

import javax.annotation.Nullable;
import javax.annotation.ParametersAreNonnullByDefault;

import net.junedev.viridium.Viridium;
import net.junedev.viridium.worldgen.terrain.GenStats;
import net.junedev.viridium.worldgen.terrain.WorldGen;
import net.junedev.viridium.worldgen.terrain.column.BiomeMapper;
import net.junedev.viridium.worldgen.terrain.column.ColumnPlan;
import net.junedev.viridium.worldgen.terrain.column.ColumnPlanCache;
import net.junedev.viridium.worldgen.terrain.column.ColumnPlanner;
import net.junedev.viridium.worldgen.terrain.core.GenParams;
import net.minecraft.entity.EnumCreatureType;
import net.minecraft.world.ChunkCoordIntPair;
import net.minecraft.world.ChunkPosition;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraft.world.biome.BiomeGenBase;
import net.minecraft.world.chunk.Chunk;

import com.cardinalstar.cubicchunks.api.ICube;
import com.cardinalstar.cubicchunks.api.worldgen.GenerationResult;
import com.cardinalstar.cubicchunks.api.worldgen.IWorldGenerator;
import com.cardinalstar.cubicchunks.mixin.api.ICubicWorldInternal;
import com.cardinalstar.cubicchunks.server.chunkio.CubeInitLevel;
import com.cardinalstar.cubicchunks.server.chunkio.IPreloadFailureDelegate;
import com.cardinalstar.cubicchunks.util.CubePos;
import com.cardinalstar.cubicchunks.world.core.IColumnInternal;
import com.cardinalstar.cubicchunks.world.cube.Cube;

/**
 * Cubic Chunks entry point. Every X/Z-only computation happens once per column in {@link ColumnPlanner} (and is
 * cached); {@link #provideCube} only reads the plan and fills one 16³ cube. Plans are precomputed on CC's background
 * threads through {@link IPreloadFailureDelegate}, so the server thread usually finds them ready.
 */
@ParametersAreNonnullByDefault
public class ViridiumWorldGenerator implements IWorldGenerator, IPreloadFailureDelegate {

    private final WorldServer world;
    private final ColumnPlanCache plans;
    private final CubeFiller filler;
    private final WorldGen gen;

    public ViridiumWorldGenerator(WorldServer world) {
        this.world = world;
        GenParams params = new GenParams();
        params.seed = GenParams.foldSeed(world.getSeed());
        WorldGen gen = this.gen = new WorldGen(params);
        openRiverStore(world, gen);
        this.plans = new ColumnPlanCache(new ColumnPlanner(gen, BiomeMapper.FROM_STAGE));
        this.filler = new CubeFiller(!world.provider.hasNoSky, gen.crust, LithologyBlocks.get());
        net.junedev.viridium.worldgen.terrain.cube.flora.FloraPlanner flora = new net.junedev.viridium.worldgen.terrain.cube.flora.FloraPlanner(
            plans,
            filler.rules(),
            world.getSeed(),
            filler.hasSky());
        net.junedev.viridium.worldgen.terrain.column.CliffDetail cliffs = new net.junedev.viridium.worldgen.terrain.column.CliffDetail(
            plans,
            params.seed ^ 0xC11FF,
            params.cliffDetail);
        filler.setCliffs(cliffs);
        filler.setCaves(gen.caves);
        flora.setCaves(gen.caves);
        flora.setCliffs(cliffs, gen.crust, LithologyBlocks.get(), params.cliffDetail);
        filler.setFlora(flora);
        BY_WORLD.put(world, this);
        PlanPrefetcher p = PlanPrefetcher.start(world, plans, gen);
        // the spawn area is prepared before any player joins: warm it right away
        p.prefetchAround(world.getSpawnPoint().posX >> 4, world.getSpawnPoint().posZ >> 4, 14);
        Viridium.LOGGER.info("Viridium terrain generator created (seed {})", params.seed);
    }

    /** Keeps the river networks of this world in its save folder, so later sessions read them instead of building. */
    private static void openRiverStore(WorldServer world, WorldGen gen) {
        try {
            java.io.File save = world.getSaveHandler()
                .getWorldDirectory();
            if (save == null) return;
            net.junedev.viridium.worldgen.terrain.stages.RiverNetStore store = net.junedev.viridium.worldgen.terrain.stages.RiverNetStore
                .open(new java.io.File(save, "viridium/rivernets"), gen.params);
            if (store == null) Viridium.LOGGER.warn("Cannot create the river network store in {}", save);
            else {
                gen.rivers.setStore(store);
                Viridium.LOGGER.info("River networks kept in {}", store.dir());
            }
        } catch (RuntimeException e) {
            Viridium.LOGGER.warn("River network store unavailable", e);
        }
    }

    /** The pipeline of this world (shared: the map reuses its caches and river networks). */
    public WorldGen gen() {
        return gen;
    }

    public ColumnPlanCache plans() {
        return plans;
    }

    public CubeFiller filler() {
        return filler;
    }

    private static final java.util.Map<World, ViridiumWorldGenerator> BY_WORLD = java.util.Collections
        .synchronizedMap(new java.util.WeakHashMap<>());

    /** The generator of a server world, or null if it does not use Viridium. */
    public static ViridiumWorldGenerator forWorld(World world) {
        return BY_WORLD.get(world);
    }

    // ------------------------------------------------------------------ generation

    @Override
    public GenerationResult<Chunk> provideColumn(World world, int columnX, int columnZ) {
        return new GenerationResult<>(newColumn(columnX, columnZ, plans.get(columnX, columnZ)));
    }

    @Override
    public GenerationResult<Cube> provideCube(@Nullable Chunk column, int cubeX, int cubeY, int cubeZ) {
        long t0 = System.nanoTime();
        ColumnPlan plan = plans.get(cubeX, cubeZ);
        long t1 = System.nanoTime();
        GenStats.PLAN_WAIT.add(t1 - t0);
        List<Chunk> sideColumns = Collections.emptyList();
        if (column == null) {
            column = newColumn(cubeX, cubeZ, plan);
            sideColumns = Collections.singletonList(column);
        }
        CubeFiller.Kind kind = CubeFiller.classify(plan, cubeY);
        long t2 = System.nanoTime();
        Cube cube = new Cube(column, cubeY, filler.fill(plan, cubeX, cubeY, cubeZ));
        long dt = System.nanoTime() - t2;
        switch (kind) {
            case AIR -> GenStats.FILL_AIR.add(dt);
            case CRUST -> GenStats.FILL_CRUST.add(dt);
            case MIXED -> GenStats.FILL_MIXED.add(dt);
            case BEDROCK -> GenStats.FILL_BEDROCK.add(dt);
        }
        return new GenerationResult<>(cube, sideColumns, null);
    }

    private Chunk newColumn(int columnX, int columnZ, ColumnPlan plan) {
        Chunk column = new Chunk(world, columnX, columnZ);
        column.setBiomeArray(plan.biomeArrayCopy());
        column.isTerrainPopulated = true;
        ((IColumnInternal) column).setColumn(true);
        return column;
    }

    /**
     * Nothing to do: vegetation is planned per column and drawn into each cube when it is generated
     * ({@link net.junedev.viridium.worldgen.terrain.cube.flora.FloraPlanner}), so population needs no neighbour cubes,
     * no world access and no light updates. The cube is only marked as populated so lighting can run.
     */
    @Override
    public void populate(Cube cube) {
        long t0 = System.nanoTime();
        cube.markPopulated(Cube.POP_ALL);
        ((ICubicWorldInternal.Server) world).getCubeCache()
            .getCubeLoader()
            .onCubeGenerated(cube);
        GenStats.POPULATE.add(System.nanoTime() - t0);
    }

    // ------------------------------------------------------------------ background precalculation

    @Override
    public void onColumnPreloadFailed(ChunkCoordIntPair pos) {
        precalculate(pos.chunkXPos, pos.chunkZPos);
    }

    @Override
    public void onCubePreloadFailed(CubePos pos, CubeInitLevel actual, CubeInitLevel wanted) {
        if (actual.ordinal() < CubeInitLevel.Generated.ordinal()) precalculate(pos.getX(), pos.getZ());
    }

    private void precalculate(int x, int z) {
        try {
            plans.precalculate(x, z);
        } catch (RuntimeException e) {
            // the server thread will retry and report it; never kill a CC worker
            Viridium.LOGGER.warn("Column plan precalculation failed at {},{}", x, z, e);
        }
    }

    // ------------------------------------------------------------------ misc

    @Override
    public void recreateStructures(ICube cube) {}

    @Override
    public void recreateStructures(Chunk column) {}

    @Override
    public List<BiomeGenBase.SpawnListEntry> getPossibleCreatures(EnumCreatureType type, int x, int y, int z) {
        BiomeGenBase biome = world.getBiomeGenForCoords(x, z);
        return biome == null ? Collections.emptyList() : biome.getSpawnableList(type);
    }

    @Override
    public @Nullable ChunkPosition getNearestStructure(String name, int x, int y, int z) {
        return null;
    }
}
