package net.junedev.viridium.worldgen.terrain;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import net.junedev.viridium.worldgen.terrain.caves.CaveSystems;
import net.junedev.viridium.worldgen.terrain.caves.Fissures;
import net.junedev.viridium.worldgen.terrain.core.GenParams;
import net.junedev.viridium.worldgen.terrain.pipeline.GenPoint;
import net.junedev.viridium.worldgen.terrain.pipeline.Stage;

import org.junit.jupiter.api.Test;

/**
 * Shapes of the collapse pits (irregular outlines, ledges, cenotes without walls of water) and the ground fissures
 * (how many, how long, cut into the terrain, closed near water), with their cost per point.
 */
class CaveShapesTest {

    @Test
    void collapsePits() {
        WorldGen gen = new WorldGen(new GenParams());
        GenPoint o = new GenPoint();
        int pits = 0, cols = 0, lakeCols = 0, waterWalls = 0;
        double minAspect = 9, maxAspect = 0;
        StringBuilder sb = new StringBuilder();
        for (int rj = -5; rj <= 5; rj++) for (int ri = -5; ri <= 5; ri++) {
            for (CaveSystems.Chamber c : gen.caves.systems.region(ri, rj).chambers) {
                if (c.pitR <= 0) continue;
                pits++;
                int r = (int) Math.ceil(Math.max(c.rx, c.rz) * 1.1), n = 2 * r + 1;
                double[] h = new double[n * n], lz = new double[n * n];
                boolean[] lake = new boolean[n * n], pit = new boolean[n * n];
                double sxx = 0, szz = 0, sxz = 0;
                int k = 0;
                for (int dz = -r; dz <= r; dz++) for (int dx = -r; dx <= r; dx++, k++) {
                    gen.evaluate(Math.floor(c.x) + dx + 0.5, Math.floor(c.z) + dz + 0.5, Stage.RIVERS, o);
                    h[k] = o.h;
                    // the cenote only (a river lake nearby keeps its own shores)
                    lake[k] = o.lake == 1 && c.lake == c.lake && Math.abs(o.lakeZ - (c.lake - net.junedev.viridium.worldgen.terrain.column.TerrainLayout.SEA_LEVEL)) < 1e-6;
                    lz[k] = o.lakeZ;
                    pit[k] = o.doline > 3;
                    if (pit[k]) {
                        cols++;
                        sxx += dx * dx;
                        szz += dz * dz;
                        sxz += dx * dz;
                    }
                    if (lake[k]) lakeCols++;
                }
                // aspect ratio of the pit from its second moments
                double tr = sxx + szz, det = sxx * szz - sxz * sxz, disc = Math.sqrt(Math.max(0, tr * tr / 4 - det));
                double aspect = Math.sqrt((tr / 2 + disc) / Math.max(1e-9, tr / 2 - disc));
                minAspect = Math.min(minAspect, aspect);
                maxAspect = Math.max(maxAspect, aspect);
                // a lake column next to dry ground lower than its water: a wall of water
                for (int z = 1; z < n - 1; z++) for (int x = 1; x < n - 1; x++) {
                    int q = z * n + x;
                    if (!lake[q]) continue;
                    for (int nb : new int[] { q - 1, q + 1, q - n, q + n })
                        if (!lake[nb] && h[nb] < lz[q] - 0.5) { if (waterWalls++ < 12) System.out.printf("  wall at %d,%d: lake %.1f ground %.2f here %.2f nbPit %b chamberLake %.1f%n", (int) Math.floor(c.x) + (nb % n) - r, (int) Math.floor(c.z) + (nb / n) - r, lz[q], h[nb], h[q], pit[nb], c.lake - 63); }
                }
                sb.append(String.format(" %.0f,%.0f(%.1f%s)", c.x, c.z, aspect, c.lake == c.lake ? " lake" : ""));
            }
        }
        System.out.printf("collapse pits: %d, %d pit columns, %d cenote columns, aspect %.2f-%.2f, water walls %d;%s%n", pits,
            cols, lakeCols, minAspect, maxAspect, waterWalls, sb);
        assertTrue(pits > 0, "no pits");
        assertTrue(waterWalls == 0, "cenote water next to lower dry ground");
    }

