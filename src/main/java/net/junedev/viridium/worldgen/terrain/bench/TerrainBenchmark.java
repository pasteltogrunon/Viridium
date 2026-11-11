package net.junedev.viridium.worldgen.terrain.bench;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import net.junedev.viridium.worldgen.terrain.GenStats;
import net.junedev.viridium.worldgen.terrain.WorldGen;
import net.junedev.viridium.worldgen.terrain.caves.CaveCarver;
import net.junedev.viridium.worldgen.terrain.column.BiomeMapper;
import net.junedev.viridium.worldgen.terrain.column.ColumnPlan;
import net.junedev.viridium.worldgen.terrain.column.ColumnPlanCache;
import net.junedev.viridium.worldgen.terrain.column.ColumnPlanner;
import net.junedev.viridium.worldgen.terrain.column.TerrainLayout;
import net.junedev.viridium.worldgen.terrain.core.GenParams;
import net.junedev.viridium.worldgen.terrain.core.Noise;
import net.junedev.viridium.worldgen.terrain.crust.CrustModel;
import net.junedev.viridium.worldgen.terrain.crust.Lithology;
import net.junedev.viridium.worldgen.terrain.map.MapLayer;
import net.junedev.viridium.worldgen.terrain.map.MapTileRenderer;
import net.junedev.viridium.worldgen.terrain.pipeline.GenPoint;
import net.junedev.viridium.worldgen.terrain.pipeline.Stage;
import net.junedev.viridium.worldgen.terrain.stages.BasinGraph;
import net.junedev.viridium.worldgen.terrain.stages.RiverNet;
import net.junedev.viridium.worldgen.terrain.stages.RiverStage;

/**
 * Headless benchmark of the terrain pipeline (no Minecraft classes). Mirrors bench/run.js (per-stage marginal cost for
 * land and ocean with the river networks already cached, primitives, cold river network builds, map tiles) and adds
 * the real case: planning contiguous 16x16 columns (single- and multi-threaded), the cube mix of a column, crust
 * sampling, and the exact vs fast-field comparison. {@link #walk} is the game's own workload (teleport, then a flight
 * generating plans and cubes): the number to judge optimisations by.
 */
public final class TerrainBenchmark {

    /** Sample area: small enough that every plate's river network fits in the cache (like run.js). */
    private static final double AREA = 14000;

    private final GenParams base;
    private final int points;
    private final StringBuilder out = new StringBuilder();
    private double sink; // defeats dead-code elimination
    private List<ColumnPlan> lastPlans;

    public TerrainBenchmark(GenParams params, int points) {
        this.base = params;
        this.points = points;
    }

    public static void main(String[] args) {
        int points = args.length > 0 ? Integer.parseInt(args[0]) : 1500;
        System.out.print(new TerrainBenchmark(new GenParams(), points).run());
    }

    private WorldGen gen(boolean fast) {
        GenParams p = base.copy();
        p.fastFields = fast;
        return new WorldGen(p);
    }

    public String run() {
        line(
            "Viridium terrain benchmark · seed %d · %d land + %d ocean points · %d threads available",
            base.seed,
            points,
            points,
            Runtime.getRuntime()
                .availableProcessors());
        WorldGen exact = gen(false), fast = gen(true);
        double[][] pts = samplePoints(exact);
        // warm-up: JIT, hotspots, river networks and coarse fields (everything the real game keeps cached)
        for (int i = 0; i < 2; i++) {
            stageCosts(exact, pts, false, null);
            stageCosts(fast, pts, false, null);
        }
        line("");
        line(
            "Per-point cost with river networks cached (µs). Exact = 1:1 port of the JS; fast = cached coarse fields.");
        double[] ex = stageCosts(exact, pts, true, "exact");
        double[] fa = stageCosts(fast, pts, true, "fast");
        line("");
        line("%-34s %9s %9s      %9s %9s", "", "exact L", "exact O", "fast L", "fast O");
        line("%-34s %9.2f %9.2f   →  %9.2f %9.2f", "TOTAL per point (µs)", ex[0], ex[1], fa[0], fa[1]);
        line(
            "%-34s %9.2f %9.2f   →  %9.2f %9.2f",
            "≈ per 16x16 column (ms, 1 thread)",
            ex[0] * 0.256,
            ex[1] * 0.256,
            fa[0] * 0.256,
            fa[1] * 0.256);
        accuracy(exact, fast, pts[0]);
        primitives(exact);
        riverNets();
        columns(fast);
        crust(fast);
        tiles(fast);
        teleport();
        walk();
        line("(sink %.3f)", sink);
        return out.toString();
    }

