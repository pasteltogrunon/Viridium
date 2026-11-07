package net.junedev.viridium.worldgen.terrain.stages;

import static net.junedev.viridium.worldgen.terrain.core.GenMath.floor;
import static net.junedev.viridium.worldgen.terrain.core.Noise.fbm;
import static net.junedev.viridium.worldgen.terrain.core.Noise.rnd;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import net.junedev.viridium.worldgen.terrain.WorldGen;
import net.junedev.viridium.worldgen.terrain.core.GenParams;
import net.junedev.viridium.worldgen.terrain.core.Seeds;
import net.junedev.viridium.worldgen.terrain.pipeline.GenPoint;
import net.junedev.viridium.worldgen.terrain.pipeline.GenScratch;

/**
 * Drainage basins: the continents are divided into warped Voronoi cells (~{@code basinSize} blocks). Each land cell
 * drains into the neighbouring cell that is closest to the sea (lowest continentality), so the cells form trees whose
 * roots are coastal cells. A cell knows the total runoff it receives from upstream and the node through which its
 * water leaves to its receiver (the lowest point of their shared divide), so the river network of every cell can be
 * built on its own and still connects with its neighbours. Everything is a pure function of position and seed.
 */
public final class BasinGraph {

    /** Continentality below which a cell is sea, and below which a land cell drains straight to the sea. */
    static final double OCEAN_L = -0.25, COAST_L = 0.06;

    /** One basin cell. Lazily completed, deterministic, safe to share. */
    public static final class Cell {

        public final int ci, cj;
        public final double fx, fy;
        /** Continentality at the feature point, and the drainage potential (continentality plus large-scale valleys). */
        public final double L, potential;
        public final boolean ocean;
        /** Drains straight to the sea (true) or has no lower neighbour (terminal = endorheic). */
        public boolean outlet, terminal;
        /** Receiving cell (when neither ocean, outlet nor terminal). */
        public int ri, rj;
        /** Global river-grid node through which the water leaves (packed gi, gj), or Long.MIN_VALUE. */
        public long exitNode = Long.MIN_VALUE;
        volatile boolean linked;
        volatile double flowOut = Double.NaN;

        Cell(int ci, int cj, double fx, double fy, double L, double potential) {
            this.ci = ci;
            this.cj = cj;
            this.fx = fx;
            this.fy = fy;
            this.L = L;
            this.potential = potential;
            this.ocean = L < OCEAN_L;
        }

        public boolean drainsToNeighbour() {
            return !ocean && !outlet && !terminal;
        }
    }

    private final WorldGen gen;
    private final GenParams P;
    private final Seeds SD;
    private final double B;
    private final int g;
    private final ConcurrentHashMap<Long, Cell> cells = new ConcurrentHashMap<>();
    private final ThreadLocal<GenPoint> pt = ThreadLocal.withInitial(GenPoint::new);
    private final ThreadLocal<GenScratch> sc = ThreadLocal.withInitial(GenScratch::new);

    public BasinGraph(WorldGen gen) {
        this.gen = gen;
        this.P = gen.params;
        this.SD = gen.seeds;
        this.B = P.basinSize;
        this.g = P.riverGrid;
    }

    public double size() {
        return B;
    }

    static long key(int a, int b) {
        return ((long) a << 32) | (b & 0xffffffffL);
    }

    public static int keyA(long k) {
        return (int) (k >> 32);
    }

    public static int keyB(long k) {
        return (int) k;
    }

    // ------------------------------------------------------------------ membership

    /** Nodes per side of a membership tile, and how many tiles are kept (cleared beyond: recomputed identically). */
    private static final int MT = 64, MT_MAX = 1024;

    /** Basin cells of a tile of river-grid nodes: a palette of packed cells and an index into it per node. */
    private static final class MemberTile {

        final long[] palette;
        final byte[] idx;

        MemberTile(long[] palette, byte[] idx) {
            this.palette = palette;
            this.idx = idx;
        }
    }

    private final ConcurrentHashMap<Long, MemberTile> members = new ConcurrentHashMap<>();

    private MemberTile memberTile(int tx, int ty) {
        long k = net.junedev.viridium.worldgen.terrain.core.GenMath.mixKey(key(tx, ty));
        MemberTile t = members.get(k);
        if (t != null) return t;
        if (members.size() >= MT_MAX) members.clear(); // deterministic: anything dropped is recomputed identically
        return members.computeIfAbsent(k, kk -> {
            long[] pal = new long[8];
            int np = 0;
            byte[] idx = new byte[MT * MT];
            for (int j = 0; j < MT; j++) for (int i = 0; i < MT; i++) {
                long c = cellAt((tx * MT + i + 0.5) * g, (ty * MT + j + 0.5) * g);
                int p = 0;
                while (p < np && pal[p] != c) p++;
                if (p == np) {
                    if (np == 256) return null; // never in practice (a tile spans ~1 basin cell): no tile, cellAt per node
                    if (np == pal.length) pal = java.util.Arrays.copyOf(pal, np * 2);
                    pal[np++] = c;
                }
                idx[j * MT + i] = (byte) p;
            }
            return new MemberTile(java.util.Arrays.copyOf(pal, np), idx);
        });
    }

