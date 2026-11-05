# Viridium terrain generator (prototype)

Java port of the web prototype in `ViridiumExtras/worldgen` (`js/worldgen.js`), plugged into Cubic Chunks.
Select the **Viridium** world type when creating a world (or `level-type=viridium` on a server).
Press **M** in game (singleplayer) for the world map.

## Layout

| Package | What lives there | Minecraft classes? |
|---|---|---|
| `core` | `Noise` (hash/perlin/fbm/ridged, bit-compatible with the JS), `GenMath`, `GenParams`, `Seeds` | no |
| `pipeline` | `GenPoint` (per-point result), `GenScratch` (per-thread temporaries), `Stage` | no |
| `stages` | one class per step: climate base, plates, continents, relief, volcanism, geology, final climate, erosion, rivers (`RiverNet`, `RiverNetBuilder`), cryosphere, biomes (`Biome`) | no |
| `fields` | `HeightGrid` (cached coarse relief lattice) and the fast climate / erosion stages that use it | no |
| `crust` | `CrustModel` (3D lithology by depth), `CrustPlan`, `Lithology`, `Province` | no |
| `column` | `ColumnPlanner` → immutable `ColumnPlan` (everything X/Z-only for a 16×16 column), `ColumnPlanCache`, `BiomeMapper`, `TerrainLayout` | no |
| `map` | `MapLayer` (every layer), `MapTileRenderer`, `CrustSectionRenderer` | no |
| `cube` | `ViridiumWorldType`, `ViridiumWorldGenerator` (CC `IWorldGenerator`), `CubeFiller`, `SurfaceRules`, `SurfaceDecorator`, `LithologyBlocks`, `/viridium bench` | yes |
| `bench` | `TerrainBenchmark` (headless) | no |
| `client/map` (outside) | `WorldMapScreen`, `MapTileCache`, key binding | yes |

Everything except `cube` and the client screen runs without Minecraft: the map, the benchmarks and the tests share the
exact same code as the game.

## Column once, cubes many

Cubic Chunks asks for 16³ cubes in any order. `ViridiumWorldGenerator.provideCube` fetches the column's `ColumnPlan`
from `ColumnPlanCache` (computed once, shared by every cube of the column, deduplicated across threads, precomputed on
CC's background threads through `IPreloadFailureDelegate`). `CubeFiller` then classifies the cube:

- above terrain, water and ice → no storage (air);
- deeper than the surface rules reach → crust only (lithology per block from the column's crust profile);
- otherwise (≈ 3 cubes per column) → surface rules, water, ice and snow.

Population (`SurfaceDecorator`) uses the vanilla +8 offset and generates the 2×3×2 cubes it can touch first.

## The crust

Each column gets a geological province (platform, basin, shield, orogen, arc, large igneous province, margin, oceanic,
volcanic island) and a profile in absolute Y: cover → upper crust → middle crust (metamorphic grade rises with depth:
mica schist → gneiss → amphibolite → migmatite; blueschist near subduction) → lower crust (amphibolite, gabbro,
eclogite under collision roots) → Moho → peridotite mantle. Oceanic crust is an ophiolite sequence (pelagic sediment →
basalt → dolerite dikes → gabbro). The crust thickens under mountains and thins towards the ocean, so the Moho sits
around Y −1200 under plains, deeper under ranges, and the mantle is reachable under the oceans. Strata are placed in
absolute Y (flat or gently dipping in platforms, folded in orogens), so valleys cut through them. 3D bodies: granite
plutons with pegmatite rims, dolerite dikes, kimberlite pipes, chromitite pods. Blocks are the Viridium rocks of the
same name (`ViriBlocks`). The map's "Crust section" panel draws a vertical cut along the map centre.

## Modifying a stage

Stages are stateless classes reading `GenParams` and `Seeds`. To try a variant, subclass and plug it in:

```java
WorldGen gen = new WorldGen.Builder(params)
    .relief(MyFlatterRelief::new)
    .build();
```

A new map layer is one `MapLayer.register(...)` line in `MapLayer`.

## Tests and benchmarks

- `ParityTest` compares the exact port (`GenParams.fastFields = false`) with values dumped from the JS prototype:
  stages 1–7 and the full pipeline (rivers, lakes, deltas, estuaries, ice, biomes). Currently 0 differing points.
  Regenerate after changing `worldgen.js`: `node tools/worldgen/parity_dump.js ../ViridiumExtras/worldgen/js/worldgen.js`
- Headless benchmark: `VIRIDIUM_BENCH=1 ./gradlew test --tests '*TerrainBenchmarkTest'` → `build/reports/worldgen-bench.txt`
- Map previews (PNG of any layer + crust sections): `VIRIDIUM_MAP=1 VIRIDIUM_MAP_LAYERS=composite VIRIDIUM_MAP_LEVEL=4 ./gradlew test --tests '*MapPreviewTest'` → `build/reports/map/`
- In game: `/viridium bench [radius] [cubesDown] [cubesUp]` (op) measures plans + real cube storage around you without
  touching the world. `VIRIDIUM_BENCH_ON_START=1 ./gradlew runServer` runs it at server start.

### Results (2026-11-05, 48-thread machine)

| | JS prototype | Java exact | Java fast |
|---|---|---|---|
| per land point, all stages | 77.5 µs | 46.9 µs | 11.3 µs |
| final climate | 44.4 µs | 29.1 µs | 1.7 µs |
| erosion | 16.5 µs | 11.1 µs | 3.1 µs |
| column plan (16×16, + crust), 1 thread, Java 25 | — | ~12 ms | 1.8 ms |
| column plan, 47 threads | — | — | 0.48 ms wall |
| river network build (per plate) | ~390 ms | — | ~230 ms, ~1.7 MiB |

In the dev server (`runServer`, **Java 8**): 4.9 ms per column plan, 0.17 ms per cube (crust-only 0.23 ms,
surface-crossing 0.12 ms, air 0). A column has 192 cubes in the generation range: 31 % air, 67 % crust only, ≈ 3.3
crossing the surface. Fast vs exact: |ΔT| mean 0.007 °C, |Δh| mean 0.03 blocks, dominant biome differs on 0.25 % of
points.

## Known limitations

- River networks are built per plate on first use (~230 ms). Background preloading usually hides it, but a player
  entering a new plate can still cause a hitch on the server thread.
- Population is minimal (vanilla + three Viridium trees, grass, flowers, desert plants); no ores, caves or structures.
- Biomes map onto vanilla biome ids until Viridium registers its own.
- The map needs the world seed, so it only works in singleplayer.
- Mountains reach ~Y 650; vanilla tree generators refuse to grow above Y 256 or below Y 1.
