package net.junedev.viridium.worldgen.terrain.map;

import static net.junedev.viridium.worldgen.terrain.core.GenMath.clamp;
import static net.junedev.viridium.worldgen.terrain.core.GenMath.ss;
import static net.junedev.viridium.worldgen.terrain.map.ColorRamp.hsl;
import static net.junedev.viridium.worldgen.terrain.map.ColorRamp.lerp;
import static net.junedev.viridium.worldgen.terrain.core.GenMath.lerp;
import static net.junedev.viridium.worldgen.terrain.map.ColorRamp.rgb;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import net.junedev.viridium.worldgen.terrain.column.BiomeMapper;
import net.junedev.viridium.worldgen.terrain.core.Noise;
import net.junedev.viridium.worldgen.terrain.pipeline.GenPoint;
import net.junedev.viridium.worldgen.terrain.pipeline.Stage;
import net.junedev.viridium.worldgen.terrain.stages.Rock;

/**
 * One way of painting the pipeline on the map. Layers are registered in {@link #ALL}; a new stage only needs to add
 * its own layer here to show up in the in-game map.
 */
public final class MapLayer {

    /** Paints one evaluated point. bpp = blocks per pixel. */
    @FunctionalInterface
    public interface Colorizer {

        int color(GenPoint o, double bpp);
    }

    /** Which height drives hill shading. */
    public enum HeightField {
        BASE,
        FINAL,
        /** Visible surface: lake level, ice, icebergs. */
        SURFACE
    }

    public final String id;
    public final String name;
    public final Stage stage;
    public final boolean shade;
    public final HeightField heightField;
    public final Colorizer colorizer;

    public MapLayer(String id, String name, Stage stage, boolean shade, HeightField heightField, Colorizer colorizer) {
        this.id = id;
        this.name = name;
        this.stage = stage;
        this.shade = shade;
        this.heightField = heightField;
        this.colorizer = colorizer;
    }

    // ------------------------------------------------------------------ ramps (layers.js)

    public static final ColorRamp R_TEMP = new ColorRamp(
        -30, 40, 0, 110, -15, 60, 90, 210, -3, 160, 210, 250, 3, 236, 244, 248,
        12, 250, 226, 130, 22, 245, 150, 60, 32, 200, 40, 30, 42, 110, 0, 20);
    public static final ColorRamp R_PREC = new ColorRamp(
        Math.log10(50), 140, 80, 40, Math.log10(200), 215, 175, 90, Math.log10(500), 230, 225, 120,
        Math.log10(1000), 120, 190, 90, Math.log10(2000), 40, 135, 140, Math.log10(3500), 30, 50, 150);
    public static final ColorRamp R_HEIGHT = new ColorRamp(
        -75, 8, 20, 60, -45, 20, 55, 120, -18, 40, 105, 175, -0.01, 110, 180, 215, 0, 72, 140, 76,
        25, 116, 166, 86, 70, 186, 180, 112, 130, 150, 110, 80, 190, 190, 186, 180, 260, 255, 255, 255,
        400, 214, 222, 240, 560, 170, 160, 210);
    public static final ColorRamp R_CONT = new ColorRamp(
        -1, 10, 30, 90, -0.3, 50, 110, 180, -0.001, 160, 205, 230, 0, 205, 195, 125, 0.5, 160, 110, 60, 1, 90, 50, 30);
    public static final ColorRamp R_ERODI = new ColorRamp(
        0, 68, 1, 84, 0.25, 59, 82, 139, 0.5, 33, 145, 140, 0.75, 94, 201, 98, 1, 253, 231, 37);

    static double plateHue(GenPoint o) {
        return Noise.rnd(o.pi, o.pj, 777) * 360;
    }

