package net.junedev.viridium.worldgen.terrain.map;

import net.junedev.viridium.worldgen.terrain.WorldGen;
import net.junedev.viridium.worldgen.terrain.column.TerrainLayout;
import net.junedev.viridium.worldgen.terrain.crust.CrustPlan;
import net.junedev.viridium.worldgen.terrain.crust.Lithology;
import net.junedev.viridium.worldgen.terrain.pipeline.GenPoint;
import net.junedev.viridium.worldgen.terrain.pipeline.Stage;

/**
 * Vertical cross-section of the crust along a west-east line (constant z): lithology colours below the surface, sea
 * and sky above. Pure Java; thread-safe.
 */
public final class CrustSectionRenderer {

    private static final int SKY = 0x9cc4e8, WATER = 0x2a5ca8, SOIL = 0x6b4a2e;

    private final WorldGen gen;
    private final ThreadLocal<GenPoint> point = ThreadLocal.withInitial(GenPoint::new);
    private final ThreadLocal<CrustPlan> column = ThreadLocal.withInitial(CrustPlan::new);

    public CrustSectionRenderer(WorldGen gen) {
        this.gen = gen;
    }

    /**
     * @param x0     west end (block x)
     * @param x1     east end
     * @param z      line position
     * @param yTop   top of the image (absolute Y)
     * @param yBot   bottom of the image
     * @param width  image width (pixels)
     * @param height image height (pixels)
     * @return ARGB pixels, row 0 = yTop
     */
    public int[] render(double x0, double x1, double z, int yTop, int yBot, int width, int height) {
        int[] out = new int[width * height];
        GenPoint o = point.get();
        CrustPlan cp = column.get();
        double ypp = (double) (yTop - yBot) / height;
        for (int i = 0; i < width; i++) {
            int x = (int) Math.floor(x0 + (x1 - x0) * (i + 0.5) / width);
            int zi = (int) Math.floor(z);
            gen.evaluate(x, zi, Stage.GEOLOGY, o);
            int surface = TerrainLayout.surfaceY(o.h);
            gen.crust.planColumn(cp, 0, x, zi, o, surface);
            for (int j = 0; j < height; j++) {
                int y = (int) Math.floor(yTop - (j + 0.5) * ypp);
                int c;
                if (y > surface) c = y < TerrainLayout.SEA_LEVEL ? WATER : SKY;
                else if (y < TerrainLayout.MIN_Y) c = 0x202020;
                else if (surface - y < 4 && o.h >= 2 && o.h < 260) c = SOIL;
                else {
                    Lithology l = gen.crust.lithologyAt(cp, 0, x, y, zi);
                    c = l.color;
                }
                out[j * width + i] = 0xff000000 | c;
            }
            // mark the Moho and the basement top as thin lines (helps reading the section)
            markLine(out, width, height, i, cp.moho(0), yTop, ypp, 0xffff4040);
            markLine(out, width, height, i, cp.basementTop(0), yTop, ypp, 0xffffe060);
        }
        return out;
    }

    /** Lithology at a block, or null above the surface. */
    public Lithology lithologyAt(double x, int y, double z) {
        GenPoint o = point.get();
        CrustPlan cp = column.get();
        int xi = (int) Math.floor(x), zi = (int) Math.floor(z);
        gen.evaluate(xi, zi, Stage.GEOLOGY, o);
        int surface = TerrainLayout.surfaceY(o.h);
        if (y > surface || y < TerrainLayout.MIN_Y) return null;
        gen.crust.planColumn(cp, 0, xi, zi, o, surface);
        return gen.crust.lithologyAt(cp, 0, xi, y, zi);
    }

    private static void markLine(int[] out, int w, int h, int i, int y, int yTop, double ypp, int color) {
        int j = (int) ((yTop - y) / ypp);
        if (j >= 0 && j < h) out[j * w + i] = color;
    }
}
