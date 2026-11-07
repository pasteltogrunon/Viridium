# Cave Systems

Part of [[Caves Overview]] · fluids in [[Aquifers and Lava]] · collapse pits change the 2D terrain after [[Rivers Overview|the rivers]].

Code: `caves/CaveSystems.java`.

## In reality

The largest caves are single chambers carved where passages meet and the roof breaks down: Sarawak Chamber (Borneo) is ~600 × 400 m and 80 m high; Son Doong (Vietnam) has halls 200 m high. Where a large chamber lies close to the surface its roof may collapse into an open pit (a **cenote** when it reaches the water: Yucatán; a **tiankeng** in China). Galleries link chambers along the old water-table levels.

## Plan per region

Regions of 1024×1024 blocks, planned on demand and cached (deterministic, any order):

| Piece | Rule |
|---|---|
| chambers | 0 (30 %), 1 (50 %) or 2 (20 %) per region; centre jittered in the region |
| shallow chamber (on land, 45 %) | half-width 30–70, half-height 20–35, roof 20–60 blocks under the ground |
| mid chamber (40 %) | half-width 50–110, centre y −150..−700 |
| deep chamber (rest) | half-width 80–180 (up to ~360 blocks across), centre y −900..−1850 |
| shape | ellipsoid (rz = 0.65–1.35 rx), flat floor at y − 0.55·ry, walls roughened by the cavern noise |
| lake | 45 % (water, floor + 3..15); deep chambers below −1200: 35 % lava |
| galleries | from each chamber to one chamber of the east and of the south region (if they are within 700 blocks in height): polyline every ~48 blocks, jittered ±30 horizontally, ±8 vertically; radius 4 (shallow) to 10 (deep), × 0.8–1.3 per segment, slightly flatter than wide |
| collapse pit | 55 % of shallow chambers, if the centre is on land, not in a river or lake, and rivers are further than 0.6·r + 40: a pit of radius 0.55·r |
| entrance | 60 % of the other shallow chambers: a gallery (radius 4.5) climbing to the surface 100–220 blocks away; its last third may open the ground |

Planning reads the terrain up to erosion and applies the river stage directly at the chamber centre: never the cave 2D stage, which needs these plans.

## Collapse pits (2D)

`CaveSystems.surface` (after the dolines): within the pit radius the ground drops to the chamber floor (+3, roughened); a steep wall over the next 10 blocks. If the chamber holds a water lake above the pit floor, the pit becomes a lake at that level (a cenote). In 3D the chamber shape adds the pit cylinder above its centre, so the open shaft and the chamber under the rim match the 2D terrain.

## Shapes and fluids

Chambers own their fluid (lake below its level, dry above), so they ignore the aquifer; where a gallery and a chamber overlap, the chamber decides (an earlier version let the gallery hand the block to the aquifer, which put barrier rock in the middle of chambers). Galleries use the aquifer and may end in sealed sumps.

## Results (seed 12345, 121 regions)

10 shallow, 56 mid and 33 deep chambers; 54 with a lake; 7 collapse pits; the widest 355 blocks; 19–20 of 20 chamber centres carved. The pit at x −4965, z −2589 opens from ~Y 69 down to a lake at ~Y 27 in a chamber that undercuts its rim.

## Cost

One region plan: a few pipeline evaluations (and the river stage at shallow chambers). Per cube: the shapes of 3×3 regions whose bounding box touches it. Per 2D point: ~0.5 µs (per-point total 11.1 → 11.6 µs).
