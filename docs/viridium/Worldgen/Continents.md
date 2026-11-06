# Continents (stage 5)

Part of [[Worldgen Stages]] · depends on: [[Tectonic Plates]] · used by: Relief, Drainage Basins,
Final Climate, Surface Geology, 3D Crust, and indirectly everything else.

Code: `stages/ContinentStage.java`.

The output is a single number, the **continentality** `L`: `> 0` is land, `< 0` is sea. Its magnitude matters too:
relief rises with `L` on land, the continental shelf and slope follow it at sea, and many later stages use `L` as "how far inland" (runoff, precipitation, river basins).

## One continent per plate

Each plate holds one continent. Its centre is the plate's feature point shifted randomly by up to ±0.15·plateSize.

```
c = edgeNC / (edgeNC + distance to centre)          1 at the centre, 0 at a non-collisional boundary
L = c − (1 − size) + n
```

- `size = continentSize ± 0.15` per plate.
- `n = 0.22·fbm(/1100) + 0.07·fbm(/240)`: coastline noise.
- `edgeNC` (see [[Tectonic Plates]]) ignores collision boundaries, so a continent extends right up to a collision and welds with its neighbour there.
- With `exactPlateEdges`, `edgeNC` is bent by a low-frequency noise (±0.04·plateSize), because the boundary itself is straight for thousands of blocks and coasts parallel to it looked artificial.

## Additions

- **Microcontinents** (chance `microChance` = 0.35 per plate): a fragment of radius 6–12 % of the plate size, at 30–43 % of the plate size from the centre (Madagascar, Zealandia). Taken as `max(L, Lm)`.
- **Shelf islands** (`islands`): positive fBm bumps added only where `L` is between about −0.5 and 0.12, i.e. on the continental shelf (Great Britain, Japan).

## Guarantees at the boundaries

- `L ≤ edge / (0.12·plateSize) − 0.5`: there is **always ocean next to a plate boundary** (ridges and trenches are underwater).
- Except at collisions: where `collZ > 0`, `L` is blended towards `0.4 + 0.8·n`, so two continents fuse across the suture (Himalaya-like ranges sit on land).
