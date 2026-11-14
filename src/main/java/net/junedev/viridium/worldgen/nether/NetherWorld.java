package net.junedev.viridium.worldgen.nether;

import static net.junedev.viridium.worldgen.nether.NetherLayout.CEIL;
import static net.junedev.viridium.worldgen.nether.NetherLayout.FLOOR;
import static net.junedev.viridium.worldgen.nether.NetherLayout.LAVA;
import static net.junedev.viridium.worldgen.terrain.core.GenMath.floor;
import static net.junedev.viridium.worldgen.terrain.core.GenMath.lerp;
import static net.junedev.viridium.worldgen.terrain.core.GenMath.ss;

import net.junedev.viridium.worldgen.terrain.core.Noise;

/**
 * The Nether pipeline (the counterpart of {@code WorldGen}): substrate → cores → domains → architecture of rock and
 * void → connections. Everything is a pure function of the seed and the position, so any part can be evaluated in any
 * order (columns, routes, chimneys) and the result is the same at both sides of any internal division of the
 * generator (design §19).
 *
 * <p>
 * This class owns the <b>regional architecture</b> (§10): for each tier, the floor and ceiling of its caverns before
 * routes, chimneys and abysses. The proportion of rock and void comes from an openness field that combines a regional
 * noise, the preference of the domain, elongated massifs that follow the deformation, the barriers between domains,
 * the long chambers along the minor fractures and the cavities of the cores. Floors are terraced; open basins and
 * active domains sink below the lava level of the tier (seas), extinct domains rise above it (dry, with marks).
 */
public final class NetherWorld {

    public final int seed;
    public final NetherSubstrate substrate;
    public final NetherCores cores;
    public final NetherRoutes routes;
    private final int sOpen, sMassif, sRel, sCeil;

    public NetherWorld(int seed) {
        this.seed = seed;
        this.substrate = new NetherSubstrate(seed);
        this.cores = new NetherCores(substrate, NetherSubstrate.salt(seed, 40));
        this.sOpen = substrate.openSeed();
        this.sMassif = NetherSubstrate.salt(seed, 41);
        this.sRel = NetherSubstrate.salt(seed, 42);
        this.sCeil = NetherSubstrate.salt(seed, 43);
        this.routes = new NetherRoutes(this, NetherSubstrate.salt(seed, 44));
    }

    /** Evaluation scratch of one column and tier (reuse per thread). */
    public static final class Column {

        public final NetherSubstrate.Fracture fracture = new NetherSubstrate.Fracture();
        public final NetherCores.Domain domain = new NetherCores.Domain();
        /** Floor (first void Y) and ceiling (first rock Y above the void); equal when the place is closed rock. */
        public double floor, ceil;
        /** Openness and cavern height before the ceiling cap. */
        public double open, height;
        public double tilt;
        public SubstrateKind kind;
    }

    private final ThreadLocal<Column> scratch = ThreadLocal.withInitial(Column::new);

    /** Smooth minimum (blend width k): no flat ceilings where the cavern meets its cap. */
    static double smin(double a, double b, double k) {
        double hh = Math.max(k - Math.abs(a - b), 0) / k;
        return Math.min(a, b) - hh * hh * k * 0.25;
    }

    /** Steps of {@code step} blocks with flat treads and steep risers (terraces). */
    static double terrace(double v, double step) {
        double q = v / step;
        int k = floor(q);
        return (k + ss(0.6, 1, q - k)) * step;
    }

