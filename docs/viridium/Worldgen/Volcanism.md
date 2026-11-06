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
