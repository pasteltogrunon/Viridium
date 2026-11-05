package net.junedev.viridium.worldgen.terrain.column;

/**
 * Immutable result of all X/Z-only work for one 16x16 column. Computed once per column and shared by every cube of
 * that column, whatever its Y. Arrays are indexed {@code localZ * 16 + localX} (the vanilla biome-array order).
 */
public final class ColumnPlan {

    public static final int SIZE = 16;
    public static final int AREA = SIZE * SIZE;

    public final int chunkX, chunkZ;

    /** Terrain height relative to sea level (blocks). */
    private final float[] height;
    /** Absolute Y of the highest solid block, per column. */
    private final int[] surfaceY;
    /** Base temperature (°C) and precipitation (mm/year). */
    private final float[] temperature, precipitation;
    /** Surface rock ({@code Rock} ordinal). */
    private final byte[] rock;
    /** Biome id (vanilla registry). */
    private final byte[] biome;

    /** Extremes of {@link #surfaceY}: cubes above the max (and above the sea) are pure air. */
    public final int minSurfaceY, maxSurfaceY;

    ColumnPlan(int chunkX, int chunkZ, float[] height, int[] surfaceY, float[] temperature, float[] precipitation,
        byte[] rock, byte[] biome) {
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
        this.height = height;
        this.surfaceY = surfaceY;
        this.temperature = temperature;
        this.precipitation = precipitation;
        this.rock = rock;
        this.biome = biome;
        int min = Integer.MAX_VALUE, max = Integer.MIN_VALUE;
        for (int y : surfaceY) {
            min = Math.min(min, y);
            max = Math.max(max, y);
        }
        this.minSurfaceY = min;
        this.maxSurfaceY = max;
    }

    public static int index(int localX, int localZ) {
        return localZ * SIZE + localX;
    }

    public float height(int localX, int localZ) {
        return height[index(localX, localZ)];
    }

    public int surfaceY(int localX, int localZ) {
        return surfaceY[index(localX, localZ)];
    }

    public float temperature(int localX, int localZ) {
        return temperature[index(localX, localZ)];
    }

    public float precipitation(int localX, int localZ) {
        return precipitation[index(localX, localZ)];
    }

    public int rock(int localX, int localZ) {
        return rock[index(localX, localZ)];
    }

    public int biomeId(int localX, int localZ) {
        return biome[index(localX, localZ)] & 0xff;
    }

    /** Copy of the biome array, ready for {@code Chunk.setBiomeArray}. */
    public byte[] biomeArrayCopy() {
        return biome.clone();
    }
}
