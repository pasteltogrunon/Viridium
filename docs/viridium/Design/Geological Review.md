# Geological Review of the Worldgen

A review of the 2.0 terrain generator ([[Worldgen Overview]]) from a geologist's point of view (2026-11-07): what is not quite realistic, and which striking landforms of the real world cannot be generated yet. Design counterpart: [[Geology]], [[Terrain]].

**Status (2026-11-11).** Done: climate-dependent erosion scaled by the relief ([[Erosion]]), river terraces, oxbow lakes, alluvial fans, braided rivers, incised meanders and slot canyons, rias ([[River Landforms]]), sea stacks (arches through narrow ones), barrier islands with lagoons and spits, fringing and barrier reefs, submarine canyons ([[Coast Landforms]]). Still open: everything else below.

**Summary.** As a game generator the pipeline is very solid: plates with collisions and arcs, a 3D crust with provinces and Moho, rivers with real drainage basins, deltas, estuaries, fjords and waterfalls, and karst. Realism breaks down in three places: the **overall shape of the continents**, the **lack of geological time** (age and sediment deposition), and the **relation between the 2D and the 3D geology**. Those three explain most of the landforms that cannot appear today.

## 1. What is not quite realistic

Ordered by visual impact.

### 1.1 Continents are domes, which forces radial drainage

In [[Relief]], `plains = 40·ss(0, 0.55, L)` makes the ground rise steadily towards the continent centre, which is also a Voronoi feature point of the plate. The result is a convex shield whose rivers fan out to the nearest coast.

The Earth is usually the opposite:

- **High margins**: rift shoulders, the Great Escarpment of southern Africa, the Ghats.
- **Low interiors**: the Congo and Amazon basins, Hudson Bay, Lake Eyre.
- The big rivers (Amazon, Mississippi, Ganges, Danube) run **along** the continent, between two ranges or in a foreland basin, not from the centre to the shore.

The ~3000-block basin cells rooted on the coast make it worse: rivers come out short and alike. Endorheic basins exist, but only as accidents of the noise, never as large interior basins.

### 1.2 The vertical scale is not consistent and the oceans are very shallow

- In the crust, 1 block ≈ 25 m.
- The lapse rate of 0.07 °C per block, against the real 6.5 °C/km, means **1 block ≈ 10 m**.
- At either scale, an abyssal plain at −62 blocks is 0.6–1.5 km deep and a trench at about −92 does not reach 2.3 km. On Earth they are 4–5 km and 11 km.
- Collision ranges, on the other hand, reach 480 blocks.

The hypsometric curve comes out inverted: more relief above the sea than below it. Fine for gameplay, but it is worth **settling on one vertical scale** shared by climate, crust and relief.

### 1.3 Mountains have neither direction nor age

- Mountains are `ridged(/760)²`, which is **isotropic**. Real orogens are **belts of parallel ridges** along the suture (Zagros, Appalachians, Jura). Here they come out as starfish-shaped massifs.
- `mask = fbm(/2600) + 1.1·orog` puts mountains **with no tectonic cause** in the middle of cratons.
- There are no **old orogens**: everything with `orog` is active and sharp. Old, subdued ranges along fossil sutures inside continents (Urals, Appalachians, Iberian Massif) would add variety and explain metamorphic rocks far from plate boundaries.
- The folds of the crust have a **random strike**, and so do the dikes. In reality fold axes run parallel to the orogen, and dikes follow the rift or radiate from a plume.

### 1.4 The 2D geology is a mosaic, not a geological map

Surface rocks are 420-block Voronoi patches picked by weight ([[Surface Geology]]); the 3D crust is then *biased* towards that rock ([[3D Crust]]).

On a real geological map, outcrops come from **cutting dipping strata with the topography**, which gives:

- parallel belts,
- concentric rings around domes and basins,
- V-shaped outcrops where strata cross valleys.

The single change with the largest payoff would be to invert the dependency: let the surface rock (and so `erod`) **come from the 3D crust evaluated at the surface**. Differential erosion could then produce cuestas, hogbacks and other structural landforms.

