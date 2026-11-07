package net.junedev.viridium.worldgen.terrain.caves;

import java.util.ArrayList;
import java.util.List;

import net.junedev.viridium.worldgen.terrain.WorldGen;
import net.junedev.viridium.worldgen.terrain.column.TerrainLayout;
import net.junedev.viridium.worldgen.terrain.core.GenMath;
import net.junedev.viridium.worldgen.terrain.core.GenParams;
import net.junedev.viridium.worldgen.terrain.core.Noise;
import net.junedev.viridium.worldgen.terrain.pipeline.GenPoint;
import net.junedev.viridium.worldgen.terrain.pipeline.GenScratch;
import net.junedev.viridium.worldgen.terrain.pipeline.Stage;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;

/**
 * Cave mouths on hillsides: about 60 % of the 256-block cells of land hold one, a wide ramp (mouth radius 5-8)
 * that enters the slope uphill at 15-30° and runs 60-150 blocks down into the rock, where it usually meets the noise
 * caves. Entering uphill makes the opening an arch in the hillside rather than a hole in flat ground. Not in rivers,
 * lakes, the sea or right next to a river.
 */
public final class CaveMouths implements CaveFeatures {

    static final int G = 256;

    private final WorldGen gen;
    private final GenParams P;
    private final int seed;
    private final Cache<Long, List<CaveSystems.Segment>> cells = CacheBuilder.newBuilder()
        .maximumSize(4096)
        .build();
    private final ThreadLocal<GenPoint> point = ThreadLocal.withInitial(GenPoint::new);
    private final ThreadLocal<GenScratch> scratch = ThreadLocal.withInitial(GenScratch::new);

    public CaveMouths(WorldGen gen) {
        this.gen = gen;
        this.P = gen.params;
        this.seed = gen.seeds.cave + 7777;
    }

    /** The mouth of cell (ci, cj) as ramp segments (empty when there is none). */
    public List<CaveSystems.Segment> cell(int ci, int cj) {
        long key = GenMath.mixKey(((long) ci << 32) | (cj & 0xffffffffL));
        List<CaveSystems.Segment> l = cells.getIfPresent(key);
        if (l != null) return l;
        l = new ArrayList<>();
        if (Noise.rnd(ci, cj, seed) < 0.6 * P.caveEntrances) {
            double x = (ci + 0.15 + 0.7 * Noise.rnd(ci, cj, seed + 1)) * G,
                z = (cj + 0.15 + 0.7 * Noise.rnd(ci, cj, seed + 2)) * G;
            GenPoint o = gen.evaluate(x, z, Stage.EROSION, 1, point.get(), scratch.get());
            if (o.L > 0.05 && o.h > 3) {
                gen.rivers.apply(x, z, o, scratch.get());
                if (o.river == 0 && o.lake == 0 && o.riverD > 24) {
                    // uphill: into the slope
                    double gx = gen.heightAt(x + 24, z, false) - gen.heightAt(x - 24, z, false),
                        gz = gen.heightAt(x, z + 24, false) - gen.heightAt(x, z - 24, false);
                    double ang = Math.hypot(gx, gz) > 2 ? Math.atan2(gz, gx) + (Noise.rnd(ci, cj, seed + 3) - 0.5) * 0.8
                        : Noise.rnd(ci, cj, seed + 3) * Math.PI * 2;
                    double len = 60 + 90 * Noise.rnd(ci, cj, seed + 4);
                    double slope = Math.tan(Math.toRadians(15 + 15 * Noise.rnd(ci, cj, seed + 5)));
                    double r0 = 5 + 3 * Noise.rnd(ci, cj, seed + 6);
                    double sy = TerrainLayout.SEA_LEVEL + o.h + 0.3 * r0;
                    CaveSystems.ramp(x, sy, z, x + Math.cos(ang) * len, sy - len * slope - r0, z + Math.sin(ang) * len, r0,
                        0.65 * r0, l, seed + ci * 31 + cj * 37);
                }
            }
        }
        cells.put(key, l);
        return l;
    }

    /** True near a mouth (its first 3 segments; no trees there). */
    public boolean nearMouth(double x, double z) {
        if (P.caveEntrances <= 0) return false;
        int ci = Math.floorDiv((int) Math.floor(x), G), cj = Math.floorDiv((int) Math.floor(z), G);
        for (int dj = -1; dj <= 1; dj++) for (int di = -1; di <= 1; di++) {
            List<CaveSystems.Segment> l = cell(ci + di, cj + dj);
            for (int q = 0; q < Math.min(3, l.size()); q++) if (l.get(q).near2d(x, z, 3)) return true;
        }
        return false;
    }

    @Override
    public void collect(int bx, int by, int bz, List<CaveCarver.Shape> out) {
        if (P.caveEntrances <= 0) return;
        int ci = Math.floorDiv(bx, G), cj = Math.floorDiv(bz, G);
        for (int dj = -1; dj <= 1; dj++) for (int di = -1; di <= 1; di++)
            for (CaveSystems.Segment s : cell(ci + di, cj + dj))
                if (s.maxX() >= bx && s.minX() <= bx + 15 && s.maxY() >= by && s.minY() <= by + 15 && s.maxZ() >= bz
                    && s.minZ() <= bz + 15) out.add(s);
    }
}
