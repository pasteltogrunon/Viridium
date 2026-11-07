# Noise Caves

Part of [[Caves Overview]] · fluids in [[Aquifers and Lava]].

Code: `CaveCarver` (`Cube.prepare`, `Cube.at`, `noise`), `CaveNoise` (3D gradient noise).

## Fields

Five 3D noise fields, evaluated on a lattice of **8 blocks** (3×3×3 nodes per cube) and interpolated trilinearly per block:

| Field | Noise | Makes |
|---|---|---|
| `C1` | fbm3, 2 octaves, x/z ÷ 90, y ÷ 55 | caverns: `C1 > threshold` (flattened: wider than tall) |
| `C2` | fbm3, 1 octave, x/z ÷ 260, y ÷ 150 (only below y −150) | huge deep caverns: `C2 > 0.62 − 0.2·ss(−200, −1400, y)` |
| `A`, `B` | fbm3, 2 octaves, x/z ÷ 75, y ÷ 110 | tunnels: `|A| < w and |B| < w` (two isosurfaces cross: long winding tubes) |
| `F` | fbm3, 1 octave, x/z ÷ 38, y ÷ 260 | fissures: `|F| < w` inside cavern-prone rock (`C1 > 0.1`): tall vertical slots |
| `A` + 2D level | | lava tubes: `(A/0.075)² + ((y − tubeY)/4)² < 1`, `tubeY` 9–25 blocks under the surface |

The cavern threshold rises in the first 70 blocks under the ground (thin roofs would collapse) and falls a little below 100–600 blocks: deeper caves are larger.

## Shape by rock

The rock of each block (from the [[Blocks and Chunks|3D Crust]]) selects a style:

| Style | Rocks | Cavern threshold | Tunnel half-width | Fissures | Measured void |
|---|---|---|---|---|---|
| karst | limestone, dolostone, chalk, evaporite, marble | 0.36 (big caverns) | 0.085 (wide) | yes, wide | ~11 % |
| clastic | sandstone, arenite, arkose, conglomerate, mudstone, shales, chert, greywacke, ironstone | 0.50 | 0.065 | no | ~5 % |
| crystalline | granites, diorite, gabbro, dolerite, gneisses, schists, migmatite, amphibolite, eclogite, quartzite, serpentinite… | 0.60 | 0.045 (thin) | yes: tall narrow slots | ~5.5 % |
| volcanic | basalt, scoria, tuff, breccia, pumice, andesite, rhyolite | 0.56 | 0.055 | lava tubes | ~3.6 % |
| mantle | peridotite, chromitite | 0.80 (only the huge caverns) | 0.03 | no | ~5 % (huge caverns) |

Because the style is chosen per block, a cave crossing a contact changes shape there: a wide limestone hall narrows into a granite fissure.

## Surface and soil

- A roof of 6 blocks everywhere, except in **entrance zones** (`fbm(/220) > 0.42`, ~15 % of the land), where caves may reach the surface; trees are not planted there (a cave may open under the trunk).
- 8 blocks of rock under rivers, lakes, the sea and ice: caves never drain them.
- Never under loose soil: below sand or gravel at least one block of rock stays (the soil would fall).
- Cave mouths also open sideways where a cave meets a cliff face (the roof is measured on the column itself).

## Cost

Interpolation is trilinear, so the extremes of a lattice cell are at its 8 corners. `prepare` stores per cell which kinds of cave are possible (bit mask: cavern, huge, tunnel, fissure, tube) and `at` evaluates only those; a cell with none costs nothing per block. Deep cubes skip the 2D masks (entrances, tube levels). Measured (Java 25, warm):

| | per crust cube |
|---|---|
| lithology only | 0.14–0.15 ms |
| lithology + caves | 0.23–0.25 ms |

A 4-block lattice (125 nodes) cost 0.28 ms; most of the remaining cost is per block in cells where some cave is possible.

## Results (seed 12345, 220 columns, ~5900 cubes)

| Depth | Void | Water | Lava |
|---|---|---|---|
| y −64..0 | 4.4 % | 5 % | 0 |
| y −256..−64 | 4.9 % | 20 % | 0 |
| y −640..−256 | 4.4 % | 15 % | 0 |
| y −1200..−640 | 6.4 % | 24 % | 4 % |
| y −1800..−1200 | 5.5 % | 23 % | 10 % |
| y −2048..−1800 | 6.6 % | 15 % | 22 % |

Huge caverns of ~240×100 blocks appear around y −700..−800.
