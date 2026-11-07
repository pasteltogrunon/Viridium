package net.junedev.viridium.worldgen.terrain.stages;

import static net.junedev.viridium.worldgen.terrain.core.GenMath.TAU;
import static net.junedev.viridium.worldgen.terrain.core.GenMath.clamp;
import static net.junedev.viridium.worldgen.terrain.core.GenMath.floor;
import static net.junedev.viridium.worldgen.terrain.core.GenMath.hypot;
import static net.junedev.viridium.worldgen.terrain.core.GenMath.lerp;
import static net.junedev.viridium.worldgen.terrain.core.GenMath.ss;
import static net.junedev.viridium.worldgen.terrain.core.Noise.perlin;
import static net.junedev.viridium.worldgen.terrain.core.Noise.rnd;
import static net.junedev.viridium.worldgen.terrain.stages.RiverNet.HALF;
import static net.junedev.viridium.worldgen.terrain.stages.RiverNet.NPT;
import static net.junedev.viridium.worldgen.terrain.stages.RiverNet.SEG;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.junedev.viridium.worldgen.terrain.GenStats;
import net.junedev.viridium.worldgen.terrain.WorldGen;
import net.junedev.viridium.worldgen.terrain.core.GenParams;
import net.junedev.viridium.worldgen.terrain.core.Noise;
import net.junedev.viridium.worldgen.terrain.core.Seeds;
import net.junedev.viridium.worldgen.terrain.pipeline.GenPoint;
import net.junedev.viridium.worldgen.terrain.pipeline.GenScratch;
import net.junedev.viridium.worldgen.terrain.pipeline.Stage;

/**
 * Builds a {@link RiverNet}: coarse grid over the plate → priority-flood (fills depressions, gives each cell a
 * downstream receiver) → lakes in filled depressions → flow accumulation → river reaches as quadratic Béziers with
 * meanders on low slopes → mouths (delta vs estuary) → broad valley distance field. Port of buildRiverNet.
 */
final class RiverNetBuilder {

    private final WorldGen gen;
    private final GenParams P;
    private final Seeds SD;

    RiverNetBuilder(WorldGen gen) {
        this.gen = gen;
        this.P = gen.params;
        this.SD = gen.seeds;
    }

    /** Binary min-heap on (float key, int value), identical to the JS one (tie order matters for parity). */
    private static final class Heap {

        final double[] k;
        final int[] v;
        int n;

        Heap(int cap) {
            k = new double[cap];
            v = new int[cap];
        }

        void push(double key, int val) {
            int i = n++;
            while (i > 0) {
                int p = (i - 1) >> 1;
                if (k[p] <= key) break;
                k[i] = k[p];
                v[i] = v[p];
                i = p;
            }
            k[i] = key;
            v[i] = val;
        }

        int pop() {
            int top = v[0];
            int nn = --n;
            if (nn == 0) return top;
            double key = k[nn];
            int val = v[nn];
            int i = 0;
            for (;;) {
                int c = 2 * i + 1;
                if (c >= nn) break;
                if (c + 1 < nn && k[c + 1] < k[c]) c++;
                if (k[c] >= key) break;
                k[i] = k[c];
                v[i] = v[c];
                i = c;
            }
            k[i] = key;
            v[i] = val;
            return top;
        }
    }

    private static final int[] DI = { 1, -1, 0, 0, 1, 1, -1, -1 }, DJ = { 0, 0, 1, -1, 1, -1, 1, -1 };

    // grid context used by the jittered node positions
    private double x0, y0;
    private int gx0, gy0, nx, g;

    private double jx(int idx) {
        int i = idx % nx;
        return x0 + (i + 0.5 + (rnd(gx0 + i, gy0 + (idx - i) / nx, SD.rivJ) - 0.5) * 0.7) * g;
    }

    private double jy(int idx) {
        int i = idx % nx;
        return y0 + ((idx - i) / nx + 0.5 + (rnd(gx0 + i, gy0 + (idx - i) / nx, SD.rivJ + 1) - 0.5) * 0.7) * g;
    }

    private double shape(double ph) {
        double ps = ph + 0.5 * Math.sin(ph) + 0.8 * perlin(ph * 0.11, 0.5, SD.mea);
        return (Math.sin(ps) + 0.18 * Math.sin(3 * ps + 0.6)) * (0.8 + 0.35 * perlin(ph * 0.07, 3.5, SD.mea + 1));
    }

    private static double lam(double w) {
        return clamp(12 * w + 40, 60, 420);
    }

    /** Temporaries of the relief grid rows, per thread (holding no reference to a WorldGen). */
    private static final ThreadLocal<GenPoint> ROW_POINT = ThreadLocal.withInitial(GenPoint::new);
    private static final ThreadLocal<GenScratch> ROW_SCRATCH = ThreadLocal.withInitial(GenScratch::new);

    private static long lap(GenStats.Timer t, long since) {
        long now = System.nanoTime();
        t.add(now - since);
        return now;
    }

    private static java.util.stream.IntStream par(java.util.stream.IntStream st, boolean serial) {
        return serial ? st : st.parallel();
    }

    /** Growable output of the delta builder. */
    private static final class DeltaOut {

        float[] v = new float[256], box = new float[0];
        int[] ch = new int[32], mc = new int[0];
        int nv, nc;

        void vertex(double x, double y, double w, double z, double s) {
            if ((nv + 1) * RiverNet.DV > v.length) v = Arrays.copyOf(v, v.length * 2);
            int b = nv++ * RiverNet.DV;
            v[b] = (float) x;
            v[b + 1] = (float) y;
            v[b + 2] = (float) w;
            v[b + 3] = (float) z;
            v[b + 4] = (float) s;
        }

        void channel(int first, int n) {
            if (n < 2) return;
            if ((nc + 1) * 2 > ch.length) ch = Arrays.copyOf(ch, ch.length * 2);
            ch[nc * 2] = first;
            ch[nc * 2 + 1] = n;
            nc++;
        }
    }

    /** Branches of other deltas a new branch joins when it meets them: segments (x0, y0, x1, y1, z0, z1, w). */
    private static final class Foreign {

        float[] a = new float[7 * 64];
        int n;

        void add(double x0, double y0, double x1, double y1, double z0, double z1, double w) {
            if ((n + 1) * 7 > a.length) a = Arrays.copyOf(a, a.length * 2);
            int b = n++ * 7;
            a[b] = (float) x0;
            a[b + 1] = (float) y0;
            a[b + 2] = (float) x1;
            a[b + 3] = (float) y1;
            a[b + 4] = (float) z0;
            a[b + 5] = (float) z1;
            a[b + 6] = (float) w;
        }

        /** Adds every branch segment of channels [c0, c1) of a delta vertex list. */
        void addChannels(float[] v, int[] ch, int c0, int c1) {
            final int D = RiverNet.DV;
            for (int c = c0; c < c1; c++) {
                int v0 = ch[c * 2], vn = ch[c * 2 + 1];
                for (int k = 0; k < vn - 1; k++) {
                    int p = (v0 + k) * D, q = p + D;
                    add(v[p], v[p + 1], v[q], v[q + 1], v[p + 3], v[q + 3], Math.max(v[p + 2], v[q + 2]));
                }
            }
        }

        /**
         * First crossing of segment (x0, y0)-(x1, y1) with a foreign branch (widened by its half-width): {t along ours,
         * x, y, water level there}, or null.
         */
        double[] hit(double x0, double y0, double x1, double y1) {
            double best = 2, hx = 0, hy = 0, hz = 0;
            double ex = x1 - x0, ey = y1 - y0;
            for (int i = 0; i < n; i++) {
                int b = i * 7;
                double fx0 = a[b], fy0 = a[b + 1], fx1 = a[b + 2], fy1 = a[b + 3], w = a[b + 6];
                if (Math.max(x0, x1) < Math.min(fx0, fx1) - w || Math.min(x0, x1) > Math.max(fx0, fx1) + w
                    || Math.max(y0, y1) < Math.min(fy0, fy1) - w || Math.min(y0, y1) > Math.max(fy0, fy1) + w) continue;
                double gx = fx1 - fx0, gy = fy1 - fy0, den = ex * gy - ey * gx;
                double t, u;
                if (Math.abs(den) > 1e-9) {
                    t = ((fx0 - x0) * gy - (fy0 - y0) * gx) / den;
                    u = ((fx0 - x0) * ey - (fy0 - y0) * ex) / den;
                } else continue;
                double gl = Math.max(1e-6, Math.sqrt(gx * gx + gy * gy)), mu = w / gl; // reach the bank, not the axis
                if (t < 0 || t > 1 || u < -mu || u > 1 + mu) {
                    // our end point inside the foreign channel also counts
                    double uu = clamp(((x1 - fx0) * gx + (y1 - fy0) * gy) / (gl * gl), 0, 1);
                    double dx = x1 - (fx0 + gx * uu), dy = y1 - (fy0 + gy * uu);
                    if (dx * dx + dy * dy > w * w) continue;
                    t = 1;
                    u = uu;
                }
                if (t < best) {
                    best = t;
                    double uc = clamp(u, 0, 1);
                    hx = fx0 + gx * uc;
                    hy = fy0 + gy * uc;
                    hz = a[b + 4] + (a[b + 5] - a[b + 4]) * uc;
                }
            }
            return best <= 1 ? new double[] { best, hx, hy, hz } : null;
        }
    }

    private static boolean contains(long[] keys, long k) {
        for (long v : keys) if (v == k) return true;
        return false;
    }

    /** Colour of a basin cell (no two neighbours share one): deltas of a cell join those of lower-coloured neighbours. */
    private static int colour(BasinGraph.Cell c) {
        return (c.ci & 1) | ((c.cj & 1) << 1);
    }

    /** A distributary being traced: position, angular sector (bearing from the apex, relative to the fan axis). */
    private static final class Branch {

        double x, y, lo, hi, w, s, lastSplit;
        int depth, id;
    }

    private double baseAt(double x, double y, GenPoint p, GenScratch s) {
        gen.plates.apply(x, y, p, s);
        gen.continents.apply(x, y, p);
        return gen.relief.baseHeight(x, y, p, false, s);
    }

