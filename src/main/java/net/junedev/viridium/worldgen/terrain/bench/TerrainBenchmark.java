package net.junedev.viridium.worldgen.terrain.bench;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import net.junedev.viridium.worldgen.terrain.WorldGen;
import net.junedev.viridium.worldgen.terrain.column.BiomeMapper;
import net.junedev.viridium.worldgen.terrain.column.ColumnPlan;
import net.junedev.viridium.worldgen.terrain.column.ColumnPlanner;
import net.junedev.viridium.worldgen.terrain.column.TerrainLayout;
import net.junedev.viridium.worldgen.terrain.core.GenParams;
import net.junedev.viridium.worldgen.terrain.core.Noise;
import net.junedev.viridium.worldgen.terrain.pipeline.GenPoint;
import net.junedev.viridium.worldgen.terrain.pipeline.Stage;

/**
 * Headless benchmark of the terrain pipeline (no Minecraft classes), so it runs from tests and from an in-game
 * command alike. Mirrors bench/run.js (per-stage marginal cost, land vs ocean, primitives) and adds the real case:
 * planning contiguous 16x16 columns, single- and multi-threaded, and the cube-kind mix of a full column.
 */
public final class TerrainBenchmark {

    private final WorldGen gen;
    private final int points;
    private final StringBuilder out = new StringBuilder();
    private double sink; // defeats dead-code elimination

    public TerrainBenchmark(GenParams params, int points) {
        this.gen = new WorldGen(params);
        this.points = points;
    }

    public static void main(String[] args) {
        int points = args.length > 0 ? Integer.parseInt(args[0]) : 2000;
        System.out.print(new TerrainBenchmark(new GenParams(), points).run());
    }

    public String run() {
        line("Viridium terrain benchmark · seed %d · %d land + %d ocean points · %d threads available",
            gen.params.seed, points, points, Runtime.getRuntime().availableProcessors());
        double[][] pts = samplePoints();
        // warm-up (JIT)
        for (int i = 0; i < 3; i++) stageCosts(pts[0], pts[1], false);
        stageCosts(pts[0], pts[1], true);
        primitives();
        columns();
        line("(sink %.3f)", sink);
        return out.toString();
    }

    /** Random land and ocean points, interleaved x/z. */
    private double[][] samplePoints() {
        Random r = new Random(42);
        double[] land = new double[points * 2], sea = new double[points * 2];
        int nl = 0, ns = 0;
        GenPoint o = new GenPoint();
        while (nl < points || ns < points) {
            double x = Math.round((r.nextDouble() - 0.5) * 120000), z = Math.round((r.nextDouble() - 0.5) * 120000);
            gen.evaluate(x, z, Stage.RELIEF, o);
            if (o.h0 >= 0 && nl < points) {
                land[2 * nl] = x;
                land[2 * nl++ + 1] = z;
            } else if (o.h0 < 0 && ns < points) {
                sea[2 * ns] = x;
                sea[2 * ns++ + 1] = z;
            }
        }
        return new double[][] { land, sea };
    }

    private double timePerPoint(double[] pts, Stage stage) {
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
        return best / 1000.0 / n; // µs
    }

    private void stageCosts(double[] land, double[] sea, boolean print) {
        if (print) {
            line("");
            line("%-36s %10s %10s", "Stage (marginal cost)", "land µs", "ocean µs");
        }
        double prevL = 0, prevS = 0;
        for (Stage s : Stage.values()) {
            gen.clearCaches();
            double tl = timePerPoint(land, s), ts = timePerPoint(sea, s);
            if (print) line("%-36s %10.2f %10.2f", s.name(), tl - prevL, ts - prevS);
            prevL = tl;
            prevS = ts;
        }
        if (print) {
            line("%-36s %10.2f %10.2f", "TOTAL per point", prevL, prevS);
            line("%-36s %10.2f %10.2f", "≈ per 16x16 column (ms, 1 thread)", prevL * 256 / 1000, prevS * 256 / 1000);
        }
    }

    private void primitives() {
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
        for (int i = 0; i < m; i++) sink += gen.heightAt(xs[i], xs[n - 1 - i], false);
        line("  %-28s %8.4f µs", "heightAt() coarse", (System.nanoTime() - t0) / 1000.0 / m);
        t0 = System.nanoTime();
        for (int i = 0; i < m; i++) sink += gen.heightAt(xs[i], xs[n - 1 - i], true);
        line("  %-28s %8.4f µs", "heightAt() detailed", (System.nanoTime() - t0) / 1000.0 / m);
    }

    /** The real case: contiguous columns, like a player exploring. */
    private void columns() {
        line("");
        line("Column plans (contiguous 16x16 columns, real case):");
        ColumnPlanner planner = new ColumnPlanner(gen, BiomeMapper.VANILLA);
        int side = 24; // 24x24 columns = 384x384 blocks
        int cx0 = 37, cz0 = -12;
        // warm-up
        for (int i = 0; i < 64; i++) planner.plan(cx0 - 100 + i, cz0 - 100);

        long t0 = System.nanoTime();
        List<ColumnPlan> plans = new ArrayList<>(side * side);
        for (int dz = 0; dz < side; dz++) for (int dx = 0; dx < side; dx++) plans.add(planner.plan(cx0 + dx, cz0 + dz));
        double single = (System.nanoTime() - t0) / 1e6 / (side * side);
        line("  1 thread: %.3f ms/column (%d columns)", single, side * side);

        int threads = Math.max(1, Runtime.getRuntime().availableProcessors() - 1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<ColumnPlan>> fs = new ArrayList<>();
            t0 = System.nanoTime();
            for (int dz = 0; dz < side; dz++) for (int dx = 0; dx < side; dx++) {
                int x = cx0 + 1000 + dx, z = cz0 + dz;
                fs.add(pool.submit(() -> planner.plan(x, z)));
            }
            for (Future<ColumnPlan> f : fs) f.get();
            double wall = (System.nanoTime() - t0) / 1e6;
            line("  %d threads: %.3f ms/column wall (%.0f columns/s)", threads, wall / fs.size(),
                fs.size() / (wall / 1000));
        } catch (Exception e) {
            line("  multithreaded run failed: %s", e);
        } finally {
            pool.shutdownNow();
        }

        // cube mix over the generation range
        long air = 0, solid = 0, mixed = 0;
        int cubesPerColumn = (TerrainLayout.MAX_Y - TerrainLayout.MIN_Y) >> 4;
        for (ColumnPlan p : plans) {
            int top = Math.max(p.maxSurfaceY, TerrainLayout.SEA_LEVEL - 1) >> 4;
            int solidTop = (p.minSurfaceY - 4 - 15) >> 4;
            for (int cy = TerrainLayout.MIN_Y >> 4; cy < TerrainLayout.MAX_Y >> 4; cy++) {
                if (cy > top) air++;
                else if (cy <= solidTop) solid++;
                else mixed++;
            }
        }
        long total = (long) plans.size() * cubesPerColumn;
        line("  cubes per column in [%d, %d): %d → air %.1f%%, uniform deep %.1f%%, surface-crossing %.1f%% (%.2f per column)",
            TerrainLayout.MIN_Y, TerrainLayout.MAX_Y, cubesPerColumn, 100.0 * air / total, 100.0 * solid / total,
            100.0 * mixed / total, (double) mixed / plans.size());
    }

    private void line(String fmt, Object... args) {
        out.append(String.format(fmt, args))
            .append('\n');
    }
}
