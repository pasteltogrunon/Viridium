package net.junedev.viridium.worldgen.nether;

import static net.junedev.viridium.worldgen.nether.NetherLayout.LAVA;
import static net.junedev.viridium.worldgen.nether.NetherLayout.MAX_Y;
import static net.junedev.viridium.worldgen.nether.NetherLayout.MIN_Y;
import static net.junedev.viridium.worldgen.nether.NetherLayout.OLD_LAVA;
import static net.junedev.viridium.worldgen.nether.NetherLayout.TIERS;
import static net.junedev.viridium.worldgen.terrain.core.GenMath.TAU;
import static net.junedev.viridium.worldgen.terrain.core.GenMath.floor;
import static net.junedev.viridium.worldgen.terrain.core.GenMath.lerp;
import static net.junedev.viridium.worldgen.terrain.core.GenMath.ss;

import java.util.Arrays;

import net.junedev.viridium.worldgen.terrain.core.Noise;
import net.junedev.viridium.worldgen.terrain.crust.LatticeNoise3;

/**
 * Turns a {@link NetherPlan} into the materials of one 16³ cube.
 *
 * <p>
 * Per block, in order: bedrock; the tier of the block; whether it is void (the tier cavern between its rough floor and
 * ceiling, the space under a bridge, an abyss) or rock (a bridge deck is always rock); inside the void, the forms of
 * the transformations (landmark, colonnades and arcades, folded sheets, suspended masses, growths, hollow shells);
 * inside the rock, strata, old lava marks, signature materials near cores, quartz veins and geodes. Then the network
 * keeps its function over the detail (§14, §19): chimneys decide their whole cylinder, and route clearances are carved
 * through everything; route ribs are added beside them. Lava fills supported void under the level of the tier, and
 * falls from active ceilings. Finally surfaces get the covers and small life of their ecosystem, ceilings their
 * pendants.
 *
 * <p>
 * Each column is evaluated over a few extra blocks above and below the cube, so covers, spikes and pendants agree on
 * both sides of a cube boundary.
 */
public final class NetherFiller {

    /** Blocks evaluated below and above the cube for decoration context. */
    private static final int BELOW = 4, BUF = 16 + 4 + 8;
    /** Vertical noise grid: step 4 from y0 - 8, 11 levels. */
    private static final int GY = 11;

    private static final byte AIR = NetherMat.AIR.id(), LAVA_ID = NetherMat.LAVA.id();
    private static final NetherMat[] M = NetherMat.VALUES;

    private final int seed;
    private final LatticeNoise3 nRough, nGrowA, nGrowB, nHole, nQuartz;
    private final ThreadLocal<Scratch> scratch = ThreadLocal.withInitial(Scratch::new);

    public NetherFiller(NetherWorld world) {
        this.seed = NetherSubstrate.salt(world.seed, 80);
        this.nRough = new LatticeNoise3(24, NetherSubstrate.salt(world.seed, 81));
        this.nGrowA = new LatticeNoise3(28, NetherSubstrate.salt(world.seed, 82));
        this.nGrowB = new LatticeNoise3(28, NetherSubstrate.salt(world.seed, 83));
        this.nHole = new LatticeNoise3(20, NetherSubstrate.salt(world.seed, 84));
        this.nQuartz = new LatticeNoise3(18, NetherSubstrate.salt(world.seed, 85));
    }

    /** Per-thread scratch. */
    private static final class Scratch {

        final double[][] grid = new double[5][5 * 5 * GY];
        final byte[] buf = new byte[BUF], dec = new byte[BUF];
        final Col col = new Col();
    }

    /** Per-column state shared by its blocks. */
    private static final class Col {

