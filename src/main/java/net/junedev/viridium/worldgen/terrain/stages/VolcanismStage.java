package net.junedev.viridium.worldgen.terrain.stages;

import static net.junedev.viridium.worldgen.terrain.core.GenMath.TAU;
import static net.junedev.viridium.worldgen.terrain.core.GenMath.clamp;
import static net.junedev.viridium.worldgen.terrain.core.GenMath.floor;
import static net.junedev.viridium.worldgen.terrain.core.GenMath.hypot;
import static net.junedev.viridium.worldgen.terrain.core.GenMath.ss;
import static net.junedev.viridium.worldgen.terrain.core.Noise.fbm;
import static net.junedev.viridium.worldgen.terrain.core.Noise.perlin;
import static net.junedev.viridium.worldgen.terrain.core.Noise.ridged;
import static net.junedev.viridium.worldgen.terrain.core.Noise.rnd;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import net.junedev.viridium.worldgen.terrain.WorldGen;
import net.junedev.viridium.worldgen.terrain.pipeline.GenPoint;
import net.junedev.viridium.worldgen.terrain.pipeline.GenScratch;

/**
 * 7b. Hotspots on a coarse grid (1.7 plate sizes). Each one is resolved once (it queries the plates at its position)
 * and memoised in a deterministic, bounded cache. Two kinds: volcano chains dragged by the plate (Hawaii, with atolls
 * in warm water) and hotspots on a ridge (Iceland, with a rift valley).
 */
public class VolcanismStage extends GenStage {

    public static final int TYPE_CHAIN = 1, TYPE_ICELAND = 2, TYPE_CONTINENT = 3;

    /** A resolved hotspot. Immutable. */
    public static final class Hotspot {

        public final int type;
        public final double x, y;
        // chain
        public final double ux, uy, sp, H0;
        public final int N, I, J;
        public final boolean warm;
        // Iceland
        public final double R;

        Hotspot(int type, double x, double y, double ux, double uy, int N, double sp, double H0, int I, int J,
            boolean warm, double R) {
            this.type = type;
            this.x = x;
            this.y = y;
            this.ux = ux;
            this.uy = uy;
            this.N = N;
            this.sp = sp;
            this.H0 = H0;
            this.I = I;
            this.J = J;
            this.warm = warm;
            this.R = R;
        }
    }

    private static final Object NONE = new Object();
    private static final int CACHE_MAX = 4096;
    private final ConcurrentHashMap<Long, Object> cache = new ConcurrentHashMap<>();

    public VolcanismStage(WorldGen gen) {
        super(gen);
    }

    public void clearCache() {
        cache.clear();
    }

    private static long key(int I, int J) {
        return net.junedev.viridium.worldgen.terrain.core.GenMath.mixKey(((long) I << 32) | (J & 0xffffffffL));
    }

    /** Hotspot of coarse cell (I, J), or null. */
    public Hotspot hotspot(int I, int J, GenScratch s) {
        long key = key(I, J);
        Object c = cache.get(key);
        if (c != null) return c == NONE ? null : (Hotspot) c;
        Hotspot h = resolve(I, J, s);
        if (cache.size() > CACHE_MAX) cache.clear();
        cache.put(key, h == null ? NONE : h);
        return h;
    }

    protected Hotspot resolve(int I, int J, GenScratch s) {
        double S = P.plateSize, HS = S * 1.7;
        if (rnd(I, J, SD.hot) >= P.hotspotChance) return null;
        double hx = (I + 0.15 + 0.7 * rnd(I, J, SD.hot + 1)) * HS, hy = (J + 0.15 + 0.7 * rnd(I, J, SD.hot + 2)) * HS;
        GenPoint p = s.hotspotPoint;
        PlateStage plates = gen.plates;
        plates.apply(hx, hy, p, s);
        gen.continents.apply(hx, hy, p);
        if (p.conv < -0.08 && p.edge < 0.25 * S && p.L < -0.2) {
            // hotspot on a ridge → snap onto the ridge axis (Iceland)
            for (int it = 0; it < 4; it++) {
                double nx = plates.featX(p.ni, p.nj) - p.fx, ny = plates.featY(p.ni, p.nj) - p.fy;
                double l = hypot(nx, ny);
                if (l == 0) l = 1;
                hx += nx / l * p.edge;
                hy += ny / l * p.edge;
                plates.apply(hx, hy, p, s);
            }
            gen.continents.apply(hx, hy, p);
            if (p.L < -0.25) return new Hotspot(
                TYPE_ICELAND,
                hx,
                hy,
                0,
                0,
                0,
                0,
                0,
                I,
                J,
                false,
                (0.1 + 0.07 * rnd(I, J, SD.hot + 3)) * S);
            return null;
        } else if (p.L < -0.1) {
            plates.plateVel(p.pi, p.pj, s);
            double l = hypot(s.VX, s.VY);
            if (l == 0) l = 1;
            return new Hotspot(
                TYPE_CHAIN,
                hx,
                hy,
                s.VX / l,
                s.VY / l,
                5 + floor(8 * rnd(I, J, SD.hot + 4)),
                230 + 160 * rnd(I, J, SD.hot + 5),
                150 + 80 * rnd(I, J, SD.hot + 6),
                I,
                J,
                Math.cos(TAU * hy / P.tempPeriod) > 0.45,
                0);
        } else if (p.L > 0.12 && P.continentalHotspots > 0) { // under a continent: Yellowstone
            plates.plateVel(p.pi, p.pj, s);
            double l = hypot(s.VX, s.VY);
            if (l == 0) l = 1;
            return new Hotspot(
                TYPE_CONTINENT,
                hx,
                hy,
                s.VX / l,
                s.VY / l,
                0,
                0,
                0,
                I,
                J,
                false,
                900 + 600 * rnd(I, J, SD.hot + 3));
        }
        return null;
    }

