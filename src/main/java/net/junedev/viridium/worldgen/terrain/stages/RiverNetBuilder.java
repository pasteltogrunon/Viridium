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

    RiverNet build(int pi, int pj, GenScratch s) {
        final double S = P.plateSize;
        g = P.riverGrid;
        PlateStage plates = gen.plates;
        double fx = plates.featX(pi, pj), fy = plates.featY(pi, pj);
        double R = S * 1.3 + S * P.plateWarp * 0.8;
        gx0 = floor((fx - R) / g);
        gy0 = floor((fy - R) / g);
        x0 = (double) gx0 * g;
        y0 = (double) gy0 * g;
        nx = (int) Math.ceil(2 * R / g) + 1;
        final int ny = nx, n = nx * ny;
        float[] hgt = new float[n], run = new float[n];
        boolean[] own = new boolean[n];
        byte[] cer = new byte[n];
        // independent per cell: rows run in parallel, each with its own temporaries
        java.util.stream.IntStream.range(0, ny).parallel().forEach(j -> {
            GenPoint rt = new GenPoint();
            GenScratch rs = new GenScratch();
            for (int i = 0; i < nx; i++) {
                int idx = j * nx + i;
                double x = x0 + (i + 0.5) * g, y = y0 + (j + 0.5) * g;
                plates.apply(x, y, rt, rs);
                if (rt.pi != pi || rt.pj != pj) continue;
                own[idx] = true;
                gen.continents.apply(x, y, rt);
                if (rt.L < 0) {
                    hgt[idx] = -1;
                    continue;
                }
                hgt[idx] = (float) gen.relief.baseHeight(x, y, rt, false, rs);
                rt.h0 = hgt[idx];
                gen.geology.apply(x, y, rt, rs);
                cer[idx] = (byte) Math.round(rt.erod * 255);
                gen.climate.apply(x, y, rt);
                run[idx] = (float) (rt.Pb * (0.4 + 0.6 * Math.exp(-rt.L / 0.4)) / 1000);
            }
        });

        // priority-flood: fills depressions and gives every cell a downstream receiver
        float[] filled = new float[n];
        int[] recv = new int[n], order = new int[n];
        Arrays.fill(recv, -1);
        boolean[] done = new boolean[n];
        Heap heap = new Heap(n);
        int cnt = 0;
        for (int j = 0; j < ny; j++) {
            for (int i = 0; i < nx; i++) {
                int idx = j * nx + i;
                if (!own[idx]) continue;
                if (hgt[idx] < 0 || i == 0 || j == 0 || i == nx - 1 || j == ny - 1) {
                    done[idx] = true;
                    filled[idx] = hgt[idx];
                    heap.push(hgt[idx], idx);
                }
            }
        }
        if (heap.n == 0) { // plate without ocean (all collisions): drains through its lowest cell
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

        // lakes: cells the flood had to fill (closed depressions); arid basins only keep a salt flat
        Map<Integer, Double> lakes = new HashMap<>();
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
        double thr = P.riverThreshold * 45 * (32.0 / g) * (32.0 / g);
        boolean[] isRiv = new boolean[n];
        float[] hw = new float[n];
        int[] mainUp = new int[n];
        Arrays.fill(mainUp, -1);
        int count = 0;
        for (int idx = 0; idx < n; idx++) {
            if (own[idx] && hgt[idx] >= 0 && recv[idx] >= 0 && acc[idx] >= thr) {
                isRiv[idx] = true;
                count++;
                hw[idx] = (float) Math.min(22, 1.2 + 2.2 * Math.sqrt(acc[idx] / thr));
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
        float[] wl = filled.clone();
        final float[] wlf = wl;
        java.util.stream.IntStream.range(0, n).parallel().forEach(idx -> { // independent per river node
            if (isRiv[idx]) wlf[idx] = (float) Math.max(0, valleyFloor(idx, recv[idx], hw[idx], null));
        });
        for (int k = cnt - 1; k >= 0; k--) { // upstream before downstream (reverse flood order)
            int idx = order[k], r = recv[idx];
            if (isRiv[idx] && r >= 0 && isRiv[r] && wl[idx] < wl[r]) wl[r] = wl[idx];
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
            if (!isRiv[r]) { // mouth
                ex = jx(r);
                ey = jy(r);
                A1 = ampAt(idx, r, filled, isRiv, hw, straight, AMAX, MA);
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
            head[sl] = (byte) (mu < 0 ? 1 : 0);
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
            w0[sl] = mu >= 0 ? hw[mu] : 0.6f;
            w1[sl] = hw[idx];
            z0[sl] = mu >= 0 ? (float) (0.5 * ((double) wl[mu] + wl[idx])) : wl[idx];
            z1[sl] = !isRiv[r] ? 0
                : mainUp[r] == idx ? (float) (0.5 * ((double) wl[idx] + wl[r]))
                    : (float) (0.5 * ((double) z0[slot[r]] + z1[slot[r]]));
            sacc[sl] = (float) (acc[idx] / thr);
            // mouth: sediment (flow × basin relief) vs tide decides delta or estuary
            if (!isRiv[r] && sacc[sl] >= 3) {
                double ux = ex - ax, uy = ey - ay;
                double l = hypot(ux, uy);
                if (l == 0) l = 1;
                ux /= l;
                uy /= l;
                double tide = 0.5 + 0.75 * perlin(ex / 2600, ey / 2600, SD.estu);
                double sed = ss(5, 45, sacc[sl]) * ss(40, 300, relief[idx]);
                double E = ss(0.42, 0.62, tide - 0.55 * sed + 0.12) * Math.min(P.estuaries, 1) * ss(3, 5, sacc[sl]);
                double Rd = sacc[sl] >= 6 && P.deltaSize > 0 ? Math.min(260, 26 * Math.sqrt(sacc[sl])) * P.deltaSize
                    : 0;
                double Lest = Math.min(900, 140 + 34 * hw[idx]) * Math.max(P.estuaries, 0.3);
                double Wm = Math.min(2.6 * g, 10 + 4.2 * hw[idx]);
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

        // broad valley field: chamfer distance from every land cell to the nearest river cell
        final double VLIM = 900;
        float[] dd = new float[n];
        Arrays.fill(dd, 1e9f);
        int[] src = new int[n];
        Arrays.fill(src, -1);
        for (int idx = 0; idx < n; idx++) if (isRiv[idx]) {
            dd[idx] = 0;
            src[idx] = idx;
        }
        final double DG = g * Math.sqrt(2);
        for (int pass = 0; pass < 2; pass++) {
            for (int j = 0; j < ny; j++) for (int i = 0; i < nx; i++) {
                int idx = j * nx + i;
                if (!own[idx]) continue;
                if (i > 0) relax(dd, src, idx, idx - 1, g);
                if (j > 0) {
                    relax(dd, src, idx, idx - nx, g);
                    if (i > 0) relax(dd, src, idx, idx - nx - 1, DG);
                    if (i < nx - 1) relax(dd, src, idx, idx - nx + 1, DG);
                }
            }
            for (int j = ny - 1; j >= 0; j--) for (int i = nx - 1; i >= 0; i--) {
                int idx = j * nx + i;
                if (!own[idx]) continue;
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
            vw[idx] = (byte) Math.min(255, Math.round(hw[sI] * 10.0));
            va[idx] = (byte) Math.min(255, Math.round(cellA[sI] * 4.0));
            vr[idx] = (byte) Math.min(255, Math.round(lrel[sI] / 2.0));
        }

        RiverNet net = new RiverNet();
        net.pi = pi;
        net.pj = pj;
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
