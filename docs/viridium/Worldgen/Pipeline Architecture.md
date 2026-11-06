# Pipeline Architecture

Part of [[Worldgen Foundations]].

Code: `WorldGen.java`, `pipeline/` (`GenPoint`, `GenScratch`, `Stage`), `core/` (`GenParams`, `Seeds`, `Noise`,
`GenMath`), `stages/GenStage.java`.

## WorldGen

`WorldGen` owns one instance of every stage plus the 3D Crust model. It is built from a `GenParams` (copied, then
treated as immutable) and derives a `Seeds` object from the seed.

```java
GenPoint evaluate(double x, double z, Stage upTo, double bpp, GenPoint out, GenScratch scratch)
```

runs the stages in order and stops after `upTo`:

```
climate → plates → continents → relief (h0) → geology → climateFinal → erosion → rivers → cryo → biomes
```

- `upTo` lets callers ask for a partial result. Many stages do this internally: the river builder samples the terrain
  "after erosion, before rivers" (`Stage.EROSION`), the basin graph only needs plates + continents, and so on.
- `bpp` (blocks per pixel) is 1 in game. The map uses larger values; stages use it to fade out detail that would alias
  when seen from far away (erosion octaves, sea ice floes) and to skip coarse caches that would thrash.
- `heightAt(x, z, detail)` is a shortcut for plates + continents + relief, used by stages that need the base height at
  other points (climate, erosion gradient). `detail = false` skips the finest octaves.

### Replacing a stage

Every stage is a separate class with a `protected` API. `WorldGen.Builder` lets you plug a subclass in without
touching the others:

```java
new WorldGen.Builder(params).rivers(MyRiverStage::new).build();
```

`GenParams.fastFields` (default true) replaces the final climate and erosion stages with `FastClimateStage` /
`FastErosionStage`, which read their neighbourhood samples from cached lattices (`fields/HeightGrid`) instead of
evaluating the relief many times per point. With `fastFields = false` the pipeline is an exact port of the prototype
(used by the parity test).

## GenPoint

The mutable result of one evaluation. One instance per thread is reused. Field groups, by stage:

| Fields | Written by |
|---|---|
| `Tb, Pb, lat, pole, windX, windY` | Base Climate |
| `pi, pj, fx, fy, wx, wy, edge, edgeNC, ni, nj, conv, ridge, orog, trench, arc, collZ, collK, collS` | Tectonic Plates |
| `L` | Continents |
| `h0, h, mtnMask, collR, volc, volcType` | Relief, Volcanism |
| `rock, rock2, rockT, erod` | Surface Geology |
| `wfx, wfy, thermal, deflect, upLand, lE, lW, sst, upwell, curX, curY, shadow, oro, T, P` | Final Climate |
| `slope, gx, gy, eroDelta`, `h` | Erosion |
| `river, riverD, riverHW, riverZ, riverWL, riverAcc, valley, delta, estu, lake, lakeZ, glac, gorge`, `h` | River Stage Evaluation |
| `seaIce, berg, perma, iceFrac, hs` | Cryosphere |
| `biomeW[], biome, color` | Biome Stage |

`h0` is the base height and is never modified after relief; `h` is the "current" height that erosion and rivers carve.

## GenScratch

All mutable temporaries of a thread (neighbourhood arrays of the plate stage, B-spline weights of the river field,
helper `GenPoint`s for nested evaluations...). Obtained from `WorldGen.scratch()` (thread-local). Code that evaluates
the pipeline **from inside another evaluation** (the river network builder does this a lot) must use its own
`GenPoint` and `GenScratch`, otherwise it would overwrite the caller's temporaries.

`GenScratch.riversOff` lets the map skip rivers entirely at very far zoom levels.

## Determinism and thread safety

- Stages hold no mutable state except deterministic caches: Volcanism hotspots, `HeightGrid` tiles,
  Drainage Basins cells and the per-basin river networks.
- Caches are bounded and evict arbitrary entries; a dropped entry is recomputed identically.
- `WorldGen.clearCaches()` drops them (the world is identical afterwards).

## Parameters (GenParams)

Defaults are tuned for the game (several were scaled up from the prototype, noted in comments). The most important:

| Parameter | Default | Meaning |
|---|---|---|
| `seed` | from world seed (`foldSeed` folds the 64-bit seed to 32 bits) | |
| `tempPeriod` / `precipPeriod` | 48000 / 36000 | climate cycles along z / x (Base Climate) |
| `plateSize` | 12000 | plate cell size; one continent per plate (Tectonic Plates) |
| `continentSize`, `microChance`, `islands` | 0.55, 0.35, 1 | Continents |
| `mountainStrength`, `ridgeStrength`, `collisionHeight` | 1 | Relief |
| `hotspotChance` | 0.55 | Volcanism |
| `rockPatch` | 420 | Surface Geology |
| `lapse`, `monsoon`, `currents` | 0.07, 1, 1 | Final Climate |
| `erosionStrength`, `erosionScale` | 1, 96 | Erosion |
| river parameters | | River Parameters and Debugging |
| `biomeSharpness` | 3 | Biome Stage |

## Seeds

`Seeds` derives one 30-bit seed per feature (`warp`, `plate`, `vel`, `cont`, ..., `basin`, `basinW`) by hashing the
world seed with the index of its name. **The order of `Seeds.NAMES` matters**: it matches the prototype. New seeds must
be appended at the end, never inserted, or every world changes.

## Tests

- `ParityTest`: the noise primitives and the pipeline (with `fastFields = false`) match the JS prototype.
- `TerrainBenchmarkTest`, `bench/TerrainBenchmark`: per-stage cost.
- `MapPreviewTest`: writes map images. `DebugPointTest`: prints pipeline values at given points (diagnostics).
- River-specific tests are listed in River Parameters and Debugging.
