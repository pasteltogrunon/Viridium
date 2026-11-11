# Performance Audit (2026-11-07)

Back to [[Worldgen Overview]] ÃÂ· touches [[Worldgen Stages]], [[Rivers Overview]], [[Blocks and Chunks]], [[Caves Overview]].

A JFR profile of the whole pipeline (benchmark + cave tests) split the work into six areas, each audited by a separate agent in its own git worktree, owning its own files (shared files had a single owner; the others used their own thread-locals). The safe proposals were implemented on six branches and merged; the larger ones are listed at the end for a decision.

## What was found and done

| Area | Main cause | Change | Gain |
|---|---|---|---|
| Rivers | `RiverNetBuilder.build` was ~8460 bytes of bytecode, over HotSpot's 8000-byte `HugeMethodLimit`: never JIT-compiled, every whole-grid loop interpreted | split into one method per phase; skip floor samples of depressions that can never hold a lake; chamfer only around the rivers; per-thread basin feature points; valley field sampled once per network per point | network build Ã¢ÂÂ60Ã¢ÂÂ80 % (valley field 135 Ã¢ÂÂ 2 ms, lakes 164 Ã¢ÂÂ 30 ms); teleport centre 1.4Ã¢ÂÂ2.0 Ã¢ÂÂ 0.4Ã¢ÂÂ1.0 s on its own |
| Plates | the 5ÃÂ5 plate neighbourhood, velocities, pair geometry and collision flags recomputed for every point, though they depend only on the cell (shared by 99.6 % of consecutive points) | per-thread plate-cell cache in `GenScratch`; edge segments clipped once per cell; plateau prefilter | plates 2.4 Ã¢ÂÂ 1.2 ÃÂµs per point and per lattice node; relief Ã¢ÂÂ35 % |
| Fields and late stages | 64 `sin` + 64 `cos` and 32 hashes per eroded point; biome blocks computed for zero weights; climate tiles of 4096 nodes for a few far probes | polynomial sin/cos (|ÃÂh| Ã¢ÂÂ¤ 4e-9, tests identical), jitter cache, biomes skip zero blocks and share sigmoids, 32-node climate tiles | erosion 2.75 Ã¢ÂÂ 1.65 ÃÂµs, biomes 1.4 Ã¢ÂÂ 0.8 (land) / 1.2 Ã¢ÂÂ 0.33 (ocean), Ã¢ÂÂ100Ã¢ÂÂ180 ms CPU per teleport |
| Columns and cubes | lithology per block recomputed lattice interpolations, strata picks and noise that are constant per cube, column or zone | per-cube noise tables, lazy blob, strata memo, constant-zone skip, one rock sample per block in surface cubes, prefetcher scan skipped when nothing moved | lithology 0.28 Ã¢ÂÂ 0.13 ms per shallow land cube, 0.17 Ã¢ÂÂ 0.07 deep |
| Caves | region planning (pits, great entrances) ran the river stage from every 2D evaluation, building networks; 9 region lookups per point | layered planning (pits and entrances decided lazily, only near cubes); last-region memo; aquifer memo per cube; 2D masks reused per column | 12 fewer networks per teleport; cave 2D stage 0.87 Ã¢ÂÂ 0.01 ÃÂµs per point; cave part of a cube Ã¢ÂÂ25 % |
| Map and LODs | DH built river networks at every detail level (far LODs needed more than the 64-network cache: rebuilt forever) | no rivers or caves when an LOD column covers Ã¢ÂÂ¥ 32 blocks; serial builds on DH threads; detail 0 reuses the game's plans; walk benchmark | DH far rows 50Ã¢ÂÂ117 s Ã¢ÂÂ ~5 ms; cold map tile 10.8 Ã¢ÂÂ 3.3 s |

After merging, a leak showed up: `GenScratch` held a reference to its `PlateStage` (so to a whole `WorldGen` and its caches) in every thread-local scratch, and `GenScratch.NETS` kept up to 9 river networks alive per thread. Fixed: an int stage id, the plate cache allocated on first use, and the network references cleared after each point.

## Results (Java 25, warm unless stated)

| | Before | After |
|---|---|---|
| land / ocean point, all stages | 11.6 / 8.6 ÃÂµs | 8.2 / 6.0 ÃÂµs |
| column plan, 1 thread | 2.2 ms | 1.29 ms |
| CPU per new column while flying (plan + cubes) | 6.36 ms | 3.57 ms |
| per-column p99 while flying | 14.3 ms | 5.9 ms |
| teleport: centre / 21ÃÂ21 view | 1.4 / 2.0 s | 0.25 / 0.7 s |
| river network (warm JIT) | 60Ã¢ÂÂ190 ms | ~33 ms |
| cold map tile, level 4 | 10.8 s | 3.3 s |

Regression baselines unchanged: `RiverWaterTest` (30662 columns, max step 2, walls 154, perched 155), `CliffDetailTest` (959 / 385 / 1509 / 2565), `WaterfallTest` (19, 116 blocks, 206 / 191), `CaveTest` (void 13.36 / 13.35 / 11.06 / 12.21 / 7.53 / 9.25 %, same 7 pits, 88 mouths, 16 great entrances).

