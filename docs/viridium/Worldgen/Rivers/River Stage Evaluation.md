# River Stage Evaluation

Part of [[Rivers Overview]] · depends on: [[Drainage Basins]], [[River Network Build]] and everything it produces
([[River Water Levels]], [[River Geometry and Meanders]], [[Lakes and Salt Flats]],
[[River Mouths, Deltas and Estuaries]], [[Valleys, Gorges and Glacial Troughs]]), plus [[Worldgen Stages|Erosion]] (the incoming height `h`) · used by:
[[Worldgen Stages|Cryosphere]], [[Worldgen Stages|Biome Stage]], Column Plans, Cubic Chunks Integration (prefetch).

Code: `stages/RiverStage.java`.

This is what runs at every (x, z) of the world during the pipeline (stage 10). It never builds anything new except by
asking for networks; all the hydrology was decided at build time.

## Main entry

`apply(x, z, o, s)`:

1. Reset every river field of the point (`river`, `riverD = 1e9`, `riverHW`, `riverAcc`, `riverZ`, `riverWL`,
   `valley`, `delta`, `estu`, `lake`, `lakeZ`, `glac`, `gorge`).
2. Return immediately in the open ocean (`L < −0.5`) or when rivers are off (far map zooms).
3. `basins.nearCells(x, z, MARGIN = 960)`: the basin containing the point plus every neighbour whose divide is closer
   than 960 blocks (the margin the valley field extends over). Usually one or two cells.
