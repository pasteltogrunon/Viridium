package net.junedev.viridium.worldgen.terrain.stages;

import static net.junedev.viridium.worldgen.terrain.core.GenMath.TAU;
import static net.junedev.viridium.worldgen.terrain.core.GenMath.clamp;
import static net.junedev.viridium.worldgen.terrain.core.GenMath.floor;
import static net.junedev.viridium.worldgen.terrain.core.GenMath.hypot;
import static net.junedev.viridium.worldgen.terrain.core.GenMath.ss;
import static net.junedev.viridium.worldgen.terrain.core.Noise.perlin;
import static net.junedev.viridium.worldgen.terrain.core.Noise.rnd;
import static net.junedev.viridium.worldgen.terrain.stages.RiverNet.HALF;
import static net.junedev.viridium.worldgen.terrain.stages.RiverNet.NPT;
import static net.junedev.viridium.worldgen.terrain.stages.RiverNet.SEG;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

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
        boolean[] isRiv, float[] acc, float[] hgt, float[] z1, float[] w1, float[] am0, float[] am1, GenScratch s) {
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
            double reachR = Rd * (0.8 + 0.35 * rnd(idSeed, br.id, SD.delta + 3));
            double splitAt = Rd * (0.16 + 0.12 * rnd(idSeed, br.id, SD.delta + 4));
            for (int it = 0; it < 400; it++) {
                double mid = 0.5 * (br.lo + br.hi), half = 0.5 * (br.hi - br.lo);
                // wandering heading, then the lowest of a few headings around it: branches follow the low ground
                double th0 = mid + 0.5 * half * perlin(br.s / (0.25 * Rd) + br.id * 3.7, idSeed * 0.013, SD.delta + 5)
                    + 0.5 * perlin(br.s / 70 + br.id * 1.3, idSeed * 0.017 + 5.1, SD.delta + 6);
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
                if (hb > zS + 25 || br.s > 2.5 * Rd) break; // boxed in by hills (rare), or lost
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
            // field 4: distance left to the tip of a leaf branch (the plain narrows there), or "no tip"
            for (int vv = first; vv < out.nv; vv++)
                out.v[vv * RiverNet.DV + 4] = split ? 1e9f : (float) (br.s - out.v[vv * RiverNet.DV + 4]);
            out.channel(first, out.nv - first);
        }
        if (out.nc == out.mc[q * 2]) { // no branch could leave the apex (hills all around): keep the river as it was
            for (int sl : marked) skip[sl] = 0;
            M[b + 4] = 0;
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
        return (float) Math.min(P.riverMaxHalfWidth, 1.2 + 2.2 * Math.sqrt(flowOverThr));
    }

    RiverNet build(BasinGraph.Cell cell, BasinGraph graph, GenScratch s) {
        final double B = graph.size();
        g = P.riverGrid;
        final double H = 1.35 * B + MARGIN;
        gx0 = floor((cell.fx - H) / g);
        gy0 = floor((cell.fy - H) / g);
        x0 = (double) gx0 * g;
        y0 = (double) gy0 * g;
        nx = (int) Math.ceil(2 * H / g) + 1;
        final int ny = nx, n = nx * ny;
        final long ck = BasinGraph.key(cell.ci, cell.cj);
        final double thr = P.riverThreshold * 45 * (32.0 / g) * (32.0 / g);
        float[] hgt = new float[n], run = new float[n];
        boolean[] own = new boolean[n], entry = new boolean[n];
        float[] inflow = new float[n];
        float[] rkey = new float[n]; // routing bias: makes the flow converge on smooth slopes and flats
        byte[] cer = new byte[n];
        // where the rivers of upstream basins come in, with everything they carry
        for (BasinGraph.Cell ch : graph.children(cell)) {
            int ei = BasinGraph.keyA(ch.exitNode) - gx0, ej = BasinGraph.keyB(ch.exitNode) - gy0;
            if (ei < 1 || ej < 1 || ei >= nx - 1 || ej >= ny - 1) continue;
            entry[ej * nx + ei] = true;
            inflow[ej * nx + ei] += (float) graph.flowOut(ch);
        }
        int exit = -1;
        if (cell.drainsToNeighbour()) {
            int xi = BasinGraph.keyA(cell.exitNode) - gx0, xj = BasinGraph.keyB(cell.exitNode) - gy0;
            if (xi >= 0 && xj >= 0 && xi < nx && xj < ny) exit = xj * nx + xi;
        }
        final int exitIdx = exit;
        // independent per cell: rows run in parallel, each with its own temporaries
        final boolean serial = RiverStage.serialBuilds(); // background callers keep to their own thread
        par(java.util.stream.IntStream.range(0, ny), serial).forEach(j -> {
            GenPoint rt = new GenPoint();
            GenScratch rs = new GenScratch();
            for (int i = 0; i < nx; i++) {
                int idx = j * nx + i;
                double x = x0 + (i + 0.5) * g, y = y0 + (j + 0.5) * g;
                if (!entry[idx] && graph.cellAt(x, y) != ck) continue;
                own[idx] = true;
                gen.plates.apply(x, y, rt, rs);
                gen.continents.apply(x, y, rt);
                if (rt.L < 0) {
                    hgt[idx] = -1;
                    continue;
                }
                hgt[idx] = (float) gen.relief.baseHeight(x, y, rt, false, rs);
                rkey[idx] = (float) (P.riverRouteNoise * Noise.fbm(x / 450, y / 450, SD.basinW + 7, 3));
                rt.h0 = hgt[idx];
                gen.geology.apply(x, y, rt, rs);
                cer[idx] = (byte) Math.round(rt.erod * 255);
                gen.climate.apply(x, y, rt);
                run[idx] = (float) (rt.Pb * (0.4 + 0.6 * Math.exp(-rt.L / 0.4)) / 1000) + inflow[idx];
            }
        });
        if (exitIdx >= 0) own[exitIdx] = true;

        // priority-flood from the basin's outlets: the sea inside the cell, or the exit node on the divide
        float[] filled = new float[n];
        int[] recv = new int[n], order = new int[n];
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

        // lakes: cells the flood had to fill (closed depressions); arid basins only keep a salt flat
        Map<Integer, Double> lakes = new HashMap<>();
        // a lake can never stand above the lowest point of the basin's whole divide: it would spill there first
        double spill = Double.POSITIVE_INFINITY;
        for (int j = 1; j < ny - 1; j++) for (int i = 1; i < nx - 1; i++) {
            int idx = j * nx + i;
            if (!own[idx] || entry[idx] || hgt[idx] < 0) continue;
            if (!own[idx - 1] || !own[idx + 1] || !own[idx - nx] || !own[idx + nx]) spill = Math.min(spill, hgt[idx]);
        }
        if (P.lakes > 0) {
            byte[] inL = new byte[n];
            int[] stack = new int[n];
            int[] cells = new int[n];
            for (int idx = 0; idx < n; idx++)
                if (own[idx] && hgt[idx] >= 0 && filled[idx] - hgt[idx] > 2 / P.lakes) inL[idx] = 1;
            for (int s0 = 0; s0 < n; s0++) {
                if (inL[s0] != 1) continue;
                int nc = 0, sp = 0;
                double lev = 0, rr = 0, bot = 1e9;
                inL[s0] = 2;
                stack[sp++] = s0;
                while (sp > 0) {
                    int c = stack[--sp];
                    cells[nc++] = c;
                    if (filled[c] > lev) lev = filled[c];
                    if (hgt[c] < bot) bot = hgt[c];
                    rr += run[c];
                    int ci = c % nx, cj = (c - ci) / nx;
                    for (int k = 0; k < 4; k++) {
                        int ii = ci + (k == 0 ? 1 : k == 1 ? -1 : 0), jj = cj + (k == 2 ? 1 : k == 3 ? -1 : 0);
                        if (ii < 0 || jj < 0 || ii >= nx || jj >= ny) continue;
                        int nb = jj * nx + ii;
                        if (inL[nb] == 1) {
                            inL[nb] = 2;
                            stack[sp++] = nb;
                        }
                    }
                }
                if (nc < 2) continue;
                double mm = rr / nc * 1000, frac = ss(250, 700, mm);
                boolean salt = frac < 0.12;
                double level = bot + (lev - bot) * (salt ? 0.1 : 0.25 + 0.75 * frac);
                // anchor the lake to the real (eroded) basin floor: the coarse grid only knows the smoothed relief
                double botF = 1e9;
                for (int q = 0, step = Math.max(1, nc / 24); q < nc; q += step) {
                    int c = cells[q], ci = c % nx, cj = (c - ci) / nx;
                    botF = Math.min(botF, finalHeight(x0 + (ci + 0.5) * g, y0 + (cj + 0.5) * g, s));
                }
                if (botF < bot) level += botF - bot;
                level = Math.min(level, spill - 1);
                if (level <= Math.min(bot, botF)) continue;
                for (int q = 0; q < nc; q++) {
                    int c = cells[q];
                    if (hgt[c] < level) lakes.put(c, salt ? -level : level);
                }
            }
        }

        // flow accumulation (upstream → downstream) and maximum basin relief
        float[] acc = run.clone(), relief = new float[n];
        for (int idx = 0; idx < n; idx++) relief[idx] = Math.max(0, hgt[idx]);
        for (int k = cnt - 1; k >= 0; k--) {
            int idx = order[k], r = recv[idx];
            if (r >= 0) {
                acc[r] = (float) ((double) acc[r] + acc[idx]);
                if (relief[idx] > relief[r]) relief[r] = relief[idx];
            }
        }
        boolean[] isRiv = new boolean[n];
        float[] hw = new float[n];
        int[] mainUp = new int[n];
        Arrays.fill(mainUp, -1);
        int count = 0;
        for (int idx = 0; idx < n; idx++) {
            if (own[idx] && hgt[idx] >= 0 && recv[idx] >= 0 && acc[idx] >= thr * P.riverStart) {
                isRiv[idx] = true;
                count++;
                hw[idx] = halfWidth(acc[idx] / thr);
            }
        }
        for (int idx = 0; idx < n; idx++) {
            if (!isRiv[idx]) continue;
            int r = recv[idx], m = mainUp[r];
            if (m < 0 || acc[idx] > acc[m]) mainUp[r] = idx;
        }

        // water profile projected onto the final terrain: each river node takes the floor of the eroded valley
        // around it (cross-section samples), then the profile is made monotone downstream (water never climbs).
        // The coarse "filled" profile only knows the smoothed relief and sits above the real valley floor.
        // the upstream basins already know the level their water leaves at: start from it, so the river never climbs
        // when it crosses a divide (builds them if needed; no cycles, a basin only depends on those draining into it)
        float[] entryLevel = new float[n];
        Arrays.fill(entryLevel, Float.NaN);
        for (BasinGraph.Cell ch : graph.children(cell)) {
            int ei = BasinGraph.keyA(ch.exitNode) - gx0, ej = BasinGraph.keyB(ch.exitNode) - gy0;
            if (ei < 1 || ej < 1 || ei >= nx - 1 || ej >= ny - 1) continue;
            float lv = gen.rivers.net(ch.ci, ch.cj).exitLevel;
            if (lv == lv) entryLevel[ej * nx + ei] = lv;
        }
        float[] wl = filled.clone();
        final float[] wlf = wl;
        par(java.util.stream.IntStream.range(0, n), serial).forEach(idx -> { // independent per river node
            if (entry[idx]) // divide node shared with the upstream basin: its exit level, or the same floor sample
                wlf[idx] = entryLevel[idx] == entryLevel[idx] ? entryLevel[idx]
                    : (float) Math.max(0, valleyFloor(idx, idx, halfWidth(inflow[idx] / thr), null));
            else if (isRiv[idx]) wlf[idx] = (float) Math.max(0, valleyFloor(idx, recv[idx], hw[idx], null));
        });
        if (exitIdx >= 0) wl[exitIdx] = (float) Math.max(0, valleyFloor(exitIdx, exitIdx, halfWidth((float) graph.flowOut(cell) / thr), null));
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
        if (clamped) for (int k = cnt - 1; k >= 0; k--) {
            int idx = order[k], r = recv[idx];
            if (isRiv[idx] && r >= 0 && hgt[r] >= 0 && wl[idx] < wl[r]) wl[r] = wl[idx];
        }
        // ...and no reach drops more than MAX_DROP per grid cell into its receiver: tributaries descend to the level of
        // the river they join instead of hanging above its deeper valley (downstream first, so it chains upstream)
        final double MAX_DROP = 2.5;
        for (int k = 0; k < cnt; k++) {
            int idx = order[k], r = recv[idx];
            if (isRiv[idx] && r >= 0 && hgt[r] >= 0 && wl[idx] > wl[r] + MAX_DROP) wl[idx] = (float) (wl[r] + MAX_DROP);
        }

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

        int[] slot = new int[n];
        Arrays.fill(slot, -1);
        float[] pts = new float[count * NPT * 2], bb = new float[count * 4];
        float[] w0 = new float[count], w1 = new float[count], z0 = new float[count], z1 = new float[count],
            sacc = new float[count];
        float[] ph0 = new float[count], ph1 = new float[count], am0 = new float[count], am1 = new float[count];
        float[] aUp = new float[count], cellA = new float[n];
        byte[] head = new byte[count];
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
            double sx = mu >= 0 ? (ax + jx(mu)) * 0.5 : ax, sy = mu >= 0 ? (ay + jy(mu)) * 0.5 : ay;
            double ex, ey, A1, P1, M1;
            if (!isRiv[r]) { // mouth, or the exit node on the divide (no meander there: the next basin starts on it)
                ex = jx(r);
                ey = jy(r);
                A1 = hgt[r] < 0 ? ampAt(idx, r, filled, isRiv, hw, straight, AMAX, MA) : 0;
                P1 = 0;
                M1 = 0;
            } else if (mainUp[r] == idx) { // main stem
                int sr = slot[r];
                ex = (ax + jx(r)) * 0.5;
                ey = (ay + jy(r)) * 0.5;
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
            double A0 = mu >= 0 ? ampAt(mu, idx, filled, isRiv, hw, straight, AMAX, MA) : 0;
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
            z0[sl] = mu >= 0 ? (float) (0.5 * ((double) wl[mu] + wl[idx])) : wl[idx];
            z1[sl] = !isRiv[r] ? (hgt[r] < 0 ? 0 : wl[r])
                : mainUp[r] == idx ? (float) (0.5 * ((double) wl[idx] + wl[r]))
                    : (float) (0.5 * ((double) z0[slot[r]] + z1[slot[r]]));
            sacc[sl] = (float) (acc[idx] / thr);
            // mouth: sediment (flow × basin relief) vs tide decides delta or estuary
            if (!isRiv[r] && hgt[r] < 0 && sacc[sl] >= 3) {
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
                double Rd = sacc[sl] >= 6 && P.deltaSize > 0 ? Math.min(P.deltaMaxRadius, 60 * Math.sqrt(sacc[sl])) * P.deltaSize
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
        // estuary reaches: the main stem upstream of each mouth, up to Lest
        int[] estSeg = new int[64 * Math.max(1, nm)];
        int ne = 0;
        float[] M = new float[nm * 12];
        for (int q = 0; q < nm * 12; q++) M[q] = (float) mouths[q];
        float[] mouthsOut = new float[nm * RiverNet.MS];
        for (int q = 0; q < nm; q++) {
            System.arraycopy(M, q * 12, mouthsOut, q * RiverNet.MS, 12);
            int start = ne;
            if (M[q * 12 + 8] > 0.02) {
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

        byte[] skip = new byte[count];
        DeltaOut dOut = new DeltaOut();
        for (int q = 0; q < nm; q++) buildDelta(q, mouthsOut, dOut, skip, pts, slot, mainUp, recv, isRiv, acc, hgt, z1, w1,
            am0, am1, s);

        // broad valley field: chamfer distance from every land cell to the nearest river cell
        final double VLIM = 900;
        float[] dd = new float[n];
        Arrays.fill(dd, 1e9f);
        int[] src = new int[n];
        Arrays.fill(src, -1);
        for (int idx = 0; idx < n; idx++) if (isRiv[idx] && skip[slot[idx]] == 0) { // not the stretch a delta replaced
            dd[idx] = 0;
            src[idx] = idx;
        }
        final double DG = g * Math.sqrt(2);
        for (int pass = 0; pass < 2; pass++) {
            for (int j = 0; j < ny; j++) for (int i = 0; i < nx; i++) {
                int idx = j * nx + i;
                // the distance to the rivers spreads over the margin too: the valley fades out before the grid ends
                if (i > 0) relax(dd, src, idx, idx - 1, g);
                if (j > 0) {
                    relax(dd, src, idx, idx - nx, g);
                    if (i > 0) relax(dd, src, idx, idx - nx - 1, DG);
                    if (i < nx - 1) relax(dd, src, idx, idx - nx + 1, DG);
                }
            }
            for (int j = ny - 1; j >= 0; j--) for (int i = nx - 1; i >= 0; i--) {
                int idx = j * nx + i;
                if (i < nx - 1) relax(dd, src, idx, idx + 1, g);
                if (j < ny - 1) {
                    relax(dd, src, idx, idx + nx, g);
                    if (i < nx - 1) relax(dd, src, idx, idx + nx + 1, DG);
                    if (i > 0) relax(dd, src, idx, idx + nx - 1, DG);
                }
            }
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
        char[] vd = new char[n];
        Arrays.fill(vd, (char) 65535);
        short[] vz = new short[n];
        byte[] vw = new byte[n], va = new byte[n], vr = new byte[n];
        for (int idx = 0; idx < n; idx++) {
            int sI = src[idx];
            if (sI < 0 || dd[idx] >= VLIM) continue;
            vd[idx] = (char) Math.round(dd[idx]);
            vz[idx] = (short) clamp(Math.round(wl[sI] * 16.0), -32000, 32000);
            vw[idx] = (byte) Math.min(255, Math.round(hw[sI] * 4.0));
            va[idx] = (byte) Math.min(255, Math.round(cellA[sI] * 4.0));
            vr[idx] = (byte) Math.min(255, Math.round(lrel[sI] / 2.0));
        }

        RiverNet net = new RiverNet();
        net.exitLevel = exitIdx >= 0 ? wl[exitIdx] : Float.NaN;
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
        net.bytes = 4L * n + 2L * n * 2 + 4L * n + (long) pts.length * 4 + (long) count * 4 * 14 + lakes.size() * 48L;
        return net;
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
