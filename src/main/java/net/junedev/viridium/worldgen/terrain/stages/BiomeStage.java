package net.junedev.viridium.worldgen.terrain.stages;

import static net.junedev.viridium.worldgen.terrain.core.GenMath.band;
import static net.junedev.viridium.worldgen.terrain.core.GenMath.sigHi;
import static net.junedev.viridium.worldgen.terrain.core.GenMath.sigLo;
import static net.junedev.viridium.worldgen.terrain.core.GenMath.ss;
import static net.junedev.viridium.worldgen.terrain.stages.Biome.*;

import java.util.Arrays;

import net.junedev.viridium.worldgen.terrain.WorldGen;
import net.junedev.viridium.worldgen.terrain.pipeline.GenPoint;

/**
 * 12. Biomes as soft memberships (functions of height, temperature, precipitation, slope, rock, water and ice),
 * sharpened by an exponent and normalised. Gives a dominant biome and a blended colour.
 */
public class BiomeStage extends GenStage {

    public BiomeStage(WorldGen gen) {
        super(gen);
    }

    private static double rockW(GenPoint o, Rock r) {
        int id = r.ordinal();
        return (o.rock == id ? o.rockT : 0) + (o.rock2 == id ? 1 - o.rockT : 0);
    }

    private static void set(float[] W, Biome b, double v) {
        W[b.ordinal()] = (float) v;
    }

