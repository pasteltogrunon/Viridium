package net.junedev.viridium.worldgen.terrain.cube;

import static net.junedev.viridium.worldgen.terrain.column.TerrainLayout.MIN_Y;
import static net.junedev.viridium.worldgen.terrain.column.TerrainLayout.SEA_LEVEL;

import javax.annotation.Nullable;

import net.junedev.viridium.worldgen.terrain.column.ColumnPlan;
import net.junedev.viridium.worldgen.terrain.crust.CrustModel;
import net.junedev.viridium.worldgen.terrain.crust.CrustPlan;
import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;

/**
 * Turns a {@link ColumnPlan} into the blocks of one cube. Classifies the cube first: pure air above the surface (no
 * storage), crust only (no surface rules), or crossing the surface. Rock comes from the 3D {@link CrustModel}.
 */
public class CubeFiller {

    /** Deepest block touched by the surface rules (top block + filler). */
    public static final int SURFACE_DEPTH = 4;

    private final boolean hasSky;
    private final CrustModel crust;
    private final Block[] lith;

    public CubeFiller(boolean hasSky, CrustModel crust, Block[] lithologyBlocks) {
        this.hasSky = hasSky;
        this.crust = crust;
        this.lith = lithologyBlocks;
    }

    /** Kind of work a cube needs. */
    public enum Kind {
        AIR,
        BEDROCK,
        CRUST,
        MIXED
    }

    public static Kind classify(ColumnPlan plan, int cubeY) {
        int y0 = cubeY << 4, y1 = y0 + 15;
        if (y1 < MIN_Y) return Kind.BEDROCK;
        if (y0 > Math.max(plan.maxSurfaceY, SEA_LEVEL - 1)) return Kind.AIR;
        if (y1 < plan.minSurfaceY - SURFACE_DEPTH) return Kind.CRUST;
        return Kind.MIXED;
    }

    /** Returns the block storage of the cube, or null for an all-air cube. */
    public @Nullable ExtendedBlockStorage fill(ColumnPlan plan, int cubeX, int cubeY, int cubeZ) {
        Kind kind = classify(plan, cubeY);
        if (kind == Kind.AIR) return null;
        int y0 = cubeY << 4;
        ExtendedBlockStorage ebs = new ExtendedBlockStorage(y0, hasSky);
        if (kind == Kind.BEDROCK) {
            for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++)
                ebs.func_150818_a(x, y, z, Blocks.bedrock);
            return ebs;
        }
        CrustModel.Sampler s = crust.sampler();
        s.prepare(cubeX << 4, y0, cubeZ << 4);
        CrustPlan cp = plan.crust;
        for (int lz = 0; lz < 16; lz++) {
            for (int lx = 0; lx < 16; lx++) {
                int i = ColumnPlan.index(lx, lz);
                if (kind == Kind.CRUST) {
                    for (int ly = 0; ly < 16; ly++)
                        ebs.func_150818_a(lx, ly, lz, lith[s.at(cp, i, lx, ly, lz).ordinal()]);
                } else {
                    fillColumn(ebs, plan, s, lx, lz, y0);
                }
            }
        }
        return ebs;
    }

    private void fillColumn(ExtendedBlockStorage ebs, ColumnPlan plan, CrustModel.Sampler s, int lx, int lz, int y0) {
        int i = ColumnPlan.index(lx, lz);
        int sy = plan.surfaceY(lx, lz);
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
                top = under = null; // bare rock
                underDepth = -1;
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
            if (y <= sy) {
                int depth = sy - y;
                if (depth == 0 && top != null) b = top;
                else if (depth <= underDepth) b = under;
                else b = lith[s.at(plan.crust, i, lx, ly, lz).ordinal()];
            } else if (y < SEA_LEVEL) {
                b = frozen && y == SEA_LEVEL - 1 ? Blocks.ice : Blocks.water;
            } else {
                continue;
            }
            ebs.func_150818_a(lx, ly, lz, b);
        }
    }
}
