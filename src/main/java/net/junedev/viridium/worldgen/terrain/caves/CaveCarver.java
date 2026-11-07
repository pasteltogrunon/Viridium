package net.junedev.viridium.worldgen.terrain.caves;

import static net.junedev.viridium.worldgen.terrain.core.GenMath.ss;

import java.util.ArrayList;
import java.util.List;

import net.junedev.viridium.worldgen.terrain.WorldGen;
import net.junedev.viridium.worldgen.terrain.column.ColumnPlan;
import net.junedev.viridium.worldgen.terrain.column.TerrainLayout;
import net.junedev.viridium.worldgen.terrain.core.GenParams;
import net.junedev.viridium.worldgen.terrain.core.Noise;
import net.junedev.viridium.worldgen.terrain.crust.Lithology;

/**
 * Caves, block by block, for one cube at a time (pure function of the position, the column plan and the rock: cubes are
 * filled in any order without their neighbours). No Minecraft classes.
 * <p>
 * <b>Noise caves</b>: fields on a 4-block lattice, interpolated trilinearly (so the extremes of a lattice cell are at its
 * corners: cells that cannot hold a cave are skipped without any per-block work):
 * <ul>
 * <li>caverns {@code C1} (a threshold of 3D noise, flattened vertically);</li>
 * <li>huge deep caverns {@code C2} (lower frequency, more of them with depth: halls of 100+ blocks deep in the crust);</li>
 * <li>tunnels: where two noises {@code A}, {@code B} are both near zero (long winding tubes);</li>
 * <li>fissures {@code F}: vertical sheets (noise stretched vertically) near zero, inside cavern-prone rock;</li>
 * <li>lava tubes: horizontal tubes a few blocks under the surface of volcanic rock.</li>
 * </ul>
 * The rock decides the shape ({@link Style}): karst rock (limestone, dolostone, chalk, evaporite, marble) has the largest
 * caverns and widest tunnels, crystalline rock tall narrow fissures and thin tunnels, volcanic rock lava tubes, clastic
 * rock something in between, the mantle only the huge deep caverns.
 * <p>
 * <b>Planned shapes</b> ({@link Shape}, from {@link CaveFeatures}) are added on top: karst shafts, giant chambers and
 * galleries.
 * <p>
 * <b>Fluids</b>: aquifer cells of 96×48×96 blocks are dry, flooded up to a level, or (deep down) full of lava up to a
 * level; caves crossing into a cell with another level are sealed by a 2-block barrier of rock, so water never stands
 * as a wall next to air. Lava fills everything carved in the last 48 blocks of the world. Near the surface (12 blocks)
 * caves stay dry.
 * <p>
 * <b>Surface</b>: caves keep a roof of 6 blocks, except in entrance zones (a 2D noise mask), and 8 blocks of rock under
 * rivers, lakes and the sea (they never drain them).
 */
public final class CaveCarver {

    public static final byte SOLID = 0, AIR = 1, WATER = 2, LAVA = 3;

    /** Cave style by rock. */
    public enum Style {

        KARST(0.36, 0.085, 0.035),
        CLASTIC(0.50, 0.065, 0),
        CRYSTALLINE(0.60, 0.045, 0.028),
        VOLCANIC(0.56, 0.055, 0),
        MANTLE(0.80, 0.03, 0);

        /** Cavern threshold, tunnel half-width (in noise units), fissure half-width (0 none). */
        final double cavern, tunnel, fissure;

        Style(double cavern, double tunnel, double fissure) {
            this.cavern = cavern;
            this.tunnel = tunnel;
            this.fissure = fissure;
        }
    }

    private static final Style[] STYLE = new Style[Lithology.VALUES.length];

    static {
        for (Lithology l : Lithology.VALUES) STYLE[l.ordinal()] = switch (l) {
            case LIMESTONE, DOLOSTONE, CHALK, EVAPORITE, MARBLE -> Style.KARST;
            case BASALT, SCORIA, TUFF, BRECCIA, PUMICE, ANDESITE, RHYOLITE -> Style.VOLCANIC;
            case PERIDOTITE, CHROMITITE -> Style.MANTLE;
            case SANDSTONE, QUARTZ_ARENITE, ARKOSE, CONGLOMERATE, MUDSTONE, SHALE, BLACK_SHALE, CHERT, GREYWACKE,
                IRONSTONE -> Style.CLASTIC;
            default -> Style.CRYSTALLINE;
        };
    }

