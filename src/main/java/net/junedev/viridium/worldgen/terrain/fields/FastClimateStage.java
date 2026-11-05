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
        this.grid = new HeightGrid(gen, 32, 64, 256);
    }

    public HeightGrid grid() {
        return grid;
    }

    @Override
    protected double coarseHeight(double x, double y, GenScratch s) {
        return grid.sample(x, y, s.tmp1);
    }

    @Override
    protected double lastLand(GenScratch s) {
        return ss(-0.15, 0.25, s.tmp1[0]);
    }

    @Override
    protected double landAt(double x, double y, GenScratch s) {
        grid.sample(x, y, s.tmp1);
        return ss(-0.15, 0.25, s.tmp1[0]);
    }
}
