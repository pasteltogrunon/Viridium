# Valleys, Gorges and Glacial Troughs

Part of [[Rivers Overview]] · step 12 of the [[River Network Build]] (valley field) and phase 0 of
[[River Stage Evaluation]] (shaping) · depends on: [[River Water Levels]], [[River Geometry and Meanders]] (meander
amplitude), [[Worldgen Stages|Surface Geology]] (erodibility), [[Worldgen Stages|Base Climate]] and [[Worldgen Stages|Final Climate]] (glaciation), [[Worldgen Stages|Tectonic Plates]]
(`edge`) · used by: River Mouths, Deltas and Estuaries (fjords), [[Worldgen Stages|Biome Stage]] (via the carved height).

Code: `RiverNetBuilder.build` (valley field section), `RiverStage.netField`, `broadValley`, `glacial`,
`glacialDepth`, and the channel part of `applyNet`.

A river does not only cut a channel: over geological time it shapes a whole valley around it. This is done by a
**broad valley** that compresses the relief towards the nearest river, whose width and profile depend on the river's
size, the rock, and whether a glacier occupied it.

## The valley field (build time)

After the deltas are built, every grid node (including the margin over the neighbouring basins) gets the **chamfer
distance** to the nearest river node, using a two-pass 8-neighbour sweep (orthogonal step `g`, diagonal `g·√2`) that
also propagates which river node is the nearest (`src`). Reaches replaced by a delta are not sources.

For each node closer than 900 blocks the network stores, from its nearest river node:

| Field | Stored as | Meaning |
|---|---|---|
| `vd` | char | distance to the river (65535 = none) |
| `vz` | short, ×16 | the river's water level |
| `vw` | byte, ×4 | its half-width |
| `va` | byte, ×4 | its meander amplitude (the meander belt the floodplain must hold) |
| `vr` | byte, /2 | **local relief**: highest node in the 7×7 around the river node minus its water level. Tells whether the valley is boxed in by mountains |
| `cer` | byte, ×255 | erodibility of the node (stored for every owned node) |

## Sampling the field (runtime)

`netField(x, z)` reads the 4×4 surrounding nodes with **cubic B-spline** weights: C² continuous, so no grid pattern is
visible in the valley walls. Nodes without a river count as distance 900. The outputs are `FD` (distance), `FZ`
(level), `FW` (half-width), `FA` (meander amplitude), `FR` (local relief) and `FE` (erodibility, with its own weight
`FEW`).

## Rock: gorges or open valleys

```
ef    = clamp(lerp(0.5, (erod − 0.2)/0.7, valleyRock), 0, 1)       erod blended between the point and the field
gorge = 1 − ef
```

`ef` near 0 means **hard rock**: narrow valleys with steep sides, narrow channels with steep banks (gorges, canyons). `ef`
near 1 means **soft rock**: wide, open valleys. `valleyRock = 0` ignores the rock (`ef = 0.5` everywhere).

## Glaciation

```
glacial(o, z) = ss(−1, −7, Tb − glacialCooling − lapse·z) · ss(5.5, 6.2, ln P)
```

"Was this valley under a glacier at the last glacial maximum?": the base temperature is lowered by `glacialCooling` (9
°C) and by altitude, and there must be enough precipitation (≈ 250–490 mm/year and up) to feed ice. In the valley code
it is further multiplied by `ss(8, 45, FR)`: **troughs only form where the ice was boxed in by mountains** (local relief
over ~45 blocks); a glacier on a flat plain leaves no trough.

The glacier lowers the floor by `glacialDepth(z, hw) = 20 + 1.2·hw + 26·ss(0, 140, z)`: deeper for larger rivers and
higher up.

## Broad valley (phase 0)

With `D` = distance to the river, `hw`, `A` (meander amplitude), `zr` (river level) from the field and
`G = glacial · ss(8, 45, FR)`:

```
str   = 0.4 + 0.6·ss(3.4, 7, hw)                              headwaters: faint valley
Wv    = (70 + 12·hw)·lerp(0.7, 2.2, ef)·valleyWidth            V / river valley width
Wu    = (80 + 8·hw)·valleyWidth                               U / glacial trough width
W     = lerp(Wv, Wu, G)·lerp(0.6, 1, str)
D0    = lerp(6 + hw + 1.15·A, 12 + 1.5·hw, G)                 flat floor: floodplain wide enough for the meanders
floor = lerp(zr + 0.6, zr − glacialDepth, G)
t     = clamp((D − D0)/W, 0, 1)
gam   = lerp(lerp(0.6, 1.5, ef), 2.2, G)                      < 1: gorge, > 1: open / U valley
f     = (t·(2 − t))^gam
fade  = ss(8, 70, edge) · (1 − ss(700, 899, D))
h     = lerp(h, floor + (h − floor)·f, str·fade)              only where h > floor
```

So the terrain is pulled down towards the valley floor, fully within `D0` (the floodplain) and less and less up to
`D0 + W`. The exponent `gam` shapes the walls: below 1 the valley stays narrow and the walls steep (gorge), above 1
the walls flare out (open valley), and 2.2 gives the flat-bottomed, steep-sided **U profile** of a glacial trough.

It also writes `valley` (1 − t, for the map) and `glac` (glaciation share). The field fades out over the last 200
blocks before 900, near plate boundaries (`edge`), and only applies on land (`L ≥ −0.04`).

## Channels in valleys

The channel itself is carved in phase 1 ([[River Stage Evaluation#Channels]]) and also depends on the rock and the
glacier:

- Channel and bank width multiplier `lerp(0.45, 2.0, ef)`; bank slope `lerp(1.5, 0.25, ef)` (steep in hard rock).
- In a glaciated valley the bed is lowered by the glacial depth too (`zu = z − G·glacialDepth`). Next to the sea this
  goes **below sea level → fjord** (River Mouths, Deltas and Estuaries).

## Order matters

All broad valleys of all nearby basin networks are applied **before** any channel (see
[[River Stage Evaluation#Phases]]), so every channel measures its water against the final valley, not a terrain a
neighbouring basin will still lower.