    /**
     * Land and ocean points (x, z interleaved) inside the sample area, in 8x8 patches 2 blocks apart around random
     * centres: generation walks contiguous columns, so the cached fields (lattices of 128 to 2048 blocks) are warm.
     * Fully
     * random points would build a new erosion lattice tile per point (~800 µs) and measure only that.
     */
    private double[][] samplePoints(WorldGen gen) {
        Random r = new Random(42);
        double[] land = new double[points * 2], sea = new double[points * 2];
        int nl = 0, ns = 0;
        GenPoint o = new GenPoint();
        while (nl < points || ns < points) {
            double cx = Math.round((r.nextDouble() - 0.5) * AREA), cz = Math.round((r.nextDouble() - 0.5) * AREA);
            for (int q = 0; q < 64; q++) {
                double x = cx + (q & 7) * 2, z = cz + (q >> 3) * 2;
                gen.evaluate(x, z, Stage.RELIEF, o);
                if (o.h0 >= 0 && nl < points) {
                    land[2 * nl] = x;
                    land[2 * nl++ + 1] = z;
                } else if (o.h0 < 0 && ns < points) {
                    sea[2 * ns] = x;
                    sea[2 * ns++ + 1] = z;
                }
            }
        }
        return new double[][] { land, sea };
    }

    private double timePerPoint(WorldGen gen, double[] pts, Stage stage) {
        GenPoint o = new GenPoint();
        int n = pts.length / 2;
        long best = Long.MAX_VALUE;
        for (int rep = 0; rep < 3; rep++) {
            long t0 = System.nanoTime();
            for (int i = 0; i < n; i++) {
                gen.evaluate(pts[2 * i], pts[2 * i + 1], stage, o);
                sink += o.h;
            }
            best = Math.min(best, System.nanoTime() - t0);
        }
        return best / 1000.0 / n;
    }

    /** @return total µs per point {land, ocean} */
    private double[] stageCosts(WorldGen gen, double[][] pts, boolean print, String label) {
        if (print) {
            line("");
            line("%-34s %9s %9s", "Stage marginal cost (" + label + ")", "land", "ocean");
        }
        double prevL = 0, prevS = 0;
        for (Stage s : Stage.values()) {
            double tl = timePerPoint(gen, pts[0], s), ts = timePerPoint(gen, pts[1], s);
            if (print) line("%-34s %9.2f %9.2f", s.name(), tl - prevL, ts - prevS);
            prevL = tl;
            prevS = ts;
        }
        return new double[] { prevL, prevS };
    }

    /** How far the fast fields move the result away from the exact port. */
    private void accuracy(WorldGen exact, WorldGen fast, double[] land) {
        GenPoint a = new GenPoint(), b = new GenPoint();
        int n = land.length / 2, biomeDiff = 0, riverDiff = 0;
        double dT = 0, mT = 0, dP = 0, mP = 0, dh = 0, mh = 0;
        for (int i = 0; i < n; i++) {
            exact.evaluate(land[2 * i], land[2 * i + 1], Stage.BIOMES, a);
            fast.evaluate(land[2 * i], land[2 * i + 1], Stage.BIOMES, b);
            double t = Math.abs(a.T - b.T), p = Math.abs(a.P - b.P) / Math.max(1, a.P), h = Math.abs(a.h - b.h);
            dT += t;
            mT = Math.max(mT, t);
            dP += p;
            mP = Math.max(mP, p);
            dh += h;
            mh = Math.max(mh, h);
            if (a.biome != b.biome) biomeDiff++;
            if (a.river != b.river) riverDiff++;
        }
        line("");
        line("Fast vs exact on %d land points:", n);
        line(
            "  |ΔT| mean %.3f max %.2f °C · |ΔP| mean %.2f%% max %.1f%% · |Δh| mean %.3f max %.2f blocks",
            dT / n,
            mT,
            100 * dP / n,
            100 * mP,
            dh / n,
            mh);
        line(
            "  dominant biome differs %.2f%% · river flag differs %.2f%%",
            100.0 * biomeDiff / n,
            100.0 * riverDiff / n);
    }