        int i, x, z;
        // repetition, per tier
        final boolean[] rep = new boolean[TIERS];
        final double[] repA = new double[TIERS], repD = new double[TIERS], repSf = new double[TIERS],
            repPillar = new double[TIERS];
        final boolean[] ringOn = new boolean[TIERS], pillarOn = new boolean[TIERS], ringTall = new boolean[TIERS];
        // fold patches
        final double[] foldPatch = new double[TIERS];
        // landmarks reaching this column
        final NetherCore[] lm = new NetherCore[8];
        final double[] lmRho = new double[8], lmTheta = new double[8];
        int lmN;
        // chimneys reaching this column
        final NetherRoutes.Chimney[] ch = new NetherRoutes.Chimney[4];
        final double[] chR = new double[4], chTheta = new double[4];
        int chN;
        // falls per tier
        final boolean[] fall = new boolean[TIERS];
    }

    // ------------------------------------------------------------------ noise grid

    private void prepare(Scratch s, int bx, int y0, int bz) {
        LatticeNoise3[] ns = { nRough, nGrowA, nGrowB, nHole, nQuartz };
        for (int n = 0; n < ns.length; n++) {
            double[] g = s.grid[n];
            for (int gx = 0; gx < 5; gx++) for (int gz = 0; gz < 5; gz++) for (int gy = 0; gy < GY; gy++)
                g[(gx * 5 + gz) * GY + gy] = ns[n].at(bx + gx * 4, y0 - 8 + gy * 4, bz + gz * 4);
        }
    }

    /** Trilinear sample of grid n at local (lx, y - y0 + 8, lz). */
    private static double grid(Scratch s, int n, int lx, int yy, int lz) {
        double[] g = s.grid[n];
        int gx = lx >> 2, gz = lz >> 2, gy = yy >> 2;
        if (gy >= GY - 1) gy = GY - 2;
        double fx = (lx & 3) * 0.25, fz = (lz & 3) * 0.25, fy = (yy - gy * 4) * 0.25;
        int a = (gx * 5 + gz) * GY + gy, b = ((gx + 1) * 5 + gz) * GY + gy, c = (gx * 5 + gz + 1) * GY + gy,
            d = ((gx + 1) * 5 + gz + 1) * GY + gy;
        double v0 = g[a] + (g[b] - g[a]) * fx, v1 = g[c] + (g[d] - g[c]) * fx;
        double w0 = g[a + 1] + (g[b + 1] - g[a + 1]) * fx, w1 = g[c + 1] + (g[d + 1] - g[c + 1]) * fx;
        double lo = v0 + (v1 - v0) * fz, hi = w0 + (w1 - w0) * fz;
        return lo + (hi - lo) * fy;
    }

    // ------------------------------------------------------------------ cube

    /** Materials of a cube, indexed {@code (ly << 8) | (lz << 4) | lx}; null when the cube is all air. */
    public byte[] fill(NetherPlan p, int cubeX, int cubeY, int cubeZ) {
        int y0 = cubeY << 4;
        if (y0 >= MAX_Y) return null;
        byte[] out = new byte[4096];
        if (y0 + 15 < MIN_Y) {
            Arrays.fill(out, NetherMat.BEDROCK.id());
            return out;
        }
        Scratch s = scratch.get();
        int bx = cubeX << 4, bz = cubeZ << 4;
        prepare(s, bx, y0, bz);
        boolean any = false;
        Col c = s.col;
        for (int lz = 0; lz < 16; lz++) for (int lx = 0; lx < 16; lx++) {
            int i = lz * 16 + lx;
            column(p, c, i, bx + lx, bz + lz);
            byte[] buf = s.buf;
            for (int k = 0; k < BUF; k++) buf[k] = block(p, c, s, lx, y0 - BELOW + k, lz, y0).id();
            decorate(p, c, s, y0);
            byte[] dec = s.dec;
            for (int ly = 0; ly < 16; ly++) {
                byte b = dec[ly + BELOW];
                out[(ly << 8) | i] = b;
                if (b != AIR) any = true;
            }
        }
        return any ? out : null;
    }

