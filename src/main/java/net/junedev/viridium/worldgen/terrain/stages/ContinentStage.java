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
        double bend = P.exactPlateEdges ? fbm(x / 1600, y / 1600, SD.contN + 90, 3) : 0; // bends coasts along boundaries
        if (bend != 0) eN = Math.max(0, eN + S * 0.04 * bend);
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
        // guarantees ocean next to the plate boundary... (bent by noise: the boundary is straight for thousands of blocks)
        double eB = o.edge + S * 0.04 * bend;
        L = Math.min(L, eB / (S * 0.12) - 0.5);
        if (o.collZ > 0) L = lerp(L, 0.4 + 0.8 * n, ss(0, 0.6, o.collZ + 0.25 * bend)); // ...except at collisions
        o.L = rift(x, y, i, j, cx, cy, L, o);
    }

    /**
     * Continental rifts (East Africa, Baikal, the Rhine graben, the Red Sea): a continent starts to split along a line
     * through its interior. The crust stretches and a graben sinks between two raised shoulders. Some continents (0.35
     * per unit of {@code rifts}) get one rift line, meandering, 0.5-0.9 plate sizes long, 600-960 blocks wide. Young
     * rifts hold long lakes, failed ones are worn down, mature ones have opened into a narrow sea (continentality
     * lowered inside the graben). Here only the geometry; ReliefStage digs the graben.
     *
     * @return continentality (lowered in a mature rift)
     */
    protected double rift(double x, double y, int i, int j, double cx, double cy, double L, GenPoint o) {
        o.rift = 0;
        o.riftD = 9;
        o.riftU = 0;
        o.riftType = 0;
        if (P.rifts <= 0 || rnd(i, j, SD.rift) >= 0.35 * P.rifts) return L;
        double S = P.plateSize;
        double a = rnd(i, j, SD.rift + 1) * Math.PI, ca = Math.cos(a), sa = Math.sin(a);
        double ox = cx + (rnd(i, j, SD.rift + 2) - 0.5) * 0.2 * S, oy = cy + (rnd(i, j, SD.rift + 4) - 0.5) * 0.2 * S;
        double dx = o.wx - ox, dy = o.wy - oy;
        double u = dx * ca + dy * sa, v = -dx * sa + dy * ca;
        v += 0.07 * S * fbm(u / (0.35 * S), 3.7, SD.rift + 5, 3); // meanders over a few thousand blocks
        double half = (0.25 + 0.2 * rnd(i, j, SD.rift + 6)) * S;
        double along = 1 - ss(0.7 * half, half, Math.abs(u));
        if (along <= 0) return L;
        double w = (0.025 + 0.015 * rnd(i, j, SD.rift + 7)) * S * (0.85 + 0.3 * fbm(u / 1500, 9.1, SD.rift + 8, 2));
        double r = rnd(i, j, SD.rift + 3);
        o.riftType = r < 0.45 ? 0 : r < 0.7 ? 1 : 2;
        o.rift = along;
        o.riftD = Math.abs(v) / w;
        o.riftU = u;
        if (o.riftType == 2 && o.riftD < 1.2) // mature: the floor of the graben is a narrow sea
            L = lerp(L, Math.min(L, -0.08), along * (1 - ss(0.55, 1.0, o.riftD)));
        return L;
    }
}
