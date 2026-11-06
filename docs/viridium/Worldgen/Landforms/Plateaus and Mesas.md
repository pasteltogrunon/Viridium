# Plateaus and Mesas

Part of [[Landforms Overview]] · stages: [[Worldgen Stages|Relief]] (plateau), [[Worldgen Stages|Erosion]] (mesas), [[Worldgen Stages|Surface Geology]] (basalt).

Code: `ReliefStage.plateau`, `ReliefStage.plateauTop`, `ErosionStage.mesas`, `GeologyStage.rockFor`.

## In reality

- **Uplifted tablelands** (Iberian Meseta, Colorado Plateau, Ethiopian highlands): a block of shield or platform lifted without folding. The top is nearly flat at 600–1500 m, an escarpment surrounds it, and rivers cut it into canyons.
- **Basalt plateaus** (Deccan traps, Columbia River): flood basalts piled flow on flow. Each flow is a ledge, so the edge climbs in steps ("traps" means stairs).
- **Collision plateaus** (Tibet) are a different thing and already exist in [[Worldgen Stages|Relief]] (the plateau on the overriding plate).
- **Mesas, buttes, ledges** (Monument Valley): in arid land on flat-lying sedimentary rock, a hard bed protects the softer ones below. Slopes retreat as cliffs separated by benches.

## Model

### Plateau mask and step

```
inland = ss(0.02, 0.2, L)·(1 − ss(0.05, 0.3, collZ))
m      = fbm(/9000) + 0.22·fbm(/1700) + 0.06·fbm(/300) − 0.3·(1 − inland)
thr    = 0.16 − 0.12·(plateaus − 1)
k      = ss(thr − 0.002, thr + 0.002, m)                          tableland: one narrow step
k      = (⌊q⌋ + ss(0.72, 1, frac q))/3,  q = 3·ss(thr ± 0.03, m)   basalt: three ledges
k      = 0.94·k + 0.06·ss(thr − 0.04, thr, m)                     a gentle footslope below
h      = plains + hills·(1 − 0.65k) + mtn·(1 − 0.85k) + k·top,  top = 60..150 blocks
```

- The large-scale noise decides where plateaus are; the 1700- and 300-block octaves make the edge ragged (spurs, embayments). Its gradient (~5·10⁻⁴ per block) turns the ±0.002 band into an escarpment ~8 blocks wide. In practice that is 100+ blocks of drop over 15–20 blocks.
- **Inland shift**: near the coast and collisions the mask is lowered instead of the height scaled, so the plateau ends inland with its full scarp. Scaling the height made basalt plateaus ramp down into the sea over 650 blocks.
- Inside, hills and mountains are damped: the top is flat.
- `basalt` = `fbm(/20000) > 0.2`. [[Worldgen Stages|Surface Geology]] then gives basalt (88 %) or tuff to the plateau (`plat > 0.3`), and the 3D crust makes it a large igneous province.
- The noise does not depend on `detail`: the coarse lattices and the river grid see the edge where the columns have it.
- Fields: `GenPoint.plat` (share), `platType` (0 tableland, 1 basalt), `scarp` (nearness to the scarp).

### Escarpment and erosion

The erosion gradient comes from an 8-block lattice. Next to a 100-block step it is smeared over ~20 blocks and dug a 6-block moat along the foot. Erosion is now multiplied by `1 − scarp` (scarp = 1 within ~25 blocks of the edge).

### Canyons

The plateau is in the base height, so the river network sees it. Rivers crossing an escarpment either fall over it ([[Waterfalls]]) or, where no fall is found, cut back into it as the drop limit lowers their water upstream (a canyon). The flat-lying strata of the crust show in the canyon walls.

### Mesas (arid terracing)

```
arid = ss(520, 260, P)·ss(−2, 6, T)
sed  = sandstone 1, limestone 0.8, shale 0.5 (weighted by the two rocks)
w    = mesas·arid·sed·ss(0.12, 0.35, slope)·ss(4, 18, h0)
h   += w·((⌊h/12⌋ + ss(0.78, 0.96, frac))·12 − h)
```

12-block steps with short steep risers, only on slopes (flats stay flat). `GenPoint.mesa` stores `w`. Combined with the badlands clay bands of the surface rules it gives the stepped arid slopes and plateau edges of the Colorado Plateau.

## Results (seed 12345)

- Plateaus cover a few percent of the land, mostly in continental interiors, some of them basaltic.
- Example escarpment at x 2700–2740, z 6250: from 30 to 140 blocks in ~20 blocks.
- Cost: unchanged per point (~0.1 µs).
