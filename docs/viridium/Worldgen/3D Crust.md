# 3D Crust

Part of [[Blocks and Chunks]] · depends on: [[Worldgen Stages|Tectonic Plates]], [[Worldgen Stages|Continents]], [[Worldgen Stages|Relief]], [[Worldgen Stages|Volcanism]],
[[Worldgen Stages|Surface Geology]], [[Worldgen Stages|Base Climate]] (arid / warm flags), [[Column Plans]] (surface Y) · used by:
Cube Filling and Surface Rules. Design: Geology.

Code: `crust/CrustModel.java`, `CrustPlan.java`, `Province.java`, `Lithology.java`, `LatticeNoise3.java`;
`cube/LithologyBlocks.java`.

The crust is **not** a pipeline stage. It works in two steps:

1. `planColumn` runs once per column (from [[Column Plans]]) and stores a vertical **profile** in absolute Y in the
   chunk's `CrustPlan`.
2. `lithology(x, y, z)` is called for every underground block by the cube filler and reads that profile plus two smooth 3D noises.

Scale: 1 block ≈ 25 m vertically, so a normal continental crust is ≈ 1300 blocks thick and mostly fits in the world (Y ≥ −2048). Under the oceans the mantle is reachable.

## Province

Each column gets one of the provinces of the design doc, decided from the 2D pipeline:

| Province | Chosen when |
|---|---|
| `VOLCANIC_ISLAND` | hotspot volcano (`volcType ≥ 2`) |
| `OCEANIC` | `L < −0.12` |
| `OROGEN` | collision (`collR > 0.3`), slate or marble at the surface, or strong `orog` with mountains |
| `ARC` | volcanic arc, andesite or tuff |
| `MARGIN` | `L < 0.06` (passive margin / shelf) |
| `SHIELD` | granite, diorite or gneiss at the surface |
| `LIP` | basalt at the surface (large igneous province) |
| `BASIN` | low (`h < 25`) and wet (`Pb > 900`) |
| `PLATFORM` | everything else |

## Vertical profile

From top to bottom:

```
surface
  cover            sedimentary / volcanic strata (province palette)
basementTop
  upper crust      crystalline basement (granites, granodiorite, gneiss...), with plutons
midTop
  middle crust     metamorphic grade rises with depth: (green/mica)schist → gneiss → amphibolite → migmatite
lowerTop
  lower crust      amphibolite → gabbro (eclogite lens at the bottom of collision roots)
moho
  mantle           peridotite (serpentinite under ridges, chromitite pods)
```

Oceanic columns use the **ophiolite sequence** instead: pelagic sediment → basalt → sheeted dolerite dikes → gabbro →
mantle.

- **Moho depth**: continental `SEA − (1250 + 500·orog + 800·collR + 250·mtnMask)` (isostatic root under mountains),
  oceanic `≈ 340` blocks below the sea floor (thinner on ridges), blended across the margin by `L`.
- **Cover thickness** depends on the province: almost none on shields, 40–90 blocks in orogens, 380–530 blocks of
  sediment in basins, etc.
- Every interface is perturbed by ±10 blocks of smooth 3D noise (`DISPLACEMENT`).

## Strata

Cover units are `strataT` blocks thick (5–12, 7 in orogens) and placed in **absolute Y**, so hills and valleys cut through them. Each unit picks a lithology from the province palette with a per-region random. Strata are gently dipping in platforms and **folded** in orogens (sinusoidal folds along a random strike, amplitude up to 65 blocks).

The unit right under the surface is biased towards the 2D [[Worldgen Stages|Surface Geology]] rock (×6 weight at the surface, fading over 60 blocks), so the crust agrees with the map. Evaporite only appears in arid columns and chalk in warm ones.

## 3D bodies

- **Plutons** (granite, diorite... ) in the upper/middle crust of continental provinces, with pegmatite rims: blobs of
  a 48-block 3D noise.
- **Dolerite dikes**: straight vertical dikes ~3–6 blocks wide and 840 long, one chance per 512-block cell, outside
  orogens.
- **Kimberlite pipes**: carrot-shaped pipes widening upwards, in old stable interiors (shield / platform, `L > 0.3`).
- **Chromitite pods** near the top of the mantle; **blueschist** near trenches and arcs.

## Blocks

`Lithology.block` names a field of `ViriBlocks`; `LithologyBlocks` resolves them by reflection and falls back to stone (with a warning) for rocks that have no block yet.
