# Surface Geology (stage 6)

Part of [[Worldgen Stages]] · depends on: [[Tectonic Plates]], [[Continents]], [[Relief]], [[Volcanism]] · used by:
Erosion (erodibility), River Network Build and Valleys, Gorges and Glacial Troughs (valley shape),
Biome Stage (badlands, karst), 3D Crust (province choice and surface bias). Design: Geology.

Code: `stages/GeologyStage.java`, `stages/Rock.java`.

This is the **2D** surface geology: one of 11 rock families per point, mainly to drive erosion and valley shapes. The
real block-level rocks come from the 3D Crust, which uses this result as a bias.

## Rock patches

Rocks come in warped Voronoi patches of `rockPatch` = 420 blocks (3×3 search, warp `0.75·rockPatch`). The nearest patch gives `rock`, the second nearest `rock2`, and `rockT` is the weight of the first (0.5 on the border, 1 inside), for soft transitions.

A patch's rock depends on where it is:

| Setting | Rock |
|---|---|
| volcanic island, arc (`volcType 1`) | andesite 55 %, tuff 30 %, basalt |
| coral atoll (`volcType 4`) | limestone 70 %, basalt |
| hotspot volcano | basalt 82 %, tuff |
| collision core on land (`collR > 0.3`) | gneiss, granite, slate, marble, limestone (regional metamorphism) |
| sea floor | basalt on ridges; otherwise basalt 80 %, shale, limestone |
| near convergent boundaries (`orog > 0.35`), half the patches | andesite / tuff (continental arc) |
| elsewhere | weighted pick by `Rock.weight` |

## Rock families

| Rock | Erodibility | Weight |
|---|---|---|
| Granite | 0.22 | 3 |
| Diorite | 0.28 | 1.5 |
| Andesite | 0.35 | 2 |
| Basalt | 0.40 | 1 |
| Slate | 0.45 | 2 |
| Marble | 0.38 | 0.8 |
| Limestone | 0.60 | 3 |
| Sandstone | 0.75 | 2.5 |
| Shale | 0.90 | 1.5 |
| Tuff | 0.70 | 0.7 |
| Gneiss | 0.20 | 0 (only from collisions) |

The order of the enum matters (the weighted pick depends on it) and matches the prototype.

## Erodibility

`erod` is **not** the erodibility of the dominant rock. It is a smooth blend of every nearby patch, weighted by how much farther than the nearest one it is (`ss(0.3·R, 0, d − d1)`), and inside each patch the cases above are blended with smoothsteps too (`erodFor`). Erosion and valleys would otherwise show steps where the rock changes.

Consumers:

- Erosion: gully depth ∝ `erod`.
- River Network Build stores `erod` per node (`cer`), and the
  valley shape uses it: hard rock → narrow gorges with steep banks, soft rock → wide open valleys.
