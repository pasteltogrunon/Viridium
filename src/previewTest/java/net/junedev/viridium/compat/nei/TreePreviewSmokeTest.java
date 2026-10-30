package net.junedev.viridium.compat.nei;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.stream.IntStream;

import net.junedev.viridium.Viridium;
import net.junedev.viridium.blocks.SmallLogBlock;
import net.junedev.viridium.worldgen.treegen.TreeRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.renderer.entity.RenderItem;
import net.minecraft.init.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ScreenShotHelper;
import net.minecraft.util.StatCollector;
import net.minecraftforge.common.util.ForgeDirection;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

import codechicken.nei.NEIClientConfig;
import codechicken.nei.PositionedStack;
import codechicken.nei.recipe.GuiCraftingRecipe;
import codechicken.nei.recipe.GuiRecipeTab;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.event.FMLLoadCompleteEvent;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/** Run using: gradlew runClient -PtreePreviewSmokeTest. Requires a working OpenGL context. */
@Mod(modid = "viridium_preview_smoke", name = "Tree preview smoke test", version = "1", dependencies = "after:viridium")
public final class TreePreviewSmokeTest {

    private boolean started;
    private boolean finished;

    @Mod.EventHandler
    public void loaded(FMLLoadCompleteEvent event) {
        FMLCommonHandler.instance()
            .bus()
            .register(this);
    }

