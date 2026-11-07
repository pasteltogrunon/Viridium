# Cave Entrances

Part of [[Caves Overview]] · ramps of [[Cave Systems]] · noise caves in [[Noise Caves]].

Code: `caves/CaveMouths.java`, `CaveSystems.greatEntrance`, `CaveSystems.ramp`, `CaveCarver.entrance`, `CaveCarver.nearOpening`.

## Why a page of its own

The first version only opened caves where a noise cave happened to reach the ground in an entrance zone, and kept fewer caverns near the surface. Entrances were rare and small, and the ones a player found were mostly shafts and fissures: vertical. Now there are three kinds:

| Kind | Where | Size | Frequency (seed 12345) |
|---|---|---|---|
| **hillside mouth** (`CaveMouths`) | ~60 % of the 256-block cells of land, not in or next to rivers, lakes, the sea | mouth radius 5–8 (an arch ~10–16 wide), ramp of 60–150 blocks down at 15–30° | 88 in 1600 cells of a mostly oceanic area |
| **great entrance** (`CaveSystems.greatEntrance`) | every planned system whose shallowest chamber has no collapse pit | mouth radius 9–13 (an arch ~18–26 wide), narrowing to 7; 120–950 blocks long, down to the chamber | all systems with land within reach (16 in 121 regions, most of them oceanic) |
| **noise openings** | entrance zones (`fbm(/220) > 0.3`, ~25 % of the land): no roof and no near-surface cavern penalty | whatever room or tunnel reaches the ground | |

Plus the collapse pits of [[Cave Systems]], the shafts under dolines and the sea caves of [[Karst]].

## Ramps

`CaveSystems.ramp` builds a winding polyline from the mouth to the target, one node every ~20 blocks, jittered sideways (up to ±9 blocks, most in the middle), radius going from the mouth radius to the inner radius over the first two thirds. Segments are capsules slightly flatter than wide (an arch rather than a tube) and may cut through the roof all along: the mouth, and a skylight where the ramp passes just under a slope.

Hillside mouths head **uphill** (the gradient of the coarse relief, ±23°): entering the slope makes the opening an arch in the hillside instead of a hole in flat ground. They start 0.3·r above the ground and end r below their slope line, where they usually meet the noise caves.

Great entrances are 1.4× as long as they are deep down to ~600 blocks (gentle), then 0.95× (a great descending cave); six directions are tried, then the same at 0.6 of the length, until the start is on land away from rivers.

## Trees

No trees are planted in entrance zones or within 3 blocks of the first segments of a mouth or a great entrance: the ground under them may be cut away (`FloraPlanner` asks `CaveCarver.entrance` and `nearOpening`).

## Tests

`CaveTest.entrancesAreOpen`: counts mouths and great entrances and checks that the ground is open within 10 blocks under the start of each (25/25 mouths and 16/16 great entrances open).