    private void column(NetherPlan p, Col c, int i, int x, int z) {
        c.i = i;
        c.x = x;
        c.z = z;
        double cx = x + 0.5, cz = z + 0.5;
        for (int t = 0; t < TIERS; t++) {
            double cav = p.cavity[t][i];
            double wr = p.w[t][Transform.REPETITION.ordinal()][i] * (1 - cav);
            NetherCore a = p.core[t][i];
            c.rep[t] = wr > 0.06;
            if (c.rep[t]) {
                double dx = cx - a.x, dz = cz - a.z, rho = Math.sqrt(dx * dx + dz * dz), theta = Math.atan2(dz, dx);
                double A = 10 + 0.09 * rho;
                double pr = Math.log(10 + 0.09 * rho) / 0.09 / 1.6;
                int k = (int) Math.round(pr);
                c.repA[t] = A;
                c.repD[t] = (pr - k) * 1.6 * A;
                double sArc = (theta + Math.PI) * rho / A;
                int ai = floor(sArc);
                c.repSf[t] = sArc - ai;
                c.ringOn[t] = Noise.rnd(k * 977 + ai, a.id, seed) < ss(0.05, 0.5, wr);
                c.ringTall[t] = Noise.rnd(k * 977 + ai, a.id, seed + 2) < 0.03;
                int k2 = floor(pr);
                double mid = (pr - k2 - 0.5) * 1.6 * A;
                c.repPillar[t] = Math.sqrt(mid * mid + (c.repSf[t] - 0.5) * (c.repSf[t] - 0.5) * A * A);
                c.pillarOn[t] = Noise.rnd(k2 * 977 + ai, a.id, seed + 1) < ss(0.15, 0.7, wr)
                    && c.repPillar[t] < 1.8 + 1.2 * wr;
            }
            c.foldPatch[t] = Noise.fbm(x / 70.0, z / 70.0, seed + 5 + t, 2);
            c.fall[t] = false;
        }
        for (int k = 0; k + 2 < p.falls.length; k += 3) {
            double dx = cx - p.falls[k], dz = cz - p.falls[k + 1];
            if (dx * dx + dz * dz < 1.3 * 1.3) c.fall[(int) p.falls[k + 2]] = true;
        }
        c.lmN = 0;
        for (NetherCore lc : p.landmarks) {
            double dx = cx - lc.x, dz = cz - lc.z, rho = Math.sqrt(dx * dx + dz * dz);
            if (rho < lc.cavityR * 1.05 && c.lmN < c.lm.length) {
                c.lm[c.lmN] = lc;
                c.lmRho[c.lmN] = rho;
                c.lmTheta[c.lmN] = Math.atan2(dz, dx);
                c.lmN++;
            }
        }
        c.chN = 0;
        for (NetherRoutes.Chimney ch : p.chimneys) {
            double dx = cx - ch.x, dz = cz - ch.z, r = Math.sqrt(dx * dx + dz * dz);
            if (r < ch.outer() + 1.5 && c.chN < c.ch.length) {
                c.ch[c.chN] = ch;
                c.chR[c.chN] = r;
                c.chTheta[c.chN] = Math.atan2(dz, dx);
                c.chN++;
            }
        }
    }

    private int hash(int x, int y, int z, int salt) {
        return Noise.hash(x * 73 + y, z, seed + salt);
    }

    private double rnd(int x, int y, int z, int salt) {
        return (hash(x, y, z, salt) & 0xffffffffL) * 2.3283064365386963e-10;
    }

    // ------------------------------------------------------------------ one block

