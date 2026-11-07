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
| shape | ellipsoid (rz = 0.65–1.35 rx) turned any way, its horizontal outline wobbled (harmonics 2–3, ±18 %), flat floor at y − 0.55·ry, walls roughened by the cavern noise |
| lake | 45 % (water, floor + 3..15); deep chambers below −1200: 35 % lava |
| galleries | from each chamber to one chamber of the east and of the south region (if they are within 700 blocks in height): polyline every ~48 blocks, jittered ±30 horizontally, ±8 vertically; radius 4 (shallow) to 10 (deep), × 0.8–1.3 per segment, slightly flatter than wide |
| collapse pit | 80 % of shallow chambers, if the centre is on land, not in a river or lake, rivers are further than 0.6·r + 40, and the ground around the outer edge of its walls (32 points, with the rivers) is land with no river, lake or lake shore above the pit floor; otherwise the pit is tried at 0.75 and 0.55 of its size before none. Shape: below |
| great entrance | every system whose shallowest chamber has no pit: a wide ramp (mouth radius 9–13, narrowing to 7) from the surface down to that chamber, 1.4 × as long as it is deep down to ~600 blocks, steeper below (120–950 blocks); see [[Cave Entrances]] |

Planning reads the terrain up to erosion and applies the river stage directly at the chamber centre: never the cave 2D stage, which needs these plans.

## Collapse pits

Pits are not round (user feedback 2026-11-07: "always a hemisphere"). Per chamber, deterministic:

| Part | Rule |
|---|---|
| outline | an ellipse as large as a round pit of radius 0.55·min(rx, rz), stretched 1–1.8 × along the longer axis of the chamber, with lobes (harmonics 2–5 of the angle, up to ±32 %) |
| walls | 5–9 blocks wide, varying 0.4–1.6 × around the pit; 1–3 ledges (flat shelves with steep risers), a little ragged (noise on the profile) |
| floor | the chamber floor + 1, rough (0–4), with a mound of rubble from the fallen roof (2–9 blocks high in the middle) |
| thin roofs | where the wall would leave less than 5 blocks of rock over the hollow chamber, it drops straight to the floor: an overhanging cenote rim, never a hanging lip |
| cenote | a chamber lake is lowered under the lowest ground around the rim (− 2); wherever the pit is below it, the pit is a lake at that level |
| lakes and shores | a river lake, or the shore of a lake higher than the pit floor, is left as it is: the pit stops there |

`CaveSystems.surface` (after the dolines) lowers the 2D terrain; the carved chamber uses the same outline (`Chamber.pitQ`) for the open shaft above its centre and the same floor (`Chamber.pitFloor`) under it, so the column plans and the carved blocks agree.

## Shapes and fluids

Chambers own their fluid (lake below its level, dry above), so they ignore the aquifer; where a gallery and a chamber overlap, the chamber decides (an earlier version let the gallery hand the block to the aquifer, which put barrier rock in the middle of chambers). Galleries use the aquifer and may end in sealed sumps.

## Results (seed 12345, 121 regions)

10 shallow, 56 mid and 33 deep chambers; 54 with a lake; 5 collapse pits (−4943,−4719; −4965,−2589 cenote; 2614,3915 cenote; 2369,5523; 5297,5521; before the stricter siting 7: two sat on river lakes or their shores and were dropped); aspect ratio 1.3–1.7; no cenote water next to lower dry ground (`CaveShapesTest.collapsePits`); 19–20 of 20 chamber centres carved.

## Cost

One region plan: a few pipeline evaluations (and the river stage at shallow chambers). Per cube: the shapes of 3×3 regions whose bounding box touches it. Per 2D point: ~0.5 µs (per-point total 11.1 → 11.6 µs).
