# Worldgen Stages

Back to [[Worldgen Overview]] · built on [[Worldgen Foundations]] · rivers (stage 10) have their own section: [[Rivers Overview]] · landforms built into stages 5, 6 and 9 (plateaus, sea cliffs, rifts, mesas) have theirs: [[Landforms Overview]] · the output feeds [[Blocks and Chunks]].

The 2D pipeline stages, in evaluation order. Each one reads what the previous ones wrote into the shared `GenPoint` and adds its own fields.

| #   | Stage         | Page                       | Main outputs                                                             |
| --- | ------------- | -------------------------- | ------------------------------------------------------------------------ |
| 1-2 | Base climate  | [[Base Climate]]           | `Tb`, `Pb`, `lat`, `pole`, general circulation wind                      |
| 3-4 | Plates        | [[Tectonic Plates]]        | owning plate, `edge`, `ridge`, `trench`, `arc`, `orog`, collision fields |
| 5   | Continents    | [[Continents]]             | continentality `L` (>0 land, <0 sea)                                     |
| 6   | Relief        | [[Relief]] + [[Volcanism]] | base height `h0`, `mtnMask`, `volc`, `volcType`                          |
| 7   | Geology       | [[Surface Geology]]        | surface rock, erodibility `erod`                                         |
| 8   | Final climate | [[Final Climate]]          | final wind, ocean currents, `T`, `P`, rain shadow                        |
| 9   | Erosion       | [[Erosion]]                | eroded height `h`, `slope`                                               |
| 10  | Rivers        | [[Rivers Overview]]        | carved `h`, river/lake/delta/estuary fields, water level                 |
| 11  | Cryosphere    | [[Cryosphere]]             | sea ice, ice shelves, icebergs, permafrost, visible surface `hs`         |
| 12  | Biomes        | [[Biome Stage]]            | weights of the 38 biomes, dominant biome, map colour                     |

## Links outside this section

When a stage page refers to a river page or a block/chunk page, the link points to that section's hub ([[Rivers Overview]] or [[Blocks and Chunks]]) with the page name as its label; the hub lists the page.

## Design notes

These pages are the implementation of the design in [[Geology]] (plates, rock, erosion), [[Atmosphere]] and [[Oceanography]] (climate, wind, currents), and [[Biomes]] / [[Ecoregions]] (biome stage).