    private NetherMat block(NetherPlan p, Col c, Scratch s, int lx, int y, int lz, int y0) {
        int x = c.x, z = c.z, i = c.i;
        if (y < MIN_Y) return NetherMat.BEDROCK;
        if (y >= MAX_Y) return NetherMat.AIR;
        if (y < MIN_Y + 1 + (hash(x, 0, z, 1) & 3) || y >= MAX_Y - 1 - (hash(x, 1, z, 1) & 3)) return NetherMat.BEDROCK;
        int t = p.tierAt(i, y);
        int yy = y - y0 + 8;
        double rough = (grid(s, 0, lx, yy, lz) - 0.5) * 2;

        // chimneys own their whole cylinder
        for (int k = 0; k < c.chN; k++) {
            NetherMat m = chimney(c.ch[k], c.chR[k], c.chTheta[k], y);
            if (m != null) return m;
        }

        int F = p.floor[t][i], C = p.ceil[t][i], U = p.under[t][i], D = p.deck[t][i];
        boolean corridor = p.has(t, i, NetherPlan.CORRIDOR);
        boolean deckSolid = D < F && y >= D && y < F;
        boolean v = false;
        if (!deckSolid) {
            double cav = p.cavity[t][i];
            double rf = corridor ? 0 : rough * 3.5 * (1 - cav);
            if (C > F && y >= F + rf && y < C + rough * 3.5) v = true;
            else if (U < D && y >= U && y < D) v = true;
            else {
                float aw = p.abyss[i];
                if (aw > 0 && p.fracDist[i] + 4 * rough < aw && y > p.floor[0][i] - 2 && y < p.ceil[2][i] - 6) v = true;
            }
        }
        NetherMat m;
        if (v) {
            m = overlay(p, c, s, t, lx, y, lz, y0, F, C);
            if (m == null) m = NetherMat.AIR;
        } else {
            m = rock(p, c, s, t, lx, y, lz, y0, F, rough);
            if (!deckSolid && !corridor && y > LAVA[t] + 3) {
                NetherMat g = hollow(p, c, s, t, lx, y, lz, y0, true);
                if (g != null) m = g;
            }
        }

        // routes keep their function: clearance through everything, ribs beside it
        float hd = p.hwDist[t][i];
        if (hd < 1e30f) {
            double g = p.hwGrade[t][i], half = p.hwHalf[t][i];
            if (corridor) {
                double q = Math.min(1, hd / half);
                double clear = 10 + 4 * Math.sqrt(1 - q * q);
                if (y >= Math.round(g) && y < g + clear) m = NetherMat.AIR;
            }
            // ribs stand on solid ground: not on bridges, not over abysses, not where the shoulder falls away
            if (m == NetherMat.AIR && C > g + 17 && D == F && p.abyss[i] == 0 && F >= Math.round(g) - 1) {
                double fr = p.hwAlong[t][i] / 26.0;
                fr -= Math.floor(fr);
                if (fr * 26 < 2) {
                    double q = Math.min(1, hd / half);
                    double clear = 10 + 4 * Math.sqrt(1 - q * q);
                    boolean post = hd >= half + 0.3 && hd < half + 2.3 && y >= Math.round(g) && y < g + 16;
                    boolean beam = hd < half + 2.3 && y >= g + clear && y < g + 16;
                    if (post || beam) {
                        if (hd < 1 && y + 1 >= g + 16) m = NetherMat.GLOWSTONE;
                        else m = SubstrateKind.VALUES[p.kind[t][i]].band;
                    }
                }
            }
        }

        if (m == NetherMat.AIR) {
            if (y <= LAVA[t] && p.has(t, i, NetherPlan.LAVA_ON) && y >= Math.min(U, F) - 4) return NetherMat.LAVA;
            if (c.fall[t] && !corridor && y > LAVA[t] && y <= C) return NetherMat.LAVA;
        }
        return m;
    }

    // ------------------------------------------------------------------ rock