    @Test
    void fissures() {
        WorldGen gen = new WorldGen(new GenParams());
        Fissures fs = gen.caves.fissures;
        int n = 0, slots = 0, landCells = 0, cellsAll = 0;
        double len = 0, minLen = 1e9, maxLen = 0;
        StringBuilder sb = new StringBuilder();
        GenPoint o = new GenPoint();
        int span = 15; // cells each way: (31 x 768)^2 ≈ 567 km²
        for (int cj = -span; cj <= span; cj++) for (int ci = -span; ci <= span; ci++) {
            cellsAll++;
            gen.evaluate((ci + 0.5) * 768, (cj + 0.5) * 768, Stage.CONTINENTS, o);
            if (o.L > 0.05) landCells++;
        }
        // each fissure once: the cell it starts in
        java.util.Set<Fissures.Fissure> seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        List<Fissures.Fissure> all = new java.util.ArrayList<>();
        for (int cj = -span; cj <= span; cj++) for (int ci = -span; ci <= span; ci++)
            for (Fissures.Fissure f : fs.around(ci, cj)) if (seen.add(f)) all.add(f);
        for (Fissures.Fissure f : all) {
            n++;
            len += f.length;
            minLen = Math.min(minLen, f.length);
            maxLen = Math.max(maxLen, f.length);
            if (f.slot > 0) slots++;
            if (sb.length() < 300) sb.append(String.format(" %d,%d", (f.minX() + f.maxX()) / 2, (f.minZ() + f.maxZ()) / 2));
        }
        double landKm2 = landCells * 0.768 * 0.768;
        System.out.printf("fissures: %d on ~%.0f km² of land (1 per %.1f km²), length %.0f-%.0f (mean %.0f), %d with a slot;"
            + " at%s%n", n, landKm2, landKm2 / Math.max(1, n), minLen, maxLen, len / Math.max(1, n), slots, sb);
        // cut into the terrain: the 2D stage lowers columns along the first few
        int cut = 0, wet = 0, checked = 0;
        double deepest = 0;
        for (Fissures.Fissure f : all) {
            if (checked++ >= 8) break;
            for (int z = f.minZ(); z <= f.maxZ(); z++) for (int x = f.minX(); x <= f.maxX(); x++) {
                gen.evaluate(x + 0.5, z + 0.5, Stage.RIVERS, o);
                if (o.doline > 1.5) {
                    cut++;
                    deepest = Math.max(deepest, o.doline);
                    if (o.river != 0 || o.lake != 0) wet++;
                }
            }
        }
        System.out.printf("fissure columns (first 8): %d cut, deepest %.1f, %d wet%n", cut, deepest, wet);
        assertTrue(n > 0 && cut > 0, "no fissures");
        assertTrue(wet == 0, "a fissure under water");
        // cost of the 2D stage per point (contiguous points across a fissure's cell, warm)
        Fissures.Fissure f0 = all.get(0);
        int cx = (f0.minX() + f0.maxX()) / 2, cz = (f0.minZ() + f0.maxZ()) / 2;
        GenPoint p = new GenPoint();
        for (int rep = 0; rep < 3; rep++) {
            long t0 = System.nanoTime();
            int k = 0;
            for (int z = cz - 512; z < cz + 512; z += 2) for (int x = cx - 512; x < cx + 512; x++, k++) {
                p.L = 1;
                p.h = 60;
                p.river = 0;
                p.lake = 0;
                p.riverD = 1e9;
                p.riverHW = 0;
                fs.surface(x, z, p);
            }
            long t1 = System.nanoTime();
            if (rep == 2) System.out.printf("fissures.surface %.4f us/point%n", (t1 - t0) / 1e3 / k);
        }
    }
}
