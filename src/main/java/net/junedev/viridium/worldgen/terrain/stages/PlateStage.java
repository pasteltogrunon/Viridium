package net.junedev.viridium.worldgen.terrain.stages;

import static net.junedev.viridium.worldgen.terrain.core.GenMath.TAU;
import static net.junedev.viridium.worldgen.terrain.core.GenMath.clamp;
import static net.junedev.viridium.worldgen.terrain.core.GenMath.floor;
import static net.junedev.viridium.worldgen.terrain.core.GenMath.ss;
import static net.junedev.viridium.worldgen.terrain.core.Noise.fbm;
import static net.junedev.viridium.worldgen.terrain.core.Noise.hash;
import static net.junedev.viridium.worldgen.terrain.core.Noise.rnd;

import net.junedev.viridium.worldgen.terrain.WorldGen;
import net.junedev.viridium.worldgen.terrain.pipeline.GenPoint;
import net.junedev.viridium.worldgen.terrain.pipeline.GenScratch;

/**
 * 3-4. Tectonic plates: Voronoi on a jittered grid (5x5 neighbourhood) in fBm-warped space. Each plate has a velocity;
 * boundaries are classified as divergent (ridge), subduction (trench + volcanic arc on the overriding plate) or
 * continental collision (suture range + plateau). Bands fade near the other boundaries so triple junctions stay
 * continuous.
 *
 * <p>
 * Everything that depends only on the plate cell (feature points, velocities, pair normals, convergence, collision,
 * override sign) is cached per thread in {@link GenScratch} for the last cell: consecutive points almost always share
 * it (cells are {@code plateSize} wide). The cached values are computed with the same expressions as before, so the
 * output is bit-identical.
 */
public class PlateStage extends GenStage {

    public PlateStage(WorldGen gen) {
        super(gen);
    }

    public double featX(int i, int j) {
        return (i + 0.15 + 0.7 * rnd(i, j, SD.plate)) * P.plateSize;
    }

    public double featY(int i, int j) {
        return (j + 0.15 + 0.7 * rnd(i, j, SD.plate + 1)) * P.plateSize;
    }

    /** Writes the velocity of plate (i, j) into {@code s.VX, s.VY}. */
    public void plateVel(int i, int j, GenScratch s) {
        double a = rnd(i, j, SD.vel) * TAU, sp = 0.25 + 0.75 * rnd(i, j, SD.vel + 1);
        s.VX = Math.cos(a) * sp;
        s.VY = Math.sin(a) * sp;
    }

    /** Symmetric random value for a pair of plates (same seen from both). */
    protected static double pairRnd(int i1, int j1, int i2, int j2, int s) {
        if (i1 > i2 || (i1 == i2 && j1 > j2)) {
            int t = i1;
            i1 = i2;
            i2 = t;
            t = j1;
            j1 = j2;
            j2 = t;
        }
        return rnd(hash(i1, j1, s), hash(i2, j2, s + 1), s + 2);
    }

    /** +1 if plate a overrides b (b subducts), -1 otherwise. Antisymmetric. */
    protected int overSign(int ai, int aj, int bi, int bj) {
        boolean aFirst = ai < bi || (ai == bi && aj < bj);
        return (aFirst == (pairRnd(ai, aj, bi, bj, SD.coll + 5) < 0.5)) ? 1 : -1;
    }

    protected boolean isCollision(double conv, int ai, int aj, int bi, int bj) {
        return conv > 0.12 && pairRnd(ai, aj, bi, bj, SD.coll) < P.collisionChance;
    }

    /** Loads the 5x5 neighbourhood of cell (ci, cj) into the scratch (kept if it is already there). */
    private void loadCell(int ci, int cj, GenScratch s) {
        if (s.plateOwner == this && s.plateCi == ci && s.plateCj == cj) return;
        s.plateOwner = this;
        s.plateCi = ci;
        s.plateCj = cj;
        if (++s.plateStamp == 0) { // wrapped: old stamps could match again
            java.util.Arrays.fill(s.PVOK, 0);
            java.util.Arrays.fill(s.PGOK, 0);
            java.util.Arrays.fill(s.PDOK, 0);
            java.util.Arrays.fill(s.PSOK, 0);
            s.plateStamp = 1;
        }
        int k = 0;
        for (int dj = -2; dj <= 2; dj++) {
            for (int di = -2; di <= 2; di++, k++) {
                int i = ci + di, j = cj + dj;
                s.FX[k] = featX(i, j);
                s.FY[k] = featY(i, j);
                s.FI[k] = i;
                s.FJ[k] = j;
            }
        }
    }