    private NetherMat rock(NetherPlan p, Col c, Scratch s, int t, int lx, int y, int lz, int y0, int F, double rough) {
        int i = c.i;
        SubstrateKind kind = SubstrateKind.VALUES[p.kind[t][i]];
        int yy = y - y0 + 8;
        double rich = p.quartz[i] + (kind == SubstrateKind.CRYSTAL ? 0.6 : 0);
        if (Math.abs(grid(s, 4, lx, yy, lz) - 0.5) < 0.0015 + 0.005 * rich) return NetherMat.QUARTZ_ORE;
        if (p.has(t, i, NetherPlan.OLD_MARK) && (y == OLD_LAVA[t] || y == OLD_LAVA[t] + 1) && y > F + 1)
            return kind.base == NetherMat.BASALT ? NetherMat.OBSIDIAN : NetherMat.BASALT;
        double yq = y - p.strata[t][i] + rough * 2;
        double fb = yq / 14;
        boolean band = fb - Math.floor(fb) < 0.2 + 0.5 * (grid(s, 3, lx, yy, lz) - 0.5);
        double cav = p.cavity[t][i];
        if (cav > 0.2 || p.intensity[t][i] > 0.55) {
            NetherCore a = p.core[t][i];
            NetherMat sig = Landmarks.signature(a.dominant);
            if (band) return sig;
            if (cav > 0.2 && rough > 0.55) return sig;
        }
        if (t == 0 && y < F - 6) return band ? NetherMat.PERIDOTITE : NetherMat.KOMATIITE;
        return band ? kind.band : kind.base;
    }

    // ------------------------------------------------------------------ transformations in the void

    private NetherMat overlay(NetherPlan p, Col c, Scratch s, int t, int lx, int y, int lz, int y0, int F, int C) {
        int i = c.i, x = c.x, z = c.z;
        double yc = y + 0.5;
        for (int k = 0; k < c.lmN; k++) {
            NetherCore lc = c.lm[k];
            if (lc.tier != t) continue;
            double dx = x + 0.5 - lc.x, dz = z + 0.5 - lc.z;
            NetherMat m = Landmarks.at(lc, dx, yc - lc.y, dz, c.lmRho[k], c.lmTheta[k]);
            if (m != null) return m;
        }
        double cav = p.cavity[t][i], keep = 1 - cav;
        if (keep <= 0.05) return null;
        double inten = p.intensity[t][i];
        SubstrateKind kind = SubstrateKind.VALUES[p.kind[t][i]];

        // repetition: arcades on rings around the core, closer and closer towards it, and free columns
        if (c.rep[t]) {
            if (c.pillarOn[t]) return inten > 0.5 ? NetherMat.BASALT : kind.band;
            if (c.ringOn[t] && Math.abs(c.repD[t]) < 1.5) {
                double A = c.repA[t], sf = c.repSf[t];
                double hp = 0.45 * A * (c.ringTall[t] ? 1.8 : 1), rise = 0.39 * A, top = F + hp + rise + 3;
                if (y < top) {
                    if (sf < 0.22) return inten > 0.5 ? NetherMat.BASALT : kind.band;
                    double xp = (sf - 0.61) / 0.39;
                    if (yc >= F + hp + rise * Math.sqrt(Math.max(0, 1 - xp * xp)))
                        return inten > 0.5 ? NetherMat.BASALT : kind.band;
                }
            }
        }

        // folding: curved, partial sheets (inclined ledges) following the folded strata
        double wf = p.w[t][Transform.FOLD.ordinal()][i] * keep;
        if (wf > 0.08 && y > F + 5 && y < C - 5 && c.foldPatch[t] > 0.55 - 0.45 * wf) {
            double yw = y - p.strata[t][i];
            double mm = yw - 24 * Math.floor(yw / 24);
            if (mm < 1.4 + 2.2 * wf) return inten > 0.55 ? NetherMat.JASPER : kind.band;
        }

        // suspension: masses detached from walls and ceilings, with a hanging cone
        double ws = p.w[t][Transform.SUSPENSION.ordinal()][i] * keep;
        if (ws > 0.06) {
            int ci = floor(x / 40.0), cj = floor(y / 30.0), ck = floor(z / 40.0);
            int hs = Noise.hash(ci, ck, seed + 11);
            if (Noise.rnd(hs, cj, seed + 12) < ss(0.05, 0.6, ws) * 0.85) {
                double r = Math.min(14, 3 + 9 * Math.min(1, ws) * (0.5 + Noise.rnd(hs, cj, seed + 13)));
                double ry = 0.55 * r, cone = 1.6 * ry;
                double mx = ci * 40 + r + 2 + Noise.rnd(hs, cj, seed + 14) * (36 - 2 * r);
                double mz = ck * 40 + r + 2 + Noise.rnd(hs, cj, seed + 15) * (36 - 2 * r);
                double lo = cj * 30 + cone + 1, hi = (cj + 1) * 30 - ry - 1;
                double my = lo + Noise.rnd(hs, cj, seed + 16) * Math.max(0, hi - lo);
                double ex = x + 0.5 - mx, ez = z + 0.5 - mz, ey = yc - my, h2 = ex * ex + ez * ez;
                if (ey >= 0) {
                    if (h2 / (r * r) + ey * ey / (ry * ry) < 1) return inten > 0.4 ? NetherMat.PUMICE : kind.base;
                } else if (ey > -cone) {
                    double hr = r * (1 + ey / cone);
                    if (h2 < hr * hr) return inten > 0.4 ? NetherMat.OBSIDIAN : kind.band;
                }
            }
        }

        // growth: branching veins and protrusions, denser near floors and ceilings
        double wg = p.w[t][Transform.GROWTH.ordinal()][i] * keep;
        if (wg > 0.05) {
            int yy = y - y0 + 8;
            double near = Math.min(y - F, C - y);
            double tg = (0.01 + 0.045 * Math.min(1, wg)) * lerp(1.3, 0.6, ss(0, 40, near));
            if (Math.abs(grid(s, 1, lx, yy, lz) - 0.5) < tg && Math.abs(grid(s, 2, lx, yy, lz) - 0.5) < tg) {
                if (grid(s, 3, lx, yy, lz) > 0.74 && p.activity[t][i] > -0.2) return NetherMat.GLOWSTONE;
                return inten > 0.45 ? NetherMat.BONE : NetherMat.CHALCEDONY;
            }
        }

        // hollowing: shells (nested when large) floating in the void
        return hollow(p, c, s, t, lx, y, lz, y0, false);
    }

