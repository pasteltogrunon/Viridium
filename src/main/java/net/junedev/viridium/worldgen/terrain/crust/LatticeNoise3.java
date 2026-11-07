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

    /**
     * The noise over one 16³ cube, precomputed for per-block sampling. Reuse per thread.
     *
     * <p>
     * The spacing must be a multiple of 16: a cube then lies inside a single lattice cell, so only its 8 corners
     * matter. {@link #prepare} does the x and y interpolation of every (lx, ly) pair once (256 pairs) and keeps the 16
     * z weights, so a sample is one lerp instead of three fades and seven lerps. The operations and their order are
     * those of {@link LatticeNoise3#at} (x, then y, then z), so the values are bit-identical.
     */
    public final class Cached {

        /** Values interpolated on the two z faces of the cell, per {@code lx * 16 + ly}, and the z weights per lz. */
        private final double[] e = new double[256], f = new double[256], wz = new double[16];

        public Cached() {
            if (spacing % 16 != 0)
                throw new IllegalStateException("cube cache needs a spacing multiple of 16: " + spacing);
        }

        public void prepare(int blockX, int blockY, int blockZ) {
            int i0 = floor((double) blockX / spacing), j0 = floor((double) blockY / spacing),
                k0 = floor((double) blockZ / spacing);
            double c000 = lattice(i0, j0, k0), c100 = lattice(i0 + 1, j0, k0), c010 = lattice(i0, j0 + 1, k0),
                c110 = lattice(i0 + 1, j0 + 1, k0);
            double c001 = lattice(i0, j0, k0 + 1), c101 = lattice(i0 + 1, j0, k0 + 1),
                c011 = lattice(i0, j0 + 1, k0 + 1), c111 = lattice(i0 + 1, j0 + 1, k0 + 1);
            for (int lx = 0; lx < 16; lx++) {
                double fx = (double) (blockX + lx) / spacing - i0;
                double u = fade(fx - (int) fx);
                double a = c000 + (c100 - c000) * u, b = c010 + (c110 - c010) * u;
                double c = c001 + (c101 - c001) * u, d = c011 + (c111 - c011) * u;
                for (int ly = 0; ly < 16; ly++) {
                    double fy = (double) (blockY + ly) / spacing - j0;
                    double w1 = fade(fy - (int) fy);
                    e[lx * 16 + ly] = a + (b - a) * w1;
                    f[lx * 16 + ly] = c + (d - c) * w1;
                }
            }
            for (int lz = 0; lz < 16; lz++) {
                double fz = (double) (blockZ + lz) / spacing - k0;
                wz[lz] = fade(fz - (int) fz);
            }
        }

        /** Sample at local cube coordinates (0..15). */
        public double at(int lx, int ly, int lz) {
            int k = lx * 16 + ly;
            double ev = e[k];
            return ev + (f[k] - ev) * wz[lz];
        }
    }
}
