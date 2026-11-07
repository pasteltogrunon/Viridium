package net.junedev.viridium.worldgen.terrain;

import java.util.List;

import net.junedev.viridium.worldgen.terrain.caves.CaveCarver;
import net.junedev.viridium.worldgen.terrain.caves.CaveNoise;
import net.junedev.viridium.worldgen.terrain.caves.CaveSystems;
import net.junedev.viridium.worldgen.terrain.column.BiomeMapper;
import net.junedev.viridium.worldgen.terrain.column.ColumnPlan;
import net.junedev.viridium.worldgen.terrain.column.ColumnPlanCache;
import net.junedev.viridium.worldgen.terrain.column.ColumnPlanner;
import net.junedev.viridium.worldgen.terrain.core.GenParams;
import net.junedev.viridium.worldgen.terrain.crust.CrustModel;
import net.junedev.viridium.worldgen.terrain.crust.Lithology;
import net.junedev.viridium.worldgen.terrain.pipeline.GenPoint;
import net.junedev.viridium.worldgen.terrain.pipeline.GenScratch;
import net.junedev.viridium.worldgen.terrain.pipeline.Stage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Cave performance measurements and output hashes (teleport nets, cube prepare/at() costs, surface() per point). Run
 * alone (many WorldGens: the default 512 MiB test heap is tight): {@code VIRIDIUM_AUDIT=1 ./gradlew.bat cleanTest test
 * --tests '*CavePerfAuditTest*' -i}; {@code VIRIDIUM_AUDIT=noaq} turns the aquifers off for the breakdown.
 */
@EnabledIfEnvironmentVariable(named = "VIRIDIUM_AUDIT", matches = ".+")
class CavePerfAuditTest {

    /** Nets built and time for the plans of a 7x7 chunk area at a far point, with / without planned caves. */
    @Test
    void teleportNets() {
        String m = System.getenv("VIRIDIUM_AUDIT");
        for (int mode : new int[] { 2, 0, 1, 0 }) {
            long hash = 0;
            GenParams p = new GenParams();
            if (mode == 1) p.plannedCaves = 0;
            if (mode == 2) {
                p.plannedCaves = 0;
                p.caveEntrances = 0;
            }
            WorldGen gen = new WorldGen(p);
            ColumnPlanCache plans = new ColumnPlanCache(new ColumnPlanner(gen, BiomeMapper.FROM_STAGE));
            int[][] pts = { { 52000, 31000 }, { -47000, 66000 }, { 90800, -41000 } };
            long t0 = System.nanoTime();
            for (int[] q : pts) {
                int cx0 = q[0] >> 4, cz0 = q[1] >> 4;
                for (int dz = -3; dz <= 3; dz++) for (int dx = -3; dx <= 3; dx++)
                    hash = hash * 31 + java.util.Arrays.hashCode(plans.get(cx0 + dx, cz0 + dz).surfaceY);
            }
            long t1 = System.nanoTime();
            long nets = gen.rivers.buildCount();
            // then the caves of those columns: cubes from the surface down 3 cubes (collect -> region segments)
            for (int[] q : pts) {
                int cx0 = q[0] >> 4, cz0 = q[1] >> 4;
                for (int dz = -3; dz <= 3; dz++) for (int dx = -3; dx <= 3; dx++) {
                    ColumnPlan cp = plans.get(cx0 + dx, cz0 + dz);
                    for (int cy = (cp.maxTopY >> 4); cy > (cp.minSurfaceY >> 4) - 4; cy--) {
                        CaveCarver.Cube cv = gen.caves.prepare(cp, cx0 + dx, cy, cz0 + dz);
                        if (cv != null) for (int b = 0; b < 4096; b++)
                            hash = hash * 31 + cv.at(b & 15, b >> 8, (b >> 4) & 15, Lithology.LIMESTONE);
                    }
                }
            }
            long t2 = System.nanoTime();
            System.out.printf(
                "hash %x mode %d (planned %.0f mouths %.0f): plans %.0f ms, nets %d (%.0f ms build sum); +cube prepare %.0f ms, nets %d%n",
                hash,
                mode,
                p.plannedCaves,
                p.caveEntrances,
                (t1 - t0) / 1e6,
                nets,
                gen.rivers.buildNanos() / 1e6,
                (t2 - t1) / 1e6,
                gen.rivers.buildCount());
        }
    }

