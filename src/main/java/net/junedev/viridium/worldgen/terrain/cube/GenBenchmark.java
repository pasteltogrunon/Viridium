package net.junedev.viridium.worldgen.terrain.cube;

import java.io.File;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.ArrayList;
import java.util.List;

import net.junedev.viridium.Viridium;
import net.junedev.viridium.worldgen.terrain.GenStats;
import net.junedev.viridium.worldgen.terrain.WorldGen;
import net.junedev.viridium.worldgen.terrain.pipeline.GenPoint;
import net.junedev.viridium.worldgen.terrain.pipeline.Stage;
import net.minecraft.world.WorldServer;

import com.cardinalstar.cubicchunks.api.IColumn;
import com.cardinalstar.cubicchunks.api.ICube;
import com.cardinalstar.cubicchunks.mixin.api.ICubicWorldInternal;
import com.cardinalstar.cubicchunks.server.CubeProviderServer;
import com.cardinalstar.cubicchunks.world.api.ICubeProviderServer.Requirement;

/**
 * Generator-agnostic pregeneration benchmark for any Cubic Chunks world (Viridium or CC's VanillaCubic): forces a box
 * of cubes through CC's own loader (generate, then populate + light), saves it, and reports wall time, CPU time of the
 * whole process (CC workers and Viridium's prefetch threads included), retained heap and bytes on disk. Same code path
 * for both generators, so the numbers compare directly.
 */
public final class GenBenchmark {

    private GenBenchmark() {}

    public static String run(WorldServer world, int radius, int cyMin, int cyMax, int bx, int bz) {
        StringBuilder out = new StringBuilder();
        CubeProviderServer cp = ((ICubicWorldInternal.Server) world).getCubeCache();
        ViridiumWorldGenerator vg = ViridiumWorldGenerator.forWorld(world);
        String genName = vg != null ? "Viridium"
            : world.getWorldInfo()
                .getTerrainType()
                .getWorldTypeName();
        if (vg != null) {
            double[] p = landNear(vg.gen(), bx, bz);
            bx = (int) p[0];
            bz = (int) p[1];
        }
        int ccx = bx >> 4, ccz = bz >> 4;
        File dir = world.getSaveHandler()
            .getWorldDirectory();
        flush(cp);
        long disk0 = size(dir);
        SaveMeter meter = new SaveMeter(ccx - radius, ccx + radius, cyMin, cyMax, ccz - radius, ccz + radius);
        long heap0 = usedHeapAfterGc();
        long[] lc0 = loadedCubes(cp);
        GenStats.reset();
        ThreadMXBean tmx = ManagementFactory.getThreadMXBean();
        long cpu0 = processCpu(), main0 = tmx.getCurrentThreadCpuTime();

        long t0 = System.nanoTime();
        List<int[]> cols = spiral(ccx, ccz, radius);
        for (int[] c : cols) for (int cy = cyMin; cy <= cyMax; cy++) cp.getCube(c[0], cy, c[1], Requirement.GENERATE);
        long t1 = System.nanoTime();
        for (int[] c : cols) for (int cy = cyMin; cy <= cyMax; cy++) cp.getCube(c[0], cy, c[1], Requirement.LIGHT);
        long t2 = System.nanoTime();
        long cpu2 = processCpu(), main2 = tmx.getCurrentThreadCpuTime();

        long heap1 = usedHeapAfterGc();
        long[] lc = loadedCubes(cp);
        long t3 = System.nanoTime();
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(meter);
        cp.saveChunks(true, null);
        flush(cp);
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.unregister(meter);
        long t4 = System.nanoTime();
        long disk1 = size(dir);

        int cubes = cols.size() * (cyMax - cyMin + 1);
        long loaded = lc[0] - lc0[0], nonEmpty = lc[1] - lc0[1];
        double genMs = (t1 - t0) / 1e6, litMs = (t2 - t1) / 1e6, saveMs = (t4 - t3) / 1e6;
        line(
            out,
            "=== %s · center %d,%d · %d columns x cubes %d..%d (Y %d..%d) = %d cubes ===",
            genName,
            bx,
            bz,
            cols.size(),
            cyMin,
            cyMax,
            cyMin << 4,
            (cyMax << 4) + 15,
            cubes);
        line(out, "time  generate %8.0f ms  (%.3f ms/cube, %.2f ms/column)", genMs, genMs / cubes, genMs / cols.size());
        line(out, "      pop+light %7.0f ms  (%.3f ms/cube)", litMs, litMs / cubes);
        line(
            out,
            "      total     %8.0f ms  (%.3f ms/cube, %.2f ms/column, %.0f cubes/s)",
            genMs + litMs,
            (genMs + litMs) / cubes,
            (genMs + litMs) / cols.size(),
            cubes / ((genMs + litMs) / 1000));
        line(out, "      save      %8.0f ms", saveMs);
        line(
            out,
            "cpu   server thread %.0f ms · whole process %.0f ms (all threads, incl. GC/JIT)",
            (main2 - main0) / 1e6,
            (cpu2 - cpu0) / 1e6);
        line(
            out,
            "heap  +%.1f MiB retained after GC (%.2f KiB per loaded cube; %d cubes loaded incl. neighbours, %d non-empty)",
            (heap1 - heap0) / 1048576.0,
            (heap1 - heap0) / 1024.0 / Math.max(1, loaded),
            loaded,
            nonEmpty);
        line(
            out,
            "disk  %.2f MiB as stored (gzip NBT): %.2f KiB per cube (%d cubes saved), %.1f KiB per column incl. column data; region files grew %.1f MiB",
            (meter.cubeBytes + meter.colBytes) / 1048576.0,
            meter.cubeBytes / 1024.0 / Math.max(1, meter.cubes),
            meter.cubes,
            (meter.cubeBytes + meter.colBytes) / 1024.0 / cols.size(),
            (disk1 - disk0) / 1048576.0);
        if (vg != null) {
            WorldGen g = vg.gen();
            line(
                out,
                "viridium caches: %d column plans, %d river networks (%d built)",
                vg.plans()
                    .size(),
                g.rivers.cachedCount(),
                g.rivers.buildCount());
            for (String l : GenStats.report()
                .split("\n")) line(out, "  %s", l);
        }
        return out.toString();
    }

