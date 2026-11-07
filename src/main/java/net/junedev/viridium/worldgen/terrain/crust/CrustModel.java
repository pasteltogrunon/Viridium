package net.junedev.viridium.worldgen.terrain.crust;

import static net.junedev.viridium.worldgen.terrain.core.GenMath.TAU;
import static net.junedev.viridium.worldgen.terrain.core.GenMath.clamp;
import static net.junedev.viridium.worldgen.terrain.core.GenMath.floor;
import static net.junedev.viridium.worldgen.terrain.core.GenMath.lerp;
import static net.junedev.viridium.worldgen.terrain.core.GenMath.ss;
import static net.junedev.viridium.worldgen.terrain.core.Noise.fbm;
import static net.junedev.viridium.worldgen.terrain.core.Noise.hash;
import static net.junedev.viridium.worldgen.terrain.core.Noise.rnd;
import static net.junedev.viridium.worldgen.terrain.crust.Lithology.*;

import net.junedev.viridium.worldgen.terrain.column.TerrainLayout;
import net.junedev.viridium.worldgen.terrain.core.GenParams;
import net.junedev.viridium.worldgen.terrain.core.Seeds;
import net.junedev.viridium.worldgen.terrain.pipeline.GenPoint;
import net.junedev.viridium.worldgen.terrain.stages.Rock;

/**
 * Realistic layered crust whose composition changes with depth.
 *
 * <p>
 * Each column gets a province (from the plates, continents and surface rock of the 2D pipeline) and a profile in
 * absolute Y: sedimentary/volcanic <b>cover</b> → crystalline <b>upper crust</b> → <b>middle crust</b> whose
 * metamorphic grade rises with depth → <b>lower crust</b> → <b>Moho</b> → peridotite <b>mantle</b>. Oceanic columns use
 * the ophiolite sequence (pelagic sediment → basalt → dolerite dikes → gabbro → mantle). Continental crust thickens
 * under mountains (isostatic root, eclogite under collisions) and thins towards the ocean.
 *
 * <p>
 * Interfaces are perturbed by smooth 3D noise; strata are flat-lying in platforms and folded in orogens and are
 * placed in absolute Y, so hills and valleys cut through them. 3D bodies: granite plutons, dolerite dikes, kimberlite
 * pipes and chromitite pods.
 *
 * <p>
 * Scale: 1 block ≈ 25 m vertically, so a normal continental crust is ≈ 1300 blocks and most of it lies within the
 * world's [{@link TerrainLayout#MIN_Y}, ...] range; the mantle is reachable under the oceans.
 */
public class CrustModel {

    /** Amplitude (blocks) of the 3D perturbation of every interface. */
    public static final double DISPLACEMENT = 10;

    protected final Seeds SD;
    protected final GenParams P;
    private final LatticeNoise3 dispNoise, blobNoise;
    private final ThreadLocal<Sampler> samplers;

    public CrustModel(GenParams params, Seeds seeds) {
        this.P = params;
        this.SD = seeds;
        this.dispNoise = new LatticeNoise3(16, seeds.crust);
        this.blobNoise = new LatticeNoise3(48, seeds.pluton);
        this.samplers = ThreadLocal.withInitial(Sampler::new);
    }

    // ------------------------------------------------------------------ palettes

    private static final class Palette {

        final Lithology[] lith;
        final double[] w;
        /**
         * Per surface rock ({@code Rock} ordinal): which entries it favours. Precomputed because a pick runs for
         * every cover block; the weights it yields are the same values as matching against the favoured list.
         */
        final boolean[][] favoured = new boolean[Rock.VALUES.length][];

