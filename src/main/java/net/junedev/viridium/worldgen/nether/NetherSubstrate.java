package net.junedev.viridium.worldgen.nether;

import static net.junedev.viridium.worldgen.terrain.core.GenMath.clamp;
import static net.junedev.viridium.worldgen.terrain.core.GenMath.floor;
import static net.junedev.viridium.worldgen.terrain.core.GenMath.ss;

import net.junedev.viridium.worldgen.terrain.core.Noise;

/**
 * Deep substrate (design §6): the fictional tectonics of space, older than the cores. Every field here varies at
 * scales larger than a domain, independently of the others, so a material can cross several domains and a fracture
 * can run between distant cores. It explains why some things appear together; it does not divide the world into
 * closed provinces.
 *
 * <ul>
 * <li><b>Fracturing</b>: the edges of a warped Voronoi diagram of {@value #FRACTURE_CELL}-block cells. Each edge is
 * one long fracture with its own identity; the major ones open into abysses along parts of their length.</li>
 * <li><b>Thermal potential</b>: where activity and lava are possible (hotter with depth and along fractures).</li>
 * <li><b>Spatial deformation</b>: a preferred orientation (folds, massifs) and a broad tilt of the tiers.</li>
 * <li><b>Material</b>: which {@link SubstrateKind} responds to the transformations.</li>
 * <li><b>Age</b>: formation, stability and abandonment (old regions tend to hold extinct systems).</li>
 * </ul>
 */
public final class NetherSubstrate {

    static final double FRACTURE_CELL = 1150;

    private final int sFrac, sWarp, sTherm, sDef, sTilt, sMat, sAge, sOpen;

    NetherSubstrate(int seed) {
        int k = 0;
        sFrac = salt(seed, k++);
        sWarp = salt(seed, k++);
        sTherm = salt(seed, k++);
        sDef = salt(seed, k++);
        sTilt = salt(seed, k++);
        sMat = salt(seed, k++);
        sAge = salt(seed, k++);
        sOpen = salt(seed, k);
    }

    static int salt(int seed, int k) {
        return Noise.hash(seed, k * 7919 + 13, 0x4e7e) & 0x3fffffff;
    }

    // ------------------------------------------------------------------ fractures

    /** The nearest fracture to a point (mutable result, reuse per thread). */
    public static final class Fracture {

        /** Distance to the fracture line (blocks, approximate). */
        public double dist;
        /** Unit direction along the fracture. */
        public double dirX, dirZ;
        /** Identity of the fracture (same along all its length). */
        public long id;
        /** Half width of the abyss opened here (0 = closed fracture). */
        public double abyss;
        /** Projection of the point along the fracture (blocks). */
        public double along;
    }

    private double pointX(int i, int j) {
        return (i + 0.12 + 0.76 * Noise.rnd(i, j, sFrac)) * FRACTURE_CELL;
    }

    private double pointZ(int i, int j) {
        return (j + 0.12 + 0.76 * Noise.rnd(i, j, sFrac + 1)) * FRACTURE_CELL;
    }