    /**
     * Base architecture of tier t at (x, z): domain, openness, floor and ceiling. {@code c.fracture} must already hold
     * the fracture at (x, z).
     */
    public void base(double x, double z, int t, Column c) {
        NetherSubstrate.Fracture f = c.fracture;
        double tilt = substrate.tilt(x, z, t);
        c.tilt = tilt;
        double yMid = (FLOOR[t] + CEIL[t]) * 0.5 + tilt;
        NetherCores.Domain d = c.domain;
        cores.sample(x, yMid, z, t, f, d);
        c.kind = substrate.material(x, z, t);

        // openness: regional noise, preference of the domain, massifs along the deformation, barriers, fractures
        double o = 0.52 + 0.30 * Noise.fbm(x / 280, z / 280, sOpen + 17 * t, 3);
        o += (d.openPref - 0.55) * 0.7;
        double ang = substrate.deformAngle(x, z), ca = Math.cos(ang), sa = Math.sin(ang);
        double mu = x * ca + z * sa, mv = -x * sa + z * ca;
        // massifs: broad bodies of rock elongated along the deformation, which force a choice of passes
        o -= 0.75 * ss(0.04, 0.34, Noise.fbm(mu / 760, mv / 270, sMassif + 17 * t, 3));
        o -= 0.75 * d.barrier;
        if (f.abyss == 0 && f.dist < 60) {
            // a closed fracture: a long chamber along it, flanked by parallel walls
            o += 0.25 * ss(40, 8, f.dist);
            o -= 0.5 * ss(17, 22, f.dist) * ss(32, 27, f.dist);
        }
        o = lerp(o, 1.1, d.coreMask);
        c.open = o;
        double h = 155 * ss(0.22, 0.85, o);
        c.height = h;

        double relief = 12 * Noise.fbm(x / 95, z / 95, sRel + 17 * t, 3)
            + 5 * Noise.fbm(x / 31, z / 31, sRel + 17 * t + 5, 2);
        relief = terrace(relief, 5);
        if (t == 0) relief *= 1.8; // the deep sea has islands
        double basin = -16 * ss(0.45, 0.95, o);
        double act = d.activity;
        basin += act > 0 ? -8 * act : 18 * -act; // active systems sink into seas, extinct ones are left dry
        double fold = 10 * fold(x, z, d);
        double fl = FLOOR[t] + tilt + relief + basin + fold;
        double cap = CEIL[t] + tilt - 4 + 20 * Noise.fbm(x / 130, z / 130, sCeil + 17 * t, 3);
        double ce = smin(fl + h, cap, 30);
        if (d.coreMask > 0) {
            // the core cavity: an active core keeps a lava lake under its landmark, ringed by a walkable shore
            NetherCore k = d.cavity;
            double rho = k.distH(x, z) / k.cavityR;
            double cf = k.active() ? lerp(LAVA[t] - 7, LAVA[t] + 4, ss(0.45, 0.62, rho))
                : LAVA[t] + 7 + 3 * ss(0.5, 0.9, rho);
            fl = lerp(fl, cf, d.coreMask);
            ce = Math.max(ce, lerp(ce, CEIL[t] + tilt + 18, d.coreMask));
        }
        if (ce - fl < 5) ce = fl;
        c.floor = fl;
        c.ceil = ce;
    }

    /**
     * Folding field at (x, z) in about [-1.5, 1.5]: each of the two cores of the domain folds space along its own
     * orientation, weighted by its fold weight and its share of the mix, so the folds are continuous across borders.
     */
    public static double fold(double x, double z, NetherCores.Domain d) {
        return foldOf(d.a, d.na, d.ga, x, z) + (d.b == null ? 0 : foldOf(d.b, d.nb, 1 - d.ga, x, z));
    }

    private static double foldOf(NetherCore c, double n, double g, double x, double z) {
        double k = c.dominant == Transform.FOLD ? 1 : c.secondary == Transform.FOLD ? 0.45 : 0;
        if (k == 0 || g <= 0) return 0;
        double w = k * g * c.intensity * NetherCores.fall(n);
        double u = (x - c.x) * c.cos + (z - c.z) * c.sin, v = -(x - c.x) * c.sin + (z - c.z) * c.cos;
        return w * Math.sin(u / 58 * 2 * Math.PI + 0.8 * Math.sin(v / 170 * 2 * Math.PI));
    }

    /** Floor of tier t at (x, z) before routes and abysses (what the routes are graded on). */
    public double baseFloor(double x, double z, int t) {
        Column c = scratch.get();
        substrate.fracture(x, z, c.fracture);
        base(x, z, t, c);
        return c.floor;
    }

    /** Full base column at (x, z) for tier t (fracture included), in the thread's scratch. */
    public Column column(double x, double z, int t) {
        Column c = scratch.get();
        substrate.fracture(x, z, c.fracture);
        base(x, z, t, c);
        return c;
    }
}
