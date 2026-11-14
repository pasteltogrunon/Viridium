package net.junedev.viridium.worldgen.nether;

import static net.junedev.viridium.worldgen.nether.NetherLayout.DRY;
import static net.junedev.viridium.worldgen.terrain.core.GenMath.TAU;
import static net.junedev.viridium.worldgen.terrain.core.GenMath.clamp;
import static net.junedev.viridium.worldgen.terrain.core.GenMath.floor;

import java.util.ArrayList;
import java.util.List;

import net.junedev.viridium.worldgen.terrain.core.GenMath;
import net.junedev.viridium.worldgen.terrain.core.Noise;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;

/**
 * Network of connections (design §11, §12): routes ("highways") and chimneys, designed together with the forms that
 * hold them.
 *
 * <ul>
 * <li><b>Main routes</b> join the cores of a tier along the edges of their Gabriel graph: every core is reached, the
 * network has loops and alternatives, and nearby cores are not all joined directly. A route leaves a core from the rim
 * of its cavity, meanders, and is <b>graded</b>: its floor follows the base floor of the tier, never below the dry
 * level (it becomes a causeway or an arched bridge over seas and abysses) and never steeper than 1 block in 2 (it cuts
 * a gallery through massifs instead).</li>
 * <li><b>Chimneys</b> join two tiers: a hollow tower whose inner wall carries a spiral ledge from the floor of the
 * lower tier to the floor of the upper one, entered by a door at the bottom and left by a gap in the parapet at the
 * top, each facing a spur route to the nearest core of that tier. Windows open at every turn.</li>
 * </ul>
 * Everything is planned per {@value NetherCores#CELL}-block cell (deterministic, any order).
 */
public final class NetherRoutes {

    /** Half widths of main and spur routes. */
    static final double MAIN_HALF = 6, SPUR_HALF = 4.5;
    /** Maximum slope (rise per horizontal block). */
    static final double SLOPE = 0.5;
    /** Horizontal spacing of the samples of a route. */
    private static final double STEP = 6;

    private static final int[][] HALF_NEIGHBOURS = { { 1, -1 }, { 1, 0 }, { 1, 1 }, { 0, 1 } };

    private final NetherWorld world;
    private final int seed;
    private final Cache<Long, CellRoutes> cells = CacheBuilder.newBuilder()
        .maximumSize(4096)
        .concurrencyLevel(4)
        .build();
    private final Cache<Long, Route[]> mains = CacheBuilder.newBuilder()
        .maximumSize(4096)
        .concurrencyLevel(4)
        .build();

    NetherRoutes(NetherWorld world, int seed) {
        this.world = world;
        this.seed = seed;
    }

    /** A graded route of one tier: a polyline with its grade (absolute Y of the walking surface). */
    public static final class Route {

        public final int tier, id;
        public final boolean main;
        public final double half;
        public final double[] px, pz, grade, along;
        public final double minX, minZ, maxX, maxZ;

        Route(int tier, int id, boolean main, double[] px, double[] pz, double[] grade) {
            this.tier = tier;
            this.id = id;
            this.main = main;
            this.half = main ? MAIN_HALF : SPUR_HALF;
            this.px = px;
            this.pz = pz;
            this.grade = grade;
            this.along = new double[px.length];
            double x0 = Double.MAX_VALUE, x1 = -Double.MAX_VALUE, z0 = Double.MAX_VALUE, z1 = -Double.MAX_VALUE;
            for (int k = 0; k < px.length; k++) {
                if (k > 0) along[k] = along[k - 1] + GenMath.hypot(px[k] - px[k - 1], pz[k] - pz[k - 1]);
                x0 = Math.min(x0, px[k]);
                x1 = Math.max(x1, px[k]);
                z0 = Math.min(z0, pz[k]);
                z1 = Math.max(z1, pz[k]);
            }
            minX = x0;
            maxX = x1;
            minZ = z0;
            maxZ = z1;
        }

        public int segments() {
            return px.length - 1;
        }

        /** Distance from (x, z) to segment k; fills hit when closer than its current distance. */
        public void probe(int k, double x, double z, Hit hit) {
            double ax = px[k], az = pz[k], ex = px[k + 1] - ax, ez = pz[k + 1] - az;
            double l2 = ex * ex + ez * ez;
            double s = l2 < 1e-9 ? 0 : clamp(((x - ax) * ex + (z - az) * ez) / l2, 0, 1);
            double dx = ax + ex * s - x, dz = az + ez * s - z;
            double d = Math.sqrt(dx * dx + dz * dz);
            if (d < hit.dist) {
                hit.dist = d;
                hit.grade = grade[k] + (grade[k + 1] - grade[k]) * s;
                hit.along = along[k] + (along[k + 1] - along[k]) * s;
                hit.route = this;
            }
        }

