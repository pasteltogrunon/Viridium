package net.junedev.viridium.worldgen.terrain.fields;

import net.junedev.viridium.worldgen.terrain.WorldGen;
import net.junedev.viridium.worldgen.terrain.pipeline.GenPoint;
import net.junedev.viridium.worldgen.terrain.pipeline.GenScratch;
import net.junedev.viridium.worldgen.terrain.stages.ErosionStage;

/**
 * {@link ErosionStage} taking the gradient of the smoothed relief from a cached 8-block {@link HeightGrid} (4 lookups)
 * instead of 4 full coarse height evaluations per point. Map overviews (bpp > 8) read the tile's own relief lattice
 * when the renderer provides one ({@link GenScratch#reliefLattice}); the game (bpp 1) never does.
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
            if (s.reliefLattice == null || !latticeGradient(x, y, o, s)) super.reliefGradient(x, y, o, bpp, s);
            return;
        }
        final double E = 12;
        o.gx = (grid.sample(x + E, y, null) - grid.sample(x - E, y, null)) / (2 * E);
        o.gy = (grid.sample(x, y + E, null) - grid.sample(x, y - E, null)) / (2 * E);
    }

    /**
     * Gradient from the map tile's relief lattice ({@link GenScratch#reliefLattice}): central difference one lattice step
     * either side (bilinear between nodes; map pixels sit on nodes). False when (x, y) is not inside the lattice.
     */
    private static boolean latticeGradient(double x, double y, GenPoint o, GenScratch s) {
        double st = s.latticeStep, u = (x - s.latticeX0) / st, v = (y - s.latticeZ0) / st;
        int n = s.latticeN;
        if (!(u >= 1 && v >= 1 && u <= n - 2 && v <= n - 2)) return false;
        float[] l = s.reliefLattice;
        o.gx = (lerp2(l, n, u + 1, v) - lerp2(l, n, u - 1, v)) / (2 * st);
        o.gy = (lerp2(l, n, u, v + 1) - lerp2(l, n, u, v - 1)) / (2 * st);
        return true;
    }

    private static double lerp2(float[] l, int n, double u, double v) {
        int i = Math.min((int) u, n - 2), j = Math.min((int) v, n - 2);
        double fu = u - i, fv = v - j;
        int k = j * n + i;
        double a = l[k] + (l[k + 1] - l[k]) * fu, b = l[k + n] + (l[k + n + 1] - l[k + n]) * fu;
        return a + (b - a) * fv;
    }
}