    /** Velocity of seed k of the current cell into s.PVX[k], s.PVY[k]. */
    private void vel(int k, GenScratch s) {
        if (s.PVOK[k] == s.plateStamp) return;
        plateVel(s.FI[k], s.FJ[k], s);
        s.PVX[k] = s.VX;
        s.PVY[k] = s.VY;
        s.PVOK[k] = s.plateStamp;
    }

    /** Geometry of the pair (a, b) of the current cell: unit normal a→b, distance, bisector midpoint. */
    private int pair(int a, int b, GenScratch s) {
        int q = a * 25 + b;
        if (s.PGOK[q] == s.plateStamp) return q;
        double ax = s.FX[a], ay = s.FY[a];
        double nx = s.FX[b] - ax, ny = s.FY[b] - ay;
        double len = Math.sqrt(nx * nx + ny * ny);
        s.PNX[q] = nx / len;
        s.PNY[q] = ny / len;
        s.PLEN[q] = len;
        s.PMX[q] = (ax + s.FX[b]) * 0.5;
        s.PMY[q] = (ay + s.FY[b]) * 0.5;
        s.PGOK[q] = s.plateStamp;
        return q;
    }

    /** Geometry plus convergence (>0 convergent), collision flag and override sign of the pair (a, b). */
    private int pairDyn(int a, int b, GenScratch s) {
        int q = pair(a, b, s);
        if (s.PDOK[q] == s.plateStamp) return q;
        vel(a, s);
        vel(b, s);
        double conv = (s.PVX[a] - s.PVX[b]) * s.PNX[q] + (s.PVY[a] - s.PVY[b]) * s.PNY[q];
        s.PCONV[q] = conv;
        s.PCOLL[q] = isCollision(conv, s.FI[a], s.FJ[a], s.FI[b], s.FJ[b]);
        s.POVER[q] = overSign(s.FI[a], s.FJ[a], s.FI[b], s.FJ[b]);
        s.PDOK[q] = s.plateStamp;
        return q;
    }

    /**
     * Distance from (wx, wy) in plate bk to the real Voronoi edge with plate k: the bisector clipped by the plate's other
     * half-planes (the bisector line alone runs on past a triple junction, where it is no boundary at all). Infinity if
     * the two plates do not touch. {@code q} is the pair index of (bk, k).
     */
    private double edgeSegmentDistance(int bk, int k, int q, double d, double wx, double wy, GenScratch s) {
        segment(bk, k, q, s);
        double tmin = s.PTMIN[q];
        if (tmin != tmin) return Double.POSITIVE_INFINITY; // NaN: no shared edge
        double nx = s.PNX[q], ny = s.PNY[q];
        // position along the bisector (u = (-ny, nx), from the midpoint) of the foot of the perpendicular
        double s0 = (wx + d * nx - s.PMX[q]) * -ny + (wy + d * ny - s.PMY[q]) * nx;
        double tc = clamp(0, tmin - s0, s.PTMAX[q] - s0);
        return Math.sqrt(d * d + tc * tc);
    }

    /**
     * The edge (bk, k) as an interval [PTMIN, PTMAX] along its bisector measured from the midpoint: the bisector clipped
     * by bk's half-planes with every other seed. Depends only on the cell, so it is computed once per pair.
     */
    private void segment(int bk, int k, int q, GenScratch s) {
        if (s.PSOK[q] == s.plateStamp) return;
        double nx = s.PNX[q], ny = s.PNY[q], px = s.PMX[q], py = s.PMY[q];
        double ux = -ny, uy = nx; // along the bisector
        double tmin = Double.NEGATIVE_INFINITY, tmax = Double.POSITIVE_INFINITY;
        boolean none = false;
        for (int m = 0; m < 25 && !none; m++) {
            if (m == bk || m == k) continue;
            int r = pair(bk, m, s);
            double mx = s.PNX[r], my = s.PNY[r];
            // bk's side of the bisector with m: (X - mid)·n <= 0
            double c0 = (px - s.PMX[r]) * mx + (py - s.PMY[r]) * my, cu = ux * mx + uy * my;
            if (Math.abs(cu) < 1e-12) {
                if (c0 > 0) none = true;
                continue;
            }
            double t = -c0 / cu;
            if (cu > 0) tmax = Math.min(tmax, t);
            else tmin = Math.max(tmin, t);
            if (tmin > tmax) none = true;
        }
        s.PTMIN[q] = none ? Double.NaN : tmin;
        s.PTMAX[q] = tmax;
        s.PSOK[q] = s.plateStamp;
    }

