package net.junedev.viridium.compat.dh;

import static net.junedev.viridium.worldgen.terrain.column.ColumnPlan.NONE;

import java.util.Arrays;

import net.junedev.viridium.worldgen.terrain.cube.flora.FloraSink;
import net.minecraft.block.Block;

/**
 * Flattens trees drawn over a square area into one vertical span per block column: the Y range covered by leaves, the
 * top of the logs, and which blocks they are. That is all a LOD column can show of a tree, and it keeps the exact
 * silhouette of every crown. Plants (grass, flowers, cacti) are ignored.
 */
final class CanopySink implements FloraSink {

    final int width;
    private int x0, z0;
    /** Per column (index z * width + x): inclusive leaf range and exclusive log top; NONE when absent. */
    final int[] leafMin, leafMax, logTop;
    final Block[] leafBlock, logBlock;
    final int[] leafMeta, logMeta;

    CanopySink(int width) {
        this.width = width;
        int n = width * width;
        leafMin = new int[n];
        leafMax = new int[n];
        logTop = new int[n];
        leafBlock = new Block[n];
        logBlock = new Block[n];
        leafMeta = new int[n];
        logMeta = new int[n];
    }

    /** Clears the area and moves it to start at block (x0, z0). */
    void reset(int x0, int z0) {
        this.x0 = x0;
        this.z0 = z0;
        Arrays.fill(leafMin, NONE);
        Arrays.fill(leafMax, NONE);
        Arrays.fill(logTop, NONE);
        Arrays.fill(leafBlock, null);
        Arrays.fill(logBlock, null);
    }

    int index(int x, int z) {
        return (z - z0) * width + (x - x0);
    }

    boolean hasTree(int i) {
        return leafMin[i] != NONE || logTop[i] != NONE;
    }

    @Override
    public void log(int x, int y, int z, Block b, int meta) {
        int lx = x - x0, lz = z - z0;
        if (lx < 0 || lz < 0 || lx >= width || lz >= width) return;
        int i = lz * width + lx;
        if (logTop[i] == NONE || y + 1 > logTop[i]) {
            logTop[i] = y + 1;
            logBlock[i] = b;
            logMeta[i] = meta;
        }
    }

    @Override
    public void leaves(int x, int y, int z, Block b, int meta) {
        int lx = x - x0, lz = z - z0;
        if (lx < 0 || lz < 0 || lx >= width || lz >= width) return;
        int i = lz * width + lx;
        if (leafMin[i] == NONE) {
            leafMin[i] = leafMax[i] = y;
        } else {
            leafMin[i] = Math.min(leafMin[i], y);
            if (y < leafMax[i]) return; // the topmost leaf decides the colour
            leafMax[i] = y;
        }
        leafBlock[i] = b;
        leafMeta[i] = meta;
    }

    @Override
    public void plant(int x, int y, int z, Block b, int meta) {}

    /** Boulders are too small for a LOD column. */
    @Override
    public void rock(int x, int y, int z, Block b, int meta) {}
}
