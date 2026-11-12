package net.junedev.viridium.worldgen.terrain;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import net.junedev.viridium.worldgen.terrain.core.GenParams;
import net.junedev.viridium.worldgen.terrain.pipeline.GenPoint;
import net.junedev.viridium.worldgen.terrain.pipeline.Stage;
import net.junedev.viridium.worldgen.terrain.stages.BasinGraph;
import net.junedev.viridium.worldgen.terrain.stages.RiverNet;
import net.junedev.viridium.worldgen.terrain.stages.RiverStage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * What persisting the river networks would cost and save ({@code VIRIDIUM_BENCH=nets}): how many basin cells a
 * continent-sized area has, how long a network takes to build (serially, like the map workers), how much it weighs in
 * memory and serialized (every array field, raw and gzip), how long reading it back takes, and what a chunk costs with
 * and without its networks. Writes {@code build/reports/nets.txt}.
 */
class RiverNetSizeTest {

    static double sink;

    /** Every public field of the network as bytes (arrays with their length; maps skipped: they mirror arrays). */
    static byte[] serialize(RiverNet n, boolean withIndex) throws Exception {
        ByteArrayOutputStream bo = new ByteArrayOutputStream(1 << 20);
        DataOutputStream out = new DataOutputStream(bo);
        for (Field f : RiverNet.class.getFields()) {
            if (Modifier.isStatic(f.getModifiers())) continue;
            if (!withIndex && (f.getName()
                .equals("chStart")
                || f.getName()
                    .equals("chCells")))
                continue;
            Object v = f.get(n);
            Class<?> t = f.getType();
            if (t.isArray()) {
                int len = v == null ? -1 : Array.getLength(v);
                out.writeInt(len);
                if (v instanceof int[]a) for (int x : a) out.writeInt(x);
                else if (v instanceof float[]a) for (float x : a) out.writeFloat(x);
                else if (v instanceof double[]a) for (double x : a) out.writeDouble(x);
                else if (v instanceof byte[]a) out.write(a);
                else if (v instanceof char[]a) for (char x : a) out.writeChar(x);
                else if (v instanceof short[]a) for (short x : a) out.writeShort(x);
                else if (v != null) throw new IllegalStateException("field " + f.getName());
            } else if (t == int.class) out.writeInt(f.getInt(n));
            else if (t == long.class) out.writeLong(f.getLong(n));
            else if (t == float.class) out.writeFloat(f.getFloat(n));
            else if (t == double.class) out.writeDouble(f.getDouble(n));
        }
        out.flush();
        return bo.toByteArray();
    }

