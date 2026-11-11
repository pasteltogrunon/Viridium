# Cube Filling and Surface Rules

Part of [[Blocks and Chunks]] · depends on: [[Column Plans]], [[3D Crust]], [[Worldgen Stages|Biome Stage]] · used by:
Cubic Chunks Integration, Flora.

Code: `cube/CubeFiller.java`, `cube/SurfaceRules.java`.

## Cube classification

`CubeFiller.classify(plan, cubeY)` decides how much work a cube needs:

| Kind | When | Work |
|---|---|---|
| `AIR` | above `maxTopY` | nothing (no storage), unless Flora draws into it |
| `BEDROCK` | below `MIN_Y` | all bedrock |
| `CRUST` | entirely more than 10 blocks below the lowest surface | every block from `CrustModel` |
| `MIXED` | crosses the surface | surface rules, water, ice, snow, then flora |

A per-thread `CrustModel.Sampler` caches the 3D noise lattice values for the cube, so each block costs no hashing.

## Column fill (MIXED cubes)

For each Y of a block column:

- `y ≤ surfaceY`: the surface rule's top block at depth 0, its filler down to `depth`, then the crust lithology;
- inside the ice body: ice (packed ice for shelves and icebergs);
- `y ≤ waterY`: water;
- one snow layer on top when the rules allow it and there is no water or ice.

## Surface rules

The slope is the gradient of the final terrain of the column (`ColumnPlanner.terrainSlope`: central differences of the planned heights, one-sided on the chunk's border), so gully walls, crests and canyon sides show rock. Before, it was the smoothed base relief's slope, which misses every gully. (A single planned point, for the Distant Horizons LODs, keeps the smoothed slope.)

Chosen once per column from biome, water, slope and temperature. Rules never look at neighbouring columns or cubes, so
any cube can be painted on its own.

- Under water: gravel in rivers, clay/sand in lakes, clay or gravel in deep or cold sea, sand in shallow warm sea.
- Salt flats: evaporite.
- Landform covers (`ColumnPlan.surf`, set by the relief and the rivers) come first and are never bare rock: river terrace risers (coarse dirt over gravel, the treads keep the biome's soil), gravel bars, alluvial fans (gravel over sand where dry), barrier and beach sand; under water reef limestone, gravel and sand. See [[Landforms Overview|Coast Landforms]] and [[Rivers Overview|River Landforms]].
- Cliffs (`slope > 1.1`, except beaches and deserts): bare rock (the crust shows through).
- Per biome: sand on beaches and deserts, red sand over banded hardened clay in badlands, packed ice on glaciers, snow
  on peaks and slopes, podzol patches in taiga, coarse dirt in savanna, grass + dirt elsewhere, with varying depth.
- Steep ground (`slope > 0.7`): only one block of soil.
- Snow layers only where `T < −0.5`, and only on an opaque non-ice block (a snow layer on ice breaks and cascades).

## Cliffs and waterfalls

After the column fill, `cliff(...)` applies the [[Landforms Overview|Cliff Detail]] of the column: notches (air, or water below the level of a wet foot; never under loose soil), talus (cobblestone, gravel or mossy cobblestone on top) and curtains of falling water. A cube that a notch or talus reaches is filled even if it would be crust-only or air. `ColumnPlan.fallTop` adds the curtain of a waterfall (`flowing_water` meta 8, ice when frozen) above the water of the column.

## Caves

`CaveCarver.prepare` is called for every crust and surface cube; for each solid block `Cube.at(lx, ly, lz, rock)` decides rock, air, water or lava (nothing under loose soil). Crust-only cubes cost ~0.25 ms instead of ~0.15. See [[Caves Overview]].