    /** Hotspots in the 3x3 coarse cells around (x, y). */
    public List<Hotspot> hotspotsNear(double x, double y, GenScratch s) {
        double HS = P.plateSize * 1.7;
        int I0 = floor(x / HS), J0 = floor(y / HS);
        long key = key(I0, J0);
        if (s.hotValid && s.hotKey == key) return s.hotList;
        List<Hotspot> list = new ArrayList<>(4);
        for (int dj = -1; dj <= 1; dj++) for (int di = -1; di <= 1; di++) {
            Hotspot v = hotspot(I0 + di, J0 + dj, s);
            if (v != null) list.add(v);
        }
        s.hotList = list;
        s.hotKey = key;
        s.hotValid = true;
        return list;
    }

    /** Raises {@code h} with nearby volcanic edifices; returns the new height. */
    public double apply(double x, double y, GenPoint o, double h, boolean detail, GenScratch s) {
        List<Hotspot> list = hotspotsNear(x, y, s);
        for (int q = 0; q < list.size(); q++) {
            Hotspot v = list.get(q);
            double dx = x - v.x, dy = y - v.y;
            if (v.type == TYPE_CONTINENT) {
                if (Math.abs(dx) < 0.7 * P.plateSize && Math.abs(dy) < 0.7 * P.plateSize)
                    h = continental(v, x, y, o, h, detail);
                continue;
            }
            if (v.type == TYPE_CHAIN) {
                // volcano chain: youngest over the hotspot, older ones dragged along by the plate
                double along = dx * v.ux + dy * v.uy;
                if (along < -450 || along > v.N * v.sp + 450) continue;
                if (Math.abs(dx * v.uy - dy * v.ux) > 520) continue;
                int kc = (int) Math.round(along / v.sp);
                for (int k = Math.max(0, kc - 1); k <= Math.min(v.N - 1, kc + 1); k++) {
                    double jx = (rnd(v.I * 31 + k, v.J, SD.hot + 7) - 0.5) * 160,
                        jy = (rnd(v.I * 31 + k, v.J, SD.hot + 8) - 0.5) * 160;
                    double cx = v.x + v.ux * k * v.sp + jx, cy = v.y + v.uy * k * v.sp + jy;
                    double top = -62 + v.H0 * Math.exp(-0.32 * k);
                    double R = 80 + 1.3 * (top + 62);
                    double r0 = hypot(x - cx, y - cy);
                    if (r0 >= R * 1.45) continue;
                    // irregular outline (lava flows, collapsed flanks) and radial gullies on the slopes
                    double r = r0 * (1 + 0.32 * fbm(x / (0.45 * R), y / (0.45 * R), SD.hot + 12 + k, detail ? 3 : 2));
                    if (r >= R) continue;
                    double t = 1 - r / R, prof = Math.pow(t, 1.7);
                    double rid = ridged(x / 70, y / 70, SD.hot + 9, detail ? 3 : 1);
                    double hv = -66 + (top + 66) * prof + (top + 66) * 0.18 * (rid - 0.5) * prof * (1 - prof) * 4;
                    if (k == 0) { // caldera
                        double cr = r / (0.11 * R);
                        hv -= 14 * Math.exp(-cr * cr);
                    }
                    int type = 2;
                    if (v.warm && top > -26 && top < 3) {
                        // atoll: the volcano sinks while the coral reef keeps growing in a ring up to the surface
                        double u = r / R, lagoon = u < 0.3 ? -5 : -5 - (u - 0.3) * 250;
                        double ring = Math
                            .max(lagoon, 1.5 - 70 * Math.abs(u - 0.3) + 0.8 * perlin(x / 9, y / 9, SD.hot + 14));
                        if (ring > hv) {
                            hv = ring;
                            type = 4;
                        }
                    }
                    if (hv > h) {
                        h = hv;
                        o.volc = Math.max(o.volc, prof);
                        o.volcType = type;
                    }
                }
            } else {
                // Iceland: large basaltic shield on the ridge, with a rift valley along the axis
                double r = hypot(dx, dy);
                if (r > 1.6 * v.R) continue;
                double rr = r / v.R + 0.32 * fbm(x / (0.9 * v.R), y / (0.9 * v.R), SD.hot + 10, detail ? 4 : 2)
                    + 0.08 * fbm(x / 120, y / 120, SD.hot + 13, 2);
                double shape = ss(1.05, 0.7, rr);
                if (shape <= 0) continue;
                double rd = o.edge / 110;
                double rv = ridged(x / 520, y / 520, SD.hot + 11, detail ? 4 : 2);
                double top = 14 + 50 * clamp(1 - rr, 0, 1) + 75 * rv * rv * shape - 24 * Math.exp(-rd * rd);
                double hv = -62 + (top + 62) * shape;
                o.geo = Math.max(o.geo, 0.6 * shape * Math.exp(-rd * rd / 4)); // geothermal along the rift
                if (hv > h) {
                    h = hv;
                    o.volc = Math.max(o.volc, shape);
                    o.volcType = 3;
                }
            }
        }
        return h;
    }

