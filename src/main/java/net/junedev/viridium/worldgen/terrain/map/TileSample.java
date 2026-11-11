package net.junedev.viridium.worldgen.terrain.map;

import net.junedev.viridium.worldgen.terrain.pipeline.GenPoint;
import net.junedev.viridium.worldgen.terrain.pipeline.Stage;

/**
 * The pipeline evaluated once over a map tile ((size + 1)² points: the tile and the row and column shading needs), kept
 * as the fields the layer colorizers and the hill shading read, so every layer of the tile is painted from one
 * evaluation. Only the fields produced up to {@link #stage} are stored (as floats: ~3.4 MB for a BIOMES sample of a
 * 128² tile). Immutable once filled; shared between threads.
 */
final class TileSample {

    // float fields, grouped by the stage that produces them (each group extends the previous one)
    static final int TB = 0, PB = 1, T = 2, P = 3, WFX = 4, WFY = 5; // CLIMATE
    static final int EDGE = 6, OROG = 7, TRENCH = 8, RIDGE = 9, ARC = 10, COLLZ = 11; // PLATES
    static final int L = 12; // CONTINENTS
    static final int H0 = 13, H = 14, PLAT = 15, RIFT = 16, RIFTD = 17, VOLC = 18, CLIFF = 19; // RELIEF
    static final int ROCKT = 20, EROD = 21; // GEOLOGY
    static final int SST = 22, SHADOW = 23, ORO = 24; // CLIMATE_FINAL
    static final int ERO = 25, MESA = 26; // EROSION
    static final int RIVERD = 27, RIVERHW = 28, VALLEY = 29, DELTA = 30, ESTU = 31, LAKEZ = 32, GLAC = 33, GORGE = 34,
        DOLINE = 35, TERRACE = 36; // RIVERS
    static final int BERG = 37, PERMA = 38, ICEFRAC = 39, HS = 40; // CRYOSPHERE
    // int fields
    static final int PI = 0, PJ = 1; // PLATES
    static final int PLATTYPE = 2, RIFTTYPE = 3, VOLCTYPE = 4; // RELIEF
    static final int ROCK = 5, ROCK2 = 6; // GEOLOGY
    static final int RIVER = 7, LAKE = 8, SURF = 9; // RIVERS (the landform cover, set from the relief on)
    static final int SEAICE = 10; // CRYOSPHERE
    static final int COLOR = 11; // BIOMES

    /** Float and int fields available after each stage (index = Stage ordinal). */
    private static final int[] FLOATS = new int[Stage.values().length], INTS = new int[Stage.values().length];

    static {
        set(Stage.CLIMATE, 6, 0);
        set(Stage.PLATES, 12, 2);
        set(Stage.CONTINENTS, 13, 2);
        set(Stage.RELIEF, 20, 5);
        set(Stage.GEOLOGY, 22, 7);
        set(Stage.CLIMATE_FINAL, 25, 7);
        set(Stage.EROSION, 27, 7);
        set(Stage.RIVERS, 37, 10);
        set(Stage.CRYOSPHERE, 41, 11);
        set(Stage.BIOMES, 41, 12);
    }

    private static void set(Stage s, int floats, int ints) {
        FLOATS[s.ordinal()] = floats;
        INTS[s.ordinal()] = ints;
    }

    final Stage stage;
    final int points;
    /** False when the rivers skipped networks not built yet (cached-only evaluation): never kept. */
    boolean complete = true;
    final float[][] f;
    final int[][] i;

    TileSample(Stage stage, int points) {
        this.stage = stage;
        this.points = points;
        f = new float[FLOATS[stage.ordinal()]][points];
        i = new int[INTS[stage.ordinal()]][points];
    }

    long bytes() {
        return 4L * points * (f.length + i.length);
    }

    /** Stores point k from an evaluation up to {@link #stage}. */
    void put(GenPoint o, int k) {
        int s = stage.ordinal();
        f[TB][k] = (float) o.Tb;
        f[PB][k] = (float) o.Pb;
        f[T][k] = (float) o.T;
        f[P][k] = (float) o.P;
        f[WFX][k] = (float) o.wfx;
        f[WFY][k] = (float) o.wfy;
        if (s < Stage.PLATES.ordinal()) return;
        f[EDGE][k] = (float) o.edge;
        f[OROG][k] = (float) o.orog;
        f[TRENCH][k] = (float) o.trench;
        f[RIDGE][k] = (float) o.ridge;
        f[ARC][k] = (float) o.arc;
        f[COLLZ][k] = (float) o.collZ;
        i[PI][k] = o.pi;
        i[PJ][k] = o.pj;
        if (s < Stage.CONTINENTS.ordinal()) return;
        f[L][k] = (float) o.L;
        if (s < Stage.RELIEF.ordinal()) return;
        f[H0][k] = (float) o.h0;
        f[H][k] = (float) o.h;
        f[PLAT][k] = (float) o.plat;
        f[RIFT][k] = (float) o.rift;
        f[RIFTD][k] = (float) o.riftD;
        f[VOLC][k] = (float) o.volc;
        f[CLIFF][k] = (float) o.cliff;
        i[PLATTYPE][k] = o.platType;
        i[RIFTTYPE][k] = o.riftType;
        i[VOLCTYPE][k] = o.volcType;
        if (s < Stage.GEOLOGY.ordinal()) return;
        f[ROCKT][k] = (float) o.rockT;
        f[EROD][k] = (float) o.erod;
        i[ROCK][k] = o.rock;
        i[ROCK2][k] = o.rock2;
        if (s < Stage.CLIMATE_FINAL.ordinal()) return;
        f[SST][k] = (float) o.sst;
        f[SHADOW][k] = (float) o.shadow;
        f[ORO][k] = (float) o.oro;
        if (s < Stage.EROSION.ordinal()) return;
        f[ERO][k] = (float) o.eroDelta;
        f[MESA][k] = (float) o.mesa;
        if (s < Stage.RIVERS.ordinal()) return;
        f[RIVERD][k] = (float) o.riverD;
        f[RIVERHW][k] = (float) o.riverHW;
        f[VALLEY][k] = (float) o.valley;
        f[DELTA][k] = (float) o.delta;
        f[ESTU][k] = (float) o.estu;
        f[LAKEZ][k] = (float) o.lakeZ;
        f[GLAC][k] = (float) o.glac;
        f[GORGE][k] = (float) o.gorge;
        f[DOLINE][k] = (float) o.doline;
        f[TERRACE][k] = (float) o.terrace;
        i[SURF][k] = o.surf;
        i[RIVER][k] = o.river;
        i[LAKE][k] = o.lake;
        if (s < Stage.CRYOSPHERE.ordinal()) return;
        f[BERG][k] = (float) o.berg;
        f[PERMA][k] = (float) o.perma;
        f[ICEFRAC][k] = (float) o.iceFrac;
        f[HS][k] = (float) o.hs;
        i[SEAICE][k] = o.seaIce;
        if (s < Stage.BIOMES.ordinal()) return;
        i[COLOR][k] = o.color;
    }