        Palette(Object... pairs) {
            int n = pairs.length / 2;
            lith = new Lithology[n];
            w = new double[n];
            for (int i = 0; i < n; i++) {
                lith[i] = (Lithology) pairs[2 * i];
                w[i] = ((Number) pairs[2 * i + 1]).doubleValue();
            }
            for (Rock r : Rock.VALUES) {
                Lithology[] f = computeFavoured(r);
                boolean[] m = new boolean[n];
                if (f != null) for (int i = 0; i < n; i++) for (Lithology x : f) if (x == lith[i]) m[i] = true;
                favoured[r.ordinal()] = m;
            }
        }

        /** Weighted pick; {@code biasW} multiplies the weight of the lithologies matching the surface rock. */
        Lithology pick(double r, int rock, double biasW) {
            boolean[] fav = favoured[rock];
            double total = 0;
            for (int i = 0; i < lith.length; i++) total += fav[i] ? w[i] * biasW : w[i];
            double t = r * total;
            for (int i = 0; i < lith.length; i++) {
                t -= fav[i] ? w[i] * biasW : w[i];
                if (t < 0) return lith[i];
            }
            return lith[lith.length - 1];
        }
    }

    private static final Palette PLATFORM_COVER = new Palette(SANDSTONE, 3, SHALE, 3, LIMESTONE, 3, DOLOSTONE, 1.5,
        MUDSTONE, 2, QUARTZ_ARENITE, 1, CONGLOMERATE, 0.6, IRONSTONE, 0.2);
    private static final Palette BASIN_COVER = new Palette(MUDSTONE, 3, SHALE, 3, BLACK_SHALE, 1.5, SANDSTONE, 2,
        LIMESTONE, 1, ARKOSE, 1);
    private static final Palette MARGIN_COVER = new Palette(LIMESTONE, 3, MUDSTONE, 2, SANDSTONE, 2, DOLOSTONE, 1,
        GREYWACKE, 1, CHALK, 0.6);
    private static final Palette OROGEN_COVER = new Palette(PHYLLITE, 3, MICASCHIST, 2, MARBLE, 1.5, QUARTZITE, 1.5,
        GREYWACKE, 1);
    private static final Palette ARC_COVER = new Palette(ANDESITE, 4, TUFF, 3, BRECCIA, 1.5, RHYOLITE, 1, PUMICE, 0.4,
        BASALT, 0.8);
    private static final Palette LIP_COVER = new Palette(BASALT, 6, SCORIA, 1, TUFF, 0.6);
    private static final Palette PELAGIC = new Palette(CHERT, 2, MUDSTONE, 2, CHALK, 1, LIMESTONE, 0.5);
    private static final Palette ISLAND = new Palette(BASALT, 5, SCORIA, 1.5, TUFF, 1);

    private static Lithology[] computeFavoured(Rock r) {
        return switch (r) {
            case LIMESTONE -> new Lithology[] { LIMESTONE, DOLOSTONE, CHALK };
            case SANDSTONE -> new Lithology[] { SANDSTONE, QUARTZ_ARENITE, ARKOSE, CONGLOMERATE };
            case SHALE -> new Lithology[] { SHALE, MUDSTONE, BLACK_SHALE };
            case SLATE -> new Lithology[] { PHYLLITE, MICASCHIST };
            case MARBLE -> new Lithology[] { MARBLE, QUARTZITE };
            case ANDESITE -> new Lithology[] { ANDESITE, BRECCIA };
            case TUFF -> new Lithology[] { TUFF, PUMICE, RHYOLITE };
            case BASALT -> new Lithology[] { BASALT, SCORIA };
            default -> null;
        };
    }

    // ------------------------------------------------------------------ column profile