    /**
     * Delta of mouth q: the main stem is cut at an apex upstream of the old mouth (the reaches below it are skipped), and
     * distributaries fan out from there towards the sea. Every branch keeps to its own angular sector around the apex,
     * and splitting divides the sector between both children, so branches never cross. Water falls linearly from the
     * river's level at the apex to sea level a little past the old coast, so it joins the river and the sea without
     * steps.
     */
    private void buildDelta(int q, float[] M, DeltaOut out, byte[] skip, float[] pts, int[] slot, int[] mainUp, int[] recv,
        boolean[] isRiv, float[] acc, float[] hgt, float[] z1, float[] w1, float[] am0, float[] am1, GenScratch s,
        Foreign fs, BasinGraph graph, long ck) {
        final int MS = RiverNet.MS;
        int b = q * MS;
        if (out.mc.length < (q + 1) * 2) {
            out.mc = Arrays.copyOf(out.mc, (q + 1) * 2 + 8);
            out.box = Arrays.copyOf(out.box, (q + 1) * 5 + 20);
        }
        out.mc[q * 2] = out.nc;
        out.mc[q * 2 + 1] = 0;
        double Rd = M[b + 4], E = M[b + 8];
        if (Rd <= 0 || E >= 0.5) {
            M[b + 4] = 0;
            return;
        }
        M[b + 8] = 0; // a delta, not an estuary
        int mIdx = (int) M[b + 7];
        // apex: up the main stem, stopping below any real tributary (one joining the replaced stretch would be left at
        // the old river's level beside the higher distributaries) or where the valley leaves the coastal plain.
        // Rivulets joining the stretch are dropped with it.
        int a = mIdx;
        double walked = 0;
        java.util.List<Integer> below = new java.util.ArrayList<>(), rivulets = new java.util.ArrayList<>();
        while (walked < 0.35 * Rd) {
            int mu = mainUp[a];
            if (mu < 0 || hgt[mu] > 18) break;
            boolean trib = false;
            int nr = rivulets.size();
            int ai = a % nx, aj = (a - ai) / nx;
            for (int k = 0; k < 8; k++) {
                int ii = ai + DI[k], jj = aj + DJ[k];
                if (ii < 0 || jj < 0 || ii >= nx || jj * nx + ii >= recv.length) continue;
                int nb = jj * nx + ii;
                if (nb == mu || !isRiv[nb] || recv[nb] != a) continue;
                if (acc[nb] > 0.06 * acc[a]) trib = true;
                else rivulets.add(nb);
            }
            if (trib) {
                while (rivulets.size() > nr) rivulets.remove(rivulets.size() - 1);
                break;
            }
            below.add(a);
            walked += am0[slot[a]] - am1[slot[a]];
            a = mu;
        }
        int sa = slot[a];
        if (sa < 0) return;
        java.util.List<Integer> marked = new java.util.ArrayList<>();
        for (int nd : below) {
            skip[slot[nd]] = 1;
            marked.add(slot[nd]);
        }
        // rivulets: their whole upstream tree
        java.util.ArrayDeque<Integer> up = new java.util.ArrayDeque<>(rivulets);
        while (!up.isEmpty()) {
            int nd = up.poll();
            if (slot[nd] < 0 || skip[slot[nd]] != 0) continue;
            skip[slot[nd]] = 1;
            marked.add(slot[nd]);
            int ci2 = nd % nx, cj2 = (nd - ci2) / nx;
            for (int k = 0; k < 8; k++) {
                int ii = ci2 + DI[k], jj = cj2 + DJ[k];
                if (ii < 0 || jj < 0 || ii >= nx || jj * nx + ii >= recv.length) continue;
                int nb = jj * nx + ii;
                if (isRiv[nb] && recv[nb] == nd) up.add(nb);
            }
        }
        int pq = (sa * NPT + SEG) * 2; // the apex reach ends halfway to the first skipped node
        double ax = pts[pq], ay = pts[pq + 1];
        if (below.isEmpty()) { // the apex is the mouth itself
            ax = M[b];
            ay = M[b + 1];
        }
        double zS = Math.max(0, z1[sa]), wS = Math.max(1.5, w1[sa]);
        // fan axis: towards the old mouth, and on through it towards the sea
        double dx = M[b] - ax, dy = M[b + 1] - ay, dl = hypot(dx, dy);
        double ux = M[b + 2], uy = M[b + 3];
        double fx = (dl > 1 ? dx / dl : 0) + ux, fy = (dl > 1 ? dy / dl : 0) + uy, fl = hypot(fx, fy);
        if (fl < 1e-6) {
            fx = ux;
            fy = uy;
            fl = Math.max(1e-6, hypot(ux, uy));
        }
        fx /= fl;
        fy /= fl;
        // ...turned towards the open water around the mouth (a ring of samples, weighted by depth)
        GenPoint pr = new GenPoint();
        double sxs = 0, sys = 0;
        for (int k = 0; k < 16; k++) {
            double an = k * TAU / 16, cx = Math.cos(an), cy = Math.sin(an);
            for (double rr : new double[] { 0.35 * Rd, 0.7 * Rd }) {
                double hb = baseAt(M[b] + cx * rr, M[b + 1] + cy * rr, pr, s);
                double wgt = clamp(-hb, 0, 30);
                sxs += cx * wgt;
                sys += cy * wgt;
            }
        }
        double sl2 = hypot(sxs, sys);
        if (sl2 > 1e-6) {
            double kx = fx + 1.5 * sxs / sl2, ky = fy + 1.5 * sys / sl2, kl = hypot(kx, ky);
            if (kl > 1e-6) {
                fx = kx / kl;
                fy = ky / kl;
            }
        }
        final double PHI = 0.95, step = Math.max(14, 0.025 * Rd), Lc = Math.max(walked, 0.1 * Rd); // sea level at the old coast
        int maxDepth = (int) Math.min(3, 1 + Math.floor(M[b + 6] / 25)); // up to 8 branches for the largest rivers
        int idSeed = (int) M[b + 7];
        GenPoint p = new GenPoint();
        double bx0 = ax, by0 = ay, bx1 = ax, by1 = ay;
        java.util.ArrayDeque<Branch> todo = new java.util.ArrayDeque<>();
        Branch root = new Branch();
        root.x = ax;
        root.y = ay;
        root.lo = -PHI;
        root.hi = PHI;
        root.w = wS;
        todo.add(root);
        int ids = 0;
        while (!todo.isEmpty()) {
            Branch br = todo.poll();
            br.id = ids++;
            int first = out.nv;
            out.vertex(br.x, br.y, br.w, zS * Math.max(0, 1 - br.s / Lc), br.s);
            boolean split = false;
            double joinZ = Double.NaN;
            double reachR = Rd * (0.8 + 0.35 * rnd(idSeed, br.id, SD.delta + 3));
            double splitAt = Rd * (0.16 + 0.12 * rnd(idSeed, br.id, SD.delta + 4));
            for (int it = 0; it < 400; it++) {
                double mid = 0.5 * (br.lo + br.hi), half = 0.5 * (br.hi - br.lo);
                // wandering heading, then the lowest of a few headings around it: branches follow the low ground
                double th0 = mid + 0.5 * half * perlin(br.s / (0.25 * Rd) + br.id * 3.7, idSeed * 0.013, SD.delta + 5)
                    + 0.5 * perlin(br.s / 70 + br.id * 1.3, idSeed * 0.017 + 5.1, SD.delta + 6);
                double r0 = hypot(br.x - ax, br.y - ay);
                double nx2 = 0, ny2 = 0, hb = 1e9, score = 1e18, vx = 0, vy = 0, r = 0;
                for (int cnd = -2; cnd <= 2; cnd++) {
                    double th = th0 + cnd * 0.3;
                    double hx = fx * Math.cos(th) - fy * Math.sin(th), hy = fx * Math.sin(th) + fy * Math.cos(th);
                    double cx2 = br.x + hx * step, cy2 = br.y + hy * step;
                    // keep inside the sector: bearing from the apex clamped to [lo, hi] (with a small margin)
                    double ux2 = cx2 - ax, uy2 = cy2 - ay, rr = hypot(ux2, uy2);
                    if (rr > 1e-6) {
                        double al = ux2 * fx + uy2 * fy, cr = ux2 * fy - uy2 * fx;
                        double bear = -Math.atan2(cr, al), mg = Math.min(0.03, 0.2 * half);
                        double bc = clamp(bear, br.lo + mg, br.hi - mg);
                        if (bc != bear) {
                            double kx = fx * Math.cos(bc) - fy * Math.sin(bc), ky = fx * Math.sin(bc) + fy * Math.cos(bc);
                            cx2 = ax + kx * rr;
                            cy2 = ay + ky * rr;
                            ux2 = cx2 - ax;
                            uy2 = cy2 - ay;
                        }
                    }
                    double hc = baseAt(cx2, cy2, p, s);
                    double sc = Math.max(hc, -6) + 1.5 * Math.abs(cnd); // water is all alike; prefer going straight
                    if (rr < r0 + 0.3 * step) sc += 1e6; // always away from the apex: a branch never turns back inland
                    // stays within its network's reach (the stage only asks the networks of cells this close)
                    if (sc < 1e6 && graph.cellAt(cx2, cy2) != ck && !contains(graph.nearCells(cx2, cy2, 0.6 * MARGIN), ck))
                        sc += 1e6;
                    if (sc < score) {
                        score = sc;
                        hb = hc;
                        nx2 = cx2;
                        ny2 = cy2;
                        vx = ux2;
                        vy = uy2;
                        r = rr;
                    }
                }
                if (hb > zS + 25 || br.s > 2.5 * Rd || score >= 1e6) break; // boxed in (hills, the reach), or lost
                double[] hit = fs.hit(br.x, br.y, nx2, ny2);
                if (hit != null) { // meets a branch of another delta: joins it there
                    double hs = br.s + step * hit[0];
                    out.vertex(hit[1], hit[2], br.w * (1 - 0.25 * Math.min(1, r / Rd)), hit[3], hs);
                    joinZ = hit[3];
                    br.s = hs;
                    break;
                }
                br.s += step;
                br.x = nx2;
                br.y = ny2;
                double w = br.w * (1 - 0.25 * Math.min(1, r / Rd));
                out.vertex(nx2, ny2, w, zS * Math.max(0, 1 - br.s / Lc), br.s);
                bx0 = Math.min(bx0, nx2);
                by0 = Math.min(by0, ny2);
                bx1 = Math.max(bx1, nx2);
                by1 = Math.max(by1, ny2);
                if (hb < -30 || (r > reachR && hb < -2)) break; // off the shelf, or far enough and in the sea
                if (br.depth < maxDepth && br.s - br.lastSplit > splitAt && r > 0.08 * Rd && br.w > 1.6) {
                    // split: each child takes the part of the sector on its side of the current bearing
                    double al = vx * fx + vy * fy, cr = vx * fy - vy * fx, bear = clamp(-Math.atan2(cr, al), br.lo, br.hi);
                    double cut = clamp(bear, br.lo + 0.3 * (br.hi - br.lo), br.hi - 0.3 * (br.hi - br.lo));
                    for (int side = 0; side < 2; side++) {
                        Branch c = new Branch();
                        c.x = br.x;
                        c.y = br.y;
                        c.lo = side == 0 ? br.lo : cut;
                        c.hi = side == 0 ? cut : br.hi;
                        c.w = br.w * 0.72;
                        c.s = br.s;
                        c.lastSplit = br.s;
                        c.depth = br.depth + 1;
                        todo.add(c);
                    }
                    split = true;
                    break;
                }
            }
            if (joinZ == joinZ) // joined: its water meets the other branch's level (never below it: no step up)
                for (int vv = first; vv < out.nv; vv++)
                    out.v[vv * RiverNet.DV + 3] = (float) Math.max(out.v[vv * RiverNet.DV + 3], joinZ);
            // field 4: distance left to the tip of a leaf branch (the plain narrows there), or "no tip"
            for (int vv = first; vv < out.nv; vv++)
                out.v[vv * RiverNet.DV + 4] = split || joinZ == joinZ ? 1e9f : (float) (br.s - out.v[vv * RiverNet.DV + 4]);
            out.channel(first, out.nv - first);
        }
        if (out.nc == out.mc[q * 2]) {
            // no branch could leave the apex (a steep or mountain coast): no delta. Like such coasts in reality the river
            // ends in an estuary, or in a fjord where the coast was glaciated (the stage turns estuaries off there and the
            // glacial trough runs below sea level)
            for (int sl : marked) skip[sl] = 0;
            M[b + 4] = 0;
            M[b + 8] = (float) Math.max(E, 0.75);
            return;
        }
        double plain = clamp(0.2 * Rd, 30, 260);
        out.mc[q * 2 + 1] = out.nc - out.mc[q * 2];
        out.box[q * 5] = (float) (bx0 - plain - wS);
        out.box[q * 5 + 1] = (float) (by0 - plain - wS);
        out.box[q * 5 + 2] = (float) (bx1 + plain + wS);
        out.box[q * 5 + 3] = (float) (by1 + plain + wS);
        out.box[q * 5 + 4] = (float) plain;
    }

