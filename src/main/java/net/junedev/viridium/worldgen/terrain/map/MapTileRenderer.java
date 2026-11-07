package net.junedev.viridium.worldgen.terrain.map;

import static net.junedev.viridium.worldgen.terrain.core.GenMath.clamp;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.LinkedHashMap;
import java.util.Map;

import net.junedev.viridium.worldgen.terrain.WorldGen;
import net.junedev.viridium.worldgen.terrain.core.GenParams;
import net.junedev.viridium.worldgen.terrain.fields.FastErosionStage;
import net.junedev.viridium.worldgen.terrain.pipeline.GenPoint;
import net.junedev.viridium.worldgen.terrain.pipeline.GenScratch;
import net.junedev.viridium.worldgen.terrain.pipeline.Stage;

/**
 * Renders square map tiles straight from the pipeline (no chunks needed), like renderTile in the web viewer. Pure Java
 * and thread-safe: call from worker threads.
 *
 * <p>
 * Tile (tx, tz) at level L covers blocks [tx·size·2^L, (tx+1)·size·2^L) on each axis; one pixel = 2^L blocks.
 * <p>
 * A tile is evaluated once ({@link TileSample}) and every layer of it is painted from that evaluation: layers that need
 * the rivers evaluate up to the biomes (the later stages only add fields), cheaper layers only up to their own stage.
 * The last {@link #SAMPLES_KEPT} samples are kept, shared by every renderer of the same generator parameters.
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
    /** Tile samples kept (~3.4 MB each for a 128² tile evaluated up to the biomes). */
    private static final int SAMPLES_KEPT = 32;

    private record SampleKey(String params, int level, int tx, int tz, int size, Stage stage) {}

    private static final Map<SampleKey, TileSample> SAMPLES = new LinkedHashMap<>(64, 0.75f, true) {

        @Override
        protected boolean removeEldestEntry(Map.Entry<SampleKey, TileSample> e) {
            return size() > SAMPLES_KEPT;
        }
    };

    private final WorldGen gen;
    /** Identifies the generator: the world is a pure function of its parameters (seed included). */
    private final String params;
    private final ThreadLocal<GenPoint> point = ThreadLocal.withInitial(GenPoint::new);

    public MapTileRenderer(WorldGen gen) {
        this.gen = gen;
        this.params = paramsKey(gen.params);
    }

    public WorldGen gen() {
        return gen;
    }

    public static double blocksPerPixel(int level) {
        return Math.scalb(1.0, level);
    }

    /** Every public parameter as text: equal strings, equal worlds (also names the on-disk tile cache). */
    public static String paramsKey(GenParams p) {
        StringBuilder sb = new StringBuilder();
        for (Field f : GenParams.class.getFields()) {
            if (Modifier.isStatic(f.getModifiers())) continue;
            try {
                sb.append(f.getName())
                    .append('=')
                    .append(f.get(p))
                    .append(';');
            } catch (IllegalAccessException ignored) {}
        }
        return sb.toString();
    }

    /** Forgets every kept tile sample (benchmarks; the next render evaluates again). */
    public static void clearSamples() {
        synchronized (SAMPLES) {
            SAMPLES.clear();
        }
    }

    /**
     * @param borders overlay plate boundaries
     * @return ARGB pixels, row-major (z rows)
     */
    public int[] render(MapLayer layer, int level, int tx, int tz, int size, boolean borders) {
        double bpp = blocksPerPixel(level);
        Stage need = layer.stage;
        if (borders && need.ordinal() < Stage.PLATES.ordinal()) need = Stage.PLATES;
        boolean shade = layer.shade;
        if (shade && need.ordinal() < Stage.RELIEF.ordinal()) need = Stage.RELIEF;
        boolean usesH = shade && layer.heightField == MapLayer.HeightField.FINAL
            || layer.stage.ordinal() >= Stage.EROSION.ordinal();
        TileSample smp = sample(level, tx, tz, size, need, usesH);
        return paint(smp, layer, bpp, size, borders);
    }

    /** A kept sample that serves the layer, or a new evaluation (kept). */
    private TileSample sample(int level, int tx, int tz, int size, Stage need, boolean usesH) {
        synchronized (SAMPLES) {
            for (int s = need.ordinal(); s < Stage.values().length; s++) {
                TileSample t = SAMPLES.get(new SampleKey(params, level, tx, tz, size, Stage.values()[s]));
                if (t != null && t.serves(need, usesH)) return t;
            }
        }
        // the river layers share one evaluation up to the biomes (cryosphere and biomes only add fields)
        Stage stage = need.ordinal() >= Stage.RIVERS.ordinal() ? Stage.BIOMES : need;
        TileSample t = evaluate(level, tx, tz, size, stage);
        synchronized (SAMPLES) {
            SAMPLES.put(new SampleKey(params, level, tx, tz, size, stage), t);
        }
        return t;
    }

    /** The pipeline up to {@code stage} at the tile's (size + 1)² pixel centres (the extra row and column shade). */
    private TileSample evaluate(int level, int tx, int tz, int size, Stage stage) {
        double bpp = blocksPerPixel(level);
        int n = size + 1;
        TileSample smp = new TileSample(stage, n * n);
        GenPoint o = point.get();
        GenScratch s = gen.scratch();
        s.riversOff = level > RIVERS_MAX_LEVEL;
        try {
            reliefLattice(s, stage, bpp, tx, tz, size);
            for (int j = 0; j < n; j++) {
                for (int i = 0; i < n; i++) {
                    double x = (tx * (double) size + i + 0.5) * bpp, z = (tz * (double) size + j + 0.5) * bpp;
                    gen.evaluate(x, z, stage, bpp, o);
                    smp.put(o, j * n + i);
                }
            }
        } finally {
            s.riversOff = false;
            s.reliefLattice = null;
        }
        return smp;
    }

    private int[] paint(TileSample smp, MapLayer layer, double bpp, int size, boolean borders) {
        int n = size + 1;
        int[] out = new int[size * size];
        GenPoint o = point.get();
        for (int j = 0; j < size; j++) {
            for (int i = 0; i < size; i++) {
                smp.get(j * n + i, o);
                int c = layer.colorizer.color(o, bpp);
                if (borders && o.edge < bpp * 0.8) c = ColorRamp.lerp(c, ColorRamp.rgb(250, 50, 250), 0.8);
                out[j * size + i] = c;
            }
        }
        if (layer.shade) {
            MapLayer.HeightField hf = layer.heightField;
            for (int j = 0; j < size; j++) {
                for (int i = 0; i < size; i++) {
                    double h = smp.height(hf, j * n + i), hr = smp.height(hf, j * n + i + 1),
                        hd = smp.height(hf, (j + 1) * n + i);
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

    /**
     * Zoomed out (bpp > 8) the erosion stage would take its relief gradient from 4 coarse height evaluations per pixel
     * (~60 % of a tile). Instead the tile gets one coarse height per pixel centre, with a one-pixel margin around the
     * (size + 1)² evaluated points, and the gradient is a central difference over one pixel. Map only: the game never
     * sets a lattice.
     */
    private void reliefLattice(GenScratch s, Stage stage, double bpp, int tx, int tz, int size) {
        if (bpp <= 8 || stage.ordinal() < Stage.EROSION.ordinal() || !(gen.erosion instanceof FastErosionStage)) return;
        int n = size + 3;
        float[] l = new float[n * n];
        double x0 = (tx * (double) size - 1 + 0.5) * bpp, z0 = (tz * (double) size - 1 + 0.5) * bpp;
        for (int j = 0; j < n; j++)
            for (int i = 0; i < n; i++) l[j * n + i] = (float) gen.heightAt(x0 + i * bpp, z0 + j * bpp, false);
        s.latticeX0 = x0;
        s.latticeZ0 = z0;
        s.latticeStep = bpp;
        s.latticeN = n;
        s.reliefLattice = l;
    }
}
