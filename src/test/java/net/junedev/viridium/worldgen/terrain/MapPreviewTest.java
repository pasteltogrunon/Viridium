package net.junedev.viridium.worldgen.terrain;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;

import javax.imageio.ImageIO;

import net.junedev.viridium.worldgen.terrain.map.CrustSectionRenderer;
import net.junedev.viridium.worldgen.terrain.map.MapLayer;
import net.junedev.viridium.worldgen.terrain.map.MapTileRenderer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Writes map previews (the same tiles the in-game map shows) to build/reports/map/. Enabled with
 * {@code VIRIDIUM_MAP=1}; optional {@code VIRIDIUM_MAP_LAYERS=height,geology} and {@code VIRIDIUM_MAP_LEVEL=5}.
 */
class MapPreviewTest {

    @Test
    @EnabledIfEnvironmentVariable(named = "VIRIDIUM_MAP", matches = "1")
    void writePreviews() throws IOException {
        MapTileRenderer r = new MapTileRenderer(new WorldGen(TerrainViewTest.params()));
        int level = Integer.parseInt(
            System.getenv()
                .getOrDefault("VIRIDIUM_MAP_LEVEL", "6"));
        String only = System.getenv("VIRIDIUM_MAP_LAYERS");
        int tiles = 4, size = 128, px = tiles * size;
        // optional centre (blocks) "x,z": tiles are aligned to the tile grid around it
        String c = System.getenv("VIRIDIUM_MAP_CENTER");
        double bppC = MapTileRenderer.blocksPerPixel(level);
        int ctx = 0, ctz = 0;
        if (c != null) {
            String[] xz = c.split(",");
            ctx = (int) Math.floor(Double.parseDouble(xz[0]) / (size * bppC));
            ctz = (int) Math.floor(Double.parseDouble(xz[1]) / (size * bppC));
        }
        String tag = c == null ? "" : "_" + c.replace(",", "_");
        File dir = new File("build/reports/map");
        dir.mkdirs();
        for (MapLayer layer : MapLayer.ALL) {
            if (only != null && !only.contains(layer.id)) continue;
            BufferedImage img = new BufferedImage(px, px, BufferedImage.TYPE_INT_RGB);
            long t0 = System.nanoTime();
            for (int tz = 0; tz < tiles; tz++) for (int tx = 0; tx < tiles; tx++) {
                int[] p = r.render(layer, level, ctx + tx - tiles / 2, ctz + tz - tiles / 2, size, false);
                img.setRGB(tx * size, tz * size, size, size, p, 0, size);
            }
            File f = new File(dir, layer.id + "_L" + level + tag + ".png");
            ImageIO.write(img, "png", f);
            System.out
                .printf("%-16s %6.0f ms/tile → %s%n", layer.id, (System.nanoTime() - t0) / 1e6 / (tiles * tiles), f);
        }
        // crust cross-sections through the middle of the previewed area
        CrustSectionRenderer sec = new CrustSectionRenderer(r.gen());
        double half = tiles * size * MapTileRenderer.blocksPerPixel(level) / 2;
        double cxB = ctx * size * bppC, czB = ctz * size * bppC;
        for (double zo : new double[] { -half * 0.5, 0, half * 0.5 }) {
            double z = czB + zo;
            long t0 = System.nanoTime();
            int w = 1024, h = 384;
            int[] p = sec.render(cxB - half, cxB + half, z, 700, -2100, w, h);
            BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            img.setRGB(0, 0, w, h, p, 0, w);
            File f = new File(dir, "section_z" + (int) z + ".png");
            ImageIO.write(img, "png", f);
            System.out.printf("section z=%d %6.0f ms → %s%n", (int) z, (System.nanoTime() - t0) / 1e6, f);
            // close-up of the upper crust
            p = sec.render(cxB - 2000, cxB + 2000, z, 400, -400, w, h);
            img.setRGB(0, 0, w, h, p, 0, w);
            ImageIO.write(img, "png", new File(dir, "section_z" + (int) z + "_upper.png"));
        }
    }
}