    /** Margin (blocks) around a basin cell: the valley field reaches this far into the neighbours, then fades out. */
    static final double MARGIN = 960;

    /** River half-width for a flow (in threshold units), capped by {@code riverMaxHalfWidth}. */
    float halfWidth(double flowOverThr) {
        // sqrt for small rivers, plus a linear term that makes the large ones grow much wider (flow 100 → ~40)
        return (float) Math.min(P.riverMaxHalfWidth, 1.2 + 2.2 * Math.sqrt(flowOverThr) + P.riverWidthGrowth * flowOverThr);
    }

    // ------------------------------------------------------------------ per-build state
    // The build runs in phases, one method each: a single method holding all of them was over the JIT's huge-method
    // limit (8000 bytes of bytecode) and ran interpreted. A builder is used for one build only.

    private int ny, n, exitIdx = -1, cnt, count, nm, ne;
    private long ck;
    private double thr, reach;
    private boolean serial;
    private float[] hgt, run, inflow, rkey, scarpG, filled, acc, hw, wl, fallDrop, fallT, cellA;
    private boolean[] own, entry, isRiv;
    private byte[] cer;
    private int[] recv, order, mainUp, slot, estSeg;
    private Map<Integer, Double> lakes;
    // reaches
    private float[] pts, bb, w0, w1, z0, z1, sacc, am0, am1, fallIn, mouthsOut;
    private byte[] lipOut, inLake, head, skip;
    private DeltaOut dOut;
    // valley field
    private char[] vd;
    private short[] vz;
    private byte[] vw, va, vr;

    RiverNet build(BasinGraph.Cell cell, BasinGraph graph, GenScratch s) {
        final double B = graph.size();
        g = P.riverGrid;
        final double H = 1.35 * B + MARGIN;
        gx0 = floor((cell.fx - H) / g);
        gy0 = floor((cell.fy - H) / g);
        x0 = (double) gx0 * g;
        y0 = (double) gy0 * g;
        nx = (int) Math.ceil(2 * H / g) + 1;
        ny = nx;
        n = nx * ny;
        ck = BasinGraph.key(cell.ci, cell.cj);
        thr = P.riverThreshold * 45 * (32.0 / g) * (32.0 / g);
        serial = RiverStage.serialBuilds(); // background callers keep to their own thread
        long tLap = System.nanoTime();
        buildDeps(cell, graph);
        tLap = lap(GenStats.RIVER_DEPS, tLap);
        reliefGrid(cell, graph);
        tLap = lap(GenStats.RIVER_GRID, tLap);
        flood();
        tLap = lap(GenStats.RIVER_FLOW, tLap);
        findLakes(s);
        tLap = lap(GenStats.RIVER_LAKES, tLap);
        accumulate();
        tLap = lap(GenStats.RIVER_ACC, tLap);
        waterLevels(cell, graph);
        tLap = lap(GenStats.RIVER_LEVELS, tLap);
        reaches();
        tLap = lap(GenStats.RIVER_REACHES, tLap);
        deltas(cell, graph, s);
        tLap = lap(GenStats.RIVER_DELTAS, tLap);
        valleyField();
        placeLakes(); // their levels need the rivers' water and valleys
        lap(GenStats.RIVER_VALLEY, tLap);
        return assemble(cell);
    }

    /**
     * This network needs the exits of its upstream basins and the deltas of its lower-coloured coastal neighbours, which
     * need theirs in turn: builds the missing ones side by side instead of one after another (a whole river system after
     * a teleport).
     */
    private void buildDeps(BasinGraph.Cell cell, BasinGraph graph) {
        List<BasinGraph.Cell> deps = new ArrayList<>(graph.children(cell));
        if (cell.outlet) for (int dj = -1; dj <= 1; dj++) for (int di = -1; di <= 1; di++) {
            if ((di | dj) == 0) continue;
            BasinGraph.Cell nb = graph.cell(cell.ci + di, cell.cj + dj);
            if (nb.outlet && colour(nb) < colour(cell)) deps.add(nb);
        }
        deps.removeIf(d -> gen.rivers.hasExit(d.ci, d.cj));
        if (deps.size() > 1 && !serial) deps.parallelStream()
            .forEach(d -> gen.rivers.exit(d.ci, d.cj));
        else for (BasinGraph.Cell d : deps) gen.rivers.exit(d.ci, d.cj);
    }

    /** Coarse relief, runoff, rock and cliffs of every node of the basin (and the entry nodes on its divide). */
    private void reliefGrid(BasinGraph.Cell cell, BasinGraph graph) {
        final int nx = this.nx, ny = this.ny, n = this.n;
        final long ck = this.ck;
        final float[] hgt = this.hgt = new float[n], run = this.run = new float[n];
        final boolean[] own = this.own = new boolean[n], entry = this.entry = new boolean[n];
        final float[] inflow = this.inflow = new float[n];
        final float[] rkey = this.rkey = new float[n]; // routing bias: makes the flow converge on smooth slopes and flats
        final byte[] cer = this.cer = new byte[n];
        final float[] scarpG = this.scarpG = new float[n]; // nearness to a cliff (plateau scarp, rift fault, sea cliff): waterfall sites
        // where the rivers of upstream basins come in, with everything they carry
        for (BasinGraph.Cell ch : graph.children(cell)) {
            int ei = BasinGraph.keyA(ch.exitNode) - gx0, ej = BasinGraph.keyB(ch.exitNode) - gy0;
            if (ei < 1 || ej < 1 || ei >= nx - 1 || ej >= ny - 1) continue;
            entry[ej * nx + ei] = true;
            inflow[ej * nx + ei] += gen.rivers.exit(ch.ci, ch.cj).flow; // what really leaves it, not the estimate
        }
        int exit = -1;
        if (cell.drainsToNeighbour()) {
            int xi = BasinGraph.keyA(cell.exitNode) - gx0, xj = BasinGraph.keyB(cell.exitNode) - gy0;
            if (xi >= 0 && xj >= 0 && xi < nx && xj < ny) exit = xj * nx + xi;
        }
        exitIdx = exit;
        // independent per cell: rows run in parallel, with per-thread temporaries (a row never nests another build)
        par(java.util.stream.IntStream.range(0, ny), serial).forEach(j -> {
            GenPoint rt = ROW_POINT.get();
            GenScratch rs = ROW_SCRATCH.get();
            long[] mem = new long[nx]; // basin cell of every node of the row, from the lattice shared by all networks
            graph.memberRow(gx0, gy0 + j, nx, mem);
            for (int i = 0; i < nx; i++) {
                int idx = j * nx + i;
                double x = x0 + (i + 0.5) * g, y = y0 + (j + 0.5) * g;
                if (!entry[idx] && mem[i] != ck) continue;
                own[idx] = true;
                gen.plates.apply(x, y, rt, rs);
                gen.continents.apply(x, y, rt);
                // sea where the relief is below sea level: a pocket of slightly negative continentality can still be land
                // (a river ending in it would stop dead inland)
                double hb = rt.L < -0.2 ? -1 : gen.relief.baseHeight(x, y, rt, false, rs);
                if (hb < 0) {
                    hgt[idx] = -1;
                    continue;
                }
                hgt[idx] = (float) hb;
                scarpG[idx] = (float) rt.scarp;
                rkey[idx] = (float) (P.riverRouteNoise * Noise.fbm(x / 450, y / 450, SD.basinW + 7, 3));
                rt.h0 = hgt[idx];
                gen.geology.apply(x, y, rt, rs);
                cer[idx] = (byte) Math.round(rt.erod * 255);
                gen.climate.apply(x, y, rt);
                run[idx] = (float) (rt.Pb * (0.4 + 0.6 * Math.exp(-rt.L / 0.4)) / 1000) + inflow[idx];
            }
        });
        if (exitIdx >= 0) own[exitIdx] = true;
    }

    /** Priority-flood from the basin's outlets (the sea inside the cell, or the exit node on the divide), then receivers. */
    private void flood() {
        final int nx = this.nx, ny = this.ny, n = this.n, exitIdx = this.exitIdx;
        final float[] hgt = this.hgt, rkey = this.rkey;
        final boolean[] own = this.own;
        float[] filled = this.filled = new float[n];
        int[] recv = this.recv = new int[n], order = this.order = new int[n];
        Arrays.fill(recv, -1);
        boolean[] done = new boolean[n];
        Heap heap = new Heap(n);
        int cnt = 0;
        for (int idx = 0; idx < n; idx++) {
            if (!own[idx] || (hgt[idx] >= 0 && idx != exitIdx)) continue;
            done[idx] = true;
            filled[idx] = hgt[idx];
            heap.push(hgt[idx], idx);
        }
        if (heap.n == 0) { // endorheic basin: everything drains to its lowest point
            int lo = -1;
            for (int idx = 0; idx < n; idx++) if (own[idx] && (lo < 0 || hgt[idx] < hgt[lo])) lo = idx;
            if (lo >= 0) {
                done[lo] = true;
                filled[lo] = hgt[lo];
                heap.push(hgt[lo], lo);
            }
        }
        while (heap.n > 0) {
            int idx = heap.pop();
            order[cnt++] = idx;
            int i = idx % nx, j = (idx - i) / nx;
            for (int k = 0; k < 8; k++) {
                int ii = i + DI[k], jj = j + DJ[k];
                if (ii < 0 || jj < 0 || ii >= nx || jj >= ny) continue;
                int nb = jj * nx + ii;
                if (!own[nb] || done[nb]) continue;
                done[nb] = true;
                filled[nb] = (float) Math.max(hgt[nb], filled[idx] + (k < 4 ? 0.01 : 0.014));
                recv[nb] = idx;
                heap.push(filled[nb], nb);
            }
        }
        this.cnt = cnt;

        // receivers: steepest descent on the filled surface plus a routing bias, among strictly lower neighbours (so the
        // flood order stays a valid upstream → downstream order). Without the bias, smooth slopes and flats drain in
        // parallel lines, one river per grid node.
        if (P.riverRouteNoise > 0) {
            for (int idx = 0; idx < n; idx++) {
                if (recv[idx] < 0 || hgt[idx] < 0) continue;
                int i = idx % nx, j = (idx - i) / nx, best = recv[idx];
                double fi = filled[idx] + rkey[idx], bs = Double.NEGATIVE_INFINITY;
                for (int k = 0; k < 8; k++) {
                    int ii = i + DI[k], jj = j + DJ[k];
                    if (ii < 0 || jj < 0 || ii >= nx || jj >= ny) continue;
                    int nb = jj * nx + ii;
                    if (!own[nb] || filled[nb] >= filled[idx]) continue;
                    double sl = (fi - filled[nb] - (hgt[nb] < 0 ? 0 : rkey[nb])) / (k < 4 ? 1 : 1.4142);
                    if (sl > bs) {
                        bs = sl;
                        best = nb;
                    }
                }
                recv[idx] = best;
            }
        }
    }

