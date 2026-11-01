package net.junedev.viridium.client.color;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.junedev.viridium.Viridium;
import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.init.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.IIcon;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;

/** Reads the stitched atlas once, including generated sprites and cropped IIcons. Client thread only. */
final class BlockColorCatalog {

    static final class Sample {

        final String id, name;
        final IIcon[] faces;
        final int tint;
        final ColorMetrics color;

        Sample(String id, String name, IIcon[] faces, int tint, ColorMetrics color) {
            this.id = id;
            this.name = name;
            this.faces = faces;
            this.tint = tint;
            this.color = color;
        }
    }

    private final List<Block> blocks = new ArrayList<>();
    final List<Sample> samples = new ArrayList<>();
    private final Map<String, ColorMetrics.Accumulator> faceCache = new HashMap<>();
    private ByteBuffer pixels;
    private int width, height, cursor;
    int skipped;

    BlockColorCatalog() {
        for (Object object : Block.blockRegistry) {
            if (object instanceof Block && object != Blocks.air) blocks.add((Block) object);
        }
        Collections.sort(blocks, Comparator.comparing(block -> Block.blockRegistry.getNameForObject(block)));
    }

    void captureAtlas() {
        int activeTexture = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        GL11.glPushAttrib(GL11.GL_TEXTURE_BIT);
        GL11.glPushClientAttrib(GL11.GL_CLIENT_PIXEL_STORE_BIT);
        try {
            OpenGlHelper.setActiveTexture(OpenGlHelper.defaultTexUnit);
            Minecraft.getMinecraft()
                .getTextureManager()
                .bindTexture(TextureMap.locationBlocksTexture);
            width = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH);
            height = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_HEIGHT);
            long bytes = (long) width * height * 4;
            if (bytes <= 0 || bytes > 256L * 1024 * 1024) {
                throw new IllegalStateException(
                    "Atlas exceeds the 256 MiB analysis limit; use a smaller resource pack.");
            }
            pixels = BufferUtils.createByteBuffer((int) bytes);
            GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1);
            GL11.glPixelStorei(GL11.GL_PACK_ROW_LENGTH, 0);
            GL11.glPixelStorei(GL11.GL_PACK_SKIP_ROWS, 0);
            GL11.glPixelStorei(GL11.GL_PACK_SKIP_PIXELS, 0);
            GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels);
        } finally {
            GL11.glPopClientAttrib();
            GL11.glPopAttrib();
            OpenGlHelper.setActiveTexture(activeTexture);
        }
    }

    boolean step() {
        long deadline = System.nanoTime() + 6_000_000;
        do {
            if (cursor == blocks.size()) {
                pixels = null;
                faceCache.clear();
                return true;
            }
            scan(blocks.get(cursor++));
        } while (System.nanoTime() < deadline);
        return false;
    }

    String progress() {
        return cursor + "/" + blocks.size() + " blocks; " + samples.size() + " samples; " + skipped + " skipped";
    }

    private void scan(Block block) {
        String id = Block.blockRegistry.getNameForObject(block);
        Item item = Item.getItemFromBlock(block);
        List<ItemStack> variants = new ArrayList<>();
        try {
            if (item != null) block.getSubBlocks(item, block.getCreativeTabToDisplayOn(), variants);
        } catch (RuntimeException exception) {
            Viridium.LOGGER.debug("Cannot enumerate color variants of " + id, exception);
        }
        // Technical blocks without a creative item still get a metadata-zero sample.
        if (variants.isEmpty()) variants.add(item == null ? null : new ItemStack(item));
        Set<Integer> metadata = new HashSet<>();
        for (ItemStack stack : variants) {
            try {
                int meta = stack == null ? 0
                    : stack.getItem()
                        .getMetadata(stack.getItemDamage());
                if (meta < 0 || meta > 15 || !metadata.add(meta)) continue;
                IIcon[] faces = new IIcon[6];
                int tint = block.getRenderColor(meta);
                ColorMetrics.Accumulator total = new ColorMetrics.Accumulator();
                for (int side = 0; side < 6; side++) {
                    IIcon icon = block.getIcon(side, meta);
                    if (icon == null || "missingno".equals(icon.getIconName())) continue;
                    faces[side] = icon;
                    total.merge(face(icon, tint));
                }
                ColorMetrics color = total.finish();
                if (color == null) {
                    skipped++;
                    continue;
                }
                String name = stack == null ? id : stack.getDisplayName();
                samples.add(new Sample(id + ":" + meta, name, faces, tint, color));
            } catch (RuntimeException exception) {
                skipped++;
                Viridium.LOGGER.debug("Cannot analyze block color: " + id, exception);
            }
        }
    }

    private ColorMetrics.Accumulator face(IIcon icon, int tint) {
        int x0 = Math.max(0, (int) Math.floor(icon.getMinU() * width));
        int x1 = Math.min(width, (int) Math.ceil(icon.getMaxU() * width));
        int y0 = Math.max(0, (int) Math.floor(icon.getMinV() * height));
        int y1 = Math.min(height, (int) Math.ceil(icon.getMaxV() * height));
        String key = x0 + ":" + x1 + ":" + y0 + ":" + y1 + ":" + tint;
        ColorMetrics.Accumulator result = faceCache.get(key);
        if (result != null) return result;
        result = new ColorMetrics.Accumulator();
        // Equal face area, independent of texture resolution. Transparent pixels contribute no color.
        double area = 1.0 / Math.max(1, (x1 - x0) * (y1 - y0));
        for (int y = y0; y < y1; y++) {
            for (int x = x0; x < x1; x++) {
                int offset = (y * width + x) * 4;
                int argb = (pixels.get(offset + 3) & 255) << 24 | (pixels.get(offset) & 255) << 16
                    | (pixels.get(offset + 1) & 255) << 8
                    | (pixels.get(offset + 2) & 255);
                result.add(argb, tint, area);
            }
        }
        faceCache.put(key, result);
        return result;
    }
}
