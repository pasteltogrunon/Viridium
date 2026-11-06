# Column Plans

Part of [[Blocks and Chunks]] · depends on: the whole [[Worldgen Foundations|2D pipeline]] (evaluated up to
[[Worldgen Stages|biomes]]) and the [[3D Crust]] · used by: Cube Filling and Surface Rules, Flora,
Cubic Chunks Integration.

Code: `column/ColumnPlanner.java`, `ColumnPlan.java`, `ColumnPlanCache.java`, `TerrainLayout.java`.

## Why plans

Cubic Chunks generates 16³ cubes, and a column of the world can have hundreds of them. Everything that only depends on
(x, z), which is the whole 2D pipeline, is therefore computed **once per 16×16 chunk column** and shared by every cube
of that column, whatever its Y.

## ColumnPlanner

`plan(chunkX, chunkZ)` evaluates the pipeline at the 256 block columns and converts each `GenPoint` to absolute Y:

- `surfaceY = ceil(63 + h) − 1`: highest solid block.
- Water (`waterY`):
  - lake: lake level (`lakeZ`);
  - river: the river's water surface `riverWL` (see River Water Levels and River Stage Evaluation);
  - sea: everything below Y 62 under sea level;
  - no water if the level is not above the surface.
- Ice (`iceBottom..iceTop`):
  - frozen lake (`seaIce = 3`) or river / lake water with `T < −6`: one ice block at the water surface;
  - floe: from sea level up to `hs`; ice shelf: 10 blocks below sea level to `hs`; iceberg: 3× its height under water.
- Per column: flags (`F_RIVER`, `F_LAKE`, `F_SALT`, `F_FLOE`, `F_SHELF`, `F_BERG`, `F_FROZEN`), temperature,
  precipitation, slope, surface rock, Viridium biome, vanilla biome id, and the crust profile (`crust.planColumn`).
- `minSurfaceY` and `maxTopY` let the cube filler classify cubes cheaply.

A plan is ~17.5 KiB.

## ColumnPlanCache

Bounded Guava cache (2048 plans ≈ 36 MiB) plus a map of in-flight `FutureTask`s, so when the server thread and a
background prefetch thread ask for the same column, only one computes it and the other waits. Eviction never changes
the output.

## TerrainLayout

| Constant | Value |
|---|---|
| `SEA_LEVEL` | 63 (height 0) |
| `MIN_Y` | −2048 (bottom of the deep crust; one bedrock cube below) |
| `MAX_Y` | 1024 |
| `SURFACE_RULE_DEPTH` | 10 |

## Waterfalls

`fallTop[i]` is the top of a waterfall curtain above the water of the column (`NONE` otherwise) and `F_FALL` marks the columns within a few blocks of a waterfall lip; `maxTopY` includes curtains. See [[Landforms Overview|Waterfalls]].
