# Karst

Part of [[Caves Overview]] · 2D part after [[Rivers Overview|the rivers]] · sea caves and mouths extend [[Landforms Overview|Cliff Detail]].

Code: `caves/Karst.java`, `CliffDetail` (sea caves, cave mouths).

## In reality

In limestone, water seeping through the soil widens fractures until the ground settles into **dolines** (sinkholes): closed funnel-shaped hollows from a few metres to hundreds across, dotting karst plateaus by the thousand. Some open into a **shaft** (pothole, aven) that leads down to the caves. On rocky coasts the waves dig **sea caves** along weaknesses at the water line; at the foot of cliffs cave **mouths** open where passages reach the face.

## Dolines (2D)

- One candidate per 48×48 cell (`rnd < 0.42·dolines`), jittered: radius 5–20 blocks, depth 0.35–0.8 × radius, bowl profile `depth·(1 − q²)^1.5`.
- Only on karstic surface rock (limestone 1, marble 0.7, blended between the two rock patches), on land, never on rivers or lakes, fading out within 4–24 blocks of a river channel.
- Applied after the rivers (`WorldGen.evaluate` → `CaveCarver.surface`), so column plans, the map and the LODs see them. `GenPoint.doline` holds the depth; the Landforms map layer shows them in dark green.
- 1.3 % of the land of the test area lies in dolines, up to 14.5 blocks deep.

## Shafts (3D)

- A quarter of the dolines whose centre is on karst land (limestone or marble, `karst > 0.4`) drop into a vertical shaft: radius 1.5–3.5 blocks (rough walls), from the surface down 30–150 blocks below the doline.
- A `CaveCarver.Shape` that may cut through the roof up to the surface (never under water). Checked per cell with the pipeline up to geology at the doline centre, cached.
- 216 shafts in a 12×12 km test area; all checked shafts are open 12 blocks down.

## Sea caves and cave mouths (CliffDetail)

The notch at the foot of a cliff (see [[Landforms Overview|Cliff Detail]]) goes much deeper on some stretches (8-block cells):

| | where | probability | height | depth into the wall |
|---|---|---|---|---|
| sea cave | wet foot, wall ≥ 14 | 25 % | 0.2·H, 3–6 | 9 |
| cave mouth | dry foot, wall ≥ 20 | 6 % | 0.3·H, 5–10 | 8 |

Notch columns in the cliff test area went from 675 to 959.
