package net.junedev.viridium;

import net.junedev.viridium.biomes.BiomeGenChaparral;
import net.junedev.viridium.config.Config;
import net.minecraft.world.biome.BiomeGenBase;
import net.minecraftforge.common.BiomeDictionary;
import net.minecraftforge.common.BiomeManager;

public class ViriBiomes {

    public void preInit(){
        chaparral = new BiomeGenChaparral(Config.biomeConfig.chaparralBiomeId);

        BiomeDictionary.registerBiomeType(chaparral, BiomeDictionary.Type.HILLS, BiomeDictionary.Type.PLAINS);
        BiomeManager.addBiome(BiomeManager.BiomeType.WARM, new BiomeManager.BiomeEntry(chaparral, 101));
    }

    public static BiomeGenBase chaparral;
}