    /** Fills column {@code i} of {@code cp} from the evaluated point (stage GEOLOGY). */
    public void planColumn(CrustPlan cp, int i, int x, int z, GenPoint o, int surfaceY) {
        final int SEA = TerrainLayout.SEA_LEVEL;
        double S = P.plateSize;
        int rockId = o.rockT >= 0.5 ? o.rock : o.rock2;
        Rock rock = Rock.VALUES[rockId];
        boolean land = o.h >= 0;
        int flags = 0;
        if (o.Pb < 450) flags |= CrustPlan.FLAG_ARID;
        if (o.Tb > 18) flags |= CrustPlan.FLAG_WARM;

        // province
        Province prov;
        if (o.volc > 0.15 && o.h0 > -45 && (o.volcType >= 2)) prov = Province.VOLCANIC_ISLAND;
        else if (o.L < -0.12) prov = Province.OCEANIC;
        else if (o.collR > 0.3 || rock == Rock.SLATE || rock == Rock.MARBLE || (o.orog > 0.55 && o.mtnMask > 0.5))
            prov = Province.OROGEN;
        else if (o.volc > 0.15 || o.arc > 0.25 || rock == Rock.ANDESITE || rock == Rock.TUFF) prov = Province.ARC;
        else if (o.L < 0.06) prov = Province.MARGIN;
        else if (rock == Rock.GRANITE || rock == Rock.DIORITE || rock == Rock.GNEISS) prov = Province.SHIELD;
        else if (rock == Rock.BASALT) prov = Province.LIP;
        else if (o.h < 25 && o.Pb > 900) prov = Province.BASIN;
        else prov = Province.PLATFORM;
        if (o.volcType == 4) flags |= CrustPlan.FLAG_ATOLL;

        // crust thickness: continental (with isostatic root) vs oceanic, blended across the margin
        double contMoho = SEA - (1250 + 500 * clamp(o.orog, 0, 1) + 800 * o.collR + 250 * o.mtnMask);
        if (prov == Province.ARC) contMoho = Math.min(contMoho, SEA - 1050);
        double oceanMoho = Math.min(surfaceY, SEA - 60) - (340 - 120 * ss(0.05, 0.5, o.ridge));
        double wc = ss(-0.35, -0.03, o.L);
        double moho = lerp(oceanMoho, contMoho, wc);
        if (o.ridge > 0.1 && o.L < -0.1) flags |= CrustPlan.FLAG_RIDGE;
        if (o.collR > 0.3) flags |= CrustPlan.FLAG_ECLOGITE_ROOT;
        if (o.trench > 0.15 || (o.arc > 0.15 && o.conv > 0.1)) flags |= CrustPlan.FLAG_BLUESCHIST;

        // cover
        double rc = rnd(floor(x / 1500.0), floor(z / 1500.0), SD.strata + 3);
        double basement;
        switch (prov) {
            case SHIELD -> basement = surfaceY - 2;
            case OROGEN -> basement = surfaceY - (40 + 50 * rc);
            case ARC -> basement = surfaceY - (140 + 120 * o.volc + 60 * rc);
            case LIP -> basement = Math.min(surfaceY, SEA) - (220 + 120 * rc);
            case BASIN -> basement = SEA - (380 + 150 * rc);
            case MARGIN -> basement = SEA - (240 + 120 * rc);
            case OCEANIC -> basement = surfaceY - 40 * clamp(1 - o.ridge * 2, 0, 1) * ss(0, 0.3, -o.L);
            case VOLCANIC_ISLAND -> basement = SEA - 70;
            default -> basement = SEA - (140 + 180 * rc);
        }
        basement = Math.min(basement, surfaceY);
        basement = Math.max(basement, moho + 120);

        double t = basement - moho;
        double midTop, lowerTop;
        if (prov == Province.OCEANIC || prov == Province.VOLCANIC_ISLAND) {
            midTop = basement - 0.25 * t; // basalt → sheeted dolerite dikes
            lowerTop = basement - 0.45 * t; // → gabbro
        } else {
            midTop = basement - 0.35 * t;
            lowerTop = basement - 0.70 * t;
        }

        // strata geometry: gentle dip in platforms, folds in orogens
        double fold;
        int strataT;
        if (prov == Province.OROGEN) {
            double a = rnd(floor(x / 2000.0), floor(z / 2000.0), SD.fold) * Math.PI;
            double along = (x * Math.cos(a) + z * Math.sin(a)) / 160.0;
            fold = (25 + 40 * o.collR) * Math.sin(TAU * along + 2 * fbm(x / 900.0, z / 900.0, SD.fold + 1, 2));
            strataT = 7;
        } else {
            fold = 18 * fbm(x / 2500.0, z / 2500.0, SD.fold + 2, 2);
            strataT = 5 + (int) (8 * rc);
        }

        // basement lithology: warped regional patches
        double wx = x + 300 * fbm(x / 900.0, z / 900.0, SD.crust + 5, 2),
            wz = z + 300 * fbm(x / 900.0 + 3.1, z / 900.0, SD.crust + 6, 2);
        double rb = rnd(floor(wx / 700), floor(wz / 700), SD.crust + 7);
        Lithology up, plut;
        switch (prov) {
            case OROGEN -> {
                up = rb < 0.5 ? GRANITIC_GNEISS : rb < 0.8 ? GNEISS : MIGMATITE;
                plut = rb < 0.5 ? PINK_GRANITE : RED_GRANITE;
            }
            case ARC -> {
                up = rb < 0.6 ? GRANODIORITE : DIORITE;
                plut = rb < 0.5 ? DIORITE : GRANODIORITE;
            }
            case OCEANIC, VOLCANIC_ISLAND -> {
                up = BASALT;
                plut = GABBRO;
            }
            default -> {
                up = rb < 0.3 ? PINK_GRANITE
                    : rb < 0.5 ? RED_GRANITE : rb < 0.7 ? GRANODIORITE : rb < 0.9 ? GRANITIC_GNEISS : BLACK_GRANITE;
                plut = rb < 0.5 ? RED_GRANITE : PINK_GRANITE;
            }
        }
        if (prov != Province.OCEANIC && prov != Province.VOLCANIC_ISLAND && prov != Province.MARGIN)
            flags |= CrustPlan.FLAG_PLUTONS;

        // dolerite dike swarms (continental), one straight dike per 512-block cell
        if (wc > 0.6 && prov != Province.OROGEN) {
            int ci = floor(x / 512.0), cj = floor(z / 512.0);
            outer: for (int dj = -1; dj <= 1; dj++) for (int di = -1; di <= 1; di++) {
                int a = ci + di, b = cj + dj;
                if (rnd(a, b, SD.dike) > 0.45) continue;
                double px = (a + rnd(a, b, SD.dike + 1)) * 512, pz = (b + rnd(a, b, SD.dike + 2)) * 512;
                double ang = rnd(a, b, SD.dike + 3) * Math.PI, ux = Math.cos(ang), uz = Math.sin(ang);
                double along = (x - px) * ux + (z - pz) * uz, across = -(x - px) * uz + (z - pz) * ux;
                double half = 1.5 + 1.5 * rnd(a, b, SD.dike + 4);
                if (Math.abs(along) < 420 && Math.abs(across) < half) {
                    flags |= CrustPlan.FLAG_DIKE;
                    break outer;
                }
            }
        }

        // kimberlite pipes in old stable continental interiors
        float pipeDist = Float.NaN, pipeR = 0;
        if ((prov == Province.SHIELD || prov == Province.PLATFORM) && o.L > 0.3) {
            int ci = floor(x / 1024.0), cj = floor(z / 1024.0);
            for (int dj = -1; dj <= 1 && Float.isNaN(pipeDist); dj++) for (int di = -1; di <= 1; di++) {
                int a = ci + di, b = cj + dj;
                if (rnd(a, b, SD.pipe) > 0.2) continue;
                double px = (a + 0.2 + 0.6 * rnd(a, b, SD.pipe + 1)) * 1024,
                    pz = (b + 0.2 + 0.6 * rnd(a, b, SD.pipe + 2)) * 1024;
                double d = Math.sqrt((x - px) * (x - px) + (z - pz) * (z - pz));
                double r = 4 + 4 * rnd(a, b, SD.pipe + 3);
                if (d < r * 3.2) {
                    pipeDist = (float) d;
                    pipeR = (float) r;
                    break;
                }
            }
        }

        cp.province[i] = (byte) prov.ordinal();
        cp.flags[i] = (byte) flags;
        cp.basementTop[i] = (short) Math.round(basement);
        cp.midTop[i] = (short) Math.round(midTop);
        cp.lowerTop[i] = (short) Math.round(lowerTop);
        cp.moho[i] = (short) Math.round(moho);
        cp.fold[i] = (float) fold;
        cp.strataT[i] = (byte) strataT;
        cp.region[i] = hash(floor(x / 3000.0), floor(z / 3000.0), SD.strata);
        cp.bias[i] = (byte) rockId;
        cp.upper[i] = (byte) up.ordinal();
        cp.pluton[i] = (byte) plut.ordinal();
        cp.pipeDist[i] = pipeDist;
        cp.pipeR[i] = pipeR;
        cp.surface[i] = (short) surfaceY;
    }

