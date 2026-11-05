package net.junedev.viridium.client.map;

import java.util.ArrayList;
import java.util.List;

import net.junedev.viridium.worldgen.terrain.WorldGen;
import net.junedev.viridium.worldgen.terrain.column.BiomeMapper;
import net.junedev.viridium.worldgen.terrain.column.TerrainLayout;
import net.junedev.viridium.worldgen.terrain.core.GenParams;
import net.junedev.viridium.worldgen.terrain.map.MapLayer;
import net.junedev.viridium.worldgen.terrain.map.MapTileRenderer;
import net.junedev.viridium.worldgen.terrain.pipeline.GenPoint;
import net.junedev.viridium.worldgen.terrain.pipeline.Stage;
import net.junedev.viridium.worldgen.terrain.stages.Rock;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.WorldServer;
import net.minecraft.world.biome.BiomeGenBase;

import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;

/**
 * Large-scale map of the generator, evaluated directly from the pipeline (so it shows unexplored land instantly).
 * Drag to pan, wheel to zoom, buttons switch layers, shift+click teleports (needs cheats). Prototype: singleplayer
 * only, because it needs the world seed.
 */
public class WorldMapScreen extends GuiScreen {

    private static final int BTN_BORDERS = 1000, BTN_CENTER = 1001;

    /** View state survives reopening the screen within a session. */
    private static String layerId = MapLayer.COMPOSITE.id;
    private static boolean borders;
    private static double centerX = Double.NaN, centerZ;
    /** Blocks per screen pixel. */
    private static double zoom = 8;
    private static long viewSeed;

    private MapTileCache cache;
    private WorldGen gen;
    private String error;
    private final GenPoint inspect = new GenPoint();
    private boolean dragging;
    private int lastMouseX, lastMouseY;

    @Override
    public void initGui() {
        buttonList.clear();
        if (gen == null && error == null) setupGenerator();
        int y = 22;
        int id = 0;
        for (MapLayer l : MapLayer.ALL) {
            GuiButton b = new GuiButton(id++, 4, y, 112, 16, l.name);
            b.enabled = !l.id.equals(layerId);
            buttonList.add(b);
            y += 17;
        }
        y += 6;
        buttonList.add(new GuiButton(BTN_BORDERS, 4, y, 112, 16, "Plate borders: " + (borders ? "on" : "off")));
        buttonList.add(new GuiButton(BTN_CENTER, 4, y + 17, 112, 16, "Center on player"));
    }

    private void setupGenerator() {
        MinecraftServer server = MinecraftServer.getServer();
        WorldServer overworld = server == null ? null : server.worldServerForDimension(0);
        if (overworld == null) {
            error = "The world map needs the world seed: singleplayer only for now.";
            return;
        }
        GenParams p = new GenParams();
        p.seed = GenParams.foldSeed(overworld.getSeed());
        gen = new WorldGen(p);
        cache = new MapTileCache(new MapTileRenderer(gen));
        if (Double.isNaN(centerX) || viewSeed != overworld.getSeed()) {
            centerX = mc.thePlayer.posX;
            centerZ = mc.thePlayer.posZ;
            viewSeed = overworld.getSeed();
        }
    }

    @Override
    protected void actionPerformed(GuiButton b) {
        if (b.id == BTN_BORDERS) borders = !borders;
        else if (b.id == BTN_CENTER) {
            centerX = mc.thePlayer.posX;
            centerZ = mc.thePlayer.posZ;
        } else if (b.id < MapLayer.ALL.size()) layerId = MapLayer.ALL.get(b.id).id;
        initGui();
    }

    // ------------------------------------------------------------------ input

    private int scaleFactor() {
        return new ScaledResolution(mc, mc.displayWidth, mc.displayHeight).getScaleFactor();
    }

    /** Blocks per GUI unit. */
    private double bpu() {
        return zoom * scaleFactor();
    }

    private double worldX(int guiX) {
        return centerX + (guiX - width / 2.0) * bpu();
    }

    private double worldZ(int guiY) {
        return centerZ + (guiY - height / 2.0) * bpu();
    }