    @SubscribeEvent
    public void render(TickEvent.RenderTickEvent event) throws Exception {
        Minecraft mc = Minecraft.getMinecraft();
        if (finished || event.phase != TickEvent.Phase.END || !(mc.currentScreen instanceof GuiMainMenu)) return;
        if (!started) {
            started = true;
            NEIClientConfig.bootNEI();
            return;
        }
        if (!NEIClientConfig.isLoaded()) return;
        finished = true;
        require(
            !TreePreviewCatalog.entries()
                .isEmpty(),
            "NEI did not discover the Viridium plugin");
        require(
            GuiRecipeTab.handlerMap.containsKey(TreePreviewHandler.class.getName()),
            "NEI missed the handler layout registration at loadComplete");
        require(
            GuiCraftingRecipe.getCraftingHandlers(
                "item",
                new ItemStack(
                    TreePreviewCatalog.entries()
                        .get(0).log))
                .stream()
                .anyMatch(handler -> handler instanceof TreePreviewHandler),
            "NEI's R query missed the preview");
        TreePreviewWorld buffer = new TreePreviewWorld(false);
        require(buffer.getBlock(0, 63, 0) == Blocks.grass, "Wrong preview soil");
        buffer.setBlock(-1, 64, -1, Blocks.log, 8, 3);
        require(buffer.getBlockMetadata(-1, 64, -1) == 8, "Orientation metadata was lost");
        require(buffer.isAirBlock(1, 64, 1), "Buffer coordinates alias");
        buffer.setBlock(-1, 64, -1, Blocks.air, 0, 3);
        require(
            buffer.cells()
                .isEmpty(),
            "Air did not remove a block");
        try {
            buffer.setBlock(49, 64, 0, Blocks.log, 0, 0);
            throw new AssertionError("Out-of-bounds tree was accepted");
        } catch (IllegalStateException expected) {
            // The preview must not silently truncate an oversized generator.
        }

        int generated = 0;
        int rendered = 0;
        for (TreePreviewCatalog.Entry entry : TreePreviewCatalog.entries()) {
            for (int sample = 0; sample < 3; sample++) {
                TreePreviewHandler handler = new TreePreviewHandler();
                handler.loadCraftingRecipes(new ItemStack(entry.sapling));
                require(handler.numRecipes() == 1, "Tree variants must share one recipe: " + entry.id);
                int index = 0;
                TreePreviewHandler.PreviewRecipe selected = (TreePreviewHandler.PreviewRecipe) handler.arecipes
                    .get(index);
                selected.selectVariant(selected.variants.indexOf(entry));
                checkTooltip(handler, index, null, null);
                checkTooltip(handler, index, new ItemStack(entry.log), null);
                mc.getFramebuffer()
                    .bindFramebuffer(true);
                GL11.glClearColor(0.84F, 0.87F, 0.82F, 1);
                GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
                ScaledResolution resolution = new ScaledResolution(mc, mc.displayWidth, mc.displayHeight);
                GL11.glMatrixMode(GL11.GL_PROJECTION);
                GL11.glLoadIdentity();
                GL11.glOrtho(0, resolution.getScaledWidth(), resolution.getScaledHeight(), 0, 1000, 3000);
                GL11.glMatrixMode(GL11.GL_MODELVIEW);
                GL11.glLoadIdentity();
                GL11.glTranslatef(0, 0, -2000);
                handler.drawBackground(index);
                Object recipe = handler.arecipes.get(index);
                require(!(Boolean) read(recipe, "failed"), "Generation failed: " + entry.id);
                TreePreviewWorld world = (TreePreviewWorld) read(recipe, "world");
                Map<Item, Integer> counts = (Map<Item, Integer>) read(recipe, "counts");
                checkTooltip(handler, index, null, null);
                checkTooltip(handler, index, new ItemStack(Blocks.stone), null);
                for (ItemStack stack : entry.blocks) {
                    Integer count = counts.get(stack.getItem());
                    checkTooltip(
                        handler,
                        index,
                        stack,
                        count == null ? StatCollector.translateToLocal("nei.viridium.tree.related")
                            : StatCollector.translateToLocalFormatted("nei.viridium.tree.count", count));
                }
                require(
                    counts.values()
                        .stream()
                        .mapToInt(Integer::intValue)
                        .sum()
                        == world.cells()
                            .size(),
                    "Counts do not match the displayed tree");
                // Angelica catches custom-renderer failures and draws an error cube; exercise these
                // world queries directly so that such a failure cannot produce a false success.
                for (TreePreviewWorld.Cell cell : world.cells()) {
                    if (cell.block instanceof SmallLogBlock log) {
                        for (ForgeDirection side : ForgeDirection.VALID_DIRECTIONS) {
                            log.getConnectionType(world, cell.x, cell.y, cell.z, side, cell.meta);
                        }
                    }
                }
                generated++;
                if (sample == 0) {
                    while (GL11.glGetError() != GL11.GL_NO_ERROR) {}
                    // NEI enables scissoring around recipe widgets. Exercise that production path,
                    // not just a standalone rendering with scissoring disabled.
                    GL11.glEnable(GL11.GL_SCISSOR_TEST);
                    GL11.glScissor(0, 0, mc.displayWidth, mc.displayHeight);
                    TreePreviewRenderer.draw(world, 35, 1, 3, 14);
                    IntBuffer clip = BufferUtils.createIntBuffer(16);
                    GL11.glGetInteger(GL11.GL_SCISSOR_BOX, clip);
                    require(
                        GL11.glIsEnabled(GL11.GL_SCISSOR_TEST) && clip.get(0) == 0
                            && clip.get(1) == 0
                            && clip.get(2) == mc.displayWidth
                            && clip.get(3) == mc.displayHeight,
                        "Preview did not restore NEI's clipping rectangle");
                    GL11.glDisable(GL11.GL_SCISSOR_TEST);
                    require(GL11.glGetError() == GL11.GL_NO_ERROR, "OpenGL error: " + entry.id);
                    handler.drawForeground(index);
                    RenderHelper.enableGUIStandardItemLighting();
                    RenderItem itemRenderer = new RenderItem();
                    for (PositionedStack stack : handler.getOtherStacks(index)) {
                        itemRenderer.renderItemAndEffectIntoGUI(
                            mc.fontRenderer,
                            mc.getTextureManager(),
                            stack.item,
                            stack.relx,
                            stack.rely);
                    }
                    RenderHelper.disableStandardItemLighting();
                    rendered++;
                    if (entry.id.equals("viridium:american_chestnut") || entry.id.equals("viridium:coconut_palm")
                        || entry.id.equals("viridium:pedunculate_oak")
                        || entry.id.equals("viridium:black_spruce")) {
                        ScreenShotHelper.saveScreenshot(
                            new File(mc.mcDataDir, "../../build"),
                            "tree-preview-" + entry.id.replace(':', '-') + "-" + entry.layout.name() + ".png",
                            mc.displayWidth,
                            mc.displayHeight,
                            mc.getFramebuffer());
                    }
                }
                if (sample == 0) checkVariantControls(handler, selected, entry);
            }
            for (ItemStack block : entry.blocks) {
                TreePreviewHandler handler = new TreePreviewHandler();
                handler.loadCraftingRecipes(block);
                require(handler.numRecipes() > 0, "Family block lookup missed " + entry.id);
            }
        }
        TreePreviewHandler unrelated = new TreePreviewHandler();
        unrelated.loadCraftingRecipes(new ItemStack(Blocks.stone));
        require(unrelated.numRecipes() == 0, "Unrelated block matched tree preview");
        // Preview generation and integrated-server generation must not exchange random seeds.
        for (TreePreviewCatalog.Entry entry : TreePreviewCatalog.entries()) {
            if (entry.illustrative || entry.id.equals("viridium:coconut_palm")) continue;
            int[] expected = IntStream.range(0, 12)
                .map(seed -> fingerprint(entry, seed))
                .toArray();
            IntStream.range(0, 12)
                .parallel()
                .forEach(
                    seed -> require(
                        fingerprint(entry, seed) == expected[seed],
                        "Concurrent generation changed the shape of " + entry.id));
        }
        Viridium.LOGGER.info("TREE_PREVIEW_SMOKE_OK generated={} rendered={}", generated, rendered);
        mc.shutdown();
    }

