package net.junedev.viridium.worldgen.terrain;

import static org.junit.jupiter.api.Assertions.assertTrue;

import net.junedev.viridium.worldgen.terrain.caves.CaveCarver;
import net.junedev.viridium.worldgen.terrain.column.BiomeMapper;
import net.junedev.viridium.worldgen.terrain.column.ColumnPlan;
import net.junedev.viridium.worldgen.terrain.column.ColumnPlanCache;
import net.junedev.viridium.worldgen.terrain.column.ColumnPlanner;
import net.junedev.viridium.worldgen.terrain.core.GenParams;
import net.junedev.viridium.worldgen.terrain.crust.CrustModel;
import net.junedev.viridium.worldgen.terrain.crust.Lithology;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Caves without Minecraft: void fraction by depth and rock style, fluids, cost per cube, and the invariants (nothing
 * carved in the safety layer under water, no water standing next to air at the same height).
 */
class CaveTest {

    static final class Sample {

        final WorldGen gen = new WorldGen(new GenParams());
        final ColumnPlanCache plans = new ColumnPlanCache(new ColumnPlanner(gen, BiomeMapper.FROM_STAGE));
        final byte[] code = new byte[4096];
        final Lithology[] rock = new Lithology[4096];

        /** Fills code[] and rock[] for one cube (index (ly * 16 + lz) * 16 + lx); returns false for an air cube. */
        boolean cube(int cx, int cy, int cz) {
            ColumnPlan p = plans.get(cx, cz);
            if ((cy << 4) > p.maxTopY) return false;
            CrustModel.Sampler s = gen.crust.sampler();
            s.prepare(cx << 4, cy << 4, cz << 4);
            CaveCarver.Cube cv = gen.caves.prepare(p, cx, cy, cz);
            for (int ly = 0; ly < 16; ly++) for (int lz = 0; lz < 16; lz++) for (int lx = 0; lx < 16; lx++) {
                int i = ColumnPlan.index(lx, lz), y = (cy << 4) + ly, k = (ly * 16 + lz) * 16 + lx;
                if (y > p.surfaceY[i]) {
                    code[k] = -1; // above the ground
                    rock[k] = null;
                    continue;
                }
                Lithology r = s.at(p.crust, i, lx, ly, lz);
                rock[k] = r;
                code[k] = cv == null ? CaveCarver.SOLID : cv.at(lx, ly, lz, r);
            }
            return true;
        }
    }