    /** Lakes: cells the flood had to fill (closed depressions); arid basins only keep a salt flat. */
    private void findLakes(GenScratch s) {
        final int nx = this.nx, ny = this.ny, n = this.n;
        final float[] hgt = this.hgt, run = this.run, filled = this.filled;
        final boolean[] own = this.own, entry = this.entry;
        final boolean serial = this.serial;
        Map<Integer, Double> lakes = this.lakes = new HashMap<>();
        if (P.lakes <= 0) return;
        byte[] inL = new byte[n];
        int[] stack = new int[n];
        final int[] cells = new int[n];
        for (int idx = 0; idx < n; idx++)
            if (own[idx] && hgt[idx] >= 0 && filled[idx] - hgt[idx] > 2 / P.lakes) inL[idx] = 1;
        // depressions first (cells grouped in `cells`, one range each), then the real floor of every one of them
        // in parallel: those samples run the pipeline up to erosion and were most of a cold network build
        final List<double[]> comps = new ArrayList<>(); // {start, count, lev, rr, bot, cap}
        int used = 0;
        for (int s0 = 0; s0 < n; s0++) {
            if (inL[s0] != 1) continue;
            int nc = 0, sp = 0;
            double lev = 0, rr = 0, bot = 1e9;
            // a depression that reaches the basin's divide could spill into the neighbouring basin there (the flood only
            // knows this basin): its water stays below the lowest of its cells on the divide. One wholly inside the basin
            // is only limited by its own spill level (the flood level).
            double cap = Double.POSITIVE_INFINITY;
            inL[s0] = 2;
            stack[sp++] = s0;
            while (sp > 0) {
                int c = stack[--sp];
                cells[used + nc++] = c;
                if (filled[c] > lev) lev = filled[c];
                if (hgt[c] < bot) bot = hgt[c];
                rr += run[c];
                int ci = c % nx, cj = (c - ci) / nx;
                boolean divide = entry[c];
                for (int k = 0; k < 4; k++) {
                    int ii = ci + (k == 0 ? 1 : k == 1 ? -1 : 0), jj = cj + (k == 2 ? 1 : k == 3 ? -1 : 0);
                    if (ii < 0 || jj < 0 || ii >= nx || jj >= ny) {
                        divide = true;
                        continue;
                    }
                    int nb = jj * nx + ii;
                    if (!own[nb] || entry[nb]) divide = true;
                    if (inL[nb] == 1) {
                        inL[nb] = 2;
                        stack[sp++] = nb;
                    }
                }
                if (divide) cap = Math.min(cap, hgt[c] - 1);
            }
            if (nc < 2) continue;
            // the level set below only ends lower than this (the real floor can only lower it), and no cell of the
            // depression lies below bot: such a depression never holds water, so its floor is not worth sampling
            double frac0 = ss(250, 700, rr / nc * 1000);
            if (Math.min(bot + (lev - bot) * (frac0 < 0.12 ? 0.1 : 0.25 + 0.75 * frac0), cap) <= bot) continue;
            comps.add(new double[] { used, nc, lev, rr, bot, cap });
            used += nc;
        }
        // anchor each lake to the real (eroded) basin floor: the coarse grid only knows the smoothed relief
        final double[] floors = new double[comps.size()];
        par(java.util.stream.IntStream.range(0, comps.size()), serial).forEach(q0 -> {
            double[] cp = comps.get(q0);
            int st = (int) cp[0], nc = (int) cp[1];
            double botF = 1e9;
            for (int q = 0, step = Math.max(1, nc / 24); q < nc; q += step) {
                int c = cells[st + q], ci = c % nx, cj = (c - ci) / nx;
                botF = Math.min(botF, finalHeight(x0 + (ci + 0.5) * g, y0 + (cj + 0.5) * g, s));
            }
            floors[q0] = botF;
        });
        lakeComps = comps;
        lakeFloors = floors;
        lakeCells = cells;
    }

    /** Largest drop of a river's water per grid node outside waterfalls (tributaries descend to the river they join). */
    static final double WATER_MAX_DROP = 2.5;

    private List<double[]> lakeComps;
    private double[] lakeFloors;
    private int[] lakeCells;

    /** Depressions made into river lakes (placeLakes leaves them alone), river nodes in one, their number. */
    private boolean[] riverLakeDone, lakeRiv;
    private int riverLakes;
    /** Lowest water a river node may have: the level of the river lake it lies in or flows into (water never climbs). */
    private float[] lakeReq;

    /**
     * River lakes, on the raw river profile (each node at its valley floor, before the profile is made monotone): where a
     * river runs through a depression, the depression fills up to the floor of the valley where the river leaves it
     * (its outlet), instead of the river cutting down through that rim. The lake covers the cells of the depression's
     * spill area whose eroded terrain lies below that level (and the river nodes whose valley floor does), found by a
     * flood over them from the river; the river's water there is the lake's level, and every river node upstream keeps at
     * least that level. Arid basins fill partly (the river then cuts its outlet down to the lake), salt flats stay as they
     * are; depressions near the sea (deltas, estuaries), on the basin's entries or exit, or in glacial troughs are left to
     * {@link #placeLakes}.
     */
    private void riverLakes(GenScratch s) {
        final int nx = this.nx, ny = this.ny, n = this.n, cnt = this.cnt;
        final float[] hgt = this.hgt, wl = this.wl, acc = this.acc, filled = this.filled;
        final int[] recv = this.recv, order = this.order;
        final boolean[] isRiv = this.isRiv, own = this.own, entry = this.entry;
        final float[] req = this.lakeReq = new float[n];
        Arrays.fill(req, Float.NaN);
        lakeRiv = new boolean[n];
        final List<double[]> comps = lakeComps;
        if (comps == null || comps.isEmpty()) return;
        riverLakeDone = new boolean[comps.size()];
        final int[] cells = lakeCells;
        int[] compOf = new int[n], seen = new int[n];
        Arrays.fill(compOf, -1);
        int[] queue = new int[n];
        for (int q0 = 0; q0 < comps.size(); q0++) {
            double[] cp = comps.get(q0);
            int st = (int) cp[0], nc = (int) cp[1];
            double frac = ss(250, 700, cp[3] / nc * 1000);
            if (frac < 0.12) continue; // salt flat
            int main = -1;
            boolean bad = false;
            for (int q = 0; q < nc; q++) {
                int c = cells[st + q];
                compOf[c] = q0;
                if (isRiv[c] && (main < 0 || acc[c] > acc[main])) main = c;
                if (entry[c] || c == exitIdx) bad = true;
            }
            if (main < 0 || bad) continue;
            int out = main;
            for (int guard = 0; out >= 0 && compOf[out] == q0 && guard < n; guard++) out = recv[out];
            if (out < 0 || hgt[out] < 0 || !isRiv[out] || entry[out]) continue;
            double floorW = Double.POSITIVE_INFINITY;
            for (int q = 0; q < nc; q++) if (isRiv[cells[st + q]]) floorW = Math.min(floorW, wl[cells[st + q]]);
            // the outlet's water once the river below it is made monotone and descends at most MAX_DROP per node: walking
            // down from the outlet, the running minimum of the valley floors plus the drop allowed back up to it
            double run = wl[out], L0 = wl[out];
            for (int p = recv[out], k = 1; p >= 0 && k < 64; p = recv[p], k++) {
                if (hgt[p] < 0) { // the sea
                    L0 = Math.min(L0, WATER_MAX_DROP * k);
                    break;
                }
                if (isRiv[p] || p == exitIdx) run = Math.min(run, wl[p]);
                L0 = Math.min(L0, run + WATER_MAX_DROP * k);
            }
            L0 = Math.min(L0, cp[5]);
            if (L0 < 12 || L0 <= floorW + 1) continue;
            final double L = floorW + (L0 - floorW) * (0.25 + 0.75 * frac);
            if (L <= floorW + 1) continue;
            // not near the sea (deltas, estuaries)
            for (int q = 0; q < nc && !bad; q++) {
                int c = cells[st + q], ci = c % nx, cj = c / nx;
                for (int dj = -6; dj <= 6 && !bad; dj += 2) for (int di = -6; di <= 6; di += 2) {
                    int ii = ci + di, jj = cj + dj;
                    if (ii >= 0 && jj >= 0 && ii < nx && jj < ny && hgt[jj * nx + ii] < 0) {
                        bad = true;
                        break;
                    }
                }
            }
            if (bad) continue;
            // not in a glacial trough (its water and floor lie below the river's level)
            if (riverWater(main) < wl[main] - 0.5 || riverWater(out) < wl[out] - 0.5) continue;
            // the lake: from the river nodes below the level, over every cell whose eroded terrain lies below it (river
            // nodes: whose valley floor does). It must be closed by terrain at or above the level: reaching the sea,
            // another basin, an entry or the exit below it means the water would leave elsewhere (or the coarse
            // depression does not match the real terrain): then no river lake
            final int stamp = q0 + 1, maxCells = 8 * nc + 100;
            boolean leak = false;
            int qh = 0, qt = 0;
            for (int q = 0; q < nc; q++) {
                int c = cells[st + q];
                if (isRiv[c] && wl[c] < L && seen[c] != stamp) {
                    seen[c] = stamp;
                    queue[qt++] = c;
                }
            }
            while (qh < qt && qt <= maxCells && !leak) {
                int c = queue[qh++], ci = c % nx, cj = c / nx;
                for (int k = 0; k < 4 && qt <= maxCells; k++) {
                    int ii = ci + (k == 0 ? 1 : k == 1 ? -1 : 0), jj = cj + (k == 2 ? 1 : k == 3 ? -1 : 0);
                    if (ii < 0 || jj < 0 || ii >= nx || jj >= ny) {
                        leak = true;
                        break;
                    }
                    int nb = jj * nx + ii;
                    if (seen[nb] == stamp) continue;
                    seen[nb] = stamp;
                    boolean in = isRiv[nb] && own[nb] ? wl[nb] < L
                        : hgt[nb] < 0 || finalHeight(x0 + (ii + 0.5) * g, y0 + (jj + 0.5) * g, s) < L;
                    if (!in) continue; // the shore
                    if (!own[nb] || hgt[nb] < 0 || entry[nb] || nb == exitIdx || nb == out) {
                        leak = true;
                        break;
                    }
                    queue[qt++] = nb;
                }
            }
            if (leak || qt > maxCells || qt < 2) continue; // spills elsewhere, or too small: a plain lake (if any)
            for (int q = 0; q < qt; q++) {
                int c = queue[q];
                lakes.put(c, L);
                if (isRiv[c]) {
                    lakeRiv[c] = true;
                    wl[c] = (float) L;
                    req[c] = (float) L;
                }
            }
            req[out] = (float) L; // leaves at the lake's level, then descends as usual
            riverLakeDone[q0] = true;
            riverLakes++;
        }
        // water never climbs: every river node upstream of a lake keeps at least its level (downstream first)
        for (int k = 0; k < cnt; k++) {
            int idx = order[k], r = recv[idx];
            if (!isRiv[idx] || r < 0 || req[r] != req[r]) continue;
            if (!(req[idx] >= req[r])) req[idx] = req[r];
        }
        keepLakeLevels();
    }

