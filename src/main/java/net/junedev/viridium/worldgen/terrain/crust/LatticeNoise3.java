package net.junedev.viridium.worldgen.terrain.crust;

import static net.junedev.viridium.worldgen.terrain.core.GenMath.floor;

import net.junedev.viridium.worldgen.terrain.core.Noise;

/**
 * Smooth 3D value noise in [0, 1) on a lattice of spacing S. Can be evaluated directly ({@link #at}) or through a
 * cached block of lattice values for one cube ({@link Cached}), which turns 8 hashes per sample into 0.
 */
public final class LatticeNoise3 {

    private final int spacing, seed;

    public LatticeNoise3(int spacing, int seed) {
        this.spacing = spacing;
        this.seed = seed;
    }

    private double lattice(int i, int j, int k) {
        return Noise.rnd(Noise.hash(i, j, seed), k, seed + 1);
    }

    private static double fade(double t) {
        return t * t * (3 - 2 * t);
    }

    public double at(double x, double y, double z) {
        double fx = x / spacing, fy = y / spacing, fz = z / spacing;
        int i = floor(fx), j = floor(fy), k = floor(fz);
        double u = fade(fx - i), v = fade(fy - j), w = fade(fz - k);
        double c000 = lattice(i, j, k), c100 = lattice(i + 1, j, k), c010 = lattice(i, j + 1, k),
            c110 = lattice(i + 1, j + 1, k);
        double c001 = lattice(i, j, k + 1), c101 = lattice(i + 1, j, k + 1), c011 = lattice(i, j + 1, k + 1),
            c111 = lattice(i + 1, j + 1, k + 1);
        double a = c000 + (c100 - c000) * u, b = c010 + (c110 - c010) * u;
        double c = c001 + (c101 - c001) * u, d = c011 + (c111 - c011) * u;
        double e = a + (b - a) * v, f = c + (d - c) * v;
        return e + (f - e) * w;
    }

    /** Lattice values covering one 16³ cube (spacing must be ≥ 16). Reuse per thread. */
    public final class Cached {

        private int bx, by, bz, i0, j0, k0;
        private final double[] v = new double[27];

        public void prepare(int blockX, int blockY, int blockZ) {
            bx = blockX;
            by = blockY;
            bz = blockZ;
            i0 = floor((double) blockX / spacing);
            j0 = floor((double) blockY / spacing);
            k0 = floor((double) blockZ / spacing);
            for (int a = 0; a < 3; a++) for (int b = 0; b < 3; b++) for (int c = 0; c < 3; c++)
                v[(a * 3 + b) * 3 + c] = lattice(i0 + a, j0 + b, k0 + c);
        }

        /** Sample at local cube coordinates (0..15). */
        public double at(int lx, int ly, int lz) {
            double fx = (double) (bx + lx) / spacing - i0, fy = (double) (by + ly) / spacing - j0,
                fz = (double) (bz + lz) / spacing - k0;
            int i = (int) fx, j = (int) fy, k = (int) fz;
            double u = fade(fx - i), w1 = fade(fy - j), w = fade(fz - k);
            int o = (i * 3 + j) * 3 + k;
            double c000 = v[o], c001 = v[o + 1], c010 = v[o + 3], c011 = v[o + 4];
            double c100 = v[o + 9], c101 = v[o + 10], c110 = v[o + 12], c111 = v[o + 13];
            double a = c000 + (c100 - c000) * u, b = c010 + (c110 - c010) * u;
            double c = c001 + (c101 - c001) * u, d = c011 + (c111 - c011) * u;
            double e = a + (b - a) * w1, f = c + (d - c) * w1;
            return e + (f - e) * w;
        }
    }
}