    private void primitives(WorldGen gen) {
        line("");
        line("Primitives:");
        int n = 200_000;
        Random r = new Random(7);
        double[] xs = new double[n];
        for (int i = 0; i < n; i++) xs[i] = (r.nextDouble() - 0.5) * 1e5;
        long t0 = System.nanoTime();
        for (int i = 0; i < n; i++) sink += Noise.perlin(xs[i] / 97, xs[n - 1 - i] / 97, 1234);
        line("  %-28s %8.4f µs", "perlin()", (System.nanoTime() - t0) / 1000.0 / n);
        t0 = System.nanoTime();
        for (int i = 0; i < n; i++) sink += Noise.fbm(xs[i] / 311, xs[n - 1 - i] / 311, 1234, 4);
        line("  %-28s %8.4f µs", "fbm() 4 octaves", (System.nanoTime() - t0) / 1000.0 / n);
        int m = 20_000;
        t0 = System.nanoTime();
        for (int i = 0; i < m; i++) sink += gen.heightAt(xs[i] / 10, xs[n - 1 - i] / 10, false);
        line("  %-28s %8.4f µs", "heightAt() coarse", (System.nanoTime() - t0) / 1000.0 / m);
    }

    private void riverNets() {
        line("");
        line("River networks (per drainage basin, cold build, parallel):");
        WorldGen g = gen(true);
        long totalBytes = 0;
        int built = 0;
        long t0 = System.nanoTime();
        for (int k = 0; k < 6; k++) {
            long ck = g.rivers.basins.cellAt(2000 + k * 3100, 1500);
            long t = System.nanoTime();
            RiverNet net = g.rivers.net(BasinGraph.keyA(ck), BasinGraph.keyB(ck));
            totalBytes += net.bytes;
            built++;
            if (built <= 3) line(
                "  %6.0f ms  %d cells, %d reaches, %d mouths, %d lake cells, ~%d KiB",
                (System.nanoTime() - t) / 1e6,
                net.nx * net.ny,
                net.count,
                net.nMouth,
                net.lakes.size(),
                net.bytes >> 10);
        }
        line(
            "  %d networks: %.0f ms each, ~%.1f MiB each (cache keeps 64)",
            built,
            (System.nanoTime() - t0) / 1e6 / built,
            totalBytes / (double) built / (1 << 20));
    }

