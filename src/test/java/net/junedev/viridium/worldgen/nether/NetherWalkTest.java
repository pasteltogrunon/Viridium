package net.junedev.viridium.worldgen.nether;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongPredicate;

import org.junit.jupiter.api.Test;

/**
 * Navigation as part of the terrain (design §11.3, §12.3): walks the generated blocks with ordinary movement (steps of
 * at most one block up or down, two blocks of head room, nothing but air, plants and fire to walk through) and checks
 * that every chimney leads from its door to its exit, and that main routes lead from one core cavity to the next.
 */
class NetherWalkTest {

    /** How many chimneys and routes each test walks (VIRIDIUM_NETHER_WALK_N, default 6). */
    static final int SAMPLES = Integer.parseInt(
        System.getenv()
            .getOrDefault("VIRIDIUM_NETHER_WALK_N", "6"));

    /** Cubes generated on demand from the real planner and filler. */
    static final class Voxels {

        final NetherPlanner planner;
        final NetherFiller filler;
        final Map<Long, NetherPlan> plans = new ConcurrentHashMap<>();
        final Map<Long, byte[]> cubes = new ConcurrentHashMap<>();
        static final byte[] EMPTY = new byte[4096];

        Voxels(NetherWorld w) {
            planner = new NetherPlanner(w);
            filler = new NetherFiller(w);
        }

        NetherPlan plan(int cx, int cz) {
            return plans.computeIfAbsent(((long) cx << 32) ^ (cz & 0xffffffffL), k -> planner.plan(cx, cz));
        }

        NetherMat at(int x, int y, int z) {
            int cx = x >> 4, cy = y >> 4, cz = z >> 4;
            long key = ((long) cx * 73856093L) ^ ((long) cy * 19349663L) ^ ((long) cz * 83492791L);
            byte[] c = cubes.computeIfAbsent(key, k -> {
                byte[] m = filler.fill(plan(cx, cz), cx, cy, cz);
                return m == null ? EMPTY : m;
            });
            return NetherMat.VALUES[c[((y & 15) << 8) | ((z & 15) << 4) | (x & 15)]];
        }

        boolean passable(int x, int y, int z) {
            NetherMat m = at(x, y, z);
            return !m.solid() && m != NetherMat.LAVA;
        }

        boolean stand(int x, int y, int z) {
            return passable(x, y, z) && passable(x, y + 1, z) && at(x, y - 1, z).solid();
        }
    }

    static long col(int x, int z) {
        return ((long) x << 32) ^ (z & 0xffffffffL);
    }

    /** Columns within {@code margin} of a route axis. */
    static Set<Long> band(NetherRoutes.Route r, double margin) {
        Set<Long> s = new HashSet<>();
        for (int k = 0; k < r.segments(); k++) {
            int x0 = (int) Math.floor(Math.min(r.px[k], r.px[k + 1]) - margin),
                x1 = (int) Math.ceil(Math.max(r.px[k], r.px[k + 1]) + margin);
            int z0 = (int) Math.floor(Math.min(r.pz[k], r.pz[k + 1]) - margin),
                z1 = (int) Math.ceil(Math.max(r.pz[k], r.pz[k + 1]) + margin);
            for (int x = x0; x <= x1; x++) for (int z = z0; z <= z1; z++) {
                NetherRoutes.Hit h = new NetherRoutes.Hit();
                r.probe(k, x + 0.5, z + 0.5, h);
                if (h.dist <= margin) s.add(col(x, z));
            }
        }
        return s;
    }

    static long key(int x, int y, int z) {
        return ((long) (x & 0x1fffff) << 42) | ((long) (y & 0x1fffff) << 21) | (z & 0x1fffff);
    }

    static int maxY;
    static double goalX, goalZ, bestD;
    static int[] bestAt;
    static int[] maxAt;

    /** Breadth-first walk from the start cells; true when a goal cell is reached. */
    static boolean walk(Voxels v, List<int[]> starts, LongPredicate allowedColumn, int[] box, Goal goal, int limit) {
        ArrayDeque<int[]> q = new ArrayDeque<>();
        maxY = Integer.MIN_VALUE;
        bestD = Double.MAX_VALUE;
        Set<Long> seen = new HashSet<>();
        for (int[] s : starts) if (seen.add(key(s[0], s[1], s[2]))) q.add(s);
        int[][] dirs = { { 1, 0 }, { -1, 0 }, { 0, 1 }, { 0, -1 } };
        while (!q.isEmpty() && seen.size() < limit) {
            int[] p = q.poll();
            double gd = Math.hypot(p[0] - goalX, p[2] - goalZ);
            if (gd < bestD) {
                bestD = gd;
                bestAt = p;
            }
            if (p[1] > maxY) {
                maxY = p[1];
                maxAt = p;
            }
            if (goal.reached(p[0], p[1], p[2])) return true;
            for (int[] d : dirs) {
                int nx = p[0] + d[0], nz = p[2] + d[1];
                if (nx < box[0] || nx > box[1] || nz < box[2] || nz > box[3]) continue;
                if (!allowedColumn.test(col(nx, nz))) continue;
                for (int dy = 1; dy >= -1; dy--) {
                    int ny = p[1] + dy;
                    if (dy == 1 && !v.passable(p[0], p[1] + 2, p[2])) continue; // head room to jump
                    if (!v.stand(nx, ny, nz)) continue;
                    if (dy == -1 && !v.passable(nx, ny + 2, nz)) continue; // reversible: room to jump back
                    if (seen.add(key(nx, ny, nz))) q.add(new int[] { nx, ny, nz });
                    break;
                }
            }
        }
        return false;
    }