    /** Raises every river node to the level of the lake it lies in or flows into (see {@link #riverLakes}). */
    private void keepLakeLevels() {
        final float[] req = lakeReq, wl = this.wl;
        if (riverLakes == 0) return;
        for (int idx = 0; idx < n; idx++) if (req[idx] == req[idx] && isRiv[idx] && wl[idx] < req[idx]) wl[idx] = req[idx];
    }

    /**
     * Lake levels, once the rivers know their water on the final terrain: a lake never stands above the water of a river
     * that runs through it or along it (the river's valley is carved down to that water, and the lake would stand as a
     * wall over it), so it drains through the river at the river's level.
     */
    /**
     * Water of river node rn as the river stage draws it: its level, lowered where the valley was a glacial trough (the
     * trough and its water lie below the river's level by up to the trough depth; see RiverStage.broadValley and the
     * channel). Climate from the final pipeline at the node, relief from the coarse grid around it.
     */
    private double riverWater(int rn) {
        double z = wl[rn];
        GenPoint o = gen.evaluate(jx(rn), jy(rn), Stage.CLIMATE_FINAL, 1, finalPoint.get(), finalScratch.get());
        double G = gen.rivers.glacial(o, z);
        if (G <= 0) return z;
        int ci = rn % nx, cj = rn / nx;
        double mxh = 0;
        for (int dj = -3; dj <= 3; dj++) for (int di = -3; di <= 3; di++) {
            int ii = ci + di, jj = cj + dj;
            if (ii >= 0 && jj >= 0 && ii < nx && jj < ny && hgt[jj * nx + ii] > mxh) mxh = hgt[jj * nx + ii];
        }
        G *= ss(8, 45, Math.max(0, mxh - z)); // troughs only where the ice was boxed in by mountains
        return z - G * RiverStage.glacialDepth(z, hw[rn]);
    }

    /**
     * Height of shore cell r (coarse height h) once the broad valley of its nearest river has been carved into it: the
     * valley field of this network read at the cell, with the shape of {@code RiverStage.broadValley} (rock from the
     * cell, no coastal fade).
     */
    private double carvedShore(int r, double h) {
        int v = vd[r];
        if (v == 65535) return h;
        double D = v, zr = vz[r] / 16.0, hwv = (vw[r] & 255) / 4.0, A = (va[r] & 255) / 4.0, FR = (vr[r] & 255) * 2.0;
        GenPoint o = gen.evaluate(x0 + (r % nx + 0.5) * g, y0 + (r / nx + 0.5) * g, Stage.CLIMATE_FINAL, 1, finalPoint.get(),
            finalScratch.get());
        double G = gen.rivers.glacial(o, zr) * ss(8, 45, FR);
        double ef = clamp(lerp(0.5, ((cer[r] & 255) / 255.0 - 0.2) / 0.7, P.valleyRock), 0, 1);
        double VW = P.valleyWidth, str = 0.4 + 0.6 * ss(3.4, 7, hwv);
        double Wv = (70 + 12 * hwv) * lerp(0.7, 2.2, ef) * VW, Wu = (80 + 8 * hwv) * VW;
        double W = lerp(Wv, Wu, G) * lerp(0.6, 1, str);
        double D0 = lerp(6 + hwv + 1.15 * A, 12 + 1.5 * hwv, G);
        double floor = lerp(zr + 0.6, zr - RiverStage.glacialDepth(zr, hwv), G);
        double t = clamp((D - D0) / W, 0, 1);
        if (t >= 1 || h <= floor) return h;
        double gam = lerp(lerp(0.6, 1.5, ef), 2.2, G);
        double f = Math.pow(t * (2 - t), gam);
        return lerp(h, floor + (h - floor) * f, str * (1 - ss(700, 899, D)));
    }

    private void placeLakes() {
        final List<double[]> comps = lakeComps;
        if (comps == null) return;
        final int nx = this.nx, ny = this.ny;
        final int[] cells = lakeCells;
        final float[] hgt = this.hgt, wl = this.wl;
        final boolean[] isRiv = this.isRiv;
        final Map<Integer, Double> lakes = this.lakes;
        final int[] inComp = new int[n]; // q0 + 1 in depression q0, -(q0 + 1) on its shore
        for (int q0 = 0; q0 < comps.size(); q0++) {
            double[] cp = comps.get(q0);
            int st = (int) cp[0], nc = (int) cp[1];
            if (riverLakeDone != null && riverLakeDone[q0]) continue; // a river lake, placed with the water levels
            double lev = cp[2], rr = cp[3], bot = cp[4], botF = lakeFloors[q0];
            double mm = rr / nc * 1000, frac = ss(250, 700, mm);
            boolean salt = frac < 0.12;
            double level = bot + (lev - bot) * (salt ? 0.1 : 0.25 + 0.75 * frac);
            if (botF < bot) level += botF - bot;
            level = Math.min(level, cp[5]);
            // rivers in the depression or within two cells of it
            double riv = Double.POSITIVE_INFINITY;
            java.util.Set<Integer> seen = null;
            for (int q = 0; q < nc; q++) {
                int c = cells[st + q], ci = c % nx, cj = c / nx;
                for (int dj = -2; dj <= 2; dj++) for (int di = -2; di <= 2; di++) {
                    int ii = ci + di, jj = cj + dj;
                    if (ii < 0 || jj < 0 || ii >= nx || jj >= ny) continue;
                    int nb = jj * nx + ii;
                    if (!isRiv[nb]) continue;
                    if (seen == null) seen = new java.util.HashSet<>();
                    if (seen.add(nb)) riv = Math.min(riv, riverWater(nb));
                }
            }
            level = Math.min(level, riv);
            // ...and its shore: the cells around it as the broad valleys of the rivers leave them (a valley carved below
            // the lake's level next to it would leave its water standing as a wall)
            if (level > Math.min(bot, botF)) {
                double off = Math.min(0, botF - bot); // the coarse relief stands above the eroded terrain
                for (int q = 0; q < nc; q++) inComp[cells[st + q]] = q0 + 1;
                for (int q = 0; q < nc && level > Math.min(bot, botF); q++) {
                    int c = cells[st + q], ci = c % nx, cj = c / nx;
                    for (int dj = -1; dj <= 1; dj++) for (int di = -1; di <= 1; di++) {
                        int ii = ci + di, jj = cj + dj;
                        if (ii < 0 || jj < 0 || ii >= nx || jj >= ny) continue;
                        int nb = jj * nx + ii;
                        if (inComp[nb] == q0 + 1 || inComp[nb] == -(q0 + 1) || hgt[nb] < 0) continue;
                        inComp[nb] = -(q0 + 1); // shore cell, looked at once
                        level = Math.min(level, carvedShore(nb, hgt[nb] + off));
                    }
                }
            }
            if (level <= Math.min(bot, botF)) continue;
            boolean taken = false; // part of it already covered by a river lake: that one has the water there
            for (int q = 0; q < nc && !taken; q++) taken = lakes.containsKey(cells[st + q]);
            if (taken) continue;
            for (int q = 0; q < nc; q++) {
                int c = cells[st + q];
                if (hgt[c] < level) lakes.put(c, salt ? -level : level);
            }
        }
    }

    /** Flow accumulation (upstream → downstream), river nodes, their widths and main upstream branch. */
    private void accumulate() {
        final int n = this.n, cnt = this.cnt;
        final float[] hgt = this.hgt;
        final int[] order = this.order, recv = this.recv;
        final boolean[] own = this.own;
        final double thr = this.thr;
        // flow accumulation (upstream → downstream) and maximum basin relief
        float[] acc = this.acc = run.clone(), relief = this.relief = new float[n];
        for (int idx = 0; idx < n; idx++) relief[idx] = Math.max(0, hgt[idx]);
        for (int k = cnt - 1; k >= 0; k--) {
            int idx = order[k], r = recv[idx];
            if (r >= 0) {
                acc[r] = (float) ((double) acc[r] + acc[idx]);
                if (relief[idx] > relief[r]) relief[r] = relief[idx];
            }
        }
        boolean[] isRiv = this.isRiv = new boolean[n];
        float[] hw = this.hw = new float[n];
        int[] mainUp = this.mainUp = new int[n];
        Arrays.fill(mainUp, -1);
        int count = 0;
        for (int idx = 0; idx < n; idx++) {
            if (own[idx] && hgt[idx] >= 0 && recv[idx] >= 0 && acc[idx] >= thr * P.riverStart) {
                isRiv[idx] = true;
                count++;
                hw[idx] = halfWidth(acc[idx] / thr);
            }
        }
        this.count = count;
        for (int idx = 0; idx < n; idx++) {
            if (!isRiv[idx]) continue;
            int r = recv[idx], m = mainUp[r];
            if (m < 0 || acc[idx] > acc[m]) mainUp[r] = idx;
        }
    }

    private float[] relief;