    /** The real case: contiguous columns, like a player exploring. */
    private void columns(WorldGen gen) {
        line("");
        line("Column plans, full pipeline + crust profile (contiguous columns, fast fields, nets warm):");
        ColumnPlanner planner = new ColumnPlanner(gen, BiomeMapper.FROM_STAGE);
        int side = 16;
        int cx0 = 37, cz0 = -12;
        for (int dz = 0; dz < side; dz++) for (int dx = 0; dx < side; dx++) planner.plan(cx0 + dx, cz0 + dz); // warm
        long t0 = System.nanoTime();
        List<ColumnPlan> plans = new ArrayList<>(side * side);
        for (int dz = 0; dz < side; dz++) for (int dx = 0; dx < side; dx++) plans.add(planner.plan(cx0 + dx, cz0 + dz));
        double single = (System.nanoTime() - t0) / 1e6 / (side * side);
        line("  1 thread: %.3f ms/column (%d columns)", single, side * side);

        int threads = Math.max(
            1,
            Runtime.getRuntime()
                .availableProcessors() - 1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            for (int dz = 0; dz < side; dz++) for (int dx = 0; dx < side; dx++) planner.plan(cx0 + side + dx, cz0 + dz);
            List<Future<ColumnPlan>> fs = new ArrayList<>();
            t0 = System.nanoTime();
            for (int dz = 0; dz < side; dz++) for (int dx = 0; dx < side; dx++) {
                int x = cx0 + side + dx, z = cz0 + side + dz;
                fs.add(pool.submit(() -> planner.plan(x, z)));
            }
            for (Future<ColumnPlan> f : fs) f.get();
            double wall = (System.nanoTime() - t0) / 1e6;
            line(
                "  %d threads: %.3f ms/column wall (%.0f columns/s)",
                threads,
                wall / fs.size(),
                fs.size() / (wall / 1000));
        } catch (Exception e) {
            line("  multithreaded run failed: %s", e);
        } finally {
            pool.shutdownNow();
        }

        long air = 0, solid = 0, mixed = 0;
        int cubesPerColumn = (TerrainLayout.MAX_Y - TerrainLayout.MIN_Y) >> 4;
        for (ColumnPlan p : plans) {
            int top = p.maxTopY >> 4;
            int solidTop = (p.minSurfaceY - TerrainLayout.SURFACE_RULE_DEPTH - 15) >> 4;
            for (int cy = TerrainLayout.MIN_Y >> 4; cy < TerrainLayout.MAX_Y >> 4; cy++) {
                if (cy > top) air++;
                else if (cy <= solidTop) solid++;
                else mixed++;
            }
        }
        long total = (long) plans.size() * cubesPerColumn;
        line(
            "  cubes per column in [%d, %d): %d → air %.1f%%, crust only %.1f%%, surface-crossing %.1f%% (%.2f/column)",
            TerrainLayout.MIN_Y,
            TerrainLayout.MAX_Y,
            cubesPerColumn,
            100.0 * air / total,
            100.0 * solid / total,
            100.0 * mixed / total,
            (double) mixed / plans.size());
        lastPlans = plans;
    }

    /** Crust lithology sampling: the per-block cost of a deep cube (without Minecraft block storage). */
    private void crust(WorldGen gen) {
        if (lastPlans == null || lastPlans.isEmpty()) return;
        CrustModel.Sampler s = gen.crust.sampler();
        ColumnPlan p = lastPlans.get(0);
        int cubes = 0;
        for (int rep = 0; rep < 2; rep++) for (int cy = -60; cy < 0; cy++) { // warm
            s.prepare(p.chunkX << 4, cy << 4, p.chunkZ << 4);
            for (int i = 0; i < 256; i++) for (int ly = 0; ly < 16; ly++) sink += s.at(p.crust, i, i & 15, ly, i >> 4)
                .ordinal();
        }
        long t0 = System.nanoTime();
        for (int cy = -120; cy < 0; cy++) {
            s.prepare(p.chunkX << 4, cy << 4, p.chunkZ << 4);
            for (int i = 0; i < 256; i++) for (int ly = 0; ly < 16; ly++) sink += s.at(p.crust, i, i & 15, ly, i >> 4)
                .ordinal();
            cubes++;
        }
        line("");
        line(
            "Crust: %.3f ms per deep 16³ cube (lithology only, no block storage)",
            (System.nanoTime() - t0) / 1e6 / cubes);
    }

    private void tiles(WorldGen gen) {
        line("");
        line("Map tiles 128x128 at level 4 (16 blocks/px), fast fields:");
        coldTile();
        line("  every layer on a warm tile, evaluated on its own (nets cached after the first):");
        MapTileRenderer r = new MapTileRenderer(gen);
        for (MapLayer l : MapLayer.ALL) {
            r.render(l, 4, 0, 0, 32, false); // warm
            MapTileRenderer.clearSamples(); // a layer switch would reuse the tile's evaluation
            long t0 = System.nanoTime();
            int[] px = r.render(l, 4, 0, 0, 128, false);
            sink += px[0];
            line("    %-24s %6.0f ms", l.name, (System.nanoTime() - t0) / 1e6);
        }
    }

