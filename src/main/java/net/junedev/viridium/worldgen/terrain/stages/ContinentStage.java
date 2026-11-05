package net.junedev.viridium.worldgen.terrain.stages;

import static net.junedev.viridium.worldgen.terrain.core.GenMath.TAU;
import static net.junedev.viridium.worldgen.terrain.core.GenMath.hypot;
import static net.junedev.viridium.worldgen.terrain.core.GenMath.lerp;
import static net.junedev.viridium.worldgen.terrain.core.GenMath.ss;
import static net.junedev.viridium.worldgen.terrain.core.Noise.fbm;
import static net.junedev.viridium.worldgen.terrain.core.Noise.rnd;

import net.junedev.viridium.worldgen.terrain.WorldGen;
import net.junedev.viridium.worldgen.terrain.pipeline.GenPoint;

/**
 * 5/13. Continentality L: one continent per plate, shrinking towards non-collisional boundaries (ocean next to them)
 * and welded across collisions. Adds microcontinents and shelf islands.
 */
public class ContinentStage extends GenStage {

    public ContinentStage(WorldGen gen) {
        super(gen);
    }

    public void apply(double x, double y, GenPoint o) {
        double S = P.plateSize;
        int i = o.pi, j = o.pj;
        double cx = o.fx + (rnd(i, j, SD.cont) - 0.5) * 0.3 * S;
        double cy = o.fy + (rnd(i, j, SD.cont + 1) - 0.5) * 0.3 * S;
        double dc = hypot(o.wx - cx, o.wy - cy);
        double eN = o.edgeNC; // collision boundaries do not count: the continent extends until it meets its neighbour
        double c = eN / (eN + dc + 1e-6); // 1 at the continent centre, 0 at the plate boundary
        double size = P.continentSize + (rnd(i, j, SD.cont + 2) - 0.5) * 0.3;
        double n = 0.22 * fbm(x / 1100, y / 1100, SD.contN, 4) + 0.07 * fbm(x / 240, y / 240, SD.contN + 50, 3);
        double L = c - (1 - size) + n;
        // microcontinent (fragment split off by a rift: Madagascar, Zealandia...)
        if (rnd(i, j, SD.micro) < P.microChance) {
            double a = rnd(i, j, SD.micro + 1) * TAU, r = (0.3 + 0.13 * rnd(i, j, SD.micro + 2)) * S;
            double rm = (0.06 + 0.06 * rnd(i, j, SD.micro + 3)) * S;
            double dm = hypot(o.wx - (o.fx + Math.cos(a) * r), o.wy - (o.fy + Math.sin(a) * r));
            double Lm = 0.45 * (1 - dm / rm) + 0.9 * n;
            if (Lm > L) L = Lm;
        }
        // continental islands: noise bumps on the shelf (Great Britain, Japan...)
        if (P.islands > 0) {
            double w = ss(-0.5, -0.2, L) * (1 - ss(-0.02, 0.12, L));
            if (w > 0) L += w * P.islands * 0.75 * Math.max(0, fbm(x / 700, y / 700, SD.isl, 4) - 0.12);
        }
        L = Math.min(L, o.edge / (S * 0.12) - 0.5); // guarantees ocean next to the plate boundary...
        if (o.collZ > 0) L = lerp(L, 0.4 + 0.8 * n, ss(0, 0.6, o.collZ)); // ...except at collisions
        o.L = L;
    }
}
