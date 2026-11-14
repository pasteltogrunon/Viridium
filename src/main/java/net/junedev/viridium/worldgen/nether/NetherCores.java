package net.junedev.viridium.worldgen.nether;

import static net.junedev.viridium.worldgen.terrain.core.GenMath.clamp;
import static net.junedev.viridium.worldgen.terrain.core.GenMath.floor;
import static net.junedev.viridium.worldgen.terrain.core.GenMath.ss;

import net.junedev.viridium.worldgen.terrain.core.GenMath;
import net.junedev.viridium.worldgen.terrain.core.Noise;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;

/**
 * Cores and their domains of influence (design §7, §8).
 *
 * <p>
 * Cores live on a grid of {@value #CELL}-block cells, at most one per cell and tier (the middle tier always has one).
 * Inside its cell a core picks, among a few candidates, the place the substrate favours most (high thermal potential,
 * close to a fracture), and derives every property from the substrate around it. Nothing is placed by a label: the
 * domain is the footprint of the core.
 *
 * <p>
 * A domain is where a core's normalised distance is the smallest. The distance is anisotropic (stretched along the
 * core's orientation), weighs vertical separation more (domains leak into the neighbouring tiers when those have no
 * nearer core), and shrinks along the fracture the core sits on: influence advances easily through a fracture. Where
 * two domains meet, the pair decides the kind of encounter: transition, dominance, interference or barrier.
 */
public final class NetherCores {

    public static final int CELL = 576;

    /** Kinds of encounter between two domains (design §8.2). */
    public static final int TRANSITION = 0, DOMINANCE = 1, INTERFERENCE = 2, BARRIER = 3;

    /** Weight of vertical separation in the domain distance. */
    private static final double VERTICAL = 1.5;
    /** Width (in normalised distance difference) of the zone where two domains mix. */
    private static final double MIX = 0.14;

    private final NetherSubstrate sub;
    private final int seed;
    private final Cache<Long, NetherCore[]> cells = CacheBuilder.newBuilder()
        .maximumSize(8192)
        .concurrencyLevel(4)
        .build();
    private final ThreadLocal<NetherSubstrate.Fracture> frac = ThreadLocal.withInitial(NetherSubstrate.Fracture::new);

    NetherCores(NetherSubstrate sub, int seed) {
        this.sub = sub;
        this.seed = seed;
    }

    /** Cores of a cell, indexed by tier (null where the cell has none). Deterministic, any order, cached. */
    public NetherCore[] cell(int i, int j) {
        long key = GenMath.mixKey(((long) i << 32) ^ (j & 0xffffffffL));
        NetherCore[] c = cells.getIfPresent(key);
        if (c == null) {
            c = build(i, j);
            cells.put(key, c);
        }
        return c;
    }