    /**
     * What the player waits for: the first tile of an unexplored area (fresh pipeline, its river networks built on the
     * map worker's own thread, like {@code MapTileCache}), then a second layer of the same tile.
     */
    private void coldTile() {
        int tx = 20, tz = 15;
        ExecutorService ex = Executors.newSingleThreadExecutor(task -> new Thread(() -> {
            RiverStage.buildSeriallyOnThisThread();
            task.run();
        }, "Viridium bench map worker"));
        try {
            String s = ex.submit(() -> {
                WorldGen g = gen(true);
                MapTileRenderer r = new MapTileRenderer(g);
                MapTileRenderer.clearSamples();
                long nets = g.rivers.buildCount();
                long t0 = System.nanoTime();
                int[] a = r.render(MapLayer.COMPOSITE, 4, tx, tz, 128, false);
                double cold = (System.nanoTime() - t0) / 1e6;
                nets = g.rivers.buildCount() - nets;
                t0 = System.nanoTime();
                int[] b = r.render(MapLayer.HEIGHT, 4, tx, tz, 128, false);
                double warm = (System.nanoTime() - t0) / 1e6;
                sink += a[0] + b[0];
                return String.format(
                    "  cold tile (%d,%d), fresh pipeline: %s %6.0f ms (%d nets built) · then %s %5.0f ms",
                    tx,
                    tz,
                    MapLayer.COMPOSITE.name,
                    cold,
                    nets,
                    MapLayer.HEIGHT.name,
                    warm);
            })
                .get();
            line("%s", s);
        } catch (Exception e) {
            line("  cold tile failed: %s", e);
        } finally {
            ex.shutdownNow();
        }
    }

    // ------------------------------------------------------------------ walk: the game's own workload

    /** Horizontal view distance (chunks) and cubes generated below and above the surface of each column. */
    private static final int WALK_VIEW = 10, WALK_CUBES = 4;
    private static final double WALK_SPEED = 10, WALK_SECONDS = 60, TICK = 0.05;

    /** CPU spent on one column, split by kind (ns). */
    private static final class ColumnCost {

        long plan, cubes, nets, caves;
        int cubeCount;
    }

