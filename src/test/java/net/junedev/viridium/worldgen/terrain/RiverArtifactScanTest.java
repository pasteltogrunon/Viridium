package net.junedev.viridium.worldgen.terrain;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;

import net.junedev.viridium.worldgen.terrain.column.TerrainLayout;
import net.junedev.viridium.worldgen.terrain.pipeline.GenPoint;
import net.junedev.viridium.worldgen.terrain.pipeline.Stage;
import net.junedev.viridium.worldgen.terrain.stages.RiverNet;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Looks for river artifacts over an area ({@code VIRIDIUM_ARTIFACTS="x0,z0,x1,z1"}, parameters from
 * {@code VIRIDIUM_PARAMS}): walks every reach of every network of the area and samples a cross-section at each of its
 * points, block by block. Diagnostic only. Reports, worst first:
 * <ul>
 * <li><b>buried</b>: a point of a reach's centre line with no water on it (the river is cut by the terrain);</li>
 * <li><b>step</b>: two neighbouring water columns whose water differs by 3+ blocks away from a waterfall (a wall of
 * water between two channels);</li>
 * <li><b>wall</b>: a water column standing 2+ blocks above the dry column next to it;</li>
 * <li><b>bar</b>: dry ground inside a channel standing 3+ blocks above the water next to it.</li>
 * </ul>
 */
class RiverArtifactScanTest {

    private static final String[] KINDS = { "buried", "step", "wall", "bar", "crossing" };

