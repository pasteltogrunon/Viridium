package net.junedev.viridium.client.map;

import java.util.ArrayList;
import java.util.List;

import net.junedev.viridium.worldgen.terrain.WorldGen;

import net.junedev.viridium.worldgen.terrain.column.TerrainLayout;
import net.junedev.viridium.worldgen.terrain.core.GenParams;
import net.junedev.viridium.worldgen.terrain.crust.Lithology;
import net.junedev.viridium.worldgen.terrain.map.CrustSectionRenderer;
import net.junedev.viridium.worldgen.terrain.map.MapLayer;
import net.junedev.viridium.worldgen.terrain.map.MapTileRenderer;
import net.junedev.viridium.worldgen.terrain.pipeline.GenPoint;
import net.junedev.viridium.worldgen.terrain.pipeline.Stage;
import net.junedev.viridium.worldgen.terrain.stages.Biome;
import net.junedev.viridium.worldgen.terrain.stages.Rock;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.WorldServer;


import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;

/**
 * Large-scale map of the generator, evaluated directly from the pipeline (so it shows unexplored land instantly).
 * Drag to pan, wheel to zoom, buttons switch layers, shift+click teleports (needs cheats). Prototype: singleplayer
 * only, because it needs the world seed.
 */
public class WorldMapScreen extends GuiScreen {

    private static final int LIST_TOP = 22, LIST_ROW = 10;
    private static final int BTN_BORDERS = 1000, BTN_CENTER = 1001, BTN_SECTION = 1002;
    private static final int SECTION_TOP = 700, SECTION_BOTTOM = TerrainLayout.MIN_Y - 40;

    /** View state survives reopening the screen within a session. */
    private static String layerId = MapLayer.COMPOSITE.id;
    private static boolean borders, section;

