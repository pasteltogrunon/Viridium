package net.junedev.viridium.compat.nei;

import java.awt.Point;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import net.junedev.viridium.Viridium;
import net.junedev.viridium.blocks.sets.WoodBlockSet;
import net.junedev.viridium.worldgen.treegen.TreeRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.StatCollector;

import codechicken.lib.gui.GuiDraw;
import codechicken.nei.PositionedStack;
import codechicken.nei.recipe.GuiRecipe;
import codechicken.nei.recipe.TemplateRecipeHandler;

public final class TreePreviewHandler extends TemplateRecipeHandler {

    static final int HEIGHT = 236;
    private static final String OVERLAY = "viridium.tree_preview";

    @Override
    public String getRecipeName() {
        return text("title");
    }

    @Override
    public String getGuiTexture() {
        return ""; // Background is drawn below; no crafting texture or transfer overlay.
    }

    @Override
    public String getOverlayIdentifier() {
        return OVERLAY;
    }

    @Override
    public int getRecipeHeight(int recipe) {
        return HEIGHT;
    }

    @Override
    public int recipiesPerPage() {
        return 1;
    }

    @Override
    public void loadCraftingRecipes(String outputId, Object... results) {
        if (OVERLAY.equals(outputId) || "all".equals(outputId)) {
            Set<WoodBlockSet> added = new HashSet<>();
            for (TreePreviewCatalog.Entry entry : TreePreviewCatalog.entries()) {
                if (added.add(entry.set)) arecipes.add(new PreviewRecipe(entry));
            }
        } else {
            super.loadCraftingRecipes(outputId, results);
        }
    }

    @Override
    public void loadCraftingRecipes(ItemStack result) {
        Set<WoodBlockSet> added = new HashSet<>();
        for (TreePreviewCatalog.Entry entry : TreePreviewCatalog.entries()) {
            if (entry.matches(result) && added.add(entry.set)) {
                PreviewRecipe preview = new PreviewRecipe(entry);
                for (int i = 0; i < preview.variants.size(); i++) {
                    TreePreviewCatalog.Entry variant = preview.variants.get(i);
                    if (result.getItem() == Item.getItemFromBlock(variant.sapling)
                        || result.getItem() == Item.getItemFromBlock(variant.leaves)) {
                        preview.selectVariant(i);
                        break;
                    }
                }
                arecipes.add(preview);
            }
        }
    }

    @Override
    public void loadUsageRecipes(ItemStack ingredient) {
        loadCraftingRecipes(ingredient);
    }

    @Override
    public void drawBackground(int recipe) {
        PreviewRecipe preview = (PreviewRecipe) arecipes.get(recipe);
        preview.ensureGenerated();
        Gui.drawRect(3, 14, 163, 132, 0xFF192923);
        button(3, 136, 21, "<");
        button(145, 136, 163, ">");
        button(3, 154, 63, text("regenerate"));
        button(66, 154, 124, text(preview.rotating ? "pause" : "rotate"));
        button(127, 154, 143, "-");
        button(146, 154, 163, "+");
        for (PositionedStack stack : preview.stacks) {
            Gui.drawRect(stack.relx - 1, stack.rely - 1, stack.relx + 17, stack.rely + 17, 0xFF8A978D);
            Gui.drawRect(stack.relx, stack.rely, stack.relx + 16, stack.rely + 16, 0xFFCBD3C6);
        }
        if (!preview.failed && Minecraft.getMinecraft().currentScreen instanceof GuiRecipe<?>gui) {
            Point mouse = GuiDraw.getMousePosition();
            Point relativeMouse = gui.getRecipeMousePosition(recipe);
            try {
                TreePreviewRenderer.draw(
                    preview.world,
                    preview.angle(),
                    preview.zoom,
                    mouse.x - relativeMouse.x + 3,
                    mouse.y - relativeMouse.y + 14);
            } catch (RuntimeException e) {
                preview.failed = true;
                Viridium.LOGGER.error("Could not render tree preview for " + preview.entry.id, e);
            }
        }
    }

