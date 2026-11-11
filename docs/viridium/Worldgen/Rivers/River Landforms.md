# River Landforms

Part of [[Rivers Overview]] · planned in [[River Network Build]] (oxbows, fans, braiding, rias, incision of the water profile) and drawn in [[River Stage Evaluation]] (terraces and rias in the broad valley, fans in their own phase, braids and canyons in the channel phase, oxbows with the lakes) · depends on: [[Valleys, Gorges and Glacial Troughs]], [[River Geometry and Meanders]], [[River Water Levels]].

Code: `RiverNetBuilder.oxbows`, `fans`, `braids`, `rias`; `RiverStage.terraces`, `fans`, `oxbowLakes`, `incision`, the braid and canyon parts of the channel phase, the ria part of `broadValley`.

Every landform is a pure function of the seed (planned per basin network, or computed per point). Parameters (`GenParams`, Java-only, `0` turns one off, all off in `ParityTest`): `terraces`, `oxbows`, `fans`, `braids`, `incision`, `rias`.

| Landform | Where (geology) | Shape |
|---|---|---|
| river terraces | soft rock / valley fill, rivers of 2.5+ blocks half-width, where the river incised in pulses: uplift (convergent margins, plateaus, collisions) or the glacial cycles of mid and high latitudes; never near the sea, in gorges or glacial troughs | flights of 2-4 flat benches, 3-5 blocks apart, risers of coarse dirt over gravel |
| oxbow lakes | meandering rivers (meander amplitude ≥ 8) on soft rock or fill, a third of them | horseshoe lake beside the bend's apex, open towards the river |
| alluvial fans | where a river leaves a steep course (≥ 4.5 % over 3 nodes) for a gentle one (≤ 2 %) under high relief; mostly in dry climates | concave cone, 60-450 blocks, up to ~25 blocks above the river at the apex; the river runs in a trench cut into it |
| braided rivers | steep-ish rivers (0.4-1.5 %) with coarse load: a glaciated catchment (now or at the last glacial maximum) or a dry one with high relief | channel up to 2.6× wider and shallow, split by lens-shaped gravel bars; no meanders |
| incised meanders, canyons, slots | dry plateaus and dry sedimentary uplands (Colorado Plateau, Canyonlands) | rivers graded to the low ground: gentle water profile far into the upland, canyon walls following the channel's bends; in dry sandstone small streams cut slots with near-vertical walls |
| rias | hilly coasts that are not glaciated, rivers with little sediment | the last 250-1100 blocks of the valley drowned by the sea, V-shaped, deepest at the mouth |

## River terraces (`RiverStage.terraces`)

In reality: a river that cuts down into its own valley fill leaves its old floodplain behind as a bench; repeated incision makes a flight of them (the Rhine, the Thames, the Snake River). They need loose fill and pulses of incision.

```
paleo  = ss(3, −5, Tb − glacialCooling)                   periglacial at the last glacial maximum
uplift = clamp(0.4 + 1.1·orog + 0.8·plat + 0.8·collR + 0.7·paleo, 0, 1)
w      = terraces·fade·uplift·ss(2.5, 6, hw)·ss(0.3, 0.6, ef)·ss(2, 10, zr)·(1 − ss(0.55, 0.95, t))·regional noise
hr     = h − (zr + 0.6)                                    height above the floodplain
S      = 3..5 blocks (bench height, noise), n = 2..4 benches per 2048-block cell
h      = floor + lerp(hr, (⌊hr/S⌋ + ss(0.62, 0.9, frac))·S, w·(1 − ss(n·S, (n + 1)·S, hr)))
```

`GenPoint.terrace` keeps the weight; the steep part of a riser gets the alluvium cover (`SURF_ALLUVIUM`: coarse dirt over gravel, never bare rock). The treads keep the biome's soil.

## Oxbow lakes

**Planning** (`oxbows`, after the deltas, before the valley field): for each meandering reach, the point of its polyline farthest from its chord is the bend's apex. The lake's centre lies beyond it, `1.25·hw + 4 + 0.96·(r + w)` from the channel; radius `r = clamp(0.28·λ, 10, 55)`, smaller where the floodplain could not hold it (the valley field stores the meander amplitude up to 63.75 blocks), arc half-width `0.8·hw`. Rejected when a channel (with its half-width and 3 blocks) comes into the arc's ellipse, or another oxbow is close. Water: the lowest water of the node and its neighbours, minus 0.15 (fed by seepage). The meander belt the valley keeps flat (`cellA`, the node's meander amplitude) is widened for the node and two nodes up and down so the floodplain holds the lake.

