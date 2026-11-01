package net.junedev.viridium.client.color;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

import net.junedev.viridium.Viridium;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.RenderGlobal;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.culling.Frustrum;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.IIcon;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;
import net.minecraft.world.World;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.client.event.TextureStitchEvent;
import net.minecraftforge.event.world.WorldEvent;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;

/** A client-only, full-bright cloud of six-face texture swatches, anchored in world coordinates. */
public final class ColorCloud {

    public static final ColorCloud INSTANCE = new ColorCloud();
    private static final int[] REFERENCE_COLORS = { 0x000000, 0x808080, 0xFFFFFF, 0xFF0000, 0x00FF00, 0x0000FF,
        0xFFFF00, 0x00FFFF, 0xFF00FF };
    private static final String[] REFERENCE_NAMES = { "Black", "Gray", "White", "Red", "Green", "Blue", "Yellow",
        "Cyan", "Magenta" };

    ColorAxis[] axes = { ColorAxis.A, ColorAxis.L, ColorAxis.LAB_B };
    double scale = 48, size = 0.6, spread;
    String filter = "";
    boolean references = true;
    private boolean visible, scanning, captured;
    private BlockColorCatalog catalog;
    private World world;
    private double originX, originY, originZ;
    private final List<Point> points = new ArrayList<>();
    private Point selected;
    private int selectionOffset, hits;

    private static final class Point {

        final BlockColorCatalog.Sample sample;
        final double x, y, z;

        Point(BlockColorCatalog.Sample sample, double x, double y, double z) {
            this.sample = sample;
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }

    private ColorCloud() {}

    void show() {
        Minecraft mc = Minecraft.getMinecraft();
        if (world != mc.theWorld) {
            world = mc.theWorld;
            here();
        }
        visible = true;
        if (catalog == null) rescan();
        else rebuild();
    }

    void hide() {
        visible = false;
        selected = null;
        if (scanning) {
            catalog = null;
            scanning = false;
            captured = false;
            points.clear();
        }
    }

    void rescan() {
        catalog = new BlockColorCatalog();
        scanning = true;
        captured = false;
        selected = null;
        points.clear();
    }

    void here() {
        EntityLivingBase player = Minecraft.getMinecraft().thePlayer;
        Vec3 look = player.getLookVec();
        double length = Math.hypot(look.xCoord, look.zCoord);
        double dx = length > 0.01 ? look.xCoord / length : 0;
        double dz = length > 0.01 ? look.zCoord / length : 1;
        originX = player.posX + dx * (scale * 0.7 + 8) - scale / 2;
        originY = player.boundingBox.minY + 2;
        originZ = player.posZ + dz * (scale * 0.7 + 8) - scale / 2;
        rebuild();
    }

    void rebuild() {
        points.clear();
        selected = null;
        selectionOffset = 0;
        if (catalog == null || scanning) return;
        for (BlockColorCatalog.Sample sample : catalog.samples) {
            if (!sample.id.toLowerCase(Locale.ROOT)
                .contains(filter)
                && !sample.name.toLowerCase(Locale.ROOT)
                    .contains(filter))
                continue;
            Random random = new Random(sample.id.hashCode());
            points.add(
                new Point(
                    sample,
                    originX + scale * axes[0].position(sample.color) + (random.nextDouble() - 0.5) * spread,
                    originY + scale * axes[1].position(sample.color) + (random.nextDouble() - 0.5) * spread,
                    originZ + scale * axes[2].position(sample.color) + (random.nextDouble() - 0.5) * spread));
        }
    }

    void next() {
        selectionOffset++;
    }

    String status() {
        if (catalog == null) return "No analysis. /blockcolors oklab";
        return (scanning ? "Analyzing: " : "Ready: ") + catalog
            .progress() + "; " + points.size() + " shown" + (visible ? "" : " (hidden)");
    }

    String info() {
        if (selected == null) return "Aim at a sample. /blockcolors next cycles overlapping samples.";
        ColorMetrics color = selected.sample.color;
        return selected.sample.name + " ["
            + selected.sample.id
            + "] "
            + String.format(
                Locale.ROOT,
                "RGB=(%.1f, %.1f, %.1f) OKLab=(%.4f, %.4f, %.4f) C=%.4f sigma(L)=%.4f",
                color.red * 255,
                color.green * 255,
                color.blue * 255,
                color.lightness,
                color.a,
                color.b,
                color.chroma(),
                color.contrast);
    }

    @SubscribeEvent
    public void unload(WorldEvent.Unload event) {
        if (event.world == world) {
            hide();
            world = null;
            catalog = null;
            points.clear();
        }
    }

    @SubscribeEvent
    public void textures(TextureStitchEvent.Post event) {
        if (event.map.getTextureType() == 0) {
            // UVs and texture statistics both become invalid when packs are reloaded.
            catalog = null;
            points.clear();
            selected = null;
            scanning = false;
            if (visible) rescan();
        }
    }

