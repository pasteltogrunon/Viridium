package net.junedev.viridium.worldgen.nether;

import static net.junedev.viridium.worldgen.nether.NetherLayout.LAVA;
import static net.junedev.viridium.worldgen.nether.NetherLayout.TIERS;
import static net.junedev.viridium.worldgen.terrain.core.GenMath.floor;
import static net.junedev.viridium.worldgen.terrain.core.GenMath.lerp;
import static net.junedev.viridium.worldgen.terrain.core.GenMath.ss;

import java.util.ArrayList;
import java.util.List;

import net.junedev.viridium.worldgen.terrain.core.Noise;

/**
 * Computes a {@link NetherPlan} for one chunk column: the base architecture of every tier, then the abysses and their
 * lips, the routes (galleries, causeways, arched bridges, shoulders), stalactites, the slabs between tiers, lava,
 * fissures, marks of old levels and ecosystems; finally the chimneys, landmarks and lava falls that reach the chunk.
 */
public final class NetherPlanner {

    /** Height of the clearance above a route grade (at its axis). */
    static final int CLEAR = 15;
    /** Minimum thickness of the slab between two tiers. */
    private static final int MIN_SLAB = 14;
    /** Spacing of the piers of a bridge along the route. */
    static final double PIER = 34;

    private final NetherWorld world;
    private final int sStal, sFiss, sFall;

    public NetherPlanner(NetherWorld world) {
        this.world = world;
        this.sStal = NetherSubstrate.salt(world.seed, 60);
        this.sFiss = NetherSubstrate.salt(world.seed, 61);
        this.sFall = NetherSubstrate.salt(world.seed, 62);
    }

    public NetherWorld world() {
        return world;
    }

