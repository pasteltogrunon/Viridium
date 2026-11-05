package net.junedev.viridium.worldgen.terrain.cube.flora;

import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.init.Blocks;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;

/**
 * Writes feature blocks (absolute coordinates) into one cube's storage, clipping everything outside it. Logs replace
 * air, leaves, snow and plants; leaves and plants only fill air (and snow layers). The storage is allocated lazily, so
 * an otherwise empty air cube stays storage-free unless a tree reaches into it.
 */
public final class BlockSink {

    private int x0, y0, z0;
    private boolean hasSky;
    private ExtendedBlockStorage ebs;
    private int written;

    /** Prepares to write into cube (cx, cy, cz); {@code ebs} may be null (air cube). */
    public void begin(int cx, int cy, int cz, ExtendedBlockStorage ebs, boolean hasSky) {
        this.x0 = cx << 4;
        this.y0 = cy << 4;
        this.z0 = cz << 4;
        this.ebs = ebs;
        this.hasSky = hasSky;
        this.written = 0;
    }

    /** The storage after writing (possibly newly allocated), or null if still empty. */
    public ExtendedBlockStorage end() {
        return ebs;
    }

    public int written() {
        return written;
    }

    public void log(int x, int y, int z, Block b, int meta) {
        put(x, y, z, b, meta, true);
    }

    public void leaves(int x, int y, int z, Block b, int meta) {
        put(x, y, z, b, meta, false);
    }

    public void plant(int x, int y, int z, Block b, int meta) {
        put(x, y, z, b, meta, false);
    }

    private void put(int x, int y, int z, Block b, int meta, boolean strong) {
        int lx = x - x0, ly = y - y0, lz = z - z0;
        if ((lx | ly | lz) < 0 || lx > 15 || ly > 15 || lz > 15) return; // another cube draws this part
        if (ebs == null) ebs = new ExtendedBlockStorage(y0, hasSky);
        Block cur = ebs.getBlockByExtId(lx, ly, lz);
        if (!replaceable(cur, strong)) return;
        ebs.func_150818_a(lx, ly, lz, b);
        ebs.setExtBlockMetadata(lx, ly, lz, meta);
        written++;
    }

    private static boolean replaceable(Block cur, boolean strong) {
        if (cur == Blocks.air || cur == Blocks.snow_layer) return true;
        if (!strong) return false;
        Material m = cur.getMaterial();
        return m == Material.leaves || m == Material.plants || m == Material.vine;
    }
}
