package net.junedev.viridium.worldgen.terrain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;

import net.junedev.viridium.worldgen.terrain.core.GenParams;
import net.junedev.viridium.worldgen.terrain.pipeline.GenPoint;
import net.junedev.viridium.worldgen.terrain.pipeline.Stage;
import net.junedev.viridium.worldgen.terrain.stages.BasinGraph;
import net.junedev.viridium.worldgen.terrain.stages.RiverNet;
import net.junedev.viridium.worldgen.terrain.stages.RiverNetStore;
import net.junedev.viridium.worldgen.terrain.stages.RiverStage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** River networks on disk: what is read back is what was built, and a world generated from them is the same world. */
class RiverNetStoreTest {

    /** A land basin cell near the origin. */
    static BasinGraph.Cell landCell(WorldGen g) {
        for (int r = 0; r < 40; r++) for (int z = -r; z <= r; z++) for (int x = -r; x <= r; x++) {
            if (Math.max(Math.abs(x), Math.abs(z)) != r) continue;
            for (long k : g.rivers.basinsAt(x * 1000.0, z * 1000.0)) {
                BasinGraph.Cell c = g.rivers.basins.cell(k);
                if (!c.ocean) return c;
            }
        }
        throw new IllegalStateException("no land near the origin");
    }

    /** Every public field of both networks holds the same bits. */
    static void assertSameNet(RiverNet a, RiverNet b) throws Exception {
        for (Field f : RiverNet.class.getFields()) {
            if (Modifier.isStatic(f.getModifiers())) continue;
            Object x = f.get(a), y = f.get(b);
            if (x != null && x.getClass()
                .isArray()) {
                assertNotNull(y, f.getName());
                int n = Array.getLength(x);
                assertEquals(n, Array.getLength(y), f.getName());
                for (int i = 0; i < n; i++)
                    assertEquals(bits(Array.get(x, i)), bits(Array.get(y, i)), f.getName() + "[" + i + "]");
            } else assertEquals(bits(x), bits(y), f.getName());
        }
    }

    private static Object bits(Object v) {
        if (v instanceof Float f) return Float.floatToRawIntBits(f);
        if (v instanceof Double d) return Double.doubleToRawLongBits(d);
        return v;
    }

    @Test
    void roundTrip(@TempDir Path tmp) throws Exception {
        RiverStage.buildSeriallyOnThisThread();
        WorldGen g = new WorldGen(new GenParams());
        BasinGraph.Cell c = landCell(g);
        RiverNet built = g.rivers.net(c.ci, c.cj);
        RiverNetStore store = RiverNetStore.open(tmp.toFile(), g.params);
        assertNotNull(store);
        assertNull(store.load(c.ci, c.cj));
        store.save(built);
        RiverNet read = store.load(c.ci, c.cj);
        assertNotNull(read);
        assertSameNet(built, read);

        // a damaged file is dropped, not trusted
        File f = new File(store.dir(), c.ci + "_" + c.cj + ".bin");
        byte[] bytes = Files.readAllBytes(f.toPath());
        Files.write(f.toPath(), java.util.Arrays.copyOf(bytes, bytes.length / 2));
        assertNull(store.load(c.ci, c.cj));
        assertTrue(!f.exists());
    }

    @Test
    void sameWorldFromStoredNetworks(@TempDir Path tmp) {
        RiverStage.buildSeriallyOnThisThread();
        WorldGen plain = new WorldGen(new GenParams());
        BasinGraph.Cell c = landCell(plain);
        final int N = 24, STEP = 60;
        double x0 = c.fx - N * STEP / 2.0, z0 = c.fy - N * STEP / 2.0;

        // first session: builds and writes
        WorldGen first = new WorldGen(new GenParams());
        first.rivers.setStore(RiverNetStore.open(tmp.toFile(), first.params));
        GenPoint o = new GenPoint();
        for (int j = 0; j < N; j++)
            for (int i = 0; i < N; i++) first.evaluate(x0 + i * STEP, z0 + j * STEP, Stage.BIOMES, o);
        assertTrue(first.rivers.buildCount() > 0);
        RiverNetStore.awaitWrites();

        // second session: reads, builds nothing, and gives the world of a generator without a store
        WorldGen second = new WorldGen(new GenParams());
        second.rivers.setStore(RiverNetStore.open(tmp.toFile(), second.params));
        GenPoint a = new GenPoint(), b = new GenPoint();
        for (int j = 0; j < N; j++) for (int i = 0; i < N; i++) {
            double x = x0 + i * STEP, z = z0 + j * STEP;
            plain.evaluate(x, z, Stage.BIOMES, a);
            second.evaluate(x, z, Stage.BIOMES, b);
            String at = "(" + x + ", " + z + ")";
            assertEquals(Double.doubleToRawLongBits(a.h), Double.doubleToRawLongBits(b.h), "h " + at);
            assertEquals(Double.doubleToRawLongBits(a.riverWL), Double.doubleToRawLongBits(b.riverWL), "riverWL " + at);
            assertEquals(Double.doubleToRawLongBits(a.lakeZ), Double.doubleToRawLongBits(b.lakeZ), "lakeZ " + at);
            assertEquals(Double.doubleToRawLongBits(a.valley), Double.doubleToRawLongBits(b.valley), "valley " + at);
            assertEquals(a.river, b.river, "river " + at);
            assertEquals(a.lake, b.lake, "lake " + at);
            assertEquals(a.biome, b.biome, "biome " + at);
        }
        assertEquals(0, second.rivers.buildCount());
        assertTrue(second.rivers.loadCount() > 0);
    }
}
