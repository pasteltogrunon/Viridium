# Cryosphere (stage 11)

Part of [[Worldgen Stages]] · depends on: [[Final Climate]] (`T`), [[Continents]], [[Rivers Overview|River Stage Evaluation]] (river
and lake flags, final height) · used by: Biome Stage, Column Plans (ice bodies).

Code: `stages/CryoStage.java`.

Sets `seaIce` (0 none, 1 floe, 2 ice shelf, 3 frozen lake), `berg` (iceberg top), `perma` (permafrost), `iceFrac`, and `hs`, the **visible surface** height (lake level, ice, iceberg top), used by the map.

| Feature       | Where                                                                      | How                                                                                                                                                  |
| ------------- | -------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------- |
| Frozen lake   | a lake with `T < −2`                                                       | `seaIce = 3`                                                                                                                                         |
| Permafrost    | land outside rivers                                                        | `perma = ss(−1, −9, T)` (discontinuous below ≈ −2 °C, continuous below ≈ −8 °C)                                                                      |
| Ice shelf     | sea next to very cold coasts (`t < −18..−24`, `L` between −0.32 and −0.12) | `seaIce = 2`, surface +3                                                                                                                             |
| Sea ice floes | sea with `T ≤ 1`                                                           | one candidate floe per 36-block cell; density and size grow towards the pole (`d = ss(0, −18, t)`); rotated, elongated, noisy ellipses; `seaIce = 1` |
| Icebergs      | sea deeper than 4 blocks                                                   | one candidate per 150-block cell, more frequent towards the pole; tabular (superellipse, exponent 3), 4–20 blocks above water                        |

Rivers are never frozen over by this stage (sea ice skips river points); freezing of rivers and lakes as ice blocks is decided by Column Plans (`T < −6`).

On far map zooms (`bpp > 6`) floes are replaced by a mean ice fraction.
