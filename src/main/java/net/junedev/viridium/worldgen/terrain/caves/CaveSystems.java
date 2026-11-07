package net.junedev.viridium.worldgen.terrain.caves;

import static net.junedev.viridium.worldgen.terrain.core.GenMath.lerp;
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
 * Planned cave systems, per region of {@value #R}×{@value #R} blocks (deterministic, any order):
 * <ul>
 * <li><b>giant chambers</b>: 0-2 per region, shallow (top 20-60 blocks under the ground), mid (y −150..−700) or deep
 * (y −900..−1850); 60-360 blocks across, domed with a flat floor, rough walls, turned any way with a wobbly
 * outline; some hold a lake (water, or lava deep down);</li>
 * <li><b>galleries</b>: wide winding tunnels from each chamber to one in the next region (east and south), 4-12 blocks
 * in radius;</li>
 * <li><b>collapse pits</b>: a shallow chamber whose roof is thin, on land away from rivers, has fallen in: a pit with
 * steep walls opens in the terrain down to its floor (a cenote when the chamber holds a lake). Pits are not round: an
 * ellipse along the chamber with a lobed outline, walls of 1-3 ledges and a mound of rubble from the fallen roof;</li>
 * <li><b>entrances</b>: other shallow chambers get a gallery climbing to the surface 100-220 blocks away.</li>
 * </ul>
 * Planning reads the relief, rivers and erosion directly (never the cave surface stage, which needs these plans).
 */
public final class CaveSystems implements CaveFeatures {

    static final int R = 1024;

    private final WorldGen gen;
    private final GenParams P;
    private final int seed;
    private final Cache<Long, Region> regions = CacheBuilder.newBuilder()
        .maximumSize(1024)
        .build();
    private final ThreadLocal<GenPoint> point = ThreadLocal.withInitial(GenPoint::new);
    private final ThreadLocal<GenScratch> scratch = ThreadLocal.withInitial(GenScratch::new);

    public CaveSystems(WorldGen gen) {
        this.gen = gen;
        this.P = gen.params;
        this.seed = gen.seeds.chamber;
    }

    /** A giant chamber. */
    public static final class Chamber implements CaveCarver.Shape {

        public final double x, y, z, rx, ry, rz, floor;
        /**
         * Lake level (absolute Y; NaN none) and kind (WATER or LAVA). A collapse pit lowers it under its rim (so the
         * cenote never spills), before pitDone is set.
         */
        public double lake;
        public final byte lakeKind;
        /** Collapse pit radius (0 none) and the floor height of the pit (absolute Y). */
        public double pitR;
        /** May collapse into a pit (decided by {@link CaveSystems#pit} from the rivers); pitR is valid once pitDone. */
        boolean pitCand;
        volatile boolean pitDone = true;
        final int x0, x1, y0, y1, z0, z1;
        /** Orientation of the rx axis (cos, sin) and the wobble of the horizontal outline. */
        final double ca, sa;
        final Lobes outline;
        /**
         * Shape of a collapse pit, if the roof falls in: an ellipse of semi-axes pa (along rx) and pb, lobed; walls
         * wallW wide (varying around), in 1-3 ledges, with a mound of rubble on the floor; nothing beyond pitReach (x and
         * z) of the centre.
         */
        double pa, pb; // shrunk (before pitDone) when the full pit would cut into water
        final double wallW, rubble, pitReach;
        final int ledges, shape;
        final Lobes pitOutline, wallOutline;

        Chamber(double x, double y, double z, double rx, double ry, double rz, double lake, byte lakeKind, int shape) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.rx = rx;
            this.ry = ry;
            this.rz = rz;
            this.floor = y - 0.55 * ry;
            this.lake = lake;
            this.lakeKind = lakeKind;
            double ang = Noise.rnd(0, 0, shape) * Math.PI;
            ca = Math.cos(ang);
            sa = Math.sin(ang);
            outline = new Lobes(new double[] { 0, 0.1, 0.08 }, shape, 10);
            // as large as a round pit of radius 0.55 min(rx, rz) would be, stretched 1-1.8 times along the chamber
            double base = 0.55 * Math.min(rx, rz), e = Math.sqrt(1 + 0.8 * Noise.rnd(1, 0, shape));
            pa = base * (rx >= rz ? e : 1 / e);
            pb = base * (rx >= rz ? 1 / e : e);
            pitOutline = new Lobes(new double[] { 0, 0.1, 0.13, 0.06, 0.03 }, shape, 20);
            wallOutline = new Lobes(new double[] { 0.25, 0.35 }, shape, 30);
            wallW = 5 + 4 * Noise.rnd(3, 0, shape);
            ledges = 1 + (int) (3 * Noise.rnd(4, 0, shape));
            rubble = 2 + 7 * Noise.rnd(5, 0, shape);
            this.shape = shape;
            pitReach = Math.max(pa, pb) * (1 + pitOutline.amp) + wallW * (1 + wallOutline.amp) * 1.2 + 2;
            double reach = Math.max(rx, rz) * 1.3 * (1 + outline.amp);
            x0 = (int) Math.floor(x - reach);
            x1 = (int) Math.ceil(x + reach);
            z0 = (int) Math.floor(z - reach);
            z1 = (int) Math.ceil(z + reach);
            y0 = (int) Math.floor(floor);
            y1 = (int) Math.ceil(y + ry * 1.3);
        }

        /**
         * Where (px, pz) is relative to the pit outline: q < 1 inside; out (when not null) gets the distance in blocks
         * outside the outline (0 inside) and the local wall width.
         */
        double pitQ(double px, double pz, double[] out) {
            double ex = px - x, ez = pz - z;
            double u = (ex * ca + ez * sa) / pa, w = (-ex * sa + ez * ca) / pb;
            double rho = Math.sqrt(u * u + w * w);
            if (rho < 1e-9) {
                if (out != null) {
                    out[0] = 0;
                    out[1] = wallW;
                }
                return 0;
            }
            double c = u / rho, s = w / rho;
            double q = rho / pitOutline.at(c, s);
            if (out != null) {
                double d = Math.sqrt(ex * ex + ez * ez);
                out[0] = q > 1 ? d * (1 - 1 / q) : 0;
                out[1] = wallW * wallOutline.at(c, s);
            }
            return q;
        }

        /**
         * Floor of the open pit at (px, pz) (absolute Y): the chamber floor, rough, with a mound of rubble from the fallen
         * roof in the middle (q from {@link #pitQ}). Both the 2D pit and the carved chamber use it.
         */
        double pitFloor(double px, double pz, double q) {
            double rough = 2.5 * (Noise.fbm(px / 20, pz / 20, shape, 2) + 0.6);
            return floor + 1 + Math.max(0, rough) + (q < 1 ? rubble * (1 - q * q) : 0);
        }

        /** Highest point the carved chamber may reach above (px, pz) (absolute Y, with its rough walls), or -inf. */
        double ceilingAt(double px, double pz) {
            double ex = px - x, ez = pz - z;
            double u = (ex * ca + ez * sa) / rx, w = (-ex * sa + ez * ca) / rz;
            double h2 = u * u + w * w;
            if (h2 > 1e-12) {
                double rho = Math.sqrt(h2), r = outline.at(u / rho, w / rho);
                h2 /= r * r;
            }
            if (h2 >= 1.3) return Double.NEGATIVE_INFINITY; // 0.3 * c1 <= 0.3: the noise widens it a little
            return y + ry * Math.sqrt(1.3 - h2);
        }

        public int minX() { return x0; }
        public int minY() { return y0; }
        public int minZ() { return z0; }
        public int maxX() { return x1; }
        public int maxY() { return pitR > 0 ? 10000 : y1; }
        public int maxZ() { return z1; }

        @Override
        public boolean inside(int px, int py, int pz, double c1) {
            if (py < floor) return false;
            // under an open pit the floor is the rubble of the fallen roof (the same height as the 2D pit floor)
            if (pitR > 0 && py < floor + 16) {
                double q = pitQ(px + 0.5, pz + 0.5, null);
                if (q < 1 && py < pitFloor(px + 0.5, pz + 0.5, q)) return false;
            }
            double ex = px + 0.5 - x, ez = pz + 0.5 - z;
            double u = (ex * ca + ez * sa) / rx, w = (-ex * sa + ez * ca) / rz, dy = (py + 0.5 - y) / ry;
            double h2 = u * u + w * w;
            if (h2 > 1e-12) {
                double rho = Math.sqrt(h2), r = outline.at(u / rho, w / rho);
                h2 /= r * r;
            }
            double q = h2 + dy * dy + 0.3 * c1;
            if (q < 1) return true;
            // a collapsed roof: the pit above the chamber (the 2D stage lowers the ground to match, same outline)
            return pitR > 0 && py > y && pitQ(px + 0.5, pz + 0.5, null) < 1;
        }

        @Override
        public byte fluid(int py) {
            if (lake == lake && py <= lake) return lakeKind;
            return CaveCarver.AIR;
        }

        @Override
        public boolean opensSurface() {
            return pitR > 0;
        }
    }

    /** A gallery segment: a capsule of radius r from (ax, ay, az) to (bx, by, bz). */
    public static final class Segment implements CaveCarver.Shape {

        final double ax, ay, az, bx, by, bz, r;
        final boolean surface;
        final int x0, x1, y0, y1, z0, z1;

        Segment(double ax, double ay, double az, double bx, double by, double bz, double r, boolean surface) {
            this.ax = ax;
            this.ay = ay;
            this.az = az;
            this.bx = bx;
            this.by = by;
            this.bz = bz;
            this.r = r;
            this.surface = surface;
            double m = r * 1.4 + 1;
            x0 = (int) Math.floor(Math.min(ax, bx) - m);
            x1 = (int) Math.ceil(Math.max(ax, bx) + m);
            y0 = (int) Math.floor(Math.min(ay, by) - m);
            y1 = (int) Math.ceil(Math.max(ay, by) + m);
            z0 = (int) Math.floor(Math.min(az, bz) - m);
            z1 = (int) Math.ceil(Math.max(az, bz) + m);
        }

        public int minX() { return x0; }
        public int minY() { return y0; }
        public int minZ() { return z0; }
        public int maxX() { return x1; }
        public int maxY() { return y1; }
        public int maxZ() { return z1; }

        @Override
        public boolean inside(int px, int py, int pz, double c1) {
            double vx = bx - ax, vy = by - ay, vz = bz - az;
            double l2 = vx * vx + vy * vy + vz * vz;
            double qx = px + 0.5 - ax, qy = py + 0.5 - ay, qz = pz + 0.5 - az;
            double t = l2 > 0 ? GenMath.clamp((qx * vx + qy * vy + qz * vz) / l2, 0, 1) : 0;
            double dx = qx - vx * t, dy = (qy - vy * t) * 1.35, dz = qz - vz * t; // a little flatter than wide
            double rr = r * (1 + 0.35 * c1);
            return dx * dx + dy * dy + dz * dz < rr * rr;
        }

        @Override
        public boolean opensSurface() {
            return surface;
        }

        /** True if (x, z) is within the radius + pad of the segment seen from above. */
        public boolean near2d(double x, double z, double pad) {
            double vx = bx - ax, vz = bz - az, l2 = vx * vx + vz * vz;
            double t = l2 > 0 ? GenMath.clamp(((x - ax) * vx + (z - az) * vz) / l2, 0, 1) : 0;
            return Math.hypot(x - ax - vx * t, z - az - vz * t) < r + pad;
        }
    }

    /** Planned chambers and gallery segments of one region. */
    public static final class Region {

        public final List<Chamber> chambers = new ArrayList<>();
        final List<Segment> segments = new ArrayList<>();
        /** The first segments of the great entrance (its mouth), if any. */
        public final List<Segment> mouth = new ArrayList<>();
        /** All segments of the great entrance (after the galleries), filled by ensureEntrance. */
        final List<Segment> entrance = new ArrayList<>();
        int ri, rj;
        /** Target of the great entrance and how far from it (in x and z) its ramp may run; reach < 0: none. */
        double ex, ez, reach = -1;
        /** Pits and the great entrance resolved (they need the rivers). */
        volatile boolean entDone;
    }

    private static long key(int ri, int rj) {
        return GenMath.mixKey(((long) ri << 32) | (rj & 0xffffffffL));
    }

    private static final Chamber[] NO_CHAMBERS = {};

    /** Per thread: the collapse pit candidates of the last region {@link #surface} looked at. */
    private static final class PitMemo {

        boolean set;
        int ri, rj;
        Chamber[] cands;
        final double[] ow = new double[2];
    }

    /** A wall profile in n ledges: 0 at t = 0 (pit floor), 1 at t = 1 (rim); flat shelves with steep risers. */
    static double stairs(double t, int n) {
        if (t <= 0) return 0;
        if (t >= 1) return 1;
        double s = t * n;
        int k = (int) s;
        return (k + ss(0.45, 0.85, s - k)) / n;
    }

    private final ThreadLocal<PitMemo> pitMemo = ThreadLocal.withInitial(PitMemo::new);

    /** Chambers of a region, without pits or galleries (no rivers): cached, also used to link neighbours. */
    private final Cache<Long, List<Chamber>> chamberLists = CacheBuilder.newBuilder()
        .maximumSize(4096)
        .build();

    /** Chambers of region (ri, rj) only (no galleries, pits not resolved yet: see {@link #pit}). */
    private List<Chamber> chambers(int ri, int rj) {
        long k = key(ri, rj);
        List<Chamber> l = chamberLists.getIfPresent(k);
        if (l == null) {
            l = chambers0(ri, rj);
            chamberLists.put(k, l);
        }
        return l;
    }

    /**
     * Resolves the collapse pit of a chamber, once: it needs the rivers at the chamber centre, so it is only done when a
     * point or a cube near the chamber needs it (river networks are never built just to plan caves far away). Races
     * compute the same value.
     */
    private void pit(Chamber ch) {
        pit(ch, null);
    }

    /**
     * As above; caller (not null) is the scratch of an evaluation that asked for cached river networks only (map
     * previews): then no network is built for the pit, and if one it needs is missing the pit stays unresolved (none for
     * now) and the caller is told so ({@code riversIncomplete}).
     */
    private void pit(Chamber ch, GenScratch caller) {
        if (ch.pitDone) return;
        GenPoint o = rivers(ch.x, ch.z, caller);
        if (o == null) return; // a network it needs is not cached (map preview): undecided for now
        if (!(o.river == 0 && o.lake == 0 && o.riverD > 0.6 * Math.min(ch.rx, ch.rz) + 40 && o.h > 4)) {
            ch.pitDone = true;
            return;
        }
        // the ground around the outer edge of the walls: no river, lake or high lake shore there (the pit would cut
        // into them; a smaller pit is tried before none), and the cenote stays under its lowest point
        for (double sc : new double[] { 1, 0.75, 0.55 }) {
            boolean ok = true;
            double low = Double.POSITIVE_INFINITY;
            for (int k = 0; k < 32 && ok; k++) {
                double a = k * Math.PI * 2 / 32, c = Math.cos(a), s = Math.sin(a);
                double rho = ch.pitOutline.at(c, s), u = c * rho * ch.pa * sc, w = s * rho * ch.pb * sc; // pit frame
                double d = Math.sqrt(u * u + w * w), out = ch.wallW * ch.wallOutline.at(c, s) * 1.2 + 1;
                double eu = u * (1 + out / d), ew = w * (1 + out / d);
                GenPoint p = rivers(ch.x + eu * ch.ca - ew * ch.sa, ch.z + eu * ch.sa + ew * ch.ca, caller);
                if (p == null) return; // a network it needs is not cached (map preview): undecided for now
                double shore = net.junedev.viridium.worldgen.terrain.stages.RiverStage.lakeShore();
                if (p.river != 0 || p.lake != 0 || (shore == shore && shore > ch.floor - TerrainLayout.SEA_LEVEL - 4)
                    || p.h < 3 || p.riverD < p.riverHW + 4) ok = false;
                low = Math.min(low, TerrainLayout.SEA_LEVEL + p.h);
            }
            if (!ok) continue;
            if (ch.lake == ch.lake) ch.lake = Math.min(ch.lake, low - 2);
            ch.pa *= sc;
            ch.pb *= sc;
            ch.pitR = 0.55 * Math.min(ch.rx, ch.rz) * sc;
            break;
        }
        ch.pitDone = true;
    }

    /**
     * The terrain up to the rivers at (x, z) (this planner's own temporaries), or null when caller asked for cached
     * networks only and one is missing (then caller.riversIncomplete is set).
     */
    private GenPoint rivers(double x, double z, GenScratch caller) {
        GenPoint o = gen.evaluate(x, z, Stage.EROSION, 1, point.get(), scratch.get());
        GenScratch s = scratch.get();
        if (caller != null) {
            s.riversCachedOnly = true;
            s.riversIncomplete = false;
            try {
                gen.rivers.applyNested(x, z, o, s);
            } finally {
                s.riversCachedOnly = false;
            }
            if (s.riversIncomplete) {
                s.riversIncomplete = false;
                caller.riversIncomplete = true;
                return null;
            }
        } else gen.rivers.applyNested(x, z, o, s);
        return o;
    }

    private List<Chamber> chambers0(int ri, int rj) {
        List<Chamber> out = new ArrayList<>(2);
        if (P.plannedCaves <= 0) return out;
        double r0 = Noise.rnd(ri, rj, seed);
        int n = r0 < 0.3 / P.plannedCaves ? 0 : r0 < 0.8 ? 1 : 2;
        for (int c = 0; c < n; c++) {
            int s = seed + 17 * c;
            double x = (ri + 0.15 + 0.7 * Noise.rnd(ri, rj, s + 1)) * R, z = (rj + 0.15 + 0.7 * Noise.rnd(ri, rj, s + 2)) * R;
            GenPoint o = gen.evaluate(x, z, Stage.EROSION, 1, point.get(), scratch.get());
            double surface = TerrainLayout.SEA_LEVEL + o.h;
            double kind = Noise.rnd(ri, rj, s + 3);
            boolean land = o.L > 0.05 && o.h > 4;
            double size = Noise.rnd(ri, rj, s + 4), sh = Noise.rnd(ri, rj, s + 5);
            double y, rx, rz, ry;
            int depthClass;
            if (kind < 0.45 && land) { // shallow
                depthClass = 0;
                rx = 30 + 40 * size;
                ry = Math.min(35, 20 + 15 * sh);
                y = surface - (20 + 40 * Noise.rnd(ri, rj, s + 6)) - ry;
            } else if (kind < 0.7) { // mid
                depthClass = 1;
                rx = 50 + 60 * size;
                ry = 0.45 * rx + 10 * sh;
                y = -150 - 550 * Noise.rnd(ri, rj, s + 6);
            } else { // deep
                depthClass = 2;
                rx = 80 + 100 * size;
                ry = 0.45 * rx + 20 * sh;
                y = -900 - 950 * Noise.rnd(ri, rj, s + 6);
            }
            rz = rx * (0.65 + 0.7 * Noise.rnd(ri, rj, s + 7));
            double floor = y - 0.55 * ry;
            double lr = Noise.rnd(ri, rj, s + 8);
            double lake = Double.NaN;
            byte lk = CaveCarver.WATER;
            if (depthClass == 2 && y < -1200 && lr < 0.35) {
                lake = floor + 3 + 10 * Noise.rnd(ri, rj, s + 9);
                lk = CaveCarver.LAVA;
            } else if (lr < 0.45) lake = floor + 3 + 12 * Noise.rnd(ri, rj, s + 9);
            Chamber ch = new Chamber(x, y, z, rx, ry, rz, lake, lk, Noise.hash(ri, rj, s + 11));
            if (depthClass == 0 && Noise.rnd(ri, rj, s + 10) < 0.8) {
                // a thin roof on land away from rivers collapses into a pit (the rivers are checked lazily: pit())
                ch.pitCand = true;
                ch.pitDone = false;
            }
            out.add(ch);
        }
        return out;
    }

    /** The full region plan (chambers with their pits, galleries to the east and south neighbours, great entrance). */
    public Region region(int ri, int rj) {
        Region r = plan(ri, rj);
        ensureEntrance(r);
        return r;
    }

    private void ensureEntrance(Region r) {
        if (r.entDone) return;
        synchronized (r) {
            if (r.entDone) return;
            for (Chamber c : r.chambers) pit(c);
            greatEntrance(r.ri, r.rj, r);
            r.entDone = true;
        }
    }

    /** True if a box centred at (x, z) with half-size hs may meet the great entrance of r. */
    private static boolean mayMeetEntrance(Region r, double x, double z, double hs) {
        return r.reach >= 0 && Math.abs(x - r.ex) <= r.reach + hs && Math.abs(z - r.ez) <= r.reach + hs;
    }

    /** Chambers and galleries, without rivers (pits and the great entrance are resolved on demand). */
    private Region plan(int ri, int rj) {
        long k = key(ri, rj);
        Region r = regions.getIfPresent(k);
        if (r != null) return r;
        r = new Region();
        r.ri = ri;
        r.rj = rj;
        r.chambers.addAll(chambers(ri, rj));
        for (int c = 0; c < r.chambers.size(); c++) {
            Chamber a = r.chambers.get(c);
            int[][] nb = { { 1, 0 }, { 0, 1 } };
            for (int e = 0; e < 2; e++) {
                List<Chamber> other = chambers(ri + nb[e][0], rj + nb[e][1]);
                if (other.isEmpty()) continue;
                Chamber b = other.get((int) (Noise.rnd(ri * 3 + e, rj * 5 + c, seed + 31) * other.size()));
                if (Math.abs(a.y - b.y) > 700) continue;
                gallery(a.x, a.y - a.ry * 0.3, a.z, b.x, b.y - b.ry * 0.3, b.z, r.segments, seed + ri * 7919 + rj * 104729
                    + e * 13 + c, galleryRadius(Math.min(a.y, b.y)), false);
            }
        }
        Chamber best = null;
        for (Chamber c : r.chambers) if (best == null || c.y > best.y) best = c;
        if (best != null) {
            double ty = best.y - 0.2 * best.ry;
            GenPoint c0 = gen.evaluate(best.x, best.z, Stage.EROSION, 1, point.get(), scratch.get());
            double depth = Math.max(40, TerrainLayout.SEA_LEVEL + Math.max(c0.h, 0) - ty);
            r.ex = best.x;
            r.ez = best.z;
            // the ramp starts at most len away from the target (see greatEntrance), wanders 9 blocks sideways and its
            // segments reach r * 1.4 + 1 (r <= 13) beyond their axis
            r.reach = GenMath.clamp(depth * (depth < 600 ? 1.4 : 0.95), 120, 950) + 9 + 13 * 1.4 + 2;
        } else r.entDone = true;
        regions.put(k, r);
        return r;
    }

    /**
     * Every system gets a great entrance: a wide ramp (mouth radius 9-13) from the surface down to its shallowest chamber
     * (unless that one has a collapse pit), 1.4 times as long as it is deep down to ~600 blocks, steeper below (120-950
     * blocks), starting on land away from rivers in one of six directions (then the same at 0.6 of the length).
     */
    private void greatEntrance(int ri, int rj, Region r) {
        Chamber best = null;
        for (Chamber c : r.chambers) if (best == null || c.y > best.y) best = c;
        if (best == null || best.pitR > 0) return;
        double tx = best.x, ty = best.y - 0.2 * best.ry, tz = best.z;
        double base = Noise.rnd(ri, rj, seed + 71) * Math.PI * 2;
        GenPoint c0 = gen.evaluate(tx, tz, Stage.EROSION, 1, point.get(), scratch.get());
        double depth = Math.max(40, TerrainLayout.SEA_LEVEL + Math.max(c0.h, 0) - ty);
        for (int k = 0; k < 12; k++) {
            double ang = base + k * Math.PI / 3;
            // gentle down to ~600 blocks deep, steeper (a great descending cave) below; shorter on the second round
            double len = GenMath.clamp(depth * (depth < 600 ? 1.4 : 0.95), 120, 950) * (k < 6 ? 1 : 0.6);
            double sx = tx + Math.cos(ang) * len, sz = tz + Math.sin(ang) * len;
            GenPoint o = gen.evaluate(sx, sz, Stage.EROSION, 1, point.get(), scratch.get());
            if (o.L < 0.05 || o.h < 4) continue;
            gen.rivers.apply(sx, sz, o, scratch.get());
            if (o.river != 0 || o.lake != 0 || o.riverD < 30) continue;
            double r0 = 9 + 4 * Noise.rnd(ri, rj, seed + 73);
            int before = r.entrance.size();
            ramp(sx, TerrainLayout.SEA_LEVEL + o.h + 0.3 * r0, sz, tx, ty, tz, r0, 7, r.entrance, seed + 79 + ri * 31 + rj * 37);
            for (int q = before; q < Math.min(r.entrance.size(), before + 4); q++) r.mouth.add(r.entrance.get(q));
            return;
        }
    }

    /**
     * A ramp from a mouth at the surface (a, wide) down to b (narrower): a winding polyline every ~20 blocks, jittered
     * sideways, which may open the ground all along (the mouth, and skylights where it runs just under a slope).
     */
    static void ramp(double ax, double ay, double az, double bx, double by, double bz, double r0, double r1,
        List<Segment> out, int s) {
        double len = Math.sqrt((bx - ax) * (bx - ax) + (bz - az) * (bz - az));
        int n = Math.max(3, (int) (len / 20));
        double ux = (bx - ax) / Math.max(len, 1), uz = (bz - az) / Math.max(len, 1);
        double px = ax, py = ay, pz = az;
        for (int i = 1; i <= n; i++) {
            double t = (double) i / n;
            double side = i == n ? 0 : (Noise.rnd(s, i, 5) - 0.5) * 18 * Math.sin(Math.PI * t);
            double qx = lerp(ax, bx, t) - uz * side, qz = lerp(az, bz, t) + ux * side;
            double qy = lerp(ay, by, t) + (i == n ? 0 : (Noise.rnd(s, i, 6) - 0.5) * 4);
            out.add(new Segment(px, py, pz, qx, qy, qz, lerp(r0, r1, Math.min(1, t * 1.5)), true));
            px = qx;
            py = qy;
            pz = qz;
        }
    }

    private static double galleryRadius(double y) {
        return 4 + 6 * ss(-100, -1400, y);
    }

    /** A winding gallery from A to B: a polyline with jittered nodes, widening a little now and then. */
    private static void gallery(double ax, double ay, double az, double bx, double by, double bz, List<Segment> out,
        int s, double r, boolean surface) {
        double len = Math.sqrt((bx - ax) * (bx - ax) + (by - ay) * (by - ay) + (bz - az) * (bz - az));
        int n = Math.max(3, (int) (len / 48));
        double px = ax, py = ay, pz = az;
        for (int i = 1; i <= n; i++) {
            double t = (double) i / n;
            double jx = i == n ? 0 : (Noise.rnd(s, i, 1) - 0.5) * 60, jz = i == n ? 0 : (Noise.rnd(s, i, 2) - 0.5) * 60,
                jy = i == n ? 0 : (Noise.rnd(s, i, 3) - 0.5) * 16;
            double qx = lerp(ax, bx, t) + jx, qy = lerp(ay, by, t) + jy, qz = lerp(az, bz, t) + jz;
            double ri = r * (0.8 + 0.5 * Noise.rnd(s, i, 4));
            // only the last third of an entrance may open the ground
            out.add(new Segment(px, py, pz, qx, qy, qz, ri, surface && t > 0.66));
            px = qx;
            py = qy;
            pz = qz;
        }
    }

    /** True near the mouth of a great entrance (no trees there: the ground is cut away). */
    public boolean nearMouth(double x, double z) {
        if (P.plannedCaves <= 0) return false;
        int ri = Math.floorDiv((int) Math.floor(x), R), rj = Math.floorDiv((int) Math.floor(z), R);
        for (int dj = -1; dj <= 1; dj++) for (int di = -1; di <= 1; di++) {
            Region r = plan(ri + di, rj + dj);
            if (!mayMeetEntrance(r, x, z, 4)) continue;
            ensureEntrance(r);
            for (Segment s : r.mouth) if (s.near2d(x, z, 3)) return true;
        }
        return false;
    }

    @Override
    public void collect(int bx, int by, int bz, List<CaveCarver.Shape> out) {
        if (P.plannedCaves <= 0) return;
        int ri = Math.floorDiv(bx, R), rj = Math.floorDiv(bz, R);
        for (int dj = -1; dj <= 1; dj++) for (int di = -1; di <= 1; di++) {
            Region r = plan(ri + di, rj + dj);
            for (Chamber c : r.chambers) {
                if (c.maxX() < bx || c.minX() > bx + 15 || c.maxZ() < bz || c.minZ() > bz + 15) continue;
                pit(c);
                if (touches(c, bx, by, bz)) out.add(c);
            }
            for (Segment g : r.segments) if (touches(g, bx, by, bz)) out.add(g);
            if (mayMeetEntrance(r, bx + 8, bz + 8, 8)) {
                ensureEntrance(r);
                for (Segment g : r.entrance) if (touches(g, bx, by, bz)) out.add(g);
            }
        }
    }

    private static boolean touches(CaveCarver.Shape s, int bx, int by, int bz) {
        return s.maxX() >= bx && s.minX() <= bx + 15 && s.maxY() >= by && s.minY() <= by + 15 && s.maxZ() >= bz
            && s.minZ() <= bz + 15;
    }

    /**
     * Collapse pits in the 2D terrain (after the rivers): inside the pit the ground drops to the chamber floor with steep
     * walls; a chamber lake shows as a lake (a cenote).
     */
    public void surface(double x, double z, GenPoint o) {
        surface(x, z, o, net.junedev.viridium.worldgen.terrain.stages.RiverStage.lakeShore());
    }

    /** As above; shore is the lake shore level of the point (RiverStage.lakeShore() right after its rivers), or NaN. */
    public void surface(double x, double z, GenPoint o, double shore) {
        // the evaluation this runs in may want cached river networks only (map previews): see pit(Chamber, GenScratch)
        GenScratch caller = net.junedev.viridium.worldgen.terrain.stages.RiverStage.takeCachedOnlyCaller();
        if (P.plannedCaves <= 0) return;
        int ri = Math.floorDiv((int) Math.floor(x), R), rj = Math.floorDiv((int) Math.floor(z), R);
        // a pit reaches at most ~95 blocks (pitReach) from its chamber, which sits at least 0.15 R inside its region:
        // only the chambers of this region can touch (x, z)
        PitMemo m = pitMemo.get();
        if (!m.set || m.ri != ri || m.rj != rj) {
            List<Chamber> l = chambers(ri, rj);
            int n = 0;
            for (Chamber c : l) if (c.pitCand) n++;
            Chamber[] cands = n == 0 ? NO_CHAMBERS : new Chamber[n];
            n = 0;
            for (Chamber c : l) if (c.pitCand) cands[n++] = c;
            m.cands = cands;
            m.ri = ri;
            m.rj = rj;
            m.set = true;
        }
        {
            for (Chamber c : m.cands) {
                if (Math.abs(x - c.x) > c.pitReach || Math.abs(z - c.z) > c.pitReach) continue;
                // a river lake stays as it is
                if (o.lake == 1 && !(c.lake == c.lake && o.lakeZ == c.lake - TerrainLayout.SEA_LEVEL)) continue;
                pit(c, caller);
                if (c.pitR <= 0) continue;
                double[] ow = m.ow;
                double q = c.pitQ(x, z, ow);
                double f; // 0 on the floor of the pit, 1 at the rim (untouched ground)
                if (q < 1) f = 0;
                else {
                    // walls in ledges: flat shelves and steep risers, a little ragged
                    double t = ow[0] / ow[1] + 0.15 * Noise.fbm(x / 7, z / 7, seed + 62, 2);
                    if (t >= 1) continue;
                    f = stairs(t, c.ledges);
                }
                // the floor (the chamber's, with the rubble of the fallen roof: the carved chamber stops at it too)
                double bottom = c.pitFloor(x, z, q) - TerrainLayout.SEA_LEVEL; // relative to sea level
                // nor the shore of a lake above the pit floor: the pit stops there, the shore holds the water
                if (shore == shore && shore > bottom - 4) continue;
                double target = lerp(Math.min(o.h, bottom), o.h, f);
                // no thin shelves over the hollow chamber: where less than 5 blocks of rock would be left above it, the
                // wall drops straight to the floor (an overhanging cenote rim, never a hanging lip)
                double roof = c.ceilingAt(x, z) - TerrainLayout.SEA_LEVEL;
                if (f > 0 && target < roof + 5) target = Math.min(target, bottom);
                if (target < o.h) {
                    o.doline = Math.max(o.doline, o.h - target);
                    o.h = target;
                    o.river = 0;
                    // a cenote: water wherever the pit is below the chamber's lake (the rim is always higher)
                    if (c.lake == c.lake && c.lakeKind == CaveCarver.WATER && o.h < c.lake - TerrainLayout.SEA_LEVEL) {
                        o.lake = 1;
                        o.lakeZ = c.lake - TerrainLayout.SEA_LEVEL;
                    }
                }
            }
        }
    }
}
