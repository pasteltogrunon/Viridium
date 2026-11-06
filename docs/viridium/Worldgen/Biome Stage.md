# Biome Stage (stage 12)

Part of [[Worldgen Stages]] · depends on: [[Final Climate]], [[Erosion]] (slope), [[Surface Geology]],
[[Rivers Overview|River Stage Evaluation]] (river, lake, delta, estuary), [[Cryosphere]] · used by: Column Plans,
Cube Filling and Surface Rules, Flora. Design: Biomes, Ecoregions.

Code: `stages/BiomeStage.java`, `stages/Biome.java`, `column/BiomeMapper.java`.

## Soft memberships

Every one of the 38 biomes gets a weight that is a product of smooth functions (`sigLo`, `sigHi`, `band`, see
Noise and Math Primitives) of:

- height `h` (sea / shore below 4 blocks / inland / high above 125 / peaks above 175; deep sea below −26..−42),
- temperature `T` and log-precipitation `ln P`,
- slope (beach vs rocky shore, swamps only on flats, karst only on slopes),
- rock (`BADLANDS` needs sandstone, `KARST` limestone),
- water and ice: `river` gives `RIVER` (×1.5), `max(delta, estu)` gives `DELTA` ("delta / salt marsh"), sea ice,
  shelves and icebergs give their own biomes,
- lakes override everything: `LAKE`, `FROZEN_LAKE` or `SALT_FLAT`.

The weights are then **sharpened** (raised to `biomeSharpness` = 3) and normalised. The result is `biomeW[]`, the
dominant biome `biome`, and a blended colour for the map.

## Biomes

Oceans (frozen / cold / temperate / warm, each shallow and deep), beach, snowy beach, rocky shore, river,
glacier / polar desert, tundra, snowy taiga, taiga, cold steppe, grassland, temperate forest, temperate rainforest,
savanna, desert, badlands, tropical rainforest, swamp, mangrove, limestone karst, alpine meadow, snowy slopes, rocky
peaks, snowy peaks, delta / salt marsh, sea ice floes, ice shelf, iceberg, lake, frozen lake, salt flat.

## Minecraft biome ids

Viridium does not register its own biomes yet. Each `Biome` has a temporary vanilla id (`vanillaId`) and
`BiomeMapper.FROM_STAGE` writes it into the chunk's biome array. `BiomeMapper.VANILLA` is an older climate-only mapping
kept as a fallback.
