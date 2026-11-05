package net.junedev.viridium.worldgen.terrain.stages;

import static net.junedev.viridium.worldgen.terrain.core.GenMath.clamp;
import static net.junedev.viridium.worldgen.terrain.core.GenMath.ss;
import static net.junedev.viridium.worldgen.terrain.core.Noise.fbm;
import static net.junedev.viridium.worldgen.terrain.core.Noise.ridged;

import net.junedev.viridium.worldgen.terrain.WorldGen;
import net.junedev.viridium.worldgen.terrain.pipeline.GenPoint;
import net.junedev.viridium.worldgen.terrain.pipeline.GenScratch;

/**
 * 7. Base height. Ocean: shelf → slope → abyssal plain + ridges − trenches. Land: plains + hills + ridged mountains.
 * Plus collision ranges, volcanic arcs and (via {@link VolcanismStage}) hotspot chains and Iceland-like islands.
 */
public class ReliefStage extends GenStage {

    public ReliefStage(WorldGen gen) {
        super(gen);
    }

    /** Returns the base height at (x, y); also sets mtnMask, collR, volc and volcType on {@code o}. */
    public double baseHeight(double x, double y, GenPoint o, boolean detail, GenScratch s) {
        double L = o.L;
        double h;
        o.collR = 0;
        o.volc = 0;
        o.volcType = 0;
        if (L >= 0) {
            double plains = 40 * ss(0, 0.55, L);
            double hills = (fbm(x / 380, y / 380, SD.hills, detail ? 4 : 2) * 0.5 + 0.5) * 26 * ss(0, 0.1, L);
            double land = ss(0, 0.16, L);
            double mask = ss(0.0, 0.4, fbm(x / 2600, y / 2600, SD.mmask, 3)) + o.orog * 1.1;
            mask = clamp(mask, 0, 1) * land;
            o.mtnMask = mask;
            double mtn = 0;
            if (mask > 1e-3) {
                double r = ridged(x / 760, y / 760, SD.mtn, detail ? 5 : 3);
                mtn = r * r * 290 * mask * P.mountainStrength;
            }
            h = plains + hills + mtn;
        } else {
            o.mtnMask = 0;
            double floor = -62 + 40 * o.ridge * P.ridgeStrength
                - 30 * o.trench
                + 5 * fbm(x / 600, y / 600, SD.sea, 2);
            if (o.ridge > 0.02) floor += 16 * o.ridge
                * P.ridgeStrength
                * (ridged(x / 260, y / 260, SD.seaR, detail ? 3 : 1) - 0.4);
            // continental shelf → slope → abyssal plain
            h = -16 * ss(0, -0.06, L) + (floor + 16) * ss(-0.06, -0.3, L);
        }
        // continental collision: range on the suture + plateau on the overriding plate (Himalaya + Tibet)
        if (o.collK > 1e-3) {
            double S = P.plateSize, sd = o.collS;
            double rg = Math.exp(-(sd / (0.07 * S)) * (sd / (0.07 * S)));
            double pl = ss(-0.03 * S, 0.05 * S, sd) * (1 - ss(0.17 * S, 0.3 * S, sd));
            double ft = (sd + 0.13 * S) / (0.05 * S), fore = Math.exp(-ft * ft);
            double rr = ridged(x / 1100, y / 1100, SD.coll + 9, detail ? 5 : 3);
            h += o.collK * P.collisionHeight
                * (pl * (105 + 25 * fbm(x / 1500, y / 1500, SD.coll + 11, 2)) + rg * (120 + 360 * rr * rr)
                    - 12 * fore);
            o.collR = o.collK * Math.max(rg, pl);
        }
        // volcanic arc (islands at sea, volcanoes on the coast): independent of L → continuous
        if (o.arc > 0.02) {
            double v = fbm(x / 380, y / 380, SD.arcN, detail ? 4 : 2);
            double isl = o.arc * ss(-0.2, 0.4, v);
            double cone = ridged(x / 200, y / 200, SD.arcN + 3, detail ? 3 : 1);
            double ht = -75 + 150 * isl + 70 * isl * cone * cone;
            if (ht > h) {
                h = ht;
                o.volc = isl;
                o.volcType = 1;
            }
        }
        return gen.volcanism.apply(x, y, o, h, detail, s);
    }
}
