# Final Climate (stage 8)

Part of [[Worldgen Stages]] · depends on: [[Base Climate]], [[Tectonic Plates]], [[Continents]], [[Relief]] · used
by: Erosion, Valleys, Gorges and Glacial Troughs (glaciation), Cryosphere, Biome Stage, Column Plans (temperature and precipitation per column). Design: Atmosphere, Oceanography.

Code: `stages/ClimateStage.java`, `fields/FastClimateStage.java`, `fields/HeightGrid.java`.

## Wind

Starts from the general circulation of [[Base Climate]] and adds:

1. **Thermal (monsoon) wind**: the land fraction 800 blocks east/west/south/north is sampled. Warm land is low pressure (air flows in from the sea), cold land is high pressure (air flows out): `f = clamp((Tb − 8)/16, −1, 1)·monsoon·0.55`. The vector is rotated by a Coriolis-like angle `0.45·pole`. Stored as `thermal`, and `lE`, `lW` (land east/west) are kept for the currents.
2. **Relief deflection**: the upslope component of the wind is reduced (up to 85 %) on steep relief, so air is deflected along ranges. Stored as `deflect`.

## Ocean currents

Only meaningful at sea and on coasts. Land is probed at 2000 and 3600 blocks east/west (scaled with `plateSize`):

- Subtropics (≈ 12°–40° equivalent latitude): **cold current and upwelling on the west coasts** of continents (California, Peru, Namibia) and warm current on the east coasts (Florida, Japan, Brazil).
- Subpolar band (≈ 44°–68°): reversed.

Outputs: `sst` (sea surface temperature anomaly), `upwell`, `curX/curY` (for the map).

## Temperature

```
T = Tb − lapse·max(0, h0) + sst·(1 at sea, 0.7 on land)          lapse = 0.07 °C per block
```

## Precipitation

At sea `P = Pb`. On land:

- **Moisture**: the wind is followed upwind at 150, 400, 900 and 1800 blocks. The more land it crossed (`upLand`), the drier it arrives; points far inland (`L` large) are drier too.
- **Rain shadow**: if the upwind relief is more than 25 blocks above the point, up to −75 %.
- **Orographic lift**: if the point is higher than the nearest upwind sample, up to +90 %.
- **Currents**: cold current coasts get almost no rain (Atacama, Namib); warm current coasts get up to +35 %.
- Floor of 15 mm/year.

## Fast fields

The stage needs 12 height samples around each point. `FastClimateStage` (default, `fastFields = true`) reads them from a cached `HeightGrid` lattice (32-block spacing, bilinear) instead of evaluating plates + continents + relief each time. At map zoom levels coarser than 128 blocks/pixel it falls back to direct sampling, because a lattice tile would be computed for a handful of pixels.
