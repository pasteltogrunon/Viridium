# Lakes and Salt Flats

Part of [[Rivers Overview]] · step 4 of the [[River Network Build]] (planning) and phase 2 of
[[River Stage Evaluation]] (shaping) · depends on: the priority-flood of [[River Network Build]], [[Worldgen Stages|Erosion]] (real
basin floor), [[Worldgen Stages|Base Climate]] (runoff) · used by: [[Worldgen Stages|Cryosphere]] (frozen lakes), [[Worldgen Stages|Biome Stage]], Column Plans.

Code: `RiverNetBuilder.build` (lakes section), `RiverStage.lakes`.

## Where lakes come from

The priority-flood fills every closed depression of the basin grid up to its spill point. A node is a **lake
candidate** when it was filled by more than `2 / lakes` blocks (`filled − hgt`; 2 blocks with the default `lakes = 1`).
Candidates are grouped into 4-connected components; components of one node are ignored.

Endorheic basins (no sea, no exit; see Drainage Basins) flood from their lowest point, so their lowest area always
becomes a lake or a salt flat.

## Lake level

For each component:

| Quantity | Meaning |
|---|---|
| `lev` | highest filled height in the component: the spill level of the depression |
| `bot` | lowest node of the component on the coarse relief |
| `mm` | mean runoff of the component, in mm/year (includes inflow from rivers) |
| `frac = ss(250, 700, mm)` | how wet the climate is |

- **Wet enough** (`frac ≥ 0.12`): a lake, filled to `bot + (lev − bot)·(0.25 + 0.75·frac)`. Arid lakes are partly
  evaporated; humid ones are full to the brim.
- **Arid** (`frac < 0.12`): a **salt flat**, at `bot + 0.1·(lev − bot)`. Stored with a negative level.

Then the level is corrected:

1. **Anchored to the real basin floor**: up to ~24 nodes of the component are sampled on the eroded terrain
   (`finalHeight`, `Stage.EROSION`). If the real floor `botF` is lower than the coarse `bot`, the level is lowered by
   the difference. The coarse grid only knows the smoothed relief.
2. **Never above the spill point of the basin**: the lowest owned land node on the edge of the basin's own area
   (`spill`). A lake can never stand above the lowest point of the basin's whole divide, since it would spill there
   first. Level ≤ `spill − 1`.
3. If the level ends up at or below the floor, there is no lake.

Every node of the component lower than the level is stored in `RiverNet.lakes` with the level (negative for salt).

Rivers keep flowing through lakes: the river nodes and their receivers are not changed by lakes.

## Shaping at runtime

`RiverStage.lakes` (phase 2, after valleys and channels):

- The lake nodes are interpolated with a **cubic B-spline** over the 4×4 surrounding nodes: `m` = lake coverage
  (0..1), `Z` = mean level, salt if salt nodes have most of the weight.
- The shore threshold `thr = 0.36 + 0.13·perlin(x/45, z/45)` makes the shoreline irregular.
- **Inside** (`m > thr` and terrain below `Z`):
  - lake: `lake = 1`, `lakeZ = Z` (the column planner fills water up to it);
  - salt flat: `lake = 2`, terrain set perfectly flat at `Z − 0.3` (evaporite surface, see
    Cube Filling and Surface Rules);
  - any river flag is cleared: the lake replaces the channel.
- **Outside**: a **rim** is raised so the lake is enclosed: `rim = Z + 30·(thr − m)`, blended in with
  `ss(thr − 0.3, thr, m)`. The rim is suppressed near river channels (`ss(2, 14, riverD − riverHW)`), so rivers flow
  into and out of the lake freely.

Lakes override the biome ([[Worldgen Stages|Biome Stage]]: `LAKE`, `FROZEN_LAKE`, `SALT_FLAT`) and freeze below −2 °C
([[Worldgen Stages|Cryosphere]]).