    private NetherCore[] build(int i, int j) {
        NetherCore[] out = new NetherCore[NetherLayout.TIERS];
        NetherSubstrate.Fracture f = new NetherSubstrate.Fracture();
        for (int t = 0; t < NetherLayout.TIERS; t++) {
            int s = seed + 1009 * (t + 1);
            if (t != 1 && Noise.rnd(i, j, s) >= 0.72) continue;
            // the substrate decides where in the cell the core can exist
            double bx = 0, bz = 0, best = -1e9;
            for (int k = 0; k < 6; k++) {
                double cx = (i + 0.2 + 0.6 * Noise.rnd(i * 8 + k, j, s + 1)) * CELL;
                double cz = (j + 0.2 + 0.6 * Noise.rnd(i * 8 + k, j, s + 2)) * CELL;
                sub.fracture(cx, cz, f);
                double th = sub.thermal(cx, cz, t, f.dist);
                double score = 0.7 * th + 0.6 * ss(150, 0, f.dist) + 0.3 * Noise.rnd(i * 8 + k, j, s + 3);
                if (f.abyss > 0 && f.dist < f.abyss + 110) score -= 2; // never inside an abyss
                if (score > best) {
                    best = score;
                    bx = cx;
                    bz = cz;
                }
            }
            sub.fracture(bx, bz, f);
            double th = sub.thermal(bx, bz, t, f.dist);
            double age = sub.age(bx, bz);
            double act = clamp(1.7 * (th - 0.5) - 1.2 * (age - 0.5) + 0.7 * (Noise.rnd(i, j, s + 4) - 0.5), -1, 1);
            SubstrateKind kind = sub.material(bx, bz, t);
            double[] bias = kind.transformBias.clone();
            if (th > 0.7) bias[Transform.SUSPENSION.ordinal()] += 1;
            if (age > 0.65) bias[Transform.HOLLOWING.ordinal()] += 1;
            Transform dom = pick(bias, Noise.rnd(i, j, s + 5));
            bias[dom.ordinal()] = 0;
            Transform sec = pick(bias, Noise.rnd(i, j, s + 6));
            boolean onFracture = f.dist < 140;
            double orient = onFracture ? Math.atan2(f.dirZ, f.dirX) : sub.deformAngle(bx, bz);
            double aniso = 1 + 0.8 * Noise.rnd(i, j, s + 7) + (onFracture ? 0.4 : 0);
            double open = switch (dom) {
                case FOLD -> 0.55;
                case REPETITION -> 0.65;
                case SUSPENSION -> 0.78;
                case GROWTH -> 0.45;
                case HOLLOWING -> 0.5;
            } + 0.2 * (Noise.rnd(i, j, s + 8) - 0.5);
            double floorY = NetherLayout.LAVA[t] + 5;
            int id = Noise.hash(i, j, s + 9);
            out[t] = new NetherCore(
                i,
                j,
                t,
                id,
                bx,
                floorY + 44,
                bz,
                floorY,
                230 + 190 * Noise.rnd(i, j, s + 10),
                0.55 + 0.45 * Noise.rnd(i, j, s + 11),
                orient,
                aniso,
                dom,
                sec,
                act,
                th,
                kind,
                f.id,
                f.dist,
                open);
        }
        return out;
    }

    private static Transform pick(double[] w, double r) {
        double sum = 0;
        for (double v : w) sum += v;
        double acc = 0;
        for (int k = 0; k < w.length; k++) {
            acc += w[k] / sum;
            if (r < acc) return Transform.VALUES[k];
        }
        for (int k = w.length - 1; k >= 0; k--) if (w[k] > 0) return Transform.VALUES[k];
        return Transform.FOLD;
    }

    // ------------------------------------------------------------------ domains

    /** Domain state at a point (mutable, reuse per thread). */
    public static final class Domain {

        /** Primary and secondary cores (b may be null) and their normalised distances. */
        public NetherCore a, b;
        public double na, nb;
        /** Weight of a in the mix (1 deep inside its domain, 0.5 at the border). */
        public double ga;
        public int encounter;
        /** Transformation intensity at this point and the weight of each {@link Transform} (already scaled by it). */
        public double intensity;
        public final double[] w = new double[Transform.COUNT];
        /** Strength of a barrier encounter here (border massif), 0..1. */
        public double barrier;
        public double openPref, activity;
        /** 1 inside the cavity of a core of the sampled tier. */
        public double coreMask;
        /** Core whose cavity this is (when coreMask > 0). */
        public NetherCore cavity;
    }

    static double fall(double n) {
        return 1 - ss(0.05, 1.15, n);
    }

    private double norm(NetherCore c, double x, double y, double z, NetherSubstrate.Fracture f) {
        double dx = x - c.x, dz = z - c.z, dy = y - c.y;
        double du = dx * c.cos + dz * c.sin, dv = -dx * c.sin + dz * c.cos, e = Math.sqrt(c.aniso);
        double d = Math.sqrt((du / e) * (du / e) + (dv * e) * (dv * e) + (dy * VERTICAL) * (dy * VERTICAL));
        if (f.id == c.fractureId && c.fractureDist < 140) d *= 1 - 0.22 * ss(110, 40, f.dist);
        return d / c.reach;
    }

