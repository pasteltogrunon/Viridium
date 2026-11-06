package net.junedev.viridium.worldgen.terrain.cube.flora;

/**
 * A planned piece of vegetation: what, where (absolute block position of its base) and a seed for its shape.
 * Immutable and fully determined by the world seed and its owner column, so every cube it overlaps can redraw exactly
 * its own part, in any order.
 */
public final class Feature {

    public final FeatureType type;
    public final int x, y, z;
    public final long seed;
    /** Block of the feature when it has one of its own (boulders: the local rock), else null. */
    public final net.minecraft.block.Block block;
    /** Conservative bounding box (inclusive). */
    public final int minX, minY, minZ, maxX, maxY, maxZ;

    public Feature(FeatureType type, int x, int y, int z, long seed) {
        this(type, x, y, z, seed, null);
    }

    public Feature(FeatureType type, int x, int y, int z, long seed, net.minecraft.block.Block block) {
        this.block = block;
        this.type = type;
        this.x = x;
        this.y = y;
        this.z = z;
        this.seed = seed;
        int r = type.radius, h = type.height;
        this.minX = x - r;
        this.maxX = x + r + 1; // 2x2 trunks extend to +1
        this.minZ = z - r;
        this.maxZ = z + r + 1;
        this.minY = y - 1;
        this.maxY = y + h;
    }

    public boolean intersectsCube(int cx, int cy, int cz) {
        int x0 = cx << 4, y0 = cy << 4, z0 = cz << 4;
        return maxX >= x0 && minX <= x0 + 15 && maxY >= y0 && minY <= y0 + 15 && maxZ >= z0 && minZ <= z0 + 15;
    }
}
