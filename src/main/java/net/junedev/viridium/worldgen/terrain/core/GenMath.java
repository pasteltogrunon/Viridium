package net.junedev.viridium.worldgen.terrain.core;

/** Small math helpers shared by every stage. Mirrors the helpers at the top of worldgen.js. */
public final class GenMath {

    public static final double TAU = Math.PI * 2;

    private GenMath() {}

    public static double clamp(double x, double a, double b) {
        return x < a ? a : x > b ? b : x;
    }

    public static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }

    /** Smoothstep from a to b (a may be greater than b for a falling edge). */
    public static double ss(double a, double b, double x) {
        double t = (x - a) / (b - a);
        if (t <= 0) return 0;
        if (t >= 1) return 1;
        return t * t * (3 - 2 * t);
    }

    /** ≈1 when v &lt; t. */
    public static double sigLo(double v, double t, double w) {
        return 1 / (1 + Math.exp((v - t) / w));
    }

    /** ≈1 when v &gt; t. */
    public static double sigHi(double v, double t, double w) {
        return 1 / (1 + Math.exp((t - v) / w));
    }

    public static double band(double v, double a, double b, double w) {
        return sigHi(v, a, w) * sigLo(v, b, w);
    }

    public static int floor(double x) {
        int i = (int) x;
        return x < i ? i - 1 : i;
    }

    public static double hypot(double x, double y) {
        return Math.sqrt(x * x + y * y);
    }
}
