package net.junedev.viridium.worldgen.terrain.cube;

import java.util.Random;

import net.junedev.viridium.ViriBlocks;
import net.junedev.viridium.worldgen.terrain.column.ColumnPlan;
import net.junedev.viridium.worldgen.terrain.column.ColumnPlanCache;
import net.junedev.viridium.worldgen.terrain.stages.Biome;
import net.junedev.viridium.worldgen.treegen.TreeRegistry;
import net.minecraft.init.Blocks;
import net.minecraft.world.World;
import net.minecraft.world.gen.feature.WorldGenBigTree;
import net.minecraft.world.gen.feature.WorldGenCactus;
import net.minecraft.world.gen.feature.WorldGenDeadBush;
import net.minecraft.world.gen.feature.WorldGenFlowers;
import net.minecraft.world.gen.feature.WorldGenForest;
import net.minecraft.world.gen.feature.WorldGenMegaJungle;
import net.minecraft.world.gen.feature.WorldGenMegaPineTree;
import net.minecraft.world.gen.feature.WorldGenSavannaTree;
import net.minecraft.world.gen.feature.WorldGenSwamp;
import net.minecraft.world.gen.feature.WorldGenTaiga1;
import net.minecraft.world.gen.feature.WorldGenTaiga2;
import net.minecraft.world.gen.feature.WorldGenTallGrass;
import net.minecraft.world.gen.feature.WorldGenTrees;
import net.minecraft.world.gen.feature.WorldGenerator;

/**
 * Minimal surface population per Viridium biome: trees (Viridium species where they exist, vanilla otherwise), grass,
 * flowers and desert plants. Follows the vanilla +8 offset: a cube decorates the 16x16 area centred on its +X/+Z
 * corner, so features only touch cubes (x..x+1, y..y+2, z..z+1), which the caller generates first.
 */
public class SurfaceDecorator {

    private final ColumnPlanCache plans;

    public SurfaceDecorator(ColumnPlanCache plans) {
        this.plans = plans;
    }

    /** Trees per 16x16 area. */
    protected double treeDensity(Biome b) {
        return switch (b) {
            case TROPICAL_RAINFOREST -> 12;
            case TEMPERATE_RAINFOREST -> 10;
            case TEMPERATE_FOREST -> 8;
            case TAIGA -> 7;
            case SNOWY_TAIGA -> 5;
            case SWAMP, MANGROVE -> 3;
            case KARST -> 2;
            case SAVANNA, DELTA -> 1;
            case ALPINE_MEADOW -> 0.5;
            case GRASSLAND, BEACH -> 0.3;
            case COLD_STEPPE -> 0.1;
            default -> 0;
        };
    }

    protected WorldGenerator tree(Biome b, Random r, float temperature) {
        switch (b) {
            case TROPICAL_RAINFOREST:
                if (r.nextInt(5) == 0) return new WorldGenMegaJungle(false, 10, 20, 3, 3);
                return new WorldGenTrees(false, 4 + r.nextInt(7), 3, 3, true);
            case TEMPERATE_RAINFOREST:
                return r.nextInt(3) == 0 ? new WorldGenMegaPineTree(false, r.nextBoolean()) : new WorldGenTaiga2(false);
            case TAIGA:
            case SNOWY_TAIGA:
                return r.nextInt(3) == 0 ? new WorldGenTaiga1() : new WorldGenTaiga2(false);
            case SWAMP:
            case MANGROVE:
                return new WorldGenSwamp();
            case SAVANNA:
                return new WorldGenSavannaTree(false);
            case BEACH:
                return temperature > 20 ? TreeRegistry.createGenerator("viridium:coconut_palm", ViriBlocks.coconut_palm, false)
                    : null;
            case TEMPERATE_FOREST: {
                int k = r.nextInt(10);
                if (k < 4) return TreeRegistry.createGenerator("viridium:pedunculate_oak", ViriBlocks.pedunculate_oak, false);
                if (k < 6)
                    return TreeRegistry.createGenerator("viridium:american_chestnut", ViriBlocks.american_chestnut, false);
                if (k < 8) return new WorldGenForest(false, false);
                return new WorldGenTrees(false);
            }
            default:
                return r.nextInt(10) == 0 ? new WorldGenBigTree(false) : new WorldGenTrees(false);
        }
    }