    /**
     * Basin cells of the river-grid nodes gx0 .. gx0 + n - 1 of row gy (node (gx, gy) is the point ((gx + 0.5) g,
     * (gy + 0.5) g)), into out: exactly {@link #cellAt} there, from a lattice shared by all networks (each node lies in
     * the grids of ~10 overlapping networks).
     */
    public void memberRow(int gx0, int gy, int n, long[] out) {
        int ty = Math.floorDiv(gy, MT), lj = gy - ty * MT;
        for (int i = 0; i < n;) {
            int gx = gx0 + i, tx = Math.floorDiv(gx, MT), li = gx - tx * MT;
            int run = Math.min(n - i, MT - li);
            MemberTile t = memberTile(tx, ty);
            if (t == null) for (int k = 0; k < run; k++) out[i + k] = cellAt((gx + k + 0.5) * g, (gy + 0.5) * g);
            else for (int k = 0; k < run; k++) out[i + k] = t.palette[t.idx[lj * MT + li + k] & 255];
            i += run;
        }
    }

    double featX(int ci, int cj) {
        return (ci + 0.15 + 0.7 * rnd(ci, cj, SD.basin)) * B;
    }

    double featY(int ci, int cj) {
        return (cj + 0.15 + 0.7 * rnd(ci, cj, SD.basin + 1)) * B;
    }

    /**
     * Feature points of the 5x5 cells around (ci, cj), and per own cell the divides to the others: consecutive points
     * almost always fall in the same block, so they are kept per thread instead of hashed again (50 per point).
     */
    private static final class Feats {

        int ci = Integer.MIN_VALUE, cj;
        final double[] fx = new double[25], fy = new double[25];
        /** Per own index ok: for each k, midpoint (x, y), normal (x, y) and its length; valid when bisOk[ok]. */
        final double[][] bis = new double[25][25 * 5];
        final boolean[] bisOk = new boolean[25];
    }

    private final ThreadLocal<Feats> feats = ThreadLocal.withInitial(Feats::new);

    private Feats feats(int ci, int cj) {
        Feats f = feats.get();
        if (f.ci != ci || f.cj != cj) {
            for (int dj = -2, k = 0; dj <= 2; dj++) for (int di = -2; di <= 2; di++, k++) {
                f.fx[k] = featX(ci + di, cj + dj);
                f.fy[k] = featY(ci + di, cj + dj);
            }
            java.util.Arrays.fill(f.bisOk, false);
            f.ci = ci;
            f.cj = cj;
        }
        return f;
    }

    /** Basin cell containing (x, y), packed (ci, cj). Warped so divides are irregular. */
    public long cellAt(double x, double y) {
        double wa = 0.22 * B, ws = 0.6 * B;
        double wx = x + wa * fbm(x / ws, y / ws, SD.basinW, 3), wy = y + wa * fbm(x / ws + 7.3, y / ws - 2.1, SD.basinW + 5, 3);
        int ci = floor(wx / B), cj = floor(wy / B);
        Feats f = feats(ci, cj);
        double best = Double.POSITIVE_INFINITY;
        long bk = 0;
        for (int dj = -1; dj <= 1; dj++) for (int di = -1; di <= 1; di++) {
            int i = ci + di, j = cj + dj, k = (dj + 2) * 5 + di + 2;
            double dx = f.fx[k] - wx, dy = f.fy[k] - wy, d = dx * dx + dy * dy;
            if (d < best) {
                best = d;
                bk = key(i, j);
            }
        }
        return bk;
    }

    /**
     * Cells whose network can shape (x, y): its own cell plus the neighbours whose divide (bisector in warped space) is
     * closer than {@code margin}. Usually one or two cells. Deterministic order.
     */
    public long[] nearCells(double x, double y, double margin) {
        long[] out = new long[9];
        int n = nearCellsInto(x, y, margin, out);
        return n == out.length ? out : java.util.Arrays.copyOf(out, n);
    }

    /** Same as {@link #nearCells(double, double, double)} into the scratch arrays (no allocation): count in, keys in s.NCK. */
    public int nearCells(double x, double y, double margin, GenScratch s) {
        return nearCellsInto(x, y, margin, s.NCK);
    }

