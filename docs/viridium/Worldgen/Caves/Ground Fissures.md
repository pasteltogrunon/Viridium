# Ground Fissures

Part of [[Caves Overview]] · cut into the 2D terrain after [[Rivers Overview|the rivers]], with [[Karst|the dolines]] and the collapse pits of [[Cave Systems]].

Code: `caves/Fissures.java`. Parameter `fissures` (1; 0 off).

## In reality

Long open cracks in the ground: tension fissures where the ground pulls apart (rift margins, the edges of plateaus and landslides, Iceland's Þingvellir), karst grikes widened by solution, desiccation and subsidence cracks. Narrow, deep, with vertical walls; some reach caves or the water table.

## Rule

One candidate per 768×768-block cell (20 % per unit of `fissures`), planned on demand and cached:

| Part | Rule |
|---|---|
| path | from a point jittered in the cell, a polyline with a node every 16 blocks, 60–250 blocks long, turning up to ±0.25 rad per node (gentle bends) |
| where | the terrain up to erosion at each node (no rivers: no river network is built for it): it stops at the first node in the sea or below 8 blocks above sea level, or where the ground changes by more than 0.55 blocks per block between nodes (steep slopes); fewer than 4 nodes: none |
| width | 2–6 blocks, tapering to a quarter at both ends, wavering ± 15 % along it |
| depth | 12–40 blocks below the ground, 35 % of it at the ends, ± 10 % noise; vertical walls (one block per column) |
| water | per point, from the point's own river distance: it closes up within 6–30 blocks of a river's banks, on low ground (h 4–12), and is never cut in a river, a lake or next to a lake as high as its floor |
| slot | 40 %: below the middle 70 % of the crack a narrower slot (half the width) goes 20–60 blocks deeper (a cave shape: noise caves and aquifers meet it; water stands in it at the aquifer level) |

The crack lowers `o.h` and is recorded in `o.doline` (dark green in the Landforms map layer, like the dolines).

## Results (seed 12345)

48 fissures on ~186 km² of land (1 per 3.9 km²), 48–248 blocks long (mean 141), 21 with a slot. The first 8 cut 3149 columns, the deepest 33 blocks, none under water (`CaveShapesTest.fissures`). On the map at 1–2 blocks per pixel they show as thin dark bending lines, a few pixels wide.

## Cost

Per 2D point: a per-thread memo of the fissures whose bounds meet the current cell, then a bounding-box test: ~0.02 µs per point. A cell plan: up to 17 evaluations up to erosion, once.