        /** True if segment k comes within {@code margin} of the box [x0, x1] × [z0, z1]. */
        public boolean near(int k, double x0, double z0, double x1, double z1, double margin) {
            double a = Math.min(px[k], px[k + 1]), b = Math.max(px[k], px[k + 1]);
            double c = Math.min(pz[k], pz[k + 1]), d = Math.max(pz[k], pz[k + 1]);
            return b >= x0 - margin && a <= x1 + margin && d >= z0 - margin && c <= z1 + margin;
        }
    }

    /** Nearest route sample (mutable). */
    public static final class Hit {

        public double dist = Double.MAX_VALUE, grade, along;
        public Route route;

        public void reset() {
            dist = Double.MAX_VALUE;
            route = null;
        }
    }

    /** A chimney between tier {@link #lower} and the next one. */
    public static final class Chimney {

        public static final int WALL = 4, LEDGE = 4;

        public final int id, lower;
        public final double x, z, r;
        /** Floor of the lower tier at the door and of the upper tier at the exit (absolute Y). */
        public final int bottom, top;
        /** Angle of the door, total angle swept by the ledge, rise per radian. */
        public final double theta0, total, rate;
        public final boolean lavafall;

        Chimney(int id, int lower, double x, double z, double r, int bottom, int top, double theta0, double total,
            boolean lavafall) {
            this.id = id;
            this.lower = lower;
            this.x = x;
            this.z = z;
            this.r = r;
            this.bottom = bottom;
            this.top = top;
            this.theta0 = theta0;
            this.total = total;
            this.rate = (top - bottom) / total;
            this.lavafall = lavafall;
        }

        /** Outer radius of the tower. */
        public double outer() {
            return r + WALL;
        }

        /** Angle of the exit at the top. */
        public double exitAngle() {
            return theta0 + total;
        }

        /** Height of the ledge surface after sweeping {@code phi} radians from the door. */
        public double ledgeY(double phi) {
            return bottom + phi * rate;
        }
    }

    static final class CellRoutes {

        final Route[] routes;
        final Chimney[] chimneys;

        CellRoutes(Route[] routes, Chimney[] chimneys) {
            this.routes = routes;
            this.chimneys = chimneys;
        }
    }

    CellRoutes cell(int i, int j) {
        long key = GenMath.mixKey(((long) i << 32) ^ (j & 0xffffffffL));
        CellRoutes c = cells.getIfPresent(key);
        if (c == null) {
            c = build(i, j);
            cells.put(key, c);
        }
        return c;
    }

    /** Routes and chimneys owned by the cells around (x, z) whose box comes within {@code margin} of the box. */
    public void collect(double x0, double z0, double x1, double z1, double margin, List<Route> routes,
        List<Chimney> chimneys) {
        int ci0 = floor((x0 - margin) / NetherCores.CELL) - 2, ci1 = floor((x1 + margin) / NetherCores.CELL) + 1;
        int cj0 = floor((z0 - margin) / NetherCores.CELL) - 2, cj1 = floor((z1 + margin) / NetherCores.CELL) + 2;
        for (int i = ci0; i <= ci1; i++) for (int j = cj0; j <= cj1; j++) {
            CellRoutes c = cell(i, j);
            if (routes != null) for (Route r : c.routes) {
                double m = margin + r.half;
                if (r.maxX >= x0 - m && r.minX <= x1 + m && r.maxZ >= z0 - m && r.minZ <= z1 + m) routes.add(r);
            }
            if (chimneys != null) for (Chimney ch : c.chimneys) {
                double m = margin + ch.outer();
                if (ch.x >= x0 - m && ch.x <= x1 + m && ch.z >= z0 - m && ch.z <= z1 + m) chimneys.add(ch);
            }
        }
    }

    // ------------------------------------------------------------------ planning

    private CellRoutes build(int i, int j) {
        List<Route> rs = new ArrayList<>(java.util.Arrays.asList(coreRoutes(i, j)));
        List<Chimney> cs = new ArrayList<>();
        for (int t = 0; t + 1 < NetherLayout.TIERS; t++) chimney(i, j, t, rs, cs);
        return new CellRoutes(rs.toArray(new Route[0]), cs.toArray(new Chimney[0]));
    }

    /** Main routes owned by a cell (independent of chimneys, so chimneys can avoid them). */
    private Route[] coreRoutes(int i, int j) {
        long key = GenMath.mixKey(((long) i << 32) ^ (j & 0xffffffffL));
        Route[] c = mains.getIfPresent(key);
        if (c != null) return c;
        List<Route> rs = new ArrayList<>();
        NetherCore[] here = world.cores.cell(i, j);
        for (int t = 0; t < NetherLayout.TIERS; t++) {
            NetherCore a = here[t];
            if (a == null) continue;
            for (int[] o : HALF_NEIGHBOURS) {
                NetherCore b = world.cores.cell(i + o[0], j + o[1])[t];
                if (b != null && gabriel(a, b, t)) rs.add(coreRoute(a, b, t));
            }
        }
        c = rs.toArray(new Route[0]);
        mains.put(key, c);
        return c;
    }