    public static Style style(Lithology l) {
        return STYLE[l.ordinal()];
    }

    /** A planned cave piece: tests a point, with an optional fluid of its own (else the aquifer decides). */
    public interface Shape {

        int minX(); int minY(); int minZ(); int maxX(); int maxY(); int maxZ();

        /** True if (x, y, z) is inside; {@code c1} is the cavern noise there (to roughen walls). */
        boolean inside(int x, int y, int z, double c1);

        /** Fluid of the shape at y (SOLID = let the aquifer decide, AIR = always dry). */
        default byte fluid(int y) {
            return SOLID;
        }

        /** True if the shape may cut through the roof up to the surface (shafts, entrances; never under water). */
        default boolean opensSurface() {
            return false;
        }
    }

    /** Lattice spacing (blocks), nodes per axis, cells per axis. */
    private static final int SH = 3, L = 1 << SH, N = 16 / L + 1, NN = N * N * N, CELLS = 16 / L;
    private static final int AX = 96, AY = 48;
    private static final int ROOF = 6, WATER_ROOF = 8, DRY_DEPTH = 12;

    private final GenParams P;
    private final int seedC1, seedC2, seedA, seedB, seedF, seedE, seedT, seedAq;
    private final double amount;
    private final List<CaveFeatures> features = new ArrayList<>();
    public final Karst karst;
    public final CaveSystems systems;
    private final ThreadLocal<Cube> cubes = ThreadLocal.withInitial(Cube::new);

    public CaveCarver(WorldGen gen) {
        this.P = gen.params;
        int s = gen.seeds.cave;
        seedC1 = s;
        seedC2 = s + 1001;
        seedA = s + 2003;
        seedB = s + 3007;
        seedF = s + 4001;
        seedE = s + 5003;
        seedT = s + 6007;
        seedAq = gen.seeds.aquifer;
        amount = P.caves;
        karst = new Karst(gen);
        features.add(karst);
        systems = new CaveSystems(gen);
        features.add(systems);
    }

    /** Adds planned shapes (karst shafts, chambers, galleries). */
    public void addFeatures(CaveFeatures f) {
        features.add(f);
    }

    /** 2D part of the caves, after the rivers: dolines (and the pits of collapsed chambers). */
    public void surface(double x, double z, net.junedev.viridium.worldgen.terrain.pipeline.GenPoint o) {
        karst.surface(x, z, o);
        systems.surface(x, z, o);
    }

    /** True where caves may open at the surface (2D mask, ~15 % of the land per unit of caveEntrances). */
    public boolean entrance(int x, int z) {
        if (P.caveEntrances <= 0) return false;
        return Noise.fbm(x / 220.0, z / 220.0, seedE, 3) > 0.42 - 0.12 * (P.caveEntrances - 1);
    }

    /**
     * Prepares cube (cx, cy, cz) of the given column plan; returns null when no block of it can be a cave (most cubes).
     * The result is per thread: use it before preparing another cube on the same thread.
     */
    public Cube prepare(ColumnPlan plan, int cx, int cy, int cz) {
        if (amount <= 0 && features.isEmpty()) return null;
        int y0 = cy << 4;
        if (y0 > plan.maxTopY) return null;
        Cube c = cubes.get();
        if (!c.prepare(plan, cx, cy, cz)) return null;
        return c;
    }

    /** Lattice fields and per-column data of one cube. */
    public final class Cube {

        private final double[] C1 = new double[NN], C2 = new double[NN], A = new double[NN], B = new double[NN],
            F = new double[NN];
        /** Per lattice cell, which kinds of cave are possible: 1 cavern, 2 huge, 4 tunnel, 8 fissure, 16 lava tube. */
        private final byte[] cell = new byte[CELLS * CELLS * CELLS];
        private final int[] roofY = new int[256], tubeY = new int[256], wetRoof = new int[256];
        private int tubeMin, tubeMax;
        private final List<Shape> shapes = new ArrayList<>();
        private int bx, by, bz;
        private boolean deep, nearSurface;
        private ColumnPlan plan;

