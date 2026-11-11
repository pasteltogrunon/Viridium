package net.junedev.viridium.worldgen.terrain.stages;

import static net.junedev.viridium.worldgen.terrain.core.GenMath.TAU;
import static net.junedev.viridium.worldgen.terrain.core.GenMath.clamp;
import static net.junedev.viridium.worldgen.terrain.core.Noise.fbm;

import net.junedev.viridium.worldgen.terrain.WorldGen;
import net.junedev.viridium.worldgen.terrain.pipeline.GenPoint;

/**
 * 1-2. Base climate: temperature varies periodically along z (equator ↔ pole), precipitation along x. Also derives an
 * equivalent latitude and the general circulation surface wind (trades, westerlies, polar easterlies).
 */
public class ClimateBaseStage extends GenStage {

    public ClimateBaseStage(WorldGen gen) {
        super(gen);
    }

    public void apply(double x, double y, GenPoint o) {
        double ct = Math.cos(TAU * y / P.tempPeriod);
        o.Tb = 2 + 28 * ct + 3.5 * fbm(x / 3000, y / 3000, SD.temp, 3);
        o.Pb = Math
            .max(40, 1250 + 1100 * Math.cos(TAU * x / P.precipPeriod) + 300 * fbm(x / 2500, y / 2500, SD.prec, 3));
        double lat = Math.acos(clamp(ct, -1, 1)) * 0.5;
        o.lat = lat;
        double st = Math.sin(TAU * y / P.tempPeriod);
        o.pole = st / (Math.abs(st) + 0.08);
        double s6 = Math.sin(6 * lat);
        o.windX = -s6 + 0.2 * fbm(x / 5000, y / 5000, SD.wind, 2);
        o.windY = -0.45 * s6 * o.pole + 0.3 * fbm(x / 4000 + 9.1, y / 4000, SD.wind + 1, 2);
        o.wfx = o.windX;
        o.wfy = o.windY;
        o.T = o.Tb;
        o.P = o.Pb;
    }
}