    static byte[] gzip(byte[] raw) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream(raw.length / 2);
        try (GZIPOutputStream gz = new GZIPOutputStream(bo, 1 << 16)) {
            gz.write(raw);
        }
        return bo.toByteArray();
    }

    static int gunzip(byte[] gz) throws IOException {
        byte[] buf = new byte[1 << 16];
        int total = 0;
        try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(gz), 1 << 16)) {
            for (int r; (r = in.read(buf)) > 0;) total += r;
        }
        return total;
    }

    /** Milliseconds per 16x16 chunk evaluated up to the biomes over a square of chunks. */
    static double chunkMs(WorldGen g, int bx, int bz, int chunks) {
        GenPoint o = new GenPoint();
        long t0 = System.nanoTime();
        for (int cz = 0; cz < chunks; cz++)
            for (int cx = 0; cx < chunks; cx++) for (int lz = 0; lz < 16; lz++) for (int lx = 0; lx < 16; lx++) {
                g.evaluate(bx + cx * 16 + lx, bz + cz * 16 + lz, Stage.BIOMES, o);
                sink += o.h;
            }
        return (System.nanoTime() - t0) / 1e6 / (chunks * chunks);
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "VIRIDIUM_BENCH", matches = "nets")
    void netSizes() throws Exception {
        RiverStage.buildSeriallyOnThisThread();
        StringBuilder out = new StringBuilder();
        GenParams p = new GenParams();
        WorldGen g = new WorldGen(p);
        out.append(
            String.format("basinSize %.0f, riverGrid %d, fastFields %s%n", p.basinSize, p.riverGrid, p.fastFields));

        // basin cells that shape a continent-sized square around the origin
        final int HALF = 10000;
        Set<Long> all = new LinkedHashSet<>(), land = new LinkedHashSet<>();
        for (int z = -HALF; z <= HALF; z += 400)
            for (int x = -HALF; x <= HALF; x += 400) for (long k : g.rivers.basinsAt(x, z)) {
                if (all.add(k) && !g.rivers.basins.cell(k).ocean) land.add(k);
            }
        out.append(
            String
                .format("area %d x %d blocks: %d basin cells, %d land%n", 2 * HALF, 2 * HALF, all.size(), land.size()));

        // build a sample of them
        int sample = Integer.parseInt(
            System.getenv()
                .getOrDefault("VIRIDIUM_NETS", "40"));
        List<RiverNet> nets = new ArrayList<>();
        long builds0 = g.rivers.buildCount();
        long t0 = System.nanoTime();
        for (long k : land) {
            if (nets.size() >= sample) break;
            nets.add(g.rivers.net(BasinGraph.keyA(k), BasinGraph.keyB(k)));
        }
        double buildMs = (System.nanoTime() - t0) / 1e6;
        long builds = g.rivers.buildCount() - builds0;
        out.append(
            String.format(
                "built %d networks (%d requested + upstream) in %.0f ms: %.0f ms each, serial%n",
                builds,
                nets.size(),
                buildMs,
                buildMs / Math.max(1, builds)));

        long mem = 0, raw = 0, gz = 0, rawNoIdx = 0, gzNoIdx = 0, reaches = 0, cells = 0, maxGz = 0,
            minGz = Long.MAX_VALUE;
        double readMs = 0, writeMs = 0;
        for (RiverNet n : nets) {
            byte[] r = serialize(n, true);
            long w0 = System.nanoTime();
            byte[] z = gzip(r);
            writeMs += (System.nanoTime() - w0) / 1e6;
            long r0 = System.nanoTime();
            sink += gunzip(z);
            readMs += (System.nanoTime() - r0) / 1e6;
            byte[] r2 = serialize(n, false);
            mem += n.bytes;
            raw += r.length;
            gz += z.length;
            rawNoIdx += r2.length;
            gzNoIdx += gzip(r2).length;
            reaches += n.count;
            cells += (long) n.nx * n.ny;
            maxGz = Math.max(maxGz, z.length);
            minGz = Math.min(minGz, z.length);
        }
        int c = Math.max(1, nets.size());
        out.append(
            String
                .format("per network (mean of %d): grid %d cells, %d reaches%n", nets.size(), cells / c, reaches / c));
        out.append(String.format("  in memory (builder's estimate) %8.0f KB%n", mem / 1024.0 / c));
        out.append(
            String.format(
                "  serialized raw                 %8.0f KB   gzip %6.0f KB (min %.0f, max %.0f)%n",
                raw / 1024.0 / c,
                gz / 1024.0 / c,
                minGz / 1024.0,
                maxGz / 1024.0));
        out.append(
            String.format(
                "  without the channel index      %8.0f KB   gzip %6.0f KB%n",
                rawNoIdx / 1024.0 / c,
                gzNoIdx / 1024.0 / c));
        out.append(String.format("  gzip write %.1f ms, read %.1f ms each%n", writeMs / c, readMs / c));
        out.append(
            String.format(
                "whole area (%d land cells): %.1f MB gzip, %.0f s to build serially%n",
                land.size(),
                gz / 1048576.0 / c * land.size(),
                buildMs / Math.max(1, builds) * land.size() / 1000));

        // a chunk with its networks built, and the first chunk of a session at the same place
        if (!nets.isEmpty()) {
            BasinGraph.Cell cell = g.rivers.basins.cell(nets.get(0).ci, nets.get(0).cj);
            int bx = (int) cell.fx & ~15, bz = (int) cell.fy & ~15;
            chunkMs(g, bx, bz, 8); // builds what is missing, JIT
            chunkMs(g, bx, bz, 8);
            double warm = chunkMs(g, bx, bz, 8);
            WorldGen cold = new WorldGen(new GenParams());
            long cb = cold.rivers.buildCount();
            double first = chunkMs(cold, bx, bz, 1);
            out.append(
                String.format(
                    "chunk at (%d, %d) up to the biomes: %.2f ms with its networks built; first chunk of a fresh generator %.0f ms (%d networks built)%n",
                    bx,
                    bz,
                    warm,
                    first,
                    cold.rivers.buildCount() - cb));

            // the same with the networks on disk
            File dir = Files.createTempDirectory("rivernets")
                .toFile();
            net.junedev.viridium.worldgen.terrain.stages.RiverNetStore store = net.junedev.viridium.worldgen.terrain.stages.RiverNetStore
                .open(dir, p);
            long disk = 0;
            for (RiverNet n : nets) store.save(n);
            long l0 = System.nanoTime();
            for (RiverNet n : nets) {
                sink += store.load(n.ci, n.cj).count;
                disk += new File(store.dir(), n.ci + "_" + n.cj + ".bin").length();
            }
            double loadMs = (System.nanoTime() - l0) / 1e6 / nets.size();
            WorldGen stored = new WorldGen(new GenParams());
            stored.rivers.setStore(store);
            double firstStored = chunkMs(stored, bx, bz, 1);
            out.append(
                String.format(
                    "store: %.0f KB and %.1f ms to load per network; first chunk of a fresh generator %.0f ms (%d loaded, %d built)%n",
                    disk / 1024.0 / nets.size(),
                    loadMs,
                    firstStored,
                    stored.rivers.loadCount(),
                    stored.rivers.buildCount()));
            File[] fs = store.dir()
                .listFiles();
            if (fs != null) for (File f : fs) f.delete();
            store.dir()
                .delete();
            dir.delete();
        }
        out.append("(sink ")
            .append(sink)
            .append(")\n");
        System.out.print(out);
        File rep = new File("build/reports/nets.txt");
        rep.getParentFile()
            .mkdirs();
        Files.write(
            rep.toPath(),
            out.toString()
                .getBytes(StandardCharsets.UTF_8));
    }
}