    private int nearCellsInto(double x, double y, double margin, long[] out) {
        double wa = 0.22 * B, ws = 0.6 * B;
        double wx = x + wa * fbm(x / ws, y / ws, SD.basinW, 3), wy = y + wa * fbm(x / ws + 7.3, y / ws - 2.1, SD.basinW + 5, 3);
        int ci = floor(wx / B), cj = floor(wy / B);
        Feats f = feats(ci, cj);
        final double[] fx = f.fx, fy = f.fy;
        int bi = 0, bj = 0;
        double best = Double.POSITIVE_INFINITY;
        for (int dj = -2, k = 0; dj <= 2; dj++) for (int di = -2; di <= 2; di++, k++) {
            double d = (fx[k] - wx) * (fx[k] - wx) + (fy[k] - wy) * (fy[k] - wy);
            if (d < best) {
                best = d;
                bi = di;
                bj = dj;
            }
        }
        int ok = (bj + 2) * 5 + bi + 2;
        double[] bs = f.bis[ok];
        if (!f.bisOk[ok]) {
            for (int k = 0; k < 25; k++) {
                if (k == ok) continue;
                double nx = fx[k] - fx[ok], ny = fy[k] - fy[ok];
                bs[k * 5] = (fx[ok] + fx[k]) * 0.5;
                bs[k * 5 + 1] = (fy[ok] + fy[k]) * 0.5;
                bs[k * 5 + 2] = nx;
                bs[k * 5 + 3] = ny;
                bs[k * 5 + 4] = Math.sqrt(nx * nx + ny * ny);
            }
            f.bisOk[ok] = true;
        }
        int n = 0;
        out[n++] = key(ci + bi, cj + bj);
        for (int k = 0; k < 25; k++) {
            if (k == ok) continue;
            int b = k * 5;
            double nx = bs[b + 2], ny = bs[b + 3], len = bs[b + 4];
            // distance from the point to the bisector between the own feature and feature k
            double d = (bs[b] - wx) * nx / len + (bs[b + 1] - wy) * ny / len;
            if (d < margin && n < out.length) out[n++] = key(ci + (k % 5) - 2, cj + (k / 5) - 2);
        }
        return n;
    }

    // ------------------------------------------------------------------ cells and links

    private Cell raw(int ci, int cj) {
        long k = key(ci, cj);
        long mk = net.junedev.viridium.worldgen.terrain.core.GenMath.mixKey(k);
        Cell c = cells.get(mk);
        if (c != null) return c;
        double fx = featX(ci, cj), fy = featY(ci, cj);
        GenPoint p = pt.get();
        GenScratch s = sc.get();
        gen.plates.apply(fx, fy, p, s);
        gen.continents.apply(fx, fy, p);
        double L = p.L + 0.02 * (rnd(ci, cj, SD.basin + 2) - 0.5);
        // low-frequency "great valleys": many cells converge into them, which makes few, very large basins
        double valleys = P.basinMerge * fbm(fx / (5 * B), fy / (5 * B), SD.basin + 3, 2);
        c = new Cell(ci, cj, fx, fy, L, L + valleys);
        if (cells.size() > 20000) cells.clear(); // deterministic: anything dropped is recomputed identically
        Cell prev = cells.putIfAbsent(mk, c);
        return prev != null ? prev : c;
    }

    /** Recently used linked cells by slot (mixed key): most lookups skip the map and its boxed key. */
    private final Cell[] front = new Cell[4096];

    /** The basin cell with its drainage link resolved. */
    public Cell cell(int ci, int cj) {
        int slot = (int) net.junedev.viridium.worldgen.terrain.core.GenMath.mixKey(key(ci, cj)) & (front.length - 1);
        Cell f = front[slot];
        if (f != null && f.ci == ci && f.cj == cj) return f;
        Cell c = raw(ci, cj);
        if (!c.linked) link(c);
        front[slot] = c;
        return c;
    }

    public Cell cell(long key) {
        return cell(keyA(key), keyB(key));
    }

    private synchronized void link(Cell c) {
        if (c.linked) return;
        if (!c.ocean) {
            // a cell with its own stretch of coast drains there: its network sends most of its water to that sea, so it
            // must not hand its whole flow on to a neighbour (which would get a huge river out of nowhere, e.g. on an islet)
            if (c.L < COAST_L || seaFraction(c) > 0.1) c.outlet = true;
            else {
                // candidates: the neighbours closer to the sea (so it always reaches the coast), the one deepest in a
                // great valley first; the first one that really shares a divide with c (an exit node) is the receiver
                List<Cell> cand = new ArrayList<>(8);
                for (int dj = -1; dj <= 1; dj++) for (int di = -1; di <= 1; di++) {
                    if (di == 0 && dj == 0) continue;
                    Cell n = raw(c.ci + di, c.cj + dj);
                    if (n.L < c.L) cand.add(n);
                }
                cand.sort((a, b) -> a.potential != b.potential ? Double.compare(a.potential, b.potential)
                    : Long.compare(key(a.ci, a.cj), key(b.ci, b.cj)));
                c.terminal = true;
                for (Cell n : cand) {
                    long exit = findExit(c, n);
                    if (exit == Long.MIN_VALUE) continue;
                    c.ri = n.ci;
                    c.rj = n.cj;
                    c.exitNode = exit;
                    c.terminal = false;
                    break;
                }
            }
        }
        c.linked = true;
    }