    /**
     * Water profile projected onto the final terrain: each river node takes the floor of the eroded valley around it
     * (cross-section samples), then the profile is made monotone downstream (water never climbs). The coarse "filled"
     * profile only knows the smoothed relief and sits above the real valley floor.
     */
    private void waterLevels(BasinGraph.Cell cell, BasinGraph graph) {
        final int nx = this.nx, ny = this.ny, n = this.n, cnt = this.cnt, exitIdx = this.exitIdx;
        final float[] hgt = this.hgt, acc = this.acc, hw = this.hw, inflow = this.inflow;
        final int[] order = this.order, recv = this.recv, mainUp = this.mainUp;
        final boolean[] isRiv = this.isRiv, entry = this.entry;
        final double thr = this.thr;
        // the upstream basins already know the level their water leaves at: start from it, so the river never climbs
        // when it crosses a divide (builds them if needed; no cycles, a basin only depends on those draining into it)
        final float[] entryLevel = new float[n];
        Arrays.fill(entryLevel, Float.NaN);
        for (BasinGraph.Cell ch : graph.children(cell)) {
            int ei = BasinGraph.keyA(ch.exitNode) - gx0, ej = BasinGraph.keyB(ch.exitNode) - gy0;
            if (ei < 1 || ej < 1 || ei >= nx - 1 || ej >= ny - 1) continue;
            float lv = gen.rivers.exit(ch.ci, ch.cj).level;
            if (lv == lv) entryLevel[ej * nx + ei] = lv;
        }
        final float[] wl = this.wl = filled.clone();
        par(java.util.stream.IntStream.range(0, n), serial).forEach(idx -> { // independent per river node
            if (entry[idx]) // divide node shared with the upstream basin: its exit level, or the same floor sample
                wl[idx] = entryLevel[idx] == entryLevel[idx] ? entryLevel[idx]
                    : (float) Math.max(0, valleyFloor(idx, idx, halfWidth(inflow[idx] / thr), null));
            else if (isRiv[idx]) wl[idx] = (float) Math.max(0, valleyFloor(idx, recv[idx], hw[idx], null));
        });
        if (exitIdx >= 0) wl[exitIdx] = (float) Math.max(0, valleyFloor(exitIdx, exitIdx, halfWidth(acc[exitIdx] / thr), null));
        riverLakes(null); // on the raw profile: depressions a river runs through fill up to their outlet
        for (int k = cnt - 1; k >= 0; k--) { // upstream before downstream (reverse flood order)
            int idx = order[k], r = recv[idx];
            if (isRiv[idx] && r >= 0 && hgt[r] >= 0 && wl[idx] < wl[r]) wl[r] = wl[idx];
        }
        // a small river next to a much larger one sits on its floodplain: no higher than the large river's water there
        // (+ half a block). Otherwise its last reach would hide its drop under the large river and show it all at once at
        // the bank (a staircase), or it would run alongside it on a perched terrace.
        boolean clamped = false;
        for (int idx = 0; idx < n; idx++) {
            if (!isRiv[idx]) continue;
            int ci = idx % nx, cj = (idx - ci) / nx;
            double px = jx(idx), py = jy(idx), lim = wl[idx];
            for (int dj = -2; dj <= 2; dj++) for (int di = -2; di <= 2; di++) {
                int ii = ci + di, jj = cj + dj;
                if ((di | dj) == 0 || ii < 0 || jj < 0 || ii >= nx || jj >= ny) continue;
                int nb = jj * nx + ii;
                if (!isRiv[nb] || acc[nb] < 3 * acc[idx] || hw[nb] < 2.5 * hw[idx]) continue;
                if (hypot(jx(nb) - px, jy(nb) - py) > hw[nb] + 1.2 * g) continue;
                lim = Math.min(lim, wl[nb] + 0.5);
            }
            if (lim < wl[idx]) {
                wl[idx] = (float) lim;
                clamped = true;
            }
        }
        keepLakeLevels();
        if (clamped) for (int k = cnt - 1; k >= 0; k--) {
            int idx = order[k], r = recv[idx];
            if (isRiv[idx] && r >= 0 && hgt[r] >= 0 && wl[idx] < wl[r]) wl[r] = wl[idx];
        }
        // waterfalls: where the main stem crosses a cliff (plateau scarp, rift fault, sea cliff) it keeps its level up to
        // the lip and drops there, instead of being lowered upstream into a gorge
        final float[] fallDrop = this.fallDrop = new float[n], fallT = this.fallT = new float[n];
        findFalls(wl, recv, isRiv, mainUp, hgt, scarpG, n, fallDrop, fallT, serial);
        // ...and no other reach drops more than MAX_DROP per grid cell into its receiver: tributaries descend to the level
        // of the river they join instead of hanging above its deeper valley (downstream first, so it chains upstream)
        final double MAX_DROP = WATER_MAX_DROP;
        for (int k = 0; k < cnt; k++) {
            int idx = order[k], r = recv[idx];
            if (fallDrop[idx] > 0) continue;
            if (isRiv[idx] && r >= 0 && hgt[r] >= 0 && wl[idx] > wl[r] + MAX_DROP) wl[idx] = (float) (wl[r] + MAX_DROP);
        }
        keepLakeLevels(); // a lake keeps its level (and the rivers above it theirs) whatever the drop below its outlet
    }

    /** River reaches as quadratic Béziers with meanders on gentle reaches, river mouths and estuary reaches. */
    private void reaches() {
        final int nx = this.nx, n = this.n, cnt = this.cnt, count = this.count;
        final float[] hgt = this.hgt, acc = this.acc, hw = this.hw, wl = this.wl, filled = this.filled,
            fallDrop = this.fallDrop, fallT = this.fallT, relief = this.relief;
        final int[] order = this.order, recv = this.recv, mainUp = this.mainUp;
        final boolean[] isRiv = this.isRiv, entry = this.entry;
        final double thr = this.thr;
        // meanders: only on gentle, well-fed reaches and never on a sharp bend
        final double AMAX = 1.3 * g, MA = P.meanders;
        float[] straight = new float[n];
        Arrays.fill(straight, 1f);
        for (int idx = 0; idx < n; idx++) {
            if (!isRiv[idx] || mainUp[idx] < 0) continue;
            int mu = mainUp[idx], r = recv[idx];
            double ax = jx(idx) - jx(mu), ay = jy(idx) - jy(mu), bx = jx(r) - jx(idx), by = jy(r) - jy(idx);
            double ang = Math.abs(Math.atan2(ax * by - ay * bx, ax * bx + ay * by));
            straight[idx] = (float) ss(1.5, 0.45, ang);
        }

        int[] slot = this.slot = new int[n];
        Arrays.fill(slot, -1);
        float[] pts = this.pts = new float[count * NPT * 2], bb = this.bb = new float[count * 4];
        float[] w0 = this.w0 = new float[count], w1 = this.w1 = new float[count], z0 = this.z0 = new float[count],
            z1 = this.z1 = new float[count], sacc = this.sacc = new float[count];
        float[] ph0 = new float[count], ph1 = new float[count], am0 = this.am0 = new float[count],
            am1 = this.am1 = new float[count];
        float[] fallIn = this.fallIn = new float[count];
        byte[] lipOut = this.lipOut = new byte[count], inLake = this.inLake = new byte[count];
        float[] aUp = new float[count], cellA = this.cellA = new float[n];
        byte[] head = this.head = new byte[count];
        double[] BX = new double[NPT], BY = new double[NPT], TX = new double[NPT], TY = new double[NPT],
            LA = new double[NPT];
        double[] mouths = new double[12 * 64];
        int nm = 0;
        int ns = 0;
        double reach = 0;
        for (int k = 0; k < cnt; k++) {
            int idx = order[k];
            if (!isRiv[idx]) continue;
            int r = recv[idx], sl = ns++;
            slot[idx] = sl;
            double ax = jx(idx), ay = jy(idx);
            int mu = mainUp[idx];
            // reaches join halfway between nodes, or at the lip of a waterfall (the step of the terrain)
            double fu = mu >= 0 && fallDrop[mu] > 0 ? fallT[mu] : 0.5;
            double sx = mu >= 0 ? jx(mu) + fu * (ax - jx(mu)) : ax, sy = mu >= 0 ? jy(mu) + fu * (ay - jy(mu)) : ay;
            double fe = fallDrop[idx] > 0 ? fallT[idx] : 0.5;
            double ex, ey, A1, P1, M1;
            if (!isRiv[r]) { // mouth, or the exit node on the divide (no meander there: the next basin starts on it)
                ex = fallDrop[idx] > 0 ? ax + fe * (jx(r) - ax) : jx(r); // a fall into the sea ends at its lip
                ey = fallDrop[idx] > 0 ? ay + fe * (jy(r) - ay) : jy(r);
                A1 = hgt[r] < 0 && fallDrop[idx] == 0 ? ampAt(idx, r, filled, isRiv, hw, straight, AMAX, MA) : 0;
                P1 = 0;
                M1 = 0;
            } else if (mainUp[r] == idx) { // main stem
                int sr = slot[r];
                ex = ax + fe * (jx(r) - ax);
                ey = ay + fe * (jy(r) - ay);
                A1 = aUp[sr];
                P1 = ph0[sr];
                M1 = am0[sr];
            } else { // confluence
                int sr = slot[r], q = (sr * NPT + HALF) * 2;
                ex = pts[q];
                ey = pts[q + 1];
                A1 = 0;
                P1 = ((double) ph0[sr] + ph1[sr]) * 0.5;
                M1 = ((double) am0[sr] + am1[sr]) * 0.5;
            }
            double A0 = mu >= 0 && fallDrop[mu] == 0 ? ampAt(mu, idx, filled, isRiv, hw, straight, AMAX, MA) : 0;
            aUp[sl] = (float) A0;
            head[sl] = (byte) (mu < 0 && !entry[idx] ? 1 : 0);
            cellA[idx] = (float) Math.max(A0, A1);
            double Lt = 0;
            for (int t = 0; t < NPT; t++) {
                double u = (double) t / SEG, a0 = (1 - u) * (1 - u), a1 = 2 * u * (1 - u), a2 = u * u;
                BX[t] = a0 * sx + a1 * ax + a2 * ex;
                BY[t] = a0 * sy + a1 * ay + a2 * ey;
                TX[t] = 2 * (1 - u) * (ax - sx) + 2 * u * (ex - ax);
                TY[t] = 2 * (1 - u) * (ay - sy) + 2 * u * (ey - ay);
                if (t > 0) Lt += hypot(BX[t] - BX[t - 1], BY[t] - BY[t - 1]);
                LA[t] = Lt;
            }
            double wm = 0.5 * ((mu >= 0 ? hw[mu] : 0.6) + hw[idx]);
            double P0 = P1 + TAU * Lt / lam(wm);
            ph0[sl] = (float) P0;
            ph1[sl] = (float) P1;
            am0[sl] = (float) (M1 + Lt);
            am1[sl] = (float) M1;
            double bx0 = 1e18, by0 = 1e18, bx1 = -1e18, by1 = -1e18;
            for (int t = 0; t < NPT; t++) {
                double f = Lt > 0 ? LA[t] / Lt : (double) t / SEG;
                double A = A0 + (A1 - A0) * f;
                double px = BX[t], py = BY[t];
                if (A > 0.05) {
                    double tl = hypot(TX[t], TY[t]);
                    if (tl == 0) tl = 1;
                    double d = A * shape(P0 + (P1 - P0) * f);
                    px += -TY[t] / tl * d;
                    py += TX[t] / tl * d;
                }
                if (t == 0 && mu < 0) {
                    px = sx;
                    py = sy;
                }
                if (t == SEG && A1 == 0 && isRiv[r]) {
                    px = ex;
                    py = ey;
                }
                int q = (sl * NPT + t) * 2;
                pts[q] = (float) px;
                pts[q + 1] = (float) py;
                if (px < bx0) bx0 = px;
                if (px > bx1) bx1 = px;
                if (py < by0) by0 = py;
                if (py > by1) by1 = py;
            }
            bb[sl * 4] = (float) bx0;
            bb[sl * 4 + 1] = (float) by0;
            bb[sl * 4 + 2] = (float) bx1;
            bb[sl * 4 + 3] = (float) by1;
            double cxc = x0 + ((idx % nx) + 0.5) * g, cyc = y0 + (((idx - idx % nx) / nx) + 0.5) * g;
            reach = Math.max(
                reach,
                Math.max(
                    Math.max(Math.abs(bx0 - cxc), Math.abs(bx1 - cxc)),
                    Math.max(Math.abs(by0 - cyc), Math.abs(by1 - cyc))));
            w0[sl] = mu >= 0 ? hw[mu] : entry[idx] ? hw[idx] : 0.6f;
            w1[sl] = hw[idx];
            // a waterfall: the reach above keeps its level to the lip, the one below starts at the level of the pool
            z0[sl] = mu >= 0 && fallDrop[mu] == 0 ? (float) (0.5 * ((double) wl[mu] + wl[idx])) : wl[idx];
            fallIn[sl] = mu >= 0 ? fallDrop[mu] : 0;
            lipOut[sl] = (byte) (fallDrop[idx] > 0 ? (isRiv[r] ? 1 : 2) : 0); // 2: falls into the sea
            inLake[sl] = (byte) (lakeRiv != null && lakeRiv[idx] ? 1 : 0);
            z1[sl] = fallDrop[idx] > 0 ? wl[idx] : !isRiv[r] ? (hgt[r] < 0 ? 0 : wl[r])
                : mainUp[r] == idx ? (float) (0.5 * ((double) wl[idx] + wl[r]))
                    : (float) (0.5 * ((double) z0[slot[r]] + z1[slot[r]]));
            sacc[sl] = (float) (acc[idx] / thr);
            // mouth: sediment (flow × basin relief) vs tide decides delta or estuary
            if (!isRiv[r] && hgt[r] < 0 && sacc[sl] >= 3 && fallDrop[idx] == 0) { // a fall into the sea has no mouth
                double ux = ex - ax, uy = ey - ay;
                double l = hypot(ux, uy);
                if (l == 0) l = 1;
                ux /= l;
                uy /= l;
                double tide = 0.5 + 0.75 * perlin(ex / 2600, ey / 2600, SD.estu);
                double sed = ss(5, 45, sacc[sl]) * ss(40, 300, relief[idx]);
                // big rivers carry so much sediment that they mostly build deltas (Nile, Mississippi, Ganges)
                double E = ss(0.42, 0.62, tide - 0.55 * sed + 0.12) * Math.min(P.estuaries, 1) * ss(3, 5, sacc[sl])
                    * (1 - 0.75 * ss(40, 200, sacc[sl]));
                double Rd = sacc[sl] >= 6 && P.deltaSize > 0 ? Math.min(P.deltaMaxRadius, 95 * Math.sqrt(sacc[sl])) * P.deltaSize
                    : 0;
                double Lest = Math.min(3000, 140 + 34 * hw[idx]) * Math.max(P.estuaries, 0.3);
                double Wm = Math.min(8.0 * g, 10 + 4.2 * hw[idx]);
                if (Rd > 0 || E > 0.02) {
                    if ((nm + 1) * 12 > mouths.length) mouths = Arrays.copyOf(mouths, mouths.length * 2);
                    double[] m = { ex, ey, ux, uy, Rd, hw[idx], sacc[sl], idx, E, Lest, Wm, sl };
                    System.arraycopy(m, 0, mouths, nm * 12, 12);
                    nm++;
                }
            }
        }
        this.reach = reach;
        this.nm = nm;
        // estuary reaches: the main stem upstream of each mouth, up to Lest
        int[] estSeg = this.estSeg = new int[64 * Math.max(1, nm)];
        int ne = 0;
        float[] M = new float[nm * 12];
        for (int q = 0; q < nm * 12; q++) M[q] = (float) mouths[q];
        float[] mouthsOut = this.mouthsOut = new float[nm * RiverNet.MS];
        for (int q = 0; q < nm; q++) {
            System.arraycopy(M, q * 12, mouthsOut, q * RiverNet.MS, 12);
            int start = ne;
            { // every mouth: a delta that cannot form falls back to an estuary later
                int sl = (int) M[q * 12 + 11], idx = (int) M[q * 12 + 7];
                while (sl >= 0 && am1[sl] < M[q * 12 + 9] && ne - start < 64) {
                    estSeg[ne++] = sl;
                    int mu = mainUp[idx];
                    if (mu < 0) break;
                    idx = mu;
                    sl = slot[idx];
                }
            }
            mouthsOut[q * RiverNet.MS + 12] = start;
            mouthsOut[q * RiverNet.MS + 13] = ne - start;
        }
        this.ne = ne;
    }

