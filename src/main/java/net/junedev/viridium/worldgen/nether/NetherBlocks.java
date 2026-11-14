package net.junedev.viridium.worldgen.nether;

import java.lang.reflect.Field;

import net.junedev.viridium.ViriBlocks;
import net.junedev.viridium.Viridium;
import net.minecraft.block.Block;
import net.minecraft.init.Blocks;

/**
 * Resolves each {@link NetherMat} to a block and metadata: vanilla Nether blocks where they exist, Viridium rocks for
 * the substrate (by {@code ViriBlocks} field name, with a vanilla fallback when a rock is missing).
 */
public final class NetherBlocks {

    private static Block[] blocks;
    private static byte[] metas;

    private NetherBlocks() {}

    /** Indexed by {@code NetherMat.ordinal()}. Call after block registration. */
    public static synchronized Block[] blocks() {
        if (blocks == null) resolve();
        return blocks;
    }

    public static synchronized byte[] metas() {
        if (metas == null) resolve();
        return metas;
    }

    private static void resolve() {
        Block[] b = new Block[NetherMat.VALUES.length];
        byte[] m = new byte[b.length];
        StringBuilder missing = new StringBuilder();
        for (NetherMat mat : NetherMat.VALUES) {
            Block block = switch (mat) {
                case AIR -> Blocks.air;
                case BEDROCK -> Blocks.bedrock;
                case LAVA -> Blocks.lava;
                case NETHERRACK -> Blocks.netherrack;
                case BASALT -> rock("basalt", Blocks.netherrack, missing);
                case SCORIA -> rock("scoria", Blocks.netherrack, missing);
                case OBSIDIAN -> rock("obsidian", Blocks.obsidian, missing);
                case PUMICE -> rock("pumice", Blocks.netherrack, missing);
                case TUFF -> rock("tuff", Blocks.netherrack, missing);
                case JASPER -> rock("jasper", Blocks.netherrack, missing);
                case CHALCEDONY -> rock("chalcedony", Blocks.quartz_block, missing);
                case PERIDOTITE -> rock("peridotite", Blocks.netherrack, missing);
                case KOMATIITE -> rock("komatiite", Blocks.netherrack, missing);
                case SOUL_SAND -> Blocks.soul_sand;
                case ASH -> Blocks.gravel;
                case QUARTZ_ORE -> Blocks.quartz_ore;
                case BONE -> Blocks.quartz_block;
                case GLOWSTONE -> Blocks.glowstone;
                case PATH -> rock("breccia", Blocks.cobblestone, missing);
                case BROWN_MUSHROOM -> Blocks.brown_mushroom;
                case RED_MUSHROOM -> Blocks.red_mushroom;
                case NETHER_WART -> Blocks.nether_wart;
                case FIRE -> Blocks.fire;
            };
            b[mat.ordinal()] = block;
            if (mat == NetherMat.NETHER_WART) m[mat.ordinal()] = 3; // fully grown
        }
        if (missing.length() > 0) Viridium.LOGGER.warn("Nether materials without a Viridium rock:{}", missing);
        blocks = b;
        metas = m;
    }

    private static Block rock(String field, Block fallback, StringBuilder missing) {
        try {
            Field f = ViriBlocks.class.getField(field);
            Object o = f.get(null);
            if (o instanceof Block block) return block;
        } catch (ReflectiveOperationException ignored) {}
        missing.append(' ')
            .append(field);
        return fallback;
    }
}
