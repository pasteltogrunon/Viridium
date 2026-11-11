package net.junedev.viridium.worldgen.terrain.stages;

import static net.junedev.viridium.worldgen.terrain.core.GenMath.TAU;
import static net.junedev.viridium.worldgen.terrain.core.GenMath.clamp;
import static net.junedev.viridium.worldgen.terrain.core.GenMath.floor;
import static net.junedev.viridium.worldgen.terrain.core.GenMath.lerp;
import static net.junedev.viridium.worldgen.terrain.core.GenMath.ss;
import static net.junedev.viridium.worldgen.terrain.core.Noise.fbm;
import static net.junedev.viridium.worldgen.terrain.core.Noise.rnd;

import net.junedev.viridium.worldgen.terrain.WorldGen;
import net.junedev.viridium.worldgen.terrain.pipeline.GenPoint;
import net.junedev.viridium.worldgen.terrain.pipeline.GenScratch;

/**
 * 9. Stateless erosion ("eroded terrain noise", Clay John): sum of waves whose fronts follow the slope, branching
 * between octaves. Depth â rock erodibility Ã f(precipitation, temperature). Only digs.
 */
public class ErosionStage extends GenStage {

    private static final int OCTAVES = 4;

    /**
     * Per-thread jitter of the 4x4 gully cells last used by each octave. Neighbouring points nearly always fall in the
     * same cells (12 to 96 blocks wide), so the 32 hashes per octave are reused; values depend only on the cell.
     */
    protected static final class Jitter {

        final int[] ix = new int[OCTAVES], iy = new int[OCTAVES];
        final boolean[] valid = new boolean[OCTAVES];
        final double[][] jx = new double[OCTAVES][16], jy = new double[OCTAVES][16];
    }

    private final ThreadLocal<Jitter> jitter = ThreadLocal.withInitial(Jitter::new);

    public ErosionStage(WorldGen gen) {
        super(gen);
    }

    /** Writes value and gradient into {@code s.EV, s.EGX, s.EGY}. {@code oc} picks the octave's slot in {@code jt}. */
    protected void gullies(double px, double py, double dx, double dy, Jitter jt, int oc, GenScratch s) {
        int ix = floor(px), iy = floor(py);
        double fx = px - ix, fy = py - iy;
        double[] jx = jt.jx[oc], jy = jt.jy[oc];
        if (!jt.valid[oc] || jt.ix[oc] != ix || jt.iy[oc] != iy) {
            for (int j = -2, c = 0; j <= 1; j++) for (int i = -2; i <= 1; i++, c++) {
                int cx = ix - i, cy = iy - j;
                jx[c] = rnd(cx, cy, SD.ero) - 0.5;
                jy[c] = rnd(cx, cy, SD.ero + 1) - 0.5;
            }
            jt.ix[oc] = ix;
            jt.iy[oc] = iy;
            jt.valid[oc] = true;
        }
        double va = 0, vx = 0, vy = 0, wt = 0;
        for (int j = -2, c = 0; j <= 1; j++) {
            for (int i = -2; i <= 1; i++, c++) {
                double ppx = fx + i - jx[c];
                double ppy = fy + j - jy[c];
                double w = Math.exp(-2 * (ppx * ppx + ppy * ppy));
                wt += w;
                double m = (ppx * dx + ppy * dy) * TAU;
                // sin and cos of m together: quadrant reduction to |r| <= pi/4, then Taylor polynomials (error < 2e-9,
                // far below a block; plain arithmetic, so also identical on every JVM unlike Math.sin/cos)
                double q = Math.rint(m * (2 / Math.PI));
                double r = m - q * (Math.PI / 2), r2 = r * r;
                double sr = r * (1 + r2 * (-1.0 / 6 + r2 * (1.0 / 120 + r2 * (-1.0 / 5040 + r2 * (1.0 / 362880)))));
                double cr = 1
                    + r2 * (-0.5 + r2 * (1.0 / 24 + r2 * (-1.0 / 720 + r2 * (1.0 / 40320 + r2 * (-1.0 / 3628800)))));
                double cos, sin;
                switch ((int) q & 3) {
                    case 0:
                        cos = cr;
                        sin = sr;
                        break;
                    case 1:
                        cos = -sr;
                        sin = cr;
                        break;
                    case 2:
                        cos = -cr;
                        sin = -sr;
                        break;
                    default:
                        cos = sr;
                        sin = -cr;
                }
                va += cos * w;
                double sn = -sin * w;
                vx += sn * dx;
                vy += sn * dy;
            }
        }
        s.EV = va / wt;
        s.EGX = vx / wt;
        s.EGY = vy / wt;
    }