    /**
     * Shared deltas: branches join those of deltas traced before them where they meet. First the deltas of the
     * lower-coloured neighbouring coastal cells (built first if needed: colours strictly decrease, no cycles), then this
     * network's own, largest river first.
     */
    private void deltas(BasinGraph.Cell cell, BasinGraph graph, GenScratch s) {
        skip = new byte[count];
        dOut = new DeltaOut();
        Foreign fs = new Foreign();
        if (cell.outlet) for (int dj = -1; dj <= 1; dj++) for (int di = -1; di <= 1; di++) {
            if ((di | dj) == 0) continue;
            BasinGraph.Cell nb = graph.cell(cell.ci + di, cell.cj + dj);
            if (!nb.outlet || colour(nb) >= colour(cell)) continue;
            RiverStage.Exit sn = gen.rivers.exit(nb.ci, nb.cj);
            fs.addChannels(sn.dv, sn.dch, 0, sn.dch.length / 2);
        }
        Integer[] byFlow = new Integer[nm];
        for (int q = 0; q < nm; q++) byFlow[q] = q;
        final float[] MO = mouthsOut;
        Arrays.sort(byFlow, (p, r) -> Float.compare(MO[r * RiverNet.MS + 6], MO[p * RiverNet.MS + 6]));
        for (int q : byFlow) {
            int c0 = dOut.nc;
            buildDelta(q, mouthsOut, dOut, skip, pts, slot, mainUp, recv, isRiv, acc, hgt, z1, w1, am0, am1, s, fs, graph,
                ck);
            fs.addChannels(dOut.v, dOut.ch, c0, dOut.nc);
        }
    }

    /** Broad valley field: chamfer distance from every land cell to the nearest river cell, and what that river is. */
    private void valleyField() {
        final int nx = this.nx, ny = this.ny, n = this.n;
        final float[] hgt = this.hgt, hw = this.hw, wl = this.wl, fallDrop = this.fallDrop, fallT = this.fallT,
            cellA = this.cellA;
        final int[] recv = this.recv, slot = this.slot;
        final boolean[] isRiv = this.isRiv;
        final byte[] skip = this.skip;
        final double VLIM = 900;
        float[] dd = new float[n];
        Arrays.fill(dd, 1e9f);
        int[] src = new int[n];
        Arrays.fill(src, -1);
        final int CHAMFER_PAD = 32;
        int ri0 = nx, rj0 = ny, ri1 = -1, rj1 = -1; // box of all river cells
        boolean skippedFall = false;
        for (int idx = 0; idx < n; idx++) {
            if (!isRiv[idx]) continue;
            int i = idx % nx, j = idx / nx;
            ri0 = Math.min(ri0, i);
            ri1 = Math.max(ri1, i);
            rj0 = Math.min(rj0, j);
            rj1 = Math.max(rj1, j);
            if (skip[slot[idx]] == 0) { // not the stretch a delta replaced
                dd[idx] = 0;
                src[idx] = idx;
            } else if (fallDrop[idx] > 0) skippedFall = true;
        }
        // Only the river box plus CHAMFER_PAD cells is swept. Outside it every cell is at least (CHAMFER_PAD + 1) * g =
        // 1056 blocks from any river, so it is dropped anyway (VLIM), and nothing it relays back inside can beat a cell's
        // distance below that. Everything that reads the field (below VLIM, and the pool re-assignment below, whose
        // Euclidean distance under VLIM means a chamfer distance under 1.083 * VLIM) is unchanged. A fall inside a
        // stretch replaced by a delta is not a source, so then the whole grid is swept as before.
        if (skippedFall || ri1 < 0) chamfer(dd, src, nx, 0, nx - 1, 0, ny - 1, g);
        else chamfer(dd, src, nx, Math.max(0, ri0 - CHAMFER_PAD), Math.min(nx - 1, ri1 + CHAMFER_PAD),
            Math.max(0, rj0 - CHAMFER_PAD), Math.min(ny - 1, rj1 + CHAMFER_PAD), g);
        // below a waterfall the valley belongs to the pool only on low ground: cells up at the level of the lip take the
        // river above the fall (otherwise the valley of the pool would cut a ramp into the cliff behind the lip)
        int[] poolTop = null; // river nodes just below a fall → the node at its lip
        for (int idx = 0; idx < n; idx++) {
            if (fallDrop[idx] <= 0) continue;
            if (poolTop == null) {
                poolTop = new int[n];
                Arrays.fill(poolTop, -1);
            }
            int b = recv[idx];
            for (int step = 0; step < 4 && b >= 0 && isRiv[b]; step++, b = recv[b]) if (poolTop[b] < 0) poolTop[b] = idx;
        }
        if (poolTop != null) for (int c = 0; c < n; c++) {
            int top = src[c] >= 0 ? poolTop[src[c]] : -1;
            if (top < 0 || hgt[c] < wl[top] - 0.3 * fallDrop[top]) continue;
            src[c] = top;
            dd[c] = (float) hypot(jx(c) - jx(top), jy(c) - jy(top));
        }
        // local relief around each river cell: is the valley boxed in by mountains (glacial trough) or open?
        float[] lrel = new float[n];
        for (int idx = 0; idx < n; idx++) {
            if (!isRiv[idx]) continue;
            int ci = idx % nx, cj = (idx - ci) / nx;
            double mxh = 0;
            for (int dj = -3; dj <= 3; dj++) for (int di = -3; di <= 3; di++) {
                int ii = ci + di, jj = cj + dj;
                if (ii >= 0 && jj >= 0 && ii < nx && jj < ny && hgt[jj * nx + ii] > mxh) mxh = hgt[jj * nx + ii];
            }
            lrel[idx] = (float) Math.max(0, mxh - wl[idx]);
        }
        char[] vd = this.vd = new char[n];
        Arrays.fill(vd, (char) 65535);
        short[] vz = this.vz = new short[n];
        byte[] vw = this.vw = new byte[n], va = this.va = new byte[n], vr = this.vr = new byte[n];
        for (int idx = 0; idx < n; idx++) {
            int sI = src[idx];
            if (sI < 0 || dd[idx] >= VLIM) continue;
            vd[idx] = (char) Math.round(dd[idx]);
            vz[idx] = (short) clamp(Math.round(wl[sI] * 16.0), -32000, 32000);
            vw[idx] = (byte) Math.min(255, Math.round(hw[sI] * 4.0));
            va[idx] = (byte) Math.min(255, Math.round(cellA[sI] * 4.0));
            vr[idx] = (byte) Math.min(255, Math.round(lrel[sI] / 2.0));
        }
        // no broad valley around a waterfall lip: the valley field is interpolated between grid cells, and between a cell
        // above the fall and one at the pool it would cut a ramp into the cliff right where the river reaches it (its water
        // would then stand over the lowered ground). There the terrain keeps its scarp; only the two channels are carved
        for (int idx = 0; idx < n; idx++) {
            if (fallDrop[idx] <= 0 || recv[idx] < 0) continue;
            int r = recv[idx];
            double lx = jx(idx) + fallT[idx] * (jx(r) - jx(idx)), ly = jy(idx) + fallT[idx] * (jy(r) - jy(idx));
            int ci = idx % nx, cj = idx / nx;
            for (int dj = -3; dj <= 3; dj++) for (int di = -3; di <= 3; di++) {
                int ii = ci + di, jj = cj + dj;
                if (ii < 0 || jj < 0 || ii >= nx || jj >= ny) continue;
                int c = jj * nx + ii;
                if (hypot(jx(c) - lx, jy(c) - ly) < 1.6 * g) vd[c] = (char) 65535;
            }
        }
    }