### 1.5 Erosion only digs; nothing is ever deposited

The gully filter ([[Erosion]]) only lowers the terrain and the valleys only compress the relief towards the river. No sediment accumulates anywhere:

- no alluvial fans or bajadas at mountain fronts,
- no basin fill: the foreland basin is just a constant −12,
- no river terraces,
- no colluvium.

Deltas do deposit, but they are the exception. That is why intermontane basins come out V-shaped instead of flat and sediment-filled.

### 1.6 Precipitation does not depend on latitude

Right now ([[Base Climate]]):

- `Pb` only varies along **x** (36 000-block period).
- Temperature only varies along **z**.

On Earth latitude is the first control on rainfall: the ITCZ is wet and the **subtropical belts around 25–30° are deserts** because of Hadley-cell subsidence (Sahara, Kalahari, the Australian desert). Here there can be rainforest in the subtropics and desert at the equator just because of x.

The pipeline already computes `lat` and the wind belts. Multiplying `Pb` by a latitudinal profile would be enough: maximum at the equator, minimum around 25–30°, a second maximum around 50–60°.

### 1.7 Minor details

- **Ridges without transform faults.** Ridges follow the warped plate boundary in smooth curves. Real ones are straight segments offset in a zigzag by transform faults and fracture zones, very visible on a sea-floor map.
- **The ocean floor does not age.** Depth should grow with the square root of crustal age away from the ridge. Today it is a constant plain plus a Gaussian.
- **Erodibility does not depend on climate.** Limestone is soft in humid climates (karst) but a cliff-former in arid ones (the Redwall of the Grand Canyon). Today it is a fixed 0.60.
- **No Andean margins.** "There is always ocean next to a plate boundary" means there is no Andean-type margin: trench, steep coast and cordillera within ~100 km, one of the most spectacular landscapes on the planet.

## 2. Landforms that cannot be generated today

For each one, where it would fit in the architecture.

### Glacial (the largest gap)

Today there are U-shaped troughs and fjords as paleoclimate ([[Valleys, Gorges and Glacial Troughs]]), but no other glacial geomorphology.

| Landform | Why it is worth it | Where it fits |
|---|---|---|
| **Cirques, arêtes and horns** (Matterhorn) | The most iconic alpine landscape there is | Local 2D: trough heads with high `glacial` and high local relief |
| **Terminal and lateral moraines**, **moraine lakes** | Arcs of hills damming lakes at the end of the troughs | River network: at the end of each trough |
| **Drumlins, eskers, kettles** | Glacial plains with fields of elongated hills and round lakes | Local 2D, on plains with high `glacial` |
| **Hanging valleys** | Already up to ~10 blocks; could be much taller | Already in the network |
| **Erratic boulders** | Cheap and very recognisable detail | Flora/features, like the existing boulders |

### Rivers

| Landform | Where it fits |
|---|---|
| **Oxbow lakes** and abandoned meanders | Meander geometry: a cut-off loop becomes a horseshoe lake |
| **Braided rivers** (glacial outwash, high sediment load) | Channel shaping in `RiverStage` from slope and sediment load |
| **Alluvial fans and bajadas** | Where a river leaves the mountains for the plain; slope break in the network |
| **Stepped river terraces** | In the valley field: several old floor levels |
| **Incised meanders** (goosenecks, Horseshoe Bend) | Large meanders inside a canyon in hard rock; today meanders only exist on gentle slopes |
| **Slot canyons** (Antelope Canyon) | Sandstone, arid climate, very narrow gullies |

### Deserts and wind erosion

| Landform | Where it fits |
|---|---|
| **Dunes**: barchans, linear, star dunes, ergs | Local 2D or column: none exist, only the `dunegrass` block |
| **Yardangs** (wind-carved ridges) | Local 2D: anisotropic noise aligned with `wfx/wfy` |
| **Hoodoos and fairy chimneys** (Bryce, Cappadocia) | In `mesas`: pillars on clay or tuff capped by hard rock |
| **Natural arches** (Arches) | 3D, in sandstone, near mesa scarps |
| **Inselbergs and bornhardts** (Uluru, Sugarloaf) | Isolated granite domes on shield plains; very cheap to generate |
| **Tepuis** | Very small sandstone plateaus in a humid tropical climate. Everything needed already exists, only that combination is missing |

