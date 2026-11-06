# Relief (stage 7)

Part of [[Worldgen Stages]] · depends on: [[Tectonic Plates]], [[Continents]], [[Volcanism]] · used by:
Surface Geology, Final Climate, Erosion, every river page (the river networks are computed on this base height, see River Network Build), 3D Crust.

Code: `stages/ReliefStage.java` (`baseHeight`).

Produces the **base height** `h0` (blocks relative to sea level), before erosion and rivers. `baseHeight(x, z, o, detail, s)` is also called on its own by other stages at other points (through `WorldGen.heightAt`); `detail = false` drops the finest octaves.

## Land (L ≥ 0)

```
plains = 40·ss(0, 0.55, L)                                   rises inland up to 40 blocks
hills  = (fbm(/380)·0.5 + 0.5)·26·ss(0, 0.1, L)
mask   = clamp(ss(0, 0.4, fbm(/2600)) + 1.1·orog, 0, 1)·ss(0, 0.16, L)
mtn    = ridged(/760)²·290·mask·mountainStrength
h      = plains + hills + mtn
```

`mtnMask` is stored: mountains appear where a low-frequency noise allows them **or** near convergent plate boundaries (`orog`).

## Sea (L < 0)

```
floor = −62 + 40·ridge − 30·trench + 5·fbm(/600)  (+ ridged texture on ridges)
h     = −16·ss(0, −0.06, L) + (floor + 16)·ss(−0.06, −0.3, L)
```

i.e. continental **shelf** (≈ −16) → **slope** → **abyssal plain** (≈ −62), with mid-ocean ridges raised and trenches deepened.

## Continental collision

Where `collK > 0` (see [[Tectonic Plates]]), using the signed distance to the suture `collS`:

- a **range on the suture**: Gaussian of width `0.07·S`, height `120 + 360·ridged²` (Himalaya);
- a **plateau** on the overriding plate, from just behind the suture to `0.17–0.3·S` inland, ≈ 105 blocks (Tibet);
- a shallow **foreland** depression on the other side (−12).

`collR` (collision relief share) is stored for Surface Geology and the 3D Crust.

## Volcanic arcs

Where `arc > 0.02`: islands at sea and volcanoes on the coast, `−75 + 150·isl + 70·isl·cone²`. Applied as a maximum, independently of `L`, so arcs are continuous from sea to land. Sets `volc` and `volcType = 1`.

## Hotspots

Finally `VolcanismStage.apply` raises the height with nearby hotspot volcanoes. See [[Volcanism]].
