package net.junedev.viridium.worldgen.terrain.cube;

import java.lang.reflect.Field;

import net.junedev.viridium.ViriBlocks;
import net.junedev.viridium.Viridium;
import net.junedev.viridium.worldgen.terrain.crust.Lithology;
import net.minecraft.block.Block;
import net.minecraft.init.Blocks;

/** Resolves each {@link Lithology} to its Viridium rock block (the {@code ViriBlocks} field of the same name). */
public final class LithologyBlocks {

    private static Block[] blocks;

    private LithologyBlocks() {}

    /** Indexed by {@code Lithology.ordinal()}. Call after block registration. */
    public static synchronized Block[] get() {
        if (blocks != null) return blocks;
        Block[] b = new Block[Lithology.VALUES.length];
        StringBuilder missing = new StringBuilder();
        for (Lithology l : Lithology.VALUES) {
            Block block = null;
            try {
                Field f = ViriBlocks.class.getField(l.block);
                block = (Block) f.get(null);
            } catch (ReflectiveOperationException | ClassCastException ignored) {}
            if (block == null) {
                block = Blocks.stone;
                missing.append(' ')
                    .append(l.block);
            }
            b[l.ordinal()] = block;
        }
        if (missing.length() > 0)
            Viridium.LOGGER.warn("Crust lithologies without a rock block (using stone):{}", missing);
        blocks = b;
        return b;
    }
}
