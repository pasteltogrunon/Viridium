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
2. **Never above the divide it touches**: a component with a node on the basin's divide (next to a node of another
   basin, or an entry node of an upstream basin) could spill into the neighbour there, which the flood of this basin
   does not know: level ≤ (lowest such node) − 1. A depression wholly inside the basin is only limited by its own
   spill level `lev`. (This used to be the lowest point of the basin's *whole* divide, which is always its own outlet:
   almost no depression kept water.)
3. **Never above the rivers' water** (applied after the water levels and the valley field): not above the water of any
   river node in the component or within two nodes of it, lowered by the glacial trough there (`riverWater`: climate
   of the final pipeline at the node, relief around it, as the river stage draws the trough). A river through a
   depression is carved down to its own water, so a lake above it would stand as a wall over the river's valley.
4. **Never above its shore as the valleys leave it**: every node next to the component (`carvedShore`) is lowered with
   the shape of `RiverStage.broadValley` read from this network's valley field (rock of the node, no coastal fade).
   Guarded by `LakeWaterTest` (no lake water column above a dry neighbour).
5. If the level ends up at or below the floor, there is no lake.

The floor samples of step 1 are skipped for components that cannot hold water even before them (level bound by 2
already at or below `bot`).

Every node of the component lower than the level is stored in `RiverNet.lakes` with the level (negative for salt).

The steps above are for depressions no river runs through (or that could not become a river lake, below).

## River lakes

Where a river runs through a depression, the depression fills up to the level where the water leaves it, and the river
crosses it as a lake (`RiverNetBuilder.riverLakes`, during the water levels, on the **raw** profile: every river node at
its valley floor on the eroded terrain, before the profile is made monotone).

- **Outlet**: follow the depression's largest river downstream until it leaves the component. Its level `L0` is the
  outlet's valley floor, lowered to what the river below allows (walking down: running minimum of the valley floors plus
  2.5 blocks per node, the same `MAX_DROP` the profile gets later), capped by the divide like any lake. A coast within
  64 nodes below caps it at 2.5 blocks per node above the sea.
- **Level**: `floor + (L0 − floor)·(0.25 + 0.75·frac)` (`floor` = lowest river valley floor in the depression): humid
  lakes are full to their outlet; arid ones partly (then the river cuts its outlet down to the lake). Salt flats keep the
  plain rules.
- **Extent**: a flood from the river nodes below the level over every node whose eroded terrain (river nodes: valley
  floor) lies below it. It must be closed by terrain at or above the level: if it reaches the sea, another basin, an
  entry or exit node, the outlet, or grows past `8·n + 100` nodes, there is no river lake (the water would leave
  elsewhere, or the coarse depression does not match the real terrain).
- **Skipped** (left to the plain rules): within 6 nodes of the sea (deltas, estuaries), depressions holding an entry or
  the exit node, glacial troughs (the trough and its water lie below the river's level), level under 12 blocks.
- **Water**: the river nodes in the lake take its level; the outlet leaves at it (then descends as usual); every river
  node upstream keeps at least it (water never climbs) — enforced again after the clamps and the drop limit.
- Its reaches are flagged `RiverNet.inLake`: at runtime they have no banks of their own, and the lake's rim closes
  around them (the rim is only suppressed near channels outside lakes).

Rivers keep flowing through lakes: the receivers are not changed, only the water levels.

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
  into and out of the lake freely — except when the nearest channel lies in a river lake (it is the lake's own water).
- The rim zone (`m > thr − 0.3`) is reported to the cave stage (`RiverStage.lakeShore`): karst dolines skip it (a doline
  there would open a hole below the lake's water).

Lakes override the biome ([[Worldgen Stages|Biome Stage]]: `LAKE`, `FROZEN_LAKE`, `SALT_FLAT`) and freeze below −2 °C
([[Worldgen Stages|Cryosphere]]).