    @SubscribeEvent
    public void render(RenderWorldLastEvent event) {
        Minecraft mc = Minecraft.getMinecraft();
        if (!visible || mc.theWorld != world || mc.renderViewEntity == null) return;
        if (scanning) {
            try {
                if (!captured) {
                    catalog.captureAtlas();
                    captured = true;
                }
                if (catalog.step()) {
                    scanning = false;
                    rebuild();
                    mc.thePlayer.addChatMessage(new ChatComponentText(status()));
                }
            } catch (RuntimeException exception) {
                Viridium.LOGGER.error("Block color analysis failed", exception);
                hide();
                mc.thePlayer.addChatMessage(new ChatComponentText("Color analysis failed: " + exception.getMessage()));
            }
            return;
        }
        EntityLivingBase viewer = mc.renderViewEntity;
        double camX = viewer.lastTickPosX + (viewer.posX - viewer.lastTickPosX) * event.partialTicks;
        double camY = viewer.lastTickPosY + (viewer.posY - viewer.lastTickPosY) * event.partialTicks;
        double camZ = viewer.lastTickPosZ + (viewer.posZ - viewer.lastTickPosZ) * event.partialTicks;
        pick(viewer, event.partialTicks);
        Frustrum frustum = new Frustrum();
        frustum.setPosition(camX, camY, camZ);
        int activeTexture = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        GL11.glPushMatrix();
        try {
            GL11.glTranslated(-camX, -camY, -camZ);
            GL11.glDisable(GL11.GL_LIGHTING);
            GL11.glDisable(GL11.GL_FOG);
            GL11.glDisable(GL11.GL_CULL_FACE);
            GL11.glEnable(GL11.GL_DEPTH_TEST);
            GL11.glDepthMask(true);
            GL11.glEnable(GL11.GL_ALPHA_TEST);
            GL11.glAlphaFunc(GL11.GL_GREATER, 0.1F);
            GL11.glDisable(GL11.GL_BLEND);
            OpenGlHelper.setActiveTexture(OpenGlHelper.lightmapTexUnit);
            GL11.glDisable(GL11.GL_TEXTURE_2D);
            OpenGlHelper.setActiveTexture(OpenGlHelper.defaultTexUnit);
            GL11.glEnable(GL11.GL_TEXTURE_2D);
            mc.getTextureManager()
                .bindTexture(TextureMap.locationBlocksTexture);
            Tessellator tessellator = Tessellator.instance;
            tessellator.startDrawingQuads();
            for (Point point : points) {
                if (!frustum.isBoundingBoxInFrustum(bounds(point, size / 2))) continue;
                cube(tessellator, point.x, point.y, point.z, size, point.sample.faces, point.sample.tint);
            }
            tessellator.draw();
            GL11.glDisable(GL11.GL_TEXTURE_2D);
            GL11.glColor4f(1, 1, 1, 1);
            GL11.glLineWidth(1.5F);
            if (selected != null) RenderGlobal.drawOutlinedBoundingBox(bounds(selected, size / 2 + 0.03), 0xFFFFFF);
            if (references) drawReferences();
        } finally {
            GL11.glPopMatrix();
            GL11.glPopAttrib();
            OpenGlHelper.setActiveTexture(activeTexture);
        }
    }

    private void pick(EntityLivingBase viewer, float partialTicks) {
        Vec3 start = viewer.getPosition(partialTicks);
        Vec3 look = viewer.getLook(partialTicks);
        Vec3 end = start.addVector(look.xCoord * 256, look.yCoord * 256, look.zCoord * 256);
        List<Point> candidates = new ArrayList<>();
        List<Double> distances = new ArrayList<>();
        double nearest = Double.POSITIVE_INFINITY;
        for (Point point : points) {
            MovingObjectPosition hit = bounds(point, size / 2 + 0.04).calculateIntercept(start, end);
            if (hit == null) continue;
            double distance = start.distanceTo(hit.hitVec);
            nearest = Math.min(nearest, distance);
            candidates.add(point);
            distances.add(distance);
        }
        for (int i = candidates.size() - 1; i >= 0; i--) {
            if (distances.get(i) > nearest + size) candidates.remove(i);
        }
        hits = candidates.size();
        selected = hits == 0 ? null : candidates.get(Math.floorMod(selectionOffset, hits));
    }

    private static AxisAlignedBB bounds(Point point, double radius) {
        return AxisAlignedBB.getBoundingBox(
            point.x - radius,
            point.y - radius,
            point.z - radius,
            point.x + radius,
            point.y + radius,
            point.z + radius);
    }

