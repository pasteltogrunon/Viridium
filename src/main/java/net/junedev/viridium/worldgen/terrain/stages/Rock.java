package net.junedev.viridium.worldgen.terrain.stages;

/**
 * Surface rock families used by the 2D geology stage. Order, erodibility and weights match {@code ROCKS} in
 * worldgen.js (the weighted pick depends on the order). The 3D crust maps these onto concrete Viridium rocks.
 */
public enum Rock {

    GRANITE(0.22, 3, 0xC8968C),
    DIORITE(0.28, 1.5, 0xD4D4CE),
    ANDESITE(0.35, 2, 0x8A8A91),
    BASALT(0.40, 1, 0x3A3A44),
    SLATE(0.45, 2, 0x586078),
    MARBLE(0.38, 0.8, 0xEEEAE2),
    LIMESTONE(0.60, 3, 0xDECE9E),
    SANDSTONE(0.75, 2.5, 0xD8A868),
    SHALE(0.90, 1.5, 0x967666),
    TUFF(0.70, 0.7, 0x96A276),
    GNEISS(0.20, 0, 0xAA78A0);

    public static final Rock[] VALUES = values();
    static final double[] CUM = new double[VALUES.length];
    static final double TOTAL;

    static {
        double t = 0;
        for (int i = 0; i < VALUES.length; i++) {
            t += VALUES[i].weight;
            CUM[i] = t;
        }
        TOTAL = t;
    }

    /** Erodibility in [0, 1]: higher erodes faster. */
    public final double erod;
    /** Relative frequency in the generic continental pick. */
    public final double weight;
    /** Map colour (RGB). */
    public final int color;

    Rock(double erod, double weight, int color) {
        this.erod = erod;
        this.weight = weight;
        this.color = color;
    }
}