    /** Gradient of the smoothed relief around (x, y). Override to read a cached field. */
    protected void reliefGradient(double x, double y, GenPoint o, double bpp, GenScratch s) {
        final double E = 12;
        o.gx = (gen.heightAt(x + E, y, false) - gen.heightAt(x - E, y, false)) / (2 * E);
        o.gy = (gen.heightAt(x, y + E, false) - gen.heightAt(x, y - E, false)) / (2 * E);
    }

    /**
     * Mesas, buttes and ledges: in arid land on flat-lying sedimentary rock a hard bed protects the softer ones below,
     * and
     * slopes retreat as cliffs separated by benches (Monument Valley, the Colorado Plateau). The height is terraced in
     * 12-block steps with short steep risers, only on slopes (flats stay flat) and on sandstone, limestone or shale.
     */
    protected void mesas(GenPoint o, double g) {
        o.mesa = 0;
        if (P.mesas <= 0) return;
        double arid = ss(520, 260, o.P) * ss(-2, 6, o.T);
        if (arid <= 0) return;
        double sed = sedimentary(o.rock) * o.rockT + sedimentary(o.rock2) * (1 - o.rockT);
        double w = Math.min(1, P.mesas * arid * sed * ss(0.12, 0.35, g) * ss(4, 18, o.h0));
        if (w <= 0) return;
        o.mesa = w;
        final double S = 12;
        double q = o.h / S, fl = Math.floor(q);
        o.h += w * ((fl + ss(0.78, 0.96, q - fl)) * S - o.h);
    }

    private static double sedimentary(int rock) {
        return rock == Rock.SANDSTONE.ordinal() ? 1
            : rock == Rock.LIMESTONE.ordinal() ? 0.8 : rock == Rock.SHALE.ordinal() ? 0.5 : 0;
    }

    /**
     * Climate and rock of the erosion at a point (Java-only; {@code erosionClimate = 0} keeps the prototype's filter).
     * <ul>
     * <li>humid temperate: soil-mantled slopes, rounded crests, valleys of moderate depth;</li>
     * <li>humid tropical: the deepest and steepest dissection, knife-edge ridges and V valleys (Na Pali, Rio);</li>
     * <li>arid: little erosion on hard rock, angular crests, flat sandy wash floors; on soft rock badlands, a dense
     * network of rills with sharp crests;</li>
     * <li>cold: frost shattering, jagged crests and couloirs.</li>
     * </ul>
     * Writes depth multiplier, crest exponent, octave gain and valley fill into {@code s.EC*}.
     */
    protected void climateStyle(GenPoint o, GenScratch s) {
        double p = o.P, t = o.T;
        double wet = ss(300, 1500, p), arid = ss(550, 220, p);
        double frost = ss(3, -8, t);
        double trop = ss(17, 24, t) * ss(1100, 1900, p);
        double soft = ss(0.45, 0.85, o.erod);
        s.ECD = (0.72 + 0.3 * wet + 0.45 * trop + 0.15 * frost - 0.35 * arid * (1 - soft) + 0.3 * arid * soft)
            * lerp(0.62, 1.2, ss(0.2, 0.9, o.erod));
        s.ECP = clamp(1.35 - 0.6 * frost - 0.55 * arid - 0.5 * trop - 0.2 * arid * soft, 0.55, 1.4);
        s.ECG = P.erosionGain + 0.15 * arid * soft + 0.05 * frost;
        s.ECF = clamp(0.06 + 0.3 * arid * (1 - 0.5 * soft) + 0.08 * wet * (1 - trop), 0, 0.4);
    }