        boolean prepare(ColumnPlan p, int cx, int cy, int cz) {
            plan = p;
            bx = cx << 4;
            by = cy << 4;
            bz = cz << 4;
            shapes.clear();
            for (int f = 0; f < features.size(); f++) features.get(f).collect(bx, by, bz, shapes);
            // per column: highest block a cave may reach, and the level of lava tubes
            int maxRoof = Integer.MIN_VALUE;
            tubeMin = Integer.MAX_VALUE;
            tubeMax = Integer.MIN_VALUE;
            // the 2D masks (entrances, lava tube level) only matter near the surface: deep cubes skip them
            nearSurface = by + 15 >= p.minSurfaceY - 40;
            for (int i = 0; i < 256; i++) {
                int sy = p.surfaceY[i];
                boolean wet = p.waterY[i] != ColumnPlan.NONE || p.iceTop[i] != ColumnPlan.NONE;
                int x = bx + (i & 15), z = bz + (i >> 4);
                int roof = wet ? sy - WATER_ROOF : nearSurface && entrance(x, z) ? sy : sy - ROOF;
                roofY[i] = roof;
                wetRoof[i] = wet ? sy - WATER_ROOF : sy;
                tubeY[i] = nearSurface ? sy - 9 - (int) (8 * (Noise.fbm(x / 300.0, z / 300.0, seedT, 2) + 1)) : Integer.MIN_VALUE / 2;
                tubeMin = Math.min(tubeMin, tubeY[i]);
                tubeMax = Math.max(tubeMax, tubeY[i]);
                maxRoof = Math.max(maxRoof, shapes.isEmpty() ? roof : wetRoof[i]);
            }
            if (by > maxRoof) return false;
            if (amount <= 0) {
                java.util.Arrays.fill(cell, (byte) 0);
                return !shapes.isEmpty();
            }
            deep = by < -150;
            for (int a = 0; a < N; a++) for (int b = 0; b < N; b++) for (int k = 0; k < N; k++) {
                double x = bx + L * a, y = by + L * b, z = bz + L * k;
                int n = (a * N + b) * N + k;
                C1[n] = CaveNoise.fbm3(x / 90, y / 55, z / 90, seedC1, 2);
                C2[n] = deep ? CaveNoise.fbm3(x / 260, y / 150, z / 260, seedC2, 1) : -1;
                A[n] = CaveNoise.fbm3(x / 75, y / 110, z / 75, seedA, 2);
                B[n] = CaveNoise.fbm3(x / 75, y / 110, z / 75, seedB, 2);
                F[n] = CaveNoise.fbm3(x / 38, y / 260, z / 38, seedF, 1);
            }
            // cells that may hold a cave, with the most permissive thresholds of any rock
            double tcMin = Style.KARST.cavern - 0.1 * (amount - 1), twMax = Style.KARST.tunnel * amount,
                fwMax = Style.KARST.fissure * amount, tube = 0.075 * amount;
            boolean any = !shapes.isEmpty();
            for (int a = 0; a < CELLS; a++) for (int b = 0; b < CELLS; b++) for (int k = 0; k < CELLS; k++) {
                double mc1 = -9, mc2 = -9, ma = 9, mb = 9, mf = 9;
                boolean sa = false, sb = false, sf = false;
                double a0 = 0, b0 = 0, f0 = 0;
                for (int q = 0; q < 8; q++) {
                    int n = ((a + (q & 1)) * N + b + ((q >> 1) & 1)) * N + k + (q >> 2);
                    mc1 = Math.max(mc1, C1[n]);
                    mc2 = Math.max(mc2, C2[n]);
                    if (q == 0) {
                        a0 = A[n];
                        b0 = B[n];
                        f0 = F[n];
                    }
                    sa |= A[n] * a0 <= 0;
                    sb |= B[n] * b0 <= 0;
                    sf |= F[n] * f0 <= 0;
                    ma = Math.min(ma, Math.abs(A[n]));
                    mb = Math.min(mb, Math.abs(B[n]));
                    mf = Math.min(mf, Math.abs(F[n]));
                }
                if (sa) ma = 0;
                if (sb) mb = 0;
                if (sf) mf = 0;
                int yCell = by + L * b;
                int m = (mc1 > tcMin ? 1 : 0) | (deep && mc2 > hugeThreshold(yCell + L) ? 2 : 0)
                    | (ma < twMax && mb < twMax ? 4 : 0) | (mf < fwMax && mc1 > 0.1 ? 8 : 0)
                    | (ma < tube && yCell + L >= tubeMin - 4 && yCell <= tubeMax + 4 ? 16 : 0);
                cell[(a * CELLS + b) * CELLS + k] = (byte) m;
                any |= m != 0;
            }
            return any;
        }

