package net.junedev.viridium.compat.nei;

import java.awt.Point;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import net.junedev.viridium.Viridium;
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

    static final int HEIGHT = 218;
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
            for (TreePreviewCatalog.Entry entry : TreePreviewCatalog.entries()) {
                arecipes.add(new PreviewRecipe(entry));
            }
        } else {
            super.loadCraftingRecipes(outputId, results);
        }
    }

    @Override
    public void loadCraftingRecipes(ItemStack result) {
        for (TreePreviewCatalog.Entry entry : TreePreviewCatalog.entries()) {
            if (entry.matches(result)) arecipes.add(new PreviewRecipe(entry));
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
        button(3, 136, 63, text("regenerate"));
        button(66, 136, 124, text(preview.rotating ? "pause" : "rotate"));
        button(127, 136, 143, "-");
        button(146, 136, 163, "+");
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
        Minecraft.getMinecraft().fontRenderer.drawString(text("blocks"), 3, 155, 0x263D2E);
        Minecraft.getMinecraft().fontRenderer.drawString(
            text(preview.failed ? "failed" : preview.entry.illustrative ? "illustrative" : "generated"),
            3,
            207,
            0x52634F);
    }

    @Override
    public boolean mouseClicked(GuiRecipe<?> gui, int button, int recipe) {
        Point mouse = gui.getRecipeMousePosition(recipe);
        if (button != 0 || mouse.y < 136 || mouse.y >= 150) return false;
        PreviewRecipe preview = (PreviewRecipe) arecipes.get(recipe);
        if (mouse.x >= 3 && mouse.x < 63) {
            preview.world = null;
            preview.failed = false;
        } else if (mouse.x >= 66 && mouse.x < 124) {
            preview.fixedAngle = preview.angle();
            preview.rotationStart = System.nanoTime();
            preview.rotating = !preview.rotating;
        } else if (mouse.x >= 127 && mouse.x < 143) {
            preview.zoom = Math.max(0.5F, preview.zoom - 0.15F);
        } else if (mouse.x >= 146 && mouse.x < 163) {
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
        PreviewRecipe preview = (PreviewRecipe) arecipes.get(recipe);
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

    private final class PreviewRecipe extends CachedRecipe {

        private final TreePreviewCatalog.Entry entry;
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
            for (int i = 0; i < entry.blocks.size(); i++) {
                stacks.add(
                    new PositionedStack(
                        entry.blocks.get(i)
                            .copy(),
                        3 + (i % 9) * 18,
                        168 + (i / 9) * 18));
            }
        }

        private float angle() {
            return fixedAngle + (rotating ? (float) ((System.nanoTime() - rotationStart) / 1.0e9 * 18 % 360) : 0);
        }

        private void ensureGenerated() {
            if (world != null || failed) return;
            counts.clear();
            try {
                Random random = new Random();
                world = new TreePreviewWorld(TreeRegistry.growsOnSand(entry.id));
                if (entry.illustrative) {
                    generateIllustration(random);
                } else if (!TreeRegistry.createGenerator(entry.id, entry.set)
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