    @Test
    void cavesAreSane() {
        Sample sm = new Sample();
        int[] bands = { 0, -64, -256, -640, -1200, -1800, -2048 };
        long[] solid = new long[bands.length], open = new long[bands.length], wet = new long[bands.length],
            lava = new long[bands.length];
        long[] styleAll = new long[CaveCarver.Style.values().length], styleOpen = new long[styleAll.length];
        int safety = 0, walls = 0, cubes = 0;
        long t0 = System.nanoTime();
        java.util.Random r = new java.util.Random(5);
        for (int n = 0; n < 220; n++) {
            int cx = r.nextInt(1200) - 600, cz = r.nextInt(1200) - 600;
            ColumnPlan p = sm.plans.get(cx, cz);
            int top = p.maxTopY >> 4;
            for (int cy = top; cy >= -126; cy -= 1 + r.nextInt(9)) {
                if (!sm.cube(cx, cy, cz)) continue;
                cubes++;
                for (int k = 0; k < 4096; k++) {
                    if (sm.code[k] < 0) continue;
                    int y = (cy << 4) + (k >> 8), i = ColumnPlan.index(k & 15, (k >> 4) & 15);
                    int band = 0;
                    while (band < bands.length - 2 && y < bands[band + 1]) band++;
                    CaveCarver.Style st = CaveCarver.style(sm.rock[k]);
                    styleAll[st.ordinal()]++;
                    if (sm.code[k] == CaveCarver.SOLID) {
                        solid[band]++;
                        continue;
                    }
                    open[band]++;
                    styleOpen[st.ordinal()]++;
                    if (sm.code[k] == CaveCarver.WATER) wet[band]++;
                    if (sm.code[k] == CaveCarver.LAVA) lava[band]++;
                    if (p.waterY[i] != ColumnPlan.NONE && y > p.surfaceY[i] - 8) safety++;
                    if (sm.code[k] == CaveCarver.WATER) {
                        int lx = k & 15, lz = (k >> 4) & 15;
                        int[][] d = { { 1, 0 }, { -1, 0 }, { 0, 1 }, { 0, -1 } };
                        for (int[] v : d) {
                            int nx = lx + v[0], nz = lz + v[1];
                            if (nx < 0 || nz < 0 || nx > 15 || nz > 15) continue;
                            if (sm.code[k - lx - lz * 16 + nx + nz * 16] == CaveCarver.AIR) walls++;
                        }
                    }
                }
            }
        }
        double ms = (System.nanoTime() - t0) / 1e6;
        StringBuilder sb = new StringBuilder(
            "caves (220 columns, " + cubes
                + " cubes, "
                + String.format("%.2f", ms / cubes)
                + " ms per cube incl. lithology):\n");
        for (int b = 0; b < bands.length - 1; b++) {
            long all = solid[b] + open[b];
            if (all == 0) continue;
            sb.append(
                String.format(
                    "  y %5d..%5d: void %5.2f%%  (water %4.1f%%, lava %4.1f%% of it)%n",
                    bands[b + 1],
                    bands[b],
                    100.0 * open[b] / all,
                    100.0 * wet[b] / Math.max(1, open[b]),
                    100.0 * lava[b] / Math.max(1, open[b])));
        }
        for (CaveCarver.Style st : CaveCarver.Style.values()) if (styleAll[st.ordinal()] > 0) sb.append(
            String.format(
                "  %-12s void %5.2f%% of %d blocks%n",
                st,
                100.0 * styleOpen[st.ordinal()] / styleAll[st.ordinal()],
                styleAll[st.ordinal()]));
        sb.append(
            String.format("  carved in the safety layer under water: %d, water next to air: %d%n", safety, walls));
        System.out.print(sb);
        assertTrue(safety == 0, "caves carved under water bodies");
    }

    /** Karst: dolines in limestone country and shafts that really are open. */
    @Test
    void karstDolinesAndShafts() {
        Sample sm = new Sample();
        net.junedev.viridium.worldgen.terrain.pipeline.GenPoint o = new net.junedev.viridium.worldgen.terrain.pipeline.GenPoint();
        int pts = 0, inDoline = 0, shafts = 0, open = 0;
        double deepest = 0;
        for (int z = -6000; z < 6000; z += 24) for (int x = -6000; x < 6000; x += 24) {
            sm.gen.evaluate(x, z, net.junedev.viridium.worldgen.terrain.pipeline.Stage.RIVERS, o);
            if (o.L < 0.02) continue;
            pts++;
            if (o.doline > 0.5) inDoline++;
            deepest = Math.max(deepest, o.doline);
        }
        for (int cj = -125; cj < 125 && shafts < 12; cj++) for (int ci = -125; ci < 125 && shafts < 12; ci++) {
            double[] s = sm.gen.caves.karst.shaft(ci, cj);
            if (s.length == 0) continue;
            shafts++;
            int x = (int) Math.floor(s[0]), z = (int) Math.floor(s[1]);
            ColumnPlan p = sm.plans.get(x >> 4, z >> 4);
            int sy = p.surfaceY[ColumnPlan.index(x & 15, z & 15)], y = sy - 12;
            if (y < s[4]) continue;
            sm.cube(x >> 4, y >> 4, z >> 4);
            byte c = sm.code[(((y & 15) * 16) + (z & 15)) * 16 + (x & 15)];
            if (c != CaveCarver.SOLID) open++;
        }
        int cand = 0, kOk = 0;
        for (int cj = -125; cj < 125; cj++) for (int ci = -125; ci < 125; ci++) {
            double[] d = sm.gen.caves.karst.dolineAt(ci, cj);
            if (d == null || d[4] <= 0) continue;
            cand++;
            sm.gen.evaluate(d[0], d[1], net.junedev.viridium.worldgen.terrain.pipeline.Stage.GEOLOGY, o);
            if (o.L > 0.02 && o.h0 > 3 && net.junedev.viridium.worldgen.terrain.caves.Karst.karstOf(o) > 0.4) kOk++;
        }
        System.out.printf("shaft candidates %d, on karst land %d%n", cand, kOk);
        System.out.printf(
            "karst: %.2f%% of land in dolines (deepest %.1f), %d shafts checked, %d open 12 blocks down%n",
            100.0 * inDoline / Math.max(1, pts),
            deepest,
            shafts,
            open);
        assertTrue(inDoline > 0, "no dolines");
        assertTrue(shafts == 0 || open > 0, "shafts not carved");
    }

