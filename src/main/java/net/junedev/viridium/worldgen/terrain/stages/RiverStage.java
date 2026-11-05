package net.junedev.viridium.worldgen.terrain.stages;

import static net.junedev.viridium.worldgen.terrain.core.GenMath.clamp;
import static net.junedev.viridium.worldgen.terrain.core.GenMath.floor;
import static net.junedev.viridium.worldgen.terrain.core.GenMath.lerp;
import static net.junedev.viridium.worldgen.terrain.core.GenMath.ss;
import static net.junedev.viridium.worldgen.terrain.core.Noise.fbm;
import static net.junedev.viridium.worldgen.terrain.core.Noise.perlin;
import static net.junedev.viridium.worldgen.terrain.core.Noise.rnd;
import static net.junedev.viridium.worldgen.terrain.stages.RiverNet.NPT;
import static net.junedev.viridium.worldgen.terrain.stages.RiverNet.SEG;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicLong;

import net.junedev.viridium.worldgen.terrain.WorldGen;
import net.junedev.viridium.worldgen.terrain.pipeline.GenPoint;
import net.junedev.viridium.worldgen.terrain.pipeline.GenScratch;

/**
 * 10. Rivers, lakes, broad valleys, gorges, glacial troughs and fjords, deltas and estuaries. Queries a per-plate
 * {@link RiverNet}, built once and cached (LRU, deduplicated across threads).
 */
public class RiverStage extends GenStage {

    public static final int CACHE_MAX = 24;

    /** Finished networks with their last-use stamp (lock-free reads; eviction by least recent use). */
    private final ConcurrentHashMap<Long, Cached> cache = new ConcurrentHashMap<>();
    private final AtomicLong clock = new AtomicLong();

    private static final class Cached {

        final RiverNet net;
        volatile long used;

        Cached(RiverNet net, long used) {
            this.net = net;
            this.used = used;
        }
    }
    private final ConcurrentHashMap<Long, FutureTask<RiverNet>> building = new ConcurrentHashMap<>();
    private final AtomicLong builds = new AtomicLong(), buildNanos = new AtomicLong();

    public RiverStage(WorldGen gen) {
        super(gen);
    }

    // ------------------------------------------------------------------ network cache

    private static long key(int pi, int pj) {
        return ((long) pi << 32) | (pj & 0xffffffffL);
    }

    /** River network of plate (pi, pj), building it if needed (other threads wait for the same build). */
    public RiverNet net(int pi, int pj) {
        long key = key(pi, pj);
        Cached hit = cache.get(key);
        if (hit != null) {
            long now = clock.get(); // the clock only ticks on inserts: plain reads write nothing
            if (hit.used < now) hit.used = now;
            return hit.net;
        }
        FutureTask<RiverNet> created = new FutureTask<>(() -> {
            long t0 = System.nanoTime();
            RiverNet n = new RiverNetBuilder(gen).build(pi, pj, new GenScratch()); // builder holds per-build state
            buildNanos.addAndGet(System.nanoTime() - t0);
            net.junedev.viridium.worldgen.terrain.GenStats.RIVER_NET.add(System.nanoTime() - t0);
            builds.incrementAndGet();
            return n;
        });
        FutureTask<RiverNet> task = building.putIfAbsent(key, created);
        if (task == null) {
            task = created;
            task.run();
        }
        try {
            RiverNet n = task.get();
            cache.putIfAbsent(key, new Cached(n, clock.incrementAndGet()));
            if (cache.size() > CACHE_MAX) evictOldest();
            return n;
        } catch (InterruptedException e) {
            Thread.currentThread()
                .interrupt();
            throw new IllegalStateException(e);
        } catch (ExecutionException e) {
            Throwable c = e.getCause();
            if (c instanceof RuntimeException re) throw re;
            if (c instanceof Error err) throw err;
            throw new IllegalStateException(c);
        } finally {
            if (task.isDone()) building.remove(key, task);
        }
    }

