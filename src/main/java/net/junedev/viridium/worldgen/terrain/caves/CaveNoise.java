package net.junedev.viridium.worldgen.terrain.caves;

/** 3D gradient noise for caves (classic improved Perlin with hashed gradients, range about [-1, 1]). */
public final class CaveNoise {

    private CaveNoise() {}

    private static int hash(int x, int y, int z, int seed) {
        int h = seed ^ x * 0x27d4eb2d ^ y * 0x165667b1 ^ z * 0x9e3779b1;
        h ^= h >>> 15;
        h *= 0x2c1b3c6d;
        h ^= h >>> 12;
        h *= 0x297a2d39;
        h ^= h >>> 15;
        return h;
    }

    private static double grad(int h, double x, double y, double z) {
        switch (h & 15) {
            case 0: return x + y;
            case 1: return -x + y;
            case 2: return x - y;
            case 3: return -x - y;
            case 4: return x + z;
            case 5: return -x + z;
            case 6: return x - z;
            case 7: return -x - z;
            case 8: return y + z;
            case 9: return -y + z;
            case 10: return y - z;
            case 11: return -y - z;
            case 12: return x + y;
            case 13: return -y + z;
            case 14: return -x + y;
            default: return -y - z;
        }
    }

    private static double fade(double t) {
        return t * t * t * (t * (t * 6 - 15) + 10);
    }

    public static double perlin3(double x, double y, double z, int seed) {
        int xi = (int) Math.floor(x), yi = (int) Math.floor(y), zi = (int) Math.floor(z);
        double xf = x - xi, yf = y - yi, zf = z - zi;
        double u = fade(xf), v = fade(yf), w = fade(zf);
        double x1 = lerp(grad(hash(xi, yi, zi, seed), xf, yf, zf), grad(hash(xi + 1, yi, zi, seed), xf - 1, yf, zf), u);
        double x2 = lerp(grad(hash(xi, yi + 1, zi, seed), xf, yf - 1, zf), grad(hash(xi + 1, yi + 1, zi, seed), xf - 1, yf - 1, zf), u);
        double x3 = lerp(grad(hash(xi, yi, zi + 1, seed), xf, yf, zf - 1), grad(hash(xi + 1, yi, zi + 1, seed), xf - 1, yf, zf - 1), u);
        double x4 = lerp(grad(hash(xi, yi + 1, zi + 1, seed), xf, yf - 1, zf - 1),
            grad(hash(xi + 1, yi + 1, zi + 1, seed), xf - 1, yf - 1, zf - 1), u);
        return lerp(lerp(x1, x2, v), lerp(x3, x4, v), w);
    }

    /** Fractal sum, normalised to about [-1, 1]. */
    public static double fbm3(double x, double y, double z, int seed, int oct) {
        double s = 0, a = 1, n = 0;
        for (int o = 0; o < oct; o++) {
            s += a * perlin3(x, y, z, seed + o * 131);
            n += a;
            a *= 0.5;
            x *= 2.03;
            y *= 2.03;
            z *= 2.03;
        }
        return s / n;
    }

    private static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }
}
