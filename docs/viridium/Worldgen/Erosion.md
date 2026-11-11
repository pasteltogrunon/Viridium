# Erosion (stage 9)

Part of [[Worldgen Stages]] · depends on: [[Relief]], [[Surface Geology]], [[Final Climate]] · used by: every river page (rivers are fitted to the eroded terrain: see River Water Levels and Lakes and Salt Flats),
Biome Stage and Cube Filling and Surface Rules (slope).

Code: `stages/ErosionStage.java`, `fields/FastErosionStage.java`, `fields/HeightGrid.gradient`.

Erosion here is **stateless**: no simulation, just a filter that adds gully patterns aligned with the slope. It is the "eroded terrain noise" technique (Clay John): a sum of waves whose fronts follow the terrain gradient, branching between octaves.

There are two versions. The **climate erosion** (`erosionClimate = 1`, the default, Java-only) scales the depth with the relief and shapes crests and valley floors by climate and rock. The **prototype filter** (`erosionClimate = 0`) is the port of the JS, kept for `ParityTest`.

## Waves (both versions)

1. Gradient `(gx, gy)` of the smoothed base relief. `slope = |gradient|`. `FastErosionStage` reads it from a cached 8-block `HeightGrid`: with the climate erosion as the analytic gradient of a cubic B-spline over the 4×4 surrounding nodes (smooth: the bilinear gradient jumps at every node line, and a depth ∝ slope turned that into a checker pattern); with the prototype filter as central differences of 4 bilinear samples at ±12 blocks.
2. Wave frequency ∝ slope (`min(2.5·slope, 1.1)`), so **flat ground gets no gullies**. Waves run perpendicular to the gradient.
3. 4 octaves on a jittered grid, each half the size of the previous one. Between octaves the direction is bent by the gradient of the previous octaves (`BRANCH`), which makes gullies branch like a drainage network.
4. Erosion **only digs**.

Octaves whose wavelength is below ~1.2 pixels are faded out on the map (anti-aliasing via `bpp`). `eroDelta` keeps the change.

## Climate erosion

### Depth scales with the relief

```
Lg     = erosionScale·erosionWidth                      96·1.6 ≈ 154 blocks: spacing of the main gullies
amount = erosionStrength·ECD·erosionDepth·Lg·min(slope, 1)·(ss(−8, 6, h0) + sea canyons)·(1 − scarp)
```

The depth is capped at 55 blocks (deeper ravines cut below the rivers beside them), and young volcanoes lose 40-75 % of it (see [[Volcanism]]). A mountainside at slope 0.5 gets gullies up to ~40 blocks deep (spurs and ravines like in the reference screenshots of RWG), hills a few blocks, plains none. The prototype used a fixed depth (32·erodibility), which left high mountains looking like smooth ridged noise. Land is never dug below sea level: the sea is the base level.

### Crests and floors by climate and rock (`climateStyle`)

The normalised wave sum gives `e`: 0 on the crests, 1 at the gully floors. It is shaped by four values computed per point:

| Value | Meaning | How |
|---|---|---|
| `ECD` | depth multiplier | humid ×1.3, tropical humid ×1.45, cold +0.15, dry hard rock −0.35, dry soft rock (badlands) +0.3; × 0.62..1.2 from hard to soft rock |
| `ECP` | crest exponent `e^ECP` (< 1 sharp, > 1 rounded) | 1.35 humid temperate (soil-mantled, rounded) → 0.55..0.8 cold (frost shattering), dry (angular) or tropical (knife-edge) |
| `ECG` | octave gain (`erosionGain` 0.6 + more for badlands and frost) | drainage density: more fine gullies on soft dry rock |
| `ECF` | valley fill (smooth minimum `e ≤ 1 − ECF`) | dry climates fill their gully floors with sand (washes, pediments) |

| Climate | Look |
|---|---|
| humid temperate | soil-mantled slopes, rounded crests, valleys of moderate depth |
| humid tropical | the deepest and steepest dissection, knife-edge ridges, V valleys (Na Pali, Rio de Janeiro) |
| arid, hard rock | little erosion, angular crests, flat-floored washes |
| arid, soft rock | badlands: a dense network of rills with sharp crests |
| cold | frost-shattered jagged crests and couloirs |

Parameters (`GenParams`): `erosionClimate` (0 = prototype), `erosionWidth` (1.6), `erosionBranch` (1.8), `erosionGain` (0.6), `erosionDepth` (0.75).

### Gullies end at the valley floor

The gully waves know nothing of the rivers. Deep gullies at the foot of a steep valley side used to leave pits below the river's water (6-block walls of water at lakes and river banks). The broad valley ([[Rivers Overview|Valleys, Gorges and Glacial Troughs]]) now fills what the erosion dug below the valley floor, so gullies end at the floodplain like real ones end at their alluvial fill. Lake basins of the relief are left alone (only `eroDelta` is filled).

### Submarine canyons

The same filter runs on the continental slope, between the shelf edge (−12) and the abyssal plain (−72), weight 0.9 (`seaCanyons`): the slope is cut into steep canyons and gullies (Monterey, Congo).

### Bare rock

The slope the surface rules use is now the gradient of the final terrain of the column (`ColumnPlanner.terrainSlope`), not the smoothed relief's: gully walls, sharp crests and canyon sides show bare rock, spurs and gentle floors keep their soil (see [[Blocks and Chunks|Cube Filling and Surface Rules]]).

## Prototype filter

```
GAIN   = 0.45, scale = erosionScale (96)
clim   = (0.3 + clamp(P/1500, 0, 1.5))·(1 + 0.6·exp(−(T/7)²))      rain + freeze/thaw (strongest near 0 °C)
amount = erosionStrength·32·erod·clim·ss(−8, 6, h0)                  fades out under the sea
h      = h0 + raw·amount,  raw ∈ [−sum, 0]
```

## Why it matters for rivers

The river networks are computed on the smooth base relief (fast, coarse), but their **water levels** and **lake levels** are measured on the terrain after this stage (`Stage.EROSION`), because that is what the player sees. See River Water Levels.

## Escarpments and mesas

- The amount is multiplied by `1 − scarp`: next to a plateau scarp, a sea cliff, a sea stack or a rift fault the lattice gradient is smeared over ~20 blocks and would dig a moat along the foot (or leave a lip along the top). See [[Landforms Overview|Plateaus and Mesas]].
- `mesas`: in arid land on sedimentary rock, slopes are terraced in 12-block steps with steep risers (mesas, buttes, ledges). `GenPoint.mesa` stores its weight.

## Cost

Per point about the same as the prototype filter (the B-spline gradient reads 16 nodes instead of 4 bilinear samples of 4).
