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
import net.minecraft.client.gui.GuiButton;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.StatCollector;

import codechicken.lib.gui.GuiDraw;
import codechicken.nei.PositionedStack;
import codechicken.nei.recipe.GuiRecipe;
import codechicken.nei.recipe.TemplateRecipeHandler;

public final class TreePreviewHandler extends TemplateRecipeHandler {

    static final int HEIGHT = 224;
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
        // One recipe per variant lets NEI's own page arrows handle navigation.
        // Put the queried foliage first, while retaining every variant of its wood family.
        for (TreePreviewCatalog.Entry entry : TreePreviewCatalog.entries()) {
            if (entry.matches(result)) {
                arecipes.add(new PreviewRecipe(entry));
            }
        }
        if (result != null) arecipes.sort(
            (left, right) -> Boolean.compare(
                isQueriedFoliage(((PreviewRecipe) right).entry, result),
                isQueriedFoliage(((PreviewRecipe) left).entry, result)));
    }

    private static boolean isQueriedFoliage(TreePreviewCatalog.Entry entry, ItemStack stack) {
        return stack.getItem() == Item.getItemFromBlock(entry.sapling)
            || stack.getItem() == Item.getItemFromBlock(entry.leaves);
    }

    @Override
    public void loadUsageRecipes(ItemStack ingredient) {
        loadCraftingRecipes(ingredient);
    }

    @Override
    public void drawBackground(int recipe) {
        PreviewRecipe preview = (PreviewRecipe) arecipes.get(recipe);
        preview.ensureGenerated();
        // Leave the preview transparent to the Minecraft/NEI menu background.
        Point relativeMouse = Minecraft.getMinecraft().currentScreen instanceof GuiRecipe<?>gui
            ? gui.getRecipeMousePosition(recipe)
            : new Point(-1, -1);
        preview.buttons.get(1).displayString = text(preview.rotating ? "pause" : "rotate");
        for (GuiButton button : preview.buttons) {
            button.drawButton(Minecraft.getMinecraft(), relativeMouse.x, relativeMouse.y);
        }
        for (PositionedStack stack : preview.stacks) {
            Gui.drawRect(stack.relx - 1, stack.rely - 1, stack.relx + 17, stack.rely + 17, 0xFF373737);
            Gui.drawRect(stack.relx, stack.rely, stack.relx + 17, stack.rely + 17, 0xFFFFFFFF);
            Gui.drawRect(stack.relx, stack.rely, stack.relx + 16, stack.rely + 16, 0xFF8B8B8B);
        }
        if (!preview.failed && Minecraft.getMinecraft().currentScreen instanceof GuiRecipe<?>) {
            Point mouse = GuiDraw.getMousePosition();
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
        String variantLabel = text(
            "layout." + preview.entry.layout.name()
                .toLowerCase(java.util.Locale.ROOT));
        int labelWidth = Minecraft.getMinecraft().fontRenderer.getStringWidth(variantLabel);
        Minecraft.getMinecraft().fontRenderer.drawString(
            Minecraft.getMinecraft().fontRenderer.trimStringToWidth(name, 154 - labelWidth),
            3,
            2,
            0x404040);
        Minecraft.getMinecraft().fontRenderer.drawString(variantLabel, 163 - labelWidth, 2, 0x404040);
        Minecraft.getMinecraft().fontRenderer.drawString(text("blocks"), 3, 161, 0x404040);
        Minecraft.getMinecraft().fontRenderer.drawString(
            text(preview.failed ? "failed" : preview.entry.illustrative ? "illustrative" : "generated"),
            3,
            213,
            0x555555);
    }

    @Override
    public boolean mouseClicked(GuiRecipe<?> gui, int button, int recipe) {
        Point mouse = gui.getRecipeMousePosition(recipe);
        return clickControl(mouse.x, mouse.y, button, recipe);
    }

    boolean clickControl(int x, int y, int button, int recipe) {
        if (button != 0) return false;
        PreviewRecipe preview = (PreviewRecipe) arecipes.get(recipe);
        GuiButton pressed = null;
        for (GuiButton control : preview.buttons) {
            if (control.mousePressed(Minecraft.getMinecraft(), x, y)) {
                pressed = control;
                break;
            }
        }
        if (pressed == null) return false;
        pressed.func_146113_a(
            Minecraft.getMinecraft()
                .getSoundHandler());
        if (pressed.id == 0) {
            preview.seed = new Random().nextLong();
            preview.invalidate();
        } else if (pressed.id == 1) {
            preview.fixedAngle = preview.angle();
            preview.rotationStart = System.nanoTime();
            preview.rotating = !preview.rotating;
        } else if (pressed.id == 2) {
            preview.zoom = Math.max(0.5F, preview.zoom - 0.15F);
        } else if (pressed.id == 3) {
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

    final class PreviewRecipe extends CachedRecipe {

        private final TreePreviewCatalog.Entry entry;
        private final List<GuiButton> buttons = new ArrayList<>();
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
            buttons.add(new GuiButton(0, 3, 136, 60, 20, text("regenerate")));
            buttons.add(new GuiButton(1, 66, 136, 58, 20, text("pause")));
            buttons.add(new GuiButton(2, 127, 136, 16, 20, "-"));
            buttons.add(new GuiButton(3, 145, 136, 18, 20, "+"));
            for (int i = 0; i < entry.blocks.size(); i++) {
                stacks.add(
                    new PositionedStack(
                        entry.blocks.get(i)
                            .copy(),
                        3 + (i % 9) * 18,
                        174 + (i / 9) * 18));
            }
        }

        private float angle() {
            return fixedAngle + (rotating ? (float) ((System.nanoTime() - rotationStart) / 1.0e9 * 18 % 360) : 0);
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