    /**
     * Submarine canyons: turbidity currents and slumps cut the continental slope into steep gullies and canyons, the
     * largest running up into the shelf edge (Monterey, Hudson, Congo canyons). The same gully filter, on the slope
     * between the shelf edge (-14) and the abyssal plain only. Weight of the filter at base height h0.
     */
    /**
     * Young volcanoes keep their cone: a stratovolcano, a cinder cone or a lava flow has not had the time to be carved
     * into
     * spurs and ravines (only shallow barrancos). Share of the gully depth they lose.
     */
    protected static double youngVolcano(GenPoint o) {
        if (o.volc <= 0.05) return 0;
        double k = o.volcType == 1 || o.volcType == 6 ? 0.75 : o.volcType == 2 ? 0.4 : 0;
        return k * ss(0.05, 0.5, o.volc);
    }

    protected double seaCanyons(double h0) {
        if (P.seaCanyons <= 0 || h0 > -12) return 0;
        return 0.9 * P.seaCanyons * ss(-12, -24, h0) * ss(-72, -54, h0);
    }

    public void apply(double x, double y, GenPoint o, double bpp, GenScratch s) {
        double h0 = o.h0;
        reliefGradient(x, y, o, bpp, s);
        double gx = o.gx, gy = o.gy;
        double g = Math.sqrt(gx * gx + gy * gy);
        o.slope = g;
        boolean climate = P.erosionClimate > 0;
        if (climate) climateStyle(o, s);
        double Lg = P.erosionScale * (climate ? P.erosionWidth : 1), BRANCH = climate ? P.erosionBranch : 1.0,
            GAIN = climate ? s.ECG : 0.45;
        // wave frequency â slope (0 on flats â no gullies), saturated to avoid noise
        double freq = Math.min(g * 2.5, 1.1), inv = g > 1e-6 ? freq / g : 0;
        double dX = gy * inv, dY = -gx * inv; // perpendicular to the gradient
        double hv = 0, hdx = 0, hdy = 0, a = 0.5, f = 1, sumA = 0;
        Jitter jt = jitter.get();
        for (int oc = 0; oc < OCTAVES; oc++) {
            double fade = ss(bpp * 1.2, bpp * 3, Lg / f); // anti-aliasing when zoomed out
            if (fade <= 0) break;
            gullies(x * f / Lg, y * f / Lg, dX + hdy * BRANCH, dY - hdx * BRANCH, jt, oc, s);
            double af = a * fade;
            hv += s.EV * af;
            hdx += s.EGX * af * f;
            hdy += s.EGY * af * f;
            sumA += af;
            a *= GAIN;
            f *= 2;
        }
        if (climate) {
            // depth of the dissection â relief: steep mountainsides get gullies tens of blocks deep, hills a few
            // blocks,
            // plains none. e: 0 on the crests, 1 at the gully floors; shaped by the climate (crest sharpness, valley
            // fill)
            double e = sumA > 0 ? clamp(0.5 * (1 - hv / sumA), 0, 1) : 0;
            e = Math.pow(e, s.ECP);
            double vf = 1 - s.ECF; // valley floors filled with sediment: a smooth minimum flattens them
            e = 0.5 * (e + vf - Math.sqrt((e - vf) * (e - vf) + 0.004));
            double amount = P.erosionStrength * P.erosionClimate
                * s.ECD
                * P.erosionDepth
                * Lg
                * Math.min(g, 1.0)
                * (ss(-8, 6, h0) + seaCanyons(h0))
                * (1 - o.scarp)
                * (1 - youngVolcano(o));
            amount = Math.min(amount, 55); // the deepest ravines; deeper ones cut below the rivers next to them
            double hE = h0 - e * amount;
            if (h0 > 0 && hE < 1) hE = Math.max(hE, Math.min(h0, 1)); // the sea is the base level: land is never dug
                                                                      // below it
            o.eroDelta = hE - h0;
            o.h = hE;
            mesas(o, g);
            deserts(x, y, o, g);
            return;
        }
        double raw = (hv - sumA) * 0.5; // â [-sumA, 0]: only digs
        double tN = o.T / 7;
        double clim = (0.3 + clamp(o.P / 1500, 0, 1.5)) * (1 + 0.6 * Math.exp(-tN * tN)); // rain + freeze/thaw
        double amount = P.erosionStrength * 32 * o.erod * clim * ss(-8, 6, h0) * (1 - o.scarp);
        o.eroDelta = raw * amount;
        o.h = h0 + o.eroDelta;
        mesas(o, g);
    }

