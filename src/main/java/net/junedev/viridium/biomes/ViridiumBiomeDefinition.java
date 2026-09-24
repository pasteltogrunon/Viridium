package net.junedev.viridium.biomes;

import net.minecraftforge.common.BiomeDictionary;
import net.minecraftforge.common.BiomeManager;

public class ViridiumBiomeDefinition {
    public String name = "Default";
    public Generation generation = new Generation();
    public Climate climate = new Climate();
    public Terrain terrain = new Terrain();
    public Decoration decoration = new Decoration();
    public Appearance appearance = new Appearance();

    public static final class Generation{
        public int weight = 10;
        public BiomeManager.BiomeType climateType = BiomeManager.BiomeType.WARM;
        public BiomeDictionary.Type[] dictionaryTypes = new BiomeDictionary.Type[0];
    }

    public static final class Climate{
        public float temperature = 0.8F;
        public float rainfall = 0.4f;
        public boolean rain = true;
    }

    public static final class Terrain{
        public Height height = new Height();
    }

    public static final class Height{
        public float baseWeight = 0.2f;
        public float heightVariation = 0.2f;
    }

    public static final class Decoration{
        public int treesPerChunk = 0;
        public int grassPerChunk = 4;
        public int flowersPerChunk = 1;
    }

    public static final class Appearance {
        public String mapColor;
        public String grassColor;
        public String foliageColor;
    }
}