    // ------------------------------------------------------------------ stratovolcanoes on the arcs

    /** Spacing (blocks) of the stratovolcano cells along the arcs. */
    private static final double STRATO_CELL = 2600;

    private final ConcurrentHashMap<Long, Object> stratoCache = new ConcurrentHashMap<>();
    private final ThreadLocal<GenPoint> planPoint = ThreadLocal.withInitial(GenPoint::new);
    private final ThreadLocal<GenScratch> planScratch = ThreadLocal.withInitial(GenScratch::new);
    private final ThreadLocal<GenPoint> ventPoint = ThreadLocal.withInitial(GenPoint::new);
    private final ThreadLocal<GenScratch> ventScratch = ThreadLocal.withInitial(GenScratch::new);

    /** A stratovolcano: centre, height above its base, base radius, caldera radius (0 = a summit crater). */
    static final class Strato {

        final double x, y, H, R, Rc;

        Strato(double x, double y, double H, double R, double Rc) {
            this.x = x;
            this.y = y;
            this.H = H;
            this.R = R;
            this.Rc = Rc;
        }
    }

    private Strato strato(int i, int j) {
        long k = key(i, j);
        Object c = stratoCache.get(k);
        if (c != null) return c == NONE ? null : (Strato) c;
        Strato v = null;
        if (rnd(i, j, SD.hot + 20) < 0.7) {
            double cx = (i + 0.2 + 0.6 * rnd(i, j, SD.hot + 21)) * STRATO_CELL,
                cy = (j + 0.2 + 0.6 * rnd(i, j, SD.hot + 22)) * STRATO_CELL;
            GenPoint p = planPoint.get();
            gen.plates.apply(cx, cy, p, planScratch.get());
            if (p.arc > 0.12) { // on the arc (volcanic front), where the subducting slab melts
                double H = (110 + 150 * rnd(i, j, SD.hot + 23)) * Math.min(1, p.arc * 2.5);
                double R = H * (3.2 + 1.6 * rnd(i, j, SD.hot + 24));
                // a quarter of them collapsed into a caldera after a great eruption (Crater Lake, Santorini, Aso)
                double Rc = rnd(i, j, SD.hot + 25) < 0.25 ? (0.17 + 0.08 * rnd(i, j, SD.hot + 26)) * R : 0;
                v = new Strato(cx, cy, H, R, Rc);
            }
        }
        if (stratoCache.size() > CACHE_MAX) stratoCache.clear();
        stratoCache.put(k, v == null ? NONE : v);
        return v;
    }