    /** Planned systems: chambers by depth, collapse pits, galleries; centres really carved. */
    @Test
    void plannedSystems() {
        Sample sm = new Sample();
        int shallow = 0, mid = 0, deep = 0, pits = 0, segs = 0, carved = 0, checked = 0, lakes = 0;
        double widest = 0;
        StringBuilder where = new StringBuilder();
        for (int rj = -5; rj <= 5; rj++) for (int ri = -5; ri <= 5; ri++) {
            var r = sm.gen.caves.systems.region(ri, rj);
            for (var c : r.chambers) {
                if (c.y > -100) shallow++;
                else if (c.y > -800) mid++;
                else deep++;
                if (c.pitR > 0) {
                    pits++;
                    if (where.length() < 200) where.append(String.format(" pit@%.0f,%.0f", c.x, c.z));
                }
                if (c.lake == c.lake) lakes++;
                widest = Math.max(widest, 2 * Math.max(c.rx, c.rz));
                if (checked < 20) {
                    checked++;
                    int x = (int) c.x, y = (int) (c.floor + c.ry * 0.3), z = (int) c.z;
                    sm.cube(x >> 4, y >> 4, z >> 4);
                    byte code = sm.code[(((y & 15) * 16) + (z & 15)) * 16 + (x & 15)];
                    if (code > 0) carved++;
                    else System.out.printf(
                        "  not carved: chamber %.0f,%.0f,%.0f r %.0f/%.0f/%.0f floor %.0f code %d surface %d%n",
                        c.x,
                        c.y,
                        c.z,
                        c.rx,
                        c.ry,
                        c.rz,
                        c.floor,
                        code,
                        sm.plans.get(x >> 4, z >> 4).surfaceY[ColumnPlan.index(x & 15, z & 15)]);
                }
            }
        }
        System.out.printf(
            "cave systems (121 regions): chambers shallow %d, mid %d, deep %d; %d with a lake; %d collapse pits; widest %.0f blocks; %d/%d chamber centres carved;%s%n",
            shallow,
            mid,
            deep,
            lakes,
            pits,
            widest,
            carved,
            checked,
            where);
        assertTrue(shallow + mid + deep > 0, "no chambers");
        assertTrue(carved >= checked * 0.8, "chambers not carved");
    }

    /** Entrances: hillside mouths and the great entrances of the systems, open at the surface. */
    @Test
    void entrancesAreOpen() {
        Sample sm = new Sample();
        int cells = 0, mouths = 0, open = 0, checked = 0, great = 0, gOpen = 0, systems = 0, pitSys = 0, landSys = 0;
        for (int cj = -20; cj < 20; cj++) for (int ci = -20; ci < 20; ci++) {
            cells++;
            var l = sm.gen.caves.mouths.cell(ci, cj);
            if (l.isEmpty()) continue;
            mouths++;
            if (checked < 25) {
                checked++;
                if (openAt(sm, l.get(0))) open++;
            }
        }
        for (int rj = -5; rj <= 5; rj++) for (int ri = -5; ri <= 5; ri++) {
            var r = sm.gen.caves.systems.region(ri, rj);
            if (r.chambers.isEmpty()) continue;
            systems++;
            var top = r.chambers.get(0);
            for (var c : r.chambers) if (c.y > top.y) top = c;
            var oo = sm.gen.evaluate(
                top.x,
                top.z,
                net.junedev.viridium.worldgen.terrain.pipeline.Stage.EROSION,
                new net.junedev.viridium.worldgen.terrain.pipeline.GenPoint());
            if (top.pitR > 0) pitSys++;
            else if (oo.L > 0.05) landSys++;
            if (r.mouth.isEmpty()) continue;
            great++;
            if (openAt(sm, r.mouth.get(0))) gOpen++;
        }
        System.out.printf("systems with a pit %d, on land without pit %d%n", pitSys, landSys);
        System.out.printf(
            "entrances: %d hillside mouths in %d cells (%d/%d checked open); %d great entrances for %d systems (%d open)%n",
            mouths,
            cells,
            open,
            checked,
            great,
            systems,
            gOpen);
        assertTrue(mouths > 0 && open >= checked * 0.7, "hillside mouths missing or closed");
        assertTrue(great > 0 && gOpen >= great * 0.7, "great entrances missing or closed");
    }

