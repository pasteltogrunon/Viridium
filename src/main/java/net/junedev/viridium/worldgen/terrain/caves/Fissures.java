package net.junedev.viridium.worldgen.terrain.caves;

import static net.junedev.viridium.worldgen.terrain.core.GenMath.ss;

import java.util.ArrayList;
import java.util.List;

import net.junedev.viridium.worldgen.terrain.WorldGen;
import net.junedev.viridium.worldgen.terrain.column.TerrainLayout;
import net.junedev.viridium.worldgen.terrain.core.GenMath;
import net.junedev.viridium.worldgen.terrain.core.GenParams;
import net.junedev.viridium.worldgen.terrain.core.Noise;
import net.junedev.viridium.worldgen.terrain.pipeline.GenPoint;
import net.junedev.viridium.worldgen.terrain.pipeline.GenScratch;
import net.junedev.viridium.worldgen.terrain.pipeline.Stage;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;

/**
 * Ground fissures: now and then a long narrow crack in the land (one candidate per {@value #G}-block cell, ~1 per 3 km²
 * of land per unit of {@code fissures}). A polyline 60-250 blocks long with gentle bends, 2-6 blocks wide (varying
 * along it, tapering at both ends), 12-40 blocks deep with vertical walls; 40 % of them go on down as a narrower slot
 * 20-60 blocks deeper, where they often meet the caves.
 * <p>
 * The crack is cut in the 2D terrain after the rivers ({@link #surface}), so the map, the column plans and the LODs see
 * it: at one block per column the walls are vertical. Planning only reads the terrain up to the erosion (no rivers, so
 * no river network is built for it): a crack stops before the sea, low ground and steep slopes; near rivers and lakes
 * it closes up (from the river distance of the point itself). The slot is a {@link CaveCarver.Shape}.
 */
public final class Fissures implements CaveFeatures {

    static final int G = 768;
    /** Node spacing of the polyline (blocks). */
    private static final double STEP = 16;

    private final WorldGen gen;
    private final GenParams P;
    private final int seed;
    private final Cache<Long, Fissure[]> cells = CacheBuilder.newBuilder()
        .maximumSize(4096)
        .build();
    private final ThreadLocal<GenPoint> point = ThreadLocal.withInitial(GenPoint::new);
    private final ThreadLocal<GenScratch> scratch = ThreadLocal.withInitial(GenScratch::new);
    private final ThreadLocal<Memo> memo = ThreadLocal.withInitial(Memo::new);
    private static final Fissure[] NONE = {};

    public Fissures(WorldGen gen) {
        this.gen = gen;
        this.P = gen.params;
        this.seed = gen.seeds.cave + 31337;
    }

    /** One crack: polyline nodes, arc length at each node, width and depth, ground at the nodes (absolute Y). */
    public static final class Fissure implements CaveCarver.Shape {

        final double[] nx, nz, arc, ground;
        final int n;
        public final double length, width, depth, slot;
        final int seed;
        /** Bounds of the crack seen from above (with its width) and of the slot (all axes). */
        final double bx0, bx1, bz0, bz1;
        final int sy0, sy1;

        Fissure(double[] nx, double[] nz, double[] ground, int n, double width, double depth, double slot, int seed) {
            this.nx = nx;
            this.nz = nz;
            this.ground = ground;
            this.n = n;
            this.arc = new double[n];
            for (int i = 1; i < n; i++) arc[i] = arc[i - 1] + Math.hypot(nx[i] - nx[i - 1], nz[i] - nz[i - 1]);
            this.length = arc[n - 1];
            this.width = width;
            this.depth = depth;
            this.slot = slot;
            this.seed = seed;
            double x0 = 1e18, x1 = -1e18, z0 = 1e18, z1 = -1e18, g0 = 1e18, g1 = -1e18;
            for (int i = 0; i < n; i++) {
                x0 = Math.min(x0, nx[i]);
                x1 = Math.max(x1, nx[i]);
                z0 = Math.min(z0, nz[i]);
                z1 = Math.max(z1, nz[i]);
                g0 = Math.min(g0, ground[i]);
                g1 = Math.max(g1, ground[i]);
            }
            double pad = width * 0.7 + 2;
            bx0 = x0 - pad;
            bx1 = x1 + pad;
            bz0 = z0 - pad;
            bz1 = z1 + pad;
            sy0 = (int) Math.floor(g0 - depth - slot - 2);
            sy1 = (int) Math.ceil(g1 - depth * 0.35 + 7);
        }

        /** Width at arc position s: tapering to both ends, wavering along. */
        double widthAt(double s) {
            double t = s / length, e = Math.sqrt(Math.max(0, Math.sin(Math.PI * t)));
            return width * (0.25 + 0.75 * e) * (0.8 + 0.3 * Noise.perlin(s / 25, 0.5, seed));
        }

