package net.junedev.viridium.worldgen.terrain.fields;

import net.junedev.viridium.worldgen.terrain.WorldGen;
import net.junedev.viridium.worldgen.terrain.pipeline.GenPoint;
import net.junedev.viridium.worldgen.terrain.pipeline.GenScratch;
import net.junedev.viridium.worldgen.terrain.stages.ErosionStage;

/**
 * {@link ErosionStage} taking the gradient of the smoothed relief from a cached 8-block {@link HeightGrid} (4 lookups)
 * instead of 4 full coarse height evaluations per point.
 */
public class FastErosionStage extends ErosionStage {

    private final HeightGrid grid;

    public FastErosionStage(WorldGen gen) {
        super(gen);
        this.grid = new HeightGrid(gen, 8, 16, 4096);
    }

    public HeightGrid grid() {
        return grid;
    }

    @Override
    protected void reliefGradient(double x, double y, GenPoint o, double bpp, GenScratch s) {
        if (bpp > 8) { // map overviews: pixels further apart than the lattice tiles would compute a new tile each
            super.reliefGradient(x, y, o, bpp, s);
            return;
        }
        final double E = 12;
        o.gx = (grid.sample(x + E, y, null) - grid.sample(x - E, y, null)) / (2 * E);
        o.gy = (grid.sample(x, y + E, null) - grid.sample(x, y - E, null)) / (2 * E);
    }
}
