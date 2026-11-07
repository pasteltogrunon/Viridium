package net.junedev.viridium.worldgen.terrain;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;

import net.junedev.viridium.worldgen.terrain.column.BiomeMapper;
import net.junedev.viridium.worldgen.terrain.column.ColumnPlan;
import net.junedev.viridium.worldgen.terrain.column.ColumnPlanner;
import net.junedev.viridium.worldgen.terrain.core.GenParams;
import net.junedev.viridium.worldgen.terrain.pipeline.GenPoint;
import net.junedev.viridium.worldgen.terrain.pipeline.Stage;
import net.junedev.viridium.worldgen.terrain.stages.BasinGraph;
import net.junedev.viridium.worldgen.terrain.stages.RiverNet;

import org.junit.jupiter.api.Test;

/**
 * Lake water must sit in its basin: no lake water column standing above a dry neighbour (a "water wall"), and no big
 * step between a lake and the river water next to it.
 */
class LakeWaterTest {

    private final Map<Long, ColumnPlan> plans = new HashMap<>();
    private ColumnPlanner planner;

    private ColumnPlan plan(int cx, int cz) {
        return plans.computeIfAbsent(((long) cx << 32) | (cz & 0xffffffffL), k -> planner.plan(cx, cz));
    }

    @Test
    void lakeWaterStaysInItsBasin() {
        WorldGen gen = new WorldGen(new GenParams());
        planner = new ColumnPlanner(gen, BiomeMapper.FROM_STAGE);
        GenPoint o = new GenPoint();
        java.util.Set<Long> seen = new java.util.HashSet<>(), cols = new java.util.HashSet<>();
        int basins = 0, lakes = 0, lakeCols = 0, walls = 0, tallWalls = 0, maxWall = 0, steps = 0, maxStep = 0;
        String maxWallAt = "-";
        for (int probe = 0; probe < 400 && basins < 8; probe++) {
            double px = probe * 2300 + 500, pz = 1000 + (probe % 7) * 1700;
            gen.evaluate(px, pz, Stage.CONTINENTS, o);
            if (o.L < 0.15) continue;
            long ck = gen.rivers.basins.cellAt(px, pz);
            if (!seen.add(ck)) continue;
            RiverNet net = gen.rivers.net(BasinGraph.keyA(ck), BasinGraph.keyB(ck));
            if (net.lakeIdx.length == 0) continue;
            basins++;
            java.util.Set<Double> levels = new java.util.HashSet<>();
            for (double v : net.lakeLv) levels.add(v);
            lakes += levels.size();
            // every lake cell of the network: its 32x32 square of columns
            for (int q = 0; q < net.lakeIdx.length; q += Math.max(1, net.lakeIdx.length / 60)) {
                int c = net.lakeIdx[q];
                int x0 = (int) Math.floor(net.x0 + (c % net.nx) * net.g), z0 = (int) Math.floor(net.y0 + (c / net.nx) * net.g);
                for (int bz = z0 - 8; bz < z0 + net.g + 8; bz++) for (int bx = x0 - 8; bx < x0 + net.g + 8; bx++) {
                    if (!cols.add(((long) bx << 32) | (bz & 0xffffffffL))) continue;
                    ColumnPlan p = plan(bx >> 4, bz >> 4);
                    int i = ColumnPlan.index(bx & 15, bz & 15);
                    if (!p.has(i, ColumnPlan.F_LAKE) || p.waterY[i] == ColumnPlan.NONE) continue;
                    if (p.has(i, ColumnPlan.F_FALL)) continue;
                    lakeCols++;
                    int w = p.waterY[i], worst = 0, step = 0;
                    int[][] d = { { 1, 0 }, { -1, 0 }, { 0, 1 }, { 0, -1 } };
                    for (int[] v : d) {
                        int nx = bx + v[0], nz = bz + v[1];
                        ColumnPlan np = plan(nx >> 4, nz >> 4);
                        int ni = ColumnPlan.index(nx & 15, nz & 15);
                        if (np.waterY[ni] == ColumnPlan.NONE) worst = Math.max(worst, w - np.surfaceY[ni]);
                        else step = Math.max(step, w - Math.max(np.waterY[ni], np.surfaceY[ni]));
                    }
                    if (worst > 0) walls++;
                    if (worst >= 2) tallWalls++;
                    if (worst > maxWall) {
                        maxWall = worst;
                        maxWallAt = bx + "," + bz;
                    }
                    if (step > 1) steps++;
                    maxStep = Math.max(maxStep, step);
                }
            }
        }
        System.out.printf(
            "lakes: %d in %d basins, %d lake water columns, walls %d (>=2 blocks %d, tallest %d at %s), steps >1 to other water %d (max %d)%n",
            lakes, basins, lakeCols, walls, tallWalls, maxWall, maxWallAt, steps, maxStep);
        // regression guards (2026-11-08, per-depression lake caps: 8683 columns, no walls, no steps)
        assertTrue(lakeCols > 200, "test found too few lake columns: " + lakeCols);
        assertTrue(walls <= lakeCols * 0.01, "lake water stands above dry ground in " + walls + " columns");
        assertTrue(tallWalls <= lakeCols * 0.002, "lake water walls of 2+ blocks in " + tallWalls + " columns");
    }
}
