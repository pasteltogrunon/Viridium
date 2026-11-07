# Cliff Detail

Part of [[Landforms Overview]] · reads [[Blocks and Chunks|Column Plans]] · drawn by [[Blocks and Chunks|Cube Filling and Surface Rules]] and [[Blocks and Chunks|Flora]].

Code: `column/CliffDetail.java`, `cube/CubeFiller.cliff`, `cube/flora/FloraPlanner.boulders`, `FeatureType.BOULDER`.

Block-level detail at the foot of every cliff, whatever made it ([[Plateaus and Mesas|plateau scarps]], [[Sea Cliffs]], [[Continental Rifts|rift faults]], gorges, mountain walls). It is found from the planned heights of a column and its 8 neighbours, after planning: one height per column cannot hold an overhang, so the overhang is cut when the cube is filled.

## In reality

- **Notch**: at the sea, waves dig a notch at the water line and the wall overhangs it (the first stage of sea caves and arches). Inland, softer beds under a hard one weather faster and leave a shallow overhang.
- **Talus**: rock falling from a dry cliff piles up as a debris cone at its foot, at the angle of repose (~35°).
- **Fallen boulders**: blocks of the wall lie scattered at the foot, thinning out with distance. At the sea they stand on the platform, half in the water.

## Finding walls

`CliffDetail.get(cx, cz)` reads surface, water and flags of the 36×36 blocks around the column (the 3×3 plans), then:

1. **Face**: a column whose ground drops at least 9 blocks within the next 3 in one of 8 directions (the direction with the tallest wall wins).
2. **Real foot**: follow that direction down while the ground still drops 2+ blocks per step. Every face of one wall finds the same foot, so the notch and the talus are at the bottom only (an earlier version put them on ledges halfway up).
3. **Wall height** `H` = face − foot, at least 10 blocks. **Wet** foot = water stands on it (sea, lake).

Results are cached per column (Guava, 2048) and never change the plans.

## Notch

| | wet foot (sea, lake) | dry foot |
|---|---|---|
| level | water line − 1 (stays flooded below it) | above the talus: foot + talus + 1 |
| height | 0.12·H, 2–4 blocks | 0.1·H, 2–3 blocks |
| depth into the wall | H/8, 2–4 columns | H/12, 1–3 columns |

- The notch starts at the outermost column of the wall that is still solid above it. The column in front of it must leave it open.
- At the sea the waves also cut through the low ledges in front of the wall: a ledge that does not rise above the notch is cut away entirely (it becomes sea floor); a higher one keeps a roof of at least one block.
- Roof: at least 3 blocks into the wall. `CubeFiller` lowers it further under loose soil: if the surface rules put sand or gravel on top, at least one block of rock stays between them and the hole.
- One in five wall stretches (8-block cells) has no notch.
- Notches can lie deep under the surface rules of a tall column, or talus above its highest point: the cube classification takes the cliff range into account (a crust-only or air cube is filled as needed).

## Talus

Only at dry feet, never on water or a river. Height `min(6, 0.25·H)` against the wall, decreasing by 0.9 per block outwards, up to `0.3·H` blocks (at most 8). Also leans on the lowest wall columns. Cobblestone, with gravel on top, or mossy cobblestone where it rains (> 900 mm). Nothing grows on talus.

## Boulders

Every column within 10 blocks of a wall foot is a site (`wallDist`, `wallH`). Probability `0.03·(1 − (dist − 1)/10)·min(1, H/30)`, so more of them close to tall walls. A boulder is a rough ellipsoid (radii 0.8–2.2, flatter in height), half buried, made of the rock under its own column (the lithology of the crust a few blocks down). It replaces air, snow, plants and water, so boulders also stand in the sea below sea cliffs. `CanopySink` (Distant Horizons LODs) ignores them.

## Falling water

`CliffDetail` also finds the lips of [[Waterfalls]]: a column next to river water of a waterfall zone (`F_FALL`) standing 3+ blocks above its own top gets a **curtain** of falling water (`flowing_water`, meta 8; ice where it is colder than −6 °C) up to that level. It covers oblique lips, where a cliff crosses the river at an angle, rivers falling off sea cliffs, and steep cascades.

## Results

`CliffDetailTest` on a sea cliff and an escarpment (72 chunks): 675 notch columns (266 flooded), 1509 talus columns, 2565 boulder sites. Sections drawn with `VIRIDIUM_CLIFF`:

```
sea cliff (x −3975..−3930, z −1128)          escarpment foot (x 3400..3480, z 6250)
  65 ###########################o             109 ##########################################oo
  64 #########################ooo             108 ##########################################oo+++ +
  63 ########################oooo             107 #############################################++++
  62 #######################wwwwww~~~~        105 #############################################++++++
  61 #######################wwwwww#~~~~       103 ##############################################++++##+
```
