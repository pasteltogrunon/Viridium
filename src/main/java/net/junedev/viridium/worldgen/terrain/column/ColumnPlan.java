package net.junedev.viridium.worldgen.terrain.column;

import net.junedev.viridium.worldgen.terrain.crust.CrustPlan;

/**
 * Immutable result of all X/Z-only work for one 16x16 column. Computed once per column and shared by every cube of
 * that column, whatever its Y. Arrays are indexed {@code localZ * 16 + localX} (the vanilla biome-array order) and
 * must not be modified after construction.
 */
public final class ColumnPlan {

    public static final int SIZE = 16;
    public static final int AREA = SIZE * SIZE;
    /** "No water / no ice" marker for the Y arrays. */
    public static final int NONE = Integer.MIN_VALUE;

    public static final int F_RIVER = 1, F_LAKE = 2, F_SALT = 4, F_FLOE = 8, F_SHELF = 16, F_BERG = 32,
        F_FROZEN = 64, F_FALL = 128;

    public final int chunkX, chunkZ;

    /** Final terrain height relative to sea level. */
    public final float[] height = new float[AREA];
    /** Absolute Y of the highest solid terrain block. */
    public final int[] surfaceY = new int[AREA];
    /** Absolute Y of the highest water block, or {@link #NONE}. */
    public final int[] waterY = new int[AREA];
    /** Ice body [iceBottom, iceTop] (absolute Y), or {@link #NONE}. */
    public final int[] iceTop = new int[AREA], iceBottom = new int[AREA];
    /** Top of a waterfall curtain (falling water from the water below up to here), or {@link #NONE}. */
    public final int[] fallTop = new int[AREA];
    /** F_* flags. */
    public final byte[] flags = new byte[AREA];
    /** Final temperature (°C at the surface), precipitation (mm/year), slope (gradient). */
    public final float[] temperature = new float[AREA], precipitation = new float[AREA], slope = new float[AREA];
    /** Surface rock ({@code Rock} ordinal) and Viridium biome ({@code Biome} ordinal). */
    public final byte[] rock = new byte[AREA], biome = new byte[AREA];
    /** Minecraft biome ids. */
    public final byte[] vanillaBiome = new byte[AREA];
    /** 3D crust profile. */
    public final CrustPlan crust = new CrustPlan();

    /** Lowest terrain surface and highest thing (terrain, water, ice) in the column. */
    public int minSurfaceY, maxTopY;

    ColumnPlan(int chunkX, int chunkZ) {
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
    }

    void finish() {
        int min = Integer.MAX_VALUE, max = Integer.MIN_VALUE;
        for (int i = 0; i < AREA; i++) {
            min = Math.min(min, surfaceY[i]);
            max = Math.max(max, Math.max(Math.max(surfaceY[i], fallTop[i]), Math.max(waterY[i], iceTop[i])));
        }
        minSurfaceY = min;
        maxTopY = max + 1; // snow layer
    }

    public static int index(int localX, int localZ) {
        return localZ * SIZE + localX;
    }

    public int surfaceY(int localX, int localZ) {
        return surfaceY[index(localX, localZ)];
    }

    public float height(int localX, int localZ) {
        return height[index(localX, localZ)];
    }

    public int biomeId(int localX, int localZ) {
        return vanillaBiome[index(localX, localZ)] & 0xff;
    }

    public boolean has(int i, int flag) {
        return (flags[i] & flag) != 0;
    }

    /** Copy of the Minecraft biome array, ready for {@code Chunk.setBiomeArray}. */
    public byte[] biomeArrayCopy() {
        return vanillaBiome.clone();
    }
}
