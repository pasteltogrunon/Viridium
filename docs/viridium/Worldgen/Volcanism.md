# Volcanism (stage 7b)

Part of [[Worldgen Stages]] (an extension of [[Relief]]) · depends on: [[Tectonic Plates]], [[Continents]],
[[Base Climate]] (warm water) · used by: Surface Geology, 3D Crust (volcanic island province, atoll cap).

Code: `stages/VolcanismStage.java`.

## Hotspots

Hotspots live on a coarse jittered grid of `1.7·plateSize` (≈ 20 400 blocks). A cell has a hotspot with probability `hotspotChance` (0.55). Each hotspot is **resolved once**: the stage evaluates the plates at its position and decides its type, then memoises it in a bounded deterministic cache (4096 entries). A point checks the 3×3 surrounding cells.

| Type | Condition at the hotspot | Result |
|---|---|---|
| **Iceland** (`TYPE_ICELAND`) | on a divergent boundary (`conv < −0.08`, `edge < 0.25·S`) in deep sea | snapped onto the ridge axis (4 iterations); a large basaltic shield with a rift valley |
| **Chain** (`TYPE_CHAIN`) | in the open ocean (`L < −0.1`) | a line of volcanoes dragged by the plate (Hawaii) |
| none | on land or near the coast | no hotspot |

## Volcano chains

- Direction = the plate's velocity. 5–12 volcanoes spaced 230–390 blocks, each jittered ±80 blocks.
- The youngest (k = 0) is over the hotspot and the tallest: `top = −62 + H0·e^(−0.32k)`, `H0 = 150–230`. Older ones
  sink progressively below sea level.
- Shape: `t^1.7` cone of radius `80 + 1.3·(top + 62)`, irregular outline (fBm on the radius: lava flows, collapsed
  flanks), radial gullies (ridged noise), and a caldera on the youngest.
- **Atolls**: in warm water (`cos(2πz/tempPeriod) > 0.45`), a volcano whose top is between −26 and +3 becomes a coral ring reaching the surface around a ~−5 lagoon (`volcType = 4`).
- Sets `volc` (cone profile) and `volcType = 2` (or 4).

## Iceland

A shield of radius 10–17 % of the plate size centred on the ridge, height `14 + 50·(1 − r) + 75·ridged²`, with a 24-block rift valley carved along the plate boundary (`edge`). `volcType = 3`.

`volc` and `volcType` select volcanic rocks in Surface Geology and the `VOLCANIC_ISLAND` province in the 3D Crust.

## Volcano rework (Java-only)

Parameters (`GenParams`, `0` turns one off, all off in `ParityTest`): `stratovolcanoes`, `volcanicFields`, `continentalHotspots`, `geothermal`, `causeways`. Debug: `TerrainViewTest` (`VIRIDIUM_FIND_VOLC` lists stratovolcanoes, fields and hotspots).

`volcType`: 1 arc / stratovolcano, 2 hotspot chain, 3 Iceland, 4 atoll, **5 continental hotspot**, **6 volcanic field**. Types 5 and 6 take the large igneous province (`LIP`) in the 3D crust, not the volcanic island one; type 5 gives tuff (ignimbrite) and basalt, type 6 basalt.

### Stratovolcanoes (`stratovolcanoes`)

The arcs used to be a ridged noise: hills, not volcanoes. Now the arc keeps its islands (`fbm /380`) and its volcanoes are separate cones: one candidate per 2.6 km cell (70 %), kept where the arc is strong at the cell's centre (`arc > 0.12`, one plate evaluation per cell, memoised). Height 110-260 blocks (× arc strength), base radius 3.2-4.8 × the height, concave profile `(1 − r/R)²` (steep summit, gentle base), a summit crater; a quarter collapsed into a **caldera** (radius 17-25 % of R, flat floor at 55 % of the height inside steep walls), which the river networks fill as a crater lake where it is wet enough (Crater Lake, Santorini). The erosion leaves young volcanoes their cone (`ErosionStage.youngVolcano`: gully depth −75 % on volcType 1 and 6, −40 % on hotspot chains).

### Volcanic fields (`volcanicFields`)

One possible field per 7 km cell, on land: chance 0.1 anywhere, +0.55 in a rift, +0.3 behind convergent margins; radius 1.5-3.2 km; one candidate vent per 260-block cell, denser in the middle (Garrotxa, Auvergne, San Francisco field, Lanzarote).

| Vent | Share | Shape |
|---|---|---|
| maar | 20 % | crater 40-100 blocks across, 9-21 below the ground (lake in wet climates), a 4-block ring of tephra (`SURF_ASH`) |
| tuya | 35 % where the field lay under the ice sheets (`Tb − glacialCooling < −5`) | flat-topped, steep-sided table volcano, 2.5 × the cone radius |
| cinder cone | the rest | 30-70 blocks across, a third as high, summit crater; scoria (`SURF_CINDER`), grassed in wet climates |
| lava flow | half of the cinder cones | runs downhill (the base relief's gradient at the vent, sampled once per vent with the fields switched off) 200-750 blocks, swelling into lobes and widening towards a rounded front, 2.5-5.5 blocks thick with a blocky aa top (`SURF_LAVA`: basalt and scoria); 60 % of their cones are breached towards it |

### Continental hotspots (`continentalHotspots`)

A hotspot under a continent (`L > 0.12`, before: none) is a Yellowstone: a broad swell (+35 over ~2.6 km), a caldera 1.8-3 km across with a 22-block rim, a flat floor and two resurgent domes, a geothermal field around it, and the track along the plate's motion: a flat basalt lowland 3 km wide and up to 0.6 plate sizes long (the Snake River Plain).

### Geothermal fields (`geothermal`)

`GenPoint.geo` (0..1) is set by calderas, the summits of stratovolcanoes, cinder cones, the Iceland-like ridge and continental hotspots. After the rivers (`VolcanismStage.geothermal`, on the final ground), one candidate per 40-block cell (probability `0.16·geo`), on gentle ground (`slope < 0.3`) away from rivers and lakes:

- **hot spring terraces** (60 %): pools of water stepping down the slope (Mammoth, Pamukkale, Grand Prismatic). The water level of a pool column is its own one-block level, and the columns between two levels are sinter rims (`SURF_SINTER`, geyserite): water never stands above a lower neighbour. Ringed by bacterial mats (`SURF_MAT`, `SURF_MAT2`: orange and yellow stained clay);
- **geyser cones** (25 %): low sinter mounds with a vent;
- **fumarole ground** (15 %): bare tuff.

### Basalt causeways (`causeways`)

On basalt shores (rock basalt, near the coast, a regional mask): hexagonal columns 2-3 blocks across whose tops step by a block (the Giant's Causeway, Staffa), bare basalt.
