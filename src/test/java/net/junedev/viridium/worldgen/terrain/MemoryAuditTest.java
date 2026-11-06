package net.junedev.viridium.worldgen.terrain;

import java.lang.management.ManagementFactory;

import net.junedev.viridium.worldgen.terrain.column.BiomeMapper;
import net.junedev.viridium.worldgen.terrain.column.ColumnPlanCache;
import net.junedev.viridium.worldgen.terrain.column.ColumnPlanner;
import net.junedev.viridium.worldgen.terrain.core.GenParams;
import net.junedev.viridium.worldgen.terrain.fields.FastClimateStage;
import net.junedev.viridium.worldgen.terrain.fields.FastErosionStage;
import net.junedev.viridium.worldgen.terrain.map.MapLayer;
import net.junedev.viridium.worldgen.terrain.map.MapTileRenderer;
import net.junedev.viridium.worldgen.terrain.stages.RiverStage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Memory audit of the generator caches: fills each one as a long play session would, then measures the heap it
 * really retains (used heap after full GCs, before and after clearing it) and what a single unit of work allocates.
 * Run with {@code VIRIDIUM_MEM=1 gradlew cleanTest test --tests '*MemoryAuditTest'}.
 */
class MemoryAuditTest {

    private static long used() {
        Runtime rt = Runtime.getRuntime();
        long best = Long.MAX_VALUE;
        for (int i = 0; i < 4; i++) {
            System.gc();
            try {
                Thread.sleep(60);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            best = Math.min(best, rt.totalMemory() - rt.freeMemory());
        }
        return best;
    }

    private static long allocated() {
        return ((com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean()).getCurrentThreadAllocatedBytes();
    }

    private static String mb(long b) {
        return String.format("%8.1f MiB", b / 1048576.0);
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "VIRIDIUM_MEM", matches = "1")
    void audit() {
        RiverStage.buildSeriallyOnThisThread(); // allocations are counted on this thread only
        StringBuilder out = new StringBuilder("Memory audit (retained after full GC)\n");
        long empty = used();
        WorldGen g = new WorldGen(new GenParams());
        ColumnPlanner planner = new ColumnPlanner(g, BiomeMapper.FROM_STAGE);
        ColumnPlanCache plans = new ColumnPlanCache(planner);

        // allocation of single units of work (warm JIT first)
        planner.plan(37, -12);
        long a0 = allocated();
        for (int i = 0; i < 32; i++) planner.plan(38 + i, -12);
        long perPlan = (allocated() - a0) / 32;
        long a1 = allocated();
        g.rivers.net(net.junedev.viridium.worldgen.terrain.stages.BasinGraph.keyA(g.rivers.basins.cellAt(20000, 9000)),
            net.junedev.viridium.worldgen.terrain.stages.BasinGraph.keyB(g.rivers.basins.cellAt(20000, 9000)));
        long perNet = allocated() - a1; // includes the networks it depends on
        long nets1 = g.rivers.buildCount();
        MapTileRenderer r = new MapTileRenderer(g);
        r.render(MapLayer.COMPOSITE, 4, 0, 0, 128, false);
        long a2 = allocated();
        r.render(MapLayer.COMPOSITE, 4, 1, 0, 128, false);
        long perTile = allocated() - a2;

        // fill the caches: walk a long line of columns (an exploration of ~40 km), 9 columns wide
        int cols = 0;
        for (int x = 0; x < 2600; x += 1) for (int dz = -4; dz <= 4; dz++) {
            plans.get(x, dz * 1 + (x / 7));
            cols++;
        }
        long full = used();
        out.append(String.format("  after planning %d columns along ~%d km: %s total (pipeline caches + plan cache)%n",
            cols, 2600 * 16 / 1000, mb(full - empty)));

        plans = null;
        long noPlans = used();
        out.append(String.format("  column plan cache (%d max)          %s%n", ColumnPlanCache.DEFAULT_MAXIMUM_SIZE,
            mb(full - noPlans)));
        long nNets = g.rivers.cachedCount();
        g.rivers.clearNets();
        long noNets = used();
        out.append(String.format("  river networks (%d cached)           %s  (%s each)%n", nNets, mb(noPlans - noNets),
            mb(nNets == 0 ? 0 : (noPlans - noNets) / nNets)));
        long nExits = g.rivers.exitCount();
        g.rivers.clearCache();
        long noExits = used();
        out.append(String.format("  river exits (%d)                     %s%n", nExits, mb(noNets - noExits)));
        int ct = ((FastClimateStage) g.climateFinal).grid().cachedTiles();
        ((FastClimateStage) g.climateFinal).grid().clear();
        long noClim = used();
        out.append(String.format("  climate lattice (%d tiles)            %s%n", ct, mb(noExits - noClim)));
        int et = ((FastErosionStage) g.erosion).grid().cachedTiles();
        ((FastErosionStage) g.erosion).grid().clear();
        long noEro = used();
        out.append(String.format("  erosion lattice (%d tiles)           %s%n", et, mb(noClim - noEro)));
        g.volcanism.clearCache();
        long noVol = used();
        out.append(String.format("  volcanism hotspots                   %s%n", mb(noEro - noVol)));
        out.append(String.format("  rest (basin cells, thread locals, …)   %s%n", mb(noVol - empty)));
        out.append("Allocated per unit of work (garbage the GC has to collect):\n");
        out.append(String.format("  column plan                          %s%n", mb(perPlan)));
        out.append(String.format("  river network build (%d nets)         %s%n", nets1, mb(perNet)));
        out.append(String.format("  map tile 128x128 composite level 4   %s%n", mb(perTile)));
        out.append(String.format("JVM max heap %s%n", mb(Runtime.getRuntime().maxMemory())));
        System.out.print(out);
    }
}
