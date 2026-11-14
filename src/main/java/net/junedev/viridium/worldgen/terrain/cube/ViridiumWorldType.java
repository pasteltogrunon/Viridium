package net.junedev.viridium.worldgen.terrain.cube;

import javax.annotation.ParametersAreNonnullByDefault;

import net.junedev.viridium.Config;
import net.junedev.viridium.Viridium;
import net.junedev.viridium.worldgen.nether.NetherLayout;
import net.junedev.viridium.worldgen.nether.NetherWorldGenerator;
import net.junedev.viridium.worldgen.terrain.column.TerrainLayout;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraft.world.WorldType;
import net.minecraft.world.chunk.IChunkProvider;

import com.cardinalstar.cubicchunks.api.IntRange;
import com.cardinalstar.cubicchunks.api.world.ICubicWorldType;
import com.cardinalstar.cubicchunks.api.worldgen.IWorldGenerator;
import com.cardinalstar.cubicchunks.mixin.api.ICubicWorldInternal;
import com.cardinalstar.cubicchunks.world.ICubicWorld;

/**
 * "Viridium" world type: the overworld uses {@link ViridiumWorldGenerator} and the Nether {@link NetherWorldGenerator}
 * (unless disabled in the config); other dimensions are untouched.
 */
@ParametersAreNonnullByDefault
public class ViridiumWorldType extends WorldType implements ICubicWorldType {

    public static final int NETHER = -1;

    public static ViridiumWorldType INSTANCE;

    private ViridiumWorldType() {
        super("viridium");
    }

    public static void init() {
        if (INSTANCE == null) INSTANCE = new ViridiumWorldType();
    }

    @Override
    public IWorldGenerator createCubeGenerator(WorldServer world, IChunkProvider chunkGenerator) {
        if (world.provider.dimensionId == NETHER) {
            ensureHeightBounds(world, NetherLayout.MIN_Y - 16, NetherLayout.MAX_Y);
            return new NetherWorldGenerator(world);
        }
        ensureHeightBounds(world, TerrainLayout.MIN_Y - 16, TerrainLayout.MAX_Y);
        return new ViridiumWorldGenerator(world);
    }

    /**
     * Extends the world bounds so the deep crust exists. One extra cube below the generation minimum holds the bedrock
     * floor (and avoids an early-return in CC's {@code setHeightBounds}, which ignores ranges that start at the
     * generation minimum).
     */
    private static void ensureHeightBounds(WorldServer world, int wantedMin, int wantedMax) {
        ICubicWorld cubic = (ICubicWorld) world;
        if (cubic.getMinHeight() > wantedMin) {
            int max = Math.max(cubic.getMaxHeight(), wantedMax);
            ((ICubicWorldInternal) world).setHeightBounds(wantedMin, max);
            Viridium.LOGGER.info(
                "Viridium world height of dimension {} set to [{}, {})",
                world.provider.dimensionId,
                wantedMin,
                max);
        }
    }

    @Override
    public IntRange getGenerationRange(World world) {
        if (world.provider.dimensionId == NETHER) return new IntRange(NetherLayout.MIN_Y, NetherLayout.MAX_Y);
        return new IntRange(TerrainLayout.MIN_Y, TerrainLayout.MAX_Y);
    }

    @Override
    public boolean hasCubicGeneratorForWorld(World world) {
        int dim = world.provider.dimensionId;
        return dim == 0 || (dim == NETHER && Config.viridiumNether);
    }
}