        /** Depth at arc position s (shallower towards the ends). */
        double depthAt(double s) {
            double t = s / length;
            return depth * (0.35 + 0.65 * Math.sqrt(Math.max(0, Math.sin(Math.PI * t))));
        }

        /**
         * Distance from (x, z) to the polyline; out[0] gets the arc position of the nearest point. Large (1e9) outside
         * the bounds.
         */
        double distance(double x, double z, double[] out) {
            if (x < bx0 || x > bx1 || z < bz0 || z > bz1) return 1e9;
            double best = 1e18, bs = 0;
            for (int i = 0; i + 1 < n; i++) {
                double ax = nx[i], az = nz[i], vx = nx[i + 1] - ax, vz = nz[i + 1] - az;
                double l2 = vx * vx + vz * vz;
                double t = l2 > 0 ? GenMath.clamp(((x - ax) * vx + (z - az) * vz) / l2, 0, 1) : 0;
                double dx = x - ax - vx * t, dz = z - az - vz * t, d2 = dx * dx + dz * dz;
                if (d2 < best) {
                    best = d2;
                    bs = arc[i] + t * (arc[i + 1] - arc[i]);
                }
            }
            out[0] = bs;
            return Math.sqrt(best);
        }

        public int minX() {
            return (int) Math.floor(bx0);
        }

        public int minY() {
            return sy0;
        }

        public int minZ() {
            return (int) Math.floor(bz0);
        }

        public int maxX() {
            return (int) Math.ceil(bx1);
        }

        public int maxY() {
            return sy1;
        }

        public int maxZ() {
            return (int) Math.ceil(bz1);
        }

        /** The slot below the crack (middle 70 % of it): narrower, down to slot blocks under the crack floor. */
        @Override
        public boolean inside(int px, int py, int pz, double c1) {
            double[] o = SLOT_TMP.get();
            double d = distance(px + 0.5, pz + 0.5, o);
            double s = o[0], t = s / length;
            if (t < 0.15 || t > 0.85) return false;
            double w = widthAt(s) * 0.5 * (1 + 0.25 * c1);
            if (d >= w * 0.5) return false;
            // ground along the crack, interpolated between the nodes
            int i = Math.min(n - 2, Math.max(0, (int) (s / (length / (n - 1)))));
            while (i > 0 && arc[i] > s) i--;
            while (i < n - 2 && arc[i + 1] < s) i++;
            double f = (s - arc[i]) / Math.max(1e-9, arc[i + 1] - arc[i]);
            double g = ground[i] + (ground[i + 1] - ground[i]) * f;
            double floor = g - depthAt(s);
            double e = Math.sin(Math.PI * (t - 0.15) / 0.7);
            double bottom = floor - slot * Math.sqrt(Math.max(0, e)) * (1 - d / (w * 0.5) * 0.5);
            return py >= bottom && py <= floor + 6; // up into the crack (the planned ground is only close to the final
                                                    // one)
        }

        @Override
        public boolean opensSurface() {
            return true;
        }
    }

    private static final ThreadLocal<double[]> SLOT_TMP = ThreadLocal.withInitial(() -> new double[1]);

    /** Per thread: the fissures that may reach the last cell a point fell in. */
    private static final class Memo {

        boolean set;
        int ci, cj;
        Fissure[] near = NONE;
        final double[] tmp = new double[1];
    }

    private static long key(int ci, int cj) {
        return GenMath.mixKey(((long) ci << 32) | (cj & 0xffffffffL));
    }

    /** The fissure of cell (ci, cj): empty or one. */
    Fissure[] cell(int ci, int cj) {
        long k = key(ci, cj);
        Fissure[] f = cells.getIfPresent(k);
        if (f != null) return f;
        Fissure one = plan(ci, cj);
        f = one == null ? NONE : new Fissure[] { one };
        cells.put(k, f);
        return f;
    }

    /** All fissures of the cells around (ci, cj) (3x3): for tests and maps. */
    public List<Fissure> around(int ci, int cj) {
        List<Fissure> l = new ArrayList<>();
        for (int dj = -1; dj <= 1; dj++)
            for (int di = -1; di <= 1; di++) for (Fissure f : cell(ci + di, cj + dj)) l.add(f);
        return l;
    }