    /**
     * Reaches of one network whose centre lines cross away from their ends (a confluence is at the end of the
     * tributary):
     * two rivers running through each other. Value: the difference between their water levels there.
     */
    private static void crossings(RiverNet net, int ci, int cj, double x0, double z0, double x1, double z1,
        List<double[]> out) {
        final int NPT = RiverNet.NPT, SEG = RiverNet.SEG;
        for (int cell = 0; cell < net.nx * net.ny; cell++) {
            int s = net.slot[cell];
            if (s < 0 || net.skip[s] != 0) continue;
            int i = cell % net.nx, j = cell / net.nx;
            for (int dj = -2; dj <= 2; dj++) for (int di = -2; di <= 2; di++) {
                int ii = i + di, jj = j + dj;
                if (ii < 0 || jj < 0 || ii >= net.nx || jj >= net.ny) continue;
                int t = net.slot[jj * net.nx + ii];
                if (t <= s || net.skip[t] != 0) continue;
                for (int a = 0; a < SEG; a++) for (int b = 0; b < SEG; b++) {
                    int qa = (s * NPT + a) * 2, qb = (t * NPT + b) * 2;
                    double ax = net.pts[qa], ay = net.pts[qa + 1], ex = net.pts[qa + 2] - ax, ey = net.pts[qa + 3] - ay;
                    double bx = net.pts[qb], by = net.pts[qb + 1], fx = net.pts[qb + 2] - bx, fy = net.pts[qb + 3] - by;
                    double den = ex * fy - ey * fx;
                    if (Math.abs(den) < 1e-9) continue;
                    double u = ((bx - ax) * fy - (by - ay) * fx) / den, v = ((bx - ax) * ey - (by - ay) * ex) / den;
                    if (u < 0 || u > 1 || v < 0 || v > 1) continue;
                    double us = (a + u) / SEG, vs = (b + v) / SEG;
                    if (us < 0.07 || us > 0.93 || vs < 0.07 || vs > 0.93) continue; // joined end to end, or a
                                                                                    // confluence
                    double px = ax + ex * u, pz = ay + ey * u;
                    if (px < x0 || px > x1 || pz < z0 || pz > z1) continue;
                    double zs = net.z0[s] + (net.z1[s] - net.z0[s]) * us, zt = net.z0[t] + (net.z1[t] - net.z0[t]) * vs;
                    out.add(
                        new double[] { Math.abs(zs - zt), px, pz, Math.max(net.w0[s], net.w0[t]), zs, zt, ci, cj, s });
                }
            }
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "VIRIDIUM_ARTIFACTS", matches = ".+")
    void scan() {
        WorldGen gen = new WorldGen(TerrainViewTest.params());
        String[] a = System.getenv("VIRIDIUM_ARTIFACTS")
            .split(",");
        double x0 = Double.parseDouble(a[0]), z0 = Double.parseDouble(a[1]), x1 = Double.parseDouble(a[2]),
            z1 = Double.parseDouble(a[3]);
        double B = gen.rivers.basins.size();
        Set<Long> seen = new HashSet<>();
        List<List<double[]>> found = new ArrayList<>();
        for (int k = 0; k < KINDS.length; k++) found.add(Collections.synchronizedList(new ArrayList<>()));
        long[] cols = new long[1];
        int reaches = 0;
        for (double z = z0; z <= z1; z += B / 2) for (double x = x0; x <= x1; x += B / 2) {
            long key = gen.rivers.basins.cellAt(x, z);
            if (!seen.add(key)) continue;
            var c = gen.rivers.basins.cell(key);
            if (c.ocean) continue;
            RiverNet net = gen.rivers.net(c.ci, c.cj);
            reaches += net.count;
            crossings(net, c.ci, c.cj, x0, z0, x1, z1, found.get(4));
            IntStream.range(0, net.count)
                .parallel()
                .forEach(s -> {
                    if (net.skip[s] != 0) return;
                    // a waterfall is a step by design: its lip, curtain and pool are left out
                    if (net.fallIn.length > 0 && (net.fallIn[s] > 0 || net.lipOut[s] != 0)) return;
                    GenPoint o = new GenPoint();
                    long n = 0;
                    for (int t = 0; t <= RiverNet.SEG; t++) {
                        int q = (s * RiverNet.NPT + t) * 2;
                        double px = net.pts[q], pz = net.pts[q + 1];
                        if (px < x0 || px > x1 || pz < z0 || pz > z1) continue;
                        int qa = (s * RiverNet.NPT + Math.max(t - 1, 0)) * 2,
                            qb = (s * RiverNet.NPT + Math.min(t + 1, RiverNet.SEG)) * 2;
                        double tx = net.pts[qb] - net.pts[qa], tz = net.pts[qb + 1] - net.pts[qa + 1],
                            tl = Math.hypot(tx, tz);
                        if (tl < 1e-6) continue;
                        double ux = -tz / tl, uz = tx / tl, u = (double) t / RiverNet.SEG;
                        double hw = net.w0[s] + (net.w1[s] - net.w0[s]) * u,
                            zr = net.z0[s] + (net.z1[s] - net.z0[s]) * u;
                        // the centre line
                        gen.evaluate(Math.floor(px), Math.floor(pz), Stage.RIVERS, o);
                        n++;
                        boolean headFade = net.head[s] != 0 && u < 0.95;
                        boolean lip = net.lipOut.length > 0 && net.lipOut[s] != 0 && t >= RiverNet.SEG - 3;
                        if (o.river == 0 && o.lake == 0 && o.h > 0.5 && !headFade && !lip && o.delta < 0.05)
                            found.get(0)
                                .add(new double[] { o.h - zr, px, pz, hw, zr, o.h, c.ci, c.cj, s });
                        // the cross-section, block by block
                        int R = (int) Math.ceil(hw * 1.3 + 10);
                        int prevW = Integer.MIN_VALUE, prevS = 0, sinceFall = 99;
                        boolean prevFall = false, prevIn = false;
                        for (int d = -R; d <= R; d++) {
                            double sx = Math.floor(px + ux * d), sz = Math.floor(pz + uz * d);
                            gen.evaluate(sx, sz, Stage.RIVERS, o);
                            n++;
                            int surf = TerrainLayout.surfaceY(o.h), w = Integer.MIN_VALUE;
                            if (o.lake == 1) w = TerrainLayout.surfaceY(o.lakeZ);
                            if (o.river != 0) w = Math.max(w, TerrainLayout.surfaceY(o.riverWL));
                            if (surf < TerrainLayout.SEA_LEVEL - 1) w = Math.max(w, TerrainLayout.SEA_LEVEL - 1);
                            if (w <= surf) w = Integer.MIN_VALUE;
                            boolean fall = o.fall != 0, in = Math.abs(d) < hw;
                            sinceFall = fall ? 0 : sinceFall + 1;
                            if (d > -R && sinceFall > 8) {
                                boolean wet = w != Integer.MIN_VALUE, pWet = prevW != Integer.MIN_VALUE;
                                if (wet && pWet && Math.abs(w - prevW) >= 3) found.get(1)
                                    .add(new double[] { Math.abs(w - prevW), sx, sz, hw, zr, w, c.ci, c.cj, s });
                                else if (wet != pWet) {
                                    int wt = wet ? w : prevW, dry = wet ? prevS : surf;
                                    if (wt - dry >= 2) found.get(2)
                                        .add(new double[] { wt - dry, sx, sz, hw, zr, wt, c.ci, c.cj, s });
                                    else if (dry - wt >= 3 && in && prevIn) found.get(3)
                                        .add(new double[] { dry - wt, sx, sz, hw, zr, wt, c.ci, c.cj, s });
                                }
                            }
                            prevW = w;
                            prevS = surf;
                            prevFall = fall;
                            prevIn = in;
                        }
                    }
                    synchronized (cols) {
                        cols[0] += n;
                    }
                });
        }
        System.out.printf("artifact scan: %d basins, %d reaches, %d columns%n", seen.size(), reaches, cols[0]);
        for (int k = 0; k < KINDS.length; k++) {
            List<double[]> l = new ArrayList<>(found.get(k));
            l.sort((p, r) -> Double.compare(r[0], p[0]));
            // one line per place: the worst column within 48 blocks
            List<double[]> places = new ArrayList<>();
            int[] counts = new int[l.size()];
            outer: for (double[] v : l) {
                for (int i = 0; i < places.size(); i++) {
                    double[] p = places.get(i);
                    if (Math.abs(p[1] - v[1]) < 48 && Math.abs(p[2] - v[2]) < 48) {
                        counts[i]++;
                        continue outer;
                    }
                }
                counts[places.size()] = 1;
                places.add(v);
            }
            System.out.printf("%s: %d columns in %d places%n", KINDS[k], l.size(), places.size());
            for (int i = 0; i < Math.min(14, places.size()); i++) {
                double[] v = places.get(i);
                System.out.printf(
                    "   %5.1f blocks at %6.0f,%6.0f (%d columns) reach hw %.1f z %.1f, there %.1f, basin %d,%d reach %d%n",
                    v[0],
                    v[1],
                    v[2],
                    counts[i],
                    v[3],
                    v[4],
                    v[5],
                    (int) v[6],
                    (int) v[7],
                    (int) v[8]);
            }
        }
    }
}