    public void fracture(double x, double z, Fracture f) {
        // warping bends the long straight Voronoi edges into curved belts without breaking their continuity
        double wx = x + 170 * Noise.fbm(x / 760, z / 760, sWarp, 2);
        double wz = z + 170 * Noise.fbm(x / 760 + 31.7, z / 760 - 12.1, sWarp, 2);
        int ci = floor(wx / FRACTURE_CELL), cj = floor(wz / FRACTURE_CELL);
        int ai = ci, aj = cj;
        double best = Double.MAX_VALUE, ax = 0, az = 0;
        for (int di = -1; di <= 1; di++) for (int dj = -1; dj <= 1; dj++) {
            double px = pointX(ci + di, cj + dj), pz = pointZ(ci + di, cj + dj);
            double d = (px - wx) * (px - wx) + (pz - wz) * (pz - wz);
            if (d < best) {
                best = d;
                ai = ci + di;
                aj = cj + dj;
                ax = px;
                az = pz;
            }
        }
        double ed = Double.MAX_VALUE, nx = 1, nz = 0, mx = 0, mz = 0;
        int bi = ai, bj = aj;
        for (int di = -2; di <= 2; di++) for (int dj = -2; dj <= 2; dj++) {
            if (di == 0 && dj == 0) continue;
            double bx = pointX(ai + di, aj + dj), bz = pointZ(ai + di, aj + dj);
            double ex = bx - ax, ez = bz - az, len = Math.sqrt(ex * ex + ez * ez);
            double cx = (ax + bx) * 0.5, cz = (az + bz) * 0.5;
            double d = -((wx - cx) * ex + (wz - cz) * ez) / len; // >= 0 on a's side
            if (d < ed) {
                ed = d;
                nx = ex / len;
                nz = ez / len;
                bi = ai + di;
                bj = aj + dj;
                mx = cx;
                mz = cz;
            }
        }
        f.dist = Math.max(0, ed);
        f.dirX = -nz;
        f.dirZ = nx;
        long ka = ((long) ai << 32) ^ (aj & 0xffffffffL), kb = ((long) bi << 32) ^ (bj & 0xffffffffL);
        long lo = Math.min(ka, kb), hi = Math.max(ka, kb);
        f.id = lo * 0x9E3779B97F4A7C15L ^ hi;
        // keep the direction sign stable along the fracture (both sides see the same orientation)
        if (ka > kb) {
            f.dirX = -f.dirX;
            f.dirZ = -f.dirZ;
        }
        f.along = (wx - mx) * f.dirX + (wz - mz) * f.dirZ;
        int idh = (int) (f.id ^ (f.id >>> 32));
        // only some fractures are major; along a major one, the abyss opens in long stretches and tapers at the ends
        double major = Noise.rnd(idh, 7, sFrac + 2);
        if (major < 0.42) {
            double o = Noise.perlin(f.along / 260, (idh & 1023) * 0.37, sFrac + 3) * 0.9 + 0.15;
            double w = ss(0.12, 0.5, o);
            f.abyss = w < 0.05 ? 0 : 4 + 15 * w;
        } else f.abyss = 0;
    }

    // ------------------------------------------------------------------ scalar fields

    /** Thermal potential in [0, 1] for tier t (hotter deeper and close to fractures). */
    public double thermal(double x, double z, int t, double fractureDist) {
        double v = 0.5 + 0.38 * Noise.fbm(x / 1400, z / 1400, sTherm, 3)
            + 0.12 * (1 - t)
            + 0.15 * ss(110, 0, fractureDist);
        return clamp(v, 0, 1);
    }

    /** Preferred orientation (radians) of the deformation. */
    public double deformAngle(double x, double z) {
        return Math.PI * 1.6 * Noise.fbm(x / 1700, z / 1700, sDef, 2);
    }

    /** Broad vertical offset of the whole stack of tiers (blocks), plus the own warp of tier t. */
    public double tilt(double x, double z, int t) {
        return 22 * Noise.fbm(x / 1150, z / 1150, sTilt, 3) + 8 * Noise.fbm(x / 520, z / 520, sTilt + 31 * (t + 1), 2);
    }

    /** Material of the substrate (crosses domains; slightly hotter kinds at depth). */
    public SubstrateKind material(double x, double z, int t) {
        double m1 = Noise.fbm(x / 1300, z / 1300, sMat, 2) + (t == 0 ? 0.18 : t == 2 ? -0.08 : 0);
        double m2 = Noise.fbm(x / 1100 + 77.3, z / 1100 - 41.9, sMat + 1, 2) + (t == 2 ? 0.12 : 0);
        if (m1 > 0.16) return SubstrateKind.PYRIC;
        if (m2 > 0.22) return SubstrateKind.CRYSTAL;
        if (m1 < -0.18 && m2 < 0.02) return SubstrateKind.SOUL;
        return SubstrateKind.INFERNAL;
    }

    /** Age in [0, 1] (1 = ancient: systems tend to be extinct). */
    public double age(double x, double z) {
        return clamp(0.5 + 0.55 * Noise.fbm(x / 1800, z / 1800, sAge, 2), 0, 1);
    }

    /** Seed for the openness (rock/void proportion) fields of the architecture. */
    int openSeed() {
        return sOpen;
    }
}
