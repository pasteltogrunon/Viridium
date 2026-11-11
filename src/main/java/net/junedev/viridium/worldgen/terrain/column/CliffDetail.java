package net.junedev.viridium.worldgen.terrain.column;

import static net.junedev.viridium.worldgen.terrain.column.ColumnPlan.NONE;

import net.junedev.viridium.worldgen.terrain.core.GenMath;
import net.junedev.viridium.worldgen.terrain.core.Noise;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;

/**
 * Block-level detail of cliffs, found from the planned heights of a column and its neighbours (no Minecraft classes):
 * <ul>
 * <li><b>notch</b>: the foot of a cliff cut back into the wall. At the sea (or a lake) the waves dig it at the water
 * line
 * and it stays flooded; on land it is a shallow overhang above the talus, where softer rock weathers faster;</li>
 * <li><b>talus</b>: the debris cone at the foot of a dry cliff (blocks above the planned surface);</li>
 * <li>for boulders: how far a column is from the foot of a wall and how tall the wall is.</li>
 * </ul>
 * A cliff face is a column whose ground drops by at least 9 blocks within the next 3 in some direction, with at least
 * 10
 * blocks of wall in total. Every value depends only on the plans (deterministic, any order); results are cached.
 */
public final class CliffDetail {

    /** Per-column result for one 16×16 column. Absolute Y; {@link ColumnPlan#NONE} where there is nothing. */
    public static final class Cols {

        public final int[] notchLo = new int[256], notchHi = new int[256], notchWater = new int[256],
            talusTop = new int[256], curtainTop = new int[256];
        /** Distance (blocks) from the foot of the nearest wall (0 = none within reach) and that wall's height. */
        public final byte[] wallDist = new byte[256];
        public final short[] wallH = new short[256];
        /** Vertical range touched by notches and talus (for cube classification); any = something to do. */
        public int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
        public boolean any;

        Cols() {
            java.util.Arrays.fill(notchLo, NONE);
            java.util.Arrays.fill(notchHi, NONE);
            java.util.Arrays.fill(notchWater, NONE);
            java.util.Arrays.fill(talusTop, NONE);
            java.util.Arrays.fill(curtainTop, NONE);
        }

        void touch(int lo, int hi) {
            any = true;
            minY = Math.min(minY, lo);
            maxY = Math.max(maxY, hi);
        }
    }

    /** Reach of the scan around a column (blocks). */
    private static final int M = 10, W = 16 + 2 * M;
    private static final int[] DX = { 1, -1, 0, 0, 1, 1, -1, -1 }, DZ = { 0, 0, 1, -1, 1, -1, 1, -1 };
    private static final int MIN_DROP3 = 9, MIN_WALL = 10;

    private final ColumnPlanCache plans;
    private final int seed;
    private final double strength;
    private final Cache<Long, Cols> cache = CacheBuilder.newBuilder()
        .maximumSize(2048)
        .concurrencyLevel(4)
        .build();
    private final ThreadLocal<int[][]> scratch = ThreadLocal.withInitial(() -> new int[3][W * W]);

    public CliffDetail(ColumnPlanCache plans, int seed, double strength) {
        this.plans = plans;
        this.seed = seed;
        this.strength = strength;
    }

    public Cols get(int cx, int cz) {
        long k = GenMath.mixKey(((long) cx << 32) | (cz & 0xffffffffL));
        Cols c = cache.getIfPresent(k);
        if (c != null) return c;
        c = compute(cx, cz);
        cache.put(k, c);
        return c;
    }

    /**
     * Falling water at waterfall lips: a column next to river water of a waterfall zone (F_FALL) standing 3+ blocks
     * above
     * it gets a curtain up to that level. This covers oblique lips (a cliff crossing the river at an angle). Only in
     * waterfall zones: elsewhere a river above a lower neighbour (a lake or the sea beside it) would raise a long
     * curtain along its bank.
     */
    private static void curtains(int[] S, int[] Wt, int[] F, Cols out) {
        final int river = ColumnPlan.F_FALL;
        for (int lz = 0; lz < 16; lz++) for (int lx = 0; lx < 16; lx++) {
            int c = (lz + M) * W + lx + M;
            int top = Wt[c] != NONE ? Math.max(Wt[c], S[c]) : S[c];
            int best = NONE;
            for (int d = 0; d < 4; d++) {
                int q = c + DZ[d] * W + DX[d];
                if (Wt[q] == NONE || (F[q] & river) == 0 || Wt[q] <= S[q]) continue;
                if (Wt[q] - top >= 3 && Wt[q] > best) best = Wt[q];
            }
            if (best == NONE) continue;
            int ci = ColumnPlan.index(lx, lz);
            out.curtainTop[ci] = best;
            out.touch(top + 1, best);
        }
    }

