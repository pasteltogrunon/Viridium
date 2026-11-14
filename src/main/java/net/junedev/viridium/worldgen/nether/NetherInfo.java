package net.junedev.viridium.worldgen.nether;

import java.util.ArrayList;
import java.util.List;

/**
 * Plain-text description of the Nether structure around a point, for {@code /viridium nether}: which domain and core
 * shape the place, and where the nearest chimney and route are. A development aid for checking what the terrain
 * should be telling the player (design §16, §21).
 */
public final class NetherInfo {

    private NetherInfo() {}

    static String compass(double dx, double dz) {
        String[] n = { "E", "SE", "S", "SW", "W", "NW", "N", "NE" };
        double a = Math.atan2(dz, dx); // +x east, +z south
        int k = (int) Math.round(a / (Math.PI / 4));
        return n[Math.floorMod(k, 8)];
    }

    static String activity(double a) {
        return a > NetherCore.ACTIVE ? "active" : a < NetherCore.EXTINCT ? "extinct" : a > 0 ? "stable" : "weakened";
    }

    public static int tierAt(double y) {
        return y < NetherLayout.slabMid(0) ? 0 : y < NetherLayout.slabMid(1) ? 1 : 2;
    }

    public static List<String> describe(NetherWorld w, double x, double y, double z) {
        List<String> out = new ArrayList<>();
        int t = tierAt(y);
        NetherWorld.Column c = w.column(x, z, t);
        NetherCores.Domain d = c.domain;
        NetherCore a = d.a;
        out.add(
            String.format(
                "Tier %d (%s), substrate %s, floor %.0f, ceiling %.0f, lava level %d",
                t,
                t == 0 ? "deep" : t == 1 ? "middle" : "high",
                c.kind,
                c.floor,
                c.ceil,
                NetherLayout.LAVA[t]));
        out.add(
            String.format(
                "Domain of a %s core (%s/%s, %s), tier %d: %.0f blocks %s at y %.0f, intensity here %.2f",
                activity(a.activity),
                a.dominant,
                a.secondary,
                a.substrate,
                a.tier,
                a.distH(x, z),
                compass(a.x - x, a.z - z),
                a.y,
                d.intensity));
        if (d.b != null && d.ga < 0.97) {
            String[] enc = { "transition", "dominance", "interference", "barrier" };
            out.add(
                String.format(
                    "Border (%s) with a %s %s core %.0f blocks %s",
                    enc[d.encounter],
                    activity(d.b.activity),
                    d.b.dominant,
                    d.b.distH(x, z),
                    compass(d.b.x - x, d.b.z - z)));
        }
        if (c.fracture.dist < 120) out.add(
            String.format(
                "On a fracture (%.0f blocks)%s",
                c.fracture.dist,
                c.fracture.abyss > 0 ? ", open into an abyss here" : ""));
        List<NetherRoutes.Route> routes = new ArrayList<>();
        List<NetherRoutes.Chimney> chims = new ArrayList<>();
        w.routes.collect(x - 400, z - 400, x + 400, z + 400, 0, routes, chims);
        NetherRoutes.Chimney best = null;
        double bd = Double.MAX_VALUE;
        for (NetherRoutes.Chimney ch : chims) {
            if (ch.lower != t && ch.lower + 1 != t) continue;
            double dd = Math.hypot(ch.x - x, ch.z - z);
            if (dd < bd) {
                bd = dd;
                best = ch;
            }
        }
        if (best != null) out.add(
            String.format(
                "Nearest chimney: %.0f blocks %s, from y %d (tier %d) to y %d (tier %d)",
                bd,
                compass(best.x - x, best.z - z),
                best.bottom,
                best.lower,
                best.top,
                best.lower + 1));
        NetherRoutes.Hit hit = new NetherRoutes.Hit();
        for (NetherRoutes.Route r : routes) {
            if (r.tier != t) continue;
            for (int k = 0; k < r.segments(); k++) r.probe(k, x, z, hit);
        }
        if (hit.route != null) out.add(
            String.format(
                "Nearest %s: %.0f blocks away, at y %.0f",
                hit.route.main ? "route between cores" : "chimney approach",
                hit.dist,
                hit.grade));
        return out;
    }
}
