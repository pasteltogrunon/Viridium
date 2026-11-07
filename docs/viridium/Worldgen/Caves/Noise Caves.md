# Noise Caves

Part of [[Caves Overview]] · fluids in [[Aquifers and Lava]].

Code: `CaveCarver` (`Cube.prepare`, `Cube.at`, `noise`), `CaveNoise` (3D gradient noise).

## Fields

Five 3D noise fields, evaluated on a lattice of **8 blocks** (3×3×3 nodes per cube) and interpolated trilinearly per block:

| Field | Noise | Makes |
|---|---|---|
| `C1` | fbm3, 2 octaves, x/z ÷ 130, y ÷ 62 | caverns: `C1 > threshold` (flattened: wider than tall) |
| `C2` | fbm3, 1 octave, x/z ÷ 260, y ÷ 150 (only below y −150) | huge deep caverns: `C2 > 0.62 − 0.2·ss(−200, −1400, y)` |
| `A`, `B` | fbm3, 2 octaves, x/z ÷ 90, y ÷ 38 | tunnels: `|A| < w and |B| < w` (two isosurfaces cross: long winding tubes; the noise varies faster vertically, so the surfaces lie flat and the tubes run horizontally) |
| `F` | fbm3, 1 octave, x/z ÷ 38, y ÷ 260 | fissures: `|F| < w` inside cavern-prone rock (`C1 > 0.2`): tall vertical slots, rare |
| `A` + 2D level | | lava tubes: `(A/0.075)² + ((y − tubeY)/4)² < 1`, `tubeY` 9–25 blocks under the surface |

The cavern threshold rises by 0.06 in the first 40 blocks under the ground (none in entrance zones, so rooms can open there) and falls a little below 100–600 blocks: deeper caves are larger.

**Revision (2026-11-07)**: the first tuning (thresholds 0.36–0.80, tunnel noise stretched vertically, wide fissures) gave caves that were narrow and mostly vertical: the tunnel isosurfaces stood upright and crystalline rock, most of the crust, had almost no caverns but many fissures. Now caverns are larger and lower-threshold in every rock (rooms with pillars, like modern Minecraft), tunnels run horizontally and fissures are rare.

## Shape by rock

The rock of each block (from the [[Blocks and Chunks|3D Crust]]) selects a style:

| Style | Rocks | Cavern threshold | Tunnel half-width | Fissures | Measured void |
|---|---|---|---|---|---|
| karst | limestone, dolostone, chalk, evaporite, marble | 0.24 (huge rooms) | 0.11 (wide) | thin, rare | most |
| clastic | sandstone, arenite, arkose, conglomerate, mudstone, shales, chert, greywacke, ironstone | 0.32 | 0.095 | no | |
| crystalline | granites, diorite, gabbro, dolerite, gneisses, schists, migmatite, amphibolite, eclogite, quartzite, serpentinite… | 0.38 | 0.08 | thin, rare | |
| volcanic | basalt, scoria, tuff, breccia, pumice, andesite, rhyolite | 0.36 | 0.085 | lava tubes | |
| mantle | peridotite, chromitite | 0.62 | 0.05 | no | |

Because the style is chosen per block, a cave crossing a contact changes shape there: a wide limestone hall narrows into a granite fissure.

## Surface and soil

- A roof of 6 blocks everywhere, except in **entrance zones** (`fbm(/220) > 0.3`, ~25 % of the land), where caves may reach the surface; trees are not planted there (a cave may open under the trunk).
- 8 blocks of rock under rivers, lakes, the sea and ice: caves never drain them.
- Never under loose soil: below sand or gravel at least one block of rock stays (the soil would fall).
- Cave mouths also open sideways where a cave meets a cliff face (the roof is measured on the column itself).

## Cost

Interpolation is trilinear, so the extremes of a lattice cell are at its 8 corners. `prepare` stores per cell which kinds of cave are possible (bit mask: cavern, huge, tunnel, fissure, tube) and `at` evaluates only those; a cell with none costs nothing per block. Deep cubes skip the 2D masks (entrances, tube levels). Measured (Java 25, warm):

| | per crust cube |
|---|---|
| lithology only | 0.14–0.15 ms |
| lithology + caves | 0.25–0.28 ms |

A 4-block lattice (125 nodes) cost 0.28 ms; most of the remaining cost is per block in cells where some cave is possible.

## Results (seed 12345, 220 columns, ~5900 cubes)

| Depth | Void | Water | Lava |
|---|---|---|---|
| y −64..0 | 13.4 % | 19 % | 0 |
| y −256..−64 | 13.4 % | 14 % | 0 |
| y −640..−256 | 11.1 % | 17 % | 0 |
| y −1200..−640 | 12.2 % | 24 % | 4 % |
| y −1800..−1200 | 7.5 % | 20 % | 9 % |
| y −2048..−1800 | 9.3 % | 18 % | 21 % |

Near the surface the rooms are typically 100+ blocks wide and ~20 high, linked by horizontal tunnels.

Huge caverns of ~240×100 blocks appear around y −700..−800.
