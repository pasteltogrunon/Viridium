package net.junedev.viridium.compat.dh;

import static net.junedev.viridium.worldgen.terrain.column.ColumnPlan.NONE;

import java.util.EnumMap;
import java.util.Map;

import net.junedev.viridium.worldgen.terrain.cube.flora.Feature;
import net.junedev.viridium.worldgen.terrain.cube.flora.FeatureType;
import net.minecraft.block.Block;

/**
 * Typical crown of each tree species, measured by drawing the real shapes a few times: how high the leaves start and
 * end above the base, how many block columns a crown covers, and its leaf block. Far LODs use it to dress forests
 * without planning individual trees.
 */
final class TreeShapes {

    /** Crown statistics; offsets are relative to the tree base (first block above the ground). */
    record Shape(int leafBottom, int leafTop, double crownArea, Block leaves, int leavesMeta) {}

    private static final int SAMPLES = 8, AREA = 48;
    private static final Map<FeatureType, Shape> SHAPES = new EnumMap<>(FeatureType.class);

    private TreeShapes() {}

    /** The shape of a tree species, or null for plants. */
    static synchronized Shape of(FeatureType type) {
        if (type.height <= 3) return null; // grass, flowers, cacti
        Shape s = SHAPES.get(type);
        if (s == null) SHAPES.put(type, s = measure(type));
        return s;
    }

    private static Shape measure(FeatureType type) {
        CanopySink sink = new CanopySink(AREA);
        int c = AREA / 2;
        long bottom = 0, top = 0, columns = 0;
        Block leaves = null;
        int meta = 0;
        for (int k = 0; k < SAMPLES; k++) {
            sink.reset(0, 0);
            type.draw(sink, new Feature(type, c, 0, c, 0x7EE5L * (k + 1)));
            int lo = Integer.MAX_VALUE, hi = Integer.MIN_VALUE;
            for (int i = 0; i < AREA * AREA; i++) {
                if (sink.leafMin[i] == NONE) continue;
                lo = Math.min(lo, sink.leafMin[i]);
                hi = Math.max(hi, sink.leafMax[i] + 1);
                columns++;
                if (leaves == null) {
                    leaves = sink.leafBlock[i];
                    meta = sink.leafMeta[i];
                }
            }
            if (lo != Integer.MAX_VALUE) {
                bottom += lo;
                top += hi;
            }
        }
        return new Shape((int) Math.round((double) bottom / SAMPLES), (int) Math.round((double) top / SAMPLES),
            (double) columns / SAMPLES, leaves, meta);
    }
}