    /** Colours of the vanilla biome ids used by {@link BiomeMapper#VANILLA} (softened vanilla palette). */
    public static int biomeColor(int id) {
        return switch (id) {
            case 0 -> rgb(32, 92, 192); // ocean
            case 24 -> rgb(20, 56, 140); // deep ocean
            case 10 -> rgb(150, 172, 222); // frozen ocean
            case 1 -> rgb(140, 190, 88); // plains
            case 2 -> rgb(236, 212, 142); // desert
            case 3 -> rgb(128, 122, 118); // extreme hills
            case 4 -> rgb(58, 138, 50); // forest
            case 5 -> rgb(48, 98, 70); // taiga
            case 6 -> rgb(82, 110, 70); // swamp
            case 7 -> rgb(64, 112, 232); // river
            case 12 -> rgb(232, 244, 255); // ice plains
            case 13 -> rgb(252, 252, 255); // ice mountains
            case 16 -> rgb(240, 226, 160); // beach
            case 21 -> rgb(22, 122, 22); // jungle
            case 25 -> rgb(140, 140, 134); // stone beach
            case 26 -> rgb(245, 246, 242); // cold beach
            case 29 -> rgb(30, 100, 52); // roofed forest
            case 30 -> rgb(122, 152, 140); // cold taiga
            case 35 -> rgb(190, 180, 90); // savanna
            default -> rgb(255, 0, 255);
        };
    }

    // ------------------------------------------------------------------ registry

    private static final List<MapLayer> LAYERS = new ArrayList<>();
    public static final List<MapLayer> ALL = Collections.unmodifiableList(LAYERS);

    public static MapLayer register(MapLayer layer) {
        LAYERS.add(layer);
        return layer;
    }

    public static MapLayer byId(String id) {
        for (MapLayer l : LAYERS) if (l.id.equals(id)) return l;
        return LAYERS.get(0);
    }

    public static final ColorRamp R_WIND = new ColorRamp(
        0, 34, 46, 70, 0.4, 46, 104, 140, 0.8, 70, 160, 150, 1.1, 210, 200, 110, 1.5, 230, 120, 70);
    public static final ColorRamp R_ERO = new ColorRamp(-50, 80, 30, 15, -20, 170, 100, 50, -6, 225, 190, 140, 0, 245, 242, 232);
    public static final ColorRamp R_SST = new ColorRamp(
        -7, 30, 70, 160, -3, 110, 160, 215, 0, 225, 225, 220, 3, 230, 150, 110, 6, 180, 50, 40);
    public static final ColorRamp R_SHADOW = new ColorRamp(-1, 40, 110, 200, 0, 240, 240, 235, 1, 190, 90, 40);

    /** Rivers stay visible even when narrower than a pixel. */
    static double riverAlpha(GenPoint o, double bpp) {
        if (o.riverD > 1e8) return 0;
        double vis = Math.max(o.riverHW, 0.6 * bpp);
        if (o.riverD + o.riverHW > vis) return 0;
        return clamp(o.riverHW / (bpp * 0.5) + 0.35, 0, 1);
    }

    public static final MapLayer COMPOSITE = register(
        new MapLayer("composite", "Biomes + relief", Stage.BIOMES, true, HeightField.SURFACE, (o, bpp) -> {
            int c = o.color;
            if (o.lake == 1 && o.seaIce == 0) c = ColorRamp.scale(c, clamp(1 - (o.lakeZ - o.h) / 50, 0.5, 1));
            else if (o.h < 0 && o.river == 0 && o.berg == 0 && o.seaIce != 2)
                c = ColorRamp.scale(c, lerp(clamp(1 + o.h / 110, 0.35, 1), 1, o.iceFrac));
            double a = riverAlpha(o, bpp);
            return a > 0 ? lerp(c, rgb(50, 105, 225), a) : c;
        }));

    public static final MapLayer BIOMES = register(
        new MapLayer("biomes", "Biomes", Stage.BIOMES, false, HeightField.FINAL, (o, bpp) -> {
            double a = riverAlpha(o, bpp);
            return a > 0 ? lerp(o.color, rgb(64, 112, 232), a) : o.color;
        }));

