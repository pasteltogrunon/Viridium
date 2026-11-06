# Sea Cliffs

Part of [[Landforms Overview]] · stage: [[Worldgen Stages|Relief]].

Code: `ReliefStage.coastalCliff`.

## In reality

Where the coast is made of resistant rock and stands high, the waves undercut the foot and the face retreats as a cliff (Étretat, Moher, the white cliffs of Dover). In front of it the sea floor is a nearly flat **wave-cut platform**. Between headlands, coves hold pocket beaches.

## Model

```
cw  = ss(0, 0.2, fbm(/6000) + 0.3·(cliffs − 1))        rocky stretches (about a third of the coast)
Lc  = L + 0.006·fbm(/90)                               coves and headlands
C   = cliffs·cw·(14 + 46·clamp(0.5 + 0.7·fbm(/2500)))   14..60 blocks
h  += C·ss(0, 0.0035, Lc)·(1 − ss(0.03, 0.12, L))
```

- The land stands `C` above the sea right at the coast and drops in ~2–5 blocks (the continentality gradient is 7·10⁻⁴ to 2·10⁻³ per block).
- The raised strip fades back to the normal land 150–400 blocks inland: the cliff top slopes gently landwards, like chalk downs.
- **Coves**: the step follows the coast shifted by 90-block noise. Where `L > 0` but `Lc < 0` the land stays low, so pocket beaches open between headlands.
- The shelf in front is already shallow: it plays the wave-cut platform.
- Fields: `GenPoint.cliff` (share) and `scarp`.
- **Erosion**: damped on the face and seaward (`1 − ss(0.002, 0.012, −Lc)`), and it fades in slowly behind the edge (`ss(0.0035, 0.08, Lc)`). A sudden start left a raised lip along the cliff top with a 20-block trench behind it.

The notch at the water line, boulders on the platform and rivers falling into the sea are in [[Cliff Detail]] and [[Waterfalls]].

## Results

Example at x −3950, z −1128: 40 blocks above the sea, dropping to 3 blocks in 2.
