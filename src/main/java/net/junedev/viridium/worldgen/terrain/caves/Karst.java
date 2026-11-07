package net.junedev.viridium.worldgen.terrain.caves;

import static net.junedev.viridium.worldgen.terrain.core.GenMath.ss;

import java.util.List;

import net.junedev.viridium.worldgen.terrain.WorldGen;
import net.junedev.viridium.worldgen.terrain.core.GenParams;
import net.junedev.viridium.worldgen.terrain.core.Noise;
import net.junedev.viridium.worldgen.terrain.pipeline.GenPoint;
import net.junedev.viridium.worldgen.terrain.pipeline.GenScratch;
import net.junedev.viridium.worldgen.terrain.pipeline.Stage;
import net.junedev.viridium.worldgen.terrain.stages.Rock;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;

/**
 * Karst at the surface: dolines (funnel-shaped hollows where limestone dissolves under the soil and the ground settles)
 * dot limestone and marble country, 10-40 blocks across; a quarter of them drop into a shaft (an open pothole 3-7
 * blocks wide, 30-150 deep) that often meets the caves below.
 * <p>
 * Dolines are applied to the 2D terrain after the rivers ({@link #surface}), so the map, the column plans and the LODs
 * see them; they fade out next to rivers and lakes. Shafts are {@link CaveCarver.Shape}s.
 */
public final class Karst implements CaveFeatures {

    /** Doline grid (one candidate per cell). */
    static final int G = 48;

    private final WorldGen gen;
    private final GenParams P;
    private final int seed;
    private final ThreadLocal<GenPoint> point = ThreadLocal.withInitial(GenPoint::new);
    private final ThreadLocal<GenScratch> scratch = ThreadLocal.withInitial(GenScratch::new);
    /** Shaft of a cell after checking its rock: {x, z, radius, top, bottom} or an empty array. */
    private final Cache<Long, double[]> shafts = CacheBuilder.newBuilder()
        .maximumSize(8192)
        .build();

    public Karst(WorldGen gen) {
        this.gen = gen;
        this.P = gen.params;
        this.seed = gen.seeds.karst;
    }

    /** Doline of cell (ci, cj): {x, z, radius, depth, shaft radius (0 none), shaft depth}, or null. */
    public double[] dolineAt(int ci, int cj) {
        return doline(ci, cj);
    }

    public static double karstOf(GenPoint o) {
        return karst(o);
    }

    double[] doline(int ci, int cj) {
        if (Noise.rnd(ci, cj, seed) >= 0.42 * P.dolines) return null;
        double x = (ci + 0.2 + 0.6 * Noise.rnd(ci, cj, seed + 1)) * G, z = (cj + 0.2 + 0.6 * Noise.rnd(ci, cj, seed + 2)) * G;
        double r = 5 + 15 * Noise.rnd(ci, cj, seed + 3);
        double depth = r * (0.35 + 0.45 * Noise.rnd(ci, cj, seed + 4));
        boolean shaft = Noise.rnd(ci, cj, seed + 5) < 0.25;
        return new double[] { x, z, r, depth, shaft ? 1.5 + 2 * Noise.rnd(ci, cj, seed + 6) : 0,
            30 + 120 * Noise.rnd(ci, cj, seed + 7) };
    }

    /** How karstic the surface rock is (limestone, marble). */
    static double karst(GenPoint o) {
        double a = karstRock(o.rock), b = karstRock(o.rock2);
        return a * o.rockT + b * (1 - o.rockT);
    }

    private static double karstRock(int r) {
        return r == Rock.LIMESTONE.ordinal() ? 1 : r == Rock.MARBLE.ordinal() ? 0.7 : 0;
    }

