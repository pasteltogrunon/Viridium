package net.junedev.viridium.worldgen.terrain.cube;

import javax.annotation.ParametersAreNonnullByDefault;

import net.junedev.viridium.Viridium;
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

/** "Viridium" world type: the overworld uses {@link ViridiumWorldGenerator}; other dimensions are untouched. */
@ParametersAreNonnullByDefault
public class ViridiumWorldType extends WorldType implements ICubicWorldType {

    public static ViridiumWorldType INSTANCE;

    private ViridiumWorldType() {
        super("viridium");
    }

    public static void init() {
        if (INSTANCE == null) INSTANCE = new ViridiumWorldType();
    }

    @Override
    public IWorldGenerator createCubeGenerator(WorldServer world, IChunkProvider chunkGenerator) {
        ensureHeightBounds(world);
        return new ViridiumWorldGenerator(world);
    }

    /**
     * Extends the world bounds so the deep crust exists. One extra cube below {@link TerrainLayout#MIN_Y} holds the
     * bedrock floor (and avoids an early-return in CC's {@code setHeightBounds}, which ignores ranges that start at
     * the generation minimum).
     */
    private static void ensureHeightBounds(WorldServer world) {
        ICubicWorld cubic = (ICubicWorld) world;
        int wantedMin = TerrainLayout.MIN_Y - 16;
        if (cubic.getMinHeight() > wantedMin) {
            int max = Math.max(cubic.getMaxHeight(), TerrainLayout.MAX_Y);
            ((ICubicWorldInternal) world).setHeightBounds(wantedMin, max);
            Viridium.LOGGER.info("Viridium world height set to [{}, {})", wantedMin, max);
        }
    }

    @Override
    public IntRange getGenerationRange(World world) {
        return new IntRange(TerrainLayout.MIN_Y, TerrainLayout.MAX_Y);
    }

    @Override
    public boolean hasCubicGeneratorForWorld(World world) {
        return world.provider.dimensionId == 0;
    }
}
