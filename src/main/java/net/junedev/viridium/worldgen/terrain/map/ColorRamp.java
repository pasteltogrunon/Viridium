package net.junedev.viridium.worldgen.terrain.map;

/** Piecewise-linear colour ramp (same idea as makeRamp in the web viewer's layers.js). */
public final class ColorRamp {

    private final double[] v;
    private final int[] rgb;

    /** stops: value, r, g, b, value, r, g, b... with increasing values. */
    public ColorRamp(double... stops) {
        int n = stops.length / 4;
        v = new double[n];
        rgb = new int[n];
        for (int i = 0; i < n; i++) {
            v[i] = stops[4 * i];
            rgb[i] = rgb((int) stops[4 * i + 1], (int) stops[4 * i + 2], (int) stops[4 * i + 3]);
        }
    }

    public int at(double x) {
        int n = v.length;
        if (x <= v[0]) return rgb[0];
        if (x >= v[n - 1]) return rgb[n - 1];
        for (int i = 1; i < n; i++) {
            if (x < v[i]) return lerp(rgb[i - 1], rgb[i], (x - v[i - 1]) / (v[i] - v[i - 1]));
        }
        return rgb[n - 1];
    }

    public double min() {
        return v[0];
    }

    public double max() {
        return v[v.length - 1];
    }

    public static int rgb(int r, int g, int b) {
        return (clamp8(r) << 16) | (clamp8(g) << 8) | clamp8(b);
    }

    private static int clamp8(int c) {
        return c < 0 ? 0 : Math.min(c, 255);
    }

    /** Linear mix of two RGB colours. */
    public static int lerp(int a, int b, double t) {
        int ar = a >> 16 & 255, ag = a >> 8 & 255, ab = a & 255;
        int br = b >> 16 & 255, bg = b >> 8 & 255, bb = b & 255;
        return rgb(
            (int) (ar + (br - ar) * t + 0.5),
            (int) (ag + (bg - ag) * t + 0.5),
            (int) (ab + (bb - ab) * t + 0.5));
    }

    public static int scale(int c, double f) {
        return rgb((int) ((c >> 16 & 255) * f), (int) ((c >> 8 & 255) * f), (int) ((c & 255) * f));
    }

    /** HSL → RGB, h in degrees, s and l in [0, 1]. */
    public static int hsl(double h, double s, double l) {
        double a = s * Math.min(l, 1 - l);
        return rgb((int) (hslF(0, h, a, l) * 255), (int) (hslF(8, h, a, l) * 255), (int) (hslF(4, h, a, l) * 255));
    }

    private static double hslF(int n, double h, double a, double l) {
        double k = (n + h / 30) % 12;
        return l - a * Math.max(-1, Math.min(Math.min(k - 3, 9 - k), 1));
    }
}