    /**
     * Stratovolcanoes of the volcanic arcs (Fuji, Rainier, Mayon, the Andes): steep, concave cones of lava and ash
     * 110-260
     * blocks high, one per 2.6 km cell (70 %) where the arc is strong, with a summit crater or (a quarter of them) a
     * caldera whose flat floor sits at 55 % of the height: a crater lake where the climate is wet enough (the river
     * networks fill it as any closed depression). The steep slopes get deep radial gullies from the erosion.
     *
     * @return the height added at (x, y) (0 outside every cone)
     */
    public double stratovolcanoes(double x, double y, GenPoint o) {
        if (P.stratovolcanoes <= 0) return 0;
        int ci = floor(x / STRATO_CELL), cj = floor(y / STRATO_CELL);
        double best = 0;
        for (int dj = -1; dj <= 1; dj++) for (int di = -1; di <= 1; di++) {
            Strato v = strato(ci + di, cj + dj);
            if (v == null) continue;
            double dx = x - v.x, dy = y - v.y;
            if (Math.abs(dx) > v.R * 1.25 || Math.abs(dy) > v.R * 1.25) continue;
            double r = Math.sqrt(dx * dx + dy * dy) * (1 + 0.12 * fbm(x / 300, y / 300, SD.hot + 27, 2));
            if (r >= v.R) continue;
            double H = v.H * P.stratovolcanoes, prof = Math.pow(1 - r / v.R, 2.0), hv = H * prof;
            if (v.Rc > 0) { // caldera: a flat floor inside steep walls
                double Rw = 1.15 * v.Rc, rim = H * Math.pow(1 - Rw / v.R, 2.0), fl = 0.55 * H;
                if (r < Rw) hv = fl + (rim - fl) * ss(v.Rc, Rw, r);
                o.geo = Math.max(o.geo, 0.7 * ss(1.6 * v.Rc, 0.8 * v.Rc, r));
            } else { // summit crater
                double rc = 0.06 * v.R, cr = r / rc;
                hv -= 0.06 * H * Math.exp(-cr * cr);
                o.geo = Math.max(o.geo, 0.35 * ss(0.25 * v.R, 0.05 * v.R, r));
            }
            if (hv > best) {
                best = hv;
                o.volc = Math.max(o.volc, prof);
                o.volcType = 1;
            }
        }
        return best;
    }

    /** Stratovolcanoes whose centre lies in the box (debugging): x, y, height, radius, caldera radius. */
    public List<double[]> stratovolcanoesIn(double x0, double y0, double x1, double y1) {
        List<double[]> out = new ArrayList<>();
        for (int j = floor(y0 / STRATO_CELL); j <= floor(y1 / STRATO_CELL); j++)
            for (int i = floor(x0 / STRATO_CELL); i <= floor(x1 / STRATO_CELL); i++) {
                Strato v = strato(i, j);
                if (v != null && v.x >= x0 && v.x <= x1 && v.y >= y0 && v.y <= y1)
                    out.add(new double[] { v.x, v.y, v.H, v.R, v.Rc });
            }
        return out;
    }

    /** Volcanic fields whose centre lies in the box (debugging): x, y, radius, density, cold (1) or not. */
    public List<double[]> volcanicFieldsIn(double x0, double y0, double x1, double y1) {
        List<double[]> out = new ArrayList<>();
        for (int j = floor(y0 / FIELD_CELL); j <= floor(y1 / FIELD_CELL); j++)
            for (int i = floor(x0 / FIELD_CELL); i <= floor(x1 / FIELD_CELL); i++) {
                Field f = field(i, j);
                if (f != null && f.x >= x0 && f.x <= x1 && f.y >= y0 && f.y <= y1)
                    out.add(new double[] { f.x, f.y, f.R, f.dens, f.cold ? 1 : 0 });
            }
        return out;
    }

    // ------------------------------------------------------------------ volcanic fields

    /** Size (blocks) of the cells that may hold one volcanic field, and of the cells holding one vent. */
    private static final double FIELD_CELL = 7000, VENT_CELL = 260;

    /** A volcanic field: many small vents over a region. Immutable. */
    static final class Field {

        final double x, y, R, dens;
        final boolean cold;

        Field(double x, double y, double R, double dens, boolean cold) {
            this.x = x;
            this.y = y;
            this.R = R;
            this.dens = dens;
            this.cold = cold;
        }
    }

    private final ConcurrentHashMap<Long, Object> fieldCache = new ConcurrentHashMap<>();
    /** Downhill direction at each vent with a lava flow (x, y), memoised per vent cell. */
    private final ConcurrentHashMap<Long, double[]> ventCache = new ConcurrentHashMap<>();
    /** Set while a vent plans its flow: the base heights it samples skip the fields (no recursion). */
    private static final ThreadLocal<boolean[]> PLANNING = ThreadLocal.withInitial(() -> new boolean[1]);

