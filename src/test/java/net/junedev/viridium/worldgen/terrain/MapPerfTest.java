package net.junedev.viridium.worldgen.terrain;

import java.lang.reflect.Method;

import net.junedev.viridium.worldgen.terrain.core.GenParams;
import net.junedev.viridium.worldgen.terrain.map.MapLayer;
import net.junedev.viridium.worldgen.terrain.map.MapTileRenderer;
import net.junedev.viridium.worldgen.terrain.stages.RiverStage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Map tile timings ({@code VIRIDIUM_BENCH=map}): a warm tile at levels 4 and 6 (evaluation, no cached samples), a
 * switch through every layer of a tile already drawn, and the first tile of an unexplored area (fresh pipeline, river
 * networks built on this thread like the map workers).
 */
class MapPerfTest {

    static double sink;

    static WorldGen gen() {
        GenParams p = new GenParams();
        p.fastFields = true;
        return new WorldGen(p);
    }

    /** Forgets the renderer's per-tile samples (when it has any) so the next render evaluates again. */
    static void clearSamples() {
        try {
            Method m = MapTileRenderer.class.getMethod("clearSamples");
            m.invoke(null);
        } catch (NoSuchMethodException e) {
            // renderer without sample cache
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    static double time(Runnable r) {
        long t0 = System.nanoTime();
        r.run();
        return (System.nanoTime() - t0) / 1e6;
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "VIRIDIUM_BENCH", matches = "map")
    void mapTiles() {
        RiverStage.buildSeriallyOnThisThread();
        StringBuilder out = new StringBuilder("Map tiles 128x128, fast fields\n");
        WorldGen g = gen();
        MapTileRenderer r = new MapTileRenderer(g);
        for (int level : new int[] { 4, 6 }) {
            for (int w = 0; w < 3; w++) { // JIT, nets, fields
                clearSamples();
                sink += r.render(MapLayer.COMPOSITE, level, 0, 0, 128, false)[0];
            }
            for (MapLayer l : new MapLayer[] { MapLayer.COMPOSITE, MapLayer.HEIGHT, MapLayer.EROSION,
                MapLayer.TEMPERATURE_FINAL }) {
                double best = 1e9;
                for (int rep = 0; rep < 3; rep++) {
                    clearSamples();
                    best = Math.min(best, time(() -> sink += r.render(l, level, 0, 0, 128, false)[0]));
                }
                out.append(String.format("  warm L%d %-24s %6.0f ms%n", level, l.name, best));
            }
            // a layer switch: every other layer of a tile whose composite is on screen
            clearSamples();
            sink += r.render(MapLayer.COMPOSITE, level, 1, 0, 128, false)[0];
            double all = 0, max = 0;
            for (MapLayer l : MapLayer.ALL) {
                if (l == MapLayer.COMPOSITE) continue;
                double t = time(() -> sink += r.render(l, level, 1, 0, 128, false)[0]);
                all += t;
                max = Math.max(max, t);
            }
            out.append(String.format("  L%d switch through the other %d layers of a drawn tile: %.0f ms (%.0f mean, max %.0f)%n",
                level, MapLayer.ALL.size() - 1, all, all / (MapLayer.ALL.size() - 1), max));
        }
        // the first tile of an unexplored area
        WorldGen c = gen();
        MapTileRenderer rc = new MapTileRenderer(c);
        clearSamples();
        long nets = c.rivers.buildCount();
        double first = time(() -> sink += rc.render(MapLayer.COMPOSITE, 4, 20, 15, 128, false)[0]);
        out.append(String.format("  cold L4 tile (20,15), complete: %.0f ms (%d nets built)%n", first,
            c.rivers.buildCount() - nets));
        System.out.print(out);
        System.out.println("(sink " + sink + ")");
    }
}
