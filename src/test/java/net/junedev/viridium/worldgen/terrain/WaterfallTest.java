package net.junedev.viridium.worldgen.terrain;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;

import net.junedev.viridium.worldgen.terrain.column.BiomeMapper;
import net.junedev.viridium.worldgen.terrain.column.ColumnPlan;
import net.junedev.viridium.worldgen.terrain.column.ColumnPlanner;
import net.junedev.viridium.worldgen.terrain.core.GenParams;
import net.junedev.viridium.worldgen.terrain.stages.BasinGraph;
import net.junedev.viridium.worldgen.terrain.stages.RiverNet;

import org.junit.jupiter.api.Test;

/**
 * Waterfalls form where rivers cross cliffs: the networks around a plateau, a sea cliff coast and a rift have some, and
 * every curtain stands on water (a pool at its foot) and reaches above it.
 */
class WaterfallTest {

    @Test
    void riversFallOverCliffs() {
        WorldGen gen = new WorldGen(new GenParams());
        ColumnPlanner planner = new ColumnPlanner(gen, BiomeMapper.FROM_STAGE);
        Map<Long, ColumnPlan> plans = new HashMap<>();
        double[][] areas = { { 3440, 6250 }, { -3960, -1128 }, { -18250, -2420 }, { 4300, 6300 } };
        var plansCache = new net.junedev.viridium.worldgen.terrain.column.ColumnPlanCache(planner);
        var cd = new net.junedev.viridium.worldgen.terrain.column.CliffDetail(plansCache, 7, 1.0);
        int spills = 0;
        int falls = 0, seaFalls = 0, curtains = 0, maxDrop = 0;
        java.util.Set<Long> seen = new java.util.HashSet<>();
        for (double[] a : areas) {
            for (long ck : gen.rivers.basinsAt(a[0], a[1])) {
                if (!seen.add(ck) || gen.rivers.basins.cell(ck).ocean) continue;
                RiverNet net = gen.rivers.net(BasinGraph.keyA(ck), BasinGraph.keyB(ck));
                for (int sl = 0; sl < net.count; sl++) {
                    if (net.lipOut[sl] == 2) seaFalls++;
                    if (net.fallIn[sl] <= 0 && net.lipOut[sl] != 2) continue;
                    falls++;
                    maxDrop = Math.max(maxDrop, (int) net.fallIn[sl]);
                    // the lip: start of the reach below, or end of a reach falling into the sea
                    int q = net.fallIn[sl] > 0 ? (sl * RiverNet.NPT) * 2 : (sl * RiverNet.NPT + RiverNet.NPT - 1) * 2;
                    int x0 = (int) Math.floor(net.pts[q]), z0 = (int) Math.floor(net.pts[q + 1]);
                    if (falls <= 6) System.out.printf("  fall at %d,%d drop %.0f%s%n", x0, z0, net.fallIn[sl] > 0 ? net.fallIn[sl] : net.z1[sl], net.lipOut[sl] == 2 ? " (sea)" : "");
                    for (int dz = -6; dz <= 6; dz++) for (int dx = -6; dx <= 6; dx++) {
                        int bx = x0 + dx, bz = z0 + dz;
                        ColumnPlan p = plans.computeIfAbsent(((long) (bx >> 4) << 32) | ((bz >> 4) & 0xffffffffL),
                            k -> planner.plan(bx >> 4, bz >> 4));
                        int i = ColumnPlan.index(bx & 15, bz & 15);
                        if (cd.get(bx >> 4, bz >> 4).curtainTop[i] != ColumnPlan.NONE) spills++;
                        if (p.fallTop[i] == ColumnPlan.NONE) continue;
                        curtains++;
                        assertTrue(p.waterY[i] != ColumnPlan.NONE, "curtain without a pool at " + bx + "," + bz);
                        assertTrue(p.fallTop[i] > p.waterY[i], "curtain below its pool at " + bx + "," + bz);
                    }
                }
            }
        }
        System.out.printf("waterfalls: %d (%d into the sea), tallest %d blocks, %d curtain columns, %d lip spills%n", falls, seaFalls,
            maxDrop, curtains, spills);
        assertTrue(falls > 0, "no waterfall where rivers cross cliffs");
        assertTrue(curtains > 0, "waterfalls without curtains");
        assertTrue(spills > 0, "no falling water at the lips");
    }
}