    public void apply(GenPoint o) {
        float[] W = o.biomeW;
        Arrays.fill(W, 0f);
        double h = o.h, T = o.T, lp = Math.log(Math.max(o.P, 1)), slope = o.slope;
        int r = o.river;
        double iceCov = o.lake != 0 ? 0 : o.iceFrac;
        double land = ss(-0.5, 0.5, h) * (1 - r), water = (1 - ss(-0.5, 0.5, h)) * (1 - r),
            ocean = water * (1 - iceCov);
        double deep = ss(-26, -42, h);
        double shore = land * sigLo(h, 4, 1.2), inland = land * sigHi(h, 4, 1.2);
        double high = sigHi(h, 125, 12), peak = sigHi(h, 175, 10);

        // oceans
        double fr = sigLo(T, -1, 1.5), co = band(T, -1, 10, 1.5), te = band(T, 10, 20, 1.5), wa = sigHi(T, 20, 1.5);
        set(W, FROZEN_OCEAN, ocean * fr * (1 - deep));
        set(W, DEEP_FROZEN_OCEAN, ocean * fr * deep);
        set(W, COLD_OCEAN, ocean * co * (1 - deep));
        set(W, DEEP_COLD_OCEAN, ocean * co * deep);
        set(W, OCEAN, ocean * te * (1 - deep));
        set(W, DEEP_OCEAN, ocean * te * deep);
        set(W, WARM_OCEAN, ocean * wa * (1 - deep));
        set(W, DEEP_WARM_OCEAN, ocean * wa * deep);
        // coast
        set(W, BEACH, shore * sigHi(T, 1, 2) * sigLo(slope, 0.35, 0.08));
        set(W, SNOWY_BEACH, shore * sigLo(T, 1, 2));
        set(W, ROCKY_SHORE, shore * sigHi(T, 1, 2) * sigHi(slope, 0.35, 0.08));
        set(W, RIVER, r * 1.5);
        // lowlands
        double il = inland * (1 - high);
        set(W, GLACIER, il * sigLo(T, -14, 2.5));
        set(W, TUNDRA, il * band(T, -14, -3, 2.5) * sigLo(lp, 6.5, 0.3));
        set(W, SNOWY_TAIGA, il * band(T, -14, -2, 2.5) * sigHi(lp, 6.5, 0.3));
        set(W, TAIGA, il * band(T, -3, 7, 2) * sigHi(lp, 6.0, 0.3));
        set(W, COLD_STEPPE, il * band(T, -3, 12, 2) * sigLo(lp, 6.0, 0.3));
        set(W, GRASSLAND, il * band(T, 7, 22, 2) * band(lp, 5.7, 6.7, 0.25));
        set(W, TEMPERATE_FOREST, il * band(T, 7, 20, 2) * band(lp, 6.5, 7.35, 0.25));
        set(W, TEMPERATE_RAINFOREST, il * band(T, 6, 18, 2) * sigHi(lp, 7.35, 0.25));
        set(W, SAVANNA, il * sigHi(T, 20, 2) * band(lp, 5.7, 7.1, 0.25));
        set(W, DESERT, il * sigHi(T, 14, 2.5) * sigLo(lp, 5.7, 0.25));
        set(W, BADLANDS, il * sigHi(T, 14, 2.5) * sigLo(lp, 6.4, 0.3) * rockW(o, Rock.SANDSTONE) * 2.5);
        set(W, TROPICAL_RAINFOREST, il * sigHi(T, 21, 2) * sigHi(lp, 7.2, 0.25));
        set(W, SWAMP, il * sigHi(T, 8, 2) * sigHi(lp, 6.9, 0.3) * sigLo(h, 12, 2.5) * sigLo(slope, 0.06, 0.02) * 2);
        set(W, MANGROVE,
            land * sigHi(T, 22, 2) * sigHi(lp, 7.0, 0.3) * sigLo(h, 7, 1.5) * sigLo(slope, 0.06, 0.02) * 2.5);
        set(W, KARST, il * rockW(o, Rock.LIMESTONE)
            * sigHi(lp, 6.8, 0.3)
            * band(T, 4, 30, 2)
            * sigHi(slope, 0.12, 0.04)
            * 1.5);
        // mountains
        double hl = inland * high * (1 - peak);
        set(W, ALPINE_MEADOW, hl * sigHi(T, -3, 2) * sigHi(lp, 6.0, 0.3));
        set(W, COLD_STEPPE, W[COLD_STEPPE.ordinal()] + hl * sigHi(T, -3, 2) * sigLo(lp, 6.0, 0.3));
        set(W, SNOWY_SLOPES, hl * sigLo(T, -3, 2));
        double pk = inland * peak;
        set(W, ROCKY_PEAKS, pk * sigHi(T, -5, 2));
        set(W, SNOWY_PEAKS, pk * sigLo(T, -5, 2));
        // delta and ice
        set(W, DELTA, land * Math.max(o.delta, o.estu) * sigHi(T, 0, 2) * 2.5);
        set(W, SEA_ICE, water * (o.seaIce == 2 || o.berg > 0 ? 0 : o.iceFrac));
        set(W, ICE_SHELF, water * (o.seaIce == 2 ? 1 : 0));
        set(W, ICEBERG, water * (o.berg > 0 ? 1 : 0));
        if (o.lake != 0) { // lakes replace whatever was there
            Arrays.fill(W, 0f);
            set(W, o.lake == 2 ? SALT_FLAT : o.seaIce == 3 ? FROZEN_LAKE : LAKE, 1);
        }

        // sharpen + normalise
        double k = P.biomeSharpness;
        int nb = Biome.VALUES.length;
        double sum = 0;
        for (int i = 0; i < nb; i++) {
            double v = W[i] > 1e-6 ? Math.pow(W[i], k) : 0;
            W[i] = (float) v;
            sum += v;
        }
        double cr = 0, cg = 0, cb = 0, best = -1;
        int bi = 0;
        if (sum > 0) {
            for (int i = 0; i < nb; i++) {
                double v = W[i] / sum;
                W[i] = (float) v;
                if (v > 0) {
                    Biome b = Biome.VALUES[i];
                    cr += b.r * v;
                    cg += b.g * v;
                    cb += b.b * v;
                }
                if (v > best) {
                    best = v;
                    bi = i;
                }
            }
        }
        o.biome = bi;
        o.color = ((int) Math.round(cr) << 16) | ((int) Math.round(cg) << 8) | (int) Math.round(cb);
    }
}
