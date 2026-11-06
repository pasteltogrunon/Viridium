# Worldgen Foundations

Back to [[Worldgen Overview]] · used by every page of [[Worldgen Stages]], [[Rivers Overview]] and [[Blocks and Chunks]].

The infrastructure the rest of the generator is built on.

| Page | Covers |
|---|---|
| [[Pipeline Architecture]] | `WorldGen`, the `GenPoint` / `GenScratch` / `Stage` contract, which stage writes which field, parameters (`GenParams`), seeds, caches, threading, tests |
| [[Noise and Math Primitives]] | hash, value, Perlin, fBm and ridged noise, jittered grids and Voronoi cells, smoothstep and the other helpers in `GenMath`, the 3D lattice noise of the crust |
