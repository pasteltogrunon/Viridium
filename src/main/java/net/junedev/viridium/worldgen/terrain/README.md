# Viridium terrain generator (prototype)

Java port of the web prototype in `ViridiumExtras/worldgen` (`js/worldgen.js`), plugged into Cubic Chunks.
Select the **Viridium** world type when creating a world (or `level-type=viridium` on a server).

## Layout

| Package | What lives there | Minecraft classes? |
|---|---|---|
| `core` | `Noise` (hash/perlin/fbm/ridged, bit-compatible with the JS), `GenMath`, `GenParams`, `Seeds` | no |
| `pipeline` | `GenPoint` (per-point result), `GenScratch` (per-thread temporaries), `Stage` | no |
| `stages` | one class per pipeline step: `ClimateBaseStage`, `PlateStage`, `ContinentStage`, `ReliefStage`, `VolcanismStage`, `GeologyStage`, `Rock` | no |
| `WorldGen` | facade: `evaluate(x, z, stage, point)`, `heightAt`; `WorldGen.Builder` swaps stages | no |
| `column` | `ColumnPlanner` → immutable `ColumnPlan` (all X/Z work for a 16×16 column), `ColumnPlanCache`, `BiomeMapper`, `TerrainLayout` (sea level, min/max Y) | no |
| `cube` | `ViridiumWorldType`, `ViridiumWorldGenerator` (CC `IWorldGenerator`), `CubeFiller` | yes |
| `bench` | `TerrainBenchmark` (headless) | no |

Everything except `cube` runs without Minecraft, so the map, the benchmark and the tests share the exact same code.

## Column once, cubes many

Cubic Chunks asks for 16³ cubes in any order. `ViridiumWorldGenerator.provideCube` fetches the column's `ColumnPlan`
from `ColumnPlanCache` (computed once, shared by every cube of the column, deduplicated across threads) and
`CubeFiller` classifies the cube before touching blocks:

- above the highest surface and the sea → no storage (air);
- deeper than the surface rules reach → uniform rock;
- otherwise (≈ 3 cubes per column) → per-block surface work.

Plans are precomputed on CC's background threads via `IPreloadFailureDelegate`.

## Modifying a stage

Stages are stateless classes reading `GenParams` and `Seeds`. To try a variant, subclass the stage and plug it in:

```java
WorldGen gen = new WorldGen.Builder(params)
    .relief(MyFlatterRelief::new)
    .build();
```

Parameters live in `GenParams` (defaults = the JS `DEFAULTS`).

## Tests and benchmark

- `ParityTest` compares the Java port with reference values dumped from the JS prototype. Regenerate them after
  changing `worldgen.js`:
  `node tools/worldgen/parity_dump.js ../ViridiumExtras/worldgen/js/worldgen.js`
- Benchmark (real case: contiguous columns, single- and multi-threaded, cube mix):
  `VIRIDIUM_BENCH=1 ./gradlew test --tests '*TerrainBenchmarkTest'` → `build/reports/worldgen-bench.txt`

## Status

Ported: stages 1–7 (base climate, plates, continents, relief + volcanism, geology). Pending (see the plan): final
climate on a coarse grid, erosion, rivers/lakes, cryosphere, biome stage, 3D crust, surface rules, population,
in-game map.