    /** Neighbouring cells that drain into c. */
    public List<Cell> children(Cell c) {
        List<Cell> out = new ArrayList<>(3);
        for (int dj = -1; dj <= 1; dj++) for (int di = -1; di <= 1; di++) {
            if (di == 0 && dj == 0) continue;
            Cell n = cell(c.ci + di, c.cj + dj);
            if (n.drainsToNeighbour() && n.ri == c.ci && n.rj == c.cj) out.add(n);
        }
        return out;
    }

    // ------------------------------------------------------------------ exits and flow

    /**
     * Lowest land node of c on its divide with r: a global grid node belonging to c with an 8-neighbour in r. Both the
     * upstream and the downstream networks find exactly the same node, so the river crosses the divide continuously.
     */
    private long findExit(Cell c, Cell r) {
        double mx = (c.fx + r.fx) * 0.5, my = (c.fy + r.fy) * 0.5, H = 0.9 * B;
        int gi0 = floor((mx - H) / g), gj0 = floor((my - H) / g), n = (int) Math.ceil(2 * H / g) + 1;
        long ck = key(c.ci, c.cj), rk = key(r.ci, r.cj);
        long[] member = new long[n * n];
        for (int j = 0; j < n; j++) for (int i = 0; i < n; i++)
            member[j * n + i] = cellAt((gi0 + i + 0.5) * g, (gj0 + j + 0.5) * g);
        GenPoint p = pt.get();
        GenScratch s = sc.get();
        double best = Double.POSITIVE_INFINITY;
        long bestNode = Long.MIN_VALUE;
        for (int j = 1; j < n - 1; j++) for (int i = 1; i < n - 1; i++) {
            if (member[j * n + i] != ck) continue;
            boolean border = false;
            for (int dj = -1; dj <= 1 && !border; dj++) for (int di = -1; di <= 1; di++)
                if (member[(j + dj) * n + i + di] == rk) {
                    border = true;
                    break;
                }
            if (!border) continue;
            double x = (gi0 + i + 0.5) * g, y = (gj0 + j + 0.5) * g;
            gen.plates.apply(x, y, p, s);
            gen.continents.apply(x, y, p);
            if (p.L < 0) continue;
            double h = gen.relief.baseHeight(x, y, p, false, s);
            if (h < best) {
                best = h;
                bestNode = key(gi0 + i, gj0 + j);
            }
        }
        return bestNode;
    }

    /** Share of the cell's area that is sea, from a coarse sample. */
    private double seaFraction(Cell c) {
        final int N = 6;
        double step = 2 * B / N;
        long ck = key(c.ci, c.cj);
        GenPoint p = pt.get();
        GenScratch s = sc.get();
        int own = 0, sea = 0;
        for (int j = 0; j < N; j++) for (int i = 0; i < N; i++) {
            double x = c.fx - B + (i + 0.5) * step, y = c.fy - B + (j + 0.5) * step;
            if (cellAt(x, y) != ck) continue;
            own++;
            gen.plates.apply(x, y, p, s);
            gen.continents.apply(x, y, p);
            if (p.L < 0) sea++;
        }
        return own == 0 ? 0 : (double) sea / own;
    }

    /** Runoff (river-grid units) collected by the land of c itself, from a coarse sample of its area. */
    private double localRunoff(Cell c) {
        final int N = 6;
        double step = 2 * B / N, cellsPerSample = (step / g) * (step / g), sum = 0;
        long ck = key(c.ci, c.cj);
        GenPoint p = pt.get();
        GenScratch s = sc.get();
        for (int j = 0; j < N; j++) for (int i = 0; i < N; i++) {
            double x = c.fx - B + (i + 0.5) * step, y = c.fy - B + (j + 0.5) * step;
            if (cellAt(x, y) != ck) continue;
            gen.plates.apply(x, y, p, s);
            gen.continents.apply(x, y, p);
            if (p.L < 0) continue;
            gen.climate.apply(x, y, p);
            sum += cellsPerSample * p.Pb * (0.4 + 0.6 * Math.exp(-p.L / 0.4)) / 1000;
        }
        return sum;
    }

    /** Total runoff leaving c: its own plus everything upstream. */
    public double flowOut(Cell c) {
        double f = c.flowOut;
        if (f == f) return f;
        f = localRunoff(c);
        for (Cell ch : children(c)) f += flowOut(ch);
        c.flowOut = f;
        return f;
    }
}
