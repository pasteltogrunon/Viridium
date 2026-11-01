package net.junedev.viridium.client.color;

/** Alpha-weighted statistics of sRGB texels, before lighting or ambient occlusion. */
public final class ColorMetrics {

    public final double red, green, blue, lightness, a, b, contrast;

    private ColorMetrics(double red, double green, double blue, double lightness, double a, double b, double contrast) {
        this.red = red;
        this.green = green;
        this.blue = blue;
        this.lightness = lightness;
        this.a = a;
        this.b = b;
        this.contrast = contrast;
    }

    public double chroma() {
        return Math.hypot(a, b);
    }

    public static ColorMetrics solid(int rgb) {
        Accumulator result = new Accumulator();
        result.add(0xFF000000 | rgb, 0xFFFFFF, 1);
        return result.finish();
    }

    public static double linear(double srgb) {
        return srgb <= 0.04045 ? srgb / 12.92 : Math.pow((srgb + 0.055) / 1.055, 2.4);
    }

    /** Matrices by Bjorn Ottosson, https://bottosson.github.io/posts/oklab/ (2021 revision). */
    public static double[] oklab(double red, double green, double blue) {
        double r = linear(red), g = linear(green), b = linear(blue);
        double l = Math.cbrt(0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b);
        double m = Math.cbrt(0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b);
        double s = Math.cbrt(0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b);
        return new double[] { 0.2104542553 * l + 0.7936177850 * m - 0.0040720468 * s,
            1.9779984951 * l - 2.4285922050 * m + 0.4505937099 * s,
            0.0259040371 * l + 0.7827717662 * m - 0.8086757660 * s };
    }

    public static final class Accumulator {

        private double weight, red, green, blue, lightness, a, b, m2;

        public void add(int argb, int tint, double area) {
            double w = (argb >>> 24) / 255.0 * area;
            if (w <= 0) return;
            double r = ((argb >> 16) & 255) / 255.0 * ((tint >> 16) & 255) / 255.0;
            double g = ((argb >> 8) & 255) / 255.0 * ((tint >> 8) & 255) / 255.0;
            double bl = (argb & 255) / 255.0 * (tint & 255) / 255.0;
            double[] lab = oklab(r, g, bl);
            double total = weight + w;
            double delta = lab[0] - lightness;
            lightness += delta * w / total;
            m2 += w * delta * (lab[0] - lightness);
            red += r * w;
            green += g * w;
            blue += bl * w;
            a += lab[1] * w;
            b += lab[2] * w;
            weight = total;
        }

        public void merge(Accumulator other) {
            if (other.weight == 0) return;
            double total = weight + other.weight;
            double delta = other.lightness - lightness;
            m2 += other.m2 + delta * delta * weight * other.weight / total;
            lightness += delta * other.weight / total;
            red += other.red;
            green += other.green;
            blue += other.blue;
            a += other.a;
            b += other.b;
            weight = total;
        }

        public ColorMetrics finish() {
            if (weight == 0) return null;
            return new ColorMetrics(
                red / weight,
                green / weight,
                blue / weight,
                lightness,
                a / weight,
                b / weight,
                Math.sqrt(Math.max(0, m2 / weight)));
        }
    }
}