    @Test
    void breakdown() throws Exception {
        CaveTest.Sample sm = new CaveTest.Sample();
        if (System.getenv("VIRIDIUM_AUDIT")
            .contains("noaq")) sm.gen.params.aquifers = 0;
        int[][] cubes = new int[600][];
        java.util.Random r = new java.util.Random(9);
        for (int n = 0; n < cubes.length; n++) {
            int cx = r.nextInt(400) - 200, cz = r.nextInt(400) - 200;
            ColumnPlan p = sm.plans.get(cx, cz);
            cubes[n] = new int[] { cx, (p.minSurfaceY >> 4) - 1 - r.nextInt(120), cz };
        }
        java.lang.reflect.Field fCell = CaveCarver.Cube.class.getDeclaredField("cell");
        fCell.setAccessible(true);
        java.lang.reflect.Field fShapes = CaveCarver.Cube.class.getDeclaredField("shapes");
        fShapes.setAccessible(true);
        Lithology[][] rocks = new Lithology[cubes.length][4096];
        for (int n = 0; n < cubes.length; n++) {
            int[] c = cubes[n];
            ColumnPlan p = sm.plans.get(c[0], c[2]);
            CrustModel.Sampler s = sm.gen.crust.sampler();
            s.prepare(c[0] << 4, c[1] << 4, c[2] << 4);
            for (int ly = 0; ly < 16; ly++) for (int lz = 0; lz < 16; lz++) for (int lx = 0; lx < 16; lx++)
                rocks[n][(ly * 16 + lz) * 16 + lx] = s.at(p.crust, ColumnPlan.index(lx, lz), lx, ly, lz);
        }
        long prep = 0, at = 0;
        int nulls = 0, cellsOn = 0, cellsAll = 0, withShapes = 0, carved = 0;
        int[] bits = new int[5];
        for (int rep = 0; rep < 5; rep++) {
            prep = 0;
            at = 0;
            nulls = cellsOn = cellsAll = withShapes = carved = 0;
            java.util.Arrays.fill(bits, 0);
            for (int n = 0; n < cubes.length; n++) {
                int[] c = cubes[n];
                ColumnPlan p = sm.plans.get(c[0], c[2]);
                long t0 = System.nanoTime();
                CaveCarver.Cube cv = sm.gen.caves.prepare(p, c[0], c[1], c[2]);
                long t1 = System.nanoTime();
                prep += t1 - t0;
                if (cv == null) {
                    nulls++;
                    continue;
                }
                byte[] cell = (byte[]) fCell.get(cv);
                for (byte b : cell) {
                    cellsAll++;
                    if (b != 0) cellsOn++;
                    for (int q = 0; q < 5; q++) if ((b & (1 << q)) != 0) bits[q]++;
                }
                if (!((List<?>) fShapes.get(cv)).isEmpty()) withShapes++;
                Lithology[] rk = rocks[n];
                int cy = c[1];
                long t2 = System.nanoTime();
                for (int ly = 0; ly < 16; ly++) for (int lz = 0; lz < 16; lz++) for (int lx = 0; lx < 16; lx++) {
                    if ((cy << 4) + ly > p.surfaceY[ColumnPlan.index(lx, lz)]) continue;
                    if (cv.at(lx, ly, lz, rk[(ly * 16 + lz) * 16 + lx]) != CaveCarver.SOLID) carved++;
                }
                at += System.nanoTime() - t2;
            }
        }
        System.out.printf(
            "cubes %d: prepare %.4f ms/cube (null %d), at() %.4f ms/prepared cube; cells possible %.1f%% "
                + "(cavern %d huge %d tunnel %d fissure %d tube %d of %d), cubes with shapes %d, carved blocks %.1f%%%n",
            cubes.length,
            prep / 1e6 / cubes.length,
            nulls,
            at / 1e6 / Math.max(1, cubes.length - nulls),
            100.0 * cellsOn / cellsAll,
            bits[0],
            bits[1],
            bits[2],
            bits[3],
            bits[4],
            cellsAll,
            withShapes,
            100.0 * carved / (4096.0 * (cubes.length - nulls)));

        // perlin3 cost
        double sink = 0;
        for (int rep = 0; rep < 3; rep++) {
            long t0 = System.nanoTime();
            for (int i = 0; i < 2_000_000; i++) sink += CaveNoise.perlin3(i * 0.0137, i * 0.0071, i * 0.0233, 7);
            long t1 = System.nanoTime();
            if (rep == 2) System.out.printf("perlin3 %.4f us%n", (t1 - t0) / 1e3 / 2_000_000);
        }

        // surface(): warm regions / cells, points in the sample area
        GenPoint o = new GenPoint();
        GenScratch s = new GenScratch();
        int np = 200_000;
        double[] xs = new double[np], zs = new double[np];
        for (int i = 0; i < np; i++) {
            xs[i] = r.nextInt(6400) - 3200;
            zs[i] = r.nextInt(6400) - 3200;
        }
        sm.gen.evaluate(0, 0, Stage.EROSION, 1, o, s);
        for (int i = 0; i < np; i++) sm.gen.caves.systems.surface(xs[i], zs[i], o); // warm the regions
        for (int rep = 0; rep < 3; rep++) {
            long t0 = System.nanoTime();
            for (int i = 0; i < np; i++) sm.gen.caves.systems.surface(xs[i], zs[i], o);
            long t1 = System.nanoTime();
            for (int i = 0; i < np; i++) {
                o.L = 1;
                o.h = 50;
                o.river = 0;
                o.lake = 0;
                o.rock = net.junedev.viridium.worldgen.terrain.stages.Rock.LIMESTONE.ordinal();
                o.rock2 = o.rock;
                o.rockT = 1;
                o.riverD = 1e9;
                o.riverHW = 0;
                sm.gen.caves.karst.surface(xs[i], zs[i], o);
            }
            long t2 = System.nanoTime();
            if (rep == 2) System.out.printf(
                "systems.surface %.4f us/pt, karst.surface (worst case: all limestone) %.4f us/pt%n",
                (t1 - t0) / 1e3 / np,
                (t2 - t1) / 1e3 / np);
        }

        // cold region planning with warm nets: a fresh CaveSystems on the same gen
        CaveSystems fresh = new CaveSystems(sm.gen);
        long t0 = System.nanoTime();
        long b0 = sm.gen.rivers.buildCount();
        int nreg = 0;
        for (int rj = -2; rj <= 2; rj++) for (int ri = -2; ri <= 2; ri++) {
            fresh.region(ri, rj);
            nreg++;
        }
        long t1 = System.nanoTime();
        CaveSystems fresh2 = new CaveSystems(sm.gen);
        long t2 = System.nanoTime();
        for (int rj = -2; rj <= 2; rj++) for (int ri = -2; ri <= 2; ri++) fresh2.region(ri, rj);
        long t3 = System.nanoTime();
        System.out.printf(
            "region planning: first %.2f ms/region (%d nets built), again warm nets %.2f ms/region%n",
            (t1 - t0) / 1e6 / nreg,
            sm.gen.rivers.buildCount() - b0,
            (t3 - t2) / 1e6 / nreg);
        System.out.println("sink " + sink);
    }