    /** Sums the compressed size (as CC stores it: gzip NBT per cube and column) of what is saved inside the box. */
    public static final class SaveMeter {

        final int x0, x1, y0, y1, z0, z1;
        long cubeBytes, colBytes;
        int cubes;

        SaveMeter(int x0, int x1, int y0, int y1, int z0, int z1) {
            this.x0 = x0;
            this.x1 = x1;
            this.y0 = y0;
            this.y1 = y1;
            this.z0 = z0;
            this.z1 = z1;
        }

        @cpw.mods.fml.common.eventhandler.SubscribeEvent
        public void onCube(com.cardinalstar.cubicchunks.api.event.CubeEvent.SaveNBT e) {
            ICube c = e.cube;
            if (c.getX() < x0 || c.getX() > x1 || c.getZ() < z0 || c.getZ() > z1 || c.getY() < y0 || c.getY() > y1)
                return;
            cubeBytes += gz(e.tag);
            cubes++;
        }

        @cpw.mods.fml.common.eventhandler.SubscribeEvent
        public void onColumn(com.cardinalstar.cubicchunks.api.event.ColumnEvent.SaveNBT e) {
            net.minecraft.world.chunk.Chunk c = e.chunk;
            if (c.xPosition < x0 || c.xPosition > x1 || c.zPosition < z0 || c.zPosition > z1) return;
            colBytes += gz(e.tag);
        }

        private static long gz(net.minecraft.nbt.NBTTagCompound t) {
            try {
                return net.minecraft.nbt.CompressedStreamTools.compress(t).length;
            } catch (java.io.IOException ex) {
                return 0;
            }
        }
    }

    /** Columns around the centre, nearest first (like a player's load order). */
    private static List<int[]> spiral(int cx, int cz, int radius) {
        List<int[]> l = new ArrayList<>();
        for (int r = 0; r <= radius; r++) for (int dz = -r; dz <= r; dz++) for (int dx = -r; dx <= r; dx++)
            if (Math.max(Math.abs(dx), Math.abs(dz)) == r) l.add(new int[] { cx + dx, cz + dz });
        return l;
    }

    private static double[] landNear(WorldGen g, double x, double z) {
        GenPoint o = new GenPoint();
        for (int r = 0; r < 60; r++) for (int a = 0; a < 8; a++) {
            double px = x + r * 700 * Math.cos(a * Math.PI / 4), pz = z + r * 700 * Math.sin(a * Math.PI / 4);
            g.evaluate(px, pz, Stage.RELIEF, o);
            if (o.h0 > 20) return new double[] { px, pz };
        }
        return new double[] { x, z };
    }

    /** {loaded cubes, loaded cubes with block storage}. */
    private static long[] loadedCubes(CubeProviderServer cp) {
        long n = 0, full = 0;
        for (Object o : cp.loadedChunks) {
            for (ICube c : ((IColumn) o).getLoadedCubes()) {
                n++;
                if (!c.isEmpty()) full++;
            }
        }
        return new long[] { n, full };
    }

    private static void flush(CubeProviderServer cp) {
        try {
            cp.getCubeLoader()
                .flush();
        } catch (Exception e) {
            Viridium.LOGGER.warn("flush failed", e);
        }
    }

    private static long usedHeapAfterGc() {
        Runtime rt = Runtime.getRuntime();
        for (int i = 0; i < 3; i++) {
            System.gc();
            try {
                Thread.sleep(150);
            } catch (InterruptedException ignored) {}
        }
        return rt.totalMemory() - rt.freeMemory();
    }

    private static long processCpu() {
        java.lang.management.OperatingSystemMXBean os = ManagementFactory.getOperatingSystemMXBean();
        if (os instanceof com.sun.management.OperatingSystemMXBean)
            return ((com.sun.management.OperatingSystemMXBean) os).getProcessCpuTime();
        return 0;
    }

    private static long size(File f) {
        if (f.isFile()) return f.length();
        long s = 0;
        File[] fs = f.listFiles();
        if (fs != null) for (File c : fs) s += size(c);
        return s;
    }

    private static void line(StringBuilder sb, String fmt, Object... args) {
        sb.append(String.format(fmt, args))
            .append('\n');
    }
}