    public NetherPlan plan(int chunkX, int chunkZ) {
        NetherPlan p = new NetherPlan(chunkX, chunkZ);
        int bx = chunkX << 4, bz = chunkZ << 4;
        NetherWorld.Column col = new NetherWorld.Column();
        NetherSubstrate.Fracture f = col.fracture;
        NetherCores.Domain d = col.domain;

        List<NetherRoutes.Route> routes = new ArrayList<>();
        List<NetherRoutes.Chimney> chimneys = new ArrayList<>();
        world.routes.collect(bx, bz, bx + 15, bz + 15, 12, routes, chimneys);
        // the segments of each route that can reach this chunk
        List<int[]> segs = new ArrayList<>(routes.size());
        for (NetherRoutes.Route r : routes) {
            int[] tmp = new int[r.segments()];
            int n = 0;
            for (int k = 0; k < r.segments(); k++) if (r.near(k, bx, bz, bx + 15, bz + 15, r.half + 8)) tmp[n++] = k;
            segs.add(java.util.Arrays.copyOf(tmp, n));
        }
        NetherRoutes.Hit hit = new NetherRoutes.Hit();
        double[] fl = new double[TIERS], ce = new double[TIERS], h = new double[TIERS];

        for (int lz = 0; lz < 16; lz++) for (int lx = 0; lx < 16; lx++) {
            int i = lz * 16 + lx;
            double x = bx + lx + 0.5, z = bz + lz + 0.5;
            world.substrate.fracture(x, z, f);
            p.fracDist[i] = (float) f.dist;
            p.quartz[i] = (float) ss(90, 0, f.dist);
            double maxMask = 0;
            for (int t = 0; t < TIERS; t++) {
                world.base(x, z, t, col);
                fl[t] = col.floor;
                ce[t] = col.ceil;
                h[t] = col.height;
                maxMask = Math.max(maxMask, d.coreMask);
                for (int k = 0; k < Transform.COUNT; k++) p.w[t][k][i] = (float) d.w[k];
                p.intensity[t][i] = (float) d.intensity;
                p.core[t][i] = d.a;
                p.coreN[t][i] = (float) d.na;
                p.cavity[t][i] = (float) d.coreMask;
                p.kind[t][i] = (byte) col.kind.ordinal();
                p.activity[t][i] = (float) d.activity;
                p.strata[t][i] = (float) (col.tilt + 20 * NetherWorld.fold(x, z, d));
                byte fg = 0;
                if (d.activity < 0) fg |= NetherPlan.OLD_MARK;
                if (d.w[Transform.GROWTH.ordinal()] > 0.2 && d.activity > -0.3 && col.height > 45)
                    fg |= NetherPlan.GARDEN;
                p.flags[t][i] = fg;
            }

            // abysses (never through a core cavity), and lips that keep the seas out of them
            double aw = f.abyss * (1 - ss(0.2, 0.6, maxMask));
            p.abyss[i] = (float) aw;
            boolean nearAbyss = aw > 0 && f.dist < aw + 9;
            boolean inAbyss = aw > 0 && f.dist < aw + 4;
            for (int t = 1; t < TIERS; t++) {
                if (!nearAbyss) continue;
                boolean closed = ce[t] <= fl[t];
                fl[t] = Math.max(fl[t], LAVA[t] + 2);
                ce[t] = closed ? fl[t] : Math.max(ce[t], fl[t]);
            }

            for (int t = 0; t < TIERS; t++) {
                double floorV = fl[t], ceilV = ce[t], floorBefore = floorV;
                int under, deck;
                p.hwDist[t][i] = Float.MAX_VALUE;
                hit.reset();
                for (int r = 0; r < routes.size(); r++) {
                    NetherRoutes.Route route = routes.get(r);
                    if (route.tier != t) continue;
                    for (int k : segs.get(r)) route.probe(k, x, z, hit);
                }
                boolean corridor = false;
                double bridgeUnder = Double.NaN;
                if (hit.route != null && hit.dist < hit.route.half + 6) {
                    double g = hit.grade, half = hit.route.half;
                    p.hwDist[t][i] = (float) hit.dist;
                    p.hwGrade[t][i] = (float) g;
                    p.hwAlong[t][i] = (float) hit.along;
                    p.hwHalf[t][i] = (float) half;
                    if (hit.dist < half) {
                        corridor = true;
                        double fl0 = floorV;
                        floorV = Math.round(g);
                        if (fl0 < g - 6 || inAbyss) {
                            // an arched bridge: piers down to the old floor, arches between them
                            double fr = hit.along / PIER;
                            fr -= Math.floor(fr);
                            double s = 1 - Math.sin(Math.PI * fr);
                            bridgeUnder = inAbyss ? g - 4 - 3 * s * s : g - 3 - (g - 3 - fl0) * s * s;
                        }
                        ceilV = Math.max(ceilV, g + CLEAR);
                        p.flags[t][i] |= NetherPlan.CORRIDOR;
                    } else if (ceilV > floorV && Math.abs(floorV - g) < 12) {
                        // shoulders: the ground meets the route in a ramp
                        floorV = lerp(g, floorV, ss(half, half + 6, hit.dist));
                    }
                }
                if (!corridor && ceilV > floorV) ceilV = stalactites(x, z, t, floorV, ceilV, h[t]);
                fl[t] = floorV;
                ce[t] = ceilV;
                int fi = (int) Math.round(floorV);
                if (Double.isNaN(bridgeUnder)) {
                    under = deck = fi;
                } else {
                    deck = (int) Math.round(Math.min(bridgeUnder, fi - 1));
                    under = (int) Math.round(Math.min(floorBefore, deck));
                }
                p.floor[t][i] = fi;
                p.under[t][i] = under;
                p.deck[t][i] = deck;
            }
            // the slab between two tiers keeps a minimum thickness
            for (int t = 0; t + 1 < TIERS; t++) {
                double max = fl[t + 1] - MIN_SLAB;
                if (ce[t] > max) ce[t] = Math.max(fl[t], max);
            }
            for (int t = 0; t < TIERS; t++) {
                int ci = (int) Math.round(ce[t]);
                if (ci < p.floor[t][i] + 4) ci = p.floor[t][i];
                p.ceil[t][i] = ci;
            }
            for (int t = 0; t + 1 < TIERS; t++) p.mid[t][i] = (p.ceil[t][i] + p.floor[t + 1][i]) / 2;

            for (int t = 0; t < TIERS; t++) {
                byte fg = p.flags[t][i];
                if (t == 0 || !nearAbyss) fg |= NetherPlan.LAVA_ON;
                double act = p.activity[t][i];
                int fi = p.floor[t][i];
                boolean corridor = (fg & NetherPlan.CORRIDOR) != 0;
                if (act > 0.2 && fi > LAVA[t] + 1 && !corridor && !inAbyss) {
                    boolean line = Math.abs(Noise.perlin(x / 45, z / 45, sFiss + 7 * t)) < 0.025
                        && Noise.perlin(x / 210, z / 210, sFiss + 7 * t + 3) > -0.1;
                    if (line || (f.abyss == 0 && f.dist < 2)) fg |= NetherPlan.FISSURE;
                }
                p.flags[t][i] = fg;
                p.eco[t][i] = (byte) eco(
                    act,
                    fi,
                    t,
                    SubstrateKind.VALUES[p.kind[t][i]],
                    p.w[t][Transform.GROWTH.ordinal()][i],
                    p.ceil[t][i] - fi).ordinal();
            }
        }

        // chimneys, landmarks and falls that reach the chunk
        List<NetherRoutes.Chimney> keep = new ArrayList<>();
        for (NetherRoutes.Chimney c : chimneys) if (boxDist(c.x, c.z, bx, bz) < c.outer() + 1) keep.add(c);
        p.chimneys = keep.toArray(new NetherRoutes.Chimney[0]);
        List<NetherCore> lm = new ArrayList<>();
        int ci = floor((double) bx / NetherCores.CELL), cj = floor((double) bz / NetherCores.CELL);
        for (int di = -1; di <= 1; di++)
            for (int dj = -1; dj <= 1; dj++) for (NetherCore c : world.cores.cell(ci + di, cj + dj))
                if (c != null && boxDist(c.x, c.z, bx, bz) < c.cavityR * 1.1) lm.add(c);
        p.landmarks = lm.toArray(new NetherCore[0]);
        p.falls = falls(bx, bz);
        return p;
    }