    private static boolean openAt(Sample sm, net.junedev.viridium.worldgen.terrain.caves.CaveSystems.Segment s) {
        int x = (int) Math.floor(s.minX() + (s.maxX() - s.minX()) * 0.5),
            z = (int) Math.floor(s.minZ() + (s.maxZ() - s.minZ()) * 0.5);
        ColumnPlan p = sm.plans.get(x >> 4, z >> 4);
        int y = p.surfaceY[ColumnPlan.index(x & 15, z & 15)] - 2;
        for (int d = 0; d < 10; d++, y--) { // the first block below the ground that is part of the ramp
            sm.cube(x >> 4, y >> 4, z >> 4);
            if (sm.code[(((y & 15) * 16) + (z & 15)) * 16 + (x & 15)] > 0) return true;
        }
        return false;
    }

    /** Cost per cube: lithology alone vs lithology + caves, same cubes, warm. */
    @Test
    void caveCost() {
        Sample sm = new Sample();
        int[][] cubes = new int[600][];
        java.util.Random r = new java.util.Random(9);
        for (int n = 0; n < cubes.length; n++) {
            int cx = r.nextInt(400) - 200, cz = r.nextInt(400) - 200;
            ColumnPlan p = sm.plans.get(cx, cz);
            cubes[n] = new int[] { cx, (p.minSurfaceY >> 4) - 1 - r.nextInt(120), cz };
        }
        long lith = 0, cave = 0;
        for (int rep = 0; rep < 3; rep++) {
            long t0 = System.nanoTime();
            for (int[] c : cubes) {
                ColumnPlan p = sm.plans.get(c[0], c[2]);
                CrustModel.Sampler s = sm.gen.crust.sampler();
                s.prepare(c[0] << 4, c[1] << 4, c[2] << 4);
                for (int ly = 0; ly < 16; ly++) for (int lz = 0; lz < 16; lz++) for (int lx = 0; lx < 16; lx++)
                    sm.rock[(ly * 16 + lz) * 16 + lx] = s.at(p.crust, ColumnPlan.index(lx, lz), lx, ly, lz);
            }
            long t1 = System.nanoTime();
            for (int[] c : cubes) sm.cube(c[0], c[1], c[2]);
            long t2 = System.nanoTime();
            lith = t1 - t0;
            cave = t2 - t1;
        }
        System.out.printf(
            "crust cube: lithology %.3f ms, lithology + caves %.3f ms%n",
            lith / 1e6 / cubes.length,
            cave / 1e6 / cubes.length);
    }

    /** Vertical section: VIRIDIUM_CAVES=x0,x1,z,yTop,yBottom (# rock, space air, ~ water, ^ lava, . above ground). */
    @Test
    @EnabledIfEnvironmentVariable(named = "VIRIDIUM_CAVES", matches = ".+")
    void section() {
        Sample sm = new Sample();
        String[] a = System.getenv("VIRIDIUM_CAVES")
            .split(",");
        int x0 = Integer.parseInt(a[0]), x1 = Integer.parseInt(a[1]), z = Integer.parseInt(a[2]),
            yt = Integer.parseInt(a[3]), yb = Integer.parseInt(a[4]);
        int step = Math.max(1, (x1 - x0) / 180);
        for (int y = yt; y >= yb; y -= step) {
            StringBuilder sb = new StringBuilder(String.format("%5d ", y));
            for (int x = x0; x <= x1; x += step) {
                if (!sm.cube(x >> 4, y >> 4, z >> 4)) {
                    sb.append('.');
                    continue;
                }
                int k = (((y & 15) * 16) + (z & 15)) * 16 + (x & 15);
                byte c = sm.code[k];
                sb.append(
                    c < 0 ? '.'
                        : c == CaveCarver.SOLID ? '#' : c == CaveCarver.AIR ? ' ' : c == CaveCarver.WATER ? '~' : '^');
            }
            System.out.println(sb);
        }
    }
}
