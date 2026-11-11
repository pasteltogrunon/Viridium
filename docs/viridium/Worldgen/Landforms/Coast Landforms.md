# Coast Landforms

Part of [[Landforms Overview]] · stage: [[Worldgen Stages|Relief]] (stacks, barriers, reefs), [[Worldgen Stages|Erosion]] (submarine canyons) · rias are a river landform: see [[Rivers Overview|River Landforms]].

Code: `ReliefStage.coastLandforms`, `offshore`, `seaStack`, `barrier`, `reef`; `ErosionStage.seaCanyons`.

Parameters (`GenParams`, Java-only, `0` turns one off, all off in `ParityTest`): `stacks`, `barriers`, `reefs`, `seaCanyons`, `rias`.

## Distance to the coast

The shelf profile follows the continentality `L`, but bands of `L` are no measure of distance: its gradient changes tenfold along a coast. `offshore` estimates the distance in blocks as `−L / |∇L|`, the gradient measured 40 blocks away (so the barrier and the reef follow the coast's general line, not every cove), and checks that there is land that way (the linear estimate also finds "coasts" around every shoal of the open sea). Three extra plate evaluations, only at sea where a coast landform may be (`L > −0.2` and its regional mask), and only in detailed evaluations at up to 6 blocks per sample (not in the coarse lattices nor on zoomed-out maps): the landforms are a few tens of blocks wide.

## Sea stacks

In reality: as a cliff retreats, harder parts are left standing in the sea as pillars (the Twelve Apostles, the needle of Étretat, the Old Man of Hoy); further out they have been worn down to stumps.

One candidate per 44-block cell (55 %) in front of rocky coasts (the sea-cliff mask `cw ≥ 0.15`), 4-60 blocks out: radius 2-5 (+ footing), rough outline, up to 55-100 % of the cliff height, lower further out. `scarp` is set so the erosion leaves them alone. [[Cliff Detail]] finds them like any wall: notches and sea caves at the water line, which open into arches through the narrow ones, boulders at their foot.

## Barrier islands, lagoons and spits

In reality: on low sandy coasts the waves pile a long narrow ridge of sand offshore, cut by tidal inlets, with a shallow lagoon behind (the Outer Banks, Padre Island, the Wadden islands, the Venetian Lido).

- Mask: regional noise (`fbm /7000`), not on cold coasts (base temperature < 3 °C fades it out), convergent margins, arcs or below sea cliffs.
- The barrier runs 70-200 blocks out, 18-34 blocks wide, dunes 1.5-4 blocks high along the crest; tidal inlets (`|fbm /1600|` near 0.46) cut it every few kilometres.
- The lagoon behind is 1-3 blocks deep; where the mask weakens it silts up (marsh at +1) and the barrier joins the mainland: a spit.
- Seaward, a 60-block shoreface back down to the shelf.
- `SURF_SAND`: sand on the barrier (never bare rock); the biome stage makes it a beach.

## Coral reefs

In reality: in warm clear seas a fringing reef hugs the coast (Red Sea), and on wide shelves a barrier reef runs far out with a lagoon (Great Barrier Reef, New Caledonia). Atolls are made with the hotspot volcanoes ([[Worldgen Stages|Volcanism]]).

- Mask: base temperature 20-24 °C and up, regional noise.
- Fringing reef: a reef flat 1.6-2.2 blocks under water from the shore to 20-45 blocks out, a crest 1.3 under water at its edge, then a 10-block front down to the shelf.
- Barrier reef (where a second noise allows it): a 14-block ridge 180-400 blocks out, crest 1.3-1.7 under water with passes, a lagoon of −8..−10 inside, a steep outer front.
- `SURF_REEF`: limestone on the reef flat and crest (no coral blocks in 1.7.10).

## Submarine canyons

The gully filter of the erosion also runs on the continental slope, between −12 and −72 (`seaCanyons`): see [[Worldgen Stages|Erosion]].

## Known limits

- The canyons are not tied to river mouths (the large ones off the Congo or the Hudson).
- No tombolos (a spit tying an island to the coast) on purpose; they appear only by chance where a barrier meets an island.
- Coastal dunes are only the barrier's crest: no dune fields inland.
- A coast landform buried by a later relief feature (an arc, a collision range, a volcano) loses its cover.
