package net.junedev.viridium.worldgen.terrain.stages;

import static net.junedev.viridium.worldgen.terrain.core.GenMath.TAU;
import static net.junedev.viridium.worldgen.terrain.core.GenMath.clamp;
import static net.junedev.viridium.worldgen.terrain.core.GenMath.floor;
import static net.junedev.viridium.worldgen.terrain.core.GenMath.hypot;
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

    /** Convergence between seeds ka and kb of the current neighbourhood. */
    private double convOf(int ka, int kb, GenScratch s) {
        double nx = s.FX[kb] - s.FX[ka], ny = s.FY[kb] - s.FY[ka];
        double l = Math.sqrt(nx * nx + ny * ny);
        if (l == 0) l = 1;
        nx /= l;
        ny /= l;
        plateVel(s.FI[ka], s.FJ[ka], s);
        double ax = s.VX, ay = s.VY;
        plateVel(s.FI[kb], s.FJ[kb], s);
        return (ax - s.VX) * nx + (ay - s.VY) * ny;
    }

    public void apply(double x, double y, GenPoint o, GenScratch s) {
        final double[] FX = s.FX, FY = s.FY, PW = s.PW;
        final int[] FI = s.FI, FJ = s.FJ;
        double S = P.plateSize, wa = S * P.plateWarp, ws = S * 0.55;
        double qx = x / ws, qy = y / ws;
        double wx = x + wa * fbm(qx, qy, SD.warp, 3);
        double wy = y + wa * fbm(qx + 17.31, qy - 9.17, SD.warp + 7, 3);
        int ci = floor(wx / S), cj = floor(wy / S);
        double best = Double.POSITIVE_INFINITY;
        int bk = 0, k = 0;
        for (int dj = -2; dj <= 2; dj++) {
            for (int di = -2; di <= 2; di++, k++) {
                int i = ci + di, j = cj + dj;
                double fx = featX(i, j), fy = featY(i, j);
                FX[k] = fx;
                FY[k] = fy;
                FI[k] = i;
                FJ[k] = j;
                double dx = fx - wx, dy = fy - wy, d = dx * dx + dy * dy;
                PW[k] = d;
                if (d < best) {
                    best = d;
                    bk = k;
                }
            }
        }
        double ax = FX[bk], ay = FY[bk];
        int ai = FI[bk], aj = FJ[bk];
        plateVel(ai, aj, s);
        double avx = s.VX, avy = s.VY;
        double edge = Double.POSITIVE_INFINITY, edgeNC = 2 * S, ridge = 0, orog = 0, trench = 0, cw = 0, cs = 0, arc = 0;
        double collZ = 0, cwS = 0, cwK = 0, cwD = 0;
        int nk = bk;
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
            double nx = FX[k] - ax, ny = FY[k] - ay;
            double len = Math.sqrt(nx * nx + ny * ny);
            nx /= len;
            ny /= len;
            // distance to the bisector (exact Voronoi edge in warped space)
            double d = ((ax + FX[k]) * 0.5 - wx) * nx + ((ay + FY[k]) * 0.5 - wy) * ny;
            if (d < edge) {
                edge = d;
                nk = k;
            }
            if (d >= S) {
                if (d < edgeNC) edgeNC = d;
                continue;
            }
            plateVel(FI[k], FJ[k], s);
            double conv = (avx - s.VX) * nx + (avy - s.VY) * ny; // >0 convergent, <0 divergent
            boolean coll = isCollision(conv, ai, aj, FI[k], FJ[k]);
            if (!coll && d < edgeNC) edgeNC = d;
            if (d >= S * 0.6) continue;
            double cP = clamp(conv, 0, 1), dP = clamp(-conv, 0, 1);
            double sd = overSign(ai, aj, FI[k], FJ[k]) * d; // signed: + on the overriding plate
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
                    double den = hypot(FX[m] - ax, FY[m] - ay) + hypot(FX[m] - FX[k], FY[m] - FY[k]);
                    double nA = (PW[m] - pA) / den, nB = (PW[m] - pB) / den + 2 * d;
                    if (nA < gnum && nA < S * 0.22 && !isCollision(convOf(bk, m, s), ai, aj, FI[m], FJ[m])) gnum = nA;
                    if (nB < gnum && nB < S * 0.22 && !isCollision(convOf(k, m, s), FI[k], FJ[k], FI[m], FJ[m]))
                        gnum = nB;
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