    @Override
    public void handleMouseInput() {
        super.handleMouseInput();
        int wheel = Mouse.getEventDWheel();
        if (wheel != 0) {
            int mx = Mouse.getEventX() * width / mc.displayWidth;
            int my = height - Mouse.getEventY() * height / mc.displayHeight - 1;
            double wx = worldX(mx), wz = worldZ(my);
            zoom = Math.max(1.0 / 8, Math.min(4096, zoom * (wheel > 0 ? 1 / 1.25 : 1.25)));
            // keep the point under the cursor fixed
            centerX = wx - (mx - width / 2.0) * bpu();
            centerZ = wz - (my - height / 2.0) * bpu();
        }
    }

    @Override
    protected void mouseClicked(int x, int y, int button) {
        super.mouseClicked(x, y, button);
        if (x < 120 || gen == null) return;
        if (button == 0 && isShiftKeyDown()) {
            teleport(worldX(x), worldZ(y));
            return;
        }
        dragging = true;
        lastMouseX = x;
        lastMouseY = y;
    }

    @Override
    protected void mouseClickMove(int x, int y, int button, long time) {
        if (!dragging) return;
        centerX -= (x - lastMouseX) * bpu();
        centerZ -= (y - lastMouseY) * bpu();
        lastMouseX = x;
        lastMouseY = y;
    }

    @Override
    protected void mouseMovedOrUp(int x, int y, int which) {
        super.mouseMovedOrUp(x, y, which);
        if (which >= 0) dragging = false;
    }

    @Override
    protected void keyTyped(char c, int key) {
        if (key == Keyboard.KEY_M) {
            mc.displayGuiScreen(null);
            return;
        }
        super.keyTyped(c, key);
    }

    private void teleport(double x, double z) {
        gen.evaluate(x, z, Stage.GEOLOGY, inspect);
        int y = Math.max(TerrainLayout.surfaceY(inspect.h), TerrainLayout.SEA_LEVEL - 1) + 2;
        mc.thePlayer.sendChatMessage(String.format("/tp %d %d %d", (int) Math.floor(x), y, (int) Math.floor(z)));
        mc.displayGuiScreen(null);
    }

    @Override
    public void onGuiClosed() {
        if (cache != null) cache.dispose();
        cache = null;
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    // ------------------------------------------------------------------ drawing

    @Override
    public void drawScreen(int mouseX, int mouseY, float partial) {
        drawRect(0, 0, width, height, 0xff101418);
        if (error != null) {
            drawCenteredString(fontRendererObj, error, width / 2, height / 2, 0xffffff);
            return;
        }
        cache.upload();
        drawTiles();
        drawPlayer();
        drawRect(0, 0, 120, height, 0xa0000000);
        drawString(fontRendererObj, "Viridium map", 6, 8, 0xffffff);
        super.drawScreen(mouseX, mouseY, partial);
        drawInspector(mouseX, mouseY);
    }

    private void drawTiles() {
        MapLayer layer = MapLayer.byId(layerId);
        double bpu = bpu();
        int sf = scaleFactor();
        int level = Math.max(0, Math.min(16, (int) Math.floor(Math.log(zoom) / Math.log(2) + 1e-9)));
        double tileBlocks = MapTileCache.TILE * Math.scalb(1.0, level);
        double x0 = worldX(0), z0 = worldZ(0), x1 = worldX(width), z1 = worldZ(height);
        int tx0 = (int) Math.floor(x0 / tileBlocks), tx1 = (int) Math.floor(x1 / tileBlocks);
        int tz0 = (int) Math.floor(z0 / tileBlocks), tz1 = (int) Math.floor(z1 / tileBlocks);
        double ctx = centerX / tileBlocks, ctz = centerZ / tileBlocks;

        List<MapTileCache.Key> want = new ArrayList<>();
        for (int tz = tz0; tz <= tz1; tz++) for (int tx = tx0; tx <= tx1; tx++)
            want.add(new MapTileCache.Key(layer.id, borders, level, tx, tz));
        want.sort(
            (a, b) -> Double.compare(
                sq(a.tx() + 0.5 - ctx) + sq(a.tz() + 0.5 - ctz),
                sq(b.tx() + 0.5 - ctx) + sq(b.tz() + 0.5 - ctz)));
        cache.request(want);

        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glColor4f(1, 1, 1, 1);
        for (MapTileCache.Key k : want) {
            double wx = k.tx() * tileBlocks, wz = k.tz() * tileBlocks;
            if (!drawTile(k, wx, wz, tileBlocks, 0, 0, 1)) {
                // fall back to a coarser tile while this one renders
                for (int up = 1; up <= 4; up++) {
                    int lv = level + up;
                    double tb = MapTileCache.TILE * Math.scalb(1.0, lv);
                    int ptx = (int) Math.floor(wx / tb), ptz = (int) Math.floor(wz / tb);
                    MapTileCache.Key pk = new MapTileCache.Key(layer.id, borders, lv, ptx, ptz);
                    if (!cache.has(pk)) continue;
                    double u = (wx - ptx * tb) / tb, v = (wz - ptz * tb) / tb, s = tileBlocks / tb;
                    drawTile(pk, wx, wz, tileBlocks, u, v, s);
                    break;
                }
            }
        }
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        if (cache.pendingCount() > 0) drawString(fontRendererObj, "rendering…", width - 60, 6, 0xaaaaaa);
        drawString(fontRendererObj, String.format("%.2f bl/px · L%d", zoom, level), width - 90, height - 12, 0xaaaaaa);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
    }

    /** Draws world square [wx, wx+size) using UV sub-rectangle (u, v, u+s, v+s) of the tile's texture. */
    private boolean drawTile(MapTileCache.Key key, double wx, double wz, double size, double u, double v, double s) {
        DynamicTexture tex = cache.get(key);
        if (tex == null) return false;
        double bpu = bpu();
        double gx0 = width / 2.0 + (wx - centerX) / bpu, gz0 = height / 2.0 + (wz - centerZ) / bpu;
        double gx1 = gx0 + size / bpu, gz1 = gz0 + size / bpu;
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, tex.getGlTextureId());
        Tessellator t = Tessellator.instance;
        t.startDrawingQuads();
        t.addVertexWithUV(gx0, gz1, 0, u, v + s);
        t.addVertexWithUV(gx1, gz1, 0, u + s, v + s);
        t.addVertexWithUV(gx1, gz0, 0, u + s, v);
        t.addVertexWithUV(gx0, gz0, 0, u, v);
        t.draw();
        return true;
    }

