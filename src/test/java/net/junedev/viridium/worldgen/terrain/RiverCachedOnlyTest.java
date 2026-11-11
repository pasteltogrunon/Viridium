package net.junedev.viridium.worldgen.terrain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.junedev.viridium.worldgen.terrain.core.GenParams;
import net.junedev.viridium.worldgen.terrain.pipeline.GenPoint;
import net.junedev.viridium.worldgen.terrain.pipeline.GenScratch;
import net.junedev.viridium.worldgen.terrain.pipeline.Stage;

import org.junit.jupiter.api.Test;

/**
 * Map previews evaluate with {@code riversCachedOnly}: no river network may ever be built from such an evaluation (not
 * by the river stage, not by the collapse pits of the cave stage), what was skipped is reported, and once every network
 * is there the result is the normal one.
 */
class RiverCachedOnlyTest {

    /** Land points next to collapse pits (see CaveTest) and a few river valleys. */
    private static final double[][] POINTS = { { 2614, 3915 }, { 5275, 3555 }, { 2369, 5523 }, { 5297, 5521 },
        { 4911, 4300 }, { 8094, 7820 }, { 3687, 4347 } };

    @Test
    void cachedOnlyNeverBuildsNetworks() {
        WorldGen gen = new WorldGen(new GenParams());
        GenPoint o = new GenPoint();
        GenScratch s = new GenScratch();
        s.riversCachedOnly = true;
        int incomplete = 0;
        for (double[] p : POINTS) for (int d = 0; d < 9; d++) {
            s.riversIncomplete = false;
            gen.evaluate(p[0] + (d % 3 - 1) * 12, p[1] + (d / 3 - 1) * 12, Stage.BIOMES, 1, o, s);
            if (s.riversIncomplete) incomplete++;
        }
        assertEquals(0, gen.rivers.buildCount(), "a cached-only evaluation built river networks");
        assertTrue(incomplete > 0, "nothing reported as incomplete although no network was built");

        // with every network built (normal evaluations), cached-only gives the normal result and is complete
        WorldGen ref = new WorldGen(new GenParams());
        GenPoint r = new GenPoint();
        GenScratch rs = new GenScratch();
        int compared = 0;
        for (double[] p : POINTS) for (int d = 0; d < 9; d++) {
            double x = p[0] + (d % 3 - 1) * 12, z = p[1] + (d / 3 - 1) * 12;
            ref.evaluate(x, z, Stage.BIOMES, 1, r, rs);
            gen.evaluate(x, z, Stage.BIOMES, 1, new GenPoint(), new GenScratch()); // builds what it needs, normally
            s.riversIncomplete = false;
            gen.evaluate(x, z, Stage.BIOMES, 1, o, s);
            if (s.riversIncomplete) continue;
            compared++;
            assertEquals(Double.doubleToLongBits(r.h), Double.doubleToLongBits(o.h), "height at " + x + "," + z);
            assertEquals(r.river, o.river);
            assertEquals(r.lake, o.lake);
            assertEquals(Double.doubleToLongBits(r.riverD), Double.doubleToLongBits(o.riverD));
        }
        System.out.printf(
            "cached-only: %d of %d points incomplete when cold, %d compared equal once built%n",
            incomplete,
            POINTS.length * 9,
            compared);
        assertTrue(compared > POINTS.length * 9 / 2, "too few complete points to compare: " + compared);
    }
}