    private synchronized void evictOldest() {
        while (cache.size() > CACHE_MAX) {
            Long oldest = null;
            long best = Long.MAX_VALUE;
            for (java.util.Map.Entry<Long, Cached> e : cache.entrySet()) if (e.getValue().used < best) {
                best = e.getValue().used;
                oldest = e.getKey();
            }
            if (oldest == null) return;
            cache.remove(oldest);
        }
    }

    public boolean isCached(int pi, int pj) {
        return cache.containsKey(key(pi, pj));
    }

    public void clearCache() {
        cache.clear();
    }

    public long buildCount() {
        return builds.get();
    }

    public long buildNanos() {
        return buildNanos.get();
    }

    // ------------------------------------------------------------------ 10b. deltas

    protected void deltas(double x, double y, GenPoint o, RiverNet net) {
        float[] M = net.mouths;
        final int MS = RiverNet.MS;
        for (int q = 0; q < net.nMouth; q++) {
            int b = q * MS;
            double R = M[b + 4], E = M[b + 8];
            if (R <= 0 || E > 0.98) continue;
            double mx = M[b], my = M[b + 1];
            if (Math.abs(x - mx) > 1.3 * R || Math.abs(y - my) > 1.3 * R) continue;
            double ux = M[b + 2], uy = M[b + 3], hw = M[b + 5], qn = M[b + 6], id = M[b + 7];
            double axp = mx - ux * 0.35 * R, ayp = my - uy * 0.35 * R; // apex, a bit upstream of the mouth
            double vx = x - axp, vy = y - ayp, r = Math.sqrt(vx * vx + vy * vy);
            if (r > 1.35 * R) continue;
            double al = vx * ux + vy * uy, cr = vx * uy - vy * ux;
            double th = Math.atan2(cr, al);
            final double PHI = 1.05;
            double Rr = R * (0.8 + 0.3 * perlin(th * 2.2 + id * 0.37, id * 0.11, SD.delta));
            // fades near the plate edge, absent on formerly glaciated coasts, shrinks where the tide dominates
            double m = (1 - ss(PHI * 0.6, PHI, Math.abs(th))) * (1 - ss(Rr * 0.7, Rr, r))
                * ss(20, 90, o.edge)
                * (1 - glacial(o, 0))
                * (1 - E);
            if (m <= 0) continue;
            double hD = 0.6 + 2.2 * (1 - r / Rr) + 0.8 * perlin(x / 40, y / 40, SD.delta + 1);
            double hn = lerp(o.h, hD, m);
            if (o.h < hD || hn < o.h) o.h = hn;
            if (m > o.delta) o.delta = m;
            // distributary channels fanning out from the apex
            int K = (int) Math.min(7, 2 + Math.floor(qn / 12));
            for (int c = 0; c <= K; c++) {
                double tk = c == 0 ? 0 : (rnd((int) id, c, SD.delta + 2) - 0.5) * 2 * PHI * 0.75;
                tk += 0.14 * Math.sin(r / (0.17 * R) + c * 2.1 + id);
                double dist = r * Math.abs(th - tk);
                double wc = Math.max(1.0, hw * (c == 0 ? 0.8 : 0.45) * (1 - 0.45 * r / Rr));
                if (dist < wc && r > 0.05 * R && r < Rr * 1.05 && m > 0.2) {
                    double qq = dist / wc;
                    o.h = Math.min(o.h, lerp(o.h, -0.8 - 1.2 * (1 - qq * qq), ss(0.2, 0.6, m)));
                    o.river = 1;
                    if (o.riverD > -1) {
                        o.riverD = dist - wc;
                        o.riverHW = wc;
                        o.riverAcc = qn;
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ 10b'. estuaries

    protected void estuaries(double x, double y, GenPoint o, RiverNet net) {
        float[] M = net.mouths, pts = net.pts;
        int[] ES = net.estSeg;
        final int MS = RiverNet.MS;
        double h0 = o.h;
        double hmin = h0, flm = o.estu;
        for (int q = 0; q < net.nMouth; q++) {
            int b = q * MS;
            double E0 = M[b + 8];
            if (E0 <= 0.02) continue;
            double Lest = M[b + 9], Wm = M[b + 10], ext = Lest + 1.7 * Wm + 40;
            if (Math.abs(x - M[b]) > ext || Math.abs(y - M[b + 1]) > ext) continue;
            double E = E0 * ss(20, 90, o.edge) * (1 - glacial(o, 0));
            if (E <= 0) continue;
            int st = (int) M[b + 12], ne = (int) M[b + 13];
            double Lc = Lest / 3;
            double fn = 0.35 * perlin(x / 35, y / 35, SD.estu + 2);
            double cr = -1;
            // every reach proposes a profile; the minimum is continuous even when the nearest point jumps
            for (int e = 0; e < ne; e++) {
                int s = ES[st + e], B = s * 4;
                double pad = 1.7 * Wm + 30;
                if (x < net.bb[B] - pad || x > net.bb[B + 2] + pad || y < net.bb[B + 1] - pad || y > net.bb[B + 3] + pad)
                    continue;
                for (int k = 0; k < SEG; k++) {
                    int qq = (s * NPT + k) * 2;
                    double ax = pts[qq], ay = pts[qq + 1], vx = pts[qq + 2] - ax, vy = pts[qq + 3] - ay;
                    double l2 = vx * vx + vy * vy;
                    if (l2 == 0) l2 = 1e-9;
                    double t = clamp(((x - ax) * vx + (y - ay) * vy) / l2, 0, 1);
                    double dx = x - (ax + vx * t), dy = y - (ay + vy * t), d = Math.sqrt(dx * dx + dy * dy);
                    if (d > 1.7 * Wm + 20) continue;
                    double u = (k + t) / SEG, am = lerp(net.am0[s], net.am1[s], u);
                    double ef = Math.exp(-am / Lc) * (1 - ss(0.6 * Lest, Lest, am));
                    if (ef <= 0.002) continue;
                    double bw = lerp(net.w0[s], net.w1[s], u), bz = lerp(net.z0[s], net.z1[s], u);
                    double We = bw + (Wm - bw) * ef;
                    if (d > 1.6 * We + 18) continue;
                    double floorC = lerp(bz - 1.5, -1.6 - 4.5 * E0, ef); // central channel
                    double flat = lerp(bz + 0.2, 0.25, ef); // tidal flat / salt marsh
                    double qd = d / We;
                    double tgt, fl = 0;
                    if (qd < 0.45) tgt = lerp(floorC, flat - 0.6, (qd / 0.45) * (qd / 0.45));
                    else if (qd < 1) {
                        if (cr < 0) cr = Math.abs(fbm(x / 40 + 0.37, y / 40 + 0.71, SD.estu + 1, 3)); // tidal creeks
                        tgt = flat - 0.6 * (1 - ss(0.45, 0.6, qd))
                            + fn * ss(0.45, 0.6, qd)
                            - 1.4 * ef * (1 - ss(0.02, 0.07, cr)) * ss(0.5, 0.6, qd) * (1 - ss(0.85, 1, qd));
                        fl = ef * ss(0.4, 0.55, qd);
                    } else {
                        tgt = lerp(flat + fn, h0, ss(0, 18 + 0.6 * We, d - We));
                        fl = ef * (1 - ss(0, 10, d - We));
                    }
                    double hn = tgt < h0 ? lerp(h0, tgt, E) : h0;
                    if (hn < hmin) hmin = hn;
                    if (fl * E > flm) flm = fl * E;
                }
            }
        }
        o.h = hmin;
        o.estu = flm;
    }

    // ------------------------------------------------------------------ glaciation and valley field

    /** Was this valley under a glacier at the last glacial maximum? */
    public double glacial(GenPoint o, double z) {
        if (P.glacialCooling <= 0) return 0;
        double Tp = o.Tb - P.glacialCooling - P.lapse * Math.max(0, z);
        return ss(-1, -7, Tp) * ss(5.5, 6.2, Math.log(Math.max(o.P, 1)));
    }

    private static double glacialDepth(double z, double hw) {
        return 20 + 1.2 * hw + 26 * ss(0, 140, z);
    }

    private static void bspl(double t, double[] w) {
        double t2 = t * t, t3 = t2 * t;
        w[0] = (1 - 3 * t + 3 * t2 - t3) / 6;
        w[1] = (3 * t3 - 6 * t2 + 4) / 6;
        w[2] = (-3 * t3 + 3 * t2 + 3 * t + 1) / 6;
        w[3] = t3 / 6;
    }

    /** Samples the valley field with a cubic B-spline (C², no visible grid). Results in scratch F*. */
    protected void netField(double x, double y, RiverNet net, GenScratch s) {
        s.FOK = false;
        s.FEW = 0;
        int g = net.g;
        double fx = (x - net.x0) / g - 0.5, fy = (y - net.y0) / g - 0.5;
        int i0 = floor(fx) - 1, j0 = floor(fy) - 1;
        if (i0 < 0 || j0 < 0 || i0 + 3 >= net.nx || j0 + 3 >= net.ny) return;
        bspl(fx - i0 - 1, s.BWX);
        bspl(fy - j0 - 1, s.BWY);
        double D = 0, z = 0, w = 0, a = 0, rl = 0, wv = 0, e = 0, we = 0;
        for (int j = 0; j < 4; j++) {
            int row = (j0 + j) * net.nx + i0;
            for (int i = 0; i < 4; i++) {
                int k = row + i;
                double wt = s.BWX[i] * s.BWY[j];
                int v = net.vd[k];
                if (v == 65535) D += wt * 900;
                else {
                    D += wt * v;
                    z += wt * net.vz[k];
                    w += wt * (net.vw[k] & 255);
                    a += wt * (net.va[k] & 255);
                    rl += wt * (net.vr[k] & 255);
                    wv += wt;
                }
                int c = net.cer[k] & 255;
                if (c > 0) {
                    e += wt * c;
                    we += wt;
                }
            }
        }
        if (we > 0.05) {
            s.FE = e / we / 255;
            s.FEW = ss(0.05, 0.3, we);
        }
        if (wv < 0.02) return;
        s.FOK = true;
        s.FD = D;
        s.FZ = z / wv / 16;
        s.FW = w / wv / 10;
        s.FA = a / wv / 4;
        s.FR = rl / wv * 2;
    }

    /** Broad valley: compresses the relief towards the nearest river; shape depends on rock and glaciation. */
    protected void broadValley(GenPoint o, double ef, GenScratch s) {
        if (!s.FOK || s.FD >= 899) return;
        double h = o.h, D = s.FD, zr = s.FZ, hw = s.FW, A = s.FA, VW = P.valleyWidth;
        double G = glacial(o, zr) * ss(8, 45, s.FR); // troughs only where the ice was boxed in by mountains
        double str = 0.4 + 0.6 * ss(3.4, 7, hw); // headwaters: faint valley
        double Wv = (70 + 12 * hw) * lerp(0.7, 2.2, ef) * VW, Wu = (80 + 8 * hw) * VW;
        double W = lerp(Wv, Wu, G) * lerp(0.6, 1, str);
        double D0 = lerp(6 + hw + 1.15 * A, 12 + 1.5 * hw, G); // flat floor (floodplain)
        double floor = lerp(zr + 0.6, zr - glacialDepth(zr, hw), G);
        double t = clamp((D - D0) / W, 0, 1);
        if (G * (1 - t) > o.glac) o.glac = G * (1 - t);
        if (t >= 1 || h <= floor) return;
        double gam = lerp(lerp(0.6, 1.5, ef), 2.2, G); // <1: gorge; >1: open / U valley
        double f = Math.pow(t * (2 - t), gam);
        double fade = ss(8, 70, o.edge) * (1 - ss(700, 899, D));
        o.h = lerp(h, floor + (h - floor) * f, str * fade);
        if ((1 - t) * fade > o.valley) o.valley = (1 - t) * fade;
    }

    // ------------------------------------------------------------------ 10. main entry

    public void apply(double x, double y, GenPoint o, GenScratch s) {
        o.river = 0;
        o.riverD = 1e9;
        o.riverHW = 0;
        o.riverAcc = 0;
        o.riverZ = 0;
        o.riverWL = 0;
        o.valley = 0;
        o.delta = 0;
        o.estu = 0;
        o.lake = 0;
        o.lakeZ = 0;
        o.glac = 0;
        o.gorge = 0;
        if (o.L < -0.5) return;
        RiverNet net = net(o.pi, o.pj);
        if (net.count == 0) return;
        if (net.nMouth > 0) deltas(x, y, o, net);
        // valley shape from the rock (interpolated from the network grid so it changes smoothly between patches)
        netField(x, y, net, s);
        double ef = clamp(lerp(0.5, (lerp(o.erod, s.FE, s.FEW) - 0.2) / 0.7, P.valleyRock), 0, 1);
        o.gorge = 1 - ef;
        if (o.L >= -0.04) {
            broadValley(o, ef, s);
            double qx = x + 3 * perlin(x / 50, y / 50, SD.rivW),
                qy = y + 3 * perlin(x / 50 + 3.3, y / 50 + 7.7, SD.rivW + 1);
            int g = net.g, gi = floor((qx - net.x0) / g), gj = floor((qy - net.y0) / g);
            float[] pts = net.pts, BB = net.bb;
            double best = 1e9, bhw = 0, bz = 0, bacc = 0, bW = 1, bwl = 0, bws = 0;
            double h = o.h, VMAX = 2 * g;
            int RC = (int) Math.ceil((VMAX + 24 + net.reach) / g);
            double wMul = lerp(0.45, 2.0, ef), bankSlope = lerp(1.5, 0.25, ef);
            double hmin = h;
            for (int dj = -RC; dj <= RC; dj++) {
                int j = gj + dj;
                if (j < 0 || j >= net.ny) continue;
                for (int di = -RC; di <= RC; di++) {
                    int i = gi + di;
                    if (i < 0 || i >= net.nx) continue;
                    int sl = net.slot[j * net.nx + i];
                    if (sl < 0) continue;
                    double pad = VMAX + Math.max(net.w0[sl], net.w1[sl]);
                    if (qx < BB[sl * 4] - pad || qx > BB[sl * 4 + 2] + pad || qy < BB[sl * 4 + 1] - pad
                        || qy > BB[sl * 4 + 3] + pad) continue;
                    for (int k = 0; k < SEG; k++) {
                        int q = (sl * NPT + k) * 2;
                        double ax = pts[q], ay = pts[q + 1], vx = pts[q + 2] - ax, vy = pts[q + 3] - ay;
                        double l2 = vx * vx + vy * vy;
                        if (l2 == 0) l2 = 1e-9;
                        double t = clamp(((qx - ax) * vx + (qy - ay) * vy) / l2, 0, 1);
                        double dx = qx - (ax + vx * t), dy = qy - (ay + vy * t);
                        double dist = Math.sqrt(dx * dx + dy * dy);
                        double u = (k + t) / SEG;
                        double hw = lerp(net.w0[sl], net.w1[sl], u);
                        double v = dist - hw;
                        if (v >= VMAX) continue;
                        double z = lerp(net.z0[sl], net.z1[sl], u);
                        // the glacial trough is made by the broad valley; here the glacier only lowers the bed
                        double G = glacial(o, z) * (s.FOK ? ss(8, 45, s.FR) : 0);
                        double W = Math.min((5 + hw * 2) * wMul, VMAX);
                        if (v >= W) continue;
                        double dep = net.head[sl] != 0 ? ss(0, 0.9, u) : 1; // headwater starts without a valley
                        double zu = z - G * glacialDepth(z, hw); // below sea level next to the sea → fjord
                        if (v < best) {
                            best = v;
                            bhw = hw;
                            bz = z;
                            bacc = net.acc[sl];
                            bW = W;
                            bws = lerp(z, zu, G); // water surface of the nearest reach
                            bwl = Math.min(h, bws); // water fills the channel up to the bank
                        }
                        double hc;
                        if (v <= 0) {
                            double qq = dist / Math.max(hw, 1e-3), zc = lerp(z, zu, G);
                            hc = Math.min(h, zc) - (1.2 + hw * 0.25) * (1 - qq * qq);
                        } else {
                            double bank = lerp(z, zu, G) + v * bankSlope;
                            hc = h > bank ? lerp(bank, h, ss(0, W, v)) : h;
                        }
                        if (dep < 1) hc = lerp(h, hc, dep);
                        if (hc < hmin) hmin = hc;
                    }
                }
            }
            if (best < 1e8) {
                if (best < o.riverD) {
                    o.riverD = best;
                    o.riverHW = bhw;
                    o.riverAcc = bacc;
                }
                o.riverZ = bz;
                o.riverWL = bwl;
                // a low bank right next to the water would let it spill: keep the first blocks at least at water level
                if (best > 0 && best < 3) o.h = Math.max(o.h, Math.min(o.h + 3, bws + 0.6 * (1 - best / 3)));
                o.h = hmin;
                if (best <= 0) o.river = 1;
                else o.valley = Math.max(o.valley, 1 - best / bW);
            }
        }
        if (net.estSeg.length > 0) estuaries(x, y, o, net);
        if (o.h < -1 && o.river != 0 && o.delta == 0) { // last reach drowned by the sea (fjord, estuary)
            o.river = 0;
            o.riverD = 1e9;
        }
        if (!net.lakes.isEmpty()) lakes(x, y, o, net, s);
    }

    // ------------------------------------------------------------------ 10c. lakes and salt flats

    protected void lakes(double x, double y, GenPoint o, RiverNet net, GenScratch s) {
        int g = net.g;
        double fx = (x - net.x0) / g - 0.5, fy = (y - net.y0) / g - 0.5;
        int i0 = floor(fx) - 1, j0 = floor(fy) - 1;
        bspl(fx - i0 - 1, s.BWX);
        bspl(fy - j0 - 1, s.BWY);
        double m = 0, zs = 0, sw = 0;
        for (int j = 0; j < 4; j++) {
            int jj = j0 + j;
            if (jj < 0 || jj >= net.ny) continue;
            for (int i = 0; i < 4; i++) {
                int ii = i0 + i;
                if (ii < 0 || ii >= net.nx) continue;
                Double lv = net.lakes.get(jj * net.nx + ii);
                if (lv == null) continue;
                double w = s.BWX[i] * s.BWY[j];
                m += w;
                zs += w * Math.abs(lv);
                sw += lv < 0 ? w : 0;
            }
        }
        if (m <= 0.002) return;
        double Z = zs / m;
        boolean salt = sw > 0.5 * m;
        double thr = 0.36 + 0.13 * perlin(x / 45, y / 45, SD.lake);
        if (m > thr && o.h < Z) {
            if (salt) { // salt flat: perfectly flat crust
                o.lake = 2;
                o.lakeZ = Z;
                o.h = Z - 0.3;
            } else {
                o.lake = 1;
                o.lakeZ = Z;
            }
            o.river = 0;
            o.riverD = 1e9;
            return;
        }
        double rim = Z + 30 * (thr - m);
        if (o.h < rim) o.h = lerp(o.h, rim, ss(thr - 0.3, thr, m));
    }
}
