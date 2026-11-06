package net.junedev.viridium.worldgen.terrain.stages;

import static net.junedev.viridium.worldgen.terrain.core.GenMath.floor;
import static net.junedev.viridium.worldgen.terrain.core.GenMath.lerp;
import static net.junedev.viridium.worldgen.terrain.core.GenMath.ss;
import static net.junedev.viridium.worldgen.terrain.core.Noise.fbm;
import static net.junedev.viridium.worldgen.terrain.core.Noise.rnd;

import net.junedev.viridium.worldgen.terrain.WorldGen;
import net.junedev.viridium.worldgen.terrain.pipeline.GenPoint;
import net.junedev.viridium.worldgen.terrain.pipeline.GenScratch;

/**
 * 6. Surface geology: warped Voronoi patches. Basalt on the sea floor and ridges, andesite/tuff in arcs,
 * gneiss/granite/slate/marble in collision cores, limestone on atolls; a weighted pick elsewhere. Erodibility is a
 * smooth blend of nearby patches so erosion leaves no steps where the rock changes.
 */
public class GeologyStage extends GenStage {

    public GeologyStage(WorldGen gen) {
        super(gen);
    }

    protected int rockFor(int i, int j, GenPoint o) {
        double r = rnd(i, j, SD.rock);
        if (o.volc > 0.15 && o.h0 > -40) { // volcanic islands
            if (o.volcType == 1)
                return (r < 0.55 ? Rock.ANDESITE : (r < 0.85 ? Rock.TUFF : Rock.BASALT)).ordinal(); // subduction arc
            if (o.volcType == 4) return (r < 0.7 ? Rock.LIMESTONE : Rock.BASALT).ordinal(); // coral atoll
            return (r < 0.82 ? Rock.BASALT : Rock.TUFF).ordinal(); // hotspot
        }
        if (o.collR > 0.3 && o.L >= 0) return collisionRock(r).ordinal(); // regional metamorphism
        if (o.L < -0.03) { // sea floor
            if (o.ridge > 0.22) return Rock.BASALT.ordinal();
            return seaRock(r).ordinal();
        }
        if (o.platType == 1 && o.plat > 0.3) return (r < 0.88 ? Rock.BASALT : Rock.TUFF).ordinal(); // flood basalt plateau
        if (o.orog > 0.35 && r < 0.5) return (r < 0.32 ? Rock.ANDESITE : Rock.TUFF).ordinal(); // volcanic arc
        double r2 = rnd(i, j, SD.rock + 1) * Rock.TOTAL;
        for (int k = 0; k < Rock.VALUES.length; k++) if (r2 < Rock.CUM[k]) return k;
        return 0;
    }

    private static Rock collisionRock(double r) {
        return r < 0.35 ? Rock.GNEISS
            : r < 0.55 ? Rock.GRANITE : r < 0.75 ? Rock.SLATE : r < 0.87 ? Rock.MARBLE : Rock.LIMESTONE;
    }

    private static Rock seaRock(double r) {
        return r < 0.8 ? Rock.BASALT : (r < 0.92 ? Rock.SHALE : Rock.LIMESTONE);
    }

    /** Erodibility of patch (i, j): same logic as rockFor with smooth transitions between the cases. */
    protected double erodFor(int i, int j, GenPoint o) {
        double r = rnd(i, j, SD.rock), r2 = rnd(i, j, SD.rock + 1) * Rock.TOTAL;
        int k = 0;
        while (k < Rock.VALUES.length - 1 && r2 >= Rock.CUM[k]) k++;
        double e = Rock.VALUES[k].erod;
        if (r < 0.5) e = lerp(e, (r < 0.32 ? Rock.ANDESITE : Rock.TUFF).erod, ss(0.3, 0.4, o.orog));
        Rock sea = o.ridge > 0.22 ? Rock.BASALT : seaRock(r);
        e = lerp(e, sea.erod, ss(-0.01, -0.05, o.L));
        e = lerp(e, collisionRock(r).erod, ss(0.25, 0.35, o.collR) * ss(-0.03, 0.01, o.L));
        Rock vr = o.volcType == 1 ? (r < 0.55 ? Rock.ANDESITE : (r < 0.85 ? Rock.TUFF : Rock.BASALT))
            : o.volcType == 4 ? (r < 0.7 ? Rock.LIMESTONE : Rock.BASALT) : (r < 0.82 ? Rock.BASALT : Rock.TUFF);
        if (o.platType == 1) e = lerp(e, Rock.BASALT.erod, ss(0.2, 0.4, o.plat));
        return lerp(e, vr.erod, ss(0.1, 0.2, o.volc) * ss(-45, -35, o.h0));
    }

    public void apply(double x, double y, GenPoint o, GenScratch s) {
        final double[] RD = s.RD;
        double R = P.rockPatch, sc = R * 1.4;
        double wx = x + 0.75 * R * fbm(x / sc, y / sc, SD.rockW, 3);
        double wy = y + 0.75 * R * fbm(x / sc + 5.2, y / sc + 1.7, SD.rockW + 3, 3);
        int ci = floor(wx / R), cj = floor(wy / R);
        double d1 = Double.POSITIVE_INFINITY, d2 = Double.POSITIVE_INFINITY, x1 = 0, y1 = 0, x2 = 0, y2 = 0;
        int i1 = 0, j1 = 0, i2 = 0, j2 = 0;
        for (int dj = -1, k = 0; dj <= 1; dj++) {
            for (int di = -1; di <= 1; di++, k++) {
                int i = ci + di, j = cj + dj;
                double fx = (i + rnd(i, j, SD.rockP)) * R, fy = (j + rnd(i, j, SD.rockP + 1)) * R;
                double d = (fx - wx) * (fx - wx) + (fy - wy) * (fy - wy);
                RD[k] = d;
                if (d < d1) {
                    d2 = d1;
                    i2 = i1;
                    j2 = j1;
                    x2 = x1;
                    y2 = y1;
                    d1 = d;
                    i1 = i;
                    j1 = j;
                    x1 = fx;
                    y1 = fy;
                } else if (d < d2) {
                    d2 = d;
                    i2 = i;
                    j2 = j;
                    x2 = fx;
                    y2 = fy;
                }
            }
        }
        // erodibility: smooth blend of every nearby patch (weight by how much farther than the nearest it is)
        double s1 = Math.sqrt(d1), B = R * 0.3;
        double ew = 0, es = 0;
        for (int dj = -1, k = 0; dj <= 1; dj++) {
            for (int di = -1; di <= 1; di++, k++) {
                double w = ss(B, 0, Math.sqrt(RD[k]) - s1);
                if (w <= 0) continue;
                es += w * erodFor(ci + di, cj + dj, o);
                ew += w;
            }
        }
        double nx = x2 - x1, ny = y2 - y1;
        double len = Math.sqrt(nx * nx + ny * ny);
        if (len == 0) len = 1;
        nx /= len;
        ny /= len;
        double d = ((x1 + x2) * 0.5 - wx) * nx + ((y1 + y2) * 0.5 - wy) * ny;
        int a = rockFor(i1, j1, o), b = rockFor(i2, j2, o);
        double t = 0.5 + 0.5 * ss(0, R * 0.15, d); // weight of the main rock (smooth transition)
        o.rock = a;
        o.rock2 = b;
        o.rockT = t;
        o.erod = es / ew;
    }
}