    interface Goal {

        boolean reached(int x, int y, int z);
    }

    static List<int[]> standNear(Voxels v, double x, double z, int y0, int range, int dy) {
        List<int[]> out = new ArrayList<>();
        for (int ix = (int) Math.floor(x) - range; ix <= (int) Math.floor(x) + range; ix++)
            for (int iz = (int) Math.floor(z) - range; iz <= (int) Math.floor(z) + range; iz++)
                for (int y = y0 - dy; y <= y0 + dy; y++) if (v.stand(ix, y, iz)) out.add(new int[] { ix, y, iz });
        return out;
    }

    @Test
    @org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named = "VIRIDIUM_NETHER_CHIMNEY", matches = ".+")
    void dumpChimney() {
        NetherWorld w = NetherViewTest.world();
        Voxels v = new Voxels(w);
        String[] a = System.getenv("VIRIDIUM_NETHER_CHIMNEY")
            .split(",");
        double qx = Double.parseDouble(a[0]), qz = Double.parseDouble(a[1]);
        int dy0 = Integer.parseInt(a[2]), dy1 = Integer.parseInt(a[3]);
        List<NetherRoutes.Chimney> chims = new ArrayList<>();
        w.routes.collect(qx - 2, qz - 2, qx + 2, qz + 2, 0, null, chims);
        NetherRoutes.Chimney c = chims.get(0);
        System.out.printf(
            "chimney %.1f,%.1f r%.1f %d..%d theta0 %.2f exit %.2f total %.2f%n",
            c.x,
            c.z,
            c.r,
            c.bottom,
            c.top,
            c.theta0,
            c.exitAngle() % (2 * Math.PI),
            c.total);
        List<NetherRoutes.Route> rr = new ArrayList<>();
        w.routes.collect(c.x - 30, c.z - 30, c.x + 30, c.z + 30, 0, rr, null);
        for (NetherRoutes.Route r : rr) {
            StringBuilder s = new StringBuilder(String.format("route t%d main=%b n=%d:", r.tier, r.main, r.px.length));
            for (int k = 0; k < Math.min(6, r.px.length); k++)
                s.append(String.format(" (%.0f,%.0f g%.1f)", r.px[k], r.pz[k], r.grade[k]));
            System.out.println(s);
        }
        int R = (int) c.outer() + 4;
        for (int y = c.top + dy1; y >= c.top + dy0; y--) {
            StringBuilder sb = new StringBuilder("y=" + y + "\n");
            for (int z = (int) c.z - R; z <= (int) c.z + R; z++) {
                for (int x = (int) c.x - R; x <= (int) c.x + R; x++) {
                    NetherMat m = v.at(x, y, z);
                    char ch = switch (m) {
                        case AIR -> '.';
                        case LAVA -> '~';
                        case TUFF -> 'T';
                        case BASALT -> '#';
                        case OBSIDIAN -> 'O';
                        case GLOWSTONE -> 'g';
                        case PATH -> '=';
                        default -> m.solid() ? 'x' : ',';
                    };
                    sb.append(ch);
                }
                sb.append('\n');
            }
            System.out.print(sb);
        }
    }

    @Test
    @org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named = "VIRIDIUM_NETHER_BOX", matches = ".+")
    void dumpBox() {
        NetherWorld w = NetherViewTest.world();
        Voxels v = new Voxels(w);
        String[] a = System.getenv("VIRIDIUM_NETHER_BOX")
            .split(",");
        int x0 = Integer.parseInt(a[0]), z0 = Integer.parseInt(a[1]), x1 = Integer.parseInt(a[2]),
            z1 = Integer.parseInt(a[3]), y0 = Integer.parseInt(a[4]), y1 = Integer.parseInt(a[5]);
        List<NetherRoutes.Route> rr = new ArrayList<>();
        w.routes.collect(x0, z0, x1, z1, 0, rr, null);
        for (NetherRoutes.Route r : rr) {
            StringBuilder s = new StringBuilder(String.format("route t%d main=%b n=%d:", r.tier, r.main, r.px.length));
            for (int k = 0; k < r.px.length; k++)
                if (r.px[k] >= x0 - 20 && r.px[k] <= x1 + 20 && r.pz[k] >= z0 - 20 && r.pz[k] <= z1 + 20)
                    s.append(String.format(" (%.0f,%.0f g%.1f)", r.px[k], r.pz[k], r.grade[k]));
            System.out.println(s);
        }
        for (int t = 0; t < 3; t++) {
            NetherCore c = w.cores.nearest(x0, z0, t, 1);
            System.out.println("  nearest t" + t + " " + c + " cav " + (c == null ? 0 : c.cavityR));
        }
        for (int y = y1; y >= y0; y--) {
            StringBuilder sb = new StringBuilder("y=" + y + "\n");
            for (int z = z0; z <= z1; z++) {
                for (int x = x0; x <= x1; x++) sb.append(glyph(v.at(x, y, z)));
                sb.append('\n');
            }
            System.out.print(sb);
        }
    }

    static char glyph(NetherMat m) {
        return switch (m) {
            case AIR -> '.';
            case LAVA -> '~';
            case TUFF -> 'T';
            case BASALT -> '#';
            case OBSIDIAN -> 'O';
            case GLOWSTONE -> 'g';
            case PATH -> '=';
            case BONE -> 'b';
            case PUMICE -> 'p';
            case JASPER -> 'j';
            case CHALCEDONY -> 'c';
            default -> m.solid() ? 'x' : ',';
        };
    }

    @Test
    void chimneysAreClimbable() {
        NetherWorld w = NetherViewTest.world();
        Voxels v = new Voxels(w);
        List<NetherRoutes.Chimney> chims = new ArrayList<>();
        w.routes.collect(-2400, -2400, 2400, 2400, 0, null, chims);
        assertTrue(chims.size() > 3, "few chimneys: " + chims.size());
        int ok = 0, n = 0;
        StringBuilder fails = new StringBuilder();
        for (NetherRoutes.Chimney c : chims) {
            if (n >= SAMPLES) break;
            n++;
            double out = c.outer() + 3;
            double ex = c.x + Math.cos(c.exitAngle()) * out, ez = c.z + Math.sin(c.exitAngle()) * out;
            List<int[]> starts = standNear(
                v,
                c.x + Math.cos(c.theta0) * out,
                c.z + Math.sin(c.theta0) * out,
                c.bottom,
                2,
                3);
            int R = (int) Math.ceil(c.outer()) + 6;
            int[] box = { (int) c.x - R, (int) c.x + R, (int) c.z - R, (int) c.z + R };
            boolean reached = !starts.isEmpty() && walk(
                v,
                starts,
                k -> true,
                box,
                (x, y, z) -> Math.abs(x + 0.5 - ex) < 2.5 && Math.abs(z + 0.5 - ez) < 2.5 && Math.abs(y - c.top) <= 3,
                2_000_000);
            if (reached) ok++;
            else fails.append(
                String.format(
                    " [%.0f,%.0f %d..%d starts=%d max %d at %d,%d r%.1f phi%.2f]",
                    c.x,
                    c.z,
                    c.bottom,
                    c.top,
                    starts.size(),
                    maxY,
                    maxAt[0],
                    maxAt[2],
                    Math.hypot(maxAt[0] + 0.5 - c.x, maxAt[2] + 0.5 - c.z),
                    (Math.atan2(maxAt[2] + 0.5 - c.z, maxAt[0] + 0.5 - c.x) - c.theta0 + 20 * Math.PI)
                        % (2 * Math.PI)));
        }
        System.out.println("chimneys climbable: " + ok + "/" + n + fails);
        assertTrue(ok == n, "not climbable:" + fails);
    }

    @Test
    void mainRoutesAreWalkable() {
        NetherWorld w = NetherViewTest.world();
        Voxels v = new Voxels(w);
        List<NetherRoutes.Route> routes = new ArrayList<>();
        w.routes.collect(-1800, -1800, 1800, 1800, 0, routes, null);
        int ok = 0, n = 0;
        StringBuilder fails = new StringBuilder();
        for (NetherRoutes.Route r : routes) {
            if (!r.main) continue;
            if (n >= SAMPLES) break;
            n++;
            int last = r.px.length - 1;
            List<int[]> starts = standNear(v, r.px[0], r.pz[0], (int) Math.round(r.grade[0]), 2, 3);
            double ex = r.px[last], ez = r.pz[last];
            int gy = (int) Math.round(r.grade[last]);
            goalX = ex;
            goalZ = ez;
            int[] box = { (int) r.minX - 10, (int) r.maxX + 10, (int) r.minZ - 10, (int) r.maxZ + 10 };
            Set<Long> band = band(r, r.half + 4);
            boolean reached = !starts.isEmpty() && walk(
                v,
                starts,
                band::contains,
                box,
                (x, y, z) -> Math.abs(x + 0.5 - ex) < 3 && Math.abs(z + 0.5 - ez) < 3 && Math.abs(y - gy) <= 4,
                3_000_000);
            if (reached) ok++;
            else fails.append(
                String.format(
                    " [t%d %.0f,%.0f -> %.0f,%.0f starts=%d best %.0f at %d,%d,%d]",
                    r.tier,
                    r.px[0],
                    r.pz[0],
                    ex,
                    ez,
                    starts.size(),
                    bestD,
                    bestAt[0],
                    bestAt[1],
                    bestAt[2]));
        }
        System.out.println("main routes walkable: " + ok + "/" + n + fails);
        assertTrue(ok == n, "not walkable:" + fails);
    }
}