    // ------------------------------------------------------------------ per-block lithology


    /**
     * Lithology at absolute (x, y, z) of column i.
     *
     * @param disp smooth 3D noise in [0, 1) (interface perturbation)
     * @param blob smooth 3D noise in [0, 1) (plutons, pods)
     */
    public Lithology lithology(CrustPlan cp, int i, int x, int y, int z, double disp, double blob) {
        return lithology(cp, i, y, disp, blob, null);
    }

    /**
     * The lithology itself. With a sampler {@code s}, {@code blob} is ignored and read from {@code s} only in the
     * branches that use it (chromitite pods, plutons), and the strata of the cover are memoised in {@code s}.
     */
    private Lithology lithology(CrustPlan cp, int i, int y, double disp, double blob, Sampler s) {
        int flags = cp.flags[i];
        int surface = cp.surface[i];
        double yd = y + (disp - 0.5) * 2 * DISPLACEMENT;
        double moho = cp.moho[i];
        Province prov = Province.VALUES[cp.province[i]];

        // kimberlite pipe: carrot-shaped, widening upwards
        float pd = cp.pipeDist[i];
        if (pd == pd && y > moho - 200) {
            double f = clamp((y - (moho - 200)) / (surface - moho + 200), 0, 1);
            if (pd < cp.pipeR[i] * (0.6 + 2.6 * f * f)) return KIMBERLITE;
        }

        // mantle
        if (yd < moho) {
            if ((flags & CrustPlan.FLAG_RIDGE) != 0 && yd > moho - 40) return SERPENTINITE;
            if (yd > moho - 260 && (s != null ? s.blob() : blob) > 0.91) return CHROMITITE;
            return PERIDOTITE;
        }
        if ((flags & CrustPlan.FLAG_DIKE) != 0 && y < surface - 6) return DOLERITE;

        double basement = cp.basementTop[i];
        if (yd >= basement) return cover(cp, i, prov, flags, y, yd, surface, s);

        boolean oceanic = prov == Province.OCEANIC || prov == Province.VOLCANIC_ISLAND;
        double midTop = cp.midTop[i], lowerTop = cp.lowerTop[i];
        if (oceanic) {
            if (yd >= midTop) return BASALT;
            if (yd >= lowerTop) return DOLERITE;
            return GABBRO;
        }
        // plutons in the upper and middle crust
        if ((flags & CrustPlan.FLAG_PLUTONS) != 0 && yd >= lowerTop && yd < basement - 20) {
            double b = s != null ? s.blob() : blob;
            if (b > 0.70) return Lithology.VALUES[cp.pluton[i]];
            if (b > 0.685) return PEGMATITE;
        }
        if (yd >= midTop) return Lithology.VALUES[cp.upper[i]];
        if (yd >= lowerTop) {
            // metamorphic grade rises with depth
            double f = (midTop - yd) / (midTop - lowerTop);
            if ((flags & CrustPlan.FLAG_BLUESCHIST) != 0 && f > 0.3 && f < 0.75) return BLUESCHIST;
            if (f < 0.25) return prov == Province.ARC ? GREENSCHIST : MICASCHIST;
            if (f < 0.5) return GNEISS;
            if (f < 0.75) return AMPHIBOLITE;
            return MIGMATITE;
        }
        // lower crust
        // eclogite lens at the bottom of a collision root: thicker where the root is deeper
        if ((flags & CrustPlan.FLAG_ECLOGITE_ROOT) != 0
            && yd < moho + Math.max(0, (TerrainLayout.SEA_LEVEL - 1400) - moho) * 0.8) return ECLOGITE;
        double f = (lowerTop - yd) / Math.max(1, lowerTop - moho);
        return f < 0.45 ? AMPHIBOLITE : GABBRO;
    }

