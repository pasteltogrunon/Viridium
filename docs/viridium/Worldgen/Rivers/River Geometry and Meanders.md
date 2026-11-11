# River Geometry and Meanders

Part of [[Rivers Overview]] · steps 8-9 of the [[River Network Build]] · depends on: [[River Water Levels]],
[[Worldgen Foundations|Noise and Math Primitives]] · used by: River Stage Evaluation (channel distance),
River Mouths, Deltas and Estuaries (mouth direction, delta apex), Valleys, Gorges and Glacial Troughs (meander belt width).

Code: `RiverNetBuilder.build` (reach loop), `ampAt`, `shape`, `lam`.

The river nodes form a tree on a jittered 32-block grid. This step turns it into smooth curves: one **reach** per river
node, each a quadratic Bézier sampled at 9 points, displaced sideways by meanders.

## Reaches

Reaches are built in flood order (downstream first), so the receiver's reach always exists when a node is processed
and the tributary can read its geometry and phase.

For river node `a` (jittered position), with main upstream node `mu` and receiver `r`:

- **start** `s`: midpoint between `mu` and `a`; for a headwater, `a` itself.
- **control point**: `a`.
- **end** `e`, three cases:

| Case | End point | Meander at the end |
|---|---|---|
| **main stem** (`mainUp[r] == a`) | midpoint between `a` and `r` | continues into the receiver's reach (same amplitude and phase) |
| **confluence** (`a` is a tributary of `r`) | the **middle point of the receiving reach's curve** (`pts[HALF]`) | amplitude 0: the tributary joins exactly on the receiving river's axis, at the phase of that point |
| **mouth / exit** (receiver is not a river node) | the receiver node itself | at a sea mouth, the amplitude of the last stretch; at an exit node 0 (the next basin starts on it) |

Because consecutive main-stem reaches share their midpoints and the control points are the nodes, the main stem is a
continuous, tangent-continuous curve (a quadratic B-spline through the jittered nodes). Tributaries join in the middle
of a reach of the main river, not at its nodes, which avoids several rivers meeting at one point.

Per reach the builder also stores widths `w0, w1`, water levels `z0, z1`
([[River Water Levels#How levels are used by reaches]]), flow, the headwater flag, the distance to the mouth along the main stem at both ends (`am0`, `am1`,
accumulated upstream from each mouth), and its bounding box. `reach` is the largest distance any curve strays from its
node's grid cell; queries search that far (River Stage Evaluation).

## Meanders

### Where

The meander amplitude at the midpoint between node `a` and its receiver `b`:

```
ampAt(a, b) = min(1.3·g, 3.2·hw + 4, room)    size: grows with the river, at most ~42 blocks; see room below
            · ss(0.05, 0.008, grad)           only on gentle slopes (filled drop per block)
            · ss(1.8, 3.6, hw)                only on rivers wide enough
            · straight[a] · straight[b]       never on a sharp bend
            · min(meanders, 1.5)
```

`straight[n] = ss(1.5, 0.45, turning angle)` measures how much the river already turns at node `n` (angle between
`mainUp → n` and `n → recv`). On a sharp bend of the network, meanders would make loops cross; there they are turned
off.

`room` (`meanderRoom`) keeps a river from meandering into another one: per river node, `0.45·gap − 2`, the gap being
the distance between its channel and the channel of the nearest other river within three cells (`distance − hw − hw`);
the smaller of the two nodes' rooms is used. Both rivers may swing towards each other and still not touch. Not counted
as "other": the river's own nodes three up and down, and tributaries about to join it (their last reach ends on its
curve). Without it two rivers a cell or two apart, each swinging up to 42 blocks, crossed each other.

Inside a reach the amplitude is interpolated from its upstream value `A0` to its downstream value `A1`, so it changes
smoothly along the river.

### Shape

The offset perpendicular to the curve's tangent is `A·shape(phase)`:

```
ps          = ph + 0.5·sin(ph) + 0.8·perlin(0.11·ph)
shape(ph)   = (sin(ps) + 0.18·sin(3·ps + 0.6)) · (0.8 + 0.35·perlin(0.07·ph))
```

- `0.5·sin(ph)` skews the wave: bends are asymmetric, like real meanders, not a sine.
- The Perlin term on the phase makes the wavelength irregular; the `sin(3ps)` harmonic sharpens the bends; the second
  Perlin modulates the amplitude.

### Phase

The wavelength grows with the river: `λ(w) = clamp(12·w + 40, 60, 420)` blocks (w = mean half-width of the reach).
The phase is accumulated **from the mouth upstream**:

```
phase at the downstream end  P1 = phase of the receiver's reach where this reach joins it
phase at the upstream end    P0 = P1 + 2π·(reach length)/λ
```

So the meander pattern is continuous along the main stem, and each tributary starts from the phase of the point where it
joins.

The headwater's first point is pinned to the start, and a confluence's last point is pinned exactly to the receiving
reach's middle point (no offset), so curves always connect.

## Mouth classification

When a reach ends at the sea, the builder also records a **mouth** and decides between delta and estuary. That part is
described in River Mouths, Deltas and Estuaries.
