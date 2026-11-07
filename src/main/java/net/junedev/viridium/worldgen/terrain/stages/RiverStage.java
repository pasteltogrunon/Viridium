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

    public static final int CACHE_MAX = 64;

    /** Drainage basins: which cell a point belongs to, how cells drain into each other. */
    public final BasinGraph basins;

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

    /** Recently used networks by slot (mixed key): most lookups skip the map and its boxed key. Cleared on eviction. */
    private final Cached[] front = new Cached[64];
    private final ConcurrentHashMap<Long, FutureTask<RiverNet>> building = new ConcurrentHashMap<>();
    private final AtomicLong builds = new AtomicLong(), buildNanos = new AtomicLong();

    public RiverStage(WorldGen gen) {
        super(gen);
        this.basins = new BasinGraph(gen);
    }

    // ------------------------------------------------------------------ network cache

    /** Map key (mixed: see GenMath.mixKey). */
    private static long key(int pi, int pj) {
        return net.junedev.viridium.worldgen.terrain.core.GenMath.mixKey(((long) pi << 32) | (pj & 0xffffffffL));
    }

    private static final ThreadLocal<Boolean> SERIAL = ThreadLocal.withInitial(() -> Boolean.FALSE);

    /**
     * Marks the calling thread as background (e.g. the map workers): the networks it builds run on this thread only
     * instead of spreading over every core.
     */
    public static void buildSeriallyOnThisThread() {
        SERIAL.set(Boolean.TRUE);
    }

    public static boolean serialBuilds() {
        return SERIAL.get();
    }

    /** River network of basin cell (ci, cj) if it is built and cached, else null (never builds one). */
    public RiverNet cachedNet(int ci, int cj) {
        long key = key(ci, cj);
        Cached f = front[(int) key & (front.length - 1)];
        if (f != null && f.net.ci == ci && f.net.cj == cj) {
            long now = clock.get();
            if (f.used < now) f.used = now;
            return f.net;
        }
        Cached hit = cache.get(key);
        if (hit != null) {
            front[(int) key & (front.length - 1)] = hit;
            long now = clock.get(); // the clock only ticks on inserts: plain reads write nothing
            if (hit.used < now) hit.used = now;
            return hit.net;
        }
        return null;
    }

    /** River network of basin cell (ci, cj), building it if needed (other threads wait for the same build). */
    public RiverNet net(int ci, int cj) {
        RiverNet hit = cachedNet(ci, cj);
        if (hit != null) return hit;
        long key = key(ci, cj);
        FutureTask<RiverNet> created = new FutureTask<>(() -> {
            long t0 = System.nanoTime();
            RiverNet n = new RiverNetBuilder(gen).build(basins.cell(ci, cj), basins, new GenScratch()); // builder holds per-build state
            buildNanos.addAndGet(System.nanoTime() - t0);
            net.junedev.viridium.worldgen.terrain.GenStats.RIVER_NET.add(System.nanoTime() - t0);
            builds.incrementAndGet();
            recordExit(key, n);
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
            Cached gone = cache.remove(oldest);
            int slot = (int) (long) oldest & (front.length - 1);
            if (front[slot] == gone) front[slot] = null;
        }
    }

    /**
     * What other networks need from a basin: the flow and water level leaving it downstream, and its delta channels
     * (neighbouring coastal cells join them). Tiny, so kept far longer than the networks: a network evicted from the
     * LRU is not rebuilt (with its whole upstream chain) just because a downstream basin needs its exit.
     */
    public static final class Exit {

        public final float flow, level;
        public final float[] dv;
        public final int[] dch;

        Exit(RiverNet n) {
            flow = n.exitFlow;
            level = n.exitLevel;
            dv = n.dv;
            dch = n.dch;
        }
    }

    private static final int EXITS_MAX = 16384;
    private final ConcurrentHashMap<Long, Exit> exits = new ConcurrentHashMap<>();

    /** The exit of basin cell (ci, cj), building its network if it was never built. */
    public Exit exit(int ci, int cj) {
        Exit e = exits.get(key(ci, cj));
        if (e != null) return e;
        RiverNet n = net(ci, cj); // records the exit
        e = exits.get(key(ci, cj));
        return e != null ? e : new Exit(n);
    }

    public boolean hasExit(int ci, int cj) {
        return exits.containsKey(key(ci, cj));
    }

    private void recordExit(long key, RiverNet n) {
        if (exits.size() >= EXITS_MAX) exits.clear(); // deterministic: a missing exit is only rebuilt
        exits.putIfAbsent(key, new Exit(n));
    }

    public boolean isCached(int ci, int cj) {
        return cache.containsKey(key(ci, cj));
    }

    /** Basin cells whose networks shape (x, y). */
    public long[] basinsAt(double x, double y) {
        return basins.nearCells(x, y, RiverNetBuilder.MARGIN);
    }

    /** True when every network that shapes (x, y) is already built (evaluating there will not build one). */
    public boolean isReadyAt(double x, double y) {
        for (long k : basins.nearCells(x, y, RiverNetBuilder.MARGIN))
            if (!cache.containsKey(net.junedev.viridium.worldgen.terrain.core.GenMath.mixKey(k)) && !basins.cell(k).ocean) return false;
        return true;
    }

    /** Drops the networks but keeps their exits. */
    public void clearNets() {
        cache.clear();
        java.util.Arrays.fill(front, null);
    }

    public int cachedCount() {
        return cache.size();
    }

    public int exitCount() {
        return exits.size();
    }

    public void clearCache() {
        cache.clear();
        java.util.Arrays.fill(front, null);
        exits.clear();
    }

    public long buildCount() {
        return builds.get();
    }

    public long buildNanos() {
        return buildNanos.get();
    }

    // ------------------------------------------------------------------ 10b. deltas

    /**
     * Deltas: a low plain built around the distributaries (filling shallow sea, flattening low land, never touching
     * hills or deep water) and the distributary channels themselves, whose water falls smoothly from the river's level
     * at the apex to the sea.
     */
    protected void deltas(double x, double y, GenPoint o, RiverNet net, boolean channels) {
        float[] V = net.dv, BOX = net.dbox;
        int[] CH = net.dch, MC = net.dmc;
        final int D = RiverNet.DV;
        for (int q = 0; q < net.nMouth; q++) {
            int nc = MC[q * 2 + 1];
            if (nc == 0) continue;
            int bq = q * 5;
            if (x < BOX[bq] || y < BOX[bq + 1] || x > BOX[bq + 2] || y > BOX[bq + 3]) continue;
            double plain = BOX[bq + 4];
            // per branch its nearest point; the plain blends them all (no seams where the nearest branch changes)
            double cover = 0, zw = 0, ww = 0, vBest = 1e18, vW = 0, vZ = 0, vD = 0;
            for (int c = MC[q * 2], ce = c + nc; c < ce; c++) {
                int v0 = CH[c * 2], vn = CH[c * 2 + 1];
                double cd = 1e18, cz = 0, ctip = 1e9;
                for (int k = 0; k < vn - 1; k++) {
                    int a = (v0 + k) * D, b = a + D;
                    double ax = V[a], ay = V[a + 1], ex = V[b] - ax, ey = V[b + 1] - ay;
                    double l2 = ex * ex + ey * ey;
                    if (l2 == 0) l2 = 1e-9;
                    double t = clamp(((x - ax) * ex + (y - ay) * ey) / l2, 0, 1);
                    double ddx = x - (ax + ex * t), ddy = y - (ay + ey * t), d = Math.sqrt(ddx * ddx + ddy * ddy);
                    double w = lerp(V[a + 2], V[b + 2], t), z = lerp(V[a + 3], V[b + 3], t);
                    if (d < cd) {
                        cd = d;
                        cz = z;
                        ctip = lerp(V[a + 4], V[b + 4], t) + w;
                    }
                    if (d - w < vBest) {
                        vBest = d - w;
                        vW = w;
                        vZ = z;
                        vD = d;
                    }
                }
                // the plain narrows towards the tips of the last branches: they end at its edge, out in the water
                double pl = Math.min(plain, 0.7 * ctip);
                cover = Math.max(cover, 1 - ss(0.45 * pl, pl, cd));
                double wt = 1 / Math.pow(cd + 30, 4);
                zw += wt * cz;
                ww += wt;
            }
            if (!channels) {
                // the plain: just above the water of the branches; fades out over deep water and against hills
                double m = cover * ss(20, 90, o.edge) * (1 - glacial(o, 0)) * ss(-45, -25, o.h);
                if (m > 0) {
                    double hP = zw / ww + 1.1 + 0.5 * perlin(x / 40, y / 40, SD.delta + 1);
                    if (o.h < hP) o.h = lerp(o.h, hP, m);
                    else o.h -= m * (1 - ss(0, 8, o.h - hP)) * (o.h - hP);
                    if (m > o.delta) o.delta = m;
                }
                continue;
            }
            if (vBest >= 3) continue;
            if (vBest < 0) {
                double qq = vD / Math.max(vW, 1e-3);
                o.h = Math.min(o.h, vZ - 1.05 - 0.22 * vW * (1 - qq * qq)); // a full block deep at the edge: no lip
                o.river = 1;
            } else if (vZ > 0.3) o.h = Math.max(o.h, Math.min(o.h + 3, vZ + 0.6 * (1 - vBest / 3))); // banks
            if (vBest < o.riverD) {
                FIELD_MEMO.get().nearestInLake = false;
                o.riverD = vBest;
                o.riverHW = vW;
                o.riverZ = vZ;
                o.riverWL = vZ;
                o.riverAcc = net.mouths[q * RiverNet.MS + 6];
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

    static double glacialDepth(double z, double hw) {
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
        s.FW = w / wv / 4; // half-width stored ×4 (rivers up to ~64 blocks)
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
        FieldMemo m = FIELD_MEMO.get();
        double W = lerp(Wv, Wu, G) * lerp(0.6, 1, str) * m.wMulV;
        double D0 = lerp(6 + hw + 1.15 * A, 12 + 1.5 * hw, G); // flat floor (floodplain)
        double floor = lerp(zr + 0.6, zr - glacialDepth(zr, hw), G);
        // the valley's sides are a little irregular (none at the river itself)
        double Dv = D + m.dOff * ss(0, D0, D);
        double t = clamp((Dv - D0) / W, 0, 1);
        if (G * (1 - t) > o.glac) o.glac = G * (1 - t);
        if (t >= 1 || h <= floor) return;
        double gam = lerp(lerp(0.6, 1.5, ef), 2.2, G); // <1: gorge; >1: open / U valley
        double tr = valleyT(t); // no crease where the floor meets the sides
        double f = Math.pow(tr * (2 - tr), gam);
        double fade = ss(8, 70, o.edge) * (1 - ss(700, 899, D));
        o.h = lerp(h, floor + (h - floor) * f, str * fade);
        if ((1 - t) * fade > o.valley) o.valley = (1 - t) * fade;
    }

    // ------------------------------------------------------------------ 10. main entry

    /**
     * The scratch of the evaluation on this thread that asked for cached networks only (map previews), from its river
     * stage until the cave stage right after it takes it ({@link #takeCachedOnlyCaller}); null otherwise. The cave
     * planners reached from there (collapse pits) use their own scratches, so this is how they learn the flag and report
     * what they skipped.
     */
    private static final class Caller {

        GenScratch cachedOnly;
    }

    private static final ThreadLocal<Caller> CALLER = ThreadLocal.withInitial(Caller::new);

    /**
     * For the 2D cave stage that runs right after this stage in an evaluation: the scratch of that evaluation if it asked
     * for cached networks only (then the cave stage must not build any, and reports a skipped one by setting its
     * {@code riversIncomplete}), else null. Clears it: a later call on this thread without a river stage in between
     * sees null.
     */
    public static GenScratch takeCachedOnlyCaller() {
        Caller c = CALLER.get();
        GenScratch s = c.cachedOnly;
        c.cachedOnly = null;
        return s;
    }

    /**
     * Rivers at (x, y). With {@code s.riversCachedOnly} no network is ever built: a basin that shapes the point but whose
     * network is not cached is left out and {@code s.riversIncomplete} is set.
     */
    public void apply(double x, double y, GenPoint o, GenScratch s) {
        CALLER.get().cachedOnly = s.riversCachedOnly ? s : null;
        applyNested(x, y, o, s);
    }

    /**
     * As {@link #apply}, for planners that run inside another evaluation (cave pits): leaves what the enclosing
     * evaluation told the cave stage ({@link #takeCachedOnlyCaller}) alone.
     */
    public void applyNested(double x, double y, GenPoint o, GenScratch s) {
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
        o.fall = 0;
        o.fallTop = -1e9;
        FieldMemo memo = FIELD_MEMO.get();
        memo.nearestInLake = false;
        memo.shoreZ = Double.NaN;
        if (o.L < -0.5 || s.riversOff) return;
        irregularity(x, y, memo);
        // every basin whose network reaches this point shapes it (valleys and channels stay continuous across divides)
        int nNear = basins.nearCells(x, y, RiverNetBuilder.MARGIN, s); // into s.NCK: no allocation per point
        RiverNet[] nets = s.NETS;
        int nn = 0;
        for (int q = 0; q < nNear; q++) {
            BasinGraph.Cell c = basins.cell(s.NCK[q]);
            if (c.ocean) continue;
            RiverNet net;
            if (s.riversCachedOnly) {
                net = cachedNet(c.ci, c.cj);
                if (net == null) { // not built: left out, never built from here
                    s.riversIncomplete = true;
                    continue;
                }
            } else net = net(c.ci, c.cj);
            if (net.count > 0 || !net.lakes.isEmpty()) nets[nn++] = net;
        }
        // in phases across networks: all valleys first, then all channels (so each channel measures its water against
        // the final terrain, not one a neighbouring basin will still lower), then estuaries and lakes
        for (int phase = 0; phase < 3; phase++) for (int q = 0; q < nn; q++) applyNet(x, y, o, s, nets[q], phase, memo, q);
        java.util.Arrays.fill(nets, 0, nn, null); // the scratch must not keep networks alive after the LRU drops them
        // last reach drowned by the sea (fjord, estuary): only where the river is at sea level anyway. A deep channel
        // inland keeps its own water (otherwise its middle would drop to sea level between higher river water)
        if (o.h < -1 && o.river != 0 && o.delta == 0 && o.riverWL < 1) {
            o.river = 0;
            o.riverD = 1e9;
        }
    }

    /**
     * What the channel phase reads of the valley field of each network at the current point, kept from the valley phase
     * (the field depends only on the point and the network: sampling it once per network instead of twice).
     */
    private static final class FieldMemo {

        final boolean[] ok = new boolean[9];
        final double[] fe = new double[9], few = new double[9], fr = new double[9];
        /** The nearest channel at the current point lies in a river lake (its water is the lake's). */
        boolean nearestInLake;
        /** Level of the lake whose shore the current point is on (rim zone, not in the lake), or NaN. */
        double shoreZ = Double.NaN;
        /** Irregularity at the current point: valley distance offset and width factor, channel width and bed depth. */
        double dOff, wMulV, widthMul = 1, depthMul = 1;
    }

    private static final ThreadLocal<FieldMemo> FIELD_MEMO = ThreadLocal.withInitial(FieldMemo::new);

    /**
     * Rivers and valleys a little less smooth: the channel half-width varies along the river (about ±18 % over a few tens
     * of blocks, plus a slight short jitter of the bank line), the bed is deeper in pools and shallower over riffles (±30 %), and
     * the valley sides are not perfect extrusions of the river (the distance to it is offset by up to ~14 blocks, the
     * valley width varies by ±12 %). Functions of position only: every network sees the same values at a point.
     */
    private void irregularity(double x, double y, FieldMemo m) {
        m.dOff = 10 * perlin(x / 90, y / 90, SD.rivW + 5) + 4 * perlin(x / 23, y / 23, SD.rivW + 6);
        m.wMulV = 1 + 0.12 * perlin(x / 400, y / 400, SD.rivW + 7);
        m.widthMul = clamp(1 + 0.18 * perlin(x / 40, y / 40, SD.rivW + 8) + 0.025 * perlin(x / 12, y / 12, SD.rivW + 9), 0.75,
            CHANNEL_WIDEN);
        m.depthMul = 1 + 0.3 * perlin(x / 70, y / 70, SD.rivW + 10);
    }

    /** Largest widening of a channel by {@link #irregularity} (search pads allow for it). */
    static final double CHANNEL_WIDEN = 1.25;

    /**
     * Position across a broad valley, 0 at the edge of its flat floor to 1 at its outer edge, with the corner at the floor
     * rounded: over the first 12 % a cubic easing in (zero slope at the floor, so even a gorge leaves its floodplain
     * without a crease; value and slope match the plain profile at 12 %), unchanged beyond.
     */
    static double valleyT(double t) {
        final double e = 0.12;
        return t < e ? (2 * t * t - t * t * t / e) / e : t;
    }

    /** Valley distance and width as {@link #broadValley} sees them at (x, y) (for the network builder's lake shores). */
    double[] valleyIrregularity(double x, double y) {
        FieldMemo m = new FieldMemo();
        irregularity(x, y, m);
        return new double[] { m.dOff, m.wMulV };
    }

    /**
     * For the 2D cave stage right after this stage (karst dolines): the level of the lake whose shore the point this
     * thread evaluated last lies on (its rim zone), or NaN. A doline there would open a hole below the lake's water.
     */
    public static double lakeShore() {
        return FIELD_MEMO.get().shoreZ;
    }

    /** Applies one phase of one basin network: 0 delta + broad valley, 1 channel, 2 estuary + lakes. */
    protected void applyNet(double x, double y, GenPoint o, GenScratch s, RiverNet net, int phase) {
        applyNet(x, y, o, s, net, phase, null, 0);
    }

    /** As above; m (when not null) carries the valley field of network mq from phase 0 to phase 1 of the same point. */
    private void applyNet(double x, double y, GenPoint o, GenScratch s, RiverNet net, int phase, FieldMemo m, int mq) {
        if (phase == 2) {
            if (net.nMouth > 0) deltas(x, y, o, net, true); // distributaries, after the river channels
            if (net.estSeg.length > 0) estuaries(x, y, o, net);
            if (!net.lakes.isEmpty()) lakes(x, y, o, net, s);
            return;
        }
        if (net.count == 0) return;
        if (phase == 0 && net.nMouth > 0) deltas(x, y, o, net, false);
        // valley shape from the rock (interpolated from the network grid so it changes smoothly between patches)
        if (m == null || phase == 0) {
            netField(x, y, net, s);
            if (m != null) {
                m.ok[mq] = s.FOK;
                m.fe[mq] = s.FE;
                m.few[mq] = s.FEW;
                m.fr[mq] = s.FR;
            }
        } else { // phase 1 reads only these
            s.FOK = m.ok[mq];
            s.FE = m.fe[mq];
            s.FEW = m.few[mq];
            s.FR = m.fr[mq];
        }
        double ef = clamp(lerp(0.5, (lerp(o.erod, s.FE, s.FEW) - 0.2) / 0.7, P.valleyRock), 0, 1);
        o.gorge = 1 - ef;
        if (o.L >= -0.04) {
            if (phase == 0) {
                broadValley(o, ef, s);
                return;
            }
            final FieldMemo cm = m != null ? m : FIELD_MEMO.get(); // irregularity of this point
            double qx = x + 3 * perlin(x / 50, y / 50, SD.rivW),
                qy = y + 3 * perlin(x / 50 + 3.3, y / 50 + 7.7, SD.rivW + 1);
            int g = net.g, gi = floor((qx - net.x0) / g), gj = floor((qy - net.y0) / g);
            float[] pts = net.pts, BB = net.bb;
            double best = 1e9, bhw = 0, bz = 0, bacc = 0, bW = 1, bwl = 0, bws = 0;
            boolean bLake = false; // the nearest reach lies in a river lake
            double h = o.h, VMAX = 2 * g;
            int RC = (int) Math.ceil((VMAX + 24 + net.reach) / g);
            double wMul = lerp(0.45, 2.0, ef), bankSlope = lerp(1.5, 0.25, ef);
            double hmin = h;
            // the reaches in the window of RC cells around the point's cell, in grid order: from the network's index
            // (the bucket of the cell lists those that can matter there), or by scanning the window
            final int W2 = 2 * RC + 1;
            final int[] CC = net.chCells;
            final boolean indexed = net.chStart != null && gi >= 0 && gj >= 0 && gi < net.nx && gj < net.ny;
            int a0 = 0, a1 = W2 * W2;
            if (indexed) {
                int b = (gj / RiverNet.CB) * net.chNbx + gi / RiverNet.CB;
                a0 = net.chStart[b];
                a1 = net.chStart[b + 1];
            }
            {
                for (int a = a0; a < a1; a++) {
                    int sl;
                    if (indexed) {
                        int cell = CC[a], i = cell % net.nx, j = cell / net.nx;
                        if (i < gi - RC || i > gi + RC || j < gj - RC || j > gj + RC) continue;
                        sl = net.slot[cell];
                    } else {
                        int j = gj + a / W2 - RC, i = gi + a % W2 - RC;
                        if (j < 0 || j >= net.ny || i < 0 || i >= net.nx) continue;
                        sl = net.slot[j * net.nx + i];
                    }
                    if (sl < 0 || net.skip[sl] != 0) continue; // skipped: replaced by a delta
                    double pad = VMAX + CHANNEL_WIDEN * Math.max(net.w0[sl], net.w1[sl]);
                    if (qx < BB[sl * 4] - pad || qx > BB[sl * 4 + 2] + pad || qy < BB[sl * 4 + 1] - pad
                        || qy > BB[sl * 4 + 3] + pad) continue;
                    if (net.fallIn.length > 0 && net.fallIn[sl] > 0) {
                        // below a waterfall: nothing behind the lip belongs to this reach (its banks would cut a ramp
                        // into the cliff from the level of the pool)
                        int q0 = sl * NPT * 2;
                        double vx0 = pts[q0 + 2] - pts[q0], vy0 = pts[q0 + 3] - pts[q0 + 1];
                        if ((qx - pts[q0]) * vx0 + (qy - pts[q0 + 1]) * vy0 < 0) continue;
                    }
                    for (int k = 0; k < SEG; k++) {
                        int q = (sl * NPT + k) * 2;
                        double ax = pts[q], ay = pts[q + 1], vx = pts[q + 2] - ax, vy = pts[q + 3] - ay;
                        double l2 = vx * vx + vy * vy;
                        if (l2 == 0) l2 = 1e-9;
                        double tr = ((qx - ax) * vx + (qy - ay) * vy) / l2, t = clamp(tr, 0, 1);
                        double dx = qx - (ax + vx * t), dy = qy - (ay + vy * t);
                        double dist = Math.sqrt(dx * dx + dy * dy);
                        double u = (k + t) / SEG;
                        double hw = lerp(net.w0[sl], net.w1[sl], u) * cm.widthMul; // irregular banks
                        double v = dist - hw;
                        if (v >= VMAX) continue;
                        double z = lerp(net.z0[sl], net.z1[sl], u);
                        // waterfalls: the reach below starts at the lip and never cuts back into it; the reach above
                        // gives no water beyond its lip, where the curtain falls
                        float fin = net.fallIn.length > 0 ? net.fallIn[sl] : 0;
                        int lip = net.lipOut.length > 0 ? net.lipOut[sl] : 0;
                        double pool = 0;
                        if (fin > 0 && k == 0) {
                            if (tr < 0) continue;
                            double along = tr * Math.sqrt(l2);
                            if (along < hw + 4 && v < 3) o.fall = 1;
                            if (along < 1.5 && v <= 0) o.fallTop = Math.max(o.fallTop, z + fin);
                        }
                        if (fin > 0 && u < 0.3) pool = (2 + 0.1 * fin) * (1 - u / 0.3); // plunge pool
                        // near its lip, the reach above stops where the ground has already dropped below its water: past
                        // an oblique cliff edge it would stand as a wall of water over the low ground
                        if (lip != 0 && k >= SEG - 3 && h < z - RiverNetBuilder.MIN_FALL) continue;
                        if (lip != 0 && k == SEG - 1) {
                            double past = (tr - 1) * Math.sqrt(l2);
                            if (past > -(hw + 4) && v < 3) o.fall = 1;
                            if (tr > 1) {
                                if (lip == 2 && past < 1.5 && v <= 0) o.fallTop = Math.max(o.fallTop, z); // into the sea
                                continue;
                            }
                        }
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
                            bLake = net.inLake.length > 0 && net.inLake[sl] != 0;
                            bwl = Math.min(h, bws); // water fills the channel up to the bank
                        }
                        double hc;
                        if (v <= 0) {
                            double qq = dist / Math.max(hw, 1e-3), zc = lerp(z, zu, G);
                            hc = Math.min(h, zc) - (1.2 + hw * 0.25 + pool) * cm.depthMul * (1 - qq * qq);
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
                if (best < o.riverD) { // the nearest channel (of any network, or a delta distributary) sets the water
                    o.riverD = best;
                    o.riverHW = bhw;
                    o.riverAcc = bacc;
                    o.riverZ = bz;
                    o.riverWL = bwl;
                    if (m != null) m.nearestInLake = bLake;
                }
                o.h = hmin;
                // a low bank right next to the water would let it spill: keep the first blocks at least at water level
                // (applied after the carve, and only when this network owns the nearest channel, so it never fills
                // another network's or a delta's channel)
                if (best > 0 && best < 4 && best <= o.riverD && !bLake) { // (in a river lake the lake holds the water)
                    double lip = Math.min(o.h + 3, bws + 0.6 * Math.max(0, 1 - best / 3));
                    if (lip > o.h) o.h = lerp(o.h, lip, 1 - ss(2.5, 4, best)); // eased out: no crease where it ends
                }
                if (best <= 0) o.river = 1;
                else o.valley = Math.max(o.valley, 1 - best / bW);
            }
        }
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
                double lv = net.lakeAt(jj * net.nx + ii);
                if (lv != lv) continue;
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
        // the rim never crosses a river channel and its banks: rivers flow into and out of the lake freely
        // (a channel inside a river lake is the lake's own water: the rim closes around it)
        FieldMemo fm = FIELD_MEMO.get();
        if (m > thr - 0.3) fm.shoreZ = fm.shoreZ == fm.shoreZ ? Math.max(fm.shoreZ, Z) : Z;
        if (o.h < rim) o.h = lerp(o.h, rim, ss(thr - 0.3, thr, m) * (fm.nearestInLake ? 1 : ss(2, 14, o.riverD - o.riverHW)));
    }
}