    /**
     * The game's own workload, so every optimisation is judged on what a player waits for. A fresh pipeline at a far
     * point: a teleport (the view window generated nearest first by the prefetch threads), then a 60 s flight at
     * 10 blocks/s along a gently curving path. Every column entering the view gets its plan and the cubes from 4 below
     * its lowest surface to 4 above its highest top (lithology + caves, like CubeFiller minus block storage, surface
     * rules and flora). The flight runs flat out on one thread with serial river builds, so the CPU of each column
     * splits exactly into plan, cubes, river networks (built on demand, nested in plans and cave planning) and cave
     * region planning.
     */
    public void walk() {
        line("");
        WorldGen g = gen(true);
        double[] p0 = landNear(g, 52000, 31000);
        ColumnPlanCache plans = new ColumnPlanCache(new ColumnPlanner(g, BiomeMapper.FROM_STAGE));
        line(
            "Walk: seed %d from (%.0f, %.0f), %.0f blocks/s for %.0f s, view %d chunks, cubes ±%d around the surface",
            base.seed,
            p0[0],
            p0[1],
            WALK_SPEED,
            WALK_SECONDS,
            WALK_VIEW,
            WALK_CUBES);
        GenStats.reset();

        // teleport: the whole window, nearest first, on prefetch-like threads (river builds parallel, as in the game)
        int threads = Math.max(
            1,
            Math.min(
                8,
                Runtime.getRuntime()
                    .availableProcessors() / 3));
        int pcx = (int) Math.floor(p0[0]) >> 4, pcz = (int) Math.floor(p0[1]) >> 4;
        Set<Long> done = new HashSet<>();
        List<int[]> window = window(pcx, pcz, done);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        long t0 = System.nanoTime();
        double centre = -1, inner = -1;
        int cubes = 0;
        try {
            List<Future<ColumnCost>> fs = new ArrayList<>();
            for (int[] c : window) fs.add(pool.submit(() -> column(g, plans, c[0], c[1], false)));
            for (int i = 0; i < fs.size(); i++) {
                cubes += fs.get(i)
                    .get().cubeCount;
                double ms = (System.nanoTime() - t0) / 1e6;
                if (i == 0) centre = ms;
                if (window.get(i)[2] == 4 && (i + 1 == fs.size() || window.get(i + 1)[2] > 4)) inner = ms;
            }
        } catch (Exception e) {
            line("  teleport failed: %s", e);
        } finally {
            pool.shutdownNow();
        }
        line(
            "  teleport (%d threads, cold): centre %.0f ms · 9x9 %.0f ms · %dx%d %.0f ms · %d nets · %d cubes",
            threads,
            centre,
            inner,
            2 * WALK_VIEW + 1,
            2 * WALK_VIEW + 1,
            (System.nanoTime() - t0) / 1e6,
            g.rivers.buildCount(),
            cubes);

        // flight: one thread, flat out, exact attribution
        ExecutorService walker = Executors.newSingleThreadExecutor(task -> new Thread(() -> {
            RiverStage.buildSeriallyOnThisThread();
            task.run();
        }, "Viridium bench walker"));
        try {
            walker.submit(() -> fly(g, plans, p0, done))
                .get();
        } catch (Exception e) {
            line("  walk failed: %s", e);
        } finally {
            walker.shutdownNow();
        }
    }

    private void fly(WorldGen g, ColumnPlanCache plans, double[] p0, Set<Long> done) {
        GenStats.reset();
        long nets0 = g.rivers.buildCount();
        List<ColumnCost> costs = new ArrayList<>();
        double x = p0[0], z = p0[1], heading = new Random(base.seed).nextDouble() * 2 * Math.PI;
        int lastCx = Integer.MIN_VALUE, lastCz = Integer.MIN_VALUE;
        long t0 = System.nanoTime();
        int ticks = (int) Math.round(WALK_SECONDS / TICK);
        for (int t = 0; t < ticks; t++) {
            double a = heading + 0.6 * Math.sin(t * TICK / 15);
            x += Math.cos(a) * WALK_SPEED * TICK;
            z += Math.sin(a) * WALK_SPEED * TICK;
            int cx = (int) Math.floor(x) >> 4, cz = (int) Math.floor(z) >> 4;
            if (cx == lastCx && cz == lastCz) continue;
            lastCx = cx;
            lastCz = cz;
            for (int[] c : window(cx, cz, done)) costs.add(column(g, plans, c[0], c[1], true));
        }
        double wall = (System.nanoTime() - t0) / 1e9;
        int n = costs.size();
        if (n == 0) {
            line("  walk: no new columns");
            return;
        }
        long plan = 0, cubeNs = 0, nets = 0, caves = 0;
        int cubes = 0;
        double[] per = new double[n];
        for (int i = 0; i < n; i++) {
            ColumnCost c = costs.get(i);
            plan += c.plan;
            cubeNs += c.cubes;
            nets += c.nets;
            caves += c.caves;
            cubes += c.cubeCount;
            per[i] = (c.plan + c.cubes + c.nets + c.caves) / 1e6;
        }
        Arrays.sort(per);
        double k = 1e6 * n;
        line(
            "  walk (1 thread, serial river builds, flat out): %d new columns · %d cubes (%.1f/column) · %d nets built",
            n,
            cubes,
            (double) cubes / n,
            g.rivers.buildCount() - nets0);
        line(
            "  CPU per new column: plan %.2f + cubes %.2f + nets %.2f + cave regions %.2f = %.2f ms",
            plan / k,
            cubeNs / k,
            nets / k,
            caves / k,
            (plan + cubeNs + nets + caves) / k);
        line(
            "    per column: p50 %.1f ms · p90 %.1f ms · p99 %.1f ms · max %.0f ms",
            per[n / 2],
            per[(int) (n * 0.9)],
            per[Math.min(n - 1, (int) (n * 0.99))],
            per[n - 1]);
        line("  %.0f s of flight took %.1f s on one thread (%.2fx real time)", WALK_SECONDS, wall, wall / WALK_SECONDS);
        line("  where the time went (flight only):");
        for (String l : GenStats.report()
            .split("\n")) line("    %s", l);
    }

