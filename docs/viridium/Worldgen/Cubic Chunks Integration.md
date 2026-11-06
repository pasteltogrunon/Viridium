# Cubic Chunks Integration

Part of [[Blocks and Chunks]] · depends on: [[Column Plans]], [[Cube Filling and Surface Rules]], [[Flora]],
[[Rivers Overview|River Stage Evaluation]] (network prefetch).

Code: `cube/ViridiumWorldType.java`, `ViridiumWorldGenerator.java`, `PlanPrefetcher.java`, `ViridiumCommand.java`.

## World type

`ViridiumWorldType` ("viridium") is a Cubic Chunks world type. Only the overworld (dimension 0) uses the Viridium
generator. It extends the world height bounds to `[MIN_Y − 16, MAX_Y)` = `[−2064, 1024)` so the deep crust exists; the
extra cube below `MIN_Y` holds the bedrock floor.

## Generator

`ViridiumWorldGenerator` builds one `WorldGen` per world (seed folded from the world seed), a `ColumnPlanCache`, a
`CubeFiller` and a `FloraPlanner`.

- `provideColumn`: a chunk column with the plan's biome array.
- `provideCube`: reads (or waits for) the column plan and fills the cube.
- `populate`: nothing to do; vegetation is already in the cube ([[Flora]]).
- `onColumnPreloadFailed` / `onCubePreloadFailed`: Cubic Chunks calls these on its background threads; the generator
  uses them to precompute the column plan there, so the server thread usually finds it ready.

`ViridiumWorldGenerator.forWorld(world)` gives access to the pipeline of a world (the in-game map reuses its caches and
river networks).

## Plan prefetcher

Some work is expensive: a column plan near a river may need to **build a whole drainage-basin river network** first
(see [[Rivers Overview|River Stage Evaluation]]). `PlanPrefetcher` hides that cost:

- Every 5 ticks, for each player, it queues the missing column plans around them up to the view distance + 3 columns,
  nearest first, shifted a bit in the direction of movement.
- It also builds the river networks of the basins around the player and at 1500 / 3000 blocks in 8 directions, before
  any column needs them.
- Background pool of `min(8, cores / 3)` low-priority daemon threads. It only warms deterministic caches: output never
  depends on it.
- The spawn area (radius 14 columns) is warmed when the generator is created.

## Command

- `/viridium bench [radius=4] [cubesDown=8] [cubesUp=8]`: generates a block of columns and reports plan time
  (including river network builds) and fill time per cube kind.
- `/viridium stats [reset]`: accumulated timings (`GenStats`).
