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
The networks under the player go first (every column there waits for them). When a player jumps more than 8 columns
between two passes (a teleport), everything still queued is dropped, and queued work that no player is near any more is
skipped when its turn comes: before, a teleport left the new area waiting behind the old one.

**Teleport cost.** A cold area needs its river networks, and a network needs the exit (flow, water level) of every
basin upstream and the deltas of its lower-coloured coastal neighbours, recursively: 4 to 13 networks per teleport in
the benchmark. Those dependencies are built side by side (they were built one after another), and their exits are kept
in a small separate map (`RiverStage.exit`), so an evicted network is never rebuilt with its whole upstream chain just
because a downstream basin needs its exit. Inside a build, the real floor of the lakes (pipeline up to erosion, ~70 % of
a cold build) is sampled in parallel, and the coarse field lattices (`HeightGrid`) build each tile once (other threads
wait for it instead of computing it again; large climate tiles by rows in parallel). `/viridium stats` shows each phase
of the network builds.

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

### Results (2026-11-06, 48-thread machine)

Per-stage costs are measured on 8x8 patches of contiguous points (the cached lattices are warm, as in the game).

| | JS prototype | Java exact | Java fast |
|---|---|---|---|
| per land point, all stages | 77.5 µs | 52.4 µs | 11.4 µs |
| plates | — | 2.0 µs | 2.0 µs |
| final climate | 44.4 µs | 31.6 µs | 1.2 µs |
| erosion | 16.5 µs | 13.1 µs | 2.7 µs |
| rivers (networks cached) | — | 3.3 µs | 3.5 µs |
| biomes | — | 1.6 µs | 1.2 µs |
| column plan (16×16, + crust), 1 thread, Java 25 | — | ~13 ms | 2.4 ms |
| column plan, 47 threads | — | — | 0.20 ms wall |
| river network build (per drainage basin, cold, warm JIT) | ~390 ms (per plate) | — | 60–170 ms |
| teleport, cold caches: centre column / whole view (8 threads) | — | — | 0.2–0.35 s / 0.55–0.8 s |

River network phases (cold, teleport benchmark): its dependencies 100 ms (other networks), lakes 32 ms (floor
samples up to erosion), water levels 11 ms, valley field 12 ms, relief grid 6 ms, flood 2 ms, deltas 1 ms. In a fresh
JVM (no JIT yet) the first teleport takes 0.5–1.4 s to the centre column; before these changes 1.1–2.8 s.
`VIRIDIUM_BENCH=teleport` runs only the teleport scenario.

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
`deltaMaxRadius` (1600); big rivers build deltas rather than estuaries. Waterfalls at
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
the channels are carved last, a full block deep at the edges. If no branch can leave the apex (steep or mountain coast) the river keeps its course and gets an estuary, which the stage
turns into a fjord on glaciated coasts (as on such coasts in reality).
Deltas are shared: a branch that meets a branch of another delta joins it there (its water raised to that branch's
level if lower). Deltas of one network are traced largest river first; a coastal cell also joins the deltas of its
neighbouring coastal cells of lower colour ((ci & 1) | (cj & 1) << 1: no two neighbours share one, so the dependency
chain is at most 3 networks long and has no cycles). Branches stay within 0.6 × `MARGIN` of their cell, where the stage
still applies their network.

**Sea in the river grid**: a node is sea only where the relief is below sea level (pockets of slightly negative
continentality can be land: rivers stopped dead there, "mouths" inland).

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

## Landforms

Documented in the Obsidian vault (`docs/viridium/Worldgen/Landforms`); all Java-only, each off with its `GenParams`
value at 0 (`ParityTest` turns them off):

- **Plateaus** (`plateaus`, `plateauHeight`): tablelands 60–150 blocks above the plains behind an escarpment of ~15–20
  blocks; basalt traps climb in three ledges and turn the rock to basalt. The mask is lowered towards coasts and
  collisions, so plateaus end inland with their scarp. **Mesas** (`mesas`): arid sedimentary slopes terraced in 12-block
  steps.
- **Sea cliffs** (`cliffs`): about a third of the coast stands 14–60 blocks above the sea and drops in ~5 blocks; coves
  open pocket beaches.
- **Continental rifts** (`rifts`, `riftDepth`): some continents split along a meandering line: a graben 600–960 blocks
  wide between fault scarps and raised shoulders; young ones with lakes and volcanoes, failed ones worn down, mature
  ones a narrow sea.
- **Cliff detail** (`cliffDetail`, `column/CliffDetail`): from the planned heights of a column and its neighbours, the
  real foot of every wall gets a notch (flooded at the water line at the sea, above the talus on land), a talus cone and
  fallen boulders of the local rock (`FeatureType.BOULDER`).