    /**
     * Hollow shells of the hollowing transformation, on a 56-block 3D lattice. In the void they are envelopes with
     * windows; in rock they are geodes: a lined cavity. Returns null where nothing changes.
     */
    private NetherMat hollow(NetherPlan p, Col c, Scratch s, int t, int lx, int y, int lz, int y0, boolean inRock) {
        int i = c.i;
        double wh = p.w[t][Transform.HOLLOWING.ordinal()][i] * (1 - p.cavity[t][i]);
        if (wh < 0.08) return null;
        int x = c.x, z = c.z;
        int ci = floor(x / 56.0), cj = floor(y / 56.0), ck = floor(z / 56.0);
        int hs = Noise.hash(ci, ck, seed + 21);
        if (Noise.rnd(hs, cj, seed + 22) >= ss(0.05, 0.6, wh) * 0.6) return null;
        double R = 6 + 14 * Noise.rnd(hs, cj, seed + 23) * (0.5 + Math.min(1, wh));
        R = Math.min(R, 24);
        double m = R + 3;
        double mx = ci * 56 + m + Noise.rnd(hs, cj, seed + 24) * Math.max(0, 56 - 2 * m);
        double my = cj * 56 + m + Noise.rnd(hs, cj, seed + 25) * Math.max(0, 56 - 2 * m);
        double mz = ck * 56 + m + Noise.rnd(hs, cj, seed + 26) * Math.max(0, 56 - 2 * m);
        double dx = x + 0.5 - mx, dy = y + 0.5 - my, dz = z + 0.5 - mz;
        double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
        double th = 1.5 + 1.5 * Math.min(1, wh);
        if (d > R + th) return null;
        boolean active = p.activity[t][i] > 0;
        if (Math.abs(d - R) < th) {
            if (inRock) return NetherMat.CHALCEDONY;
            if (grid(s, 3, lx, y - y0 + 8, lz) > 0.6) return null; // windows
            return active ? NetherMat.OBSIDIAN : NetherMat.CHALCEDONY;
        }
        if (R > 13 && Math.abs(d - 0.45 * R) < 1.2) return inRock ? NetherMat.OBSIDIAN : NetherMat.CHALCEDONY;
        if (d < R - th) return inRock ? NetherMat.AIR : null;
        return null;
    }

