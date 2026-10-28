package net.junedev.viridium.compat.nei;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

import net.minecraft.block.Block;
import net.minecraft.entity.Entity;
import net.minecraft.init.Blocks;
import net.minecraft.profiler.Profiler;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;
import net.minecraft.world.WorldProviderSurface;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.WorldType;
import net.minecraft.world.biome.BiomeGenBase;
import net.minecraft.world.chunk.IChunkProvider;
import net.minecraft.world.storage.SaveHandlerMP;
import net.minecraftforge.common.util.ForgeDirection;

/** A bounded, in-memory block buffer. It never loads chunks, sends updates, or writes a save. */
final class TreePreviewWorld extends World {

    static final int BASE_Y = 64;
    private static final int RADIUS = 48;
    private static final int MAX_BLOCKS = 32768;
    private final Map<Long, Cell> cells = new LinkedHashMap<>();
    private final Block soil;

    TreePreviewWorld(boolean sand) {
        // Use the client constructor; deliberately do not call finishSetup (dimension/chunk initialization).
        super(
            new SaveHandlerMP(),
            "Viridium tree preview",
            new WorldProviderSurface(),
            new WorldSettings(0, WorldSettings.GameType.CREATIVE, false, false, WorldType.FLAT),
            new Profiler());
        isRemote = true;
        soil = sand ? Blocks.sand : Blocks.grass;
    }

    private static long key(int x, int y, int z) {
        return ((long) (x & 0xFFFF) << 32) | ((long) (z & 0xFFFF) << 16) | (y & 0xFFFFL);
    }

    Collection<Cell> cells() {
        return cells.values();
    }

    @Override
    public Block getBlock(int x, int y, int z) {
        Cell cell = cells.get(key(x, y, z));
        return cell == null ? (y == BASE_Y - 1 ? soil : Blocks.air) : cell.block;
    }

    @Override
    public int getBlockMetadata(int x, int y, int z) {
        Cell cell = cells.get(key(x, y, z));
        return cell == null ? 0 : cell.meta;
    }

    @Override
    public boolean setBlock(int x, int y, int z, Block block, int meta, int flags) {
        if (Math.abs(x) > RADIUS || Math.abs(z) > RADIUS || y < BASE_Y || y > BASE_Y + 96) {
            throw new IllegalStateException("Tree exceeded preview bounds");
        }
        long key = key(x, y, z);
        if (block == Blocks.air) {
            cells.remove(key);
        } else {
            if (!cells.containsKey(key) && cells.size() >= MAX_BLOCKS) {
                throw new IllegalStateException("Tree exceeded preview block budget");
            }
            cells.put(key, new Cell(x, y, z, block, meta));
        }
        return true;
    }

    @Override
    public boolean isAirBlock(int x, int y, int z) {
        return getBlock(x, y, z) == Blocks.air;
    }

    @Override
    public boolean checkChunksExist(int x1, int y1, int z1, int x2, int y2, int z2) {
        return true;
    }

    @Override
    public int getLightBrightnessForSkyBlocks(int x, int y, int z, int light) {
        return 0xF000F0;
    }

    @Override
    public float getLightBrightness(int x, int y, int z) {
        return 1.0F;
    }

    @Override
    public BiomeGenBase getBiomeGenForCoords(int x, int z) {
        return BiomeGenBase.plains;
    }

    @Override
    public TileEntity getTileEntity(int x, int y, int z) {
        return null;
    }

    @Override
    public boolean isSideSolid(int x, int y, int z, ForgeDirection side, boolean defaultValue) {
        return getBlock(x, y, z).isSideSolid(this, x, y, z, side);
    }

    @Override
    protected IChunkProvider createChunkProvider() {
        return null;
    }

    @Override
    protected int func_152379_p() {
        return 0;
    }

    @Override
    public Entity getEntityByID(int id) {
        return null;
    }

    static final class Cell {

        final int x;
        final int y;
        final int z;
        final Block block;
        final int meta;

        Cell(int x, int y, int z, Block block, int meta) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.block = block;
            this.meta = meta;
        }
    }
}