    /** Edge of the Gabriel graph: no other core of the tier inside the circle whose diameter is ab. */
    private boolean gabriel(NetherCore a, NetherCore b, int t) {
        double len = a.distH(b.x, b.z);
        if (len > 1.75 * NetherCores.CELL) return false;
        double mx = (a.x + b.x) * 0.5, mz = (a.z + b.z) * 0.5, r = len * 0.5 * 0.98;
        int ci = floor(mx / NetherCores.CELL), cj = floor(mz / NetherCores.CELL);
        for (int di = -2; di <= 2; di++) for (int dj = -2; dj <= 2; dj++) {
            NetherCore c = world.cores.cell(ci + di, cj + dj)[t];
            if (c == null || c.id == a.id || c.id == b.id) continue;
            if (c.distH(mx, mz) < r) return false;
        }
        return true;
    }

    private Route coreRoute(NetherCore a, NetherCore b, int t) {
        double dx = b.x - a.x, dz = b.z - a.z, len = Math.sqrt(dx * dx + dz * dz);
        double ux = dx / len, uz = dz / len;
        double sx = a.x + ux * 0.85 * a.cavityR, sz = a.z + uz * 0.85 * a.cavityR;
        double ex = b.x - ux * 0.85 * b.cavityR, ez = b.z - uz * 0.85 * b.cavityR;
        int id = Noise.hash(Math.min(a.id, b.id), Math.max(a.id, b.id), seed);
        return route(sx, sz, ex, ez, t, true, id, Double.NaN, 0);
    }

    /**
     * A graded route from (sx, sz) to (ex, ez) in tier t. With a finite {@code startGrade} the route starts exactly at
     * that height (a chimney door) and climbs or descends from it within the slope limit.
     */
    Route route(double sx, double sz, double ex, double ez, int t, boolean main, int id, double startGrade,
        double flat) {
        double dx = ex - sx, dz = ez - sz, len = Math.max(1, Math.sqrt(dx * dx + dz * dz));
        int n = Math.max(4, (int) Math.ceil(len / STEP));
        double nx = -dz / len, nz = dx / len;
        double amp = Math.min(70, 0.1 * len) * (main ? 1 : 0.5);
        double[] px = new double[n + 1], pz = new double[n + 1], g = new double[n + 1];
        for (int k = 0; k <= n; k++) {
            double s = (double) k / n;
            double off = amp * 1.3
                * Noise.perlin(s * len / 200, (id & 1023) * 0.61 + 0.3, seed)
                * Math.sin(Math.PI * s);
            px[k] = sx + dx * s + nx * off;
            pz[k] = sz + dz * s + nz * off;
            g[k] = Math.max(world.baseFloor(px[k], pz[k], t), DRY[t]);
        }
        // smooth, keep the ends
        for (int pass = 0; pass < 3; pass++) {
            double prev = g[0];
            for (int k = 1; k < n; k++) {
                double cur = g[k];
                g[k] = (prev + 2 * cur + g[k + 1]) * 0.25;
                prev = cur;
            }
        }
        // slope limit by lowering (cuts galleries through rises): g_k <= g_j + slope * dist(j, k)
        double[] seg = new double[n + 1];
        for (int k = 1; k <= n; k++) seg[k] = GenMath.hypot(px[k] - px[k - 1], pz[k] - pz[k - 1]);
        for (int k = 1; k <= n; k++) g[k] = Math.min(g[k], g[k - 1] + SLOPE * seg[k]);
        for (int k = n - 1; k >= 0; k--) g[k] = Math.min(g[k], g[k + 1] + SLOPE * seg[k + 1]);
        if (!Double.isNaN(startGrade)) {
            // level with the door for the first blocks, then within the slope limit
            double along = 0;
            for (int k = 0; k <= n; k++) {
                if (k > 0) along += seg[k];
                if (k > 0 && along > flat) break;
                g[k] = startGrade;
            }
        }
        for (int k = 1; k <= n; k++) g[k] = clamp(g[k], g[k - 1] - SLOPE * seg[k], g[k - 1] + SLOPE * seg[k]);
        for (int k = 0; k <= n; k++) g[k] = Math.max(g[k], DRY[t]);
        return new Route(t, id, main, px, pz, g);
    }

