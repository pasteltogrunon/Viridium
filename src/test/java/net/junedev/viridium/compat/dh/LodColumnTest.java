package net.junedev.viridium.compat.dh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.junedev.viridium.worldgen.terrain.WorldGen;
import net.junedev.viridium.worldgen.terrain.column.BiomeMapper;
import net.junedev.viridium.worldgen.terrain.column.ColumnPlan;
import net.junedev.viridium.worldgen.terrain.column.ColumnPlanner;
import net.junedev.viridium.worldgen.terrain.core.GenParams;

import org.junit.jupiter.api.Test;

/**
 * LOD columns handed to Distant Horizons must satisfy its API contract (contiguous, non-empty runs covering
 * [minY, maxY)) and agree with the column plan (ground, water and ice heights), at every LOD resolution.
 */
class LodColumnTest {

    private static final int MIN_Y = 0, MAX_Y = 1024;

    @Test
    void columnsAreContiguousAndMatchThePlan() {
        GenParams params = new GenParams();
        params.seed = 12345;
        WorldGen gen = new WorldGen(params);
        ColumnPlanner planner = new ColumnPlanner(gen, BiomeMapper.FROM_STAGE);
        ColumnPlan plan = planner.newScratchPlan();
        LodColumn c = new LodColumn();

        int columns = 0, wet = 0, belowRange = 0, iced = 0, snowy = 0, maxRuns = 0;
        long t0 = System.nanoTime();
        for (int step : new int[] { 1, 16, 256, 4096 }) {
            for (int gz = 0; gz < 24; gz++) {
                for (int gx = 0; gx < 24; gx++) {
                    // DH-like: neighbouring columns of one LOD section, so the pipeline's region caches are reused
                    int x = (gx - 12) * step + (step >> 1), z = (gz - 12) * step + (step >> 1);
                    planner.planPoint(plan, 0, x, z, step);
                    boolean snow = plan.temperature[0] < -0.5;
                    int depth = (gx + gz) % 4;
                    c.build(plan, 0, MIN_Y, MAX_Y, depth, gx % 5 == 0, snow);
                    String at = " at " + x + "," + z + " step " + step;

                    assertTrue(c.size >= 2, "too few runs" + at);
                    assertEquals(MIN_Y, c.bottom[0], "column must start at minY" + at);
                    assertEquals(MAX_Y, c.top[c.size - 1], "column must reach maxY" + at);
                    for (int k = 0; k < c.size; k++) {
                        assertTrue(c.top[k] > c.bottom[k], "empty run" + at);
                        if (k > 0) assertEquals(c.top[k - 1], c.bottom[k], "gap or overlap" + at);
                    }
                    assertEquals(LodColumn.AIR, c.kind[c.size - 1], "column must end in air" + at);

                    int sy = plan.surfaceY[0];
                    int solidTop = Math.min(Math.max(sy + 1, MIN_Y + 1), MAX_Y);
                    assertEquals(LodColumn.TOP, kindAt(c, solidTop - 1), "top block" + at);
                    if (solidTop - 1 > MIN_Y) {
                        int below = kindAt(c, solidTop - 2);
                        assertTrue(below == LodColumn.UNDER || below == LodColumn.ROCK, "ground under the top" + at);
                    }
                    if (plan.waterY[0] != ColumnPlan.NONE && plan.iceTop[0] == ColumnPlan.NONE) {
                        int w = Math.min(plan.waterY[0], MAX_Y - 1);
                        assertEquals(LodColumn.WATER, kindAt(c, w), "water surface" + at);
                        if (w + 1 < MAX_Y) assertEquals(LodColumn.AIR, kindAt(c, w + 1), "air above water" + at);
                        wet++;
                    }
                    if (plan.iceTop[0] != ColumnPlan.NONE) {
                        int ice = kindAt(c, Math.min(plan.iceTop[0], MAX_Y - 1));
                        assertTrue(ice == LodColumn.ICE || ice == LodColumn.PACKED_ICE, "ice top" + at);
                        iced++;
                    }
                    for (int k = 0; k < c.size; k++) if (c.kind[k] == LodColumn.SNOW) snowy++;
                    if (sy < MIN_Y) belowRange++;
                    maxRuns = Math.max(maxRuns, c.size);
                    columns++;

                    // the same column under a tree (a crown may also overhang water from a neighbouring column)
                    int base = sy + 1;
                    c.tree((gx & 1) == 0 ? base + 6 : ColumnPlan.NONE, base + 3, base + 9);
                    c.build(plan, 0, MIN_Y, MAX_Y, depth, false, snow);
                    assertEquals(MIN_Y, c.bottom[0], "tree column must start at minY" + at);
                    assertEquals(MAX_Y, c.top[c.size - 1], "tree column must reach maxY" + at);
                    for (int k = 1; k < c.size; k++) assertEquals(c.top[k - 1], c.bottom[k], "tree gap" + at);
                    int liquidTop = Math.max(solidTop, Math.max(
                        plan.waterY[0] == ColumnPlan.NONE ? 0 : plan.waterY[0] + 1,
                        plan.iceTop[0] == ColumnPlan.NONE ? 0 : plan.iceTop[0] + 1));
                    if (base + 9 > liquidTop && base + 8 < MAX_Y)
                        assertEquals(LodColumn.LEAVES, kindAt(c, base + 8), "crown top" + at);
                    if (base + 9 >= liquidTop && base + 9 < MAX_Y)
                        assertEquals(LodColumn.AIR, kindAt(c, base + 9), "air above the crown" + at);
                    // the next column is plain again: the tree must not leak
                    c.build(plan, 0, MIN_Y, MAX_Y, depth, false, snow);
                    for (int k = 0; k < c.size; k++)
                        assertTrue(c.kind[k] != LodColumn.LEAVES && c.kind[k] != LodColumn.LOG, "leaked tree" + at);
                }
            }
        }
        System.out.printf(
            "LOD columns: %d (%.1f us each), water %d, ice %d, snow %d, ground below Y %d: %d, max runs %d%n",
            columns,
            (System.nanoTime() - t0) / 1e3 / columns,
            wet,
            iced,
            snowy,
            MIN_Y,
            belowRange,
            maxRuns);
        assertTrue(wet > 0, "the sample should include some sea or rivers");
    }

    private static int kindAt(LodColumn c, int y) {
        for (int k = 0; k < c.size; k++) if (y >= c.bottom[k] && y < c.top[k]) return c.kind[k];
        return -1;
    }
}