    private Field field(int i, int j) {
        long k = key(i, j);
        Object c = fieldCache.get(k);
        if (c != null) return c == NONE ? null : (Field) c;
        Field f = null;
        double cx = (i + 0.25 + 0.5 * rnd(i, j, SD.hot + 30)) * FIELD_CELL,
            cy = (j + 0.25 + 0.5 * rnd(i, j, SD.hot + 31)) * FIELD_CELL;
        GenPoint p = planPoint.get();
        GenScratch s = planScratch.get();
        gen.plates.apply(cx, cy, p, s);
        gen.continents.apply(cx, cy, p);
        if (p.L > 0.05) {
            // where the crust is stretched or above a slab: rifts, behind the arcs; a few anywhere (intraplate)
            double chance = 0.1 + 0.55 * (p.rift > 0.2 && p.riftD < 2.5 ? 1 : 0) + 0.3 * ss(0.15, 0.5, p.orog);
            if (rnd(i, j, SD.hot + 32) < chance * Math.min(P.volcanicFields, 2)) {
                gen.climate.apply(cx, cy, p);
                boolean cold = p.Tb - P.glacialCooling < -5; // erupted under the ice sheets of the last glaciation
                f = new Field(cx, cy, 1500 + 1700 * rnd(i, j, SD.hot + 33), 0.35 + 0.35 * rnd(i, j, SD.hot + 34), cold);
            }
        }
        if (fieldCache.size() > CACHE_MAX) fieldCache.clear();
        fieldCache.put(k, f == null ? NONE : f);
        return f;
    }

    /** Downhill direction of the base relief at a vent (its lava runs that way). */
    private double[] ventDownhill(int a, int b, double vx, double vy) {
        long k = key(a, b) ^ 0x9e3779b97f4a7c15L;
        double[] d = ventCache.get(k);
        if (d != null) return d;
        boolean[] g = PLANNING.get();
        g[0] = true;
        try {
            GenPoint p = ventPoint.get(); // not the planners': the base heights below use those
            GenScratch s = ventScratch.get();
            final double E = 50;
            double[] hh = new double[4];
            for (int q = 0; q < 4; q++) {
                double px = vx + (q == 0 ? E : q == 1 ? -E : 0), py = vy + (q == 2 ? E : q == 3 ? -E : 0);
                gen.plates.apply(px, py, p, s);
                gen.continents.apply(px, py, p);
                hh[q] = gen.relief.baseHeight(px, py, p, false, s);
            }
            double gx = hh[1] - hh[0], gy = hh[3] - hh[2], l = Math.hypot(gx, gy); // downhill
            if (l < 1e-3) {
                double an = TAU * rnd(a, b, SD.hot + 47);
                gx = Math.cos(an);
                gy = Math.sin(an);
                l = 1;
            }
            d = new double[] { gx / l, gy / l };
        } finally {
            g[0] = false;
        }
        if (ventCache.size() > CACHE_MAX * 4) ventCache.clear();
        ventCache.put(k, d);
        return d;
    }