    @Override
    public void drawForeground(int recipe) {
        PreviewRecipe preview = (PreviewRecipe) arecipes.get(recipe);
        String name = new ItemStack(preview.entry.sapling).getDisplayName();
        Minecraft.getMinecraft().fontRenderer
            .drawString(Minecraft.getMinecraft().fontRenderer.trimStringToWidth(name, 160), 3, 2, 0x263D2E);
        String variantLabel = (preview.variantIndex + 1) + "/"
            + preview.variants.size()
            + "  "
            + text(
                "layout." + preview.entry.layout.name()
                    .toLowerCase(java.util.Locale.ROOT));
        Minecraft.getMinecraft().fontRenderer.drawString(
            variantLabel,
            83 - Minecraft.getMinecraft().fontRenderer.getStringWidth(variantLabel) / 2,
            139,
            0x263D2E);
        Minecraft.getMinecraft().fontRenderer.drawString(text("blocks"), 3, 173, 0x263D2E);
        Minecraft.getMinecraft().fontRenderer.drawString(
            text(preview.failed ? "failed" : preview.entry.illustrative ? "illustrative" : "generated"),
            3,
            225,
            0x52634F);
    }

    @Override
    public boolean mouseClicked(GuiRecipe<?> gui, int button, int recipe) {
        Point mouse = gui.getRecipeMousePosition(recipe);
        return clickControl(mouse.x, mouse.y, button, recipe);
    }

    boolean clickControl(int x, int y, int button, int recipe) {
        if (button != 0) return false;
        PreviewRecipe preview = (PreviewRecipe) arecipes.get(recipe);
        if (y >= 136 && y < 150) {
            if (x >= 3 && x < 21) preview.selectVariant(preview.variantIndex - 1);
            else if (x >= 145 && x < 163) preview.selectVariant(preview.variantIndex + 1);
            else return false;
            return true;
        }
        if (y < 154 || y >= 168) return false;
        if (x >= 3 && x < 63) {
            preview.seed = new Random().nextLong();
            preview.invalidate();
        } else if (x >= 66 && x < 124) {
            preview.fixedAngle = preview.angle();
            preview.rotationStart = System.nanoTime();
            preview.rotating = !preview.rotating;
        } else if (x >= 127 && x < 143) {
            preview.zoom = Math.max(0.5F, preview.zoom - 0.15F);
        } else if (x >= 146 && x < 163) {
            preview.zoom = Math.min(2.5F, preview.zoom + 0.15F);
        } else {
            return false;
        }
        return true;
    }

    @Override
    public boolean mouseScrolled(GuiRecipe<?> gui, int scroll, int recipe) {
        Point mouse = gui.getRecipeMousePosition(recipe);
        if (mouse.x < 3 || mouse.x >= 163 || mouse.y < 14 || mouse.y >= 132) return false;
        PreviewRecipe preview = (PreviewRecipe) arecipes.get(recipe);
        preview.zoom = Math.max(0.5F, Math.min(2.5F, preview.zoom + Math.signum(scroll) * 0.1F));
        return true;
    }

    @Override
    public List<String> handleItemTooltip(GuiRecipe<?> gui, ItemStack stack, List<String> tooltip, int recipe) {
        // NEI also calls this over the recipe background, with no hovered item.
        if (stack == null) return tooltip;
        PreviewRecipe preview = (PreviewRecipe) arecipes.get(recipe);
        // Do not annotate unrelated items or report counts before generation/after a failure.
        if (!preview.entry.matches(stack) || preview.world == null || preview.failed) return tooltip;
        Integer count = preview.counts.get(stack.getItem());
        tooltip.add(
            count == null ? text("related")
                : StatCollector.translateToLocalFormatted("nei.viridium.tree.count", count));
        return tooltip;
    }

