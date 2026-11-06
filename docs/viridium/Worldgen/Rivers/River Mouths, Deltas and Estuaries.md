# River Mouths, Deltas and Estuaries

Part of [[Rivers Overview]] · steps 9-11 of the [[River Network Build]] (planning) and phases 0 and 2 of
[[River Stage Evaluation]] (shaping) · depends on: [[River Geometry and Meanders]], [[River Water Levels]],
[[Drainage Basins]] (shared deltas), [[Worldgen Stages|Relief]] (coast shape), [[Worldgen Stages|Tectonic Plates]] (`edge`),
[[Valleys, Gorges and Glacial Troughs]] (glaciation → fjords) · used by: [[Worldgen Stages|Biome Stage]] (`DELTA` / salt marsh).

Code: `RiverNetBuilder.build` (mouth classification, estuary reaches, delta loop), `RiverNetBuilder.buildDelta`,
`Foreign`, `RiverStage.deltas`, `RiverStage.estuaries`.

A river that reaches the sea ends in one of three ways:

| Ending | Real-world analogue | When |
|---|---|---|
| **Delta** | Nile, Mississippi, Ganges | large flow, a lot of sediment (flow × catchment relief), low coast |
| **Estuary** | Thames, Gironde | strong tide, little sediment, or a coast too steep for a delta |
| **Fjord** | Norway, Chile | the river valley was under a glacier: the glacial trough runs below sea level |

## Delta or estuary

When a reach's receiver is sea and its flow is at least 3 threshold units, a **mouth** is recorded (direction `u` =
direction of the last step). Using the flow `f` (threshold units) and the catchment relief (maximum height upstream):

```
tide = 0.5 + 0.75·perlin(x/2600, z/2600)                      regional tidal range
sed  = ss(5, 45, f) · ss(40, 300, relief)                     sediment load
E    = ss(0.42, 0.62, tide − 0.55·sed + 0.12)                 estuary strength
       · min(estuaries, 1) · ss(3, 5, f)
       · (1 − 0.75·ss(40, 200, f))                            big rivers mostly build deltas
Rd   = f ≥ 6 ? min(deltaMaxRadius, 95·√f)·deltaSize : 0       delta radius (1600 max)
Lest = min(3000, 140 + 34·hw)·max(estuaries, 0.3)             estuary length
Wm   = min(8·g, 10 + 4.2·hw)                                  estuary width at the mouth
```

The mouth is kept if `Rd > 0` or `E > 0.02`. Each mouth stores 14 floats: position, direction, `Rd`, half-width, flow,
node, `E`, `Lest`, `Wm`, reach, and the range of its estuary reaches.

**Estuary reaches** are collected for every mouth, even those that will try to build a delta: the main-stem reaches
upstream of the mouth until the distance to the mouth reaches `Lest` (at most 64). If the delta cannot form, the mouth
falls back to an estuary and needs them.

## Deltas

### Order and shared deltas

Neighbouring coastal basins often have their mouths close together, and a delta's distributaries can run into another
delta. To avoid crossing channels, **branches join other deltas where they meet**:

1. Basin cells get a 4-colouring by parity: `colour = (ci & 1) | ((cj & 1) << 1)`. No two neighbours share a colour.
2. If this cell is an outlet, the delta branches of every neighbouring **outlet** cell with a **lower** colour are
   loaded first as "foreign" branches (building those networks if needed; colours strictly decrease, so there is no
   cycle).
3. This network's own mouths are processed by **descending flow**; after each delta is built, its branches are added to
   the foreign set, so smaller deltas join larger ones.

### Delta builder (buildDelta)

Skipped (no delta) if `Rd ≤ 0` or `E ≥ 0.5`; otherwise the mouth becomes a delta (`E = 0`).

**1. Apex.** Walk up the main stem from the mouth for up to `0.35·Rd`, stopping:

- where the next node upstream is higher than 18 blocks (the valley leaves the coastal plain);
- below any **real tributary** (flow > 6 % of the river's): a tributary joining the replaced stretch would be left at
  the old river's level beside the higher distributaries.

Smaller **rivulets** joining the stretch are dropped with it (their whole upstream tree). All walked reaches are marked
`skip`: the main stem below the apex is replaced by the delta. The apex is the end point of the apex reach (halfway to
the first skipped node), or the mouth itself if nothing was walked.

**2. Fan axis.** Direction from the apex to the old mouth plus the mouth's direction, then turned towards the open water:
16 directions at 0.35·Rd and 0.7·Rd are sampled and weighted by depth (`clamp(−h, 0, 30)`); axis += 1.5 × that vector.

**3. Water profile.** Water falls **linearly** from the river's level at the apex `zS` to sea level at distance
`Lc = max(walked, 0.1·Rd)` (a little past the old coast), so it joins the river and the sea without steps.

**4. Branch tracing.** Distributaries are traced breadth-first from the apex, step `max(14, 0.025·Rd)` blocks:

- Each branch owns an **angular sector** `[lo, hi]` around the apex (the root: ±0.95 rad around the axis).
- Heading: the middle of the sector plus two Perlin wanderings. Five candidate headings around it (±0.3, ±0.6 rad) are
  scored: `max(h, −6) + 1.5·|offset|`: follow the **low ground**, prefer going straight; all water below −6 counts the
  same.
- Every candidate is clamped back into the sector (bearing from the apex), so **branches never cross**.
- A candidate that does not move away from the apex is forbidden: **a branch never turns back inland**.
- A candidate outside the area where this network is consulted (`nearCells(…, 0.6·MARGIN)` must include the cell) is
  forbidden, otherwise the runtime would never see it.
- Stops when: boxed in (best height > `zS + 25`), longer than 2.5·Rd, no valid candidate, off the shelf (`h < −30`),
  or far enough and in the sea (`r > reachR` and `h < −2`, `reachR = (0.8–1.15)·Rd`).
- **Width** tapers to 75 % at distance Rd.
- **Split**: when depth < `maxDepth`, enough distance since the last split (`(0.16–0.28)·Rd`), farther than 0.08·Rd
  from the apex and still wider than 1.6: the sector is cut at the current bearing (kept within its central 40 %), each
  child takes one side and 72 % of the width. `maxDepth = min(3, 1 + floor(flow/25))`: up to 8 leaf channels for the
  largest rivers.
- **Joining**: if a step crosses a foreign branch (widened by its half-width) or ends inside one, a last vertex is added
  at the crossing and the branch stops; its water levels are raised to at least the other branch's level there (no
  step up).

