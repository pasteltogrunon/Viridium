# Viridium terrain generator (prototype)

Java port of the web prototype in `ViridiumExtras/worldgen` (`js/worldgen.js`), plugged into Cubic Chunks.
Select the **Viridium** world type when creating a world (or `level-type=viridium` on a server).
Press **M** in game (singleplayer) for the world map. It shares the server's pipeline (warm caches and river
networks), keeps finished tiles in memory between openings and on disk in `.minecraft/viridium/mapcache/<fingerprint>`
(the fingerprint covers the parameters, the seed and the generator code, so changes never show stale tiles), and
draws rivers only up to level 4 (further out it would build hundreds of networks). It renders on at most 2
low-priority threads, and the river networks it needs are built on those threads only. Beyond 128 blocks per pixel
the climate samples the relief directly (its 32-block lattice would compute a 2048-block tile per pixel).

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
| `cube` | `ViridiumWorldType`, `ViridiumWorldGenerator` (CC `IWorldGenerator`), `CubeFiller`, `SurfaceRules`, `flora` (planned vegetation), `PlanPrefetcher`, `LithologyBlocks`, `/viridium bench|stats` | yes |
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

`PlanPrefetcher` computes the plans of the columns around every player (view distance + 3, nearest first, a bit
ahead of the movement) and the river networks of nearby drainage basins on its own threads, so the server thread rarely waits.

Vegetation is **planned, not populated**: `FloraPlanner` decides each column's trees and plants from its plan alone
(deterministic per column), and every air or surface cube draws the parts of the features of the 3×3 surrounding
columns that fall inside it, straight into its storage. Population therefore touches no other cube, never calls
`setBlock` and queues no light updates. Tree shapes live in `FeatureType` (vanilla-like oak, birch, spruce, pine,
jungle, mega jungle, acacia, swamp oak, and simplified Viridium pedunculate oak, chestnut and coconut palm).

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

- The JS prototype is no longer the reference (2026-11-05): the generator is free to diverge. `ParityTest` still
  checks stages 1–7 against values dumped from it, only as a cheap regression guard; delete it when those stages change.
- `RiverWaterTest` walks river networks and checks the water sits in its channel at the valley bottom (no water
  walls, small cascades only).
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
| river network build (per drainage basin) | ~390 ms (per plate) | — | ~260 ms |

In the dev server (`runServer`, **Java 8**): 4.9 ms per column plan, 0.17 ms per cube (crust-only 0.23 ms,
surface-crossing 0.12 ms, air 0). A column has 192 cubes in the generation range: 31 % air, 67 % crust only, ≈ 3.3
crossing the surface. Fast vs exact: |ΔT| mean 0.007 °C, |Δh| mean 0.03 blocks, dominant biome differs on 0.25 % of
points.

## Rivers

The network (priority-flood on a 32-block grid of the base relief) decides where rivers run and how much water they
carry. Their water level is then projected onto the final, eroded terrain: every river node takes the floor of the
valley around it (cross-sections at the node and towards its receiver) and the profile is made monotone downstream.
The channel is carved below that level and the first blocks of bank are kept at least at water level. Lakes are
anchored to the real basin floor the same way.

Continents are split into **drainage basins** (`BasinGraph`): Voronoi cells of warped coordinates, `basinSize`
(3000 blocks) apart. Coastal cells drain to the sea; an inland cell drains into the lower neighbour with the lowest
"potential" (its land height plus a large-scale noise weighted by `basinMerge`, so basins chain into a few very large
systems), through the lowest point of their shared divide. Every basin gets its own network with a margin around it;
the water of the upstream basins enters at that divide point with its flow and water level. The size of the whole
upstream system decides the size of the mouth: half width 1.2 + 2.2·√q + `riverWidthGrowth`·q (q = flow in threshold
units; flow 100 → ~40) up to `riverMaxHalfWidth` (48, ~100-block wide rivers) and delta radius 95·√q up to
`deltaMaxRadius` (1600); one delta per stretch of coast (smaller rivers within reach of a larger delta end in it); big rivers build deltas rather than estuaries. Waterfalls at
confluences are limited to 2.5 blocks per node (glacial hanging valleys can still drop ~10). Lake rims never cross a
river channel or its banks, so rivers flow into and out of lakes freely. A cell with more than 10 % sea in its area
is an outlet (its network sends its water to that coast; handing its whole flow to a neighbour made huge rivers appear
out of nowhere, e.g. on islets).

**Deltas** (`RiverNetBuilder.buildDelta`, `RiverStage.deltas`): the main stem is cut at an apex up to 0.35 R upstream
of the mouth (never above a real tributary: it would be left at the old river's level), and the reaches below it are
skipped. From the apex, distributaries are traced in steps towards the open water around the mouth, each choosing
the lowest of a few headings; every branch owns an angular sector around the apex and a split divides it between both
children, so branches never cross. They run until they are out on the sea. Their water falls linearly from the
river's level at the apex to sea level at the old coast. The delta plain (just above the water of the branches,
blended between them) is only built around the branches, narrowing towards their tips, over shallow water and low land;
the channels are carved last, a full block deep at the edges. If no branch can leave the apex the river stays as it was.

**Flow between basins**: a basin receives the flow that really reaches the exit node of its upstream networks
(`RiverNet.exitFlow`), not the graph's estimate: an "inner" cell with a few inlets of its own sends most of its water
there, and the estimate made huge rivers start out of nowhere where it entered the next basin.

**Plate edges** (`exactPlateEdges`, off in `ParityTest`): the distance to the plate edge is a soft minimum over the
cell's sides, and the distance to non-collisional boundaries is measured to their real segments (bisectors clipped at
the triple junctions); coasts that follow a boundary are bent by noise. Before, the hard minimum creased along straight
lines from every corner and the bisector lines ran on past triple junctions, cutting straight wedges into the coast.

**Confluences**: a small river within reach of a much larger one (3× the flow, 2.5× the width) has its water no higher
than the large river's there (+0.5): otherwise its last reach hid its drop under the large river and showed it all at
once at the bank (a staircase), or it ran alongside on a perched terrace. Deep river channels below sea level keep their
river water (only river water at sea level gives way to the sea).

Flow routing: the priority flood only decides the filled surface (lakes); each node then drains to the steepest
strictly lower neighbour on that surface plus a gentle routing noise (`riverRouteNoise`, 8 blocks at ~450-block
scale). Without it, smooth slopes and flats drained in parallel lines (a river on every grid node, and the broad valley
flattened the whole area). A river starts at `riverStart` (1.6) times the threshold flow; widths use the threshold.

Scales: plates/continents `plateSize` 12000, temperature period 48000, precipitation period 36000 (3× the prototype;
`ParityTest` sets the prototype values back).

## Known limitations

- River networks are built per drainage basin (~0.26 s, parallel) by the prefetcher before players reach them; teleporting
  far away can still make the server thread wait for one.
- Vegetation uses simplified shapes (the detailed Viridium tree generators are only used for saplings); no ores,
  caves or structures.
- Biomes map onto vanilla biome ids until Viridium registers its own.
- The map needs the world seed, so it only works in singleplayer.
- Mountains reach ~Y 650; vanilla tree generators refuse to grow above Y 256 or below Y 1.
