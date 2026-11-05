package net.junedev.viridium.worldgen.terrain.stages;

/**
 * The 38 biomes of the prototype (order = BIOMES in worldgen.js). {@link #vanillaId} is the temporary Minecraft biome
 * used until Viridium registers its own.
 */
public enum Biome {

    FROZEN_OCEAN("Frozen ocean", 150, 172, 222, 10),
    DEEP_FROZEN_OCEAN("Deep frozen ocean", 108, 128, 190, 10),
    COLD_OCEAN("Cold ocean", 48, 72, 172, 0),
    DEEP_COLD_OCEAN("Deep cold ocean", 30, 44, 128, 24),
    OCEAN("Temperate ocean", 32, 92, 192, 0),
    DEEP_OCEAN("Deep temperate ocean", 20, 56, 140, 24),
    WARM_OCEAN("Warm ocean", 34, 156, 204, 0),
    DEEP_WARM_OCEAN("Deep warm ocean", 22, 104, 162, 24),
    BEACH("Beach", 240, 226, 160, 16),
    SNOWY_BEACH("Snowy beach", 245, 246, 242, 26),
    ROCKY_SHORE("Rocky shore", 140, 140, 134, 25),
    RIVER("River", 64, 112, 232, 7),
    GLACIER("Glacier / polar desert", 232, 244, 255, 12),
    TUNDRA("Tundra", 188, 198, 176, 12),
    SNOWY_TAIGA("Snowy taiga", 122, 152, 140, 30),
    TAIGA("Taiga", 48, 98, 70, 5),
    COLD_STEPPE("Cold steppe", 168, 168, 118, 1),
    GRASSLAND("Grassland", 140, 190, 88, 1),
    TEMPERATE_FOREST("Temperate forest", 58, 138, 50, 4),
    TEMPERATE_RAINFOREST("Temperate rainforest", 30, 100, 52, 32),
    SAVANNA("Savanna", 190, 180, 90, 35),
    DESERT("Desert", 236, 212, 142, 2),
    BADLANDS("Badlands", 202, 108, 58, 37),
    TROPICAL_RAINFOREST("Tropical rainforest", 22, 122, 22, 21),
    SWAMP("Swamp", 82, 110, 70, 6),
    MANGROVE("Mangrove", 60, 124, 84, 6),
    KARST("Limestone karst", 176, 186, 150, 3),
    ALPINE_MEADOW("Alpine meadow", 150, 192, 130, 34),
    SNOWY_SLOPES("Snowy slopes", 218, 224, 236, 13),
    ROCKY_PEAKS("Rocky peaks", 128, 122, 118, 3),
    SNOWY_PEAKS("Snowy peaks", 252, 252, 255, 13),
    DELTA("Delta / salt marsh", 118, 150, 104, 6),
    SEA_ICE("Sea ice floes", 214, 230, 246, 10),
    ICE_SHELF("Ice shelf", 246, 250, 255, 10),
    ICEBERG("Iceberg", 176, 228, 246, 10),
    LAKE("Lake", 52, 104, 196, 7),
    FROZEN_LAKE("Frozen lake", 196, 216, 238, 11),
    SALT_FLAT("Salt flat", 236, 232, 220, 2);

    public static final Biome[] VALUES = values();

    public final String displayName;
    public final int r, g, b;
    public final int vanillaId;

    Biome(String displayName, int r, int g, int b, int vanillaId) {
        this.displayName = displayName;
        this.r = r;
        this.g = g;
        this.b = b;
        this.vanillaId = vanillaId;
    }

    public int rgb() {
        return (r << 16) | (g << 8) | b;
    }
}