    private Lithology cover(CrustPlan cp, int i, Province prov, int flags, int y, double yd, int surface, Sampler s) {
        if ((flags & CrustPlan.FLAG_ATOLL) != 0 && y > TerrainLayout.SEA_LEVEL - 40)
            return y > TerrainLayout.SEA_LEVEL - 12 ? LIMESTONE : DOLOSTONE;
        Palette pal = switch (prov) {
            case BASIN -> BASIN_COVER;
            case MARGIN -> MARGIN_COVER;
            case OROGEN -> OROGEN_COVER;
            case ARC -> ARC_COVER;
            case LIP -> LIP_COVER;
            case OCEANIC -> PELAGIC;
            case VOLCANIC_ISLAND -> ISLAND;
            case SHIELD -> null;
            default -> PLATFORM_COVER;
        };
        if (pal == null) return Lithology.VALUES[cp.upper[i]];
        int T = cp.strataT[i];
        double ys = y + cp.fold[i] + (yd - y) * 0.3;
        int idx = floor(ys / T);
        // 60+ blocks under the surface the bias weight below is exactly 1, so the unit's rock depends on idx alone and a
        // unit (5..13 blocks) is picked once per column of the cube instead of once per block
        boolean unbiased = surface - y >= 60;
        double r;
        if (s == null) r = rnd(idx, cp.region[i], SD.strata + 1);
        else {
            if (unbiased && idx == s.unitIdx) return s.unitLith;
            if (idx != s.rIdx) {
                s.rIdx = idx;
                s.r = rnd(idx, cp.region[i], SD.strata + 1);
            }
            r = s.r;
        }
        // the unit just under the surface tends to be the 2D geology's rock, deeper ones less so
        double biasW = 1 + 5 * clamp(1 - (surface - y) / 60.0, 0, 1);
        Lithology l = pal.pick(r, cp.bias[i], biasW);
        if (l == EVAPORITE && (flags & CrustPlan.FLAG_ARID) == 0) l = MUDSTONE;
        if (l == CHALK && (flags & CrustPlan.FLAG_WARM) == 0) l = LIMESTONE;
        if (s != null && unbiased) {
            s.unitIdx = idx;
            s.unitLith = l;
        }
        return l;
    }

