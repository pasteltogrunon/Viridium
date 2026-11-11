package net.junedev.viridium.worldgen.terrain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.junedev.viridium.worldgen.terrain.column.BiomeMapper;
import net.junedev.viridium.worldgen.terrain.column.CliffDetail;
import net.junedev.viridium.worldgen.terrain.column.ColumnPlan;
import net.junedev.viridium.worldgen.terrain.column.ColumnPlanCache;
import net.junedev.viridium.worldgen.terrain.column.ColumnPlanner;
import net.junedev.viridium.worldgen.terrain.core.GenParams;

import org.junit.jupiter.api.Test;

/**
 * Cliff detail on a sea cliff and a plateau escarpment: notches and talus exist, every notch keeps a roof of rock and
 * talus never covers water or a river.
 */
class CliffDetailTest {

    @Test
    void notchesAndTalusAreSane() {
        WorldGen gen = new WorldGen(new GenParams());
        ColumnPlanCache plans = new ColumnPlanCache(new ColumnPlanner(gen, BiomeMapper.FROM_STAGE));
        CliffDetail cd = new CliffDetail(plans, 7, 1.0);
        int[][] areas = { { -3960, -1128 }, { 3440, 6250 } };
        int notches = 0, wet = 0, talus = 0, boulderSites = 0;
        for (int[] a : areas) {
            int cx0 = (a[0] >> 4) - 3, cz0 = (a[1] >> 4) - 3;
            for (int cz = cz0; cz < cz0 + 6; cz++) for (int cx = cx0; cx < cx0 + 6; cx++) {
                ColumnPlan p = plans.get(cx, cz);
                CliffDetail.Cols c = cd.get(cx, cz);
                for (int i = 0; i < 256; i++) {
                    if (c.notchLo[i] != ColumnPlan.NONE) {
                        notches++;
                        assertTrue(c.notchHi[i] >= c.notchLo[i], "empty notch band");
                        assertTrue(c.notchHi[i] <= p.surfaceY[i], "notch above the surface");
                        if (c.notchWater[i] != ColumnPlan.NONE) wet++;
                        assertTrue(c.minY <= c.notchLo[i] && c.maxY >= c.notchHi[i], "range misses a notch");
                    }
                    if (c.talusTop[i] != ColumnPlan.NONE) {
                        talus++;
                        assertTrue(c.talusTop[i] > p.surfaceY[i], "talus below the ground");
                        assertEquals(ColumnPlan.NONE, p.waterY[i], "talus in water");
                        assertTrue(!p.has(i, ColumnPlan.F_RIVER), "talus on a river");
                    }
                    if (c.wallDist[i] > 0) boulderSites++;
                }
            }
        }
        System.out.printf(
            "cliff detail: %d notch columns (%d flooded), %d talus columns, %d boulder sites%n",
            notches,
            wet,
            talus,
            boulderSites);
        assertTrue(wet > 0, "no sea notch at the sea cliff");
        assertTrue(talus > 0, "no talus at the escarpment");
        assertTrue(boulderSites > 0, "no boulder sites");
    }
}