    public static final MapLayer HEIGHT = register(
        new MapLayer("height", "Final height", Stage.RIVERS, true, HeightField.FINAL, (o, bpp) -> R_HEIGHT.at(o.h)));

    public static final MapLayer HEIGHT_BASE = register(
        new MapLayer("heightBase", "Base height", Stage.RELIEF, true, HeightField.BASE, (o, bpp) -> R_HEIGHT.at(o.h0)));

    public static final MapLayer RIVERS = register(
        new MapLayer("rivers", "Rivers, lakes, deltas", Stage.RIVERS, true, HeightField.FINAL, (o, bpp) -> {
            if (o.h < 0 && o.river == 0) return rgb(36, 62, 110);
            int gv = (int) (120 + clamp(o.h, 0, 250) * 0.45);
            int c = rgb(gv, gv, gv - 6);
            if (o.delta > 0) c = lerp(c, rgb(120, 190, 90), o.delta * 0.8);
            if (o.estu > 0) c = lerp(c, rgb(90, 170, 150), o.estu * 0.85);
            if (o.lake == 1) c = rgb(50, 100, 210);
            if (o.lake == 2) c = rgb(240, 236, 225);
            double a = riverAlpha(o, bpp);
            return a > 0 ? lerp(c, rgb(40, 110, 235), a) : c;
        }));

    public static final MapLayer VALLEYS = register(
        new MapLayer("valleys", "Valleys and glaciation", Stage.RIVERS, true, HeightField.FINAL, (o, bpp) -> {
            int gv = (int) (95 + clamp(o.h, 0, 300) * 0.4);
            int c = rgb(gv, gv, gv - 4);
            if (o.h < 0) return o.glac > 0.2 ? rgb(40, 190, 210) : rgb(30, 48, 80);
            if (o.valley > 0 || o.river != 0) {
                double a = o.river != 0 ? 0.9 : o.valley * 0.75;
                if (o.glac > 0.3) c = lerp(c, rgb(70, 120, 230), a);
                else c = lerp(c, rgb((int) lerp(110.0, 235.0, o.gorge), (int) lerp(190.0, 120.0, o.gorge),
                    (int) lerp(90.0, 40.0, o.gorge)), a);
            }
            if (o.lake == 1) c = rgb(50, 100, 210);
            if (o.lake == 2) c = rgb(240, 236, 225);
            return c;
        }));

    public static final MapLayer TEMPERATURE_FINAL = register(
        new MapLayer("temp", "Temperature", Stage.CLIMATE_FINAL, false, HeightField.BASE, (o, bpp) -> R_TEMP.at(o.T)));

    public static final MapLayer PRECIPITATION_FINAL = register(
        new MapLayer("precip", "Precipitation", Stage.CLIMATE_FINAL, false, HeightField.BASE,
            (o, bpp) -> R_PREC.at(Math.log10(o.P))));

    public static final MapLayer WIND = register(
        new MapLayer("wind", "Wind speed", Stage.CLIMATE_FINAL, true, HeightField.BASE, (o, bpp) -> {
            int c = R_WIND.at(Math.sqrt(o.wfx * o.wfx + o.wfy * o.wfy));
            return o.h0 < 0 ? lerp(c, rgb(14, 22, 40), 0.35) : c;
        }));

    public static final MapLayer CURRENTS = register(
        new MapLayer("currents", "Ocean currents", Stage.CLIMATE_FINAL, false, HeightField.BASE,
            (o, bpp) -> o.h0 < 0 ? lerp(R_SST.at(o.sst), rgb(20, 30, 50), 0.15)
                : lerp(R_PREC.at(Math.log10(o.P)), rgb(245, 240, 230), 0.25)));

    public static final MapLayer RAIN_SHADOW = register(
        new MapLayer("rainShadow", "Rain shadow", Stage.CLIMATE_FINAL, true, HeightField.BASE,
            (o, bpp) -> o.h0 < 0 ? rgb(30, 40, 60) : R_SHADOW.at(o.shadow - o.oro)));