    /**
     * Soft minimum of the distances to the cell's sides (only the near ones matter): the hard minimum creases along
     * straight lines from every corner (the medial axis), and the coasts that follow it showed them.
     */
    private double softEdge(int bk, double edge, GenScratch s) {
        final double K = P.plateSize * 0.02;
        double sum = 0;
        for (int k = 0; k < 25; k++) if (k != bk && s.LD[k] < edge + 6 * K) sum += Math.exp(-(s.LD[k] - edge) / K);
        return Math.max(0, edge - K * Math.log(Math.max(1, sum)));
    }

    /**
     * Soft distance to the non-collisional boundaries, measured to their real segments: a bisector line alone runs on past
     * the triple junction where the boundary turns into a collision, and cut a straight ocean wedge there.
     * {@code open} has a bit per non-collisional neighbour.
     */
    private double softEdgeNC(int bk, int open, double wx, double wy, GenScratch s) {
        final double K = P.plateSize * 0.02, S = P.plateSize;
        final double[] LD = s.LD;
        double best = 2 * S;
        int n = 0;
        while (open != 0) { // nearest lines first, until the next line is beyond the best segment found (+ the soft margin)
            int pick = -1;
            double pd = 0;
            for (int bits = open; bits != 0; bits &= bits - 1) {
                int k = Integer.numberOfTrailingZeros(bits);
                if (pick < 0 || LD[k] < pd) {
                    pick = k;
                    pd = LD[k];
                }
            }
            if (pd > best + 6 * K) break;
            open &= ~(1 << pick);
            double dd = edgeSegmentDistance(bk, pick, pair(bk, pick, s), pd, wx, wy, s);
            s.SEGD[n++] = dd;
            if (dd < best) best = dd;
        }
        double sum = 0;
        for (int q = 0; q < n; q++) if (s.SEGD[q] < best + 6 * K) sum += Math.exp(-(s.SEGD[q] - best) / K);
        return Math.max(0, best - K * Math.log(Math.max(1, sum)));
    }

