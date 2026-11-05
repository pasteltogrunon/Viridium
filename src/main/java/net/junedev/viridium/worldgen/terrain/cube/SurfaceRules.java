package net.junedev.viridium.worldgen.terrain.cube;

import static net.junedev.viridium.worldgen.terrain.column.TerrainLayout.SEA_LEVEL;

import net.junedev.viridium.worldgen.terrain.column.ColumnPlan;
import net.junedev.viridium.worldgen.terrain.core.Noise;
import net.junedev.viridium.worldgen.terrain.crust.Lithology;
import net.junedev.viridium.worldgen.terrain.stages.Biome;
import net.minecraft.block.Block;
import net.minecraft.init.Blocks;

/**
 * Surface materials of a column: the top block and a filler of limited depth, chosen once per column from the biome,
 * water, slope and climate. Below the filler the 3D crust shows through. Rules never look at neighbouring columns or
 * cubes, so any cube can be painted on its own.
 */
public final class SurfaceRules {

    /** Deepest block any rule can paint below the surface. Cubes deeper than this skip the rules entirely. */
    public static final int MAX_DEPTH = net.junedev.viridium.worldgen.terrain.column.TerrainLayout.SURFACE_RULE_DEPTH;

    private static final int[] BADLANDS_BANDS = { 1, 0, 4, 14, 1, 12, 8, 1, 0, 14, 12, 4 };

    /** Resolved materials for one column. Mutable scratch; one per thread. */
    public static final class Column {

        public Block top, under;
        public int topMeta, underMeta, depth;
        /** Badlands-style coloured clay bands instead of {@link #under}. */
        public boolean bands;
        public boolean snow;
    }

    private final Block evaporite;

    public SurfaceRules(Block[] lithologyBlocks) {
        this.evaporite = lithologyBlocks[Lithology.EVAPORITE.ordinal()];
    }

    public void resolve(ColumnPlan plan, int i, int x, int z, Column c) {
        int surface = plan.surfaceY[i];
        boolean underwater = plan.waterY[i] != ColumnPlan.NONE || plan.iceBottom[i] != ColumnPlan.NONE;
        float h = plan.height[i], t = plan.temperature[i], slope = plan.slope[i];
        Biome b = Biome.VALUES[plan.biome[i]];
        double r = (Noise.hash(x, z, 0x51) & 0xffff) / 65536.0;
        c.bands = false;
        c.topMeta = c.underMeta = 0;
        c.snow = !underwater && t < -0.5 && surface >= SEA_LEVEL - 1;

        if (underwater) {
            if (plan.has(i, ColumnPlan.F_RIVER)) set(c, Blocks.gravel, Blocks.gravel, 2);
            else if (plan.has(i, ColumnPlan.F_LAKE)) set(c, r < 0.5 ? Blocks.clay : Blocks.sand, Blocks.sand, 2);
            else if (h < -45) set(c, Blocks.clay, Blocks.gravel, 2);
            else if (h < -20 || t < 4) set(c, Blocks.gravel, Blocks.gravel, 2);
            else set(c, Blocks.sand, Blocks.sand, 3);
            return;
        }
        if (plan.has(i, ColumnPlan.F_SALT)) {
            set(c, evaporite, evaporite, 2);
            return;
        }
        // cliffs: too steep for soil
        if (slope > 1.1 && b != Biome.BEACH && b != Biome.DESERT) {
            set(c, null, null, 0);
            return;
        }
        switch (b) {
            case BEACH, SNOWY_BEACH -> set(c, Blocks.sand, Blocks.sand, 4);
            case ROCKY_SHORE -> set(c, Blocks.gravel, null, 1);
            case RIVER, DELTA -> set(c, Blocks.grass, Blocks.dirt, 2);
            case GLACIER -> set(c, Blocks.packed_ice, Blocks.packed_ice, 5);
            case SNOWY_PEAKS -> set(c, Blocks.snow, Blocks.packed_ice, 3);
            case SNOWY_SLOPES -> set(c, Blocks.snow, Blocks.snow, 1);
            case ROCKY_PEAKS -> set(c, null, null, 0);
            case DESERT -> set(c, Blocks.sand, Blocks.sand, 5);
            case BADLANDS -> {
                set(c, Blocks.sand, Blocks.stained_hardened_clay, MAX_DEPTH);
                c.topMeta = 1; // red sand
                c.bands = true;
            }
            case KARST -> set(c, Blocks.grass, null, 0);
            case ALPINE_MEADOW, TUNDRA -> set(c, Blocks.grass, Blocks.dirt, 1);
            case TAIGA, SNOWY_TAIGA -> {
                set(c, Blocks.grass, Blocks.dirt, 3);
                if (r < 0.35) {
                    c.top = Blocks.dirt;
                    c.topMeta = 2; // podzol
                }
            }
            case SAVANNA -> {
                set(c, Blocks.grass, Blocks.dirt, 3);
                if (r < 0.25) {
                    c.top = Blocks.dirt;
                    c.topMeta = 1; // coarse dirt
                }
            }
            case SWAMP, MANGROVE -> set(c, Blocks.grass, Blocks.dirt, 2);
            case TROPICAL_RAINFOREST, TEMPERATE_RAINFOREST -> set(c, Blocks.grass, Blocks.dirt, 5);
            case COLD_STEPPE -> set(c, Blocks.grass, Blocks.dirt, 2);
            default -> set(c, Blocks.grass, Blocks.dirt, 3);
        }
        if (slope > 0.7 && c.top == Blocks.grass) c.depth = Math.min(c.depth, 1); // thin soil on steep ground
    }

    private static void set(Column c, Block top, Block under, int depth) {
        c.top = top;
        c.under = under;
        c.depth = depth;
    }

    /** Meta of the badlands clay band at absolute Y. */
    public static int bandMeta(int y, int x, int z) {
        int off = (int) (3 * Math.sin(x / 70.0) + 3 * Math.sin(z / 90.0));
        return BADLANDS_BANDS[Math.floorMod(y + off, BADLANDS_BANDS.length)];
    }
}
