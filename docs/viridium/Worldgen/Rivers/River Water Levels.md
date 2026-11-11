# River Water Levels

Part of [[Rivers Overview]] · step 7 of the [[River Network Build]] · depends on: [[Worldgen Stages|Erosion]] (final terrain),
[[Drainage Basins]] (entry / exit levels) · used by: River Geometry and Meanders (`z0`, `z1`),
River Stage Evaluation (channel carving), River Mouths, Deltas and Estuaries (delta apex level),
Valleys, Gorges and Glacial Troughs (valley floor), Column Plans (water blocks).

Code: `RiverNetBuilder.build` (water profile section), `valleyFloor`, `finalHeight`.

The water level `wl` of every river node decides where the water surface is, and therefore how deep the channel is
carved and whether the water looks right. This is where most of the visual bugs of rivers came from (walls of water,
perched rivers, staircases at confluences), so the procedure has several passes.

## Why not use the flood surface

The priority-flood gives a `filled` height, but it is computed on the **smooth base relief** at 32-block resolution. The
real terrain (after [[Worldgen Stages|Erosion]]) has gullies and is generally lower in valleys, so the filled profile sits **above**
the real valley floor: water would float above the ground. Hence the levels are re-measured on the final terrain.

## 1. Valley floor projection

For each river node, `wl = max(0, valleyFloor(node, receiver, hw))`:

- three cross-sections, at the node and at 1/3 and 2/3 of the way to its receiver (the reach runs between them);
- each cross-section samples the centre and 3 points on each side perpendicular to the flow, out to
  `R = max(10, 3·hw + 6)` blocks;
- every sample is `finalHeight`: the pipeline evaluated up to `Stage.EROSION` (terrain the player would see without
  rivers);
- the **minimum** of these 21 samples is the valley floor.

These evaluations are independent per node and run in parallel. They are the most expensive part of a network build.

Special nodes:

- **Entry nodes**: the upstream network's `exitLevel` if known, so the river never climbs when it crosses a divide;
  otherwise the same floor sample (with the inflow's width).
- **Exit node**: its own floor sample; stored as `exitLevel` for the downstream basin. Both basins sample exactly the
  same node, so the levels agree.

## 2. Monotone downstream

Walking upstream → downstream (reverse flood order):

```
if wl[node] < wl[recv]:  wl[recv] = wl[node]
```

Water **never climbs** downstream: a receiver is never above any of its river nodes. A bump in the valley floor
lowers everything downstream of it to the upstream level (the channel will be cut through the bump).

## 3. Floodplain clamp

A small river next to a much larger one sits on the large river's floodplain. For each river node, every river node in
the 5×5 neighbourhood with **≥ 3× the flow and ≥ 2.5× the half-width**, whose channel is within `hw_big + 1.2·g`
blocks, caps it:

```
wl[small] ≤ wl[big] + 0.5
```

Without it, the small river's last reach would hide its drop under the large river and show it all at once at the bank
(a staircase), or run alongside it on a perched terrace. If any node was clamped, pass 2 is repeated.

## 4. Tributaries

No reach may drop more than `MAX_DROP` = 2.5 blocks into its receiver. Walking **downstream first** (flood order), so
the effect chains upstream:

```
if wl[node] > wl[recv] + 2.5:  wl[node] = wl[recv] + 2.5
```

A tributary therefore **descends gradually to the level of the river it joins**, instead of hanging above the larger
river's deeper valley and falling into it at the confluence. The terrain upstream is higher than this lowered profile,
so the channel is carved into it: small rivers cut short gorges where they enter large valleys.

**Side by side before a confluence.** Two rivers about to join often run next to each other for their last nodes, each
on its own valley floor. Within 4 nodes of the confluence, any node of the tributary and any node of the main river
(above or below the confluence) whose channels come within `0.75·g` of each other (`distance ≤ hw + hw + 0.75·g`) keep
within one block: the higher one is lowered. (Rivers that run in neighbouring grid nodes for longer than that are
joined outright, see [[River Network Build#River capture]].)

**Entry nodes are never lowered** by the floodplain clamp, this rule or `MAX_DROP`: the basin above ends its river at
the level it gave them, and lowering it here made the water step down at the divide.

## How levels are used by reaches

Each reach stores levels at its two ends (see River Geometry and Meanders):

| End | Level |
|---|---|
| upstream `z0` | average of the node and its main upstream node (or the node's own level for a headwater) |
| downstream `z1`, main stem | average of the node and its receiver |
| downstream `z1`, confluence | the level at the middle of the receiving reach (`(z0 + z1)/2` of it), exactly where the tributary joins |
| downstream `z1`, mouth | 0 (sea level) |
| downstream `z1`, exit node | the exit node's level |

Inside a reach the level is linearly interpolated, so the water surface is continuous along the main stem and at
confluences.

At runtime the water surface is further limited to the terrain (`riverWL = min(h, surface)`, the water fills the
channel up to its bank, never above it). See River Stage Evaluation.

## Waterfalls

Main-stem edges that drop 6+ blocks across a cliff (found only near a scarp, with terrain samples along the edge) skip the drop limit: the river keeps its level up to the lip and the reaches join there, the one below at the level of its pool. See [[Landforms Overview|Waterfalls]].
