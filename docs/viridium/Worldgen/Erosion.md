# Erosion (stage 9)

Part of [[Worldgen Stages]] · depends on: [[Relief]], [[Surface Geology]], [[Final Climate]] · used by: every river page (rivers are fitted to the eroded terrain: see River Water Levels and Lakes and Salt Flats),
Biome Stage and Cube Filling and Surface Rules (slope).

Code: `stages/ErosionStage.java`, `fields/FastErosionStage.java`.

Erosion here is **stateless**: no simulation, just a filter that adds gully patterns aligned with the slope. It is the "eroded terrain noise" technique (Clay John): a sum of waves whose fronts follow the terrain gradient, branching between octaves.

## Algorithm

1. Gradient `(gx, gy)` of the smoothed base relief, by central differences at ±12 blocks. `slope = |gradient|`. `FastErosionStage` reads these 4 heights from a cached 8-block `HeightGrid` (except on far map zooms).
2. Wave frequency ∝ slope (`min(2.5·slope, 1.1)`), so **flat ground gets no gullies**. Waves run perpendicular to the gradient.
3. 4 octaves on a jittered grid of scale `erosionScale` (96 blocks), each octave half the size and 0.45 the amplitude. Between octaves the direction is bent by the gradient of the previous octaves (`BRANCH`), which makes gullies branch
   like a drainage network.
4. The result is shifted to `[−sum, 0]`: erosion **only digs**.
5. Depth:

```
clim   = (0.3 + clamp(P/1500, 0, 1.5))·(1 + 0.6·exp(−(T/7)²))      rain + freeze/thaw (strongest near 0 °C)
amount = erosionStrength·32·erod·clim·ss(−8, 6, h0)                  fades out under the sea
h      = h0 + raw·amount
```

`eroDelta` keeps the change. Octaves whose wavelength is below ~1.2 pixels are faded out on the map (anti-aliasing via `bpp`).

## Why it matters for rivers

The river networks are computed on the smooth base relief (fast, coarse), but their **water levels** and **lake levels** are measured on the terrain after this stage (`Stage.EROSION`), because that is what the player sees. See River Water Levels.

## Escarpments and mesas

- The amount is multiplied by `1 − scarp`: next to a plateau scarp, a sea cliff or a rift fault the lattice gradient is smeared over ~20 blocks and would dig a moat along the foot (or leave a lip along the top). See [[Landforms Overview|Plateaus and Mesas]].
- `mesas`: in arid land on sedimentary rock, slopes are terraced in 12-block steps with steep risers (mesas, buttes, ledges). `GenPoint.mesa` stores its weight.