        private double lerp3(double[] f, int a, int b, int k, double u, double v, double w) {
            int n = (a * N + b) * N + k;
            double c000 = f[n], c001 = f[n + 1], c010 = f[n + N], c011 = f[n + N + 1];
            double c100 = f[n + N * N], c101 = f[n + N * N + 1], c110 = f[n + N * N + N], c111 = f[n + N * N + N + 1];
            double x00 = c000 + (c100 - c000) * u, x01 = c001 + (c101 - c001) * u;
            double x10 = c010 + (c110 - c010) * u, x11 = c011 + (c111 - c011) * u;
            double y0 = x00 + (x10 - x00) * v, y1 = x01 + (x11 - x01) * v;
            return y0 + (y1 - y0) * w;
        }

        /**
         * What block (lx, ly, lz) of the cube becomes: SOLID (keep the rock), AIR, WATER or LAVA. {@code lith} is the
         * rock there (for the style).
         */
        public byte at(int lx, int ly, int lz, Lithology lith) {
            int a = lx >> SH, b = ly >> SH, k = lz >> SH;
            int mask = cell[(a * CELLS + b) * CELLS + k];
            if (mask == 0 && shapes.isEmpty()) return SOLID;
            int i = lz * 16 + lx, y = by + ly;
            boolean roofed = y > roofY[i];
            if (roofed && (shapes.isEmpty() || y > wetRoof[i])) return SOLID;
            double u = (lx & (L - 1)) / (double) L, v = (ly & (L - 1)) / (double) L, w = (lz & (L - 1)) / (double) L;
            int x = bx + lx, z = bz + lz;
            byte own = SOLID;
            boolean carve = false;
            double c1 = Double.NaN;
            if (!shapes.isEmpty()) {
                for (int s = 0; s < shapes.size(); s++) {
                    Shape sh = shapes.get(s);
                    if (x < sh.minX() || x > sh.maxX() || y < sh.minY() || y > sh.maxY() || z < sh.minZ() || z > sh.maxZ())
                        continue;
                    if (c1 != c1) c1 = lerp3(C1, a, b, k, u, v, w);
                    if (roofed && !sh.opensSurface()) continue;
                    if (sh.inside(x, y, z, amount > 0 ? c1 : 0)) {
                        carve = true;
                        own = sh.fluid(y);
                        if (own != SOLID) break; // a shape with its own fluid (a chamber) wins over the aquifer
                    }
                }
            }
            if (!carve && mask != 0 && !roofed) carve = noise(mask, a, b, k, u, v, w, y, i, style(lith), c1);
            if (!carve) return SOLID;
            if (own != SOLID) return own;
            return fluid(x, y, z, plan.surfaceY[i] - y);
        }

