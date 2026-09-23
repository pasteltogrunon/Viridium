package net.junedev.viridium.biomes;

import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.world.World;
import net.minecraft.world.biome.BiomeGenBase;

import java.util.Random;

public class BiomeGenChaparral extends BiomeGenBase {

    public BiomeGenChaparral(int id) {
        super(id);

        setBiomeName("Chaparral");
        setTemperatureRainfall(0.8F, 0.2F);
        setHeight(new Height(0.2F, 0.2F));

        this.topBlock = Blocks.grass;
        this.fillerBlock = Blocks.dirt;

        theBiomeDecorator.treesPerChunk = -999;
        theBiomeDecorator.grassPerChunk = 20;
        theBiomeDecorator.flowersPerChunk = 4;

        setColor(12431967);
    }

    @Override
    public void genTerrainBlocks(
        World world,
        Random random,
        Block[] blocks,
        byte[] metadata,
        int x,
        int z,
        double noise) {

        topBlock = Blocks.grass;
        fillerBlock = Blocks.dirt;

        if (noise > 2.5D) {
            topBlock = Blocks.stone;
            fillerBlock = Blocks.stone;
        } else if (noise > 2.0D) {
            topBlock = Blocks.sand;
            fillerBlock = Blocks.sand;
        }

        genBiomeTerrain(world, random, blocks, metadata, x, z, noise);
    }

}
