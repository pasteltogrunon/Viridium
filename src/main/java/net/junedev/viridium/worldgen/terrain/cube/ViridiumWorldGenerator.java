package net.junedev.viridium.worldgen.terrain.cube;

import java.util.Collections;
import java.util.List;

import javax.annotation.Nullable;
import javax.annotation.ParametersAreNonnullByDefault;

import net.junedev.viridium.Viridium;
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
import com.cardinalstar.cubicchunks.api.util.Box;
import com.cardinalstar.cubicchunks.api.worldgen.GenerationResult;
import com.cardinalstar.cubicchunks.api.worldgen.IWorldGenerator;
import com.cardinalstar.cubicchunks.mixin.api.ICubicWorldInternal;
import com.cardinalstar.cubicchunks.server.chunkio.CubeInitLevel;
import com.cardinalstar.cubicchunks.server.chunkio.ICubeLoader;
import com.cardinalstar.cubicchunks.server.chunkio.IPreloadFailureDelegate;
import com.cardinalstar.cubicchunks.util.CubePos;
import com.cardinalstar.cubicchunks.world.api.ICubeProviderServer.Requirement;
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
    private final SurfaceDecorator decorator;

    public ViridiumWorldGenerator(WorldServer world) {
        this.world = world;
        GenParams params = new GenParams();
        params.seed = GenParams.foldSeed(world.getSeed());
        WorldGen gen = new WorldGen(params);
        this.plans = new ColumnPlanCache(new ColumnPlanner(gen, BiomeMapper.FROM_STAGE));
        this.filler = new CubeFiller(!world.provider.hasNoSky, gen.crust, LithologyBlocks.get());
        this.decorator = new SurfaceDecorator(plans);
        BY_WORLD.put(world, this);
        Viridium.LOGGER.info("Viridium terrain generator created (seed {})", params.seed);
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
        ColumnPlan plan = plans.get(cubeX, cubeZ);
        List<Chunk> sideColumns = Collections.emptyList();
        if (column == null) {
            column = newColumn(cubeX, cubeZ, plan);
            sideColumns = Collections.singletonList(column);
        }
        Cube cube = new Cube(column, cubeY, filler.fill(plan, cubeX, cubeY, cubeZ));
        return new GenerationResult<>(cube, sideColumns, null);
    }

    private Chunk newColumn(int columnX, int columnZ, ColumnPlan plan) {
        Chunk column = new Chunk(world, columnX, columnZ);
        column.setBiomeArray(plan.biomeArrayCopy());
        column.isTerrainPopulated = true;
        ((IColumnInternal) column).setColumn(true);
        return column;
    }

    @Override
    public void populate(Cube cube) {
        ICubeLoader loader = ((ICubicWorldInternal.Server) world).getCubeCache()
            .getCubeLoader();
        int cx = cube.getX(), cy = cube.getY(), cz = cube.getZ();
        try {
            if (cube.isPopulated()) return;
            if (touchesSurface(cx, cy, cz)) {
                // features may reach into +X/+Z and up to two cubes above: generate (not populate) those first
                for (int dx = 0; dx <= 1; dx++) for (int dz = 0; dz <= 1; dz++) for (int dy = 0; dy <= 2; dy++)
                    loader.getCube(cx + dx, cy + dy, cz + dz, Requirement.GENERATE);
                loader.cacheCubes(new Box(cx, cy, cz, cx + 1, cy + 2, cz + 1));
                try {
                    decorator.decorate(world, world.getSeed(), cx, cy, cz);
                } finally {
                    loader.uncacheCubes();
                }
            }
        } catch (Throwable t) {
            Viridium.LOGGER.error("Viridium population failed at cube {},{},{}", cx, cy, cz, t);
        } finally {
            cube.markPopulated(Cube.POP_ALL);
            loader.onCubeGenerated(cube);
        }
    }

    /** Does the decoration area of this cube (offset +8) contain dry ground inside the cube's Y range? */
    private boolean touchesSurface(int cx, int cy, int cz) {
        int y0 = cy << 4, y1 = y0 + 15;
        for (int dx = 0; dx <= 1; dx++) for (int dz = 0; dz <= 1; dz++) {
            ColumnPlan p = plans.get(cx + dx, cz + dz);
            if (p.minSurfaceY + 1 <= y1 && p.maxTopY >= y0) return true;
        }
        return false;
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