        private boolean noise(int mask, int a, int b, int k, double u, double v, double w, int y, int i, Style st, double c1) {
            int d = plan.surfaceY[i] - y;
            if ((mask & 9) != 0) {
                if (c1 != c1) c1 = lerp3(C1, a, b, k, u, v, w);
                // fewer caverns just under the surface (thin roofs collapse), more and larger deep down
                double tc = st.cavern - 0.1 * (amount - 1) + 0.12 * (1 - ss(12, 70, d)) - 0.05 * ss(100, 600, d);
                if ((mask & 1) != 0 && c1 > tc) return true;
                if ((mask & 8) != 0 && st.fissure > 0 && c1 > 0.1
                    && Math.abs(lerp3(F, a, b, k, u, v, w)) < st.fissure * amount * ss(0.1, 0.3, c1)) return true;
            }
            if ((mask & 2) != 0 && lerp3(C2, a, b, k, u, v, w) > hugeThreshold(y)) return true;
            if ((mask & 20) == 0) return false;
            double av = lerp3(A, a, b, k, u, v, w);
            if ((mask & 4) != 0) {
                double tw = st.tunnel * amount;
                if (Math.abs(av) < tw && Math.abs(lerp3(B, a, b, k, u, v, w)) < tw) return true;
            }
            if ((mask & 16) != 0 && st == Style.VOLCANIC && d > 4) {
                double ty = (y - tubeY[i]) / 4.0, ta = av / (0.075 * amount);
                if (ta * ta + ty * ty < 1) return true;
            }
            return false;
        }

        /** Air, water or lava for a carved block, from the aquifer cells (and the lava at the bottom of the world). */
        private byte fluid(int x, int y, int z, int depth) {
            if (y < TerrainLayout.MIN_Y + 48) return LAVA;
            if (depth < DRY_DEPTH || P.aquifers <= 0) return AIR;
            int ci = Math.floorDiv(x, AX), cj = Math.floorDiv(y, AY), ck = Math.floorDiv(z, AZ_);
            long here = aquifer(ci, cj, ck);
            int lvl = level(here);
            // seal the boundary between cells of different levels with 2 blocks of rock
            int mx = Math.floorMod(x, AX), my = Math.floorMod(y, AY), mz = Math.floorMod(z, AZ_);
            if (mx < 2 && barrier(here, aquifer(ci - 1, cj, ck), y)) return SOLID;
            if (mx >= AX - 2 && barrier(here, aquifer(ci + 1, cj, ck), y)) return SOLID;
            if (mz < 2 && barrier(here, aquifer(ci, cj, ck - 1), y)) return SOLID;
            if (mz >= AZ_ - 2 && barrier(here, aquifer(ci, cj, ck + 1), y)) return SOLID;
            if (my < 2 && barrier(here, aquifer(ci, cj - 1, ck), y)) return SOLID;
            if (my >= AY - 2 && barrier(here, aquifer(ci, cj + 1, ck), y)) return SOLID;
            if (y > lvl) return AIR;
            return kind(here) == 2 ? LAVA : WATER;
        }
    }

    private static final int AZ_ = AX;

    /** Threshold of the huge deep caverns at height y: none near the surface, common in the deep crust. */
    private double hugeThreshold(int y) {
        return 0.62 - 0.2 * ss(-200, -1400, y) - 0.08 * (amount - 1);
    }

    /** Aquifer cell: packed kind (0 dry, 1 water, 2 lava) and level. */
    private long aquifer(int ci, int cj, int ck) {
        int h = Noise.hash(Noise.hash(ci, cj, seedAq), ck, seedAq + 7);
        double r1 = (h & 0xffff) / 65536.0, r2 = ((h >>> 16) & 0xffff) / 65536.0;
        int bottom = cj * AY;
        double pLava = 0.3 * ss(-700, -1500, bottom);
        double pWater = 0.38 * P.aquifers * (bottom > TerrainLayout.SEA_LEVEL - 80 ? 0.5 : 1);
        int kind = r1 < pLava ? 2 : r1 < pLava + pWater ? 1 : 0;
        int level = kind == 0 ? Integer.MIN_VALUE : bottom + (int) ((0.15 + 0.7 * r2) * AY);
        return ((long) kind << 32) | (level & 0xffffffffL);
    }

    private static int kind(long a) {
        return (int) (a >>> 32);
    }

    private static int level(long a) {
        return (int) a;
    }

    private static boolean barrier(long here, long other, int y) {
        if (here == other) return false;
        int lh = level(here), lo = level(other);
        if (kind(here) == kind(other) && lh == lo) return false;
        return y <= Math.max(lh, lo);
    }
}