    // cross-section panel
    private CrustSectionRenderer sectionRenderer;
    private DynamicTexture sectionTex;
    private String sectionKey, sectionPendingKey;
    private volatile int[] sectionPixels;
    private volatile String sectionReadyKey;
    private int sectionW, sectionH;
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
        int y = LIST_TOP + MapLayer.ALL.size() * LIST_ROW + 6;
        buttonList.add(new GuiButton(BTN_BORDERS, 4, y, 112, 16, "Plate borders: " + (borders ? "on" : "off")));
        buttonList.add(new GuiButton(BTN_CENTER, 4, y + 17, 112, 16, "Center on player"));
        buttonList.add(new GuiButton(BTN_SECTION, 4, y + 34, 112, 16, "Crust section: " + (section ? "on" : "off")));
    }

    private void setupGenerator() {
        MinecraftServer server = MinecraftServer.getServer();
        WorldServer overworld = server == null ? null : server.worldServerForDimension(0);
        if (overworld == null) {
            error = "The world map needs the world seed: singleplayer only for now.";
            return;
        }
        // share the server's pipeline: its caches and river networks are already warm around the player
        net.junedev.viridium.worldgen.terrain.cube.ViridiumWorldGenerator vg = net.junedev.viridium.worldgen.terrain.cube.ViridiumWorldGenerator
            .forWorld(overworld);
        if (vg != null) gen = vg.gen();
        else {
            GenParams p = new GenParams();
            p.seed = GenParams.foldSeed(overworld.getSeed());
            gen = new WorldGen(p);
        }
        cache = new MapTileCache(new MapTileRenderer(gen), mc.mcDataDir);
        sectionRenderer = new CrustSectionRenderer(gen);
        if (Double.isNaN(centerX) || viewSeed != overworld.getSeed()) {
            centerX = mc.thePlayer.posX;
            centerZ = mc.thePlayer.posZ;
            viewSeed = overworld.getSeed();
        }
    }

    @Override
    protected void actionPerformed(GuiButton b) {
        if (b.id == BTN_BORDERS) borders = !borders;
        else if (b.id == BTN_SECTION) section = !section;
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
        int row = (y - LIST_TOP) / LIST_ROW;
        if (x < 120 && y >= LIST_TOP && row < MapLayer.ALL.size()) {
            layerId = MapLayer.ALL.get(row).id;
            return;
        }
        if (x < 120 || gen == null) return;
        if (menuOpen) { // a click while the menu is open picks an entry or closes it
            int entry = menuEntryAt(x, y);
            menuOpen = false;
            if (entry == 0 && canTeleport()) teleport(menuX, menuZ);
            else if (entry == 1) {
                centerX = menuX;
                centerZ = menuZ;
            }
            return;
        }
        if (button == 1) {
            openMenu(x, y);
            return;
        }
        dragging = true;
        dragDistance = 0;
        lastMouseX = x;
        lastMouseY = y;
    }

    // ------------------------------------------------------------------ click menu

    private static final int MENU_W = 130, MENU_ROW = 12;
    private boolean menuOpen;
    private int menuGX, menuGY, dragDistance;
    private double menuX, menuZ;

    private void openMenu(int x, int y) {
        menuOpen = true;
        menuX = worldX(x);
        menuZ = worldZ(y);
        menuGX = Math.min(x, width - MENU_W - 2);
        menuGY = Math.min(y, height - 3 * MENU_ROW - 6);
    }

    /** Entry under (x, y): 0 teleport, 1 centre, 2 close, -1 outside. */
    private int menuEntryAt(int x, int y) {
        if (x < menuGX || x >= menuGX + MENU_W || y < menuGY + 14) return -1;
        int e = (y - menuGY - 14) / MENU_ROW;
        return e < 3 ? e : -1;
    }

    /** Commands allowed for this player (singleplayer with cheats, or op). */
    private boolean canTeleport() {
        MinecraftServer server = MinecraftServer.getServer();
        return server != null && server.getConfigurationManager() != null
            && server.getConfigurationManager()
                .func_152596_g(mc.thePlayer.getGameProfile());
    }

    private void drawMenu(int mx, int my) {
        boolean tp = canTeleport();
        String[] entries = { tp ? "Teleport here" : "Teleport (needs cheats)", "Centre map here", "Close" };
        int h = 14 + entries.length * MENU_ROW + 2;
        drawRect(menuGX - 1, menuGY - 1, menuGX + MENU_W + 1, menuGY + h + 1, 0xff808080);
        drawRect(menuGX, menuGY, menuGX + MENU_W, menuGY + h, 0xf0101010);
        drawString(fontRendererObj, String.format("X %d  Z %d", (int) Math.floor(menuX), (int) Math.floor(menuZ)),
            menuGX + 4, menuGY + 3, 0xa0a0a0);
        int hover = menuEntryAt(mx, my);
        for (int i = 0; i < entries.length; i++) {
            int y = menuGY + 14 + i * MENU_ROW;
            boolean enabled = i != 0 || tp;
            if (hover == i && enabled) drawRect(menuGX + 1, y - 1, menuGX + MENU_W - 1, y + MENU_ROW - 1, 0x80508050);
            drawString(fontRendererObj, entries[i], menuGX + 6, y + 1, !enabled ? 0x707070 : hover == i ? 0xffff80 : 0xe0e0e0);
        }
        // marker on the chosen point
        int px = (int) (width / 2.0 + (menuX - centerX) / bpu()), pz = (int) (height / 2.0 + (menuZ - centerZ) / bpu());
        drawRect(px - 2, pz - 2, px + 3, pz + 3, 0xffffff00);
    }

    @Override
    protected void mouseClickMove(int x, int y, int button, long time) {
        if (!dragging) return;
        dragDistance += Math.abs(x - lastMouseX) + Math.abs(y - lastMouseY);
        centerX -= (x - lastMouseX) * bpu();
        centerZ -= (y - lastMouseY) * bpu();
        lastMouseX = x;
        lastMouseY = y;
    }

    @Override
    protected void mouseMovedOrUp(int x, int y, int which) {
        super.mouseMovedOrUp(x, y, which);
        if (which >= 0 && dragging) {
            dragging = false;
            if (which == 0 && dragDistance <= 3 && x >= 120) openMenu(x, y); // a plain click, not a drag
        }
    }

    @Override
    protected void keyTyped(char c, int key) {
        if (key == Keyboard.KEY_ESCAPE && menuOpen) {
            menuOpen = false;
            return;
        }
        if (key == Keyboard.KEY_M) {
            mc.displayGuiScreen(null);
            return;
        }
        super.keyTyped(c, key);
    }

    private void teleport(double x, double z) {
        gen.evaluate(x, z, Stage.BIOMES, inspect);
        int y = Math.max(TerrainLayout.surfaceY(Math.max(inspect.h, inspect.hs)), TerrainLayout.SEA_LEVEL - 1) + 2;
        mc.thePlayer.sendChatMessage(String.format("/tp %d %d %d", (int) Math.floor(x), y, (int) Math.floor(z)));
        mc.displayGuiScreen(null);
    }

    @Override
    public void onGuiClosed() {
        if (cache != null) cache.dispose();
        cache = null;
        if (sectionTex != null) sectionTex.deleteGlTexture();
        sectionTex = null;
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
        if (section) drawSection();
        drawRect(0, 0, 120, height, 0xa0000000);
        drawString(fontRendererObj, "Viridium map", 6, 8, 0xffffff);
        for (int k = 0; k < MapLayer.ALL.size(); k++) {
            MapLayer l = MapLayer.ALL.get(k);
            int ly = LIST_TOP + k * LIST_ROW;
            boolean sel = l.id.equals(layerId), hover = mouseX < 120 && mouseY >= ly && mouseY < ly + LIST_ROW;
            if (sel) drawRect(2, ly - 1, 118, ly + LIST_ROW - 1, 0x80508050);
            drawString(fontRendererObj, l.name, 6, ly, sel ? 0xffff80 : hover ? 0xffffff : 0xa0a0a0);
        }
        super.drawScreen(mouseX, mouseY, partial);
        if (menuOpen) drawMenu(mouseX, mouseY);
        else drawInspector(mouseX, mouseY);
    }

    /** Coarser levels rendered first (relative to the view's level), ending with the view's level itself. */
    private static final int[] LOD_STEPS = { 4, 3, 0 };
    private static final int MAX_LEVEL = 16;

    /** Tiles of a level covering a view rectangle, nearest to the map centre first. */
    private List<MapTileCache.Key> tilesInView(MapLayer layer, int level, double x0, double z0, double x1, double z1) {
        double tileBlocks = MapTileCache.TILE * Math.scalb(1.0, level);
        int tx0 = (int) Math.floor(x0 / tileBlocks), tx1 = (int) Math.floor(x1 / tileBlocks);
        int tz0 = (int) Math.floor(z0 / tileBlocks), tz1 = (int) Math.floor(z1 / tileBlocks);
        double ctx = centerX / tileBlocks, ctz = centerZ / tileBlocks;
        List<MapTileCache.Key> keys = new ArrayList<>();
        for (int tz = tz0; tz <= tz1; tz++) for (int tx = tx0; tx <= tx1; tx++)
            keys.add(new MapTileCache.Key(layer.id, borders, level, tx, tz));
        keys.sort(
            (a, b) -> Double.compare(
                sq(a.tx() + 0.5 - ctx) + sq(a.tz() + 0.5 - ctz),
                sq(b.tx() + 0.5 - ctx) + sq(b.tz() + 0.5 - ctz)));
        return keys;
    }

    private void drawTiles() {
        MapLayer layer = MapLayer.byId(layerId);
        double bpu = bpu();
        int sf = scaleFactor();
        int level = Math.max(0, Math.min(16, (int) Math.floor(Math.log(zoom) / Math.log(2) + 1e-9)));
        double tileBlocks = MapTileCache.TILE * Math.scalb(1.0, level);
        double x0 = worldX(0), z0 = worldZ(0), x1 = worldX(width), z1 = worldZ(height);

        List<MapTileCache.Key> want = tilesInView(layer, level, x0, z0, x1, z1);
        // progressive detail: the whole view at 1/16 and 1/8 resolution first (a handful of tiles), then the real
        // level. A finer pass only starts once every tile of the coarser one is on screen.
        List<MapTileCache.Key> queue = new ArrayList<>();
        for (int up : LOD_STEPS) {
            int lv = level + up;
            if (lv > MAX_LEVEL) continue;
            List<MapTileCache.Key> pass = up == 0 ? want : tilesInView(layer, lv, x0, z0, x1, z1);
            queue.addAll(pass);
            boolean complete = true;
            for (MapTileCache.Key k : pass) if (!cache.has(k)) {
                complete = false;
                break;
            }
            if (!complete) break;
        }
        cache.request(queue);

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

    /** Bottom panel: crust cross-section along the horizontal line through the map centre. */
    private void drawSection() {
        int panelTop = height * 3 / 5, left = 120;
        int cy = height / 2;
        drawRect(left, cy, width, cy + 1, 0xc0ffffff); // section line on the map
        drawRect(left, panelTop - 1, width, height, 0xff000000);
        int sf = scaleFactor();
        int w = Math.min(768, (width - left) * sf / 2), h = Math.min(384, (height - panelTop) * sf / 2);
        double x0 = worldX(left), x1 = worldX(width), z = centerZ;
        String key = String.format("%d|%d|%d|%d|%d", Math.round(x0), Math.round(x1), Math.round(z), w, h);
        if (!key.equals(sectionKey) && !key.equals(sectionPendingKey) && !dragging) {
            sectionPendingKey = key;
            MapTileCache.submit(() -> {
                int[] px = sectionRenderer.render(x0, x1, z, SECTION_TOP, SECTION_BOTTOM, w, h);
                sectionPixels = px;
                sectionReadyKey = key;
            });
        }
        String ready = sectionReadyKey;
        if (ready != null && !ready.equals(sectionKey) && sectionPixels != null) {
            int[] px = sectionPixels;
            if (sectionTex == null || sectionW != w || sectionH != h || px.length != sectionW * sectionH) {
                if (sectionTex != null) sectionTex.deleteGlTexture();
                String[] parts = ready.split("\\|");
                sectionW = Integer.parseInt(parts[3]);
                sectionH = Integer.parseInt(parts[4]);
                sectionTex = new DynamicTexture(sectionW, sectionH);
            }
            if (px.length == sectionW * sectionH) {
                System.arraycopy(px, 0, sectionTex.getTextureData(), 0, px.length);
                sectionTex.updateDynamicTexture();
                sectionKey = ready;
            }
        }
        if (sectionTex != null) {
            GL11.glEnable(GL11.GL_TEXTURE_2D);
            GL11.glColor4f(1, 1, 1, 1);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, sectionTex.getGlTextureId());
            Tessellator t = Tessellator.instance;
            t.startDrawingQuads();
            t.addVertexWithUV(left, height, 0, 0, 1);
            t.addVertexWithUV(width, height, 0, 1, 1);
            t.addVertexWithUV(width, panelTop, 0, 1, 0);
            t.addVertexWithUV(left, panelTop, 0, 0, 0);
            t.draw();
        }
        drawString(fontRendererObj, String.format("Crust section · Z %d · Y %d to %d", (int) z, SECTION_TOP,
            SECTION_BOTTOM), left + 4, panelTop + 3, 0xffffff);
        // lithology under the cursor in the panel
        int mx = Mouse.getX() * width / mc.displayWidth, my = height - Mouse.getY() * height / mc.displayHeight - 1;
        if (mx >= left && my >= panelTop) {
            double wx = worldX(mx);
            int y = (int) Math.round(SECTION_TOP - (double) (my - panelTop) / (height - panelTop) * (SECTION_TOP - SECTION_BOTTOM));
            Lithology l = sectionRenderer.lithologyAt(wx, y, z);
            drawString(fontRendererObj, String.format("Y %d: %s", y, l == null ? "air / water"
                : l.name().toLowerCase().replace('_', ' ')), mx + 8, my - 10, 0xffff80);
        }
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
        GenPoint o = gen.evaluate(x, z, Stage.PLATES, inspect);
        // never build a river network on the render thread: fall back to the stages before rivers
        boolean full = o.L < -0.5 || gen.rivers.isReadyAt(x, z);
        if (full) gen.evaluate(x, z, Stage.BIOMES, o);
        else gen.evaluate(x, z, Stage.EROSION, o);
        String water = o.lake == 1 ? "lake" : o.lake == 2 ? "salt flat" : o.river != 0 ? "river" : o.h < 0 ? "sea" : "-";
        String[] lines = { String.format("X %d  Z %d   Y %d (h %.1f)", (int) Math.floor(x), (int) Math.floor(z),
            TerrainLayout.surfaceY(o.h), o.h),
            String.format("T %.1f °C   P %.0f mm   lat %.0f°", o.T, o.P, Math.toDegrees(o.lat)),
            String.format("Plate %d,%d   L %.2f   slope %.2f", o.pi, o.pj, o.L, o.slope),
            String.format("Rock %s   erod %.2f", Rock.VALUES[o.rockT >= 0.5 ? o.rock : o.rock2].name()
                .toLowerCase(), o.erod),
            full ? String.format("Biome %s   water %s", Biome.VALUES[o.biome].displayName, water)
                : "Biome: river network not built yet",
            "Click or right-click: menu" };
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
