# Performance Audit (2026-11-07)

Back to [[Worldgen Overview]] · touches [[Worldgen Stages]], [[Rivers Overview]], [[Blocks and Chunks]], [[Caves Overview]].

A JFR profile of the whole pipeline (benchmark + cave tests) split the work into six areas, each audited by a separate agent in its own git worktree, owning its own files (shared files had a single owner; the others used their own thread-locals). The safe proposals were implemented on six branches and merged; the larger ones are listed at the end for a decision.

## What was found and done

| Area | Main cause | Change | Gain |
|---|---|---|---|
| Rivers | `RiverNetBuilder.build` was ~8460 bytes of bytecode, over HotSpot's 8000-byte `HugeMethodLimit`: never JIT-compiled, every whole-grid loop interpreted | split into one method per phase; skip floor samples of depressions that can never hold a lake; chamfer only around the rivers; per-thread basin feature points; valley field sampled once per network per point | network build −60–80 % (valley field 135 → 2 ms, lakes 164 → 30 ms); teleport centre 1.4–2.0 → 0.4–1.0 s on its own |
| Plates | the 5×5 plate neighbourhood, velocities, pair geometry and collision flags recomputed for every point, though they depend only on the cell (shared by 99.6 % of consecutive points) | per-thread plate-cell cache in `GenScratch`; edge segments clipped once per cell; plateau prefilter | plates 2.4 → 1.2 µs per point and per lattice node; relief −35 % |
| Fields and late stages | 64 `sin` + 64 `cos` and 32 hashes per eroded point; biome blocks computed for zero weights; climate tiles of 4096 nodes for a few far probes | polynomial sin/cos (|Δh| ≤ 4e-9, tests identical), jitter cache, biomes skip zero blocks and share sigmoids, 32-node climate tiles | erosion 2.75 → 1.65 µs, biomes 1.4 → 0.8 (land) / 1.2 → 0.33 (ocean), −100–180 ms CPU per teleport |
| Columns and cubes | lithology per block recomputed lattice interpolations, strata picks and noise that are constant per cube, column or zone | per-cube noise tables, lazy blob, strata memo, constant-zone skip, one rock sample per block in surface cubes, prefetcher scan skipped when nothing moved | lithology 0.28 → 0.13 ms per shallow land cube, 0.17 → 0.07 deep |
| Caves | region planning (pits, great entrances) ran the river stage from every 2D evaluation, building networks; 9 region lookups per point | layered planning (pits and entrances decided lazily, only near cubes); last-region memo; aquifer memo per cube; 2D masks reused per column | 12 fewer networks per teleport; cave 2D stage 0.87 → 0.01 µs per point; cave part of a cube −25 % |
| Map and LODs | DH built river networks at every detail level (far LODs needed more than the 64-network cache: rebuilt forever) | no rivers or caves when an LOD column covers ≥ 32 blocks; serial builds on DH threads; detail 0 reuses the game's plans; walk benchmark | DH far rows 50–117 s → ~5 ms; cold map tile 10.8 → 3.3 s |

After merging, a leak showed up: `GenScratch` held a reference to its `PlateStage` (so to a whole `WorldGen` and its caches) in every thread-local scratch, and `GenScratch.NETS` kept up to 9 river networks alive per thread. Fixed: an int stage id, the plate cache allocated on first use, and the network references cleared after each point.

## Results (Java 25, warm unless stated)

| | Before | After |
|---|---|---|
| land / ocean point, all stages | 11.6 / 8.6 µs | 8.2 / 6.0 µs |
| column plan, 1 thread | 2.2 ms | 1.29 ms |
| CPU per new column while flying (plan + cubes) | 6.36 ms | 3.57 ms |
| per-column p99 while flying | 14.3 ms | 5.9 ms |
| teleport: centre / 21×21 view | 1.4 / 2.0 s | 0.25 / 0.7 s |
| river network (warm JIT) | 60–190 ms | ~33 ms |
| cold map tile, level 4 | 10.8 s | 3.3 s |

Regression baselines unchanged: `RiverWaterTest` (30662 columns, max step 2, walls 154, perched 155), `CliffDetailTest` (959 / 385 / 1509 / 2565), `WaterfallTest` (19, 116 blocks, 206 / 191), `CaveTest` (void 13.36 / 13.35 / 11.06 / 12.21 / 7.53 / 9.25 %, same 7 pits, 88 mouths, 16 great entrances).

## Second round (2026-11-07)

Done (proposals 1, 3, 4 and 5 below, plus the lake fix; nothing new adds threading risk):

- **Map first paint without waiting for rivers**: with `GenScratch.riversCachedOnly` the river stage and cave pit planning never build a network; an incomplete tile is shown, not stored, and a complete render replaces it. Cold L4 tile: first paint 0.33 s (provisional), complete 2.9 s (was 3–11 s before anything showed).
- **Channel search index** per network and a **shared basin-membership lattice**: per-point rivers −30–60 % on inland patches, relief grid of a build 36 → 25 ms; identical output.
- **One evaluation per map tile** for every layer (LRU of 16 tiles, ≤ ~55 MB): switching through the 20 other layers of a drawn tile 2.4 s → 0.15 s.
- **Erosion gradient from a per-tile relief lattice** at bpp > 8: warm L4 tiles ~205 → ~115 ms (map only; game output identical).
- **Lakes**: the cap was the lowest point of the basin's whole divide, which is always its own outlet. Now a depression is capped by the lowest of its cells touching the divide (if any), by the water of rivers in or next to it, and by its shore once the broad valleys are carved (without the last two, lakes stood as walls over carved valleys). 0.1 → 3.4 lakes per network (half of them salt flats in arid basins); `LakeWaterTest`: 30 lakes in 8 basins, no walls. `RiverWaterTest` now: 30811 river columns, mean depth 4.00 (rest unchanged).

Open: rivers flowing through a depression keep the carved valley level, so there are no large lakes crossed by a river (it would need the river's water raised to the lake level through it). Karst dolines are not lake-aware and could cut a lake shore.

## Proposals left for a decision

1. **Map tiles without waiting for rivers**: draw provisional tiles from cached networks, build the missing ones at low priority, re-render. First paint of a cold tile ~3 s → ~0.3 s. Cross-area (map, rivers, caves), medium effort.
2. **Shorter dependency waits** for river networks: start relief, flood and lakes while upstream builds run; wait for coastal neighbours only when a delta can form (~37 % of coastal builds skip the chain). Medium effort, threading risk.
3. **Per-net bucket index** for the per-point channel search (−0.3–0.5 µs per land point) and a **shared basin-membership lattice** for the relief grid (−40 % of that phase). Medium effort, identical.
4. **One evaluation per map tile for all layers** (switching layers 70–350 → ~2 ms per tile). Medium, identical.
5. **Map erosion gradient from a per-tile lattice** (warm tile −45 %). Map only; hooks in two areas.
6. **Direct block storage writes / dropping `removeInvalidBlocks`** (~0.1 ms per cube?): measure `GenStats.FILL_CRUST` in game first; an ID-extension mod may change the storage layout.
7. **Cheap "river near here?" query** for cave planning (the deferred pits and entrances still build ~12 networks at cube time).

## Design note found on the way

Of ~15 closed depressions per river network only ~0.7 become lakes: a lake may not rise above the lowest point of its basin's whole divide, which for coastal basins is near sea level. Probably why lakes are rare.
