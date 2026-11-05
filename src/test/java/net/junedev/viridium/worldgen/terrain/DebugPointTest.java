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

    /** Lists the biggest lakes and the river reaches running inside them (VIRIDIUM_LAKES = half size in blocks). */
    @Test
    @EnabledIfEnvironmentVariable(named = "VIRIDIUM_LAKES", matches = ".+")
    void biggestLakes() {
        WorldGen gen = new WorldGen(new GenParams());
        double half = Double.parseDouble(System.getenv("VIRIDIUM_LAKES"));
        java.util.Set<Long> seen = new java.util.HashSet<>();
        double B = gen.rivers.basins.size();
        java.util.List<String> out = new java.util.ArrayList<>();
        int total = 0, inLake = 0;
        for (double z = -half; z <= half; z += B / 2) for (double x = -half; x <= half; x += B / 2) {
            long k = gen.rivers.basins.cellAt(x, z);
            if (!seen.add(k)) continue;
            var c = gen.rivers.basins.cell(k);
            if (c.ocean) continue;
            var net = gen.rivers.net(c.ci, c.cj);
            int lakeRiv = 0;
            for (int s = 0; s < net.count; s++) {
                int q = (s * net.NPT + net.NPT / 2) * 2;
                int i = (int) Math.floor((net.pts[q] - net.x0) / net.g), j = (int) Math.floor((net.pts[q + 1] - net.y0) / net.g);
                if (net.lakes.containsKey(j * net.nx + i)) lakeRiv++;
            }
            total += net.count;
            inLake += lakeRiv;
            if (net.lakes.size() < 40) continue;
            double sx = 0, sy = 0;
            for (int idx : net.lakes.keySet()) {
                sx += net.x0 + (idx % net.nx + 0.5) * net.g;
                sy += net.y0 + (idx / net.nx + 0.5) * net.g;
            }
            out.add(String.format("%6d cells lake area, %4d reaches inside, centre %.0f,%.0f (basin %d,%d)", net.lakes.size(),
                lakeRiv, sx / net.lakes.size(), sy / net.lakes.size(), c.ci, c.cj));
        }
        out.sort(java.util.Comparator.reverseOrder());
        out.stream().limit(12).forEach(System.out::println);
        System.out.println("reaches " + total + ", inside lakes " + inLake);
    }

    /** Lists the river reaches of every basin net within r blocks of a point (VIRIDIUM_REACHES = "x,z,r"). */
    @Test
    @EnabledIfEnvironmentVariable(named = "VIRIDIUM_REACHES", matches = ".+")
    void reachesNear() {
        WorldGen gen = new WorldGen(new GenParams());
        String[] a = System.getenv("VIRIDIUM_REACHES").split(",");
        double x = Double.parseDouble(a[0]), z = Double.parseDouble(a[1]), r = Double.parseDouble(a[2]);
        for (long k : gen.rivers.basins.nearCells(x, z, 1000)) {
            var c = gen.rivers.basins.cell(k);
            if (c.ocean) continue;
            var net = gen.rivers.net(c.ci, c.cj);
            System.out.printf("basin %d,%d outlet=%b terminal=%b L=%.2f reaches %d lakes %d exit %s%n", c.ci, c.cj, c.outlet,
                c.terminal, c.L, net.count, net.lakes.size(), net.exitLevel);
            int shown = 0;
            for (int s = 0; s < net.count && shown < 40; s++) {
                int q = (s * net.NPT + net.NPT / 2) * 2;
                double px = net.pts[q], pz = net.pts[q + 1];
                if (Math.hypot(px - x, pz - z) > r) continue;
                shown++;
                int q0 = s * net.NPT * 2, q1 = (s * net.NPT + net.NPT - 1) * 2;
                System.out.printf("  reach %d (%.0f,%.0f)->(%.0f,%.0f) w %.1f..%.1f z %.1f..%.1f acc %.1f head %d%n", s,
                    net.pts[q0], net.pts[q0 + 1], net.pts[q1], net.pts[q1 + 1], net.w0[s], net.w1[s], net.z0[s], net.z1[s],
                    net.acc[s], net.head[s]);
            }
        }
    }

    /** Times map tiles per layer and level, cold and warm (VIRIDIUM_MAPTIME = "layer,level,tx,tz"). */
    @Test
    @EnabledIfEnvironmentVariable(named = "VIRIDIUM_MAPTIME", matches = ".+")
    void mapTileTime() {
        WorldGen gen = new WorldGen(new GenParams());
        String[] a = System.getenv("VIRIDIUM_MAPTIME").split(",");
        var r = new net.junedev.viridium.worldgen.terrain.map.MapTileRenderer(gen);
        var layer = net.junedev.viridium.worldgen.terrain.map.MapLayer.byId(a[0]);
        int level = Integer.parseInt(a[1]), tx = Integer.parseInt(a[2]), tz = Integer.parseInt(a[3]);
        for (int pass = 0; pass < 3; pass++) {
            long b0 = gen.rivers.buildCount(), t0 = System.nanoTime();
            r.render(layer, level, tx, tz, 128, false);
            System.out.printf("pass %d: %.0f ms, %d river nets built (%.0f ms building in total so far)%n", pass,
                (System.nanoTime() - t0) / 1e6, gen.rivers.buildCount() - b0, gen.rivers.buildNanos() / 1e6);
        }
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
