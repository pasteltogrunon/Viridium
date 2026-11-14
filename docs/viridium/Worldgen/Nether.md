# Nether (prototype)

Implements the design in [[Design/Nether]] on Cubic Chunks · same pattern as the overworld: [[Column Plans]],
[[Cubic Chunks Integration]] · enabled by `viridiumNether` (config, category `worldgen`, default on).

Code: `worldgen/nether/`. Entry point `NetherWorldGenerator` (dimension −1 of a `viridium` world, wired in
`ViridiumWorldType`). Everything is a pure function of the seed (`foldSeed(worldSeed) ^ 0x4E7E4`) and the position.

## Layers

| Design layer | Class | What it does |
|---|---|---|
| Deep substrate | `NetherSubstrate` | Fractures = edges of a warped Voronoi diagram (1150-block cells, each edge one fracture with an id; 42 % are major and open into abysses along stretches). Thermal potential, deformation angle, tilt of the tiers, material (`SubstrateKind`: infernal, pyric, soul, crystal), age. |
| Cores | `NetherCores`, `NetherCore` | One per 576-block cell and tier (the middle tier always). Among 6 candidates the core picks the hottest place close to a fracture (never in an abyss). Everything else derives from the substrate: dominant/secondary `Transform` (biased by the material), activity (thermal − age), reach, intensity, orientation (along its fracture, else the deformation), anisotropy, openness preference. |
| Domains | `NetherCores.sample` | Primary core = smallest anisotropic normalised distance (vertical separation ×1.5, so domains leak into tiers without a nearer core; ×0.78 along the core's own fracture). Encounter of the two nearest cores per pair: transition, dominance (border shifted), interference (both at ≤ 0.6) or barrier (a massif at the border; likelier between different substrates). |
| Architecture | `NetherWorld.base` | Three tiers (`NetherLayout`): floor/ceiling per tier from an openness field (noise + domain preference + massifs elongated along the deformation + barriers + long chambers flanked by parallel walls along minor fractures + core cavities). Terraced floors; open basins and active domains sink below the tier's lava level, extinct ones are raised (dry). Ceiling capped by a smooth minimum. |
| Connections | `NetherRoutes` | Main routes = Gabriel graph of the cores of each tier (loops, alternatives). Graded: never under the dry level (causeways and arched bridges), never steeper than 1 in 2 (galleries cut through massifs). Chimneys between tiers: hollow towers with a spiral ledge (≤ 1 in 2) from a door facing the lower spur route to an exit gap facing the upper one, windows each turn, optional central lavafall; placed where they clear cavities, abysses and main routes. |
| Lava | planner + filler | One flat level per tier (`LAVA`), so seas are always contained by their own floor; abyss lips; lava falls from active ceilings into seas; luminous fissures on active floors; a crust band at `OLD_LAVA` on the walls of extinct systems. The deep tier is a buried sea with islands. |
| Grammar | `NetherFiller.overlay`, `Landmarks` | Fold: curved partial sheets following the folded strata. Repetition: arcades on log-spaced rings around the core (arches closer and closer towards it) and free columns, with anomalies. Suspension: floating masses with hanging cones. Growth: branching tube networks (glowstone tips). Hollowing: windowed shells in the void, lined geodes in rock. Each core has a landmark at full intensity (curtains, colonnades, spiral of masses, mineral tree, nested envelopes); its signature material appears as strata on nearby walls. |
| Surfaces and ecosystems | `Eco`, `NetherFiller.decorate` | Ecosystem per column from activity, lava closeness, substrate, growth and height: crust, ash, soul plains (wart), ossuaries (bone spikes), fungal (extinct), reefs (quartz); glowstone pendants (dense in "inverted gardens"). Routes get a path cover and ribs (arch frames with a glowstone keystone every 26 blocks). |

`NetherPlanner` computes a `NetherPlan` per chunk column (≈ 70 KiB, cached by `NetherPlanCache`); `NetherFiller`
fills a cube from it into `NetherMat` ids (no Minecraft classes, so tests render them); `NetherBlocks` maps them to
vanilla blocks and Viridium rocks.

## Vertical layout

World Y −256..512 (bedrock floor and roof, 1–4 layers). Tiers (nominal floor / ceiling / lava): deep −184 / −44 /
−182, middle 37 / 200 / 28, high 269 / 436 / 260. Slabs between tiers keep ≥ 14 blocks. Vanilla portals land in the
middle tier (`getActualHeight` is the generation top).

## Tools

- `/viridium nether`: domain, core (direction, distance, activity), border, fracture, nearest chimney and route.
- `NetherViewTest` (`VIRIDIUM_NETHER_VIEW="x,z,half[,step[,ymin,ymax[,sliceY]]]"`): tier maps, vertical sections and
  slices in `build/reports/nether/`.
- `NetherWalkTest`: walks the generated blocks (1-block steps, 2 of head room, reversible) through chimneys and main
  routes (`VIRIDIUM_NETHER_WALK_N`, default 6). Also `VIRIDIUM_NETHER_CHIMNEY` / `VIRIDIUM_NETHER_BOX` ASCII dumps.
- Server pregeneration: `VIRIDIUM_GENBENCH="3,-17,32" VIRIDIUM_GENBENCH_DIM=-1` with `runServer`.

## Not done yet

Fortresses and other vanilla Nether structures (no `ChunkProviderHell`), mod ore populators, custom biomes per
ecosystem, fog/lighting per domain, substrate boundaries are vertical contacts, the fold field can jump at triple
domain junctions, spurs of different chimneys are not checked against other towers.