    /** Lowers the terrain inside dolines (after the rivers). */
    public void surface(double x, double z, GenPoint o) {
        o.doline = 0;
        if (P.dolines <= 0 || o.L < 0.02 || o.h < 3 || o.river != 0 || o.lake != 0) return;
        double k = karst(o);
        if (k <= 0.05) return;
        double away = ss(o.riverHW + 4, o.riverHW + 24, o.riverD);
        if (away <= 0) return;
        int ci = Math.floorDiv((int) Math.floor(x), G), cj = Math.floorDiv((int) Math.floor(z), G);
        double lower = 0;
        for (int dj = -1; dj <= 1; dj++) for (int di = -1; di <= 1; di++) {
            double[] d = doline(ci + di, cj + dj);
            if (d == null) continue;
            double q = Math.hypot(x - d[0], z - d[1]) / d[2];
            if (q >= 1) continue;
            double t = 1 - q * q;
            lower = Math.max(lower, d[3] * t * Math.sqrt(t));
        }
        if (lower <= 0) return;
        lower *= ss(0.05, 0.4, k) * away;
        o.h -= lower;
        o.doline = lower;
    }

    @Override
    public void collect(int bx, int by, int bz, List<CaveCarver.Shape> out) {
        if (P.dolines <= 0) return;
        int ci0 = Math.floorDiv(bx - 8, G), ci1 = Math.floorDiv(bx + 24, G), cj0 = Math.floorDiv(bz - 8, G),
            cj1 = Math.floorDiv(bz + 24, G);
        for (int cj = cj0; cj <= cj1; cj++) for (int ci = ci0; ci <= ci1; ci++) {
            double[] s = shaft(ci, cj);
            if (s.length == 0) continue;
            int r = (int) Math.ceil(s[2]) + 2;
            int x = (int) s[0], z = (int) s[1];
            if (x + r < bx || x - r > bx + 15 || z + r < bz || z - r > bz + 15) continue;
            if (s[3] < by || s[4] > by + 15) continue;
            out.add(new Shaft(s, r));
        }
    }

    /** Shaft of doline cell (ci, cj): {x, z, radius, top, bottom}, or an empty array. */
    public double[] shaft(int ci, int cj) {
        long key = net.junedev.viridium.worldgen.terrain.core.GenMath.mixKey(((long) ci << 32) | (cj & 0xffffffffL));
        double[] s = shafts.getIfPresent(key);
        if (s != null) return s;
        s = new double[0];
        double[] d = doline(ci, cj);
        if (d != null && d[4] > 0) {
            GenPoint o = gen.evaluate(d[0], d[1], Stage.GEOLOGY, 1, point.get(), scratch.get());
            if (o.L > 0.02 && o.h0 > 3 && karst(o) > 0.4) {
                double top = net.junedev.viridium.worldgen.terrain.column.TerrainLayout.SEA_LEVEL + o.h0 + 6;
                s = new double[] { d[0], d[1], d[4], top, top - d[3] - d[5] };
            }
        }
        shafts.put(key, s);
        return s;
    }

    /** A vertical pothole with rough walls, from above the surface down to its bottom. */
    private static final class Shaft implements CaveCarver.Shape {

        final double x, z, r;
        final int x0, x1, z0, z1, top, bottom;

        Shaft(double[] s, int reach) {
            x = s[0];
            z = s[1];
            r = s[2];
            x0 = (int) x - reach;
            x1 = (int) x + reach;
            z0 = (int) z - reach;
            z1 = (int) z + reach;
            top = (int) s[3];
            bottom = (int) s[4];
        }

        public int minX() { return x0; }
        public int minY() { return bottom; }
        public int minZ() { return z0; }
        public int maxX() { return x1; }
        public int maxY() { return top; }
        public int maxZ() { return z1; }

        @Override
        public boolean inside(int px, int py, int pz, double c1) {
            double rr = r * (1 + 0.35 * c1) * (py - bottom < 6 ? (py - bottom) / 6.0 + 0.2 : 1);
            double dx = px + 0.5 - x, dz = pz + 0.5 - z;
            return dx * dx + dz * dz < rr * rr;
        }

        @Override
        public boolean opensSurface() {
            return true;
        }
    }
}