    /** Columns of the view window around (cx, cz) not generated yet, nearest first: {x, z, ring}. Marks them done. */
    private static List<int[]> window(int cx, int cz, Set<Long> done) {
        List<int[]> out = new ArrayList<>();
        for (int r = 0; r <= WALK_VIEW; r++) for (int dz = -r; dz <= r; dz++) for (int dx = -r; dx <= r; dx++) {
            if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
            int x = cx + dx, z = cz + dz;
            if (done.add(((long) x << 32) | (z & 0xffffffffL))) out.add(new int[] { x, z, r });
        }
        return out;
    }

    /** Net-building CPU so far (a build's wait for upstream nets is the nested builds, counted on their own). */
    private static long netNs() {
        return Math.round((GenStats.RIVER_NET.totalMs() - GenStats.RIVER_DEPS.totalMs()) * 1e6);
    }

    /**
     * Generates one column: its cave regions (when {@code account}, on their own so they can be told apart), its plan
     * and its cubes around the surface. River builds nested in any of them are moved to {@code nets} (exact only when
     * this is the only thread generating).
     */
    private ColumnCost column(WorldGen g, ColumnPlanCache plans, int cx, int cz, boolean account) {
        ColumnCost c = new ColumnCost();
        long n0 = netNs(), t0 = System.nanoTime();
        if (account) {
            GenPoint tmp = new GenPoint(); // plans the 3x3 cave regions around the column (also done by the plan)
            g.caves.surface((cx << 4) + 8, (cz << 4) + 8, tmp);
        }
        long n1 = netNs(), t1 = System.nanoTime();
        ColumnPlan p = plans.get(cx, cz);
        long n2 = netNs(), t2 = System.nanoTime();
        CrustModel.Sampler s = g.crust.sampler();
        byte[] code = new byte[4096];
        int lo = (p.minSurfaceY >> 4) - WALK_CUBES, hi = (p.maxTopY >> 4) + WALK_CUBES;
        for (int cy = lo; cy <= hi; cy++) if (cube(g, p, s, cx, cy, cz, code)) c.cubeCount++;
        long n3 = netNs(), t3 = System.nanoTime();
        c.caves = (t1 - t0) - (n1 - n0);
        c.plan = (t2 - t1) - (n2 - n1);
        c.cubes = (t3 - t2) - (n3 - n2);
        c.nets = n3 - n0;
        return c;
    }

    /** Lithology and caves of one cube (like CubeFiller without block storage); false for an air cube. */
    private boolean cube(WorldGen g, ColumnPlan p, CrustModel.Sampler s, int cx, int cy, int cz, byte[] code) {
        if ((cy << 4) > p.maxTopY) return false;
        s.prepare(cx << 4, cy << 4, cz << 4);
        CaveCarver.Cube cv = g.caves.prepare(p, cx, cy, cz);
        int acc = 0;
        for (int ly = 0; ly < 16; ly++) for (int lz = 0; lz < 16; lz++) for (int lx = 0; lx < 16; lx++) {
            int i = ColumnPlan.index(lx, lz), y = (cy << 4) + ly, k = (ly * 16 + lz) * 16 + lx;
            if (y > p.surfaceY[i]) {
                code[k] = -1;
                continue;
            }
            Lithology r = s.at(p.crust, i, lx, ly, lz);
            code[k] = cv == null ? CaveCarver.SOLID : cv.at(lx, ly, lz, r);
            acc += code[k] + r.ordinal();
        }
        sink += acc;
        return true;
    }

