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

    /**
     * Lists confluences of a tributary (half-width >= 3) into a large river (>= 12), worst drop first: the water level
     * at the end of the tributary versus the main river beside it (VIRIDIUM_CONFLUENCES = half size in blocks).
     */
    @Test
    @EnabledIfEnvironmentVariable(named = "VIRIDIUM_CONFLUENCES", matches = ".+")
    void confluences() {
        WorldGen gen = new WorldGen(new GenParams());
        double half = Double.parseDouble(System.getenv("VIRIDIUM_CONFLUENCES"));
        java.util.Set<Long> seen = new java.util.HashSet<>();
        double B = gen.rivers.basins.size();
        java.util.List<double[]> out = new java.util.ArrayList<>();
        GenPoint o = new GenPoint();
        for (double z = -half; z <= half; z += B / 2) for (double x = -half; x <= half; x += B / 2) {
            long k = gen.rivers.basins.cellAt(x, z);
            if (!seen.add(k)) continue;
            var c = gen.rivers.basins.cell(k);
            if (c.ocean) continue;
            var net = gen.rivers.net(c.ci, c.cj);
            for (int s = 0; s < net.count; s++) {
                if (net.w1[s] < 3) continue;
                int q = (s * net.NPT + net.NPT - 1) * 2;
                double ex = net.pts[q], ey = net.pts[q + 1];
                // the reach of the river the tributary ends in: nearest other reach that is much wider
                int i = (int) Math.floor((ex - net.x0) / net.g), j = (int) Math.floor((ey - net.y0) / net.g);
                double bestD = 1e9, mainZ = 0, mainW = 0;
                for (int dj = -2; dj <= 2; dj++) for (int di = -2; di <= 2; di++) {
                    int ii = i + di, jj = j + dj;
                    if (ii < 0 || jj < 0 || ii >= net.nx || jj >= net.ny) continue;
                    int sl = net.slot[jj * net.nx + ii];
                    if (sl < 0 || sl == s || net.w1[sl] < 12 || net.w1[sl] < 3 * net.w1[s]) continue;
                    for (int t = 0; t < net.NPT; t++) {
                        int qq = (sl * net.NPT + t) * 2;
                        double d = Math.hypot(net.pts[qq] - ex, net.pts[qq + 1] - ey);
                        if (d < bestD) {
                            bestD = d;
                            double u = (double) t / (net.NPT - 1);
                            mainZ = net.z0[sl] + (net.z1[sl] - net.z0[sl]) * u;
                            mainW = net.w0[sl] + (net.w1[sl] - net.w0[sl]) * u;
                        }
                    }
                }
                if (bestD > 20) continue;
                out.add(new double[] { net.z0[s] - mainZ, ex, ey, net.w1[s], mainW, net.z0[s], net.z1[s], mainZ });
            }
        }
        out.sort((p, r) -> Double.compare(r[0], p[0]));
        System.out.println("confluences: " + out.size());
        for (int n = 0; n < Math.min(12, out.size()); n++) {
            double[] v = out.get(n);
            System.out.printf("  drop %.1f at %.0f,%.0f trib hw %.1f main hw %.1f, trib z %.1f..%.1f main z %.1f%n", v[0], v[1],
                v[2], v[3], v[4], v[5], v[6], v[7]);
        }
        int nb = 0;
        for (double[] v : out) if (v[0] > 3) nb++;
        System.out.println("  drop > 3 blocks over the last reach: " + nb);
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
            for (int q = 0; q < net.nMouth; q++) {
                int c0 = net.dmc[q * 2], ncn = net.dmc[q * 2 + 1];
                if (ncn == 0) continue;
                System.out.printf("  delta of mouth %d: R %.0f, plain %.0f, %d branches%n", q, net.mouths[q * net.MS + 4],
                    net.dbox[q * 5 + 4], ncn);
                for (int cc = c0; cc < c0 + ncn; cc++) {
                    int v0 = net.dch[cc * 2], vn = net.dch[cc * 2 + 1], D = net.DV;
                    int a0 = v0 * D, a1 = (v0 + vn - 1) * D;
                    System.out.printf("    branch %d: %d vertices (%.0f,%.0f)->(%.0f,%.0f) w %.1f->%.1f z %.1f->%.1f tip %.0f%n", cc,
                        vn, net.dv[a0], net.dv[a0 + 1], net.dv[a1], net.dv[a1 + 1], net.dv[a0 + 2], net.dv[a1 + 2],
                        net.dv[a0 + 3], net.dv[a1 + 3], net.dv[a0 + 4]);
                }
            }
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

    /**
     * Block-resolution picture of the generated water around a point (VIRIDIUM_WATERPNG = "x,z,half"): ground shaded
     * by height, water coloured by its level (bands every block), red where a water column stands 1+ blocks above a
     * neighbouring water column, magenta where it stands above dry ground. Written to build/reports/map/water_x_z.png.
     */
    @Test
    @EnabledIfEnvironmentVariable(named = "VIRIDIUM_WATERPNG", matches = ".+")
    void waterPicture() throws java.io.IOException {
        WorldGen gen = new WorldGen(new GenParams());
        var planner = new net.junedev.viridium.worldgen.terrain.column.ColumnPlanner(gen,
            net.junedev.viridium.worldgen.terrain.column.BiomeMapper.FROM_STAGE);
        String[] a = System.getenv("VIRIDIUM_WATERPNG").split(",");
        int cx = Integer.parseInt(a[0]), cz = Integer.parseInt(a[1]), half = Integer.parseInt(a[2]);
        int n = 2 * half;
        int[] surf = new int[(n + 2) * (n + 2)], wat = new int[(n + 2) * (n + 2)];
        java.util.Map<Long, net.junedev.viridium.worldgen.terrain.column.ColumnPlan> plans = new java.util.HashMap<>();
        int x0 = cx - half - 1, z0 = cz - half - 1;
        for (int j = 0; j < n + 2; j++) for (int i = 0; i < n + 2; i++) {
            int bx = x0 + i, bz = z0 + j;
            long key = ((long) (bx >> 4) << 32) | ((bz >> 4) & 0xffffffffL);
            var p = plans.computeIfAbsent(key, k -> planner.plan(bx >> 4, bz >> 4));
            int idx = net.junedev.viridium.worldgen.terrain.column.ColumnPlan.index(bx & 15, bz & 15);
            surf[j * (n + 2) + i] = p.surfaceY[idx];
            wat[j * (n + 2) + i] = p.waterY[idx];
        }
        final int NONE = net.junedev.viridium.worldgen.terrain.column.ColumnPlan.NONE, W = n + 2;
        var img = new java.awt.image.BufferedImage(n, n, java.awt.image.BufferedImage.TYPE_INT_RGB);
        int steps = 0, walls = 0;
        for (int j = 1; j <= n; j++) for (int i = 1; i <= n; i++) {
            int k = j * W + i, s = surf[k], w = wat[k];
            int c;
            if (w == NONE || w <= s) {
                int hs = surf[k - 1] - surf[k + 1] + surf[k - W] - surf[k + W];
                int g = Math.max(30, Math.min(230, 120 + (s - 63) / 2 + hs * 12));
                c = (g << 16) | (Math.min(255, g + 20) << 8) | g;
            } else {
                int band = ((w % 4) + 4) % 4;
                c = new int[] { 0x1e50c8, 0x2a6ae0, 0x3a84f0, 0x5aa0ff }[band];
                if (w <= 63) c = 0x103070;
                boolean step = false, wall = false;
                for (int d : new int[] { 1, -1, W, -W }) {
                    int wn = wat[k + d], sn = surf[k + d];
                    if (wn != NONE && wn > sn) {
                        if (w - wn >= 1) step = true;
                    } else if (w > sn) {
                        wall = true;
                        if (walls < 6) System.out.printf("  wall at %d,%d water %d, dry neighbour %d,%d top %d%n",
                            x0 + i, z0 + j, w, x0 + i + (d == 1 ? 1 : d == -1 ? -1 : 0),
                            z0 + j + (d == W ? 1 : d == -W ? -1 : 0), sn);
                    }
                }
                if (step) {
                    c = 0xff3030;
                    steps++;
                }
                if (wall) {
                    c = 0xff30ff;
                    walls++;
                }
            }
            img.setRGB(i - 1, j - 1, c);
        }
        java.io.File dir = new java.io.File("build/reports/map");
        dir.mkdirs();
        java.io.File f = new java.io.File(dir, "water_" + cx + "_" + cz + ".png");
        javax.imageio.ImageIO.write(img, "png", f);
        System.out.println("water picture " + f + ": step edges " + steps + ", walls " + walls);
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
        if (a.length > 4) { // sweep: one warm-up tile, then each level from `level` to a[4], two neighbouring tiles each
            r.render(layer, 3, 0, 0, 128, false);
            for (int lv = level; lv <= Integer.parseInt(a[4]); lv++) {
                long t0 = System.nanoTime();
                r.render(layer, lv, tx, tz, 128, false);
                long t1 = System.nanoTime();
                r.render(layer, lv, tx + 1, tz, 128, false);
                System.out.printf("level %d: first tile %.0f ms, neighbour %.0f ms%n", lv, (t1 - t0) / 1e6,
                    (System.nanoTime() - t1) / 1e6);
            }
            return;
        }
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
