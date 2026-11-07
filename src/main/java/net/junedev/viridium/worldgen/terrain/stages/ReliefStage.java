package net.junedev.viridium.worldgen.terrain.stages;

import static net.junedev.viridium.worldgen.terrain.core.GenMath.clamp;
import static net.junedev.viridium.worldgen.terrain.core.GenMath.lerp;
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

    /**
     * Plateaus: tablelands lifted as a block without folding (Iberian Meseta, Colorado Plateau, the Ethiopian highlands)
     * or built by piled flood basalts (Deccan traps). A large-scale mask with ragged detail (spurs and embayments along
     * the edge) is cut by a narrow step: the top is flat, the edge an escarpment ~8 blocks wide. Basalt plateaus climb in
     * 3 ledges (each lava flow a step). Only inland, away from collisions (Tibet is the collision plateau). The noise does
     * not depend on {@code detail}: the coarse lattices and the rivers see the edge where the columns have it.
     *
     * @return plateau share k in 0..1 (also o.plat, o.platType)
     */
    protected double plateau(double x, double y, double L, GenPoint o) {
        o.plat = 0;
        o.platType = 0;
        o.scarp = 0;
        if (P.plateaus <= 0 || L < 0.02) return 0;
        // towards the coast and collisions the mask is lowered: the plateau ends inland with its scarp, it does not ramp down
        double inland = ss(0.02, 0.2, L) * (1 - ss(0.05, 0.3, o.collZ));
        double thr = 0.16 - 0.12 * (P.plateaus - 1);
        double m0 = fbm(x / 9000, y / 9000, SD.plat, 3);
        // the two detail octaves add at most 0.28 (|fbm| <= ~1; 1.5 bounds it safely): far below the threshold they
        // cannot lift m above it, so skip them (same result)
        if (m0 + 0.28 * 1.5 - 0.3 * (1 - inland) < thr - 0.045) return 0;
        double m = m0 + 0.22 * fbm(x / 1700, y / 1700, SD.plat + 1, 3) + 0.06 * fbm(x / 300, y / 300, SD.plat + 2, 2)
            - 0.3 * (1 - inland);
        if (m < thr - 0.045) return 0;
        boolean basalt = fbm(x / 20000, y / 20000, SD.plat + 4, 2) > 0.2;
        double k;
        if (basalt) { // traps: three ledges, each with its own short scarp
            double q = ss(thr - 0.03, thr + 0.03, m) * 3, fl = Math.floor(q);
            k = Math.min(1, (fl + ss(0.72, 1, q - fl)) / 3);
        } else k = ss(thr - 0.002, thr + 0.002, m);
        k = 0.94 * k + 0.06 * ss(thr - 0.04, thr, m); // gentle footslope below the scarp
        // the erosion gradient comes from an 8-block lattice: next to a scarp it is smeared over ~20 blocks and would dig
        // a moat along its foot
        o.scarp = (1 - ss(basalt ? 0.032 : 0.003, basalt ? 0.042 : 0.013, Math.abs(m - thr)));
        o.plat = k;
        o.platType = basalt ? 1 : 0;
        return k;
    }

    /**
     * Continental rift (geometry from ContinentStage): two fault scarps drop a graben between raised shoulders. Young rifts
     * are 60-110 blocks deep with sills and basins along the floor (long lakes form between them, as in East Africa or
     * Baikal); failed rifts are shallow and worn down, with soft walls; mature rifts are a narrow, deep sea (the Red Sea).
     * Volcanoes rise on the floor and the shoulders of young and mature rifts.
     */
    protected double rift(double x, double y, double h, boolean detail, GenPoint o) {
        if (o.rift <= 0 || o.riftD > 2.6 || P.riftDepth <= 0) return h;
        double d = o.riftD, a = o.rift;
        int t = o.riftType;
        double wall = t == 1 ? ss(0.7, 1.15, d) : ss(0.88, 0.97, d); // 0 inside the graben, 1 outside
        double var = fbm(o.riftU / 2200, 4.4, SD.rift + 9, 3); // sills and basins along the floor
        double U = (t == 1 ? 20 : 40 + 45 * clamp(0.5 + 0.7 * fbm(o.riftU / 3000, 1.3, SD.rift + 12, 2), 0, 1)) * P.riftDepth;
        double shoulders = U * Math.exp(-((d - 1.0) / 0.6) * ((d - 1.0) / 0.6)) * wall;
        double floor = t == 2 ? -45 + 15 * var
            : t == 1 ? 0.6 * Math.max(h, 0) + 4 + 8 * var
            : 0.4 * Math.max(h, 0) + 10 + 32 * var;
        floor *= t == 2 ? P.riftDepth : 1;
        double out = h + shoulders * a;
        double res = lerp(out, Math.min(out, floor), (1 - wall) * a);
        if (t != 1) o.scarp = Math.max(o.scarp, a * (1 - ss(0.02, 0.12, Math.abs(d - 0.925))));
        // volcanoes on the floor and the shoulders
        if (t != 1) {
            double spot = ss(0.3, 0.55, fbm(x / 1400, y / 1400, SD.rift + 11, 2)) * (1 - ss(1.2, 1.8, d)) * a;
            if (spot > 0.02) {
                double c = ridged(x / 240, y / 240, SD.rift + 10, detail ? 3 : 1);
                double cone = res + spot * (60 + 90 * c * c * c);
                if (spot * c > 0.25 && cone > res) {
                    res = lerp(res, cone, ss(0.25, 0.45, spot * c));
                    o.volc = Math.max(o.volc, spot * c);
                    o.volcType = 1;
                }
            }
        }
        return res;
    }

    /**
     * Sea cliffs: where the coast is rocky (a regional noise, about a third of the coastline) the land does not slope into
     * the sea, it stands 14..60 blocks above it and drops in ~5 blocks (waves cut the foot back and leave a shallow
     * platform: the shelf). The raised strip fades back to the normal land 150..400 blocks inland. Coves: the step
     * follows the coast shifted by small-scale noise, so pocket beaches open between headlands. The noise does not
     * depend on {@code detail}.
     *
     * @return height added
     */
    protected double coastalCliff(double x, double y, double L, GenPoint o) {
        if (P.cliffs <= 0 || L < -0.03 || L > 0.12) return 0;
        double cw = ss(0.0, 0.2, fbm(x / 6000, y / 6000, SD.cliff, 2) + 0.3 * (P.cliffs - 1));
        if (cw <= 0) return 0;
        double Lc = L + 0.006 * fbm(x / 90, y / 90, SD.cliff + 2, 2);
        double C = P.cliffs * cw * (14 + 46 * clamp(0.5 + 0.7 * fbm(x / 2500, y / 2500, SD.cliff + 1, 2), 0, 1));
        double k = ss(0, 0.0035, Lc) * (1 - ss(0.03, 0.12, L));
        o.cliff = cw * k;
        // erosion fades in slowly behind the edge: a sudden start would leave a raised lip along the top
        o.scarp = Math.max(o.scarp, Math.min(1, 3 * cw) * (Lc < 0 ? 1 - ss(0.002, 0.012, -Lc) : 1 - ss(0.0035, 0.08, Lc)));
        return C * k;
    }

    /** Height of the plateau top above the surrounding plains (60..150 blocks, varying over ~14 km). */
    protected double plateauTop(double x, double y) {
        return P.plateauHeight * (60 + 90 * clamp(0.5 + 0.8 * fbm(x / 14000, y / 14000, SD.plat + 3, 2), 0, 1));
    }

    /** Returns the base height at (x, y); also sets mtnMask, collR, volc and volcType on {@code o}. */
    public double baseHeight(double x, double y, GenPoint o, boolean detail, GenScratch s) {
        double L = o.L;
        double h;
        o.plat = 0;
        o.platType = 0;
        o.scarp = 0;
        o.cliff = 0;
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
            double k = plateau(x, y, L, o);
            // k * plateauTop is +0 when k == 0 (the top is positive): skip its noise there
            h = plains + hills * (1 - 0.65 * k) + mtn * (1 - 0.85 * k) + (k > 0 ? k * plateauTop(x, y) : 0);
            o.mtnMask *= 1 - 0.85 * k;
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
        h += coastalCliff(x, y, L, o);
        h = rift(x, y, h, detail, o);
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