    /**
     * Teleport: a cold pipeline (nothing cached), columns around a far point planned nearest first by as many threads
     * as the prefetcher uses. Reports when the centre, the inner 9x9 and the whole view are ready, and how many river
     * networks it took (a network needs the exit flow of every upstream basin and the deltas of its coastal
     * neighbours).
     */
    public void teleport() {
        line("");
        int threads = Math.max(
            1,
            Math.min(
                8,
                Runtime.getRuntime()
                    .availableProcessors() / 3));
        line("Teleport to a far point (cold caches, %d prefetch threads, view 10 + 3):", threads);
        net.junedev.viridium.worldgen.terrain.GenStats.reset();
        double[][] targets = { { 52000, 31000 }, { -47000, 66000 }, { 88000, -41000 }, { -23000, -97000 } };
        for (double[] t : targets) {
            WorldGen g = gen(true);
            double[] p = landNear(g, t[0], t[1]);
            int cx = (int) Math.floor(p[0]) >> 4, cz = (int) Math.floor(p[1]) >> 4;
            ColumnPlanner planner = new ColumnPlanner(g, BiomeMapper.FROM_STAGE);
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            int R = 13;
            long t0 = System.nanoTime();
            List<Future<?>> fs = new ArrayList<>();
            List<Integer> ring = new ArrayList<>();
            // like the prefetcher: the networks under the player first, then the columns nearest first
            for (long k : g.rivers.basinsAt(p[0], p[1])) {
                BasinGraph.Cell c = g.rivers.basins.cell(k);
                if (!c.ocean) pool.submit(() -> g.rivers.net(c.ci, c.cj));
            }
            for (int r = 0; r <= R; r++) for (int dz = -r; dz <= r; dz++) for (int dx = -r; dx <= r; dx++) {
                if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                int x = cx + dx, z = cz + dz;
                fs.add(pool.submit(() -> planner.plan(x, z)));
                ring.add(r);
            }
            double centre = -1, inner = -1, all;
            try {
                for (int i = 0; i < fs.size(); i++) {
                    fs.get(i)
                        .get();
                    double ms = (System.nanoTime() - t0) / 1e6;
                    if (i == 0) centre = ms;
                    if (ring.get(i) == 4 && (i + 1 == fs.size() || ring.get(i + 1) > 4)) inner = ms;
                }
            } catch (Exception e) {
                line("  failed: %s", e);
            }
            all = (System.nanoTime() - t0) / 1e6;
            pool.shutdownNow();
            net.junedev.viridium.worldgen.terrain.GenStats.Timer rn = net.junedev.viridium.worldgen.terrain.GenStats.RIVER_NET;
            line(
                "  %7.0f,%7.0f  centre %6.0f ms · 9x9 %6.0f ms · 27x27 %6.0f ms · %3d nets built (so far %.0f ms each, max %.0f)",
                p[0],
                p[1],
                centre,
                inner,
                all,
                g.rivers.buildCount(),
                rn.meanMs(),
                rn.maxMs());
        }
        line("  where the time went (all four, summed over threads):");
        for (String l : net.junedev.viridium.worldgen.terrain.GenStats.report()
            .split("\n")) line("    %s", l);
    }

    private static double[] landNear(WorldGen g, double x, double z) {
        GenPoint o = new GenPoint();
        for (int r = 0; r < 60; r++) for (int a = 0; a < 8; a++) {
            double px = x + r * 700 * Math.cos(a * Math.PI / 4), pz = z + r * 700 * Math.sin(a * Math.PI / 4);
            g.evaluate(px, pz, Stage.RELIEF, o);
            if (o.h0 > 20) return new double[] { px, pz };
        }
        return new double[] { x, z };
    }

    public String report() {
        return out.toString();
    }

    private void line(String fmt, Object... args) {
        out.append(String.format(fmt, args))
            .append('\n');
    }
}
