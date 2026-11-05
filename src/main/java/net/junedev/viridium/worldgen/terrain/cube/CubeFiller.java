package net.junedev.viridium.worldgen.terrain.cube;

import static net.junedev.viridium.worldgen.terrain.column.ColumnPlan.NONE;
import static net.junedev.viridium.worldgen.terrain.column.TerrainLayout.MIN_Y;

import javax.annotation.Nullable;

import net.junedev.viridium.worldgen.terrain.column.ColumnPlan;
import net.junedev.viridium.worldgen.terrain.crust.CrustModel;
import net.junedev.viridium.worldgen.terrain.crust.CrustPlan;
import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;

/**
 * Turns a {@link ColumnPlan} into the blocks of one cube. Classifies the cube first: pure air above everything (no
 * storage), crust only (deeper than the surface rules), or crossing the surface (rules, water, ice, snow).
 */
public class CubeFiller {

    private final boolean hasSky;
    private final CrustModel crust;
    private final Block[] lith;
    private final SurfaceRules rules;
    private final ThreadLocal<SurfaceRules.Column> surface = ThreadLocal.withInitial(SurfaceRules.Column::new);

    public CubeFiller(boolean hasSky, CrustModel crust, Block[] lithologyBlocks) {
        this.hasSky = hasSky;
        this.crust = crust;
        this.lith = lithologyBlocks;
        this.rules = new SurfaceRules(lithologyBlocks);
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
        if (y0 > plan.maxTopY) return Kind.AIR;
        if (y1 < plan.minSurfaceY - SurfaceRules.MAX_DEPTH) return Kind.CRUST;
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
        SurfaceRules.Column sc = surface.get();
        for (int lz = 0; lz < 16; lz++) {
            for (int lx = 0; lx < 16; lx++) {
                int i = ColumnPlan.index(lx, lz);
                if (kind == Kind.CRUST) {
                    for (int ly = 0; ly < 16; ly++)
                        ebs.func_150818_a(lx, ly, lz, lith[s.at(cp, i, lx, ly, lz).ordinal()]);
                } else {
                    fillColumn(ebs, plan, s, sc, i, (cubeX << 4) + lx, (cubeZ << 4) + lz, lx, lz, y0);
                }
            }
        }
        ebs.removeInvalidBlocks();
        return ebs;
    }

    private void fillColumn(ExtendedBlockStorage ebs, ColumnPlan plan, CrustModel.Sampler s, SurfaceRules.Column sc,
        int i, int x, int z, int lx, int lz, int y0) {
        int sy = plan.surfaceY[i];
        int water = plan.waterY[i], iceTop = plan.iceTop[i], iceBottom = plan.iceBottom[i];
        int top = Math.max(sy, Math.max(water, iceTop)) + 1;
        if (y0 > top) return;
        rules.resolve(plan, i, x, z, sc);
        boolean packed = plan.has(i, ColumnPlan.F_SHELF) || plan.has(i, ColumnPlan.F_BERG);
        for (int ly = 0; ly < 16; ly++) {
            int y = y0 + ly;
            Block b;
            int meta = 0;
            if (y <= sy) {
                int depth = sy - y;
                if (depth == 0 && sc.top != null) {
                    b = sc.top;
                    meta = sc.topMeta;
                } else if (depth <= sc.depth && sc.under != null) {
                    b = sc.under;
                    if (sc.bands) meta = SurfaceRules.bandMeta(y, x, z);
                    else meta = sc.underMeta;
                } else b = lith[s.at(plan.crust, i, lx, ly, lz).ordinal()];
            } else if (iceTop != NONE && y >= iceBottom && y <= iceTop) {
                b = packed ? Blocks.packed_ice : Blocks.ice;
            } else if (water != NONE && y <= water) {
                b = Blocks.water;
            } else if (y == top && sc.snow && iceTop == NONE && water == NONE && y == sy + 1) {
                b = Blocks.snow_layer;
            } else if (y == top && iceTop != NONE && plan.temperature[i] < -4 && y == iceTop + 1) {
                b = Blocks.snow_layer;
            } else continue;
            ebs.func_150818_a(lx, ly, lz, b);
            if (meta != 0) ebs.setExtBlockMetadata(lx, ly, lz, meta);
        }
    }
}