    /**
     * Samples the domains at (x, y, z) for tier {@code tier}; {@code f} must hold the fracture at (x, z).
     */
    public void sample(double x, double y, double z, int tier, NetherSubstrate.Fracture f, Domain d) {
        int ci = floor(x / CELL), cj = floor(z / CELL);
        NetherCore a = null, b = null;
        double na = Double.MAX_VALUE, nb = Double.MAX_VALUE;
        for (int di = -1; di <= 1; di++) for (int dj = -1; dj <= 1; dj++) {
            for (NetherCore c : cell(ci + di, cj + dj)) {
                if (c == null) continue;
                double n = norm(c, x, y, z, f);
                if (n < na) {
                    b = a;
                    nb = na;
                    a = c;
                    na = n;
                } else if (n < nb) {
                    b = c;
                    nb = n;
                }
            }
        }
        d.a = a;
        d.b = b;
        d.na = na;
        d.nb = nb;
        double sigma = b == null ? 1 : (nb - na) / (na + nb);
        int enc = b == null ? TRANSITION : encounter(a, b);
        d.encounter = enc;
        double ga;
        if (enc == DOMINANCE) {
            boolean aStronger = a.intensity > b.intensity || (a.intensity == b.intensity && a.id > b.id);
            double delta = aStronger ? 0.07 : -0.07;
            ga = ss(-MIX - delta, MIX - delta, sigma);
        } else ga = ss(-MIX, MIX, sigma);
        double gb = 1 - ga;
        d.ga = ga;
        double ia = a.intensity * fall(na), ib = b == null ? 0 : b.intensity * fall(nb);
        double[] w = d.w;
        java.util.Arrays.fill(w, 0);
        if (enc == INTERFERENCE) {
            // both transformations act on the same forms near the border, with a controlled intensity (§8.3)
            double zone = 1 - ss(0, MIX, sigma);
            double[] wa = new double[Transform.COUNT], wb = new double[Transform.COUNT];
            add(wa, a, ia);
            add(wb, b, ib);
            for (int k = 0; k < w.length; k++)
                w[k] = (ga * wa[k] + gb * wb[k]) * (1 - zone) + zone * Math.min(0.6, 0.85 * Math.max(wa[k], wb[k]));
            d.intensity = (ga * ia + gb * ib) * (1 - zone) + zone * Math.min(0.6, Math.max(ia, ib));
        } else {
            add(w, a, ga * ia);
            if (b != null) add(w, b, gb * ib);
            d.intensity = ga * ia + gb * ib;
        }
        d.barrier = enc == BARRIER ? 1 - ss(0, 0.07, sigma) : 0;
        d.openPref = b == null ? a.openPref : ga * a.openPref + gb * b.openPref;
        d.activity = b == null ? a.activity : ga * a.activity + gb * b.activity;
        d.coreMask = 0;
        d.cavity = null;
        cavity(d, a, x, z, tier);
        if (b != null) cavity(d, b, x, z, tier);
    }

    private static void cavity(Domain d, NetherCore c, double x, double z, int tier) {
        if (c.tier != tier) return;
        double m = 1 - ss(0.8, 1.15, c.distH(x, z) / c.cavityR);
        if (m > d.coreMask) {
            d.coreMask = m;
            d.cavity = c;
        }
    }

    private static void add(double[] w, NetherCore c, double amount) {
        w[c.dominant.ordinal()] += amount;
        w[c.secondary.ordinal()] += 0.45 * amount;
    }

    /** Kind of encounter between two cores (symmetric); different substrates favour barriers. */
    public int encounter(NetherCore a, NetherCore b) {
        int lo = Math.min(a.id, b.id), hi = Math.max(a.id, b.id);
        double r = Noise.rnd(lo, hi, seed + 77);
        double barrierFrom = a.substrate != b.substrate ? 0.7 : 0.86;
        if (r >= barrierFrom) return BARRIER;
        if (r < 0.45) return TRANSITION;
        if (r < 0.65) return DOMINANCE;
        return INTERFERENCE;
    }

    /** Nearest core of tier t to (x, z) among the cells around (horizontal distance), or null. */
    public NetherCore nearest(double x, double z, int t, int cellRadius) {
        int ci = floor(x / CELL), cj = floor(z / CELL);
        NetherCore best = null;
        double bd = Double.MAX_VALUE;
        for (int di = -cellRadius; di <= cellRadius; di++) for (int dj = -cellRadius; dj <= cellRadius; dj++) {
            NetherCore c = cell(ci + di, cj + dj)[t];
            if (c == null) continue;
            double d = c.distH(x, z);
            if (d < bd) {
                bd = d;
                best = c;
            }
        }
        return best;
    }

    NetherSubstrate.Fracture scratchFracture() {
        return frac.get();
    }
}