    /** Near-surface cubes, column by column (as the game fills them): prepare cost. */
    @Test
    void nearSurface() {
        CaveTest.Sample sm = new CaveTest.Sample();
        java.util.Random r = new java.util.Random(3);
        int[][] cols = new int[200][];
        for (int n = 0; n < cols.length; n++) cols[n] = new int[] { r.nextInt(400) - 200, r.nextInt(400) - 200 };
        for (int[] c : cols) sm.plans.get(c[0], c[1]);
        long hash = 0, t = 0;
        int cubes = 0;
        for (int rep = 0; rep < 5; rep++) {
            t = 0;
            cubes = 0;
            for (int[] c : cols) {
                ColumnPlan p = sm.plans.get(c[0], c[1]);
                for (int cy = p.maxTopY >> 4; cy >= (p.minSurfaceY >> 4) - 3; cy--) {
                    long t0 = System.nanoTime();
                    CaveCarver.Cube cv = sm.gen.caves.prepare(p, c[0], cy, c[1]);
                    t += System.nanoTime() - t0;
                    cubes++;
                    if (rep == 0 && cv != null) for (int b = 0; b < 4096; b++)
                        hash = hash * 31 + cv.at(b & 15, b >> 8, (b >> 4) & 15, Lithology.BASALT);
                }
            }
        }
        System.out.printf("near-surface cubes %d: prepare %.4f ms/cube, hash %x%n", cubes, t / 1e6 / cubes, hash);
    }

    /** Hash of the 2D terrain around the collapse pits and a few karst areas, and surface() on contiguous points. */
    @Test
    void surfaceHash() {
        WorldGen gen = new WorldGen(new GenParams());
        double[][] at = { { -4943, -4719 }, { -4965, -2589 }, { 2614, 3915 }, { 5275, 3555 }, { 5950, 4411 },
            { 2369, 5523 }, { 5297, 5521 } };
        long hash = 0;
        GenPoint o = new GenPoint();
        for (double[] q : at) for (int dz = -60; dz <= 60; dz += 2) for (int dx = -60; dx <= 60; dx += 2) {
            gen.evaluate(q[0] + dx, q[1] + dz, Stage.RIVERS, o);
            hash = hash * 31 + Double.doubleToLongBits(o.h) + 7 * Double.doubleToLongBits(o.doline) + o.lake;
        }
        // karst: wide scan, dolines only (Karst.surface on synthetic limestone points)
        GenPoint k = new GenPoint();
        for (int z = -3000; z < 3000; z += 3) for (int x = -3000; x < 3000; x += 3) {
            k.L = 1;
            k.h = 50;
            k.river = 0;
            k.lake = 0;
            k.rock = net.junedev.viridium.worldgen.terrain.stages.Rock.LIMESTONE.ordinal();
            k.rock2 = k.rock;
            k.rockT = 1;
            k.riverD = 1e9;
            k.riverHW = 0;
            gen.caves.karst.surface(x + 0.25, z + 0.75, k);
            hash = hash * 31 + Double.doubleToLongBits(k.h);
        }
        // contiguous points (as a column plan or a map tile scans them)
        for (int rep = 0; rep < 3; rep++) {
            long t0 = System.nanoTime();
            int n = 0;
            for (int z = 0; z < 2048; z += 4) for (int x = 0; x < 2048; x++, n++) gen.caves.systems.surface(x, z, o);
            long t1 = System.nanoTime();
            if (rep == 2) System.out.printf("systems.surface contiguous %.4f us/pt%n", (t1 - t0) / 1e3 / n);
        }
        System.out.printf("surface hash %x%n", hash);
    }
}
