# Base Climate (stages 1-2)

Part of [[Worldgen Stages]] · depends on: nothing (first stage) · used by: Final Climate, Drainage Basins, River Network Build (runoff), Volcanism (warm water), Valleys, Gorges and Glacial Troughs (glaciation), Cryosphere.

Code: `stages/ClimateBaseStage.java`.

The world has no real latitude; climate is **periodic**. Temperature cycles along z (equator → pole → equator) and precipitation along x, so travelling in a straight line crosses every climate.

## Temperature

```
ct = cos(2π·z / tempPeriod)                       tempPeriod = 48000
Tb = 2 + 28·ct + 3.5·fbm(x/3000, z/3000)          → ≈ −26 °C .. +30 °C
```

From `ct` the stage also derives:

- `lat = acos(ct) / 2`: an **equivalent latitude** in radians (0 at the warm line, π/2 at the cold line). Used by the wind belts and by the ocean currents in Final Climate.
- `pole = sin / (|sin| + 0.08)`: ≈ ±1, the direction towards the pole (which "hemisphere" the point is in).

## Precipitation

```
Pb = max(40, 1250 + 1100·cos(2π·x / precipPeriod) + 300·fbm(x/2500, z/2500))   mm/year, precipPeriod = 36000
```

This is only the large-scale moisture supply. Final Climate turns it into the real precipitation (distance to the sea, rain shadow, orographic lift, currents).

## General circulation wind

Three wind belts per half-cycle, as on Earth (trade winds, westerlies, polar easterlies), from `sin(6·lat)`:

```
windX = −sin(6·lat) + 0.2·fbm(...)
windY = −0.45·sin(6·lat)·pole + 0.3·fbm(...)
```

The final wind (`wfx, wfy`) starts equal to it and is modified by Final Climate. `T` and `P` also start equal to `Tb` and `Pb`.

## Latitude in the moisture supply (`hadley`, Java-only)

The prototype's precipitation only varies along x. With `hadley = 1` (0 in `ParityTest`) `Pb` is multiplied by a profile of the equivalent latitude (`ClimateBaseStage.hadley`):

```
1 + 0.3·exp(−(lat/9)²) − 0.6·exp(−((lat − 26)/8)²) + 0.12·exp(−((lat − 52)/12)²) − 0.35·ss(65, 85, lat)     (degrees)
```

Rising air over the equator (+30 %), the sinking air of the Hadley cells around 26° (−60 %: the belt of the great hot deserts), the westerlies' storms around 52° (+12 %), dry polar air (−35 %). Continentality, rain shadows and cold currents ([[Final Climate]]) still shape the deserts inside each belt.