    public static final MapLayer EROSION = register(
        new MapLayer("erosion", "Erosion", Stage.EROSION, true, HeightField.FINAL,
            (o, bpp) -> o.h0 < 0 ? rgb(40, 60, 95) : R_ERO.at(o.eroDelta)));

    public static final MapLayer CRYO = register(
        new MapLayer("cryo", "Ice and permafrost", Stage.CRYOSPHERE, true, HeightField.FINAL, (o, bpp) -> {
            if (o.lake == 1) return o.seaIce == 3 ? rgb(200, 220, 240) : rgb(50, 100, 210);
            if (o.h < 0 && o.river == 0) {
                int c = lerp(rgb(22, 40, 72), rgb(205, 222, 240), o.iceFrac);
                if (o.seaIce == 2) c = rgb(250, 252, 255);
                if (o.berg > 0) c = rgb(120, 220, 250);
                return c;
            }
            if (o.T < -14) return rgb(245, 248, 255);
            int c = rgb(150, 140, 120);
            c = lerp(c, rgb(125, 70, 170), ss(0, 0.5, o.perma) * 0.55);
            c = lerp(c, rgb(80, 30, 140), ss(0.5, 1, o.perma) * 0.8);
            return o.river != 0 ? rgb(60, 100, 200) : c;
        }));

    public static final MapLayer TEMPERATURE = register(
        new MapLayer("tempBase", "Base temperature", Stage.CLIMATE, false, HeightField.BASE,
            (o, bpp) -> R_TEMP.at(o.Tb)));

    public static final MapLayer PRECIPITATION = register(
        new MapLayer("precipBase", "Base precipitation", Stage.CLIMATE, false, HeightField.BASE,
            (o, bpp) -> R_PREC.at(Math.log10(o.Pb))));

    public static final MapLayer PLATES = register(
        new MapLayer("plates", "Tectonic plates", Stage.CONTINENTS, false, HeightField.BASE, (o, bpp) -> {
            if (o.edge < bpp * 0.9) return rgb(15, 15, 15);
            return hsl(plateHue(o), 0.5, o.L >= 0 ? 0.62 : 0.32);
        }));

    public static final MapLayer BOUNDARIES = register(
        new MapLayer("boundaries", "Boundaries and ridges", Stage.CONTINENTS, false, HeightField.BASE, (o, bpp) -> {
            int c = o.L >= 0 ? rgb(172, 168, 152) : rgb(40, 50, 70);
            c = lerp(c, rgb(230, 60, 40), clamp(o.orog, 0, 1) * 0.85);
            c = lerp(c, rgb(140, 50, 170), o.trench * 0.8);
            c = lerp(c, rgb(90, 230, 255), o.ridge * 0.95);
            c = lerp(c, rgb(255, 170, 30), o.arc * 0.9);
            return lerp(c, rgb(120, 10, 20), o.collZ * 0.9);
        }));

    public static final MapLayer CONTINENTALITY = register(
        new MapLayer("continentality", "Continentality", Stage.CONTINENTS, false, HeightField.BASE,
            (o, bpp) -> R_CONT.at(clamp(2 * o.L, -1, 1))));

    public static final MapLayer GEOLOGY = register(
        new MapLayer("geology", "Surface rock", Stage.GEOLOGY, true, HeightField.BASE, (o, bpp) -> {
            int c = lerp(Rock.VALUES[o.rock2].color, Rock.VALUES[o.rock].color, o.rockT);
            return o.h0 < 0 ? lerp(c, rgb(20, 40, 80), 0.45) : c;
        }));

    public static final MapLayer ERODIBILITY = register(
        new MapLayer("erodibility", "Erodibility", Stage.GEOLOGY, true, HeightField.BASE,
            (o, bpp) -> R_ERODI.at(o.erod)));
}