    private void drawPlayer() {
        double bpu = bpu();
        int px = (int) (width / 2.0 + (mc.thePlayer.posX - centerX) / bpu);
        int pz = (int) (height / 2.0 + (mc.thePlayer.posZ - centerZ) / bpu);
        drawRect(px - 3, pz - 3, px + 4, pz + 4, 0xff000000);
        drawRect(px - 2, pz - 2, px + 3, pz + 3, 0xffff3030);
        double yaw = Math.toRadians(mc.thePlayer.rotationYaw);
        int dx = (int) Math.round(-Math.sin(yaw) * 8), dz = (int) Math.round(Math.cos(yaw) * 8);
        drawRect(px + dx - 1, pz + dz - 1, px + dx + 2, pz + dz + 2, 0xffffffff);
    }

    private void drawInspector(int mx, int my) {
        if (mx < 120) return;
        double x = worldX(mx), z = worldZ(my);
        GenPoint o = gen.evaluate(x, z, Stage.GEOLOGY, inspect);
        int biome = BiomeMapper.VANILLA.biomeId(o);
        BiomeGenBase b = biome >= 0 && biome < BiomeGenBase.getBiomeGenArray().length
            ? BiomeGenBase.getBiomeGenArray()[biome]
            : null;
        String[] lines = { String.format("X %d  Z %d   Y %d (h %.1f)", (int) Math.floor(x), (int) Math.floor(z),
            TerrainLayout.surfaceY(o.h), o.h),
            String.format("T %.1f °C   P %.0f mm   lat %.0f°", o.Tb - 0.07 * Math.max(0, o.h), o.Pb,
                Math.toDegrees(o.lat)),
            String.format("Plate %d,%d   L %.2f   edge %.0f", o.pi, o.pj, o.L, o.edge),
            String.format("Rock %s   erod %.2f", Rock.VALUES[o.rockT >= 0.5 ? o.rock : o.rock2].name()
                .toLowerCase(), o.erod),
            "Biome " + (b == null ? biome : b.biomeName), "Shift+click: teleport" };
        int w = 0;
        for (String s : lines) w = Math.max(w, fontRendererObj.getStringWidth(s));
        int x0 = width - w - 10, y0 = height - 18 - lines.length * 10;
        drawRect(x0 - 4, y0 - 4, width - 4, y0 + lines.length * 10, 0xa0000000);
        for (int i = 0; i < lines.length; i++) drawString(fontRendererObj, lines[i], x0, y0 + i * 10, 0xe0e0e0);
    }

    private static double sq(double v) {
        return v * v;
    }
}
