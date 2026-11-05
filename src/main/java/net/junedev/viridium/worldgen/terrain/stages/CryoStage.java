package net.junedev.viridium.worldgen.terrain.stages;

import static net.junedev.viridium.worldgen.terrain.core.GenMath.floor;
import static net.junedev.viridium.worldgen.terrain.core.GenMath.ss;
import static net.junedev.viridium.worldgen.terrain.core.Noise.fbm;
import static net.junedev.viridium.worldgen.terrain.core.Noise.perlin;
import static net.junedev.viridium.worldgen.terrain.core.Noise.rnd;

import net.junedev.viridium.worldgen.terrain.WorldGen;
import net.junedev.viridium.worldgen.terrain.pipeline.GenPoint;

/**
 * 11. Cryosphere: floating ice whose density and floe size grow towards the poles, tabular icebergs, ice shelves next
 * to very cold coasts, frozen lakes, permafrost (discontinuous below ≈ −2 °C, continuous below ≈ −8 °C).
 */
public class CryoStage extends GenStage {

    public CryoStage(WorldGen gen) {
        super(gen);
    }

    public void apply(double x, double y, GenPoint o, double bpp) {
        o.seaIce = 0;
        o.berg = 0;
        o.perma = 0;
        o.iceFrac = 0;
        double T = o.T;
        o.hs = o.lake == 1 ? o.lakeZ : o.h;
        if (o.lake == 1) { // frozen lake
            if (T < -2) {
                o.seaIce = 3;
                o.iceFrac = 1;
            }
            return;
        }
        if (o.h >= 0) {
            if (o.river == 0) o.perma = ss(-1, -9, T);
            return;
        }
        if (o.river != 0 || T > 1) return;
        double t = T + 2.5 * fbm(x / 1400, y / 1400, SD.ice, 2);
        // ice shelf: the ice cap keeps floating on the sea next to very cold coasts
        if (ss(-18, -24, t) * ss(-0.32, -0.12, o.L) > 0.5) {
            o.seaIce = 2;
            o.iceFrac = 1;
            o.hs = 3;
            return;
        }
        double d = ss(0, -18, t); // floating ice density: 0 at ~0 °C, 1 near the pole
        if (d <= 0) return;
        if (bpp > 6) { // far view: mean fraction
            o.iceFrac = Math.min(1, 1.15 * d * d + 0.15 * d);
            return;
        }
        // floes: one per 36-block cell, more likely and larger towards the pole
        final int F = 36;
        int ci = floor(x / F), cj = floor(y / F);
        for (int dj = -1; dj <= 1 && o.seaIce == 0; dj++) for (int di = -1; di <= 1; di++) {
            int i = ci + di, j = cj + dj;
            if (rnd(i, j, SD.ice + 5) > 0.15 + 0.85 * d) continue;
            double cx = (i + 0.5 + (rnd(i, j, SD.ice + 3) - 0.5) * 0.5) * F,
                cy = (j + 0.5 + (rnd(i, j, SD.ice + 4) - 0.5) * 0.5) * F;
            double R = F * (0.12 + (0.22 + 0.4 * d) * rnd(i, j, SD.ice + 6));
            double a = rnd(i, j, SD.ice + 7) * Math.PI, ca = Math.cos(a), sa = Math.sin(a);
            double lx = ((x - cx) * ca + (y - cy) * sa) / R,
                ly = (-(x - cx) * sa + (y - cy) * ca) / (R * (0.55 + 0.45 * rnd(i, j, SD.ice + 8)));
            if (lx * lx + ly * ly + 0.35 * perlin(x / 7, y / 7, SD.ice + 9) < 1) {
                o.seaIce = 1;
                o.iceFrac = 1;
                o.hs = 1;
                break;
            }
        }
        // icebergs calved from glaciers: tabular, more frequent towards the pole
        if (o.h < -4) {
            final int B = 150;
            int bi = floor(x / B), bj = floor(y / B);
            for (int dj = -1; dj <= 1; dj++) for (int di = -1; di <= 1; di++) {
                int i = bi + di, j = bj + dj;
                if (rnd(i, j, SD.berg) > 0.08 + 0.5 * d) continue;
                double cx = (i + 0.2 + 0.6 * rnd(i, j, SD.berg + 1)) * B,
                    cy = (j + 0.2 + 0.6 * rnd(i, j, SD.berg + 2)) * B;
                double a = rnd(i, j, SD.berg + 3) * Math.PI, ca = Math.cos(a), sa = Math.sin(a);
                double R = 5 + (10 + 12 * d) * rnd(i, j, SD.berg + 4), el = 0.5 + 0.5 * rnd(i, j, SD.berg + 5);
                double lx = ((x - cx) * ca + (y - cy) * sa) / R, ly = (-(x - cx) * sa + (y - cy) * ca) / (R * el);
                double dd = Math.pow(Math.abs(lx), 3) + Math.pow(Math.abs(ly), 3)
                    + 0.25 * perlin(x / 6, y / 6, SD.berg + 6);
                if (dd < 1) {
                    double top = 4 + 16 * rnd(i, j, SD.berg + 7);
                    if (top > o.berg) {
                        o.berg = top;
                        o.hs = top * ss(1, 0.6, dd);
                        o.iceFrac = 1;
                    }
                }
            }
        }
    }
}
