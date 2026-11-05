package net.junedev.viridium.worldgen.terrain.column;

import net.junedev.viridium.worldgen.terrain.pipeline.GenPoint;
import net.junedev.viridium.worldgen.terrain.stages.Biome;

/** Chooses the Minecraft biome id of a column from the evaluated point. Swap it to use custom biomes. */
public interface BiomeMapper {

    int biomeId(GenPoint p);

    /** Default: the dominant biome of the biome stage, mapped onto its temporary vanilla biome. */
    BiomeMapper FROM_STAGE = p -> Biome.VALUES[p.biome].vanillaId;

    /**
     * Temporary mapping onto vanilla biome ids (numeric, so the planner stays free of Minecraft classes) until the
     * biome stage is ported.
     */
    BiomeMapper VANILLA = p -> {
        final int OCEAN = 0, PLAINS = 1, DESERT = 2, EXTREME_HILLS = 3, FOREST = 4, TAIGA = 5, SWAMP = 6,
            FROZEN_OCEAN = 10, ICE_PLAINS = 12, ICE_MOUNTAINS = 13, BEACH = 16, JUNGLE = 21, DEEP_OCEAN = 24,
            STONE_BEACH = 25, COLD_BEACH = 26, ROOFED_FOREST = 29, COLD_TAIGA = 30, SAVANNA = 35;
        double h = p.h;
        double t = p.Tb - 0.07 * Math.max(0, h);
        double rain = p.Pb;
        if (h < 0) {
            if (p.Tb < -2) return FROZEN_OCEAN;
            return h < -30 ? DEEP_OCEAN : OCEAN;
        }
        if (h < 2) {
            if (t < 0) return COLD_BEACH;
            return p.mtnMask > 0.4 ? STONE_BEACH : BEACH;
        }
        if (h > 220) return t < 0 ? ICE_MOUNTAINS : EXTREME_HILLS;
        if (t < -5) return ICE_PLAINS;
        if (t < 3) return rain > 500 ? COLD_TAIGA : ICE_PLAINS;
        if (t < 10) return rain > 600 ? TAIGA : PLAINS;
        if (t < 20) {
            if (rain < 450) return PLAINS;
            if (rain < 1300) return FOREST;
            return h < 8 ? SWAMP : ROOFED_FOREST;
        }
        if (rain < 350) return DESERT;
        if (rain < 1000) return SAVANNA;
        return rain > 1700 ? JUNGLE : FOREST;
    };
}
