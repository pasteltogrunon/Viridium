package net.junedev.viridium.worldgen.nether;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import javax.annotation.Nullable;
import javax.annotation.ParametersAreNonnullByDefault;

import net.junedev.viridium.Viridium;
import net.junedev.viridium.worldgen.terrain.core.GenParams;
import net.minecraft.block.Block;
import net.minecraft.entity.EnumCreatureType;
import net.minecraft.world.ChunkCoordIntPair;
import net.minecraft.world.ChunkPosition;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraft.world.biome.BiomeGenBase;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;

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
 * Cubic Chunks entry point of the Viridium Nether. Like the overworld generator, every X/Z-only computation happens
 * once per column ({@link NetherPlanner}, cached) and {@link #provideCube} only reads the plan and fills one cube.
 * Plans are precomputed on CC's background threads through {@link IPreloadFailureDelegate}.
 */
@ParametersAreNonnullByDefault
public class NetherWorldGenerator implements IWorldGenerator, IPreloadFailureDelegate {

    private static final Map<World, NetherWorldGenerator> BY_WORLD = Collections.synchronizedMap(new WeakHashMap<>());

    private final WorldServer world;
    private final NetherWorld nether;
    private final NetherPlanCache plans;
    private final NetherFiller filler;
    private final Block[] blocks;
    private final byte[] metas;
    private final byte[] biomes = new byte[256];

    public NetherWorldGenerator(WorldServer world) {
        this.world = world;
        int seed = netherSeed(world.getSeed());
        this.nether = new NetherWorld(seed);
        this.plans = new NetherPlanCache(new NetherPlanner(nether), 1024);
        this.filler = new NetherFiller(nether);
        this.blocks = NetherBlocks.blocks();
        this.metas = NetherBlocks.metas();
        Arrays.fill(biomes, (byte) BiomeGenBase.hell.biomeID);
        BY_WORLD.put(world, this);
        Viridium.LOGGER.info("Viridium nether generator created (seed {})", seed);
    }

    /** Seed of the Nether pipeline for a world seed (independent of the overworld's). */
    public static int netherSeed(long worldSeed) {
        return GenParams.foldSeed(worldSeed) ^ 0x4E7E4;
    }

    /** The generator of a server world, or null if it is not a Viridium Nether. */
    public static NetherWorldGenerator forWorld(World world) {
        return BY_WORLD.get(world);
    }

    public NetherWorld nether() {
        return nether;
    }

    public NetherPlanCache plans() {
        return plans;
    }

    // ------------------------------------------------------------------ generation

    @Override
    public GenerationResult<Chunk> provideColumn(World world, int columnX, int columnZ) {
        return new GenerationResult<>(newColumn(columnX, columnZ));
    }

    @Override
    public GenerationResult<Cube> provideCube(@Nullable Chunk column, int cubeX, int cubeY, int cubeZ) {
        NetherPlan plan = plans.get(cubeX, cubeZ);
        List<Chunk> sideColumns = Collections.emptyList();
        if (column == null) {
            column = newColumn(cubeX, cubeZ);
            sideColumns = Collections.singletonList(column);
        }
        byte[] mats = filler.fill(plan, cubeX, cubeY, cubeZ);
        ExtendedBlockStorage ebs = null;
        if (mats != null) {
            ebs = new ExtendedBlockStorage(cubeY << 4, !world.provider.hasNoSky);
            for (int k = 0; k < 4096; k++) {
                int id = mats[k];
                if (id == 0) continue;
                int lx = k & 15, lz = (k >> 4) & 15, ly = k >> 8;
                ebs.func_150818_a(lx, ly, lz, blocks[id]);
                if (metas[id] != 0) ebs.setExtBlockMetadata(lx, ly, lz, metas[id]);
            }
        }
        return new GenerationResult<>(new Cube(column, cubeY, ebs), sideColumns, null);
    }

    private Chunk newColumn(int columnX, int columnZ) {
        Chunk column = new Chunk(world, columnX, columnZ);
        column.setBiomeArray(biomes.clone());
        column.isTerrainPopulated = true;
        ((IColumnInternal) column).setColumn(true);
        return column;
    }

    /** Everything is drawn into each cube when it is generated: population only marks the cube. */
    @Override
    public void populate(Cube cube) {
        cube.markPopulated(Cube.POP_ALL);
        ((ICubicWorldInternal.Server) world).getCubeCache()
            .getCubeLoader()
            .onCubeGenerated(cube);
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
            plans.get(x, z);
        } catch (RuntimeException e) {
            Viridium.LOGGER.warn("Nether plan precalculation failed at {},{}", x, z, e);
        }
    }

    // ------------------------------------------------------------------ misc

    @Override
    public void recreateStructures(ICube cube) {}

    @Override
    public void recreateStructures(Chunk column) {}

    @Override
    public List<BiomeGenBase.SpawnListEntry> getPossibleCreatures(EnumCreatureType type, int x, int y, int z) {
        return BiomeGenBase.hell.getSpawnableList(type);
    }

    @Override
    public @Nullable ChunkPosition getNearestStructure(String name, int x, int y, int z) {
        return null;
    }
}