    /**
     * Volcanic fields: regions of many small vents that each erupted once (monogenetic: the Garrotxa, the Auvergne, the
     * San Francisco field, Lanzarote), in rifts, behind the arcs and a few anywhere. Within 1.5-3.2 km of the field's
     * centre, one candidate vent per 260-block cell, denser in the middle:
     * <ul>
     * <li>cinder cones (scoria, 30-70 blocks across the base, a third as high, a summit crater; a third breached on one
     * side), half of them with a lava flow running downhill 250-950 blocks: a rough tongue of basalt 2-5 blocks thick
     * with steep margins (malpaís);</li>
     * <li>maars (a fifth): explosion craters below the ground with a low ring of tephra, which hold lakes in wet
     * climates;</li>
     * <li>tuyas, where the field lay under the ice sheets: flat-topped, steep-sided table volcanoes (Herðubreið).</li>
     * </ul>
     *
     * @return the new height
     */
    public double volcanicFields(double x, double y, GenPoint o, double h, boolean detail) {
        if (P.volcanicFields <= 0 || o.L < 0.02 || PLANNING.get()[0]) return h;
        int fi = floor(x / FIELD_CELL), fj = floor(y / FIELD_CELL);
        Field f = null;
        double fd = 1e9;
        for (int dj = -1; dj <= 1; dj++) for (int di = -1; di <= 1; di++) {
            Field c = field(fi + di, fj + dj);
            if (c == null) continue;
            double d = Math.hypot(x - c.x, y - c.y);
            if (d < c.R + 1000 && d < fd) {
                f = c;
                fd = d;
            }
        }
        if (f == null) return h;
        int vi = floor(x / VENT_CELL), vj = floor(y / VENT_CELL);
        double add = 0, sub = 0;
        for (int dj = -2; dj <= 2; dj++) for (int di = -2; di <= 2; di++) {
            int a = vi + di, b = vj + dj;
            double vx = (a + 0.15 + 0.7 * rnd(a, b, SD.hot + 40)) * VENT_CELL,
                vy = (b + 0.15 + 0.7 * rnd(a, b, SD.hot + 41)) * VENT_CELL;
            double dens = f.dens * ss(f.R, 0.5 * f.R, Math.hypot(vx - f.x, vy - f.y));
            if (rnd(a, b, SD.hot + 42) >= dens) continue;
            double kind = rnd(a, b, SD.hot + 43), dx = x - vx, dy = y - vy, r = Math.sqrt(dx * dx + dy * dy);
            if (kind < 0.2) { // maar
                double Rm = 40 + 60 * rnd(a, b, SD.hot + 44), Dm = 9 + 12 * rnd(a, b, SD.hot + 45);
                if (r > 1.5 * Rm) continue;
                double q = r / Rm;
                if (q < 1) sub = Math.max(sub, Dm * (1 - q * q * q * q));
                double ring = 4 * ss(0.9, 1.05, q) * (1 - ss(1.05, 1.45, q));
                if (ring > add) add = ring;
                if (q < 1.4 && q > 0.95) setSurf(o, GenPoint.SURF_ASH);
                o.volc = Math.max(o.volc, 0.3);
                o.volcType = 6;
                continue;
            }
            double Rb = 30 + 40 * rnd(a, b, SD.hot + 46);
            if (f.cold && kind < 0.55) { // tuya: a table volcano that erupted under the ice
                double Rt = 2.5 * Rb, Ht = 1.2 * Rb + 10;
                if (r > Rt * 1.2) continue;
                double rr = r * (1 + 0.1 * perlin(x / 25, y / 25, SD.hot + 48));
                double hv = Ht * ss(Rt, 0.86 * Rt, rr) + 3 * ss(0.8 * Rt, 0, rr);
                if (hv > add) add = hv;
                o.scarp = Math.max(o.scarp, ss(1.15 * Rt, Rt, rr) * ss(0.7 * Rt, 0.85 * Rt, rr));
                o.volc = Math.max(o.volc, 0.5);
                o.volcType = 6;
                continue;
            }
            // cinder cone with a summit crater, a third of them breached on the side their lava left by
            double H = Rb * (0.33 + 0.09 * rnd(a, b, SD.hot + 49));
            boolean flow = rnd(a, b, SD.hot + 50) < 0.5;
            if (r < Rb) {
                double prof = Math.pow(1 - r / Rb, 1.25), hv = H * prof;
                double rc = 0.32 * Rb, rim = H * Math.pow(1 - rc / Rb, 1.25);
                if (r < rc) hv = rim - 0.4 * H * (1 - (r / rc) * (r / rc));
                if (flow && rnd(a, b, SD.hot + 51) < 0.6 && r < 0.75 * Rb) { // breached towards the flow
                    double[] dd = ventDownhill(a, b, vx, vy);
                    double c = r > 1e-6 ? (dx * dd[0] + dy * dd[1]) / r : 0;
                    hv -= 0.7 * hv * ss(0.55, 0.9, c) * ss(0.75 * Rb, 0.3 * Rb, r);
                }
                if (hv > add) add = hv;
                if (hv > 0.5) setSurf(o, GenPoint.SURF_CINDER);
                o.volc = Math.max(o.volc, prof);
                o.volcType = 6;
                o.geo = Math.max(o.geo, 0.15 * ss(Rb, 0, r));
            }
            if (flow) {
                double Lf = 200 + 550 * rnd(a, b, SD.hot + 52);
                if (Math.abs(dx) > Lf + 80 || Math.abs(dy) > Lf + 80) continue;
                double[] dd = ventDownhill(a, b, vx, vy);
                double u = dx * dd[0] + dy * dd[1], w = -dx * dd[1] + dy * dd[0];
                if (u < 0.3 * Rb || u > Lf + 60) continue;
                // widens towards its front, and swells and narrows into lobes along the way
                double wf = (16 + 22 * rnd(a, b, SD.hot + 53)) * (0.55 + 0.9 * u / Lf)
                    * (0.75 + 0.5 * clamp(0.5 + fbm(u / 70, a * 0.53 + b * 0.29, SD.hot + 57, 2), 0, 1));
                double wob = 0.14 * Lf * fbm(u / (0.35 * Lf), a * 0.37 + b * 0.11, SD.hot + 54, 2);
                double off = Math.abs(w - wob), end = (u - Lf) / wf;
                double k = ss(wf, wf - 5, off) * (end > 0 ? ss(1, 0.6, Math.hypot(end, off / wf)) : 1);
                if (k <= 0) continue;
                double th = (2.5 + 3 * rnd(a, b, SD.hot + 55)) * k
                    + (detail ? 1.2 * k * ridged(x / 7, y / 7, SD.hot + 56, 2) : 0); // blocky aa surface
                if (th > add) add = th;
                if (k > 0.3) setSurf(o, GenPoint.SURF_LAVA);
                o.volc = Math.max(o.volc, 0.2);
                o.volcType = 6;
            }
        }
        return h + add - sub;
    }

