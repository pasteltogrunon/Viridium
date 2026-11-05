package net.junedev.viridium.worldgen.terrain;

import net.junedev.viridium.worldgen.terrain.core.GenParams;
import net.junedev.viridium.worldgen.terrain.pipeline.GenPoint;
import net.junedev.viridium.worldgen.terrain.pipeline.Stage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** Prints the pipeline values at the points given in VIRIDIUM_POINTS ("x,z;x,z"). Diagnostic only. */
class DebugPointTest {

    /** Lists the biggest river mouths in a square around the origin (VIRIDIUM_MOUTHS = half size in blocks). */
    @Test
    @EnabledIfEnvironmentVariable(named = "VIRIDIUM_MOUTHS", matches = ".+")
    void biggestMouths() {
        WorldGen gen = new WorldGen(new GenParams());
        double half = Double.parseDouble(System.getenv("VIRIDIUM_MOUTHS"));
        java.util.Set<Long> seen = new java.util.HashSet<>();
        java.util.List<double[]> mouths = new java.util.ArrayList<>();
        double B = gen.rivers.basins.size();
        int terminal = 0, outlets = 0, inner = 0;
        for (double z = -half; z <= half; z += B / 2) for (double x = -half; x <= half; x += B / 2) {
            long k = gen.rivers.basins.cellAt(x, z);
            if (!seen.add(k)) continue;
            var c = gen.rivers.basins.cell(k);
            if (c.terminal) terminal++;
            else if (c.outlet) outlets++;
            else if (!c.ocean) inner++;
            if (c.ocean || !c.outlet) continue;
            var net = gen.rivers.net(c.ci, c.cj);
            for (int q = 0; q < net.nMouth; q++) {
                int b = q * net.MS;
                mouths.add(new double[] { net.mouths[b + 6], net.mouths[b], net.mouths[b + 1], net.mouths[b + 4],
                    net.mouths[b + 5], net.mouths[b + 8] });
            }
        }
        mouths.sort((a, b) -> Double.compare(b[0], a[0]));
        for (int i = 0; i < Math.min(8, mouths.size()); i++) {
            double[] m = mouths.get(i);
            System.out.printf("mouth flow %.0f at %.0f,%.0f deltaR %.0f halfWidth %.1f estuary %.2f%n", m[0], m[1], m[2],
                m[3], m[4], m[5]);
        }
        System.out.println("mouths: " + mouths.size() + ", networks built: " + gen.rivers.buildCount() + ", cells: outlet "
            + outlets + ", inner " + inner + ", terminal " + terminal);
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "VIRIDIUM_POINTS", matches = ".+")
    void printPoints() {
        WorldGen gen = new WorldGen(new GenParams());
        GenPoint o = new GenPoint();
        for (String pt : System.getenv("VIRIDIUM_POINTS").split(";")) {
            String[] xz = pt.split(",");
            double x = Double.parseDouble(xz[0]), z = Double.parseDouble(xz[1]);
            gen.evaluate(x, z, Stage.EROSION, o);
            double hEro = o.h;
            gen.evaluate(x, z, Stage.BIOMES, o);
            System.out.printf(
                "(%s) h0=%.2f hEro=%.2f h=%.2f river=%d riverD=%.2f hw=%.2f riverZ=%.2f riverWL=%.2f valley=%.2f glac=%.2f gorge=%.2f lake=%d T=%.1f%n",
                pt, o.h0, hEro, o.h, o.river, o.riverD, o.riverHW, o.riverZ, o.riverWL, o.valley, o.glac, o.gorge,
                o.lake, o.T);
        }
    }
}