    // ------------------------------------------------------------------ chimneys

    private static double angDist(double a, double b) {
        double d = a - b + Math.PI;
        d -= TAU * Math.floor(d / TAU);
        return Math.abs(d - Math.PI);
    }

    private static NetherMat chimney(NetherRoutes.Chimney ch, double r, double theta, int y) {
        if (r >= ch.outer() || y < ch.bottom - 6 || y > ch.top + 8) return null;
        if (y < ch.bottom) return NetherMat.BASALT; // foundation
        if (y > ch.top + 2) return NetherMat.AIR; // a clean landing over the mouth
        if (r < ch.r) {
            if (r >= ch.r - NetherRoutes.Chimney.LEDGE) {
                double phi = theta - ch.theta0;
                phi -= TAU * Math.floor(phi / TAU);
                double m = (y - ch.bottom) / ch.rate - phi;
                double sweep = phi + TAU * Math.ceil(m / TAU);
                if (sweep >= 0 && sweep <= ch.total && ch.ledgeY(sweep) - y < 3) return NetherMat.TUFF;
            }
            if (ch.lavafall && r < 1.6 && y < ch.top) return NetherMat.LAVA;
            return NetherMat.AIR;
        }
        // the wall: door at the bottom, exit gap at the top, a window opposite the door at every turn
        double door = 3.2 / ch.r;
        if (y < ch.bottom + 6 && angDist(theta, ch.theta0) < door) return NetherMat.AIR;
        if (y >= ch.top && angDist(theta, ch.exitAngle()) < door) return NetherMat.AIR;
        if (angDist(theta, ch.theta0 + Math.PI) < 2.2 / ch.r) {
            double k = Math.round(((y - ch.bottom) / ch.rate - Math.PI) / TAU);
            double sweep = Math.PI + TAU * k;
            if (sweep >= 0 && sweep <= ch.total) {
                double hy = Math.floor(ch.ledgeY(sweep));
                if (y == hy) return NetherMat.GLOWSTONE;
                if (y > hy && y < hy + 5) return NetherMat.AIR;
            }
        }
        if (y == ch.top + 2) return NetherMat.GLOWSTONE;
        return Math.floorMod(y, 9) == 0 ? NetherMat.OBSIDIAN : NetherMat.BASALT;
    }

    // ------------------------------------------------------------------ surfaces and life

    private static boolean rocky(NetherMat m) {
        return switch (m) {
            case NETHERRACK, BASALT, SCORIA, TUFF, JASPER, CHALCEDONY, PUMICE, PERIDOTITE, KOMATIITE -> true;
            default -> false;
        };
    }

