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
    private net.junedev.viridium.worldgen.terrain.cube.flora.FloraPlanner flora;
    private net.junedev.viridium.worldgen.terrain.column.CliffDetail cliffs;
    private net.junedev.viridium.worldgen.terrain.caves.CaveCarver caves;

    public CubeFiller(boolean hasSky, CrustModel crust, Block[] lithologyBlocks) {
        this.hasSky = hasSky;
        this.crust = crust;
        this.lith = lithologyBlocks;
        this.rules = new SurfaceRules(lithologyBlocks);
    }

    public SurfaceRules rules() {
        return rules;
    }

    public boolean hasSky() {
        return hasSky;
    }

    /** Planned vegetation drawn into air and surface cubes (null = none). */
    public void setFlora(net.junedev.viridium.worldgen.terrain.cube.flora.FloraPlanner flora) {
        this.flora = flora;
    }

    /** Caves carved while filling (null = none). */
    public void setCaves(net.junedev.viridium.worldgen.terrain.caves.CaveCarver caves) {
        this.caves = caves;
    }

    private static Block caveBlock(byte c) {
        return c == net.junedev.viridium.worldgen.terrain.caves.CaveCarver.WATER ? Blocks.water
            : c == net.junedev.viridium.worldgen.terrain.caves.CaveCarver.LAVA ? Blocks.lava : Blocks.air;
    }

    /** Notches and talus of cliffs (null = none). */
    public void setCliffs(net.junedev.viridium.worldgen.terrain.column.CliffDetail cliffs) {
        this.cliffs = cliffs;
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
        int y0 = cubeY << 4;
        // a notch can lie below the surface rules of this column (its wall is tall), talus above its highest point
        net.junedev.viridium.worldgen.terrain.column.CliffDetail.Cols cc = null;
        if (cliffs != null && kind != Kind.BEDROCK) {
            cc = cliffs.get(cubeX, cubeZ);
            if (!cc.any || cc.maxY < y0 || cc.minY > y0 + 15) cc = null;
        }
        if (kind == Kind.AIR && cc == null) return flora == null ? null : flora.draw(plan, cubeX, cubeY, cubeZ, null);
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
        net.junedev.viridium.worldgen.terrain.caves.CaveCarver.Cube cv = caves == null || kind == Kind.AIR ? null
            : caves.prepare(plan, cubeX, cubeY, cubeZ);
        for (int lz = 0; lz < 16; lz++) {
            for (int lx = 0; lx < 16; lx++) {
                int i = ColumnPlan.index(lx, lz);
                if (kind == Kind.AIR) continue;
                if (kind == Kind.CRUST) {
                    s.column(cp, i, lx, lz); // per-column state (constant zones, strata memo) for the 16 blocks
                    for (int ly = 0; ly < 16; ly++) {
                        net.junedev.viridium.worldgen.terrain.crust.Lithology r = s.at(ly);
                        Block b = lith[r.ordinal()];
                        if (cv != null) {
                            byte c = cv.at(lx, ly, lz, r);
                            if (c != net.junedev.viridium.worldgen.terrain.caves.CaveCarver.SOLID) b = caveBlock(c);
                        }
                        ebs.func_150818_a(lx, ly, lz, b);
                    }
                } else {
                    fillColumn(ebs, plan, s, sc, i, (cubeX << 4) + lx, (cubeZ << 4) + lz, lx, lz, y0, cv);
                }
            }
        }
        if (cc != null) for (int i = 0; i < 256; i++) cliff(ebs, plan, cc, i, y0, cubeX, cubeZ);
        if ((kind == Kind.MIXED || kind == Kind.AIR) && flora != null) flora.draw(plan, cubeX, cubeY, cubeZ, ebs);
        ebs.removeInvalidBlocks();
        return ebs;
    }

    /**
     * Cuts the notch of column i (flooded below the water level of the cliff foot) and piles its talus: cobblestone with
     * gravel on top, mossy cobblestone where it rains.
     */
    private void cliff(ExtendedBlockStorage ebs, ColumnPlan plan, net.junedev.viridium.worldgen.terrain.column.CliffDetail.Cols cc,
        int i, int y0, int cubeX, int cubeZ) {
        int lx = i & 15, lz = i >> 4;
        int lo = cc.notchLo[i];
        if (lo != NONE) {
            int hi = cc.notchHi[i], wl = cc.notchWater[i];
            // never hang sand or gravel over the hole: keep at least one block of rock under loose soil
            SurfaceRules.Column sc = surface.get();
            rules.resolve(plan, i, (cubeX << 4) + lx, (cubeZ << 4) + lz, sc);
            if (sc.top instanceof net.minecraft.block.BlockFalling || sc.under instanceof net.minecraft.block.BlockFalling)
                hi = Math.min(hi, plan.surfaceY[i] - sc.depth - 2);
            for (int y = Math.max(lo, y0); y <= Math.min(hi, y0 + 15); y++)
                ebs.func_150818_a(lx, y - y0, lz, wl != NONE && y <= wl ? Blocks.water : Blocks.air);
        }
        int tt = cc.talusTop[i];
        if (tt != NONE) {
            boolean wet = plan.precipitation[i] > 900;
            for (int y = Math.max(plan.surfaceY[i] + 1, y0); y <= Math.min(tt, y0 + 15); y++) {
                Block b = y < tt ? Blocks.cobblestone : wet ? Blocks.mossy_cobblestone : Blocks.gravel;
                ebs.func_150818_a(lx, y - y0, lz, b);
                ebs.setExtBlockMetadata(lx, y - y0, lz, 0);
            }
        }
        int ct = cc.curtainTop[i];
        if (ct != NONE) { // falling water from a river above this column (waterfall lip, cascade)
            boolean frozen = plan.temperature[i] < -6;
            int from = Math.max(plan.surfaceY[i], plan.waterY[i]) + 1;
            for (int y = Math.max(from, y0); y <= Math.min(ct, y0 + 15); y++) {
                if (ebs.getBlockByExtId(lx, y - y0, lz) != Blocks.air) continue;
                ebs.func_150818_a(lx, y - y0, lz, frozen ? Blocks.ice : Blocks.flowing_water);
                ebs.setExtBlockMetadata(lx, y - y0, lz, frozen ? 0 : 8);
            }
        }
    }

    private void fillColumn(ExtendedBlockStorage ebs, ColumnPlan plan, CrustModel.Sampler s, SurfaceRules.Column sc,
        int i, int x, int z, int lx, int lz, int y0, net.junedev.viridium.worldgen.terrain.caves.CaveCarver.Cube cv) {
        int sy = plan.surfaceY[i];
        int water = plan.waterY[i], iceTop = plan.iceTop[i], iceBottom = plan.iceBottom[i];
        int fallTop = plan.fallTop[i];
        int top = Math.max(Math.max(sy, fallTop), Math.max(water, iceTop)) + 1;
        if (y0 > top) return;
        rules.resolve(plan, i, x, z, sc);
        boolean packed = plan.has(i, ColumnPlan.F_SHELF) || plan.has(i, ColumnPlan.F_BERG);
        // caves never hollow out loose soil (sand, gravel) or the blocks right under it: it would fall
        boolean loose = sc.top instanceof net.minecraft.block.BlockFalling || sc.under instanceof net.minecraft.block.BlockFalling;
        int caveTop = loose ? sy - sc.depth - 2 : sy;
        for (int ly = 0; ly < 16; ly++) {
            int y = y0 + ly;
            Block b;
            int meta = 0;
            if (y <= sy) {
                int depth = sy - y;
                // the rock is sampled once, and only where it shows or a cave needs it
                net.junedev.viridium.worldgen.terrain.crust.Lithology rock = null;
                if (depth == 0 && sc.top != null) {
                    b = sc.top;
                    meta = sc.topMeta;
                } else if (depth <= sc.depth && sc.under != null) {
                    b = sc.under;
                    if (sc.bands) meta = SurfaceRules.bandMeta(y, x, z);
                    else meta = sc.underMeta;
                } else {
                    rock = s.at(plan.crust, i, lx, ly, lz);
                    b = lith[rock.ordinal()];
                }
                if (cv != null && y <= caveTop) {
                    if (rock == null) rock = s.at(plan.crust, i, lx, ly, lz);
                    byte c = cv.at(lx, ly, lz, rock);
                    if (c != net.junedev.viridium.worldgen.terrain.caves.CaveCarver.SOLID) {
                        b = caveBlock(c);
                        meta = 0;
                    }
                }
            } else if (iceTop != NONE && y >= iceBottom && y <= iceTop) {
                b = packed ? Blocks.packed_ice : Blocks.ice;
            } else if (water != NONE && y <= water) {
                b = Blocks.water;
            } else if (fallTop != NONE && y <= fallTop && water != NONE && y > water) {
                // waterfall curtain: falling water (meta 8) from the pool up to the lip, frozen in cold climates
                boolean frozen = plan.has(i, ColumnPlan.F_FROZEN);
                b = frozen ? Blocks.ice : Blocks.flowing_water;
                meta = frozen ? 0 : 8;
            } else if (y == top && sc.snow && iceTop == NONE && water == NONE && y == sy + 1) {
                b = Blocks.snow_layer;
            } else continue; // no snow on ice: a snow layer cannot stand on ice and breaks in a cascade
            ebs.func_150818_a(lx, ly, lz, b);
            if (meta != 0) ebs.setExtBlockMetadata(lx, ly, lz, meta);
        }
    }
}
