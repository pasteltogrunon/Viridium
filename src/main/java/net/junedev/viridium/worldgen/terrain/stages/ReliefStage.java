package net.junedev.viridium.worldgen.terrain.stages;

import static net.junedev.viridium.worldgen.terrain.core.GenMath.clamp;
import static net.junedev.viridium.worldgen.terrain.core.GenMath.lerp;
import static net.junedev.viridium.worldgen.terrain.core.GenMath.ss;
import static net.junedev.viridium.worldgen.terrain.core.Noise.fbm;
import static net.junedev.viridium.worldgen.terrain.core.Noise.perlin;
import static net.junedev.viridium.worldgen.terrain.core.Noise.ridged;
import static net.junedev.viridium.worldgen.terrain.core.Noise.rnd;

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
     * Plateaus: tablelands lifted as a block without folding (Iberian Meseta, Colorado Plateau, the Ethiopian
     * highlands)
     * or built by piled flood basalts (Deccan traps). A large-scale mask with ragged detail (spurs and embayments along
     * the edge) is cut by a narrow step: the top is flat, the edge an escarpment ~8 blocks wide. Basalt plateaus climb
     * in
     * 3 ledges (each lava flow a step). Only inland, away from collisions (Tibet is the collision plateau). The noise
     * does
     * not depend on {@code detail}: the coarse lattices and the rivers see the edge where the columns have it.
     *
     * @return plateau share k in 0..1 (also o.plat, o.platType)
     */
    protected double plateau(double x, double y, double L, GenPoint o) {
        o.plat = 0;
        o.platType = 0;
        o.scarp = 0;
        if (P.plateaus <= 0 || L < 0.02) return 0;
        // towards the coast and collisions the mask is lowered: the plateau ends inland with its scarp, it does not
        // ramp down
        double inland = ss(0.02, 0.2, L) * (1 - ss(0.05, 0.3, o.collZ));
        double thr = 0.16 - 0.12 * (P.plateaus - 1);
        double m0 = fbm(x / 9000, y / 9000, SD.plat, 3);
        // the two detail octaves add at most 0.28 (|fbm| <= ~1; 1.5 bounds it safely): far below the threshold they
        // cannot lift m above it, so skip them (same result)
        if (m0 + 0.28 * 1.5 - 0.3 * (1 - inland) < thr - 0.045) return 0;
        double m = m0 + 0.22 * fbm(x / 1700, y / 1700, SD.plat + 1, 3)
            + 0.06 * fbm(x / 300, y / 300, SD.plat + 2, 2)
            - 0.3 * (1 - inland);
        if (m < thr - 0.045) return 0;
        boolean basalt = fbm(x / 20000, y / 20000, SD.plat + 4, 2) > 0.2;
        double k;
        if (basalt) { // traps: three ledges, each with its own short scarp
            double q = ss(thr - 0.03, thr + 0.03, m) * 3, fl = Math.floor(q);
            k = Math.min(1, (fl + ss(0.72, 1, q - fl)) / 3);
        } else k = ss(thr - 0.002, thr + 0.002, m);
        k = 0.94 * k + 0.06 * ss(thr - 0.04, thr, m); // gentle footslope below the scarp
        // the erosion gradient comes from an 8-block lattice: next to a scarp it is smeared over ~20 blocks and would
        // dig
        // a moat along its foot
        o.scarp = (1 - ss(basalt ? 0.032 : 0.003, basalt ? 0.042 : 0.013, Math.abs(m - thr)));
        o.plat = k;
        o.platType = basalt ? 1 : 0;
        return k;
    }

    /**
     * Continental rift (geometry from ContinentStage): two fault scarps drop a graben between raised shoulders. Young
     * rifts
     * are 60-110 blocks deep with sills and basins along the floor (long lakes form between them, as in East Africa or
     * Baikal); failed rifts are shallow and worn down, with soft walls; mature rifts are a narrow, deep sea (the Red
     * Sea).
     * Volcanoes rise on the floor and the shoulders of young and mature rifts.
     */
    protected double rift(double x, double y, double h, boolean detail, GenPoint o) {
        if (o.rift <= 0 || o.riftD > 2.6 || P.riftDepth <= 0) return h;
        double d = o.riftD, a = o.rift;
        int t = o.riftType;
        double wall = t == 1 ? ss(0.7, 1.15, d) : ss(0.88, 0.97, d); // 0 inside the graben, 1 outside
        double var = fbm(o.riftU / 2200, 4.4, SD.rift + 9, 3); // sills and basins along the floor
        double U = (t == 1 ? 20 : 40 + 45 * clamp(0.5 + 0.7 * fbm(o.riftU / 3000, 1.3, SD.rift + 12, 2), 0, 1))
            * P.riftDepth;
        double shoulders = U * Math.exp(-((d - 1.0) / 0.6) * ((d - 1.0) / 0.6)) * wall;
        double floor = t == 2 ? -45 + 15 * var
            : t == 1 ? 0.6 * Math.max(h, 0) + 4 + 8 * var : 0.4 * Math.max(h, 0) + 10 + 32 * var;
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
     * Sea cliffs: where the coast is rocky (a regional noise, about a third of the coastline) the land does not slope
     * into
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
        o.scarp = Math
            .max(o.scarp, Math.min(1, 3 * cw) * (Lc < 0 ? 1 - ss(0.002, 0.012, -Lc) : 1 - ss(0.0035, 0.08, Lc)));
        return C * k;
    }

    /**
     * Distance (blocks) from (x, y), at sea, to the coast: the continentality over its gradient, measured 40 blocks
     * away
     * (two extra plate evaluations, only where a coast landform may be). Bands of continentality are no measure of
     * distance: its gradient changes tenfold along a coast.
     */
    protected double offshore(double x, double y, double L, GenScratch s) {
        GenPoint p = COAST_POINT.get();
        s = COAST_SCRATCH.get(); // its own temporaries: the caller's are still in use
        final double E = 40; // wide: the barrier and the reef follow the coast's general line, not every cove
        gen.plates.apply(x + E, y, p, s);
        gen.continents.apply(x + E, y, p);
        double gx = (p.L - L) / E;
        gen.plates.apply(x, y + E, p, s);
        gen.continents.apply(x, y + E, p);
        double gy = (p.L - L) / E;
        double g = Math.sqrt(gx * gx + gy * gy);
        if (g < 1e-4) return 1e9;
        double dist = -L / g;
        if (dist > 450) return dist;
        // the linear estimate also finds "coasts" around every shoal of the open sea: there must be land that way
        double k = (dist + 8) / g;
        gen.plates.apply(x + gx * k, y + gy * k, p, s);
        gen.continents.apply(x + gx * k, y + gy * k, p);
        return p.L >= 0 ? dist : 1e9;
    }

    private static final ThreadLocal<GenPoint> COAST_POINT = ThreadLocal.withInitial(GenPoint::new);
    private static final ThreadLocal<GenScratch> COAST_SCRATCH = ThreadLocal.withInitial(GenScratch::new);

    /**
     * Sea stacks: as a cliff retreats, harder parts of it are left standing in the sea as pillars (the Twelve Apostles,
     * the needle of Etretat, the Old Man of Hoy), lower the farther out they are. One candidate per 44-block cell in
     * front of rocky coasts, 4-10 blocks across, up to the height of the cliff behind, 6-60 blocks out; the cliff
     * detail
     * cuts notches and sea caves into them at the water line, which open into arches through the narrow ones.
     *
     * @return the stack's height (blocks above sea level), or NaN where there is none
     */
    protected double seaStack(double x, double y, double dist, double cw, double C) {
        if (dist < 4 || dist > 70) return Double.NaN;
        final double S = 44;
        int ci = (int) Math.floor(x / S), cj = (int) Math.floor(y / S);
        double best = 0;
        for (int dj = -1; dj <= 1; dj++) for (int di = -1; di <= 1; di++) {
            int a = ci + di, b = cj + dj;
            if (rnd(a, b, SD.coast) > 0.55 * Math.min(P.stacks, 1.8)) continue;
            double cx = (a + 0.2 + 0.6 * rnd(a, b, SD.coast + 1)) * S,
                cy = (b + 0.2 + 0.6 * rnd(a, b, SD.coast + 2)) * S;
            double r = 2 + 3 * rnd(a, b, SD.coast + 3), d = Math.hypot(x - cx, y - cy);
            if (d > r + 4) continue;
            d *= 1 + 0.18 * perlin((x - cx) / 4, (y - cy) / 4, SD.coast + 4); // rough outline, steep sides
            double k = ss(r + 1.5, r - 0.5, d);
            if (k > 0) best = Math.max(best, k * (0.55 + 0.45 * rnd(a, b, SD.coast + 5)) * C);
        }
        if (best <= 0) return Double.NaN;
        // stumps further out: the sea has had longer to wear them down
        return best * ss(75, 30, dist) * Math.min(1, 2.5 * cw);
    }

    /** Where barrier islands may stand (0..1), from regional noise: gentle coasts only. */
    protected double barrierMask(double x, double y, GenPoint o) {
        if (P.barriers <= 0 || o.Tb < -1 || o.orog > 0.4 || o.arc > 0.02) return 0;
        double bm = ss(0.1, 0.35, fbm(x / 7000, y / 7000, SD.coast + 10, 2) + 0.25 * (P.barriers - 1)) * ss(-1, 3, o.Tb)
            * (1 - ss(0.1, 0.4, o.orog));
        if (bm > 0.01 && P.cliffs > 0) // not below sea cliffs
            bm *= 1 - ss(0.0, 0.08, ss(0.0, 0.2, fbm(x / 6000, y / 6000, SD.cliff, 2) + 0.3 * (P.cliffs - 1)));
        return bm;
    }

    /**
     * Barrier islands and lagoons: on low, sandy coasts the waves pile a long narrow ridge of sand offshore, cut by
     * tidal
     * inlets, with a shallow lagoon behind it (the Outer Banks, Padre Island, the Wadden islands, the Venetian Lido).
     * The
     * barrier runs 70-200 blocks out, 18-34 blocks wide, with dunes along its crest. Where the regional mask weakens
     * the
     * lagoon silts up and the barrier joins the mainland: a spit.
     *
     * @return the height (blocks relative to sea level) the barrier system gives the point
     */
    protected double barrier(double x, double y, double dist, double h, double bm) {
        double Db = 70 + 130 * clamp(0.5 + 0.7 * fbm(x / 2500, y / 2500, SD.coast + 11, 2), 0, 1); // the barrier line
        double wb = 9 + 8 * clamp(0.5 + fbm(x / 900, y / 900, SD.coast + 12, 2), 0, 1); // its half-width
        double fill = ss(0.15, 0.55, bm); // weak barrier: the lagoon has silted up (attached spit, marsh)
        if (dist < Db - wb) { // the lagoon, shallow and muddy
            double deep = -1.2 - 1.4 * ss(0, 25, dist) * ss(Db - wb, Db - wb - 20, dist)
                + 0.4 * perlin(x / 60, y / 60, SD.coast + 13);
            return lerp(1.2 * (1 - ss(0, Db, dist)) + 0.2, deep, fill);
        }
        if (dist < Db + wb) { // the barrier: dunes along its crest, tidal inlets every few kilometres
            double q = (dist - Db) / wb;
            double crest = 1.5 + 2.5 * clamp(0.5 + 0.8 * fbm(x / 70, y / 70, SD.coast + 14, 3), 0, 1);
            double t = lerp(-0.6, crest, Math.sqrt(Math.max(0, 1 - q * q)));
            double inlet = ss(0.42, 0.5, Math.abs(fbm(x / 1600, y / 1600, SD.coast + 15, 2)));
            return lerp(t, -2.5, inlet * fill);
        }
        return lerp(-0.6, h, ss(Db + wb, Db + wb + 60, dist)); // the shoreface, back down to the shelf
    }

    /** Where coral reefs grow (0..1): warm, clear seas. */
    protected double reefMask(double x, double y, GenPoint o) {
        if (P.reefs <= 0 || o.Tb < 20) return 0;
        return ss(20, 24, o.Tb) * ss(-0.05, 0.25, fbm(x / 5000, y / 5000, SD.coast + 20, 2) + 0.2 * (P.reefs - 1));
    }

    /**
     * Coral reefs, in warm clear seas: a fringing reef hugs the coast (a reef flat a block or two under the water, its
     * crest at the outer edge 20-45 blocks out, a steep front down to the shelf: the Red Sea), and where the shelf is
     * wide
     * a barrier reef runs 180-400 blocks out with a lagoon behind it (the Great Barrier Reef, New Caledonia). Atolls
     * are
     * made with the hotspot volcanoes.
     *
     * @return the height the reef gives the point (never below {@code h}), or NaN where there is no reef
     */
    protected double reef(double x, double y, double dist, double h, double warm) {
        double res = Double.NaN;
        double Df = 20 + 25 * clamp(0.5 + fbm(x / 800, y / 800, SD.coast + 21, 2), 0, 1);
        if (dist < Df + 10) { // fringing reef: the flat from the shore, the crest at its edge, then the front
            double t = dist < Df ? -1.6 - 0.6 * ss(Df, 0, dist) * ss(0, 6, dist) : lerp(-1.3, h, ss(Df, Df + 10, dist));
            if (dist < Df && dist > Df - 4) t = -1.3; // the crest, awash at low tide
            if (t > h) res = lerp(h, t, warm);
        }
        double br = warm * ss(0.0, 0.3, fbm(x / 9000, y / 9000, SD.coast + 23, 2));
        if (br > 0) { // barrier reef: a ridge far out, its lagoon inside
            double Dr = 180 + 220 * clamp(0.5 + 0.7 * fbm(x / 3000, y / 3000, SD.coast + 22, 2), 0, 1);
            double q = Math.abs(dist - Dr);
            double t = Double.NaN;
            if (q < 7) // the crest, with passes through it
                t = -1.3 - 0.4 * ss(4, 7, q) - 1.2 * ss(0.42, 0.5, Math.abs(fbm(x / 500, y / 500, SD.coast + 24, 2)));
            else if (dist < Dr && dist > Df + 10) t = Math.max(h, -8 - 2 * perlin(x / 80, y / 80, SD.coast + 25)); // lagoon
            else if (dist > Dr && q < 25) t = lerp(-1.7, h, ss(7, 25, q)); // the steep outer front
            if (t == t) {
                double v = lerp(h, Math.max(t, h), br);
                if (v > h && (res != res || v > res)) res = v;
            }
        }
        return res;
    }

    /** Sea stacks, barrier islands and lagoons, coral reefs: the shallow sea in front of the coast. */
    protected double coastLandforms(double x, double y, double L, double h, GenPoint o, GenScratch s) {
        double cw = 0, bm = barrierMask(x, y, o), warm = reefMask(x, y, o);
        if (P.stacks > 0 && P.cliffs > 0 && L > -0.08)
            cw = ss(0.0, 0.2, fbm(x / 6000, y / 6000, SD.cliff, 2) + 0.3 * (P.cliffs - 1));
        if (cw < 0.15 && bm <= 0.01 && warm <= 0) return h;
        double dist = offshore(x, y, L, s);
        if (cw >= 0.15 && dist < 75) {
            double C = P.cliffs * cw * (14 + 46 * clamp(0.5 + 0.7 * fbm(x / 2500, y / 2500, SD.cliff + 1, 2), 0, 1));
            double st = seaStack(x, y, dist, cw, C);
            if (st == st && st > h) {
                o.scarp = Math.max(o.scarp, ss(h, h + 4, st)); // erosion leaves the pillar alone
                o.cliff = Math.max(o.cliff, 0.5);
                return st;
            }
        }
        if (bm > 0.01 && dist < 300) {
            double b = barrier(x, y, dist, h, bm), hb = lerp(h, b, bm);
            if (hb > -1 && b > h) o.surf = GenPoint.SURF_SAND;
            h = hb;
        }
        if (warm > 0 && dist < 430) {
            double rf = reef(x, y, dist, h, warm);
            if (rf == rf) {
                if (rf > h + 0.5) o.surf = GenPoint.SURF_REEF;
                h = rf;
            }
        }
        return h;
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
            double floor = -62 + 40 * o.ridge * P.ridgeStrength - 30 * o.trench + 5 * fbm(x / 600, y / 600, SD.sea, 2);
            if (o.ridge > 0.02)
                floor += 16 * o.ridge * P.ridgeStrength * (ridged(x / 260, y / 260, SD.seaR, detail ? 3 : 1) - 0.4);
            // continental shelf → slope → abyssal plain
            h = -16 * ss(0, -0.06, L) + (floor + 16) * ss(-0.06, -0.3, L);
        }
        h += coastalCliff(x, y, L, o);
        // (a few tens of blocks wide: not in the coarse lattices, nor on map pixels wider than 6 blocks)
        if (L < 0 && L > -0.2 && detail && (s == null || s.bpp <= 6)) h = coastLandforms(x, y, L, h, o, s);
        double hCoast = h;
        h = rift(x, y, h, detail, o);
        // continental collision: range on the suture + plateau on the overriding plate (Himalaya + Tibet)
        if (o.collK > 1e-3) {
            double S = P.plateSize, sd = o.collS;
            double rg = Math.exp(-(sd / (0.07 * S)) * (sd / (0.07 * S)));
            double pl = ss(-0.03 * S, 0.05 * S, sd) * (1 - ss(0.17 * S, 0.3 * S, sd));
            double ft = (sd + 0.13 * S) / (0.05 * S), fore = Math.exp(-ft * ft);
            double rr = ridged(x / 1100, y / 1100, SD.coll + 9, detail ? 5 : 3);
            h += o.collK * P.collisionHeight
                * (pl * (105 + 25 * fbm(x / 1500, y / 1500, SD.coll + 11, 2)) + rg * (120 + 360 * rr * rr) - 12 * fore);
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
        double hf = gen.volcanism.apply(x, y, o, h, detail, s);
        // a coast landform buried by a later one (arc, collision range, volcano) loses its cover
        if (o.surf != GenPoint.SURF_NONE && Math.abs(hf - hCoast) > 1.5) o.surf = GenPoint.SURF_NONE;
        return hf;
    }
}