    private static int fingerprint(TreePreviewCatalog.Entry entry, long seed) {
        TreePreviewWorld world = new TreePreviewWorld(entry.previewSoil());
        require(
            TreeRegistry.createGenerator(entry.id, entry.set, entry.layout, false)
                .generate(world, new Random(seed), 0, 64, 0),
            "Seeded generation failed");
        int result = 1;
        for (TreePreviewWorld.Cell cell : world.cells()) {
            result = 31 * result + java.util.Objects.hash(cell.x, cell.y, cell.z, cell.block, cell.meta);
        }
        return result;
    }

    private static void checkVariantControls(TreePreviewHandler handler, TreePreviewHandler.PreviewRecipe preview,
        TreePreviewCatalog.Entry entry) throws Exception {
        int original = preview.variants.indexOf(entry);
        long seed = (Long) read(preview, "seed");
        require(handler.clickControl(153, 142, 0, 0), "Next variant click was not consumed");
        require(
            read(preview, "entry") == preview.variants.get((original + 1) % preview.variants.size()),
            "Next variant selected the wrong layout/foliage");
        require(
            read(preview, "world") == null && ((Map<?, ?>) read(preview, "counts")).isEmpty(),
            "Variant change retained the previous tree/counts");
        checkTooltip(handler, 0, new ItemStack(entry.log), null);
        handler.drawBackground(0);
        require(!(Boolean) read(preview, "failed"), "Next variant failed to generate");
        require(handler.clickControl(10, 142, 0, 0), "Previous variant click was not consumed");
        require(read(preview, "entry") == entry, "Previous variant did not return to original");
        require((Long) read(preview, "seed") == seed, "Variant navigation unexpectedly changed the seed");
        handler.drawBackground(0);
        require(!(Boolean) read(preview, "failed"), "Previous variant failed to generate");
        require(!handler.clickControl(153, 142, 1, 0), "Right mouse button changed the variant");
        require(!handler.clickControl(83, 142, 0, 0), "Variant label behaved as a button");
        handler.clickControl(20, 160, 0, 0);
        require(read(preview, "world") == null, "Regenerate did not invalidate the tree");
        handler.drawBackground(0);
        require(!(Boolean) read(preview, "failed"), "Regeneration failed");
    }

    private static void checkTooltip(TreePreviewHandler handler, int recipe, ItemStack stack, String expected) {
        List<String> tooltip = new ArrayList<>();
        tooltip.add("Existing tooltip");
        try {
            require(handler.handleItemTooltip(null, stack, tooltip, recipe) == tooltip, "Tooltip list was replaced");
            require(
                tooltip.get(0)
                    .equals("Existing tooltip"),
                "Existing tooltip was changed");
            require(tooltip.size() == (expected == null ? 1 : 2), "Unexpected tree tooltip on empty/unrelated hover");
            if (expected != null) require(expected.equals(tooltip.get(1)), "Incorrect tree block count tooltip");
        } catch (RuntimeException | AssertionError e) {
            Viridium.LOGGER.error("TREE_PREVIEW_TOOLTIP_REGRESSION_FAILED", e);
            // CoreTweaks can recover from GUI exceptions; still terminate this diagnostic run on failure.
            Minecraft.getMinecraft()
                .shutdown();
            throw e;
        }
    }

    private static Object read(Object target, String name) throws Exception {
        Field field = target.getClass()
            .getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
