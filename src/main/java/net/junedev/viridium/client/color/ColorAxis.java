package net.junedev.viridium.client.color;

import java.util.Locale;

/** Fixed scales keep positions comparable when filtering or switching resource packs. */
public enum ColorAxis {

    R("R", 0, 1),
    G("G", 0, 1),
    B("B", 0, 1),
    L("OKLab L", 0, 1),
    A("OKLab a", -0.4, 0.4),
    LAB_B("OKLab b", -0.4, 0.4),
    CHROMA("OKLab C", 0, 0.4),
    CONTRAST("sigma(L)", 0, 0.5);

    public final String label;
    public final double min, max;

    ColorAxis(String label, double min, double max) {
        this.label = label;
        this.min = min;
        this.max = max;
    }

    public double value(ColorMetrics color) {
        switch (this) {
            case R:
                return color.red;
            case G:
                return color.green;
            case B:
                return color.blue;
            case L:
                return color.lightness;
            case A:
                return color.a;
            case LAB_B:
                return color.b;
            case CHROMA:
                return color.chroma();
            case CONTRAST:
                return color.contrast;
            default:
                throw new AssertionError(this);
        }
    }

    public double position(ColorMetrics color) {
        return (value(color) - min) / (max - min);
    }

    public static ColorAxis parse(String name) {
        return valueOf(name.toUpperCase(Locale.ROOT));
    }
}