    private static void setSurf(GenPoint o, int surf) {
        o.surf = surf;
    }

    // ------------------------------------------------------------------ continental hotspot (Yellowstone)

    /**
     * A hotspot under a continent (Yellowstone, the Eifel's bigger cousins): a broad swell, a caldera 1.8-3 km across
     * with a rim, a flat floor and two resurgent domes, a geothermal field all round it, and the hotspot's track, a
     * flat
     * lowland of basalt flows trailing along the plate's motion (the Snake River Plain) where older calderas stood.
     */
    private double continental(Hotspot v, double x, double y, GenPoint o, double h, boolean detail) {
        double S = P.plateSize, dx = x - v.x, dy = y - v.y, r = Math.hypot(dx, dy), Rc = v.R;
        double along = dx * v.ux + dy * v.uy, across = Math.abs(-dx * v.uy + dy * v.ux);
        double sw = 35 * Math.exp(-(r / (0.22 * S)) * (r / (0.22 * S))) * P.continentalHotspots;
        double out = h + sw;
        // the track: older calderas worn down and buried by basalt flows
        if (along > 0.4 * Rc && along < 0.6 * S && across < 1600) {
            double tm = ss(1600, 900, across) * ss(0.4 * Rc, 1.2 * Rc, along) * (1 - ss(0.45 * S, 0.6 * S, along));
            double plain = Math.min(out, 18 + 6 * fbm(x / 900, y / 900, SD.hot + 60, 2) + sw);
            out = out + (plain - out) * 0.85 * tm;
            if (tm > 0.4) {
                o.volc = Math.max(o.volc, 0.3 * tm);
                o.volcType = 5;
                if (rnd(floor(x / 300), floor(y / 300), SD.hot + 61) < 0.3) setSurf(o, GenPoint.SURF_LAVA);
            }
        }
        // the caldera
        if (r < 1.5 * Rc) {
            double q = r / Rc * (1 + 0.1 * fbm(x / 600, y / 600, SD.hot + 62, 2));
            double rim = 22 * ss(0.85, 1.0, q) * (1 - ss(1.05, 1.5, q));
            double floor = Math.min(out, 12 + sw - 4 + 4 * fbm(x / 200, y / 200, SD.hot + 63, 2));
            // two resurgent domes on the floor
            double d1 = Math.hypot(dx - 0.35 * Rc * v.ux, dy - 0.35 * Rc * v.uy) / (0.28 * Rc),
                d2 = Math.hypot(dx + 0.3 * Rc * v.uy, dy - 0.3 * Rc * v.ux) / (0.22 * Rc);
            floor += 28 * Math.exp(-d1 * d1) + 22 * Math.exp(-d2 * d2);
            out = q < 0.9 ? floor : q < 1.0 ? floor + (out + rim - floor) * ss(0.9, 1.0, q) : out + rim;
            o.volc = Math.max(o.volc, 0.6);
            o.volcType = 5;
        }
        o.geo = Math.max(o.geo, ss(1.9 * Rc, 0.9 * Rc, r) * P.continentalHotspots);
        return out;
    }

    // ------------------------------------------------------------------ geothermal fields and basalt causeways

