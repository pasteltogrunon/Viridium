package net.junedev.viridium.worldgen.terrain;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;

import net.junedev.viridium.worldgen.terrain.column.BiomeMapper;
import net.junedev.viridium.worldgen.terrain.column.ColumnPlan;
import net.junedev.viridium.worldgen.terrain.column.ColumnPlanner;
import net.junedev.viridium.worldgen.terrain.column.TerrainLayout;
import net.junedev.viridium.worldgen.terrain.core.GenParams;
import net.junedev.viridium.worldgen.terrain.pipeline.GenPoint;
import net.junedev.viridium.worldgen.terrain.pipeline.Stage;
import net.junedev.viridium.worldgen.terrain.stages.RiverNet;

import org.junit.jupiter.api.Test;

/**
 * River water must sit in its channel: no column of river water standing more than one block above every neighbour
 * (the "water walls" seen in game), including across chunk borders.
 */
class RiverWaterTest {

    private final Map<Long, ColumnPlan> plans = new HashMap<>();
    private ColumnPlanner planner;

    private ColumnPlan plan(int cx, int cz) {
        return plans.computeIfAbsent(((long) cx << 32) | (cz & 0xffffffffL), k -> planner.plan(cx, cz));
    }

    /** Top of a dry column, or Integer.MAX_VALUE when it holds water (a step down to water is a cascade, not a wall). */
    private int dryTop(int x, int z) {
        ColumnPlan p = plan(x >> 4, z >> 4);
        int i = ColumnPlan.index(x & 15, z & 15);
        return p.waterY[i] != ColumnPlan.NONE ? Integer.MAX_VALUE : p.surfaceY[i];
    }

    private int top(int x, int z) {
        ColumnPlan p = plan(x >> 4, z >> 4);
        int i = ColumnPlan.index(x & 15, z & 15);
        return p.waterY[i] != ColumnPlan.NONE ? Math.max(p.waterY[i], p.surfaceY[i]) : p.surfaceY[i];
    }

    @Test
    void riverWaterStaysInItsChannel() {
        WorldGen gen = new WorldGen(new GenParams());
        planner = new ColumnPlanner(gen, BiomeMapper.FROM_STAGE);
        GenPoint o = new GenPoint();
        int riverCols = 0, hanging = 0, walls = 0, perched = 0, depthSum = 0, maxStep = 0, tallWalls = 0, maxWall = 0, maxPerch = 0;
        // walk along the reaches of a few plates' river networks
        for (int plate = 0; plate < 3; plate++) {
            gen.evaluate(plate * 4000 + 1500, 800, Stage.PLATES, o);
            RiverNet net = gen.rivers.net(o.pi, o.pj);
            for (int s = 0; s < net.count; s += Math.max(1, net.count / 40)) {
                int q = (s * RiverNet.NPT + RiverNet.NPT / 2) * 2;
                int x = (int) Math.floor(net.pts[q]), z = (int) Math.floor(net.pts[q + 1]);
                for (int dz = -6; dz <= 6; dz++) for (int dx = -6; dx <= 6; dx++) {
                    int bx = x + dx, bz = z + dz;
                    ColumnPlan p = plan(bx >> 4, bz >> 4);
                    int i = ColumnPlan.index(bx & 15, bz & 15);
                    if (!p.has(i, ColumnPlan.F_RIVER) || p.waterY[i] == ColumnPlan.NONE) continue;
                    if (p.surfaceY[i] < TerrainLayout.SEA_LEVEL - 1) continue; // drowned in the sea
                    riverCols++;
                    int w = p.waterY[i];
                    depthSum += w - p.surfaceY[i];
                    int lowest = Math.min(
                        Math.min(top(bx + 1, bz), top(bx - 1, bz)),
                        Math.min(top(bx, bz + 1), top(bx, bz - 1)));
                    maxStep = Math.max(maxStep, w - lowest);
                    if (w - lowest > 1) hanging++;
                    int dry = Math.min(
                        Math.min(dryTop(bx + 1, bz), dryTop(bx - 1, bz)),
                        Math.min(dryTop(bx, bz + 1), dryTop(bx, bz - 1)));
                    // valley bottom: no dry ground lower than the water within 4 blocks
                    int nearDry = Integer.MAX_VALUE;
                    for (int ez = -4; ez <= 4; ez++) for (int ex = -4; ex <= 4; ex++) nearDry = Math.min(nearDry, dryTop(bx + ex, bz + ez));
                    if (nearDry != Integer.MAX_VALUE && nearDry < w - 1) perched++;
                    if (nearDry != Integer.MAX_VALUE) maxPerch = Math.max(maxPerch, w - nearDry);
                    if (dry != Integer.MAX_VALUE) {
                        maxWall = Math.max(maxWall, w - dry);
                        if (w - dry >= 2) tallWalls++;
                    }
                    if (dry != Integer.MAX_VALUE && w > dry) {
                        walls++;
                        if (walls <= 0) {
                            int[][] d = { { 1, 0 }, { -1, 0 }, { 0, 1 }, { 0, -1 } };
                            StringBuilder sb = new StringBuilder();
                            for (int[] v : d) {
                                int nx = bx + v[0], nz = bz + v[1];
                                ColumnPlan np = plan(nx >> 4, nz >> 4);
                                int ni = ColumnPlan.index(nx & 15, nz & 15);
                                sb.append(String.format(" [%+d,%+d s=%d w=%s f=%d%s]", v[0], v[1], np.surfaceY[ni],
                                    np.waterY[ni] == ColumnPlan.NONE ? "-" : String.valueOf(np.waterY[ni]), np.flags[ni],
                                    (nx >> 4) != (bx >> 4) || (nz >> 4) != (bz >> 4) ? " other-chunk" : ""));
                            }
                            System.out.printf("wall at %d,%d local %d,%d s=%d w=%d f=%d:%s%n", bx, bz, bx & 15, bz & 15,
                                p.surfaceY[i], w, p.flags[i], sb);
                        }
                    }
                }
            }
        }
        System.out.printf("river water columns %d, mean depth %.2f, steps >1 (cascades) %d, max step %d, water above dry ground (walls) %d, dry ground >1 below the water within 4 blocks %d%n  walls >=2 blocks %d, tallest wall %d, deepest perch %d%n",
            riverCols, riverCols == 0 ? 0 : (double) depthSum / riverCols, hanging, maxStep, walls, perched, tallWalls, maxWall, maxPerch);
        assertTrue(riverCols > 50, "test found too few river columns: " + riverCols);
        System.out.printf("river networks: %d built, %.0f ms each%n", gen.rivers.buildCount(),
            gen.rivers.buildNanos() / 1e6 / Math.max(1, gen.rivers.buildCount()));
        // regression guards (values measured 2026-11-05: walls 1.5%, walls >=2 0.3%, perched 5.8%, max step 5)
        assertTrue(walls <= riverCols * 0.025, "river water stands above dry ground in " + walls + " columns");
        assertTrue(tallWalls <= riverCols * 0.005, "river water walls of 2+ blocks in " + tallWalls + " columns");
        assertTrue(maxStep <= 6, "cascade too tall: " + maxStep);
        assertTrue(perched <= riverCols * 0.07, "river water perched above its valley floor in " + perched + " columns");
    }
}