    protected int grassCount(Biome b) {
        return switch (b) {
            case GRASSLAND -> 20;
            case SAVANNA -> 15;
            case TROPICAL_RAINFOREST, TEMPERATE_RAINFOREST -> 10;
            case COLD_STEPPE -> 8;
            case TEMPERATE_FOREST, SWAMP, ALPINE_MEADOW, DELTA -> 6;
            case TUNDRA, TAIGA, SNOWY_TAIGA, KARST -> 3;
            default -> 0;
        };
    }

    /**
     * Decorates the area of cube (cx, cy, cz). Only positions whose surface lies inside this cube are used, so each
     * feature is placed by exactly one cube.
     */
    public void decorate(World world, long seed, int cx, int cy, int cz) {
        Random r = new Random(seed ^ (cx * 341873128712L + cy * 132897987541L + cz * 914744123L));
        int x0 = (cx << 4) + 8, z0 = (cz << 4) + 8, y0 = cy << 4, y1 = y0 + 15;
        // dominant biome of the area (sampled at its centre)
        ColumnPlan center = plans.get((x0 + 8) >> 4, (z0 + 8) >> 4);
        int ci = ColumnPlan.index((x0 + 8) & 15, (z0 + 8) & 15);
        Biome b = Biome.VALUES[center.biome[ci]];
        float t = center.temperature[ci];

        double density = treeDensity(b);
        int trees = (int) density + (r.nextDouble() < density - (int) density ? 1 : 0);
        for (int k = 0; k < trees; k++) {
            int x = x0 + r.nextInt(16), z = z0 + r.nextInt(16);
            int y = groundY(x, z);
            if (y < y0 || y > y1) continue;
            WorldGenerator g = tree(b, r, t);
            if (g != null) g.generate(world, r, x, y, z);
        }
        int grass = grassCount(b);
        for (int k = 0; k < grass; k++) {
            int x = x0 + r.nextInt(16), z = z0 + r.nextInt(16);
            int y = groundY(x, z);
            if (y < y0 || y > y1) continue;
            boolean fern = (b == Biome.TAIGA || b == Biome.SNOWY_TAIGA || b == Biome.TEMPERATE_RAINFOREST) && r.nextInt(3) == 0;
            new WorldGenTallGrass(Blocks.tallgrass, fern ? 2 : 1).generate(world, r, x, y, z);
        }
        if (b == Biome.GRASSLAND || b == Biome.TEMPERATE_FOREST || b == Biome.ALPINE_MEADOW) {
            for (int k = 0; k < 2; k++) {
                int x = x0 + r.nextInt(16), z = z0 + r.nextInt(16);
                int y = groundY(x, z);
                if (y < y0 || y > y1) continue;
                WorldGenFlowers f = new WorldGenFlowers(r.nextBoolean() ? Blocks.yellow_flower : Blocks.red_flower);
                f.func_150550_a(r.nextBoolean() ? Blocks.yellow_flower : Blocks.red_flower, 0);
                f.generate(world, r, x, y, z);
            }
        }
        if (b == Biome.DESERT || b == Biome.BADLANDS) {
            for (int k = 0; k < 3; k++) {
                int x = x0 + r.nextInt(16), z = z0 + r.nextInt(16);
                int y = groundY(x, z);
                if (y < y0 || y > y1) continue;
                if (b == Biome.DESERT && r.nextBoolean()) new WorldGenCactus().generate(world, r, x, y, z);
                else new WorldGenDeadBush(Blocks.deadbush).generate(world, r, x, y, z);
            }
        }
    }

    /** First air Y above dry ground, or Integer.MIN_VALUE when the column is under water or ice. */
    private int groundY(int x, int z) {
        ColumnPlan p = plans.get(x >> 4, z >> 4);
        int i = ColumnPlan.index(x & 15, z & 15);
        if (p.waterY[i] != ColumnPlan.NONE || p.iceTop[i] != ColumnPlan.NONE) return Integer.MIN_VALUE;
        return p.surfaceY[i] + 1;
    }
}
