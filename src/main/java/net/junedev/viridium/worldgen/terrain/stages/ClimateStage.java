package net.junedev.viridium.worldgen.terrain.stages;

import static net.junedev.viridium.worldgen.terrain.core.GenMath.band;
import static net.junedev.viridium.worldgen.terrain.core.GenMath.clamp;
import static net.junedev.viridium.worldgen.terrain.core.GenMath.hypot;
import static net.junedev.viridium.worldgen.terrain.core.GenMath.ss;

import net.junedev.viridium.worldgen.terrain.WorldGen;
import net.junedev.viridium.worldgen.terrain.pipeline.GenPoint;
import net.junedev.viridium.worldgen.terrain.pipeline.GenScratch;

/**
 * 8. Final climate. Wind = general circulation + thermal land/sea wind (monsoon, with Coriolis) + deflection by relief.
 * Ocean currents (cold on the west coast of continents in the subtropics, warm on the east coast; reversed at high
 * latitudes). Temperature lapse with height. Precipitation from how much land the wind crossed, orographic lift, rain
 * shadow and currents.
 */
public class ClimateStage extends GenStage {

    private static final double[] UPWIND = { 150, 400, 900, 1800 }, UPW = { 0.35, 0.3, 0.2, 0.15 };

    public ClimateStage(WorldGen gen) {
        super(gen);
    }

    /** Height sampled around a point by the climate. Override to read a cached coarse field instead. */
    protected double coarseHeight(double x, double y, GenScratch s) {
        return gen.heightAt(x, y, false);
    }

    /** Continentality of the last {@link #coarseHeight} call, as land fraction. */
    protected double lastLand(GenScratch s) {
        return ss(-0.15, 0.25, s.heightPoint.L);
    }

    protected void wind(double x, double y, GenPoint o, GenScratch s) {
        final double E = 800;
        double hE = Math.max(0, coarseHeight(x + E, y, s)), lE = lastLand(s);
        double hW = Math.max(0, coarseHeight(x - E, y, s)), lW = lastLand(s);
        double hS = Math.max(0, coarseHeight(x, y + E, s)), lS = lastLand(s);
        double hN = Math.max(0, coarseHeight(x, y - E, s)), lN = lastLand(s);
        o.lE = lE;
        o.lW = lW;
        double wx = o.windX, wy = o.windY;
        // warm land = low pressure → air flows in from the sea; cold land = high pressure → flows out
        double f = clamp((o.Tb - 8) / 16, -1, 1) * P.monsoon * 0.55;
        double tx = (lE - lW) * f, ty = (lS - lN) * f;
        double ang = 0.45 * o.pole, ca = Math.cos(ang), sa = Math.sin(ang); // Coriolis deflection
        double rtx = tx * ca - ty * sa, rty = tx * sa + ty * ca;
        wx += rtx;
        wy += rty;
        o.thermal = hypot(rtx, rty);
        // relief slows the upslope component and deflects the flow along the range
        double gx = (hE - hW) / (2 * E), gy = (hS - hN) / (2 * E), gm = hypot(gx, gy);
        o.deflect = 0;
        if (gm > 1e-4) {
            double ux = gx / gm, uy = gy / gm, up = wx * ux + wy * uy;
            if (up > 0) {
                double k = 0.85 * ss(0.01, 0.08, gm);
                wx -= ux * up * k;
                wy -= uy * up * k;
                o.deflect = up * k;
            }
        }
        o.wfx = wx;
        o.wfy = wy;
    }

    /** Land fraction at a point (plates + continents only). */
    protected double landAt(double x, double y, GenScratch s) {
        GenPoint p = s.landPoint;
        gen.plates.apply(x, y, p, s);
        gen.continents.apply(x, y, p);
        return ss(-0.15, 0.25, p.L);
    }

    protected void currents(double x, double y, GenPoint o, GenScratch s) {
        o.sst = 0;
        o.upwell = 0;
        o.curX = 0;
        o.curY = 0;
        if (P.currents <= 0) return;
        double lat = o.lat * 57.2958;
        double sub = band(lat, 12, 40, 3), subp = band(lat, 44, 68, 3);
        double here = ss(-0.15, 0.25, o.L);
        double lE2 = landAt(x + 2000, y, s), lW2 = landAt(x - 2000, y, s);
        double lE3 = landAt(x + 3600, y, s), lW3 = landAt(x - 3600, y, s);
        double toE = Math.max(o.lE, Math.max(0.8 * lE2, 0.55 * lE3)),
            toW = Math.max(o.lW, Math.max(0.8 * lW2, 0.55 * lW3));
        // eastern edge of the ocean = WEST coast of a continent (California, Peru, Namibia)
        double eb = (1 - here) * toE * (1 - 0.5 * toW) + here * (1 - o.lW);
        // western edge of the ocean = EAST coast of a continent (Florida, Japan, Brazil)
        double wb = (1 - here) * toW * (1 - 0.5 * toE) + here * (1 - o.lE);
        o.sst = P.currents * (sub * (-6 * eb + 3.5 * wb) + subp * (3 * eb - 3 * wb));
        o.upwell = P.currents * sub * eb;
        o.curX = 0.5 * -Math.sin(6 * o.lat) * (1 - here);
        o.curY = o.pole * 0.8 * (sub * (wb - eb) + subp * (eb - wb)) * (1 - here);
    }

    public void apply(double x, double y, GenPoint o, GenScratch s) {
        double h = o.h0, hp = Math.max(0, h);
        wind(x, y, o, s);
        currents(x, y, o, s);
        o.T = o.Tb - P.lapse * hp + (h < 0 ? 1 : 0.7) * o.sst;
        o.shadow = 0;
        o.oro = 0;
        o.upLand = 0;
        if (h < 0) {
            o.P = o.Pb;
            return;
        }
        double inland = Math.exp(-Math.max(0, o.L) / 0.4);
        double wl = hypot(o.wfx, o.wfy);
        double p = o.Pb * (0.4 + 0.6 * inland);
        if (wl > 0.05) {
            double ux = o.wfx / wl, uy = o.wfy / wl, ws = Math.min(1, wl);
            double maxUp = 0, near = 0, land = 0;
            for (int k = 0; k < UPWIND.length; k++) {
                double hu = coarseHeight(x - ux * UPWIND[k], y - uy * UPWIND[k], s);
                land += UPW[k] * ss(-3, 10, hu);
                double hc = Math.max(0, hu);
                if (k == 0) near = hc;
                if (hc > maxUp) maxUp = hc;
            }
            o.upLand = land;
            // moisture: wind from the sea brings water; wind that crossed a lot of land arrives dry
            double moist = 0.45 * inland + 0.55 * (1 - land * ws);
            p = o.Pb * (0.35 + 0.65 * moist);
            double shadow = clamp((maxUp - hp - 25) / 150, 0, 1) * ws; // rain shadow
            double oro = clamp((hp - near) / 100 * 3, 0, 1) * ws; // orographic lift
            p *= (1 - 0.75 * shadow) * (1 + 0.9 * oro);
            o.shadow = shadow;
            o.oro = oro;
        }
        // cold current coast: stable air, fog and almost no rain (Atacama, Namib); warm current: more rain
        p *= (1 - 0.85 * ss(0.5, 5, -o.sst) - 0.3 * o.upwell) * (1 + 0.35 * ss(0.5, 4, o.sst));
        o.P = Math.max(15, p);
    }
}
