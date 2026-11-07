# Aquifers and Lava

Part of [[Caves Overview]] · used by [[Noise Caves]] and the galleries of [[Cave Systems]].

Code: `CaveCarver.Cube.fluid`, `CaveCarver.aquifer`, `CaveCarver.barrier`.

## In reality

Below the water table the rock is saturated and caves are flooded (phreatic); above it they are dry (vadose). The table follows the terrain loosely and is broken up by impermeable layers, so neighbouring cave systems can sit at different water levels, separated by rock. Lava does not fill caves in the real crust; it is a game convention for depth.

## Model

Each carved block asks the **aquifer cell** it lies in (96×48×96 blocks):

| Kind | Probability | Level |
|---|---|---|
| lava | `0.3·ss(−700, −1500, bottom)` (none above −700) | bottom + 15..85 % of the cell |
| water | 0.38 (0.19 near the surface) × `aquifers` | bottom + 15..85 % of the cell |
| dry | the rest | — |

- A block at or below the level is water (or lava), above it air.
- **Barriers**: within 2 blocks of a cell boundary, if the neighbouring cell has another kind or level and the block lies at or below the higher of the two levels, the block stays rock. Caves crossing into a cell with another water level are sealed, so water never stands as a wall next to air (`CaveTest` counts water blocks next to air: 0). Vertically this makes a floor under a flooded cell above a dry one.
- **Cap**: water never rises above the lowest coarse ground of its cell (9 samples) minus 24 blocks. It is a per-cell constant: an earlier per-column rule (dry within 12 blocks of the ground) put water next to air where neighbouring columns had different heights.
- **Lava sea**: everything carved in the last 48 blocks of the world (y < −2000) is lava.
- **Safety layer**: no cave within 8 blocks under a column holding water (river, lake, sea) or ice.
- Planned chambers carry their own lake (see [[Cave Systems]]) and win over the aquifer where shapes overlap; galleries use the aquifer (they can end in a flooded sump).

## Results

Water is 14–24 % of the void at every depth; lava appears below ~−700 and reaches ~21 % of the void at the bottom.
