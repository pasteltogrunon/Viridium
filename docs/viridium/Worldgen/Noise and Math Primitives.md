# Noise and Math Primitives

Part of [[Worldgen Foundations]] · used by every stage of the Pipeline Architecture.

Code: `core/Noise.java`, `core/GenMath.java`, `crust/LatticeNoise3.java`.

## Noise

| Function               | Range      | Notes                                                                                                                                         |     |                                                                                                 |
| ---------------------- | ---------- | --------------------------------------------------------------------------------------------------------------------------------------------- | --- | ----------------------------------------------------------------------------------------------- |
| `hash(x, y, s)`        | 32-bit int | integer hash of a lattice point and a seed                                                                                                    |     |                                                                                                 |
| `rnd(x, y, s)`         | [0, 1)     | uniform value per lattice point. Used for every "random" choice: feature points, plate velocities, rock types...                              |     |                                                                                                 |
| `perlin(x, y, s)`      | ≈ [−1, 1]  | 2D gradient noise with quintic fade                                                                                                           |     |                                                                                                 |
| `fbm(x, y, s, oct)`    | ≈ [−1, 1]  | fractal sum, amplitude ×0.5 per octave, coordinates rotated and scaled by `[[1.6, −1.2], [1.2, 1.6]]` between octaves (avoids grid alignment) |     |                                                                                                 |
| `ridged(x, y, s, oct)` | [0, 1]     | ridged multifractal; uses a smoothed `                                                                                                        | n   | ` (`sqrt(n² + 0.015)`) so ridges are never one pixel wide; octaves weighted by the previous one |
| `LatticeNoise3`        | [0, 1)     | smooth 3D value noise used by the 3D Crust; has a per-cube cached variant (27 lattice values)                                             |     |                                                                                                 |

Noise inputs are always `position / scale` in blocks, so `fbm(x / 1100, y / 1100, ...)` has features about 1100 blocks wide.

## Jittered grids and Voronoi

Several stages use the same pattern: a regular grid of size `S` with one feature point per cell, jittered inside the cell with `rnd`, e.g. `featX(i, j) = (i + 0.15 + 0.7·rnd(i, j, seed))·S`. The nearest feature point (searched in the 3×3 or 5×5 neighbouring cells) defines a Voronoi cell. Most of them first **warp** the query position with fBm, so cell borders are irregular:

- plates (Tectonic Plates), 5×5 search, warp `0.28·plateSize`;
- drainage basins (Drainage Basins), 3×3 search, warp `0.22·basinSize`;
- rock patches (Surface Geology), 3×3 search, warp `0.75·rockPatch`;
- hotspots (Volcanism), sea ice floes and icebergs (Cryosphere), dikes and kimberlite pipes (3D Crust).

## Math helpers (GenMath)

| Helper                              | Meaning                                                                                                                                |
| ----------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------- |
| `ss(a, b, x)`                       | smoothstep: 0 below a, 1 above b, smooth in between. If a > b it is a falling edge. This is the "soft threshold" of the whole codebase |
| `sigLo(v, t, w)` / `sigHi(v, t, w)` | logistic ≈1 when v < t / v > t, width w (used by the Biome Stage)                                                                  |
| `band(v, a, b, w)`                  | `sigHi(v, a, w)·sigLo(v, b, w)`: ≈1 between a and b                                                                                    |
| `lerp`, `clamp`, `floor`, `hypot`   | usual                                                                                                                                  |

Reading formulas in the code: `x * ss(0, 0.16, L)` means "x, faded in as the continentality goes from 0 to 0.16".
