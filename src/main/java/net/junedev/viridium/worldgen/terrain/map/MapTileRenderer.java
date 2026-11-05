package net.junedev.viridium.worldgen.terrain.map;

import static net.junedev.viridium.worldgen.terrain.core.GenMath.clamp;

import net.junedev.viridium.worldgen.terrain.WorldGen;
import net.junedev.viridium.worldgen.terrain.pipeline.GenPoint;
import net.junedev.viridium.worldgen.terrain.pipeline.Stage;

/**
 * Renders square map tiles straight from the pipeline (no chunks needed), like renderTile in the web viewer. Pure Java
 * and thread-safe: call from worker threads.
 *
 * <p>
 * Tile (tx, tz) at level L covers blocks [tx·size·2^L, (tx+1)·size·2^L) on each axis; one pixel = 2^L blocks.
 */
public final class MapTileRenderer {

    /** Light direction (from north-west, above) for hill shading. */
    private static final double LX = -0.5, LZ = -0.6, LY = 0.62;
    private static final double EXAGGERATION = 2.0;
    /**
     * Coarsest level that draws rivers: further out a screen spans more basins than the river network cache holds
     * (seconds per tile, and it would evict the networks the server needs).
     */
    public static final int RIVERS_MAX_LEVEL = 4;

    private final WorldGen gen;
    private final ThreadLocal<GenPoint> point = ThreadLocal.withInitial(GenPoint::new);

    public MapTileRenderer(WorldGen gen) {
        this.gen = gen;
    }

    public WorldGen gen() {
        return gen;
    }

    public static double blocksPerPixel(int level) {
        return Math.scalb(1.0, level);
    }

    /**
     * @param borders overlay plate boundaries
     * @return ARGB pixels, row-major (z rows)
     */
    public int[] render(MapLayer layer, int level, int tx, int tz, int size, boolean borders) {
        double bpp = blocksPerPixel(level);
        Stage stage = layer.stage;
        if (borders && stage.ordinal() < Stage.PLATES.ordinal()) stage = Stage.PLATES;
        boolean shade = layer.shade;
        if (shade && stage.ordinal() < Stage.RELIEF.ordinal()) stage = Stage.RELIEF;
        int n = size + 1;
        float[] hb = shade ? new float[n * n] : null;
        int[] out = new int[size * size];
        GenPoint o = point.get();
        net.junedev.viridium.worldgen.terrain.pipeline.GenScratch s = gen.scratch();
        s.riversOff = level > RIVERS_MAX_LEVEL;
        try {
            for (int j = 0; j < n; j++) {
                for (int i = 0; i < n; i++) {
                    boolean inside = i < size && j < size;
                    if (!inside && !shade) continue;
                    double x = (tx * (double) size + i + 0.5) * bpp, z = (tz * (double) size + j + 0.5) * bpp;
                    gen.evaluate(x, z, stage, bpp, o);
                    if (shade) hb[j * n + i] = (float) switch (layer.heightField) {
                        case BASE -> o.h0;
                        case FINAL -> o.h;
                        case SURFACE -> o.hs;
                    };
                    if (!inside) continue;
                    int c = layer.colorizer.color(o, bpp);
                    if (borders && o.edge < bpp * 0.8) c = ColorRamp.lerp(c, ColorRamp.rgb(250, 50, 250), 0.8);
                    out[j * size + i] = c;
                }
            }
        } finally {
            s.riversOff = false;
        }
        if (shade) {
            for (int j = 0; j < size; j++) {
                for (int i = 0; i < size; i++) {
                    double h = hb[j * n + i], hr = hb[j * n + i + 1], hd = hb[(j + 1) * n + i];
                    double dx = (hr - h) / bpp * EXAGGERATION, dz = (hd - h) / bpp * EXAGGERATION;
                    double nl = Math.sqrt(dx * dx + dz * dz + 1);
                    double dot = (-dx * LX - dz * LZ + LY) / nl;
                    double f = clamp(dot / LY, 0.25, 1.5);
                    if (h < 0) f = 1 + (f - 1) * 0.6;
                    out[j * size + i] = ColorRamp.scale(out[j * size + i], f);
                }
            }
        }
        for (int k = 0; k < out.length; k++) out[k] |= 0xff000000;
        return out;
    }
}
