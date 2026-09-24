package net.junedev.viridium;

import net.junedev.viridium.utils.ColorParser;
import net.junedev.viridium.biomes.ViridiumBiomeDefinition;
import net.junedev.viridium.biomes.ViridiumBiomeDefinitionLoader;
import net.junedev.viridium.biomes.ViridiumBiomeGen;
import net.minecraft.world.biome.BiomeGenBase;
import net.minecraftforge.common.BiomeDictionary;
import net.minecraftforge.common.BiomeManager;

public class ViriBiomes {
    private int currentBiomeIndex = 100;

    public void preInit(){

        currentBiomeIndex = Config.firstBiomeId;

        registerBiomeFromFile("chaparral.json");
        registerBiomeFromFile("deciduous_forest.json");
        registerBiomeFromFile("fen.json");
    }

    public static BiomeGenBase chaparral;

    void registerBiomeFromFile(String path){
        ViridiumBiomeDefinition definition = ViridiumBiomeDefinitionLoader.loadBuiltin(path);

        if(definition == null) return;
        // Should the game crash?

        registerBiome(definition);
    }

    void registerBiome(ViridiumBiomeDefinition definition) {
        if(currentBiomeIndex > 255) {
            throw new IllegalStateException("Maximum biome index reached. Cannot add another biome.");
        }
        if (BiomeGenBase.getBiomeGenArray()[currentBiomeIndex] != null) {
            throw new IllegalStateException(
                "Biome ID " + currentBiomeIndex + " is already occupied.");
        }

        ViridiumBiomeGen biome = new ViridiumBiomeGen(currentBiomeIndex);

        biome.setBiomeName(definition.name);
        biome.setTemperatureRainfall(definition.climate.temperature, definition.climate.rainfall);
        if(!definition.climate.rain) biome.setDisableRain();

        biome.setHeight(new BiomeGenBase.Height(definition.terrain.height.baseWeight, definition.terrain.height.heightVariation));

        if (definition.appearance.mapColor != null) biome.setMapColor(ColorParser.parseRgb(definition.appearance.mapColor));
        if (definition.appearance.grassColor != null) biome.setGrassColorOverride(ColorParser.parseRgb(definition.appearance.grassColor));
        if (definition.appearance.foliageColor != null) biome.setFoliageColorOverride(ColorParser.parseRgb(definition.appearance.foliageColor));


        biome.theBiomeDecorator.treesPerChunk = definition.decoration.treesPerChunk;
        biome.theBiomeDecorator.grassPerChunk = definition.decoration.grassPerChunk;
        biome.theBiomeDecorator.flowersPerChunk = definition.decoration.flowersPerChunk;

        BiomeDictionary.registerBiomeType(biome, definition.generation.dictionaryTypes);
        BiomeManager.addBiome(definition.generation.climateType, new BiomeManager.BiomeEntry(biome, definition.generation.weight));

        currentBiomeIndex++;
    }
}