    private static String text(String key) {
        return StatCollector.translateToLocal("nei.viridium.tree." + key);
    }

    private static void button(int left, int top, int right, String label) {
        Gui.drawRect(left, top, right, top + 14, 0xFF647D68);
        Minecraft.getMinecraft().fontRenderer.drawString(
            label,
            (left + right - Minecraft.getMinecraft().fontRenderer.getStringWidth(label)) / 2,
            top + 3,
            0xFFFFFF);
    }

    final class PreviewRecipe extends CachedRecipe {

        private TreePreviewCatalog.Entry entry;
        final List<TreePreviewCatalog.Entry> variants = new ArrayList<>();
        private int variantIndex;
        private long seed = new Random().nextLong();
        private final List<PositionedStack> stacks = new ArrayList<>();
        private final Map<Item, Integer> counts = new HashMap<>();
        private TreePreviewWorld world;
        private boolean failed;
        private boolean rotating = true;
        private float zoom = 1;
        private float fixedAngle = 35;
        private long rotationStart = System.nanoTime();

        private PreviewRecipe(TreePreviewCatalog.Entry entry) {
            this.entry = entry;
            for (TreePreviewCatalog.Entry variant : TreePreviewCatalog.entries()) {
                if (variant.set == entry.set) variants.add(variant);
            }
            variantIndex = variants.indexOf(entry);
            for (int i = 0; i < entry.blocks.size(); i++) {
                stacks.add(
                    new PositionedStack(
                        entry.blocks.get(i)
                            .copy(),
                        3 + (i % 9) * 18,
                        186 + (i / 9) * 18));
            }
        }

        private float angle() {
            return fixedAngle + (rotating ? (float) ((System.nanoTime() - rotationStart) / 1.0e9 * 18 % 360) : 0);
        }

        void selectVariant(int index) {
            variantIndex = Math.floorMod(index, variants.size());
            entry = variants.get(variantIndex);
            invalidate();
        }

        private void invalidate() {
            world = null;
            failed = false;
            counts.clear();
        }

        private void ensureGenerated() {
            if (world != null || failed) return;
            counts.clear();
            try {
                Random random = new Random(seed);
                world = new TreePreviewWorld(entry.previewSoil());
                if (entry.illustrative) {
                    generateIllustration(random);
                } else if (!TreeRegistry.createGenerator(entry.id, entry.set, entry.layout, false)
                    .generate(world, random, 0, TreePreviewWorld.BASE_Y, 0)) {
                        throw new IllegalStateException("Generator rejected the preview site");
                    }
                if (world.cells()
                    .isEmpty()) throw new IllegalStateException("Generator produced no blocks");
                for (TreePreviewWorld.Cell cell : world.cells()) {
                    counts.merge(Item.getItemFromBlock(cell.block), 1, Integer::sum);
                }
            } catch (RuntimeException e) {
                failed = true;
                Viridium.LOGGER.error("Could not generate tree preview for " + entry.id, e);
            }
        }

        /** Explicitly labelled placeholder for block sets not yet supported by TreeRegistry's generators. */
        private void generateIllustration(Random random) {
            int height = 5 + random.nextInt(4);
            int radius = 2 + random.nextInt(2);
            int base = TreePreviewWorld.BASE_Y;
            for (int y = 0; y < height; y++) world.setBlock(0, base + y, 0, entry.log, 0, 0);
            for (int dy = -2; dy <= 2; dy++) {
                for (int x = -radius; x <= radius; x++) {
                    for (int z = -radius; z <= radius; z++) {
                        if (x * x + z * z + dy * dy > radius * radius + 1) continue;
                        int y = base + height - 1 + dy;
                        if (world.isAirBlock(x, y, z)) world.setBlock(x, y, z, entry.leaves, 0, 0);
                    }
                }
            }
        }

        @Override
        public PositionedStack getResult() {
            return null;
        }

        @Override
        public List<PositionedStack> getOtherStacks() {
            return stacks;
        }
    }
}