    /** Restores point k into {@code o} (fields of later stages are left as they are). */
    void get(int k, GenPoint o) {
        int s = stage.ordinal();
        o.Tb = f[TB][k];
        o.Pb = f[PB][k];
        o.T = f[T][k];
        o.P = f[P][k];
        o.wfx = f[WFX][k];
        o.wfy = f[WFY][k];
        if (s < Stage.PLATES.ordinal()) return;
        o.edge = f[EDGE][k];
        o.orog = f[OROG][k];
        o.trench = f[TRENCH][k];
        o.ridge = f[RIDGE][k];
        o.arc = f[ARC][k];
        o.collZ = f[COLLZ][k];
        o.pi = i[PI][k];
        o.pj = i[PJ][k];
        if (s < Stage.CONTINENTS.ordinal()) return;
        o.L = f[L][k];
        if (s < Stage.RELIEF.ordinal()) return;
        o.h0 = f[H0][k];
        o.h = f[H][k];
        o.plat = f[PLAT][k];
        o.rift = f[RIFT][k];
        o.riftD = f[RIFTD][k];
        o.volc = f[VOLC][k];
        o.cliff = f[CLIFF][k];
        o.platType = i[PLATTYPE][k];
        o.riftType = i[RIFTTYPE][k];
        o.volcType = i[VOLCTYPE][k];
        if (s < Stage.GEOLOGY.ordinal()) return;
        o.rockT = f[ROCKT][k];
        o.erod = f[EROD][k];
        o.rock = i[ROCK][k];
        o.rock2 = i[ROCK2][k];
        if (s < Stage.CLIMATE_FINAL.ordinal()) return;
        o.sst = f[SST][k];
        o.shadow = f[SHADOW][k];
        o.oro = f[ORO][k];
        if (s < Stage.EROSION.ordinal()) return;
        o.eroDelta = f[ERO][k];
        o.mesa = f[MESA][k];
        if (s < Stage.RIVERS.ordinal()) return;
        o.riverD = f[RIVERD][k];
        o.riverHW = f[RIVERHW][k];
        o.valley = f[VALLEY][k];
        o.delta = f[DELTA][k];
        o.estu = f[ESTU][k];
        o.lakeZ = f[LAKEZ][k];
        o.glac = f[GLAC][k];
        o.gorge = f[GORGE][k];
        o.doline = f[DOLINE][k];
        o.terrace = f[TERRACE][k];
        o.surf = i[SURF][k];
        o.river = i[RIVER][k];
        o.lake = i[LAKE][k];
        if (s < Stage.CRYOSPHERE.ordinal()) return;
        o.berg = f[BERG][k];
        o.perma = f[PERMA][k];
        o.iceFrac = f[ICEFRAC][k];
        o.hs = f[HS][k];
        o.seaIce = i[SEAICE][k];
        if (s < Stage.BIOMES.ordinal()) return;
        o.color = i[COLOR][k];
    }

    /** Height that drives the hill shading of a layer. */
    float height(MapLayer.HeightField field, int k) {
        return switch (field) {
            case BASE -> f[H0][k];
            case FINAL -> f[H][k];
            case SURFACE -> f[HS][k];
        };
    }

    /**
     * Whether a layer evaluated up to {@code need} can be painted from this sample with the same values. Later stages
     * only add fields, except the final height {@code h}, which the erosion and then the rivers (and collapse pits)
     * lower: a layer that reads it ({@code usesH}) needs a sample that stopped in the same group (before the erosion,
     * at
     * the erosion, or from the rivers on).
     */
    boolean serves(Stage need, boolean usesH) {
        if (stage.ordinal() < need.ordinal()) return false;
        return !usesH || heightGroup(stage) == heightGroup(need);
    }

    private static int heightGroup(Stage s) {
        return s.ordinal() < Stage.EROSION.ordinal() ? 0 : s == Stage.EROSION ? 1 : 2;
    }
}
