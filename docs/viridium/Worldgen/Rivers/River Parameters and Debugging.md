# River Parameters and Debugging

Part of [[Rivers Overview]] · see also [[Worldgen Foundations|Pipeline Architecture]] for the other parameters.

## Parameters (GenParams)

| Parameter | Default | Effect | Page |
|---|---|---|---|
| `basinSize` | 3000 | size (blocks) of the drainage basin cells; also sets the network grid extent (≈ 3.4 × basinSize) | [[Drainage Basins]] |
| `basinMerge` | 0.8 | how strongly cells converge into large basins (0 = each cell runs straight to the nearest coast) | [[Drainage Basins]] |
| `riverGrid` | 32 | river grid spacing (blocks); the flow threshold scales with it | [[River Network Build]] |
| `riverThreshold` | 1.0 | flow unit; rivers start at `riverStart` units, widths are measured in these units | [[River Network Build]] |
| `riverStart` | 1.6 | flow at which a river starts: higher = fewer, longer tributaries (width does not change) | [[River Network Build]] |
| `riverRouteNoise` | 8 | routing bias amplitude (blocks): makes flow converge on smooth slopes and flats | [[River Network Build]] |
| `riverWidthGrowth` | 0.16 | linear extra half-width per flow unit (large rivers much wider) | [[River Network Build]] |
| `riverMaxHalfWidth` | 48 | cap on half-width | [[River Network Build]] |
| `meanders` | 1.0 | meander amplitude multiplier (capped at 1.5; 0 = straight rivers) | [[River Geometry and Meanders]] |
| `lakes` | 1.0 | lake depth threshold `2/lakes` (0 = no lakes) | [[Lakes and Salt Flats]] |
| `deltaSize` | 1.0 | delta radius multiplier (0 = no deltas) | [[River Mouths, Deltas and Estuaries]] |
| `deltaMaxRadius` | 1600 | cap on delta radius | [[River Mouths, Deltas and Estuaries]] |
| `estuaries` | 1.0 | estuary strength (capped at 1) and length | [[River Mouths, Deltas and Estuaries]] |
| `valleyWidth` | 1.0 | broad valley width multiplier | [[Valleys, Gorges and Glacial Troughs]] |
| `valleyRock` | 1.0 | how much the rock decides gorge vs open valley (0 = ignore rock) | [[Valleys, Gorges and Glacial Troughs]] |
| `glacialCooling` | 9 | °C colder at the last glacial maximum (0 = no glacial troughs or fjords) | [[Valleys, Gorges and Glacial Troughs]] |

Hard-coded constants worth knowing: `RiverNetBuilder.MARGIN` = 960 (how far a network shapes its neighbours),
`MAX_DROP` = 2.5 (max water drop per reach), `RiverStage.CACHE_MAX` = 64 networks, valley field limit 900 blocks.

## Cost

A network build samples ~99 000 nodes on the base relief, then evaluates the pipeline up to erosion ~21 times per river
node (valley floors) and a few times per lake. It is the most expensive operation of the generator, which is why it is
cached, parallelised and prefetched. Use `/viridium bench` (reports the number and time of network builds inside the
plan time) and `/viridium stats`.

## Tests and diagnostics

- **`RiverWaterTest.riverWaterStaysInItsChannel`**: generates column plans over an area and checks the water
  invariants: river water standing above adjacent dry ground in at most 2.5 % of river columns (0.5 % for walls of 2+
  blocks), cascades at most 12 blocks, water perched above its valley floor in at most 7 % of river columns.
- **`DebugPointTest`** (diagnostic, driven by environment variables):
  - `VIRIDIUM_POINTS="x,z;x,z"`: prints every pipeline value at those points;
  - `VIRIDIUM_MOUTHS=<half size>`: lists the biggest river mouths around the origin (with estimated vs real basin
    flow);
  - `VIRIDIUM_LAKES=<half size>`: biggest lakes and the river reaches inside them;
  - confluences, `VIRIDIUM_REACHES="x,z,r"` (reaches of every basin net near a point), a water picture, and map tile
    timings.
- **In-game map layers**: "Rivers, lakes, deltas" and "Valleys and glaciation" (`MapLayer`), plus "Final height".

## Common symptoms and where to look

| Symptom | Likely place |
|---|---|
| Wall of water above the bank | water level higher than the terrain next to the channel: [[River Water Levels]], channel bank shaping in [[River Stage Evaluation#Channels]] |
| River floating above its valley | valley floor sampling, floodplain clamp: [[River Water Levels]] |
| Staircase at a confluence | floodplain clamp and `MAX_DROP`: [[River Water Levels#4. Tributaries]] |
| River cut at a basin divide | exit / entry nodes, phases, `MARGIN`: [[Drainage Basins#Exit nodes]], [[River Stage Evaluation#Phases]] |
| Many parallel small rivers | routing noise: [[River Network Build#Receivers with routing bias]] |
| Huge river on a small island | outlet rule: [[Drainage Basins#Linking - who drains into whom]] |
| Crossing delta channels | sectors and foreign branches: [[River Mouths, Deltas and Estuaries#Delta builder (buildDelta)]] |
| Lake above its rim | spill clamp and floor anchoring: [[Lakes and Salt Flats#Lake level]] |
