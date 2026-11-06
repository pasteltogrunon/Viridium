# Tectonic Plates (stages 3-4)

Part of [[Worldgen Stages]] · depends on: [[Worldgen Foundations|Noise and Math Primitives]] · used by: Continents, Relief,
Volcanism, Surface Geology, 3D Crust, river mouths and valleys (via `edge`).

Code: `stages/PlateStage.java`.

## Plates

Plates are Voronoi cells of a jittered grid of size `plateSize` (12000 blocks), searched in a 5×5 neighbourhood. The query point is first warped by fBm (amplitude `plateWarp·plateSize` ≈ 3360 blocks, scale `0.55·plateSize`), so plate boundaries wander instead of being straight lines. The warped position is kept in `wx, wy`; the owning plate in
`pi, pj` and its feature point in `fx, fy`.

Each plate has a random velocity: direction uniform, speed 0.25–1 (`plateVel`).

## Boundaries

For every neighbouring plate, the stage measures the distance `d` to the bisector (the exact Voronoi edge in warped space) and the **convergence**: relative velocity projected on the boundary normal (`> 0` convergent, `< 0` divergent). Each boundary is classified:

| Type | Condition | Effect |
|---|---|---|
| Divergent / transform | not a collision | mid-ocean **ridge** (`ridge`, Gaussian of width `0.045·S`, stronger when divergent) |
| Ocean subduction | convergent, not a collision | **trench** right at the boundary on the subducting side (`trench`) and a volcanic **island arc** `0.08·S` inside the overriding plate (`arc`) |
| Continental collision | `conv > 0.12` and a per-pair random < `collisionChance` (0.5) | suture range and plateau (`collZ`, `collK`, `collS`) |

- Which plate overrides the other is a symmetric per-pair random (`overSign`), so both plates agree.
- `orog` accumulates convergence over a wide Gaussian (`0.16·S`): general mountain building near convergent edges.
- **Triple junctions**: every band is multiplied by `fk = ss(0, 0.08·S, p)`, where `p` is the "power distance" to the
  plate's *other* boundaries. Bands fade where two boundaries meet instead of overlapping or ending abruptly.
- Collision zones fade near any non-collisional boundary of either plate (`g`), so a collision does not run into an
  ocean wedge. Several nearby collisions are blended with weights `zw⁴` so the dominant one can change without jumps. `collS` is the signed distance to the suture (positive on the overriding plate), used to place the range and the plateau in Relief.

## Edge distances

- `edge`: distance to the nearest boundary.
- `edgeNC`: distance to the nearest **non-collisional** boundary. Continents grow until they meet a collision, but must stay away from other boundaries (Continents).

With `exactPlateEdges` (default on), both are refined:

- `edgeNC` is measured to the real boundary **segment** (the bisector clipped by the other half-planes), not to the infinite bisector line, which would otherwise cut straight ocean wedges past triple junctions.
- Both are **soft minimums** (log-sum-exp over the near sides, `K = 0.02·S`). The hard minimum creases along the medial axis of the cell (straight lines from every corner) and the coasts that follow it showed them.

`edge` is also read much later: the broad valleys and the delta plains and estuaries fade out near plate boundaries (`ss(8..20, 70..90, edge)`).
