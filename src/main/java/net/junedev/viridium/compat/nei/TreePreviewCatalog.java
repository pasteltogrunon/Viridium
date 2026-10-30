package net.junedev.viridium.compat.nei;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import net.junedev.viridium.ViriBlocks;
import net.junedev.viridium.blocks.sets.SmallTreeBlockSet;
import net.junedev.viridium.blocks.sets.WoodBlockSet;
import net.junedev.viridium.blocks.sets.large_tree.LargeTreeBlockSet;
import net.junedev.viridium.blocks.sets.large_tree.WhitePoplarBlockSet;
import net.junedev.viridium.blocks.vanilike.BaseSaplingBlock;
import net.junedev.viridium.worldgen.SoilType;
import net.junedev.viridium.worldgen.treegen.SaplingLayout;
import net.junedev.viridium.worldgen.treegen.TreeRegistry;
import net.minecraft.block.Block;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;

final class TreePreviewCatalog {

    private static List<Entry> entries = Collections.emptyList();

    private TreePreviewCatalog() {}

    static void initialize() {
        List<Entry> result = new ArrayList<>();
        // ViriBlocks is the authoritative list. Include inherited and species-specific block fields,
        // without maintaining a second list of hundreds of block registrations for the prototype.
        try {
            for (Field field : ViriBlocks.class.getFields()) {
                if (!Modifier.isStatic(field.getModifiers()) || !WoodBlockSet.class.isAssignableFrom(field.getType())) {
                    continue;
                }
                WoodBlockSet set = (WoodBlockSet) field.get(null);
                if (set instanceof LargeTreeBlockSet tree) {
                    addVariants(result, set, tree.log, tree.leaves, tree.sapling, false);
                } else if (set instanceof SmallTreeBlockSet tree) {
                    addVariants(result, set, tree.log, tree.leaves, tree.sapling, true);
                } else if (set instanceof WhitePoplarBlockSet tree) {
                    addVariants(result, set, tree.log, tree.leaves_yellow, tree.sapling_yellow, true);
                    addVariants(result, set, tree.log, tree.leaves_orange, tree.sapling_orange, true);
                    addVariants(result, set, tree.log, tree.leaves_red, tree.sapling_red, true);
                }
            }
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Cannot read Viridium tree block sets", e);
        }
        result.sort(Comparator.comparing(entry -> entry.id));
        entries = Collections.unmodifiableList(result);
    }

    static List<Entry> entries() {
        return entries;
    }

    private static void addVariants(List<Entry> result, WoodBlockSet set, Block log, Block leaves, Block sapling,
        boolean illustrative) throws IllegalAccessException {
        String id = ((BaseSaplingBlock) sapling).getTreeId();
        result.add(new Entry(id, set, log, leaves, sapling, illustrative, SaplingLayout.SINGLE));
        if (!illustrative) {
            for (SaplingLayout layout : SaplingLayout.values()) {
                if (layout != SaplingLayout.SINGLE && TreeRegistry.hasLayout(id, layout)) {
                    result.add(new Entry(id, set, log, leaves, sapling, false, layout));
                }
            }
        }
    }

    static final class Entry {

        final String id;
        final WoodBlockSet set;
        final Block log;
        final Block leaves;
        final Block sapling;
        final boolean illustrative;
        final SaplingLayout layout;
        final List<ItemStack> blocks;

        Entry(String id, WoodBlockSet set, Block log, Block leaves, Block sapling, boolean illustrative,
            SaplingLayout layout) throws IllegalAccessException {
            this.id = id;
            this.set = set;
            this.log = log;
            this.leaves = leaves;
            this.sapling = sapling;
            this.illustrative = illustrative;
            this.layout = layout;
            List<ItemStack> stacks = new ArrayList<>();
            stacks.add(new ItemStack(sapling));
            stacks.add(new ItemStack(log));
            stacks.add(new ItemStack(leaves));
            List<Field> fields = new ArrayList<>();
            Collections.addAll(
                fields,
                set.getClass()
                    .getFields());
            fields.sort(Comparator.comparing(Field::getName));
            for (Field field : fields) {
                if (!Block.class.isAssignableFrom(field.getType())) continue;
                Block block = (Block) field.get(set);
                Item item = Item.getItemFromBlock(block);
                if (item != null && stacks.stream()
                    .noneMatch(stack -> stack.getItem() == item)) {
                    stacks.add(new ItemStack(item));
                }
            }
            blocks = Collections.unmodifiableList(stacks);
        }

        boolean matches(ItemStack stack) {
            return stack != null && blocks.stream()
                .anyMatch(block -> block.getItem() == stack.getItem());
        }

        Block previewSoil() {
            for (SoilType type : SoilType.values()) {
                for (Block soil : type.getBlocks()) {
                    if (TreeRegistry.isValidSoil(id, soil)) return soil;
                }
            }
            throw new IllegalStateException("No preview soil registered for " + id);
        }
    }
}
