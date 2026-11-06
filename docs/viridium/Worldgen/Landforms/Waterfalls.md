# Waterfalls

Part of [[Landforms Overview]] · built into [[Rivers Overview]] (network build and channel carving) · drawn by [[Blocks and Chunks|Cube Filling and Surface Rules]].

Code: `RiverNetBuilder.findFalls`, the reach geometry in `RiverNetBuilder.build`, `RiverStage` (channel phase), `ColumnPlanner`, `CubeFiller`, `CliffDetail.curtains`.

## In reality

A waterfall is a **knickpoint**: the river crosses a step of resistant rock faster than it can wear it down.

- hard rock over soft rock: the soft one is undercut and the lip retreats upstream (Niagara);
- the edge of a plateau or escarpment (Angel Falls on a tepui, Victoria Falls on a basalt plateau);
- glacial hanging valleys (Yosemite): already in the river model, up to ~10 blocks;
- fault scarps (rift shoulders), and sea cliffs where a river falls straight into the sea (McWay Falls).

## Why it needed work

The water profile of a river was made monotone and then limited to 2.5 blocks of drop per grid node (32 blocks), and `RiverWaterTest` guarded against steps. That limit is what made tributaries descend gently to their main river, but at a cliff it lowered the water upstream into a gorge, whatever the height of the cliff.

## Model

### Finding falls (`findFalls`)

After the water levels are projected onto the final terrain, and before the drop limit:

1. **Candidates** (cheap): main-stem edges `idx → recv` (or into the sea) next to a scarp (`scarp ≥ 0.15` on either node, stored while filling the relief grid) whose water drops at least `6 / waterfalls` blocks.
2. **Terrain samples** (in parallel): the final height at 9 points along the edge. It is a fall if the largest step between two samples is at least 5 blocks and 40 % of the drop.
3. The drop and where the lip is along the edge (`fallT`) are recorded. The drop limit skips these edges, so the river keeps its level up to the lip.

Tributaries keep the drop limit: they still descend gently to the river they join.

### Reach geometry

Reaches normally join halfway between nodes. A fall moves the joint to the lip:

- the reach above ends at the lip with the level of the top (`lipOut = 1`, or `2` when the sea is below);
- the reach below starts at the lip with the level of the pool (`fallIn` = drop);
- no meanders at the joint;
- a fall into the sea has no mouth (no delta, no estuary).

### Channels (`RiverStage`)

- The reach below never uses a point behind its lip (its banks, cut from the level of the pool, would carve a ramp into the cliff).
- The reach above gives no water beyond its lip.
- **Plunge pool**: the first 30 % of the reach below is dug `2 + 0.1·drop` blocks deeper.
- `GenPoint.fallTop` (curtain top) on the first 1.5 blocks below the lip, and `fall = 1` within `hw + 4` blocks of it on both sides (`F_FALL` in the plan).

### Valley field

The broad valley of a river cell is taken from its nearest river node. Below a fall, cells up at the level of the lip used to take the pool's level and the valley cut a 50-block ramp into the plateau behind the lip. Now cells at least `0.7·drop` above the pool, whose nearest node is one of the 4 nodes below a fall, take the node at the lip.

### Blocks

- `ColumnPlan.fallTop`: falling water (`flowing_water`, meta 8; ice when frozen) from the water of the column up to it.
- `CliffDetail.curtainTop`: the same next to any river water standing 3+ blocks higher, which covers oblique lips (see [[Cliff Detail]]).

## Results (seed 12345)

- `WaterfallTest` around a plateau, a sea cliff coast and a rift: 19 waterfalls (4 into the sea), the tallest 116 blocks; 236 curtain columns and 289 lip spills.
- `RiverWaterTest` (outside waterfall zones): max step 2, tallest wall 2, unchanged.
- Cost: a few terrain samples per candidate, only near scarps.

## Known limits

- The lip is found on the straight line between two grid nodes (32 blocks apart). Where a cliff crosses the river at an angle the top water borders the low ground along a few blocks; those columns spill falling water (curtains from `CliffDetail`).
- Waterfalls are static blocks until a block update: then Minecraft's own flow takes over (flowing water spreads at the pool and the lip).