    /**
     * The lithology of every block of column i in the cube starting at {@code y0}, when it cannot vary there; null when
     * the blocks must be evaluated one by one. The displaced depth yd stays within {@link #DISPLACEMENT} of y, so if
     * that whole range (plus a block of margin for rounding) lies inside one zone whose rock depends neither on yd nor
     * on the blob noise, the noise need not be sampled at all: deep mantle, the oceanic layers, dike columns and the
     * pluton-free upper crust under the cover. Covers most deep cubes and many under the sea floor.
     */
    private Lithology constantZone(CrustPlan cp, int i, int y0) {
        double lo = y0 - DISPLACEMENT - 1, hi = y0 + 15 + DISPLACEMENT + 1;
        double moho = cp.moho[i];
        int flags = cp.flags[i];
        float pd = cp.pipeDist[i];
        if (pd == pd && y0 + 15 > moho - 200) return null; // a kimberlite pipe may cross the cube
        if (hi < moho - 260) return PERIDOTITE; // below serpentinite and chromitite
        if ((flags & CrustPlan.FLAG_DIKE) != 0) return lo >= moho && y0 + 15 < cp.surface[i] - 6 ? DOLERITE : null;
        double basement = cp.basementTop[i], midTop = cp.midTop[i], lowerTop = cp.lowerTop[i];
        if (lo < moho || hi >= basement) return null; // mantle or cover within reach
        Province prov = Province.VALUES[cp.province[i]];
        if (prov == Province.OCEANIC || prov == Province.VOLCANIC_ISLAND) {
            if (lo >= midTop) return BASALT;
            if (lo >= lowerTop && hi < midTop) return DOLERITE;
            if (hi < lowerTop) return GABBRO;
            return null;
        }
        if ((flags & CrustPlan.FLAG_PLUTONS) == 0 && lo >= midTop) return Lithology.VALUES[cp.upper[i]];
        return null;
    }

