package net.junedev.viridium.worldgen.terrain.fields;

import static net.junedev.viridium.worldgen.terrain.core.GenMath.floor;

import java.util.concurrent.ConcurrentHashMap;

import net.junedev.viridium.worldgen.terrain.WorldGen;
import net.junedev.viridium.worldgen.terrain.pipeline.GenPoint;
import net.junedev.viridium.worldgen.terrain.pipeline.GenScratch;

/**
 * Cached lattice of the coarse base height ({@code heightAt(x, z, false)}) and continentality, read with bilinear
 * interpolation. Nodes depend only on their position, so the cache is deterministic (eviction never changes the
 * world) and random access is preserved. Replaces the many neighbourhood height samples of the climate and erosion
 * stages, which dominated the cost of the prototype.
 */
public final class HeightGrid {

    /** Nodes per tile side. */
    private final int N;

    private final WorldGen gen;
    private final int spacing;
    private final int maxTiles;
    private final ConcurrentHashMap<Long, float[]> tiles = new ConcurrentHashMap<>();
    /** Tiles being computed: other threads wait for them instead of computing the same tile again. */
    private final ConcurrentHashMap<Long, java.util.concurrent.CompletableFuture<float[]>> building = new ConcurrentHashMap<>();

    /** @param spacing node spacing in blocks; @param maxTiles cache bound (64x64 nodes per tile) */
    public HeightGrid(WorldGen gen, int spacing, int nodesPerTile, int maxTiles) {
        this.gen = gen;
        this.N = nodesPerTile;
        this.spacing = spacing;
        this.maxTiles = maxTiles;
    }

    public int spacing() {
        return spacing;
    }

    private float[] tile(int tx, int tz) {
        long key = ((long) tx << 32) | (tz & 0xffffffffL);
        float[] t = tiles.get(key);
        if (t != null) return t;
        java.util.concurrent.CompletableFuture<float[]> mine = new java.util.concurrent.CompletableFuture<>(),
            other = building.putIfAbsent(key, mine);
        if (other != null) return other.join();
        try {
            t = compute(tx, tz);
            if (tiles.size() >= maxTiles) evictSome();
            tiles.put(key, t);
            mine.complete(t);
            return t;
        } catch (RuntimeException | Error e) {
            mine.completeExceptionally(e);
            throw e;
        } finally {
            building.remove(key, mine);
        }
    }

    /** One tile; large ones by rows in parallel (a 64x64 climate tile is 4096 relief evaluations, ~12 ms). */
    private float[] compute(int tx, int tz) {
        float[] t = new float[N * N * 2];
        double x0 = (double) tx * N * spacing, z0 = (double) tz * N * spacing;
        java.util.stream.IntStream rows = java.util.stream.IntStream.range(0, N);
        if (N >= 32 && !net.junedev.viridium.worldgen.terrain.stages.RiverStage.serialBuilds()) rows = rows.parallel();
        rows.forEach(j -> {
            GenScratch s = new GenScratch();
            GenPoint p = new GenPoint();
            for (int i = 0; i < N; i++) {
                double x = x0 + i * spacing, z = z0 + j * spacing;
                gen.plates.apply(x, z, p, s);
                gen.continents.apply(x, z, p);
                t[(j * N + i) * 2] = (float) gen.relief.baseHeight(x, z, p, false, s);
                t[(j * N + i) * 2 + 1] = (float) p.L;
            }
        });
        return t;
    }

    private float node(int i, int j, int c) {
        int tx = Math.floorDiv(i, N), tz = Math.floorDiv(j, N);
        float[] t = tile(tx, tz);
        return t[((j - tz * N) * N + (i - tx * N)) * 2 + c];
    }

    /**
     * Bilinear sample of the coarse height at (x, z). The continentality at the same point goes to {@code out[0]}
     * when out is not null.
     */
    public double sample(double x, double z, double[] out) {
        double fx = x / spacing, fz = z / spacing;
        int i = floor(fx), j = floor(fz);
        double u = fx - i, v = fz - j;
        int ti = Math.floorDiv(i, N), tj = Math.floorDiv(j, N);
        int li = i - ti * N, lj = j - tj * N;
        double h00, h10, h01, h11, l00, l10, l01, l11;
        if (li < N - 1 && lj < N - 1) { // fast path: all four nodes in one tile
            float[] t = tile(ti, tj);
            int k = (lj * N + li) * 2;
            h00 = t[k];
            l00 = t[k + 1];
            h10 = t[k + 2];
            l10 = t[k + 3];
            h01 = t[k + 2 * N];
            l01 = t[k + 2 * N + 1];
            h11 = t[k + 2 * N + 2];
            l11 = t[k + 2 * N + 3];
        } else {
            h00 = node(i, j, 0);
            h10 = node(i + 1, j, 0);
            h01 = node(i, j + 1, 0);
            h11 = node(i + 1, j + 1, 0);
            l00 = node(i, j, 1);
            l10 = node(i + 1, j, 1);
            l01 = node(i, j + 1, 1);
            l11 = node(i + 1, j + 1, 1);
        }
        if (out != null) {
            double la = l00 + (l10 - l00) * u, lb = l01 + (l11 - l01) * u;
            out[0] = la + (lb - la) * v;
        }
        double a = h00 + (h10 - h00) * u, b = h01 + (h11 - h01) * u;
        return a + (b - a) * v;
    }

    /** Drops about a quarter of the tiles (arbitrary ones: output never depends on what is cached). */
    private void evictSome() {
        int drop = Math.max(1, maxTiles / 4);
        java.util.Iterator<Long> it = tiles.keySet()
            .iterator();
        while (drop-- > 0 && it.hasNext()) {
            it.next();
            it.remove();
        }
    }

    public int cachedTiles() {
        return tiles.size();
    }

    public void clear() {
        tiles.clear();
    }
}
