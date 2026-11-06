# Drainage Basins

Part of [[Rivers Overview]] · depends on: [[Worldgen Stages|Tectonic Plates]], [[Worldgen Stages|Continents]] (continentality `L`), [[Worldgen Stages|Relief]] (exit
node height), [[Worldgen Stages|Base Climate]] (runoff estimate), [[Worldgen Foundations|Noise and Math Primitives]] · used by: River Network Build,
River Stage Evaluation, River Mouths, Deltas and Estuaries (shared deltas).

Code: `stages/BasinGraph.java`.

The basin graph is the **continental-scale skeleton** of the river system. It decides, for every region of ~3000 blocks,
where its water goes. It is cheap (it only evaluates plates and continents at a few points), so it can be queried
anywhere.

## Basin cells

Continents are divided into Voronoi cells of size `basinSize` (3000 blocks):

- one feature point per grid cell, jittered inside the middle 70 % of the cell;
- the query position is warped by fBm (amplitude `0.22·B`, scale `0.6·B`, 3 octaves, seed `basinW`), so **divides are
  irregular** instead of straight bisectors;
- `cellAt(x, z)` returns the nearest feature point in warped space (3×3 search), packed as a `long` key `(ci, cj)`.

A `Cell` stores:

| Field | Meaning |
|---|---|
| `fx, fy` | feature point |
| `L` | continentality at the feature point (plus a ±0.01 random tiebreak) |
| `potential` | `L + basinMerge·fbm(f / 5B)`: continentality plus low-frequency "great valleys" |
| `ocean` | `L < −0.25`: the cell is sea and has no network |
| `outlet` | drains straight to the sea |
| `terminal` | has no valid lower neighbour: **endorheic** basin |
| `ri, rj` | receiving cell (when it drains into a neighbour) |
| `exitNode` | the global river-grid node on the shared divide through which its water leaves |

Cells are created lazily and cached in a `ConcurrentHashMap` (cleared above 20 000 cells; everything is recomputed
identically).

## Linking - who drains into whom

`link(c)` (synchronized, once per cell):

1. **Outlet**: if `L < 0.06` (coastal) or more than 10 % of the cell's own area is sea (`seaFraction`, a 6×6 sample),
   the cell drains to the sea through its own network. It must not hand its whole flow to a neighbour: an islet would
   otherwise receive a huge river out of nowhere.
2. Otherwise the **candidates** are the 8 neighbours with **strictly lower `L`** (closer to the sea), sorted by
   `potential` (lowest first, i.e. the one deepest in a great valley), with the key as tiebreak.
3. The first candidate that really shares a divide with `c` (it has an exit node, see below) becomes the receiver.
4. If none qualifies, the cell is **terminal** (endorheic): its network drains to its own lowest point
   (Lakes and Salt Flats).

Because `L` strictly decreases along receivers, **there are no cycles** and every chain ends at an outlet or a terminal
cell. The cells form trees rooted on the coast.

### Great valleys (`basinMerge`)

Sorting candidates by `potential` instead of `L` makes many cells converge into the same low-frequency "valleys", which
produces **few, very large basins** (Amazon, Mississippi) instead of every cell running straight to its nearest coast.
`basinMerge = 0` gives the latter behaviour.

## Exit nodes

`findExit(c, r)` finds the node through which water leaves cell `c` into its receiver `r`:

- It scans the global 32-block river grid in a window of ±0.9·B around the midpoint of the two feature points.
- Candidate nodes belong to `c` and have an 8-neighbour belonging to `r` (they are on the shared divide).
- Sea nodes (`L < 0`) are skipped.
- The **lowest** candidate on the base relief wins: the natural pass.

Both networks, upstream and downstream, compute exactly the same node (same grid, same function), so a river crosses
the divide **continuously**: the upstream network routes its water to that node (it is the outlet of its
priority-flood), and the downstream network starts a river there with the upstream flow (an *entry node*). See
River Network Build.

## Flow estimates

`localRunoff(c)` (6×6 sample of the cell, `Pb·(0.4 + 0.6·e^(−L/0.4))/1000` per grid cell) and `flowOut(c)` (own runoff
plus all children, memoised) are a **cheap estimate** of the flow leaving a cell. They are only used for diagnostics
(`DebugPointTest`) now: the networks use the real flow that leaves each upstream network (`RiverNet.exitFlow`).

## Neighbourhood queries

- `children(c)`: the neighbouring cells that drain into `c`.
- `nearCells(x, z, margin)`: the cell containing the point plus every neighbour whose divide is closer than `margin`
  (up to 9 cells). This is how the runtime knows which networks can shape a point (margin = 960 blocks, see
  River Stage Evaluation), and how the delta builder keeps branches inside the area its network is consulted for.