    private void chimney(int i, int j, int t, List<Route> rs, List<Chimney> cs) {
        NetherCores cores = world.cores;
        int s = seed + 31 * (t + 1) + 500;
        NetherCore[] here = cores.cell(i, j);
        NetherCore lo = here[t], up = here[t + 1];
        double p = lo != null && up != null ? 0.9 : 0.4;
        if (Noise.rnd(i, j, s) >= p) return;
        double bx, bz;
        if (lo != null && up != null) {
            bx = (lo.x + up.x) * 0.5 + 100 * (Noise.rnd(i, j, s + 1) - 0.5);
            bz = (lo.z + up.z) * 0.5 + 100 * (Noise.rnd(i, j, s + 2) - 0.5);
        } else {
            bx = (i + 0.25 + 0.5 * Noise.rnd(i, j, s + 1)) * NetherCores.CELL;
            bz = (j + 0.25 + 0.5 * Noise.rnd(i, j, s + 2)) * NetherCores.CELL;
        }
        double r = 8 + 4 * Noise.rnd(i, j, s + 3);
        // a few candidate places around the preferred one: the first that keeps clear of core cavities, abysses
        // and main routes (a tower must never cut a route)
        double px = 0, pz = 0;
        boolean found = false;
        double a0 = Noise.rnd(i, j, s + 4) * TAU;
        for (int k = 0; k < 9 && !found; k++) {
            double dist = k == 0 ? 0 : 70 + 40 * ((k - 1) / 4);
            px = bx + dist * Math.cos(a0 + k * 1.7);
            pz = bz + dist * Math.sin(a0 + k * 1.7);
            found = clear(px, pz, r, t);
        }
        if (!found) return;
        NetherCore lowerCore = cores.nearest(px, pz, t, 1), upperCore = cores.nearest(px, pz, t + 1, 1);
        if (lowerCore == null || upperCore == null) return;
        int bottom = (int) Math.round(Math.max(world.baseFloor(px, pz, t), DRY[t] + 1));
        int top = (int) Math.round(Math.max(world.baseFloor(px, pz, t + 1), DRY[t + 1] + 1));
        if (top - bottom < 40) return;
        int id = Noise.hash(i, j, s + 5);
        double flat = r + Chimney.WALL + 8;
        Route spurLo = spur(px, pz, lowerCore, t, id, bottom, flat);
        Route spurUp = spur(px, pz, upperCore, t + 1, id + 1, top, flat);
        double theta0 = Math.atan2(spurLo.pz[1] - pz, spurLo.px[1] - px);
        double thetaTop = Math.atan2(spurUp.pz[1] - pz, spurUp.px[1] - px);
        double rp = r - 2, rise = top - bottom;
        double delta = thetaTop - theta0;
        delta -= TAU * Math.floor(delta / TAU);
        double need2 = rise / (SLOPE * rp);
        int turns = Math.max(1, (int) Math.ceil((need2 - delta) / TAU));
        double total = TAU * turns + delta;
        boolean lava = lowerCore.active() || (lowerCore.activity > 0 && Noise.rnd(i, j, s + 6) < 0.3);
        cs.add(new Chimney(id, t, px, pz, r, bottom, top, theta0, total, lava));
        rs.add(spurLo);
        rs.add(spurUp);
    }

    /** True if a chimney of inner radius r between tiers t and t + 1 can stand at (px, pz). */
    private boolean clear(double px, double pz, double r, int t) {
        double outer = r + Chimney.WALL;
        int pi = floor(px / NetherCores.CELL), pj = floor(pz / NetherCores.CELL);
        for (int tt = t; tt <= t + 1; tt++) for (int di = -1; di <= 1; di++) for (int dj = -1; dj <= 1; dj++) {
            NetherCore c = world.cores.cell(pi + di, pj + dj)[tt];
            if (c != null && c.distH(px, pz) < c.cavityR + r + 30) return false;
        }
        NetherSubstrate.Fracture f = new NetherSubstrate.Fracture();
        world.substrate.fracture(px, pz, f);
        if (f.abyss > 0 && f.dist < f.abyss + outer + 14) return false;
        Hit hit = new Hit();
        for (int di = -2; di <= 1; di++)
            for (int dj = -2; dj <= 2; dj++) for (Route route : coreRoutes(pi + di, pj + dj)) {
                if (route.tier != t && route.tier != t + 1) continue;
                double m = outer + route.half + 8;
                if (px < route.minX - m || px > route.maxX + m || pz < route.minZ - m || pz > route.maxZ + m) continue;
                hit.reset();
                for (int k = 0; k < route.segments(); k++) route.probe(k, px, pz, hit);
                if (hit.dist < m) return false;
            }
        return true;
    }

    private Route spur(double px, double pz, NetherCore c, int t, int id, int startGrade, double flat) {
        double dx = px - c.x, dz = pz - c.z, d = Math.max(1, Math.sqrt(dx * dx + dz * dz));
        double ex = c.x + dx / d * 0.85 * c.cavityR, ez = c.z + dz / d * 0.85 * c.cavityR;
        return route(px, pz, ex, ez, t, false, id, startGrade, flat);
    }
}