    private Cols compute(int cx, int cz) {
        Cols out = new Cols();
        if (strength <= 0) return out;
        int[][] t = scratch.get();
        int[] S = t[0], Wt = t[1], F = t[2];
        // surfaces, water and flags of the 36×36 blocks around (from the 3×3 plans)
        int bx0 = (cx << 4) - M, bz0 = (cz << 4) - M;
        ColumnPlan[] ps = new ColumnPlan[9];
        for (int dz = -1; dz <= 1; dz++)
            for (int dx = -1; dx <= 1; dx++) ps[(dz + 1) * 3 + dx + 1] = plans.get(cx + dx, cz + dz);
        int lowest = Integer.MAX_VALUE, highest = Integer.MIN_VALUE;
        for (int j = 0; j < W; j++) for (int i = 0; i < W; i++) {
            int bx = bx0 + i, bz = bz0 + j;
            ColumnPlan p = ps[((bz >> 4) - cz + 1) * 3 + (bx >> 4) - cx + 1];
            int pi = ColumnPlan.index(bx & 15, bz & 15);
            S[j * W + i] = p.surfaceY[pi];
            Wt[j * W + i] = p.waterY[pi];
            F[j * W + i] = p.flags[pi];
            lowest = Math.min(lowest, p.surfaceY[pi]);
            highest = Math.max(highest, p.surfaceY[pi]);
        }
        if (highest - lowest < 3) return out; // flat around: nothing to find
        final int skipF = ColumnPlan.F_RIVER | ColumnPlan.F_LAKE;
        curtains(S, Wt, F, out);
        if (highest - lowest < MIN_WALL) return out;
        // faces: any block within reach whose wall can touch this column
        for (int j = 3; j < W - 3; j++) for (int i = 3; i < W - 3; i++) {
            int f = j * W + i;
            if ((F[f] & skipF) != 0 || (Wt[f] != NONE && Wt[f] > S[f])) continue;
            int bestDir = -1, bestH = 0, bestFoot = 0, bestFootIdx = 0;
            for (int d = 0; d < 8; d++) {
                int m3 = Integer.MAX_VALUE;
                for (int k = 1; k <= 3; k++) m3 = Math.min(m3, S[f + k * (DZ[d] * W + DX[d])]);
                if (S[f] - m3 < MIN_DROP3) continue;
                // the real foot: follow the wall down while it still drops 2+ blocks per step. Every face of one wall
                // finds the same foot, so its notch and talus are at the bottom only (not on ledges halfway up)
                int footIdx = -1;
                for (int k = 1;; k++) {
                    int ii = i + k * DX[d], jj = j + k * DZ[d];
                    if (ii < 0 || jj < 0 || ii >= W || jj >= W) break; // still steep at the edge of the scan
                    int q = jj * W + ii, prev = q - (DZ[d] * W + DX[d]);
                    if (S[prev] - S[q] < 2) {
                        footIdx = q;
                        break;
                    }
                }
                if (footIdx < 0) continue;
                int foot = S[footIdx];
                int H = S[f] - foot;
                if (H > bestH) {
                    bestH = H;
                    bestDir = d;
                    bestFoot = foot;
                    bestFootIdx = footIdx;
                }
            }
            if (bestDir < 0 || bestH < MIN_WALL) continue;
            int H = bestH, d = bestDir, foot = bestFoot;
            int footWater = Wt[bestFootIdx] != NONE && Wt[bestFootIdx] > foot ? Wt[bestFootIdx] : NONE;
            int fx = bx0 + i, fz = bz0 + j;
            // notch, cut into the wall behind the face (some stretches of wall have none)
            if (Noise.rnd(fx >> 3, fz >> 3, seed) < 0.8) {
                boolean wet = footWater != NONE;
                int tal1 = wet ? 0 : (int) Math.min(6, Math.round(H * 0.25 * strength));
                int base = wet ? footWater - 1 : foot + tal1 + 1;
                int hN = (int) GenMath.clamp(Math.round(H * (wet ? 0.12 : 0.1) * strength), 2, wet ? 4 : 3);
                int D = (int) GenMath.clamp(Math.round(H / (wet ? 8.0 : 12.0) * strength), 1, wet ? 4 : 3);
                // some stretches go much deeper: sea caves at the water line, cave mouths at the foot of tall dry walls
                double rc = Noise.rnd(fx >> 3, fz >> 3, seed + 11);
                if (wet && rc < 0.25 && H >= 14) {
                    hN = (int) GenMath.clamp(Math.round(H * 0.2), 3, 6);
                    D = 9;
                } else if (!wet && rc < 0.06 && H >= 20) {
                    hN = (int) GenMath.clamp(Math.round(H * 0.3), 5, 10);
                    D = 8;
                }
                // the notch opens where the wall stands solid above it: the outermost column (towards the foot) still
                // at
                // least 3 blocks above the band; the column in front of it must leave the band open
                int band = base + hN - 1, e = -1, lips = 0;
                for (int s = 0; s <= 6; s++) {
                    int ii = i + s * DX[d], jj = j + s * DZ[d];
                    if (ii < 0 || jj < 0 || ii >= W || jj >= W) break;
                    if (S[jj * W + ii] >= band + 3) e = s;
                    else break;
                }
                int fi = i + (e + 1) * DX[d], fj = j + (e + 1) * DZ[d];
                boolean open = e >= 0 && fi >= 0 && fj >= 0 && fi < W && fj < W && S[fj * W + fi] < band;
                if (wet && e >= 0 && !open) {
                    // at the sea the waves cut through the low ledges in front of the wall too (roof of at least one
                    // block)
                    for (int s = e + 1; s <= e + 3; s++) {
                        int ii = i + s * DX[d], jj = j + s * DZ[d];
                        if (ii < 0 || jj < 0 || ii >= W || jj >= W) break;
                        int q = jj * W + ii;
                        if (S[q] <= base || (Wt[q] != NONE && Wt[q] > S[q])) {
                            open = true;
                            break;
                        }
                        lips++;
                    }
                    if (!open) lips = 0;
                }
                for (int s = -lips; open && s < D; s++) {
                    int ii = i + (e - s) * DX[d], jj = j + (e - s) * DZ[d];
                    int lx = ii - M, lz = jj - M;
                    if (lx < 0 || lz < 0 || lx > 15 || lz > 15) continue;
                    int c = jj * W + ii;
                    double u = Math.max(0, s) / (double) D;
                    int hj = (int) Math.max(1, Math.round(hN * (1 - u * u)));
                    // the cube filler lowers it further under loose soil
                    int top = s >= 0 ? Math.min(base + hj - 1, S[c] - 3)
                        : S[c] <= band + 1 ? S[c] // a low ledge at the water line: cut away entirely
                            : Math.min(band, S[c] - 1);
                    if (top < base || (F[c] & skipF) != 0) continue;
                    int ci = ColumnPlan.index(lx, lz);
                    if (out.notchHi[ci] != NONE && out.notchHi[ci] - out.notchLo[ci] >= top - base) continue;
                    out.notchLo[ci] = base;
                    out.notchHi[ci] = top;
                    out.notchWater[ci] = footWater;
                    out.touch(base, top);
                }
            }
            // talus (leaning on the lowest part of the wall too) and boulder reach, measured from the foot
            int T = (int) Math.min(8, Math.round(H * 0.3 * strength));
            int fI = bestFootIdx % W, fJ = bestFootIdx / W;
            for (int s = -1; s <= 10; s++) {
                int ii = fI + (s - 1) * DX[d], jj = fJ + (s - 1) * DZ[d];
                int lx = ii - M, lz = jj - M;
                if (ii < 0 || jj < 0 || ii >= W || jj >= W) break;
                if (lx < 0 || lz < 0 || lx > 15 || lz > 15) continue;
                int c = jj * W + ii, ci = ColumnPlan.index(lx, lz);
                if (s >= 1 && (out.wallDist[ci] == 0 || s < out.wallDist[ci])) {
                    out.wallDist[ci] = (byte) s;
                    out.wallH[ci] = (short) Math.min(H, Short.MAX_VALUE);
                }
                if (s < -1 || s > T || footWater != NONE || (F[c] & skipF) != 0 || (Wt[c] != NONE && Wt[c] > S[c]))
                    continue;
                int top = foot + (int) Math.round(Math.min(6, H * 0.25 * strength) - (Math.max(s, 1) - 1) * 0.9);
                if (top <= S[c] || top >= S[f]) continue;
                if (out.talusTop[ci] == NONE || top > out.talusTop[ci]) {
                    out.talusTop[ci] = top;
                    out.touch(S[c] + 1, top);
                }
            }
        }
        return out;
    }
}