## Second round (2026-11-07)

Done (proposals 1, 3, 4 and 5 below, plus the lake fix; nothing new adds threading risk):

- **Map first paint without waiting for rivers**: with `GenScratch.riversCachedOnly` the river stage and cave pit planning never build a network; an incomplete tile is shown, not stored, and a complete render replaces it. Cold L4 tile: first paint 0.33 s (provisional), complete 2.9 s (was 3Ã¢ÂÂ11 s before anything showed).
- **Channel search index** per network and a **shared basin-membership lattice**: per-point rivers Ã¢ÂÂ30Ã¢ÂÂ60 % on inland patches, relief grid of a build 36 Ã¢ÂÂ 25 ms; identical output.
- **One evaluation per map tile** for every layer (LRU of 16 tiles, Ã¢ÂÂ¤ ~55 MB): switching through the 20 other layers of a drawn tile 2.4 s Ã¢ÂÂ 0.15 s.
- **Erosion gradient from a per-tile relief lattice** at bpp > 8: warm L4 tiles ~205 Ã¢ÂÂ ~115 ms (map only; game output identical).
- **Lakes**: the cap was the lowest point of the basin's whole divide, which is always its own outlet. Now a depression is capped by the lowest of its cells touching the divide (if any), by the water of rivers in or next to it, and by its shore once the broad valleys are carved (without the last two, lakes stood as walls over carved valleys). 0.1 Ã¢ÂÂ 3.4 lakes per network (half of them salt flats in arid basins); `LakeWaterTest`: 30 lakes in 8 basins, no walls. `RiverWaterTest` now: 30811 river columns, mean depth 4.00 (rest unchanged).

Then **river lakes**: a river crossing a depression fills it to its outlet (partly in arid basins), the river enters and leaves it, upstream water stays at least at lake level, and the rim closes around in-lake channels; dolines skip lake shores. 0 Ã¢ÂÂ 50 river lakes in a 42-network probe (largest 133 cells of 32ÃÂ32); `LakeWaterTest` 32 lakes (13 river lakes, largest 379 cells), walls 31 of 368k lake columns, all 1 block. Not near coasts or in glacial troughs. Details in [[Rivers Overview|Lakes and Salt Flats]].

## River and coast landforms, climate erosion (2026-11-11)

`TerrainBenchmarkTest`, fast fields, nets warm, same machine: land 7.39 â 8.20 Âµs per point, ocean 5.11 â 6.19 (the coast landforms measure the distance to the coast with three plate evaluations, only in detailed evaluations near coasts), one column on one thread 1.23 â 1.39 ms, river network build 43 â 46 ms. `LakeWaterTest` now 22 lakes (12 river lakes): the deeper gullies change the coarse depressions; walls 23, all 1 block. See [[Erosion]], [[Rivers Overview|River Landforms]], [[Landforms Overview|Coast Landforms]].

## Volcanoes, deserts, subtropical belts (2026-11-11)

Same benchmark, against the commit before: land 8.24 → 9.89 µs per point (relief +0.6: stratovolcano and volcanic-field cells, inselbergs, tepuis; erosion +0.5: deserts), ocean 5.95 → 6.34, one column on one thread 1.36 → 1.46 ms. The gully depth is now capped at 55 blocks: on very steep coasts it reached 80+ and cut below the river beside it (a 5-block step in `RiverWaterTest`); max step 2 again. See [[Volcanism]], [[Landforms Overview|Deserts]], [[Base Climate]].

## Proposals left for a decision

1. **Map tiles without waiting for rivers**: draw provisional tiles from cached networks, build the missing ones at low priority, re-render. First paint of a cold tile ~3 s Ã¢ÂÂ ~0.3 s. Cross-area (map, rivers, caves), medium effort.
2. **Shorter dependency waits** for river networks: start relief, flood and lakes while upstream builds run; wait for coastal neighbours only when a delta can form (~37 % of coastal builds skip the chain). Medium effort, threading risk.
3. **Per-net bucket index** for the per-point channel search (Ã¢ÂÂ0.3Ã¢ÂÂ0.5 ÃÂµs per land point) and a **shared basin-membership lattice** for the relief grid (Ã¢ÂÂ40 % of that phase). Medium effort, identical.
4. **One evaluation per map tile for all layers** (switching layers 70Ã¢ÂÂ350 Ã¢ÂÂ ~2 ms per tile). Medium, identical.
5. **Map erosion gradient from a per-tile lattice** (warm tile Ã¢ÂÂ45 %). Map only; hooks in two areas.
6. **Direct block storage writes / dropping `removeInvalidBlocks`** (~0.1 ms per cube?): measure `GenStats.FILL_CRUST` in game first; an ID-extension mod may change the storage layout.
7. **Cheap "river near here?" query** for cave planning (the deferred pits and entrances still build ~12 networks at cube time).

## Design note found on the way

Of ~15 closed depressions per river network only ~0.7 become lakes: a lake may not rise above the lowest point of its basin's whole divide, which for coastal basins is near sea level. Probably why lakes are rare.
