package net.junedev.viridium.worldgen.terrain.cube.flora;

import net.minecraft.block.Block;

/** Receives the blocks of a feature shape (absolute coordinates); see {@link FeatureType#draw(FloraSink, Feature)}. */
public interface FloraSink {

    void log(int x, int y, int z, Block b, int meta);

    void leaves(int x, int y, int z, Block b, int meta);

    void plant(int x, int y, int z, Block b, int meta);

    /** Rock (boulders): replaces air, snow, plants, leaves and water. */
    void rock(int x, int y, int z, Block b, int meta);
}
