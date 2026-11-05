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

    public static final int TYPE_CHAIN = 1, TYPE_ICELAND = 2;

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
        return ((long) I << 32) | (J & 0xffffffffL);
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
            if (p.L < -0.25)
                return new Hotspot(TYPE_ICELAND, hx, hy, 0, 0, 0, 0, 0, I, J, false,
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
                if (hv > h) {
                    h = hv;
                    o.volc = Math.max(o.volc, shape);
                    o.volcType = 3;
                }
            }
        }
        return h;
    }
}