### Karst (beyond dolines and shafts)

| Landform | Where it fits |
|---|---|
| **Tower karst** (Guilin, Ha Long), **mogotes** | Limestone in humid tropics: inverted-cone noise; at sea it gives Ha Long Bay |
| **Tsingy** (limestone pinnacles) | Micro-relief on arid or seasonal limestone |
| **Poljes** (closed flat-floored plains) | Closed basins already exist; they would need flattening on limestone |
| **Travertine and tufa terraces** (Pamukkale, Plitvice) | Rivers on limestone: dammed steps, fits with the waterfalls |

### Coasts

| Landform | Where it fits |
|---|---|
| **Sea stacks and sea arches** (Étretat, the Twelve Apostles) | `CliffDetail` already has the notch and sea caves; the next step is missing |
| **Spits, tombolos, barrier islands and coastal lagoons** | Today there is only the atoll lagoon; barrier islands depend on longshore drift |
| **Rias** (drowned river valleys) | Estuaries and fjords exist; a drowned V-shaped valley is missing |
| **Barrier and fringing reefs** | Only atolls today; no reefs on warm shelves |
| **Submarine canyons** off river mouths | Continuation of the valley down the slope |

### Volcanic

Today there are arcs, stratovolcanoes (the `cone` mask), Hawaii-type chains, Iceland and atolls. Missing:

| Landform | Why it is worth it |
|---|---|
| Visible **lava flows** (basalt tongues, malpaís) | Very visual and cheap in 2D |
| **Cinder cones and monogenetic fields** | Dozens of small cones with craters, like La Garrotxa or Lanzarote |
| **Maars** (crater lakes) | Easy to make |
| **Large calderas** with a lake (Crater Lake, Toba) | Today a caldera only exists on the youngest volcano of each hotspot chain |
| **Columnar basalt** (Giant's Causeway) | Hexagonal 3D pattern inside LIPs and basalt plateaus |
| **Geysers, hot springs, fumaroles** | The `geyserite` block exists, but nothing places it |
| **Tuyas** (subglacial table volcanoes) | Combine volcanism and glaciation |
| **Continental hotspots** (Yellowstone) | Today hotspots are vetoed on land |

### Tectonic structures

| Landform | Where it fits |
|---|---|
| **Cuestas and hogbacks** | Direct consequence of taking the geology from the 3D crust (1.4) |
| **Visible anticlines** (Zagros "zebra mountains") | Folds striking parallel to the suture, plus differential erosion |
| **Salt domes and diapirs** | Sedimentary basins with evaporites in the crust |
| **Transform faults on land** (San Andreas): offset rivers, linear valleys | Could reuse the transform boundary type that is already classified |
| **Fault-block ranges** (Basin and Range) | A variant of the rift: many parallel horsts and grabens instead of one |
| **Impact craters** | Rare but very visible; a ring with a central peak is cheap |

### Periglacial

The pipeline already knows where permafrost is (`perma`, [[Cryosphere]]) but draws nothing from it. Missing: **pingos**, **polygonal ground** and **thermokarst** (thaw lakes).

## 3. Suggested priorities

Ordered by impact against effort:

1. **Change the continental profile**: high margins, low interior, and a real foreland basin behind the ranges. Long rivers along the continent then appear without touching the river network.
2. **Make precipitation depend on latitude**: a one-line change in `ClimateBaseStage` that puts deserts where they belong.
3. **Give mountains direction and age**: ridges aligned with the suture, fossil sutures with a subdued orogen.
4. **Glacial morphology**: cirques, horns and moraines on top of the existing `glacial` and `FR` fields.
5. **Minor volcanism and deserts**: flows, cinder cones, maars, inselbergs and dunes. Cheap, local and very visual.
6. In the longer term, **take the surface rock from the 3D crust**. It is the change that adds the most geological realism, but also the most expensive one.
