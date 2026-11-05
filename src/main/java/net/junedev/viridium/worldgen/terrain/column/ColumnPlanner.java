package net.junedev.viridium.worldgen.terrain.column;

import net.junedev.viridium.worldgen.terrain.WorldGen;
import net.junedev.viridium.worldgen.terrain.pipeline.GenPoint;
import net.junedev.viridium.worldgen.terrain.pipeline.Stage;

/** Pure function from column coordinates to a {@link ColumnPlan}. Thread-safe; no Minecraft classes. */
public final class ColumnPlanner {

    private final WorldGen gen;
    private final BiomeMapper biomes;
    private final ThreadLocal<GenPoint> point = ThreadLocal.withInitial(GenPoint::new);

    public ColumnPlanner(WorldGen gen, BiomeMapper biomes) {
        this.gen = gen;
        this.biomes = biomes;
    }

    public WorldGen gen() {
        return gen;
    }

    public ColumnPlan plan(int chunkX, int chunkZ) {
        float[] height = new float[ColumnPlan.AREA];
        int[] surfaceY = new int[ColumnPlan.AREA];
        float[] temp = new float[ColumnPlan.AREA];
        float[] rain = new float[ColumnPlan.AREA];
        byte[] rock = new byte[ColumnPlan.AREA];
        byte[] biome = new byte[ColumnPlan.AREA];
        GenPoint p = point.get();
        int x0 = chunkX * ColumnPlan.SIZE, z0 = chunkZ * ColumnPlan.SIZE;
        for (int lz = 0; lz < ColumnPlan.SIZE; lz++) {
            for (int lx = 0; lx < ColumnPlan.SIZE; lx++) {
                gen.evaluate(x0 + lx, z0 + lz, Stage.GEOLOGY, p);
                int i = ColumnPlan.index(lx, lz);
                height[i] = (float) p.h;
                surfaceY[i] = TerrainLayout.surfaceY(p.h);
                temp[i] = (float) p.Tb;
                rain[i] = (float) p.Pb;
                rock[i] = (byte) (p.rockT >= 0.5 ? p.rock : p.rock2);
                biome[i] = (byte) biomes.biomeId(p);
            }
        }
        return new ColumnPlan(chunkX, chunkZ, height, surfaceY, temp, rain, rock, biome);
    }
}