    // ------------------------------------------------------------------ deserts: dunes, yardangs, hoodoos, pavement

    /**
     * Desert landforms, on the eroded terrain (they need the final climate and wind):
     * <ul>
     * <li><b>dune seas</b> (ergs) on the low, flat ground of dry climates, a fifth to a third of the desert: the dune
     * type
     * follows the wind and the sand supply, as in reality. One steady wind and plenty of sand: transverse ridges across
     * the wind with a gentle windward slope and a steep slip face (angle of repose); little sand: isolated barchans,
     * crescents with their horns downwind, on a gravel floor; two seasonal winds: linear (seif) dunes along the wind,
     * tens of kilometres long (Namib, Simpson); changing winds: star dunes, pyramids 20-35 blocks high (Grand Erg
     * Oriental);</li>
     * <li><b>yardangs</b>: streamlined ridges carved by the wind in soft rock where there is no sand to cover it (Lut,
     * Qaidam);</li>
     * <li><b>hoodoos</b>: thin pillars left standing on the steep slopes of dry soft rock (Bryce Canyon,
     * Cappadocia);</li>
     * <li><b>desert pavement</b> (reg, hamada) everywhere else in the desert: most of a real desert is gravel and rock,
     * not sand.</li>
     * </ul>
     */
    protected void deserts(double x, double y, GenPoint o, double g) {
        if (o.h0 < 1.5 || o.h < 1) return;
        double arid = ss(330, 140, o.P) * ss(-6, 2, o.T);
        if (arid <= 0) return;
        double flat = ss(0.22, 0.06, g) * ss(160, 90, o.h0);
        double soft = ss(0.5, 0.75, o.erod);
        // the erg: a regional sand sea on flat dry ground
        double ergN = fbm(x / 6500, y / 6500, SD.ero + 30, 3);
        double erg = P.dunes > 0 ? arid * flat * ss(-0.05, 0.25, ergN + 0.15 * (P.dunes - 1)) * (1 - o.mesa) : 0;
        double add = 0;
        if (erg > 0.02) {
            double supply = ss(-0.05, 0.45, ergN); // the heart of the erg holds the most sand
            double regime = fbm(x / 9000, y / 9000, SD.ero + 31, 2);
            double wStar = ss(-0.15, -0.3, regime), wLin = ss(-0.3, -0.15, regime) * ss(0.25, 0.1, regime),
                wTrans = ss(0.1, 0.25, regime);
            double wr = wStar + wLin + wTrans;
            wStar /= wr;
            wLin /= wr;
            wTrans /= wr;
            double warp = 0.6 * fbm(x / 320, y / 320, SD.ero + 32, 2);
            // The dunes are laid out in the frame of the wind. A frame that turned from point to point would twist the
            // pattern around the world's origin, so each 3 km cell has one wind direction (the general circulation at
            // its centre) and the four cells around the point are blended.
            final double DC = 3000;
            double gx = x / DC - 0.5, gy = y / DC - 0.5;
            int i0 = (int) Math.floor(gx), j0 = (int) Math.floor(gy);
            double fx = gx - i0, fy = gy - j0;
            for (int c = 0; c < 4; c++) {
                int ci = i0 + (c & 1), cj = j0 + (c >> 1);
                double wgt = ((c & 1) == 1 ? fx : 1 - fx) * ((c >> 1) == 1 ? fy : 1 - fy);
                if (wgt <= 1e-3) continue;
                double cx = (ci + 0.5) * DC, cy = (cj + 0.5) * DC;
                double[] w = cellWind(cx, cy);
                double ux = w[0], uy = w[1];
                double px = (x - cx) * ux + (y - cy) * uy, py = -(x - cx) * uy + (y - cy) * ux; // along, across the
                                                                                                // wind
                double d = 0;
                if (wTrans > 0) {
                    if (supply > 0.35) { // transverse ridges, crestlines sinuous
                        double u = px / 55 + warp + 0.2 * fbm(x / 90, y / 90, SD.ero + 33, 2), fr = u - Math.floor(u);
                        double f = fr < 0.78 ? Math.pow(fr / 0.78, 1.3) : 1 - (fr - 0.78) / 0.22;
                        d += wTrans * ss(0.35, 0.6, supply) * (4 + 6 * supply) * f;
                    }
                    if (supply < 0.6) d += wTrans * ss(0.6, 0.35, supply) * barchans(ci, cj, px, py);
                }
                if (wLin > 0) { // linear dunes along the wind, merging in Y junctions
                    double v = py / 110 + 0.8 * warp + 0.15 * fbm(px / 900, py / 400, SD.ero + 34, 2),
                        fr = v - Math.floor(v);
                    double f = Math.pow(1 - Math.abs(2 * fr - 1), 1.6) * (0.75 + 0.25 * Math.sin(px / 85 + 3 * warp));
                    d += wLin * (9 + 10 * supply) * f;
                }
                add += wgt * d;
            }
            if (wStar > 0) add += wStar * starDunes(x, y, supply);
            add *= erg;
            if (add > 0.4 || supply > 0.4 && erg > 0.4) o.surf = GenPoint.SURF_DUNE;
        }
        // yardangs: soft rock, no sand to cover it, the wind's own hulls
        if (P.yardangs > 0 && soft > 0 && erg < 0.3) {
            double yz = arid * soft * flat * (1 - erg) * ss(0.2, 0.45, fbm(x / 5000, y / 5000, SD.ero + 35, 2));
            if (yz > 0.02) {
                final double DC = 3000;
                double cx = (Math.floor(x / DC) + 0.5) * DC, cy = (Math.floor(y / DC) + 0.5) * DC;
                double[] w = cellWind(cx, cy);
                double px = (x - cx) * w[0] + (y - cy) * w[1], py = -(x - cx) * w[1] + (y - cy) * w[0];
                double v = py / 22 + 0.3 * fbm(x / 150, y / 150, SD.ero + 36, 2), fr = v - Math.floor(v);
                double lane = Math.floor(v), u = px / 140 + rnd((int) lane, 7, SD.ero + 37), t = u - Math.floor(u);
                double hull = Math.pow(Math.sin(Math.PI * Math.min(1, t * 1.15)), 0.6); // blunt upwind, tapering
                                                                                        // downwind
                double ridge = Math.pow(1 - Math.abs(2 * fr - 1), 0.7) * hull;
                o.h -= yz * (6 + 6 * soft) * (1 - ridge);
                if (yz > 0.3) o.surf = GenPoint.SURF_NONE;
            }
        }
        if (add > 0) o.h += add;
        // hoodoos on the steep slopes of dry soft rock (and of the mesas)
        if (P.hoodoos > 0 && g > 0.25 && (soft > 0.3 || o.mesa > 0.2)) {
            double hz = arid * Math.max(soft, o.mesa)
                * ss(0.25, 0.55, g)
                * ss(0.0, 0.3, fbm(x / 2500, y / 2500, SD.ero + 38, 2));
            if (hz > 0.05) {
                final double C = 7;
                int ci = (int) Math.floor(x / C), cj = (int) Math.floor(y / C);
                double top = 0;
                for (int dj = -1; dj <= 1; dj++) for (int di = -1; di <= 1; di++) {
                    int a = ci + di, b = cj + dj;
                    if (rnd(a, b, SD.ero + 39) > 0.5 * hz * Math.min(P.hoodoos, 2)) continue;
                    double cx = (a + 0.2 + 0.6 * rnd(a, b, SD.ero + 40)) * C,
                        cy = (b + 0.2 + 0.6 * rnd(a, b, SD.ero + 41)) * C;
                    double r = Math.hypot(x - cx, y - cy), R = 1.1 + 1.4 * rnd(a, b, SD.ero + 42);
                    if (r < R) top = Math.max(top, (4 + 9 * rnd(a, b, SD.ero + 43)) * ss(R, R - 0.6, r));
                }
                if (top > 0) {
                    o.h += top;
                    o.scarp = 1;
                }
            }
        }
        // desert pavement on the rest of the dry land
        if (P.desertPavement > 0 && o.surf == GenPoint.SURF_NONE && arid > 0.5 && g < 0.45 && o.h0 < 140)
            o.surf = GenPoint.SURF_REG;
    }

