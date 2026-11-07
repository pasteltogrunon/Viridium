package net.junedev.viridium.worldgen.terrain.fields;

import static net.junedev.viridium.worldgen.terrain.core.GenMath.ss;

import net.junedev.viridium.worldgen.terrain.WorldGen;
import net.junedev.viridium.worldgen.terrain.pipeline.GenScratch;
import net.junedev.viridium.worldgen.terrain.stages.ClimateStage;

/**
 * {@link ClimateStage} reading its 12 neighbourhood samples (wind, upwind moisture, ocean currents) from a cached
 * 32-block {@link HeightGrid} instead of evaluating the plates and relief each time. Same model; differences are the
 * interpolation error of a field whose samples are hundreds of blocks apart.
 */
public class FastClimateStage extends ClimateStage {

    private final HeightGrid grid;

    public FastClimateStage(WorldGen gen) {
        super(gen);
        // 1024-block tiles: the ocean-current probes reach 6000 and 10800 blocks away (plateSize 12000) and use a few nodes
        // there; 2048-block tiles built 2.3-2.8x more nodes for a new area. Same 8 MB bound, same values.
        this.grid = new HeightGrid(gen, 32, 32, 1024);
    }

    /**
     * Map pixels at least this far apart sample the relief directly: a lattice tile (1024 blocks, 1024 nodes) would be
     * computed for a handful of pixels, and the cache would thrash (minutes per map tile).
     */
    private static final double EXACT_BPP = 128;

    public HeightGrid grid() {
        return grid;
    }

    @Override
    protected double coarseHeight(double x, double y, GenScratch s) {
        if (s.bpp >= EXACT_BPP) return super.coarseHeight(x, y, s);
        return grid.sample(x, y, s.tmp1);
    }

    @Override
    protected double lastLand(GenScratch s) {
        if (s.bpp >= EXACT_BPP) return super.lastLand(s);
        return ss(-0.15, 0.25, s.tmp1[0]);
    }

    @Override
    protected double landAt(double x, double y, GenScratch s) {
        if (s.bpp >= EXACT_BPP) return super.landAt(x, y, s);
        grid.sample(x, y, s.tmp1);
        return ss(-0.15, 0.25, s.tmp1[0]);
    }
}
