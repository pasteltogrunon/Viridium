# Deserts

Part of [[Landforms Overview]] · stages: [[Worldgen Stages|Relief]] (inselbergs, tepuis), [[Worldgen Stages|Erosion]] (dunes, yardangs, hoodoos, desert pavement) · the dry belts come from [[Worldgen Stages|Base Climate]] (Hadley cells) and [[Worldgen Stages|Final Climate]] (continentality, rain shadows, cold currents).

Code: `ErosionStage.deserts`, `barchans`, `starDunes`, `cellWind`; `ReliefStage.inselbergs`, `tepuis`; `SurfaceRules` (`SURF_DUNE`, `SURF_REG`).

Parameters (`GenParams`, Java-only, `0` turns one off, all off in `ParityTest`): `dunes`, `yardangs`, `hoodoos`, `desertPavement`, `inselbergs`, `tepuis`.

Most of a real desert is not sand: gravel plains (reg), bare rock plateaus (hamada), mountains, dry valleys. The sand gathers in a few great sand seas (ergs), where the dune shapes are set by the wind and the amount of sand.

## Where

`arid = ss(330, 140, P)·ss(−6, 2, T)` (hot and cold deserts), on land above sea level, after the erosion (they need the final climate and wind).

## Dune seas (`dunes`)

- **Erg**: a regional noise (`fbm /6500`) on flat, low, dry ground (`slope < 0.06..0.22`, `h0 < 90..160`), not on the mesas. Its core holds the most sand (`supply`).
- **Wind frame**: the dunes are laid out along the wind. A direction that turned from point to point would twist the pattern around the world's origin, so each 3 km cell takes the general circulation wind at its centre (base climate) and the four cells around a point are blended.
- **Types** by a regional "wind regime" noise, blended at the borders:

| Type | Real-world setting | Shape |
|---|---|---|
| transverse ridges | one steady wind, plenty of sand | ridges across the wind every 55 blocks, gentle windward slope, slip face at the angle of repose, sinuous crests; 4-10 high |
| barchans | one steady wind, little sand | crescents 20-44 across, horns downwind, on a gravel floor; one per 90-block cell (60 %) |
| linear (seif) | two seasonal winds (Namib, Simpson) | sharp ridges along the wind every 110 blocks, Y junctions; 9-19 high |
| star | changing winds (Grand Erg Oriental) | three-armed pyramids 18-35 high, one per 260-block cell |

`SURF_DUNE`: sand, red in some ergs.

## Yardangs (`yardangs`)

Soft rock (erodibility > 0.5), dry, flat, outside the ergs (no sand to cover it): streamlined hulls along the wind, 22 blocks apart, 140-block lanes, blunt upwind and tapering downwind; the wind lowers the ground between them by 6-12 blocks (Lut, Qaidam).

## Hoodoos (`hoodoos`)

On the steep slopes (0.25-0.55) of dry soft rock and of the mesas, a regional mask: thin pillars 2-5 blocks across and 4-13 high, one per 7-block cell where dense (Bryce Canyon, Cappadocia). `scarp` set: the erosion leaves them alone.

## Desert pavement (`desertPavement`)

Every other dry, not too steep point below 140: `SURF_REG`, gravel, coarse dirt and patches of red sand over sand; bare rock on cliffs (the hamada).

## Inselbergs (`inselbergs`)

Smooth domes of massive rock rising abruptly from the plains of old stable interiors (Uluru, Spitzkoppe, the Sugarloaf): `L > 0.2`, no convergence, mountains, plateau or collision, a regional mask; one candidate per 1.6 km cell (45 %), 60-180 blocks across, a little elongated, height 0.6-1 × the radius, steep sides and a rounded top. Granite (`GenPoint.insel` overrides the surface rock), smooth (`scarp`).

## Tepuis (`tepuis`)

Sandstone table mountains of the humid tropics (Roraima, Auyán-tepui): `Tb > 18..22`, `Pb > 1300..1900`, inland, a regional mask; one candidate per 2.5 km cell (50 %), 350-900 blocks across, an irregular outline, a flat summit at its own height (160-260), near-vertical walls set as a scarp so the rivers reaching the edge fall over it ([[Waterfalls]]).

## Not done

- Natural arches (they need a hole through a fin: a 3D carve).
- Playas are the existing salt flats ([[Rivers Overview|Lakes and Salt Flats]]); no interdune lakes on purpose.
