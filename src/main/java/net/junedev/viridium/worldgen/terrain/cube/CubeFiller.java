package net.junedev.viridium.worldgen.terrain.cube;

import static net.junedev.viridium.worldgen.terrain.column.TerrainLayout.MIN_Y;
import static net.junedev.viridium.worldgen.terrain.column.TerrainLayout.SEA_LEVEL;

import javax.annotation.Nullable;

import net.junedev.viridium.worldgen.terrain.column.ColumnPlan;
import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;

/**
 * Turns a {@link ColumnPlan} into the blocks of one cube. Classifies the cube first so the common cases do no
 * per-column work: pure air above the surface, uniform rock deep below it, detailed only where it crosses the surface.
 *
 * <p>
 * Phase 0 materials (stone + simple soils). The 3D crust (phase 4) replaces {@link #rockAt}.
 */
public class CubeFiller {

    /** Deepest block touched by the surface rules (top block + filler). */
    public static final int SURFACE_DEPTH = 4;

    private final boolean hasSky;

    public CubeFiller(boolean hasSky) {
        this.hasSky = hasSky;
    }

    /** Kind of work a cube needs. */
    public enum Kind {
        AIR,
        BEDROCK,
        SOLID,
        MIXED
    }

    public static Kind classify(ColumnPlan plan, int cubeY) {
        int y0 = cubeY << 4, y1 = y0 + 15;
        if (y1 < MIN_Y) return Kind.BEDROCK;
        if (y0 > Math.max(plan.maxSurfaceY, SEA_LEVEL - 1)) return Kind.AIR;
        if (y1 < plan.minSurfaceY - SURFACE_DEPTH) return Kind.SOLID;
        return Kind.MIXED;
    }

    /** Returns the block storage of the cube, or null for an all-air cube. */
    public @Nullable ExtendedBlockStorage fill(ColumnPlan plan, int cubeY) {
        Kind kind = classify(plan, cubeY);
        if (kind == Kind.AIR) return null;
        int y0 = cubeY << 4;
        ExtendedBlockStorage ebs = new ExtendedBlockStorage(y0, hasSky);
        if (kind == Kind.BEDROCK) {
            fillUniform(ebs, Blocks.bedrock);
            return ebs;
        }
        if (kind == Kind.SOLID) {
            fillUniform(ebs, rockAt(plan, y0));
            return ebs;
        }
        for (int lz = 0; lz < 16; lz++) {
            for (int lx = 0; lx < 16; lx++) {
                fillColumn(ebs, plan, lx, lz, y0);
            }
        }
        return ebs;
    }

    private void fillColumn(ExtendedBlockStorage ebs, ColumnPlan plan, int lx, int lz, int y0) {
        int s = plan.surfaceY(lx, lz);
        float h = plan.height(lx, lz);
        int biome = plan.biomeId(lx, lz);
        boolean frozen = plan.temperature(lx, lz) < -2;
        Block top, under;
        int underDepth;
        if (h >= 2) {
            if (biome == 2) { // desert
                top = under = Blocks.sand;
                underDepth = 3;
            } else if (h > 260) {
                top = under = Blocks.stone;
                underDepth = 0;
            } else {
                top = Blocks.grass;
                under = Blocks.dirt;
                underDepth = 3;
            }
        } else if (h >= -20) {
            top = under = Blocks.sand;
            underDepth = 3;
        } else {
            top = under = Blocks.gravel;
            underDepth = 2;
        }
        for (int ly = 0; ly < 16; ly++) {
            int y = y0 + ly;
            Block b;
            if (y <= s) {
                int depth = s - y;
                if (depth == 0) b = top;
                else if (depth <= underDepth) b = under;
                else b = rockAt(plan, y);
            } else if (y < SEA_LEVEL) {
                b = frozen && y == SEA_LEVEL - 1 ? Blocks.ice : Blocks.water;
            } else {
                continue;
            }
            ebs.func_150818_a(lx, ly, lz, b);
        }
    }

    /** Rock at absolute Y. Placeholder until the 3D crust model. */
    protected Block rockAt(ColumnPlan plan, int y) {
        return Blocks.stone;
    }

    private static void fillUniform(ExtendedBlockStorage ebs, Block block) {
        for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++)
            ebs.func_150818_a(x, y, z, block);
    }
}