    private void drawReferences() {
        GL11.glBegin(GL11.GL_LINES);
        for (int axis = 0; axis < 3; axis++) {
            GL11.glColor3f(axis == 0 ? 1 : 0.4F, axis == 1 ? 1 : 0.4F, axis == 2 ? 1 : 0.4F);
            GL11.glVertex3d(originX, originY, originZ);
            GL11.glVertex3d(
                originX + (axis == 0 ? scale : 0),
                originY + (axis == 1 ? scale : 0),
                originZ + (axis == 2 ? scale : 0));
            for (int tick = 0; tick <= 4; tick++) {
                double d = scale * tick / 4;
                double x = originX + (axis == 0 ? d : 0);
                double y = originY + (axis == 1 ? d : 0);
                double z = originZ + (axis == 2 ? d : 0);
                GL11.glVertex3d(x - (axis == 1 ? 0.3 : 0), y - (axis == 1 ? 0 : 0.3), z);
                GL11.glVertex3d(x + (axis == 1 ? 0.3 : 0), y + (axis == 1 ? 0 : 0.3), z);
            }
        }
        GL11.glEnd();
        for (int axis = 0; axis < 3; axis++) {
            label(
                axes[axis].label + " [" + axes[axis].min + ", " + axes[axis].max + "]",
                originX + (axis == 0 ? scale + 1 : 0),
                originY + (axis == 1 ? scale + 1 : 0),
                originZ + (axis == 2 ? scale + 1 : 0));
        }
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        Tessellator tessellator = Tessellator.instance;
        tessellator.startDrawingQuads();
        for (int i = 0; i < REFERENCE_COLORS.length; i++) {
            ColorMetrics color = ColorMetrics.solid(REFERENCE_COLORS[i]);
            cube(
                tessellator,
                originX + scale * axes[0].position(color),
                originY + scale * axes[1].position(color),
                originZ + scale * axes[2].position(color),
                0.3,
                null,
                REFERENCE_COLORS[i]);
        }
        tessellator.draw();
        for (int i = 0; i < REFERENCE_COLORS.length; i++) {
            ColorMetrics color = ColorMetrics.solid(REFERENCE_COLORS[i]);
            label(
                REFERENCE_NAMES[i],
                originX + scale * axes[0].position(color),
                originY + scale * axes[1].position(color) + 0.5,
                originZ + scale * axes[2].position(color));
        }
    }

    private static void label(String text, double x, double y, double z) {
        Minecraft mc = Minecraft.getMinecraft();
        GL11.glPushMatrix();
        GL11.glTranslated(x, y, z);
        GL11.glRotatef(-RenderManager.instance.playerViewY, 0, 1, 0);
        GL11.glRotatef(RenderManager.instance.playerViewX, 1, 0, 0);
        GL11.glScaled(-0.035, -0.035, 0.035);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glColor4f(1, 1, 1, 1);
        mc.fontRenderer.drawStringWithShadow(text, -mc.fontRenderer.getStringWidth(text) / 2, 0, 0xFFFFFF);
        GL11.glDisable(GL11.GL_BLEND);
        GL11.glPopMatrix();
    }

    private static final int[][] CORNERS = { { 0, 0, 1, 1, 0, 1, 1, 0, 0, 0, 0, 0 },
        { 0, 1, 0, 1, 1, 0, 1, 1, 1, 0, 1, 1 }, { 1, 1, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0 },
        { 0, 1, 1, 1, 1, 1, 1, 0, 1, 0, 0, 1 }, { 0, 1, 0, 0, 1, 1, 0, 0, 1, 0, 0, 0 },
        { 1, 1, 1, 1, 1, 0, 1, 0, 0, 1, 0, 1 } };

    private static void cube(Tessellator tessellator, double x, double y, double z, double size, IIcon[] faces,
        int tint) {
        tessellator.setColorOpaque_I(tint);
        for (int side = 0; side < 6; side++) {
            IIcon icon = faces == null ? null : faces[side];
            if (faces != null && icon == null) continue;
            for (int vertex = 0; vertex < 4; vertex++) {
                double vx = x + (CORNERS[side][vertex * 3] - 0.5) * size;
                double vy = y + (CORNERS[side][vertex * 3 + 1] - 0.5) * size;
                double vz = z + (CORNERS[side][vertex * 3 + 2] - 0.5) * size;
                if (icon == null) tessellator.addVertex(vx, vy, vz);
                else tessellator.addVertexWithUV(
                    vx,
                    vy,
                    vz,
                    vertex == 0 || vertex == 3 ? icon.getMinU() : icon.getMaxU(),
                    vertex < 2 ? icon.getMinV() : icon.getMaxV());
            }
        }
    }

    @SubscribeEvent
    public void overlay(RenderGameOverlayEvent.Text event) {
        Minecraft mc = Minecraft.getMinecraft();
        if (!visible || mc.theWorld != world || mc.gameSettings.hideGUI) return;
        event.left.add(
            scanning ? "Block colors | " + catalog.progress()
                : "Block colors | " + points.size() + " samples | /blockcolors help");
        event.left.add(
            "X=" + axes[0].label
                + " Y="
                + axes[1].label
                + " Z="
                + axes[2].label
                + (spread == 0 ? "" : " | spread=" + spread));
        if (selected != null) {
            event.left.add(selected.sample.name + " [" + selected.sample.id + "] (" + hits + " under cursor)");
            ColorMetrics c = selected.sample.color;
            event.left.add(
                String.format(
                    Locale.ROOT,
                    "RGB %.0f %.0f %.0f | L %.3f a %.3f b %.3f | sigma(L) %.4f",
                    c.red * 255,
                    c.green * 255,
                    c.blue * 255,
                    c.lightness,
                    c.a,
                    c.b,
                    c.contrast));
        }
    }
}
