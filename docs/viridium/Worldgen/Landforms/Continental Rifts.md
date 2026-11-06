# Continental Rifts

Part of [[Landforms Overview]] · stages: [[Worldgen Stages|Continents]] (geometry, narrow seas), [[Worldgen Stages|Relief]] (graben, shoulders, volcanoes).

Code: `ContinentStage.rift`, `ReliefStage.rift`.

## In reality

A continent starts to split: the crust stretches and thins, and a **graben** sinks between two normal faults while the **shoulders** on both sides rise. Rifts are 30–60 km wide and hundreds to thousands of km long. They hold volcanoes (Kilimanjaro), long deep lakes (Tanganyika, Malawi, Baikal) and floors below sea level (Danakil). Then:

- if it keeps opening, the sea floods it: a narrow sea (Red Sea, Gulf of California), later an ocean;
- if it stops, it stays as a **failed rift** (Rhine graben, the Midcontinent rift), worn down and filled with sediment.

Before this, the plate model only had divergent boundaries in the ocean (ridges). Every non-collisional boundary is ocean, so continents never split.

## Model

### Geometry (`ContinentStage.rift`)

Some continents get one rift line: `rnd(plate) < 0.35·rifts`.

```
line   through the continent centre (± 0.1 S), random direction
u, v   along / across it, in the warped plate coordinates
v     += 0.07·S·fbm(u / 0.35 S)                          meanders over a few thousand blocks
half   = (0.25 + 0.2·rnd)·S                              0.5–0.9 S long
along  = 1 − ss(0.7 half, half, |u|)                      fades at the ends
w      = (0.025 + 0.015·rnd)·S·(0.85 + 0.3·fbm(u/1500))   300–480 half-width (600–960 blocks)
riftD  = |v| / w                                          < 1 inside the graben
type   = young (45 %), failed (25 %), mature (30 %)
```

A **mature** rift lowers the continentality inside the graben to −0.08: it is a narrow sea.

### Relief (`ReliefStage.rift`)

```
wall      = ss(0.88, 0.97, d)      (failed: ss(0.7, 1.15, d), soft walls)
shoulders = U·exp(−((d − 1)/0.6)²)·wall,  U = 40..85 (failed 20)
floor     = young:  0.4·h + 10 + 32·var    sills and basins along the floor
            failed: 0.6·h + 4 + 8·var
            mature: −45 + 15·var           a sea
h         = lerp(h + shoulders, min(h + shoulders, floor), (1 − wall)·along)
```

- `var = fbm(u / 2200)` makes sills and basins along the floor. The priority flood of the river network turns the closed basins into lakes (rift lakes), and rivers run along the floor.
- The faults are ~0.09 half-widths wide: 80–90 blocks of wall in ~16 blocks. `scarp` is set along them, so erosion leaves them alone and waterfalls can form.
- **Volcanoes** on the floor and shoulders of young and mature rifts: ridged cones in patches (`fbm(/1400)`), 60–150 blocks high, with `volc` and `volcType = 1`, so the geology gives andesite, tuff and basalt.
- Fields: `GenPoint.rift`, `riftD`, `riftU`, `riftType`.

## Results

Example (young rift across an arid continent) near x −18250, z −2420: plains at 40–55, shoulders up to 97–110, a flat floor at 18, walls of 80–90 blocks.

## Not done

The crust has no rift province yet (thinned crust, rift sediments, dikes): the 3D crust uses the existing provinces there.