    public void apply(double x, double y, GenPoint o, GenScratch s) {
        final double[] FX = s.FX, FY = s.FY, PW = s.PW, LD = s.LD;
        final int[] FI = s.FI, FJ = s.FJ;
        double S = P.plateSize, wa = S * P.plateWarp, ws = S * 0.55;
        double qx = x / ws, qy = y / ws;
        double wx = x + wa * fbm(qx, qy, SD.warp, 3);
        double wy = y + wa * fbm(qx + 17.31, qy - 9.17, SD.warp + 7, 3);
        loadCell(floor(wx / S), floor(wy / S), s);
        double best = Double.POSITIVE_INFINITY;
        int bk = 0, k;
        for (k = 0; k < 25; k++) {
            double dx = FX[k] - wx, dy = FY[k] - wy, d = dx * dx + dy * dy;
            PW[k] = d;
            if (d < best) {
                best = d;
                bk = k;
            }
        }
        double ax = FX[bk], ay = FY[bk];
        int ai = FI[bk], aj = FJ[bk];
        double edge = Double.POSITIVE_INFINITY, edgeNC = 2 * S, ridge = 0, orog = 0, trench = 0, cw = 0, cs = 0, arc = 0;
        double collZ = 0, cwS = 0, cwK = 0, cwD = 0;
        int nk = bk, ncMask = 0; // ncMask: neighbours whose boundary is not a collision
        double wR = S * 0.045, wO = S * 0.16, tW = S * 0.025, aC = S * 0.08, aW = S * 0.022, Wz = S * 0.3,
            Wf = S * 0.08;
        // two smallest "power distances" (|pK|²-|pA|²)/2S: fade a boundary's bands near the plate's OTHER boundaries
        double p1 = Double.POSITIVE_INFINITY, p2 = Double.POSITIVE_INFINITY;
        int p1k = -1;
        for (k = 0; k < 25; k++) {
            if (k == bk) continue;
            double pd = (PW[k] - PW[bk]) / (2 * S);
            if (pd < p1) {
                p2 = p1;
                p1 = pd;
                p1k = k;
            } else if (pd < p2) p2 = pd;
        }
        for (k = 0; k < 25; k++) {
            if (k == bk) continue;
            int q = pair(bk, k, s);
            double nx = s.PNX[q], ny = s.PNY[q];
            // distance to the bisector (exact Voronoi edge in warped space)
            double d = (s.PMX[q] - wx) * nx + (s.PMY[q] - wy) * ny;
            if (d < edge) {
                edge = d;
                nk = k;
            }
            LD[k] = d;
            if (d >= S) {
                ncMask |= 1 << k;
                if (d < edgeNC) edgeNC = d;
                continue;
            }
            pairDyn(bk, k, s);
            double conv = s.PCONV[q]; // >0 convergent, <0 divergent
            boolean coll = s.PCOLL[q];
            if (!coll) {
                ncMask |= 1 << k;
                if (d < edgeNC) edgeNC = d;
            }
            if (d >= S * 0.6) continue;
            double cP = clamp(conv, 0, 1), dP = clamp(-conv, 0, 1);
            double sd = s.POVER[q] * d; // signed: + on the overriding plate
            double fk = ss(0, Wf, k == p1k ? p2 : p1); // fades near the other boundaries (triple junctions)
            double eR = Math.exp(-(d / wR) * (d / wR));
            double eO = Math.exp(-(d / wO) * (d / wO));
            orog += cP * eO * fk;
            cw += conv * eO;
            cs += eO;
            if (!coll) {
                double r = (0.35 + 0.65 * dP) * (1 - 0.6 * cP) * eR * fk;
                if (r > ridge) ridge = r;
                // ocean-ocean subduction: trench at the boundary, island arc on the overriding plate
                double tt = (sd + S * 0.012) / tW, tr = cP * Math.exp(-tt * tt) * fk;
                if (tr > trench) trench = tr;
                double at = (sd - aC) / aW, ar = ss(0.05, 0.35, conv) * Math.exp(-at * at) * fk;
                if (ar > arc) arc = ar;
            } else if (d < S * 0.45) {
                // continental collision. g fades near any NON-collisional boundary of either plate.
                double gnum = Double.POSITIVE_INFINITY;
                double pA = PW[bk], pB = PW[k];
                for (int m = 0; m < 25; m++) {
                    if (m == bk || m == k) continue;
                    int qa = pair(bk, m, s), qb = pair(k, m, s);
                    double den = s.PLEN[qa] + s.PLEN[qb];
                    double nA = (PW[m] - pA) / den, nB = (PW[m] - pB) / den + 2 * d;
                    if (nA < gnum && nA < S * 0.22 && !s.PCOLL[pairDyn(bk, m, s)]) gnum = nA;
                    if (nB < gnum && nB < S * 0.22 && !s.PCOLL[pairDyn(k, m, s)]) gnum = nB;
                }
                double g = ss(S * 0.02, S * 0.22, gnum);
                double z = (1 - ss(0, Wz, d)) * g * fk;
                if (z > collZ) collZ = z;
                // several nearby collisions: weighted blend (no jumps when the dominant neighbour changes)
                double zw = (1 - ss(0, S * 0.45, d)) * g * fk, w = zw * zw * zw * zw;
                cwS += w;
                cwK += w * g * ss(0.12, 0.45, conv) * (1 - ss(S * 0.25, S * 0.4, d));
                cwD += w * sd;
            }
        }
        o.pi = ai;
        o.pj = aj;
        o.fx = ax;
        o.fy = ay;
        o.wx = wx;
        o.wy = wy;
        if (P.exactPlateEdges) {
            edge = softEdge(bk, edge, s);
            edgeNC = softEdgeNC(bk, ncMask, wx, wy, s);
        }
        o.edge = edge;
        o.edgeNC = edgeNC;
        o.ni = FI[nk];
        o.nj = FJ[nk];
        o.ridge = ridge;
        o.orog = Math.min(orog, 1.2);
        o.trench = trench;
        o.arc = arc;
        o.collZ = collZ;
        o.collK = cwS > 1e-12 ? cwK / cwS : 0;
        o.collS = cwS > 1e-12 ? cwD / cwS : 0;
        o.conv = cs > 1e-4 ? cw / cs : 0;
    }
}
