package net.junedev.viridium.config;

import net.minecraftforge.common.config.Configuration;

public class BiomeConfig {
    public int chaparralBiomeId = 100;

    public void synchronizeConfig(Configuration configuration) {
        chaparralBiomeId = configuration.getInt(
            "chaparralBiomeId", "biomes", chaparralBiomeId, 0, 255,
            "Stable ID for chaparral biome. Change it only before creating a world!");
    }
}