    private final ThreadLocal<GenPoint> windPoint = ThreadLocal.withInitial(GenPoint::new);

    /** Unit vector of the general circulation wind at (x, y) (the base climate: cheap, smooth). */
    private double[] cellWind(double x, double y) {
        GenPoint p = windPoint.get();
        gen.climate.apply(x, y, p);
        double l = Math.hypot(p.windX, p.windY);
        return l > 1e-6 ? new double[] { p.windX / l, p.windY / l } : new double[] { 1, 0 };
    }

    /** Barchans: crescents 20-44 blocks across, horns pointing downwind, one per 90-block cell (60 %). */
    private double barchans(int fi, int fj, double px, double py) {
        final double C = 90;
        int ci = (int) Math.floor(px / C), cj = (int) Math.floor(py / C);
        double best = 0;
        for (int dj = -1; dj <= 1; dj++) for (int di = -1; di <= 1; di++) {
            int a = ci + di + fi * 7919, b = cj + dj + fj * 104729; // each wind cell its own dunes
            if (rnd(a, b, SD.ero + 44) > 0.6) continue;
            double cu = (ci + di + 0.2 + 0.6 * rnd(a, b, SD.ero + 45)) * C,
                cv = (cj + dj + 0.2 + 0.6 * rnd(a, b, SD.ero + 46)) * C;
            double R = 10 + 12 * rnd(a, b, SD.ero + 47), H = 0.4 * R;
            double u = px - cu, v = py - cv; // u downwind
            double eu = u > 0 ? u / (1.25 * R) : u / R, body = 1 - (eu * eu + (v / R) * (v / R));
            if (body <= 0) continue;
            // the slip face: a concave cut downwind of the crest, curving back so the sides run on as horns
            double uc = 0.05 * R + 0.9 * v * v / R, cut = ss(uc + 0.2 * R, uc, u);
            double hv = H * Math.sqrt(body) * cut;
            if (hv > best) best = hv;
        }
        return best;
    }

    /** Star dunes: pyramids with three or more arms, one per 260-block cell. */
    private double starDunes(double x, double y, double supply) {
        final double C = 260;
        int ci = (int) Math.floor(x / C), cj = (int) Math.floor(y / C);
        double best = 0;
        for (int dj = -1; dj <= 1; dj++) for (int di = -1; di <= 1; di++) {
            int a = ci + di, b = cj + dj;
            double cx = (a + 0.25 + 0.5 * rnd(a, b, SD.ero + 48)) * C,
                cy = (b + 0.25 + 0.5 * rnd(a, b, SD.ero + 49)) * C;
            double dx = x - cx, dy = y - cy, r = Math.hypot(dx, dy), R = 120 + 50 * rnd(a, b, SD.ero + 50);
            if (r >= R) continue;
            double th = Math.atan2(dy, dx) + TAU * rnd(a, b, SD.ero + 51);
            double arms = 0.55 + 0.45 * Math.pow(Math.abs(Math.cos(1.5 * th)), 3); // three arms
            double q = r / (R * arms);
            if (q >= 1) continue;
            double hv = (18 + 17 * supply) * Math.pow(1 - q, 1.6);
            if (hv > best) best = hv;
        }
        return best;
    }
}