**Shaping** (`oxbowLakes`, with the lakes): an ellipse stretched across the open side (a cut-off loop is wider than deep), radius wobbling ±12 %, tapering shut at both ends; lake water at its level, never next to a channel, only where the ground is between 0.3 and 8 blocks above the water (the floodplain or the foot of the valley side: ground above the water all round).

## Alluvial fans

**Planning** (`fans`): candidates as in the table; `R = clamp(55 + 28·√flow, 60, 420)·(0.7..1.15 dry)`, kept with probability `0.3 + 0.6·arid`; apex height `wl + clamp(0.06·R, 4, 25)`, toe at the river's level ~R downstream. Never with the sea, a lake or a waterfall within R; the largest fan of a mountain front wins the space.

**Shaping** (phase 1 of the river stage, after every broad valley and before every channel): a concave radial profile `zT + (zA − zT)·(1 − r/R)^1.7` with shallow radial ribs, over a sector of about ±100° downstream, filling the ground below it. `SURF_FAN`: gravel over sand in dry climates (desert pavement), soil with gravel on steep parts in humid ones.

## Braided rivers

**Planning** (`braids`, before the reaches): per river node, `braids·ss(0.004, 0.015, water slope)·max(ice, dryHigh)·(1 − incision)·ss(1.5, 6, wl)`, smoothed along the river. `ice`: the top of the catchment under ice today or with half the glacial cooling (big ice caps only), 60-150 blocks above the node; `dryHigh`: dry and 60-160 blocks of relief upstream. The reach widths include the widening `1 + 1.6·b`, so search pads, channel index and banks need nothing else; the meander amplitude is multiplied by `1 − b`. The flat valley floor is widened to hold the channel.

**Channel** (phase 2): the bed is shallow (`0.7 + 0.03·hw`). Gravel bars: noise in channel coordinates (distance along the river to the mouth, signed distance across), lens-shaped and several widths long; a bar stands half a block above the water (`SURF_GRAVEL`).

## Incision: canyons, goosenecks and slots (`RiverStage.incision`)

```
plateau = ss(0.35, 0.75, plat)·ss(1000, 550, Pb)
canyon  = ss(650, 350, Pb)·(sandstone + 0.8 limestone + 0.5 shale + 0.4 tuff)·ss(35, 90, h0)
inc     = incision·max(plateau, canyon)
```

Only fields known before the rivers: the network builder computes the same mask per node.

- **Water profile**: the drop limit per grid node goes from 2.5 down to 0.6 blocks (`INC_DROP`): the river is graded to the low ground beyond the scarp and its canyon reaches kilometres back into the upland, instead of a short gorge. No waterfalls where `inc > 0.5` (humid plateaus keep their falls, like tepuis).
- **Broad valley**: weakened by `1 − 0.85·inc`; the channel cuts the canyon itself.
- **Channel phase**: the banks rise as walls `z + 0.8 + v·lerp(2.2, 7, slot)` up to 64 blocks from the channel, so the canyon follows every bend of the river: meanders become goosenecks. `slot = dry sandstone × small stream (hw 1.5-3.5)`: near-vertical slot canyons.

## Rias

**Planning** (`rias`, in the water levels, before the waterfalls and the drop limit): a mouth on a hilly coast (≥ 25-55 blocks of relief within 3 nodes), not glaciated (that is a fjord), without a sediment-laden river (`ss(5, 45, flow)·ss(40, 300, relief) ≤ 0.3`, else a delta has filled it), under a regional noise (about a quarter of the hilly coasts). The main stem is marked over `Lr = clamp(250 + 30·hw, 250, 1100)` (1 at the mouth, 0 at the head), its water set to sea level (rising ~2.5 blocks at the head); the drop limit then makes the river above descend to it. No delta at a ria mouth.

**Valley field**: `RiverNet.vq` carries the mark of the nearest river (`s.FQ`).

**Broad valley**: `floor −= Q·(4 + 0.5·hw + 8·Q)`, a narrower floor and valley, V-shaped sides. The floor below sea level floods: a long, winding arm of the sea.

## Results (seed 12345)

- 0,0 ± 8 km: 73 oxbow lakes, 48 fans in 38 networks; terraces along many valleys (3000..8000, 2000..7600); 62 ria cells.
- 21500,−3000 ± 6 km (dry plateau): canyons with goosenecks (17950,−1000), braided reaches (19550,−3500), fans.
- 6000,3500 ± 9 km: rias along the hilly coast (3376,2864: a drowned valley).
- `RiverWaterTest` and `LakeWaterTest` as before the landforms: lakes 0 walls of 2+ blocks, rivers tallest wall 2.