    private static double boxDist(double x, double z, int bx, int bz) {
        double dx = Math.max(0, Math.max(bx - x, x - (bx + 16))), dz = Math.max(0, Math.max(bz - z, z - (bz + 16)));
        return Math.sqrt(dx * dx + dz * dz);
    }

    /** Ceiling lowered by stalactites: cones on a 9-block grid, longer in tall caverns. */
    private double stalactites(double x, double z, int t, double floorV, double ceilV, double height) {
        double tall = ss(40, 100, height);
        if (tall <= 0) return ceilV;
        int gi = floor(x / 9), gj = floor(z / 9);
        double low = 0;
        for (int di = -1; di <= 1; di++) for (int dj = -1; dj <= 1; dj++) {
            int a = gi + di, b = gj + dj, s = sStal + 13 * t;
            double cx = (a + Noise.rnd(a, b, s)) * 9, cz = (b + Noise.rnd(a, b, s + 1)) * 9;
            double r = 2 + 3.5 * Noise.rnd(a, b, s + 2), q = Noise.rnd(a, b, s + 3);
            double len = q * q * 24 * tall;
            double dd = Math.sqrt((x - cx) * (x - cx) + (z - cz) * (z - cz));
            if (dd < r) low = Math.max(low, len * Math.pow(1 - dd / r, 1.5));
        }
        return Math.max(floorV + 8, ceilV - low);
    }

    static Eco eco(double act, int floorY, int t, SubstrateKind kind, double growth, int height) {
        if (act > 0.2 && floorY < LAVA[t] + 6) return Eco.CRUST;
        if (act > 0.3) return Eco.ASH;
        if (kind == SubstrateKind.SOUL) return growth > 0.25 ? Eco.OSSUARY : Eco.SOUL;
        if (act < -0.15) return height > 40 ? Eco.FUNGAL : Eco.SOUL;
        if (growth > 0.3) return Eco.REEF;
        return Eco.BARE;
    }

    /** Lava falling from the ceiling of active, tall caverns into their seas: {x, z, tier} triples. */
    private double[] falls(int bx, int bz) {
        final int C = 112;
        List<double[]> out = new ArrayList<>();
        int i0 = floor((bx - 2.0) / C), i1 = floor((bx + 18.0) / C), j0 = floor((bz - 2.0) / C),
            j1 = floor((bz + 18.0) / C);
        for (int t = 0; t < TIERS; t++) for (int i = i0; i <= i1; i++) for (int j = j0; j <= j1; j++) {
            int s = sFall + 11 * t;
            if (Noise.rnd(i, j, s) >= 0.55) continue;
            double fx = Math.floor((i + 0.15 + 0.7 * Noise.rnd(i, j, s + 1)) * C) + 0.5;
            double fz = Math.floor((j + 0.15 + 0.7 * Noise.rnd(i, j, s + 2)) * C) + 0.5;
            if (fx < bx - 2 || fx > bx + 18 || fz < bz - 2 || fz > bz + 18) continue;
            NetherWorld.Column c = world.column(fx, fz, t);
            if (c.domain.activity > 0.3 && c.floor < LAVA[t] - 1 && c.height > 50 && c.domain.coreMask < 0.3)
                out.add(new double[] { fx, fz, t });
        }
        double[] r = new double[out.size() * 3];
        for (int k = 0; k < out.size(); k++) System.arraycopy(out.get(k), 0, r, k * 3, 3);
        return r;
    }
}