Each vertex stores `(x, z, half-width, water level, distance to the tip)`; the tip distance (∞ for branches that split
or joined) lets the plain narrow towards the ends of the last branches.

**5. No branch could leave the apex** (steep or mountain coast): the delta is abandoned, skipped reaches are restored,
`Rd = 0` and `E = max(E, 0.75)`. Like such coasts in reality, the river ends in an **estuary**, or in a **fjord**
where the coast was glaciated (estuaries are turned off there and the glacial trough runs below sea level).

**6. Plain.** The delta gets a bounding box and a plain radius `clamp(0.2·Rd, 30, 260)`.

### Delta shaping at runtime (RiverStage.deltas)

For each delta whose box contains the point, every channel gives its nearest distance `cd`, the water level there and
the distance to its tip.

**Phase 0, the plain** (before valleys):

- `cover = max over channels of 1 − ss(0.45·pl, pl, cd)` with `pl = min(plain, 0.7·tip distance)`.
- The plain height is the channels' water level blended with weights `1/(cd + 30)⁴` (no seams where the nearest branch
  changes), `+ 1.1 + 0.5·perlin`.
- Applied with `m = cover · ss(20, 90, edge) · (1 − glacial) · ss(−45, −25, h)`: not near plate boundaries, not in
  glaciated valleys, not over deep water.
- Below the plain the terrain is **raised** (fills shallow sea: the delta progrades); above it, it is lowered only if
  it is less than ~8 blocks above (it flattens low land but never touches hills).
- `delta = m` (used by the [[Worldgen Stages|Biome Stage]]).

**Phase 2, the distributary channels** (after the river channels):

- Inside a channel (`d < w`): bed `min(h, z − 1.05 − 0.22·w·(1 − q²))`, a full block deep even at the edge so there is
  no lip; `river = 1`.
- Within 3 blocks of the bank: banks raised just above the water (`z + 0.6·(1 − v/3)`, at most +3).
- The nearest channel sets the point's water fields (`riverWL = z`, flow = mouth flow).

## Estuaries (RiverStage.estuaries)

For each mouth with `E > 0.02`, near its estuary reaches. The estuary strength is faded like the delta plain:
`E · ss(20, 90, edge) · (1 − glacial)`.

Along the estuary reaches, `am` is the distance to the mouth and

```
ef = exp(−am / (Lest/3)) · (1 − ss(0.6·Lest, Lest, am))       funnel factor: 1 at the mouth, 0 upstream
We = hw + (Wm − hw)·ef                                       the channel widens into a funnel
```

The cross-section (`qd = d / We`):

| Zone | Target height |
|---|---|
| `qd < 0.45` | central channel, from `lerp(z − 1.5, −1.6 − 4.5·E, ef)` up to the flats |
| `0.45 ≤ qd < 1` | **tidal flats / salt marsh** around `lerp(z + 0.2, 0.25, ef)`, with noise and **tidal creeks** (thin lines where `|fbm|` is near 0, 1.4 blocks deep) |
| `qd ≥ 1` | blends back to the terrain over `18 + 0.6·We` blocks |

The estuary only **lowers** terrain (`lerp(h, target, E)` if the target is lower). Every reach proposes a profile and
the minimum is kept, so the result is continuous when the nearest reach changes. `estu` (flats factor) is used by the
[[Worldgen Stages|Biome Stage]] for the delta / salt marsh biome.

## Fjords

There is no fjord-specific code. A fjord appears when a river valley is **glaciated**
([[Valleys, Gorges and Glacial Troughs#Glaciation]]): the broad valley becomes a deep U-shaped trough and the channel
bed is lowered by the glacial depth, so near the coast both go below sea level. Deltas and estuaries are faded out
there (`1 − glacial`), and the final step of the river stage drowns the last stretch (see
[[River Stage Evaluation#Main entry]]): the sea fills the trough.
