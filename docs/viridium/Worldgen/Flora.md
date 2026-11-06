# Flora

Part of [[Blocks and Chunks]] · depends on: [[Column Plans]], [[Cube Filling and Surface Rules]], [[Worldgen Stages|Biome Stage]] ·
used by: Cubic Chunks Integration. Design: Ecology.

Code: `cube/flora/FloraPlanner.java`, `Feature.java`, `FeatureType.java`, `BlockSink.java`.

Vegetation is **planned per column and drawn into cubes as they are generated**, instead of the usual world-based
population. No `setBlock`, no neighbour cubes generated, no light updates: the blocks are simply part of the cube when
it is created. `ViridiumWorldGenerator.populate` only marks the cube as populated.

## Planning

`features(chunkX, chunkZ)` (cached, 4096 columns) uses a `Random` seeded by the world seed and the chunk position.
The biome at the column centre decides:

- tree count per column (`treeDensity`: 12 in tropical rainforest, 8 in temperate forest, 0.3 on grassland...) and
  tree types (jungle, spruce/pine, swamp oak, acacia, palms on warm beaches, pedunculate oak, chestnut, birch, oak);
- tall grass / ferns, flowers, cacti and dead bushes.

Each feature is only placed if the ground block (from the surface rules) suits it: no water or ice above, grass or dirt
for trees, sand for palms and cacti.

## Drawing

`draw(plan, cx, cy, cz, storage)` draws every feature of the 3×3 surrounding columns that intersects the cube. A
feature's shape depends only on its own seed, and `BlockSink` clips it to the cube, so the same tree comes out identical
whichever cube draws which part. The largest feature reach is 7 blocks horizontally and 32 vertically, so 3×3 columns
always cover it. Leaves stay within 4 blocks of a log so they decay normally.

## Boulders and talus

`FloraPlanner.setCliffs` gives the planner the [[Landforms Overview|Cliff Detail]]: nothing grows on talus, and `BOULDER` features (rough ellipsoids of the local rock, half buried) fall within 10 blocks of the foot of walls. Boulders draw through `FloraSink.rock`, which also replaces water.