    // ------------------------------------------------------------------ sampling helpers

    /** Lithology with directly evaluated noise (map cross-sections, tools, single samples). */
    public Lithology lithologyAt(CrustPlan cp, int i, int x, int y, int z) {
        return lithology(cp, i, y, dispNoise.at(x, y, z), blobNoise.at(x, y, z), null);
    }

    /** Per-thread sampler with the 3D noise cached for one cube. */
    public Sampler sampler() {
        return samplers.get();
    }

    /**
     * Samples the lithology of one cube: {@link #prepare} once, then either {@link #at(CrustPlan, int, int, int, int)}
     * per block or, faster, {@link #column} per block column followed by {@link #at(int)} for its 16 blocks. Keeps per
     * column state (a constant zone, the last strata unit), so sampling a column's blocks together is what pays off;
     * any order gives the same values. Not thread-safe: one per thread ({@link #sampler()}).
     */
    public final class Sampler {

        private final LatticeNoise3.Cached dispC = dispNoise.new Cached(), blobC = blobNoise.new Cached();
        private int by;
        /** The selected column: plan, index, local x/z; the block being sampled (for {@link #blob}). */
        private CrustPlan cp;
        private int i, lx, lz, ly;
        /** Whole-column lithology when {@link #constantZone} found one. */
        private Lithology constant;
        /** Cover memo of the column: last strata unit's random value, and last unbiased unit's lithology. */
        int rIdx, unitIdx;
        double r;
        Lithology unitLith;

        public void prepare(int blockX, int blockY, int blockZ) {
            by = blockY;
            dispC.prepare(blockX, blockY, blockZ);
            blobC.prepare(blockX, blockY, blockZ);
            cp = null;
        }

        /** Selects column i (at local lx, lz) of the prepared cube for {@link #at(int)}. */
        public void column(CrustPlan cp, int i, int lx, int lz) {
            if (cp == this.cp && i == this.i) return;
            this.cp = cp;
            this.i = i;
            this.lx = lx;
            this.lz = lz;
            rIdx = unitIdx = Integer.MIN_VALUE;
            constant = constantZone(cp, i, by);
        }

        /** Lithology at local height ly of the selected column. */
        public Lithology at(int ly) {
            if (constant != null) return constant;
            this.ly = ly;
            return lithology(cp, i, by + ly, dispC.at(lx, ly, lz), 0, this);
        }

        public Lithology at(CrustPlan cp, int i, int lx, int ly, int lz) {
            column(cp, i, lx, lz);
            return at(ly);
        }

        double blob() {
            return blobC.at(lx, ly, lz);
        }
    }
}
