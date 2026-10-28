package net.junedev.viridium.compat.nei;

import java.nio.IntBuffer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.RenderBlocks;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.texture.TextureMap;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

/** Uses the world's block renderers, including Viridium's connected small logs. */
final class TreePreviewRenderer {

    private TreePreviewRenderer() {}

    static void draw(TreePreviewWorld world, float angle, float zoom, int screenX, int screenY) {
        if (world.cells()
            .isEmpty()) return;
        Minecraft mc = Minecraft.getMinecraft();
        ScaledResolution resolution = new ScaledResolution(mc, mc.displayWidth, mc.displayHeight);
        int factor = resolution.getScaleFactor();
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (TreePreviewWorld.Cell cell : world.cells()) {
            minX = Math.min(minX, cell.x);
            minY = Math.min(minY, cell.y);
            minZ = Math.min(minZ, cell.z);
            maxX = Math.max(maxX, cell.x + 1);
            maxY = Math.max(maxY, cell.y + 1);
            maxZ = Math.max(maxZ, cell.z + 1);
        }
        double horizontal = Math.hypot(maxX - minX, maxZ - minZ);
        double projectedHeight = (maxY - minY) * 0.91 + horizontal * 0.42;
        double scale = Math.min(142 / horizontal, 106 / projectedHeight) * zoom;

        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        GL11.glPushMatrix();
        try {
            int left = screenX * factor;
            int bottom = mc.displayHeight - (screenY + 118) * factor;
            int right = left + 160 * factor;
            int top = bottom + 118 * factor;
            // Respect NEI's own clip rectangle when its recipe widget is partially scrolled out of view.
            if (GL11.glIsEnabled(GL11.GL_SCISSOR_TEST)) {
                IntBuffer clip = BufferUtils.createIntBuffer(4);
                GL11.glGetInteger(GL11.GL_SCISSOR_BOX, clip);
                left = Math.max(left, clip.get(0));
                bottom = Math.max(bottom, clip.get(1));
                right = Math.min(right, clip.get(0) + clip.get(2));
                top = Math.min(top, clip.get(1) + clip.get(3));
            }
            GL11.glEnable(GL11.GL_SCISSOR_TEST);
            GL11.glScissor(left, bottom, Math.max(0, right - left), Math.max(0, top - bottom));
            GL11.glDepthMask(true);
            GL11.glClear(GL11.GL_DEPTH_BUFFER_BIT);
            GL11.glEnable(GL11.GL_DEPTH_TEST);
            GL11.glDepthFunc(GL11.GL_LEQUAL);
            GL11.glDisable(GL11.GL_LIGHTING);
            GL11.glDisable(GL11.GL_CULL_FACE);
            GL11.glEnable(GL11.GL_ALPHA_TEST);
            GL11.glAlphaFunc(GL11.GL_GREATER, 0.1F);
            GL11.glDisable(GL11.GL_BLEND);
            OpenGlHelper.setActiveTexture(OpenGlHelper.lightmapTexUnit);
            GL11.glDisable(GL11.GL_TEXTURE_2D);
            OpenGlHelper.setActiveTexture(OpenGlHelper.defaultTexUnit);
            GL11.glEnable(GL11.GL_TEXTURE_2D);
            mc.getTextureManager()
                .bindTexture(TextureMap.locationBlocksTexture);
            GL11.glColor4f(1, 1, 1, 1);
            GL11.glTranslated(83, 73, 100);
            GL11.glScaled(scale, -scale, scale);
            GL11.glRotatef(25, 1, 0, 0);
            GL11.glRotatef(angle, 0, 1, 0);
            GL11.glTranslated(-(minX + maxX) / 2.0, -(minY + maxY) / 2.0, -(minZ + maxZ) / 2.0);

            RenderBlocks renderer = new RenderBlocks(world);
            Tessellator tessellator = Tessellator.instance;
            tessellator.startDrawingQuads();
            try {
                for (TreePreviewWorld.Cell cell : world.cells()) {
                    renderer.renderBlockByRenderType(cell.block, cell.x, cell.y, cell.z);
                }
            } finally {
                tessellator.draw();
                tessellator.setTranslation(0, 0, 0);
            }
        } finally {
            GL11.glPopMatrix();
            GL11.glPopAttrib();
        }
    }
}
