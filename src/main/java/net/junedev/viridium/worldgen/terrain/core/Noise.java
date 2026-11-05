package net.junedev.viridium.worldgen.terrain.core;

import static net.junedev.viridium.worldgen.terrain.core.GenMath.TAU;
import static net.junedev.viridium.worldgen.terrain.core.GenMath.floor;
import static net.junedev.viridium.worldgen.terrain.core.GenMath.ss;

/**
 * Stateless hash and gradient noise. Bit-compatible with worldgen.js (same hash constants, same gradient table, same
 * octave rotation) so that the in-game world matches the web viewer up to floating point rounding.
 */
public final class Noise {

    private static final double[] GX = new double[16], GY = new double[16];

    static {
        for (int i = 0; i < 16; i++) {
            double a = (i + 0.5) * TAU / 16;
            GX[i] = Math.cos(a);
            GY[i] = Math.sin(a);
        }
    }

    private Noise() {}

    /** 32-bit hash. Returned as a Java int; interpret as unsigned where it matters (see {@link #rnd}). */
    public static int hash(int x, int y, int s) {
        int h = x * 0x27d4eb2d ^ y * 0x165667b1 ^ s * 0x9e3779b1;
        h = (h ^ (h >>> 15)) * 0x85ebca6b;
        h = (h ^ (h >>> 13)) * 0xc2b2ae35;
        h = (h ^ (h >>> 16)) * 0x27d4eb2d;
        return h ^ (h >>> 15);
    }

    /** Uniform value in [0, 1). */
    public static double rnd(int x, int y, int s) {
        return (hash(x, y, s) & 0xffffffffL) * 2.3283064365386963e-10;
    }

    /** 2D gradient noise, roughly in [-1, 1]. */
    public static double perlin(double x, double y, int s) {
        int xi = floor(x), yi = floor(y);
        double xf = x - xi, yf = y - yi;
        double u = xf * xf * xf * (xf * (xf * 6 - 15) + 10);
        double v = yf * yf * yf * (yf * (yf * 6 - 15) + 10);
        int h00 = hash(xi, yi, s) & 15, h10 = hash(xi + 1, yi, s) & 15;
        int h01 = hash(xi, yi + 1, s) & 15, h11 = hash(xi + 1, yi + 1, s) & 15;
        double n00 = GX[h00] * xf + GY[h00] * yf;
        double n10 = GX[h10] * (xf - 1) + GY[h10] * yf;
        double n01 = GX[h01] * xf + GY[h01] * (yf - 1);
        double n11 = GX[h11] * (xf - 1) + GY[h11] * (yf - 1);
        double a = n00 + u * (n10 - n00), b = n01 + u * (n11 - n01);
        return (a + v * (b - a)) * 1.414;
    }

    /** fBm with a rotation between octaves (matrix [[1.6,-1.2],[1.2,1.6]], scale 2). */
    public static double fbm(double x, double y, int s, int oct) {
        double sum = 0, amp = 1, norm = 0;
        for (int i = 0; i < oct; i++) {
            sum += amp * perlin(x, y, s + i * 1013);
            norm += amp;
            amp *= 0.5;
            double nx = x * 1.6 - y * 1.2;
            y = x * 1.2 + y * 1.6;
            x = nx;
        }
        return sum / norm;
    }

    /** Ridged multifractal in [0, 1]. */
    public static double ridged(double x, double y, int s, int oct) {
        double sum = 0, amp = 0.5, norm = 0, w = 1;
        for (int i = 0; i < oct; i++) {
            double pn = perlin(x, y, s + i * 1013);
            double n = 1 - Math.sqrt(pn * pn + 0.015); // smoothed |n|: avoids 1-pixel ridges
            n *= n;
            sum += n * amp * w;
            norm += amp;
            w = ss(0, 1, n * 1.6); // smooth weight between octaves
            amp *= 0.5;
            double nx = x * 1.6 - y * 1.2;
            y = x * 1.2 + y * 1.6;
            x = nx;
        }
        return sum / norm;
    }
}