- **Waterfalls** (`waterfalls`): main stems that drop 6+ blocks across a cliff (only near a scarp) keep their level to
  the lip; the reaches join there, the lower one digs a plunge pool and never cuts back behind the lip; curtains of
  falling water (`flowing_water` meta 8) from the plan (`fallTop`) and at every river lip standing 3+ blocks above a
  neighbour.

Scarps set `GenPoint.scarp`: erosion leaves them alone (its lattice gradient dug a moat along their foot or a rim along
their top). Map layer **Landforms**; debug: `VIRIDIUM_PROFILE`, `VIRIDIUM_CLIFF`, `VIRIDIUM_FALLMAP`; tests:
`CliffDetailTest`, `WaterfallTest` (`RiverWaterTest` skips waterfall zones).

## Caves

Documented in the vault (`docs/viridium/Worldgen/Caves`). `caves/CaveCarver` decides, per block of every crust and
surface cube, rock, air, water or lava:

- **noise caves** on an 8-block lattice: caverns, huge caverns below −150, tunnels (two noises near zero), fissures,
  lava tubes; the rock of the block sets the shape (karst big, crystalline narrow fissures, volcanic tubes, clastic
  in between, mantle only huge caverns); cells whose corners exclude a cave cost nothing;
- **aquifer cells** of 96×48×96 (dry, water, lava below −700) sealed by 2-block barriers; a lava sea in the last 48
  blocks; 8 blocks of rock under rivers, lakes and the sea; a 6-block roof except in entrance zones;
- **karst** (`Karst`): dolines in limestone and marble (2D, after the rivers) and shafts under a quarter of them;
  sea caves and cave mouths come from `CliffDetail`;
- **planned systems** (`CaveSystems`, 1024-block regions): giant chambers (up to ~360 blocks, some with a water or lava
  lake), galleries between regions, collapse pits (cenotes) and entrance galleries for shallow chambers.

Parameters `caves`, `caveEntrances`, `aquifers`, `dolines`, `plannedCaves`; tests in `CaveTest`
(`VIRIDIUM_CAVES=x0,x1,z,yTop,yBottom` draws a section). Cost: +0.1 ms per crust cube, +0.5 µs per 2D point.

## Memory

`VIRIDIUM_MEM=1 ./gradlew cleanTest test --tests '*MemoryAuditTest'` fills every cache as a long session would
(23 400 columns along 41 km) and measures what each one really retains after a full GC, plus what one unit of work
allocates. `/viridium stats` shows the cache occupancy and the heap in game. Results (2026-11-06):

| Retained | |
|---|---|
| column plans (2048 max, ~17.8 KiB each) | 36 MiB |
| river networks (64 max, ~1.05 MiB each) | 54 MiB at 52 cached |
| climate lattice (256 tiles) + erosion lattice (4096 tiles) | 7 + 8 MiB |
| river exits, hotspots, basin cells, thread locals | < 8 MiB |
| client map (outside the test): 512 tiles in memory + 384 textures | ~32 + 24 MiB (+ 24 MiB of textures) |

The generator keeps ~110 MiB; the `runClient` JVM may grow to 6 GiB (`-Xmx6G`, RetroFuturaGradle's default) and G1
keeps what it reserved, so the process size says little about live data. What drives the heap up is garbage. It was
cut from 14.5 GB to 1.15 GB in the audit:

- Map keys were `x << 32 | z` in a `Long`, whose hash is `x ^ z`: nearby tiles, columns and cells collided by the
  thousands. ConcurrentHashMap then turned bins into trees and reflected on `Long` at every lookup (a third of all
  allocations, and CPU); Guava caches had long chains. Keys now go through `GenMath.mixKey` (bijective).
- Per point: `nearCells` writes into the scratch arrays; basin cells and river networks are looked up first in small
  front arrays (no boxed key); lakes are sorted primitive arrays; the lattices are 4-way set-associative arrays
  instead of maps of boxed keys.
- Lattice tiles reuse per-thread temporaries instead of a `GenScratch` per row.

Per unit of work: a column plan allocates only its result (~18 KiB); a river network build ~8 MiB of temporaries (was
20); a 128² map tile at level 4 ~14 MiB (was 97).

## Known limitations

- River networks are built per drainage basin by the prefetcher before players reach them; teleporting far away still
  makes the first columns wait for the networks there (0.2–0.35 s on a 48-thread machine, 0.5–1.4 s right after start).
- Vegetation uses simplified shapes (the detailed Viridium tree generators are only used for saplings); no ores,
  caves or structures.
- Biomes map onto vanilla biome ids until Viridium registers its own.
- The map needs the world seed, so it only works in singleplayer.
- Mountains reach ~Y 650; vanilla tree generators refuse to grow above Y 256 or below Y 1.
