package net.junedev.viridium.worldgen.terrain.caves;

import net.junedev.viridium.worldgen.terrain.core.Noise;

/**
 * A wobbly closed outline: r(θ) = 1 + Σ a_k cos(kθ − φ_k) over a few low harmonics, random per feature. Evaluated from a
 * unit direction (cos θ, sin θ) by complex powers: no trigonometry per call.
 */
final class Lobes {

    /** Per harmonic k = 1..n: a_k cos φ_k and a_k sin φ_k. */
    private final double[] p, q;
    /** Σ |a_k|: r stays within 1 ± amp. */
    final double amp;

    /**
     * @param max largest amplitude of harmonic k = 1..max.length (0: none); the amplitude and the phase are drawn from
     *            {@code Noise.rnd(k, salt, seed)}
     */
    Lobes(double[] max, int seed, int salt) {
        p = new double[max.length];
        q = new double[max.length];
        double s = 0;
        for (int k = 0; k < max.length; k++) {
            double a = max[k] * Noise.rnd(k, salt, seed), ph = Noise.rnd(k, salt + 1, seed) * Math.PI * 2;
            p[k] = a * Math.cos(ph);
            q[k] = a * Math.sin(ph);
            s += a;
        }
        amp = s;
    }

    /** r(θ) for the unit direction (c, s) = (cos θ, sin θ). */
    double at(double c, double s) {
        double r = 1, ck = c, sk = s;
        for (int k = 0; k < p.length; k++) {
            r += p[k] * ck + q[k] * sk;
            double n = ck * c - sk * s;
            sk = sk * c + ck * s;
            ck = n;
        }
        return r;
    }
}
