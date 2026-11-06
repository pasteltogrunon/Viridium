# Blocks and Chunks

Back to [[Worldgen Overview]] · built on the output of [[Worldgen Stages]] and [[Rivers Overview]].

How the 2D pipeline output becomes blocks in a Cubic Chunks world. The order is: plan each column once, then fill every 16³ cube from the plans of its columns only.

| Page | Covers |
|---|---|
| [[Column Plans]] | running the pipeline per chunk column and turning it into absolute Y values: surface, water, ice, biome, crust profile |
| [[3D Crust]] | the vertical structure under each column: crustal provinces, lithology layers, folds, intrusions |
| [[Cube Filling and Surface Rules]] | classifying cubes, filling stone / water / air, surface blocks per biome and rock |
| [[Flora]] | trees and plants planned per column and drawn into each cube as it is generated |
| [[Cubic Chunks Integration]] | world type, generator, background prefetch of plans and river networks, `/viridium` commands |

## Design notes

The crust follows the design in [[Geology]]; vegetation follows [[Ecology]].