    private Fissure plan(int ci, int cj) {
        if (Noise.rnd(ci, cj, seed) >= 0.2 * P.fissures) return null;
        double x = (ci + 0.15 + 0.7 * Noise.rnd(ci, cj, seed + 1)) * G,
            z = (cj + 0.15 + 0.7 * Noise.rnd(ci, cj, seed + 2)) * G;
        double ang = Noise.rnd(ci, cj, seed + 3) * Math.PI * 2;
        double len = 60 + 190 * Noise.rnd(ci, cj, seed + 4);
        int max = (int) Math.ceil(len / STEP) + 1;
        double[] nx = new double[max], nz = new double[max], gr = new double[max];
        int n = 0;
        int hs = Noise.hash(ci, cj, seed + 5);
        double prev = Double.NaN;
        for (int i = 0; i < max; i++) {
            GenPoint o = gen.evaluate(x, z, Stage.EROSION, 1, point.get(), scratch.get());
            // on land, well above the sea, not on steep slopes (a crack there would look like a mistake)
            if (o.L < 0.05 || o.h < 8) break;
            if (prev == prev && Math.abs(o.h - prev) > 0.55 * STEP) break;
            nx[n] = x;
            nz[n] = z;
            gr[n] = TerrainLayout.SEA_LEVEL + o.h;
            n++;
            prev = o.h;
            ang += (Noise.rnd(i, 1, hs) - 0.5) * 0.5; // gentle bends
            double step = i == max - 2 ? len - STEP * (max - 2) : STEP;
            if (step <= 0) break;
            x += Math.cos(ang) * step;
            z += Math.sin(ang) * step;
        }
        if (n < 4) return null; // too short where the ground allows it
        double width = 2 + 4 * Noise.rnd(ci, cj, seed + 6), depth = 12 + 28 * Noise.rnd(ci, cj, seed + 7);
        double slot = Noise.rnd(ci, cj, seed + 8) < 0.4 ? 20 + 40 * Noise.rnd(ci, cj, seed + 9) : 0;
        return new Fissure(
            java.util.Arrays.copyOf(nx, n),
            java.util.Arrays.copyOf(nz, n),
            java.util.Arrays.copyOf(gr, n),
            n,
            width,
            depth,
            slot,
            hs);
    }

    /** Cuts the cracks into the 2D terrain (after the rivers and the other cave features). */
    public void surface(double x, double z, GenPoint o) {
        surface(x, z, o, net.junedev.viridium.worldgen.terrain.stages.RiverStage.lakeShore());
    }

    /** As above; shore is the lake shore level of the point (RiverStage.lakeShore() right after its rivers), or NaN. */
    public void surface(double x, double z, GenPoint o, double shore) {
        if (P.fissures <= 0 || o.L < 0.02 || o.river != 0 || o.lake != 0) return;
        int ci = Math.floorDiv((int) Math.floor(x), G), cj = Math.floorDiv((int) Math.floor(z), G);
        Memo m = memo.get();
        if (!m.set || m.ci != ci || m.cj != cj) {
            // a crack runs at most 250 blocks from its start, which sits 0.15 G = 115 inside its cell: the 3x3 cells
            // around cover it; keep those whose bounds meet this cell
            List<Fissure> l = new ArrayList<>(2);
            double x0 = ci * (double) G, z0 = cj * (double) G;
            for (int dj = -1; dj <= 1; dj++) for (int di = -1; di <= 1; di++) for (Fissure f : cell(ci + di, cj + dj))
                if (f.bx1 >= x0 && f.bx0 <= x0 + G && f.bz1 >= z0 && f.bz0 <= z0 + G) l.add(f);
            m.near = l.isEmpty() ? NONE : l.toArray(NONE);
            m.ci = ci;
            m.cj = cj;
            m.set = true;
        }
        if (m.near.length == 0) return;
        for (Fissure f : m.near) {
            double d = f.distance(x, z, m.tmp);
            if (d > f.width) continue;
            double s = m.tmp[0], w = f.widthAt(s);
            if (d >= w * 0.5) continue;
            // closes up near rivers, lakes and the sea (from this point's own distances: no river network needed)
            double fade = ss(o.riverHW + 6, o.riverHW + 30, o.riverD) * ss(4, 12, o.h);
            double cut = f.depthAt(s) * fade * (0.9 + 0.2 * Noise.perlin(x / 6, z / 6, f.seed));
            if (cut < 1.5) continue;
            if (shore == shore && shore > o.h - cut - 4) return; // nor next to a lake as high as its floor
            o.h -= cut;
            o.doline = Math.max(o.doline, cut);
            return;
        }
    }

    @Override
    public void collect(int bx, int by, int bz, List<CaveCarver.Shape> out) {
        if (P.fissures <= 0) return;
        int ci = Math.floorDiv(bx, G), cj = Math.floorDiv(bz, G);
        for (int dj = -1; dj <= 1; dj++) for (int di = -1; di <= 1; di++) for (Fissure f : cell(ci + di, cj + dj)) {
            if (f.slot <= 0) continue;
            if (f.maxX() >= bx && f.minX() <= bx + 15
                && f.maxY() >= by
                && f.minY() <= by + 15
                && f.maxZ() >= bz
                && f.minZ() <= bz + 15) out.add(f);
        }
    }
}