    /**
     * Geothermal fields, after the rivers (they need the final ground): where {@code o.geo} is high (calderas, the
     * summits of young volcanoes, the Iceland-like ridge, continental hotspots, the vents of volcanic fields). One
     * candidate per 40-block cell:
     * <ul>
     * <li>hot spring terraces (Mammoth, Pamukkale, Grand Prismatic): pools of water stepping down the slope, each level
     * held by a rim of sinter (geyserite), ringed by mats of orange and yellow bacteria;</li>
     * <li>geyser cones: low mounds of sinter with a vent;</li>
     * <li>fumarole ground: bleached, bare patches.</li>
     * </ul>
     * Pools sit on gentle ground only; the water level of a pool column is its own one-block level, and the columns
     * between two levels are rims: water never stands above a lower neighbour.
     */
    public void geothermal(double x, double y, GenPoint o) {
        if (P.geothermal <= 0 || o.geo < 0.15 || o.h < 1.5 || o.river != 0 || o.lake != 0 || o.slope > 0.3) return;
        final double C = 40;
        int ci = floor(x / C), cj = floor(y / C);
        for (int dj = -1; dj <= 1; dj++) for (int di = -1; di <= 1; di++) {
            int a = ci + di, b = cj + dj;
            if (rnd(a, b, SD.hot + 70) >= 0.16 * o.geo * Math.min(P.geothermal, 4)) continue;
            double cx = (a + 0.25 + 0.5 * rnd(a, b, SD.hot + 71)) * C,
                cy = (b + 0.25 + 0.5 * rnd(a, b, SD.hot + 72)) * C;
            double r = Math.hypot(x - cx, y - cy), kind = rnd(a, b, SD.hot + 73);
            if (kind < 0.6) { // hot spring terraces
                double Rp = 7 + 10 * rnd(a, b, SD.hot + 74);
                if (r > Rp + 5) continue;
                if (r > Rp) { // sinter apron with bacterial mats
                    o.surf = (int) Math.floor(r * 0.9 + 3 * rnd(a, b, SD.hot + 75)) % 2 == 0 ? GenPoint.SURF_MAT
                        : GenPoint.SURF_MAT2;
                    return;
                }
                double lv = Math.floor(o.h - 0.3), fr = o.h - 0.3 - lv;
                o.surf = GenPoint.SURF_SINTER;
                if (fr < 0.2 || fr > 0.85 || r > 0.85 * Rp) return; // a rim between two levels, or the pool's edge
                o.lake = 1;
                o.lakeZ = lv + 0.5;
                o.h = Math.min(o.h, o.lakeZ - 1 - 1.5 * (1 - r / Rp));
                return;
            }
            if (kind < 0.85) { // geyser cone
                double Rg = 4 + 3 * rnd(a, b, SD.hot + 76);
                if (r > Rg + 9) continue;
                o.surf = GenPoint.SURF_SINTER;
                if (r < Rg) o.h += (2 + 2 * rnd(a, b, SD.hot + 77)) * (1 - r / Rg) - (r < 0.8 ? 1.5 : 0);
                return;
            }
            double Rf = 6 + 8 * rnd(a, b, SD.hot + 78); // fumarole ground
            if (r < Rf * (1 + 0.3 * perlin(x / 4, y / 4, SD.hot + 79))) {
                o.surf = GenPoint.SURF_ASH;
                return;
            }
        }
    }

    /**
     * Basalt causeways: where a basalt flow meets the sea it cools into hexagonal columns, and the waves leave their
     * tops
     * as stepping stones of different heights (the Giant's Causeway, Staffa, Reynisfjara). On basalt shores (a quarter
     * of them), hexagons 2-3 blocks across whose tops step by a block.
     */
    public void causeway(double x, double y, GenPoint o) {
        if (P.causeways <= 0 || o.h < -2.5 || o.h > 6 || o.L < -0.05 || o.L > 0.04 || o.river != 0 || o.lake != 0)
            return;
        if (o.rock != Rock.BASALT.ordinal() || o.rockT < 0.6) return;
        if (fbm(x / 2500, y / 2500, SD.hot + 80, 2) < 0.15 * (2 - Math.min(P.causeways, 2))) return;
        // hexagonal cell: axial coordinates rounded in cube space
        final double sz = 1.4;
        double q = (2.0 / 3 * x) / sz, rr = (-1.0 / 3 * x + Math.sqrt(3) / 3 * y) / sz;
        double cxq = q, czq = rr, cyq = -q - rr;
        long rx = Math.round(cxq), ry = Math.round(cyq), rz = Math.round(czq);
        double ex = Math.abs(rx - cxq), ey = Math.abs(ry - cyq), ez = Math.abs(rz - czq);
        if (ex > ey && ex > ez) rx = -ry - rz;
        else if (ey > ez) ry = -rx - rz;
        else rz = -rx - ry;
        double step = Math.floor(3 * rnd((int) rx, (int) rz, SD.hot + 81)) - 1; // -1, 0 or +1 block
        o.h = Math.floor(o.h) + 0.5 + step;
        o.surf = GenPoint.SURF_LAVA;
    }
}