    /**
     * Two-pass 8-neighbour chamfer distance (forward and backward raster sweeps, twice: the second pairs settle the
     * float rounding of the first and do change values, so both stay). The distance spreads over the margin too: the
     * valley fades out before the grid ends. Sweeps the cells [i0, i1] x [j0, j1] of a grid nx wide; cells outside
     * stay untouched.
     */
    private static void chamfer(float[] dd, int[] src, int nx, int i0, int i1, int j0, int j1, double g) {
        final double DG = g * Math.sqrt(2);
        for (int pass = 0; pass < 2; pass++) {
            for (int j = j0; j <= j1; j++) for (int i = i0; i <= i1; i++) {
                int idx = j * nx + i;
                if (i > i0) relax(dd, src, idx, idx - 1, g);
                if (j > j0) {
                    relax(dd, src, idx, idx - nx, g);
                    if (i > i0) relax(dd, src, idx, idx - nx - 1, DG);
                    if (i < i1) relax(dd, src, idx, idx - nx + 1, DG);
                }
            }
            for (int j = j1; j >= j0; j--) for (int i = i1; i >= i0; i--) {
                int idx = j * nx + i;
                if (i < i1) relax(dd, src, idx, idx + 1, g);
                if (j < j1) {
                    relax(dd, src, idx, idx + nx, g);
                    if (i < i1) relax(dd, src, idx, idx + nx + 1, DG);
                    if (i > i0) relax(dd, src, idx, idx + nx - 1, DG);
                }
            }
        }
    }

    private RiverNet assemble(BasinGraph.Cell cell) {
        RiverNet net = new RiverNet();
        net.exitLevel = exitIdx >= 0 ? wl[exitIdx] : Float.NaN;
        net.exitFlow = exitIdx >= 0 ? acc[exitIdx] : 0;
        net.ci = cell.ci;
        net.cj = cell.cj;
        net.x0 = x0;
        net.y0 = y0;
        net.g = g;
        net.nx = nx;
        net.ny = ny;
        net.count = count;
        net.slot = slot;
        net.pts = pts;
        net.bb = bb;
        net.w0 = w0;
        net.w1 = w1;
        net.z0 = z0;
        net.z1 = z1;
        net.acc = sacc;
        net.head = head;
        net.am0 = am0;
        net.am1 = am1;
        net.reach = reach + 2;
        net.mouths = mouthsOut;
        net.nMouth = nm;
        net.estSeg = Arrays.copyOf(estSeg, ne);
        net.skip = skip;
        net.fallIn = fallIn;
        net.lipOut = lipOut;
        net.inLake = inLake;
        net.riverLakes = riverLakes;
        net.dv = Arrays.copyOf(dOut.v, dOut.nv * RiverNet.DV);
        net.dch = Arrays.copyOf(dOut.ch, dOut.nc * 2);
        net.dmc = dOut.mc.length == nm * 2 ? dOut.mc : Arrays.copyOf(dOut.mc, nm * 2);
        net.dbox = dOut.box.length == nm * 5 ? dOut.box : Arrays.copyOf(dOut.box, nm * 5);
        net.vd = vd;
        net.vz = vz;
        net.vw = vw;
        net.va = va;
        net.vr = vr;
        net.cer = cer;
        net.lakes = lakes;
        net.lakeIdx = lakes.keySet().stream().mapToInt(Integer::intValue).sorted().toArray();
        net.lakeLv = new double[net.lakeIdx.length];
        for (int q = 0; q < net.lakeIdx.length; q++) net.lakeLv[q] = lakes.get(net.lakeIdx[q]);
        net.buildChannelIndex();
        net.bytes = 4L * n + 2L * n * 2 + 4L * n + (long) pts.length * 4 + (long) count * 4 * 14 + lakes.size() * 48L
            + (net.chStart == null ? 0 : 4L * (net.chStart.length + net.chCells.length));
        return net;
    }

    /** Smallest drop (blocks) of a waterfall. */
    static final double MIN_FALL = 6;

    /**
     * Waterfalls on the main stems: an edge to the next node (or to the sea) whose water drops at least MIN_FALL blocks
     * and whose terrain does it in one step (a plateau scarp, a rift fault, a sea cliff: the largest step between samples
     * 4-6 blocks apart is at least 40 % of the drop and 5 blocks). Tributaries keep their gentle descent to the main
     * river (confluences are handled by the drop limit). Records the drop and where the lip is along the edge.
     */
    private void findFalls(float[] wl, int[] recv, boolean[] isRiv, int[] mainUp, float[] hgt, float[] scarpG, int n,
        float[] fallDrop, float[] fallT, boolean serial) {
        if (P.waterfalls <= 0) return;
        final int N = 8;
        // candidates first (cheap), then the terrain samples of each in parallel
        int[] cand = new int[n];
        int nc = 0;
        for (int idx = 0; idx < n; idx++) {
            if (!isRiv[idx]) continue;
            int r = recv[idx];
            if (r < 0) continue;
            boolean sea = hgt[r] < 0;
            if (!sea && (!isRiv[r] || mainUp[r] != idx)) continue;
            if (Math.max(scarpG[idx], scarpG[r]) < 0.15) continue; // falls only where a cliff is near
            if (wl[idx] - (sea ? 0 : wl[r]) < MIN_FALL / P.waterfalls) continue;
            cand[nc++] = idx;
        }
        final int[] C = cand;
        par(java.util.stream.IntStream.range(0, nc), serial).forEach(ci -> {
            int idx = C[ci], r = recv[idx];
            boolean sea = hgt[r] < 0;
            double drop = wl[idx] - (sea ? 0 : wl[r]);
            GenScratch s = null;
            double ax = jx(idx), ay = jy(idx), bx = jx(r), by = jy(r);
            double prev = finalHeight(ax, ay, s), best = 0;
            int bk = -1;
            for (int k = 1; k <= N; k++) {
                double h = finalHeight(ax + (bx - ax) * k / N, ay + (by - ay) * k / N, s);
                if (prev - h > best) {
                    best = prev - h;
                    bk = k;
                }
                prev = h;
            }
            if (bk < 0 || best < Math.max(5, 0.4 * drop)) return;
            fallDrop[idx] = (float) drop;
            fallT[idx] = (float) ((bk - 0.5) / N);
        });
    }

    private final ThreadLocal<GenPoint> finalPoint = ThreadLocal.withInitial(GenPoint::new);
    private final ThreadLocal<GenScratch> finalScratch = ThreadLocal.withInitial(GenScratch::new);

    /** Terrain height after erosion, before any river carving (what the player would see without rivers). */
    private double finalHeight(double x, double y, GenScratch s) {
        // own temporaries per thread: called from parallel workers and from inside another evaluation
        return gen.evaluate(x, y, Stage.EROSION, 1, finalPoint.get(), finalScratch.get()).h;
    }

    /**
     * Floor of the eroded valley around river node idx: minimum of the final terrain on a cross-section perpendicular
     * to the flow, as wide as the valley a river of that size carves.
     */
    private double valleyFloor(int idx, int r, double hw, GenScratch s) {
        double px = jx(idx), py = jy(idx);
        double dx = jx(r) - px, dy = jy(r) - py, l = hypot(dx, dy);
        if (l == 0) {
            dx = 1;
            l = 1;
        }
        double ux = -dy / l, uy = dx / l, R = Math.max(10, 3 * hw + 6);
        double floor = Double.POSITIVE_INFINITY;
        // three cross-sections: at the node and on the way to its receiver (the reach runs between them)
        for (int a = 0; a < 3; a++) {
            double cx = px + dx * a / 3, cy = py + dy * a / 3;
            floor = Math.min(floor, finalHeight(cx, cy, s));
            for (int k = 1; k <= 3; k++) {
                double t = R * k / 3;
                floor = Math.min(floor, finalHeight(cx + ux * t, cy + uy * t, s));
                floor = Math.min(floor, finalHeight(cx - ux * t, cy - uy * t, s));
            }
        }
        return floor;
    }

    private static void relax(float[] dd, int[] src, int idx, int nb, double w) {
        if (src[nb] >= 0 && dd[nb] + w < dd[idx]) {
            dd[idx] = (float) (dd[nb] + w);
            src[idx] = src[nb];
        }
    }

    /** Meander amplitude at the midpoint between node a and its receiver b. */
    private double ampAt(int a, int b, float[] filled, boolean[] isRiv, float[] hw, float[] straight, double AMAX,
        double MA) {
        if (MA <= 0) return 0;
        double d = hypot(jx(b) - jx(a), jy(b) - jy(a));
        if (d == 0) d = g;
        double grad = (filled[a] - (isRiv[b] ? (double) filled[b] : 0)) / d;
        return Math.min(AMAX, 3.2 * hw[a] + 4) * ss(0.05, 0.008, grad)
            * ss(1.8, 3.6, hw[a])
            * straight[a]
            * (isRiv[b] ? straight[b] : 1)
            * Math.min(MA, 1.5);
    }
}
