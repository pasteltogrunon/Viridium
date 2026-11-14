package net.junedev.viridium.worldgen.nether;

import static net.junedev.viridium.worldgen.terrain.core.GenMath.TAU;
import static net.junedev.viridium.worldgen.terrain.core.GenMath.clamp;
import static net.junedev.viridium.worldgen.terrain.core.GenMath.ss;

import net.junedev.viridium.worldgen.terrain.core.Noise;

/**
 * The landmark of a core (design §7.3): the same rules as its domain, carried to their maximum expression, inside the
 * core cavity. Each dominant {@link Transform} has its own monumental form; an active core lights it with glowstone,
 * an extinct one leaves it dark.
 *
 * <ul>
 * <li>Folding: three curtains wrapping the cavity, folded with height, with openings.</li>
 * <li>Repetition: three rings of colonnades with lintels, and one anomaly (a missing pillar, a swollen one).</li>
 * <li>Suspension: a spiral of floating masses over the lake, each with a hanging cone.</li>
 * <li>Growth: a mineral tree with branches ending in luminous bulbs.</li>
 * <li>Hollowing: nested envelopes with windows around a small heart.</li>
 * </ul>
 */
final class Landmarks {

    private Landmarks() {}

    /**
     * Material of the landmark at an offset from the core centre (dx, dy, dz; rho and theta are the horizontal polar
     * coordinates), or null where the landmark leaves the cavity empty.
     */
    static NetherMat at(NetherCore c, double dx, double dy, double dz, double rho, double theta) {
        double R = c.cavityR;
        double a0 = Noise.rnd(c.id, 3, 0x1a4d) * TAU;
        NetherMat light = c.extinct() ? NetherMat.BASALT : NetherMat.GLOWSTONE;
        switch (c.dominant) {
            case FOLD: {
                for (int k = 0; k < 3; k++) {
                    if (dy < -35 + 5 * k || dy > 48 - 6 * k) continue;
                    double rr = R * (0.3 + 0.22 * k) + 0.08 * R * Math.sin(2 * theta + dy / 14 + 1.3 * k + a0);
                    if (Math.abs(rho - rr) < 1.4 + 0.4 * k) {
                        if (Math.sin(3 * theta + 1.7 * k + dy / 30 + a0) > 0.55) continue;
                        if (k == 0 && Math.abs(dy - 40) < 1) return light;
                        return k == 1 ? c.substrate.band : NetherMat.JASPER;
                    }
                }
                return null;
            }
            case REPETITION: {
                for (int k = 0; k < 3; k++) {
                    double rk = R * (0.28 + 0.24 * k);
                    if (Math.abs(rho - rk) > 4) continue;
                    int n = Math.max(6, (int) Math.round(TAU * rk / 9));
                    double step = TAU / n, th = theta - a0;
                    th -= TAU * Math.floor(th / TAU);
                    int j = (int) Math.floor(th / step);
                    int anomaly = Math.floorMod(c.id >>> 3, n);
                    double ct = (j + 0.5) * step;
                    double d2 = rho * rho + rk * rk - 2 * rho * rk * Math.cos(th - ct);
                    double pr = (k == 2 && j == anomaly) ? 3.6 : 2.3;
                    if (k == 1 && j == anomaly) pr = 0; // the missing pillar
                    // a colonnade under its lintel; one pillar in four goes on up to hold the vault
                    if (d2 < pr * pr && (dy < 26 || (j & 3) == 0))
                        return dy >= 20 && dy < 22 ? light : NetherMat.BASALT;
                    if (k >= 1 && Math.abs(rho - rk) < 1.6 && dy >= 23 && dy < 26) return NetherMat.OBSIDIAN;
                }
                return null;
            }
            case SUSPENSION: {
                for (int i = 0; i < 9; i++) {
                    double a = a0 + 2.4 * i, ri = R * (0.12 + 0.07 * i), hy = -12 + 7 * i;
                    double s = Math.max(4, R * (0.13 - 0.008 * i)), ry = 0.55 * s;
                    double ex = dx - ri * Math.cos(a), ey = dy - hy, ez = dz - ri * Math.sin(a);
                    if (Math.abs(ex) > s || Math.abs(ez) > s) continue;
                    double h2 = ex * ex + ez * ez;
                    if (ey >= 0) {
                        if (h2 / (s * s) + ey * ey / (ry * ry) < 1)
                            return h2 + ey * ey < 4 && !c.extinct() ? NetherMat.GLOWSTONE : NetherMat.PUMICE;
                    } else if (ey > -1.8 * ry) {
                        double hr = s * (1 + ey / (1.8 * ry));
                        if (h2 < hr * hr) return NetherMat.OBSIDIAN;
                    }
                }
                return null;
            }
            case GROWTH: {
                if (dy < 22 && rho < 5.5 * (1 - 0.35 * ss(-40, 22, dy))) return NetherMat.BONE;
                double len = 0.5 * R;
                for (int b = 0; b < 7; b++) {
                    double a = a0 + b * 0.9 + 0.4 * Noise.rnd(c.id, b, 0x77), e = 0.2 + 0.5 * Noise.rnd(c.id, b, 0x78);
                    double ux = Math.cos(a) * Math.cos(e), uy = Math.sin(e), uz = Math.sin(a) * Math.cos(e);
                    double sy = 12 + 3 * b;
                    double qx = dx, qy = dy - sy, qz = dz;
                    double s = clamp((qx * ux + qy * uy + qz * uz) / len, 0, 1);
                    double ex = qx - ux * len * s, ey = qy - uy * len * s, ez = qz - uz * len * s;
                    double d2 = ex * ex + ey * ey + ez * ez;
                    double tx = qx - ux * len, ty = qy - uy * len, tz = qz - uz * len;
                    if (tx * tx + ty * ty + tz * tz < 3.5 * 3.5) return light;
                    double rad = 3 - 1.5 * s;
                    if (d2 < rad * rad) return NetherMat.BONE;
                }
                return null;
            }
            case HOLLOWING: {
                double dist = Math.sqrt(rho * rho + dy * dy), psi = Math.atan2(dy, rho);
                double r1 = 0.55 * R, r2 = 0.28 * R, r3 = 0.08 * R + 3;
                if (Math.abs(dist - r1) < 1.6)
                    return Math.cos(4 * theta + a0) * Math.cos(3 * psi) > 0.45 ? null : NetherMat.OBSIDIAN;
                if (Math.abs(dist - r2) < 1.1)
                    return Math.cos(3 * theta + a0) * Math.cos(2 * psi + 1) > 0.5 ? null : NetherMat.CHALCEDONY;
                if (dist < r3) return c.extinct() ? NetherMat.OBSIDIAN : NetherMat.GLOWSTONE;
                return null;
            }
            default:
                return null;
        }
    }

    /** Material that marks the walls near a core with its transformation (§16: signals of proximity). */
    static NetherMat signature(Transform t) {
        return switch (t) {
            case FOLD -> NetherMat.JASPER;
            case REPETITION -> NetherMat.BASALT;
            case SUSPENSION -> NetherMat.PUMICE;
            case GROWTH -> NetherMat.BONE;
            case HOLLOWING -> NetherMat.OBSIDIAN;
        };
    }
}