    private void decorate(NetherPlan p, Col c, Scratch s, int y0) {
        byte[] buf = s.buf, dec = s.dec;
        System.arraycopy(buf, 0, dec, 0, BUF);
        int i = c.i, x = c.x, z = c.z;
        int yb = y0 - BELOW;
        // covers on exposed floors (two deep for loose covers), path on the routes, lava in fissures
        for (int k = BUF - 2; k >= 1; k--) {
            NetherMat m = M[buf[k]], above = M[buf[k + 1]];
            if (!m.solid() || above.solid() || above == NetherMat.LAVA) continue;
            int y = yb + k, t = p.tierAt(i, y);
            if (inChimney(c, y)) continue;
            int F = p.floor[t][i];
            if (y < F - 6 || y > F + 6) continue;
            if (p.has(t, i, NetherPlan.CORRIDOR) && Math.abs(y + 1 - Math.round(p.hwGrade[t][i])) <= 1) {
                dec[k] = NetherMat.PATH.id();
                continue;
            }
            if (!rocky(m)) continue;
            if (p.has(t, i, NetherPlan.FISSURE)) {
                dec[k] = LAVA_ID;
                continue;
            }
            NetherMat cover;
            boolean deep = true;
            switch (Eco.VALUES[p.eco[t][i]]) {
                case CRUST -> cover = rnd(x, y, z, 3) < 0.08 ? NetherMat.OBSIDIAN : NetherMat.SCORIA;
                case ASH -> cover = NetherMat.ASH;
                case SOUL, OSSUARY -> cover = NetherMat.SOUL_SAND;
                case FUNGAL -> {
                    cover = NetherMat.NETHERRACK;
                    deep = false;
                }
                case REEF -> {
                    cover = rnd(x, y, z, 4) < 0.12 ? NetherMat.QUARTZ_ORE : m;
                    deep = false;
                }
                default -> {
                    cover = SubstrateKind.VALUES[p.kind[t][i]].floor;
                    deep = false;
                }
            }
            dec[k] = cover.id();
            if (deep && rocky(M[buf[k - 1]])) dec[k - 1] = cover.id();
        }
        // small life on floors
        for (int k = 1; k < BUF - 3; k++) {
            if (buf[k] != AIR || dec[k] != AIR) continue;
            NetherMat below = M[dec[k - 1]];
            if (!below.solid()) continue;
            int y = yb + k, t = p.tierAt(i, y);
            if (inChimney(c, y)) continue;
            if (y > p.floor[t][i] + 8 || p.has(t, i, NetherPlan.CORRIDOR)) continue;
            double r = rnd(x, y, z, 5);
            switch (Eco.VALUES[p.eco[t][i]]) {
                case FUNGAL -> {
                    if (r < 0.1) dec[k] = (r < 0.05 ? NetherMat.BROWN_MUSHROOM : NetherMat.RED_MUSHROOM).id();
                }
                case SOUL -> {
                    if (r < 0.06 && below == NetherMat.SOUL_SAND) dec[k] = NetherMat.NETHER_WART.id();
                }
                case OSSUARY -> {
                    if (r < 0.03 && below == NetherMat.SOUL_SAND) dec[k] = NetherMat.NETHER_WART.id();
                    else if (r < 0.06) spike(buf, dec, k, 1 + (hash(x, y, z, 6) & 1), NetherMat.BONE);
                }
                case REEF -> {
                    if (r < 0.02) spike(buf, dec, k, 1 + (hash(x, y, z, 6) & 1), NetherMat.BONE);
                    else if (r < 0.025) dec[k] = NetherMat.GLOWSTONE.id();
                }
                default -> {
                    if (r < 0.02 && below == NetherMat.NETHERRACK && p.activity[t][i] > 0.2)
                        dec[k] = NetherMat.FIRE.id();
                }
            }
        }
        // pendants of glowstone under ceilings (inverted gardens are dense)
        for (int k = BUF - 2; k >= 1; k--) {
            if (buf[k] != AIR || !M[buf[k + 1]].solid()) continue;
            int y = yb + k, t = p.tierAt(i, y);
            if (inChimney(c, y)) continue;
            if (p.has(t, i, NetherPlan.CORRIDOR) && y < p.hwGrade[t][i] + 18) continue; // keep the head room
            boolean garden = p.has(t, i, NetherPlan.GARDEN);
            if (rnd(x, y, z, 7) >= (garden ? 0.06 : 0.006)) continue;
            int len = 1 + (hash(x, y, z, 8) & (garden ? 7 : 3));
            for (int j = 0; j < len && k - j >= 0 && buf[k - j] == AIR; j++) dec[k - j] = NetherMat.GLOWSTONE.id();
        }
    }

    /** Chimneys keep their ledges, doors and landings bare. */
    private static boolean inChimney(Col c, int y) {
        for (int k = 0; k < c.chN; k++) {
            NetherRoutes.Chimney ch = c.ch[k];
            if (c.chR[k] < ch.outer() + 1 && y >= ch.bottom - 7 && y <= ch.top + 9) return true;
        }
        return false;
    }

    private static void spike(byte[] buf, byte[] dec, int k, int h, NetherMat m) {
        for (int j = 0; j < h && k + j < buf.length && buf[k + j] == AIR; j++) dec[k + j] = m.id();
    }
}