4. Get the network of each non-ocean cell (building it if needed, see below). Keep those with rivers or lakes.
5. Apply the networks in [[#Phases]].
6. **Drowned last reach**: if after all that the point is below −1, flagged as river, not in a delta, and its water level
   is below 1, the river flag is removed: the sea covers it (fjord, estuary mouth). A deep channel inland keeps its own
   water; otherwise its middle would drop to sea level between higher river water.

## Phases

Every point may be shaped by several networks (near divides). They are applied in **three phases across all
networks**, not network by network:

| Phase | What, for every network |
|---|---|
| 0 | delta plains, broad valleys |
| 1 | river channels |
| 2 | delta distributary channels, estuaries, lakes |

So all valleys are done before any channel measures its water against the terrain, and all channels before the
features that must win over them (distributaries replace the main stem, estuaries widen it, lakes replace channels).
Valleys and channels of neighbouring basins therefore stay continuous across divides.

Valleys and channels are shaped wherever the point can be land for the network builder: `L ≥ LAND_L = −0.2` (the
builder takes a node for sea below it, and lets the relief decide above it). A stricter limit here used to leave the
rivers of pockets of slightly negative continentality uncarved: a river cut off in the middle of the land, with its
estuary on the other side.

**The networks do not stack** (`RiverStage.valleys`). Every network shapes the *same* incoming terrain with the broad
valley of its nearest river, and the results are blended with weights `exp(−(D − Dmin)/30)`, `D` being the distance to
that river in each network's valley field: the nearest river of any network decides, exactly as inside one network
(whose field only knows the nearest river of a point). Applied one after another, the wide valley of a large river in
the next basin cut the ground from under a river on its flank (water left hanging, clamped to the slope column by
column), and the result depended on the order of the networks, which changes at the divide (a cliff along it).

Likewise every network cuts its channels into the same ground (`FieldMemo.hch`, the terrain after valleys and fans)
and the lowest result is kept: where a river crosses a divide, the last reach of one network and the first of the next
overlap, and cut one after the other they dug the bed twice as deep and sank the water to the other's bed.

## Rock factor

For each network (phases 0 and 1), the valley field is sampled
([[Valleys, Gorges and Glacial Troughs#Sampling the field (runtime)]]) and the rock factor computed:

```
ef    = clamp(lerp(0.5, (lerp(o.erod, FE, FEW) − 0.2)/0.7, valleyRock), 0, 1)
gorge = 1 − ef
```

The network's interpolated erodibility (`FE`) is blended with the point's own, so the valley shape changes smoothly
between rock patches. Valleys and channels are only applied where `L ≥ −0.04`.

## Channels

Phase 1, for one network:

1. **Warp the query point** by ±3 blocks of Perlin noise (`rivW`), so banks are not perfect mathematical curves.
2. **Candidate reaches**: the river nodes within `RC = ceil((2·g + 24 + reach)/g)` grid cells, skipping reaches replaced
   by a delta, rejected by bounding box (padded by `VMAX = 2·g` + half-width).
3. For every one of the 8 segments of each candidate reach: distance `dist` to the segment, position `u` along the
   reach, interpolated half-width `hw` (times the point's **width factor**, see below) and water level `z`, **signed
   distance to the bank** `v = dist − hw`.
4. Per segment, a proposed height `hc`:
   - glacial lowering `G = glacial(o, z)·ss(8, 45, FR)` and water surface `zc = z − G·glacialDepth(z, hw₀)`, with `hw₀`
     the river's own half-width (without braiding or the width factor): the same depth the broad valley gives its
     trough floor, so the water lies on that floor. (It used to be `z − G²·glacialDepth`: in a half-glaciated valley
     the water stood above the floor, was clamped to the ground column by column, and the gravel bars of a braided
     river rose blocks above it.)
   - **inside the channel** (`v ≤ 0`): a parabolic bed `min(h, zc) − (1.2 + 0.25·hw)·depth·(1 − q²)`, `q = dist/hw`: about
     1.2 blocks deep at the centre for a small stream, ~13 for the largest rivers (times the **depth factor**);
   - **on the banks** (`0 < v < W`): a bank rising from the water level with slope `bankSlope = lerp(1.5, 0.25, ef)`,
     blended into the terrain over `W = min((5 + 2·hw)·lerp(0.45, 2, ef), 2·g)` blocks; only ever lowers the
     terrain;
   - **headwater reaches** fade in over 90 % of their length (`ss(0, 0.9, u)`): a river starts without a valley.
5. `h = min` of all proposals: the union of all channels is carved, continuously across segment and reach boundaries.
6. The **nearest reach** of any network (or delta distributary, see
   [[River Mouths, Deltas and Estuaries#Delta shaping at runtime (RiverStage.deltas)]]) sets the point's water:
   `riverD = v`, `riverHW`, `riverAcc`, `riverZ`, and `riverWL = min(h, water surface)`: water fills the channel up to
   the bank, never above the incoming terrain.
7. **Bank lip**: if this network owns the nearest channel and the point is less than 4 blocks outside it
   (`0 < v < 4`), the carved height is raised towards `water surface + 0.6·max(0, 1 − v/3)` (never more than 3 blocks),
   fully up to 2.5 blocks out, then eased out by `1 − ss(2.5, 4, v)` (no crease where it ends),
   so a low bank right next to the water cannot let it spill. It is applied after the carve, the same way as the
   delta distributary banks.
8. `river = 1` if inside a channel; otherwise `valley = max(valley, 1 − v/W)`.
9. **Water cap between channels** (after all networks): every reach holds down the water around it to
   `its level + 0.5·v` (`WATER_SLOPE`) within `WATER_REACH = 12` blocks of its bank (rising steeply beyond). Only
   *beside* the reach: not where its nearest point is one of its two ends (the next reach takes over there, and a
   reach says nothing about the river above a waterfall), and never from a headwater or a waterfall reach. If the
   lowest cap is more than 0.75 below the point's water, the water comes down to it and the bed with it (a gravel bar
   stays just above). So a tributary coming in at a tangent, or a hanging valley, slopes down to the lower channel
   over the last blocks instead of standing beside it as a wall of water.

A gravel bar of a braided channel stands 0.5 above the water *as it is at that column* (`riverWL`), not above the
reach's nominal level.

A river crossing the funnel of an **estuary** (a tributary still blocks above it) has its bed and banks lowered to the
tidal flats in phase 2: its water is lowered by the same amount, instead of standing over the flats.

**Irregularity** (`RiverStage.irregularity`, once per point, the same for every network): rivers are a little less
smooth than their geometry. Width factor `clamp(1 + 0.18·perlin(p/40) + 0.025·perlin(p/12), 0.75, 1.25)`: the channel
widens and narrows along the river over a few tens of blocks, with a slight jitter of the bank line (the search pads
are widened by 1.25 for it, here and in the network's channel index). Depth factor `1 + 0.3·perlin(p/70)`: pools and
riffles. The water level is unchanged, so every water rule (bank lip, lakes, falls) holds as before.

Column Plans turns `riverWL` into water blocks: water from the bed up to `riverWL`, only if that is above the
surface.

## Network cache

`net(ci, cj)` returns the network of a basin cell:

- A `ConcurrentHashMap` of finished networks with a last-use stamp; **LRU eviction above 64 networks** (`CACHE_MAX`).
  Reads only bump the stamp; the clock ticks on inserts.
- A second map of in-flight `FutureTask`s: if several threads ask for the same network, one builds it and the others
  wait for the same result.
- Building a network recursively asks for the networks of the upstream basins (for their exit flow and level) and of
  lower-coloured neighbouring outlets (for shared deltas), see [[River Network Build#Inflow from upstream basins]] and
  [[River Mouths, Deltas and Estuaries#Order and shared deltas]].
- A build normally parallelises its per-node work over all cores. Threads marked with `buildSeriallyOnThisThread()`
  (background map workers) build on their own thread only.
- `buildCount()`, `buildNanos()` and `GenStats.RIVER_NET` measure builds; `/viridium bench` reports them.

Helpers for callers:

- `basinsAt(x, z)`: the basin keys whose networks shape a point.
- `isReadyAt(x, z)`: true when every network that shapes the point is already built. The in-game map uses it to avoid
  triggering builds on the render thread.
- `clearCache()`.

Because a network covers ~10 000 × 10 000 blocks, a player usually stays in the same handful of networks for a long
time; the plan prefetcher builds the ones around the player in the
background before any column needs them.

## Implementation notes

- Until commit `76de28a`, the bank lip in `applyNet` was written before `o.h = hmin` and so was overwritten and had
  no effect. It now runs after the carve, and only when `best <= riverD` so it never fills another network's channel.
- Fields like `riverZ`, `riverAcc` and `valley` are mainly for the map and diagnostics; `river`, `riverWL`, `lake`,
  `lakeZ`, `delta` and `estu` drive the blocks and biomes.
