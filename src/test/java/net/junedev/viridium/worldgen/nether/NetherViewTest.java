package net.junedev.viridium.worldgen.nether;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import javax.imageio.ImageIO;

import net.junedev.viridium.worldgen.terrain.core.GenParams;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Headless pictures of the Viridium Nether, written to build/reports/nether/.
 *
 * <p>
 * {@code VIRIDIUM_NETHER_VIEW="x,z,half[,step]"}: a map of each tier around (x, z) ({@code 2·half} blocks wide, one
 * sample every {@code step} blocks): domains coloured by core (brightness = transformation intensity), closed rock
 * dark, lava orange, abysses black, routes white, chimneys cyan, cores marked with their transformation. Also vertical
 * sections through (x, z) along X and Z over the whole height, and horizontal slices a few blocks above each tier's
 * nominal floor, drawn from the real cube filler.
 *
 * <p>
 * {@code VIRIDIUM_PARAMS="seed=<world seed>"} picks the world (default seed 0).
 */
class NetherViewTest {

    static NetherWorld world() {
        long seed = 0;
        String o = System.getenv("VIRIDIUM_PARAMS");
        if (o != null) for (String kv : o.split(",")) {
            String[] e = kv.split("=");
            if (e[0].trim()
                .equals("seed")) seed = Long.parseLong(e[1].trim());
        }
        return new NetherWorld(GenParams.foldSeed(seed) ^ 0x4E7E4);
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "VIRIDIUM_NETHER_VIEW", matches = ".+")
    void views() throws IOException {
        NetherWorld w = world();
        File dir = new File("build/reports/nether");
        dir.mkdirs();
        for (String spec : System.getenv("VIRIDIUM_NETHER_VIEW")
            .split(";")) {
            String[] a = spec.trim()
                .split(",");
            int cx = Integer.parseInt(a[0]), cz = Integer.parseInt(a[1]), half = Integer.parseInt(a[2]);
            int step = a.length > 3 ? Integer.parseInt(a[3]) : 4;
            String tag = cx + "_" + cz + "_" + half;
            long t0 = System.nanoTime();
            ImageIO.write(tierMaps(w, cx, cz, half, step), "png", new File(dir, "tiers_" + tag + ".png"));
            System.out.printf("tier maps in %.1f s%n", (System.nanoTime() - t0) / 1e9);
            t0 = System.nanoTime();
            int sh = Math.min(half, 384);
            int ymin = a.length > 5 ? Integer.parseInt(a[4]) : NetherLayout.MIN_Y,
                ymax = a.length > 5 ? Integer.parseInt(a[5]) : NetherLayout.MAX_Y;
            int sc = sh <= 128 ? 3 : 1;
            ImageIO.write(
                crop(section(w, cx, cz, sh, true), ymin, ymax, sc),
                "png",
                new File(dir, "secX_" + tag + ".png"));
            ImageIO.write(
                crop(section(w, cx, cz, sh, false), ymin, ymax, sc),
                "png",
                new File(dir, "secZ_" + tag + ".png"));
            System.out.printf("sections in %.1f s%n", (System.nanoTime() - t0) / 1e9);
            t0 = System.nanoTime();
            if (a.length > 6) {
                int y = Integer.parseInt(a[6]);
                ImageIO.write(slice(w, cx, cz, sh, y), "png", new File(dir, "sliceY" + y + "_" + tag + ".png"));
            } else for (int t = 0; t < NetherLayout.TIERS; t++) {
                int y = NetherLayout.FLOOR[t] + 10;
                ImageIO.write(slice(w, cx, cz, sh, y), "png", new File(dir, "slice" + t + "_" + tag + ".png"));
            }
            System.out.printf("slices in %.1f s%n", (System.nanoTime() - t0) / 1e9);
            List<NetherRoutes.Chimney> ch = new ArrayList<>();
            w.routes.collect(cx - half, cz - half, cx + half, cz + half, 0, null, ch);
            for (NetherRoutes.Chimney c : ch) System.out.printf(
                "  chimney %d->%d at %.0f,%.0f r%.1f y %d..%d turns %.1f lava %b%n",
                c.lower,
                c.lower + 1,
                c.x,
                c.z,
                c.r,
                c.bottom,
                c.top,
                c.total / (2 * Math.PI),
                c.lavafall);
            for (int i = Math.floorDiv(cx - half, NetherCores.CELL); i
                <= Math.floorDiv(cx + half, NetherCores.CELL); i++)
                for (int j = Math.floorDiv(cz - half, NetherCores.CELL); j
                    <= Math.floorDiv(cz + half, NetherCores.CELL); j++)
                    for (NetherCore c : w.cores.cell(i, j)) if (c != null) System.out.println("  " + c);
            System.out.println("  → " + dir.getAbsolutePath());
        }
    }

    /** Rows of a section between ymin and ymax, scaled. */
    static BufferedImage crop(BufferedImage s, int ymin, int ymax, int sc) {
        int y0 = NetherLayout.MAX_Y - ymax, h = ymax - ymin;
        BufferedImage out = new BufferedImage(s.getWidth() * sc, h * sc, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < h * sc; y++)
            for (int x = 0; x < s.getWidth() * sc; x++) out.setRGB(x, y, s.getRGB(x / sc, y0 + y / sc));
        return out;
    }

    // ------------------------------------------------------------------ tier maps

    static int hue(int id, float bright) {
        float h = ((id * 0x9E3779B1) >>> 8) / (float) (1 << 24);
        return Color.HSBtoRGB(h, 0.55f, bright);
    }

    static BufferedImage tierMaps(NetherWorld w, int cx, int cz, int half, int step) {
        int n = 2 * half / step;
        int pad = 10;
        BufferedImage img = new BufferedImage(3 * n + 2 * pad, n + 24, BufferedImage.TYPE_INT_RGB);
        int[][] px = new int[3][n * n];
        IntStream.range(0, n)
            .parallel()
            .forEach(j -> {
                NetherWorld.Column col = new NetherWorld.Column();
                for (int i = 0; i < n; i++) {
                    double x = cx - half + i * step, z = cz - half + j * step;
                    w.substrate.fracture(x, z, col.fracture);
                    boolean abyss = col.fracture.abyss > 0 && col.fracture.dist < col.fracture.abyss;
                    for (int t = 0; t < 3; t++) {
                        w.base(x, z, t, col);
                        NetherCores.Domain d = col.domain;
                        int rgb;
                        if (abyss && d.coreMask < 0.3) rgb = 0x000000;
                        else if (col.ceil <= col.floor) rgb = 0x2a2522;
                        else if (col.floor < NetherLayout.LAVA[t]) rgb = 0xd2561a;
                        else {
                            float b = (float) (0.35 + 0.6 * Math.min(1, d.intensity));
                            if (d.b != null && d.ga < 0.95) b *= 0.8f;
                            rgb = hue(d.a.id, b);
                            if (d.a.tier != t) rgb = (rgb >> 1) & 0x7f7f7f | 0x202020;
                        }
                        px[t][j * n + i] = rgb;
                    }
                }
            });
        for (int t = 0; t < 3; t++) {
            int lava = 0, closed = 0, abyss = 0;
            for (int v : px[t]) {
                if (v == 0xd2561a) lava++;
                else if (v == 0x2a2522) closed++;
                else if (v == 0) abyss++;
            }
            System.out.printf(
                "tier %d: lava %.0f%%, closed %.0f%%, abyss %.1f%%%n",
                t,
                100.0 * lava / px[t].length,
                100.0 * closed / px[t].length,
                100.0 * abyss / px[t].length);
        }
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(Color.DARK_GRAY);
        g.fillRect(0, 0, img.getWidth(), img.getHeight());
        List<NetherRoutes.Route> routes = new ArrayList<>();
        List<NetherRoutes.Chimney> chims = new ArrayList<>();
        w.routes.collect(cx - half, cz - half, cx + half, cz + half, 0, routes, chims);
        for (int t = 0; t < 3; t++) {
            int ox = t * (n + pad);
            img.setRGB(ox, 0, n, n, px[t], 0, n);
            g.setColor(Color.WHITE);
            g.setStroke(new BasicStroke(1.5f));
            for (NetherRoutes.Route r : routes) {
                if (r.tier != t) continue;
                g.setColor(r.main ? Color.WHITE : new Color(200, 200, 255));
                for (int k = 0; k + 1 < r.px.length; k++) g.drawLine(
                    ox + (int) ((r.px[k] - cx + half) / step),
                    (int) ((r.pz[k] - cz + half) / step),
                    ox + (int) ((r.px[k + 1] - cx + half) / step),
                    (int) ((r.pz[k + 1] - cz + half) / step));
            }
            g.setColor(Color.CYAN);
            for (NetherRoutes.Chimney c : chims) {
                if (c.lower != t && c.lower + 1 != t) continue;
                int qx = ox + (int) ((c.x - cx + half) / step), qz = (int) ((c.z - cz + half) / step);
                g.fillOval(qx - 4, qz - 4, 8, 8);
            }
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 12));
            int ci0 = Math.floorDiv(cx - half, NetherCores.CELL) - 1,
                ci1 = Math.floorDiv(cx + half, NetherCores.CELL) + 1;
            int cj0 = Math.floorDiv(cz - half, NetherCores.CELL) - 1,
                cj1 = Math.floorDiv(cz + half, NetherCores.CELL) + 1;
            for (int i = ci0; i <= ci1; i++) for (int j = cj0; j <= cj1; j++) {
                NetherCore c = w.cores.cell(i, j)[t];
                if (c == null) continue;
                int qx = ox + (int) ((c.x - cx + half) / step), qz = (int) ((c.z - cz + half) / step);
                if (qx < ox || qx >= ox + n || qz < 0 || qz >= n) continue;
                int r = (int) (c.cavityR / step);
                g.setColor(c.active() ? Color.YELLOW : c.extinct() ? Color.GRAY : Color.ORANGE);
                g.drawOval(qx - r, qz - r, 2 * r, 2 * r);
                g.drawString(
                    c.dominant.name()
                        .substring(0, 3),
                    qx - 10,
                    qz + 4);
            }
            g.setColor(Color.WHITE);
            g.drawString("tier " + t, ox + 4, n + 16);
        }
        g.dispose();
        return img;
    }

    // ------------------------------------------------------------------ materials

    static final Map<NetherMat, Integer> COLOR = new HashMap<>();

    static {
        COLOR.put(NetherMat.AIR, 0x16110f);
        COLOR.put(NetherMat.BEDROCK, 0x111111);
        COLOR.put(NetherMat.LAVA, 0xf06a10);
        COLOR.put(NetherMat.NETHERRACK, 0x7a2e2b);
        COLOR.put(NetherMat.BASALT, 0x4a4a52);
        COLOR.put(NetherMat.SCORIA, 0x5c3a33);
        COLOR.put(NetherMat.OBSIDIAN, 0x1e1630);
        COLOR.put(NetherMat.PUMICE, 0xb8b2a2);
        COLOR.put(NetherMat.TUFF, 0x8f8a76);
        COLOR.put(NetherMat.JASPER, 0xa8402a);
        COLOR.put(NetherMat.CHALCEDONY, 0x9fb3c2);
        COLOR.put(NetherMat.PERIDOTITE, 0x4f6640);
        COLOR.put(NetherMat.KOMATIITE, 0x3c4a3a);
        COLOR.put(NetherMat.SOUL_SAND, 0x55413a);
        COLOR.put(NetherMat.ASH, 0x7a7470);
        COLOR.put(NetherMat.QUARTZ_ORE, 0xe8dcd0);
        COLOR.put(NetherMat.BONE, 0xece6d6);
        COLOR.put(NetherMat.GLOWSTONE, 0xffe080);
        COLOR.put(NetherMat.PATH, 0xc0b090);
        COLOR.put(NetherMat.BROWN_MUSHROOM, 0x9a7050);
        COLOR.put(NetherMat.RED_MUSHROOM, 0xd03030);
        COLOR.put(NetherMat.NETHER_WART, 0xa01020);
        COLOR.put(NetherMat.FIRE, 0xffb020);
    }

    static int color(byte id) {
        return COLOR.get(NetherMat.VALUES[id]);
    }

    /** Vertical section through (cx, cz) along X (alongX) or Z, over the generated height. */
    static BufferedImage section(NetherWorld w, int cx, int cz, int half, boolean alongX) {
        NetherPlanner planner = new NetherPlanner(w);
        NetherFiller filler = new NetherFiller(w);
        int width = 2 * half, height = NetherLayout.MAX_Y - NetherLayout.MIN_Y;
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        int fixed = alongX ? cz : cx;
        int fchunk = Math.floorDiv(fixed, 16), flocal = Math.floorMod(fixed, 16);
        int c0 = Math.floorDiv((alongX ? cx : cz) - half, 16), c1 = Math.floorDiv((alongX ? cx : cz) + half - 1, 16);
        IntStream.rangeClosed(c0, c1)
            .parallel()
            .forEach(c -> {
                int chx = alongX ? c : fchunk, chz = alongX ? fchunk : c;
                NetherPlan p = planner.plan(chx, chz);
                for (int cy = NetherLayout.MIN_Y >> 4; cy < NetherLayout.MAX_Y >> 4; cy++) {
                    byte[] m = filler.fill(p, chx, cy, chz);
                    for (int l = 0; l < 16; l++) {
                        int px = c * 16 + l - ((alongX ? cx : cz) - half);
                        if (px < 0 || px >= width) continue;
                        int lx = alongX ? l : flocal, lz = alongX ? flocal : l;
                        for (int ly = 0; ly < 16; ly++) {
                            int y = (cy << 4) + ly;
                            int rgb = m == null ? color(NetherMat.AIR.id()) : color(m[(ly << 8) | (lz << 4) | lx]);
                            img.setRGB(px, NetherLayout.MAX_Y - 1 - y, rgb);
                        }
                    }
                }
            });
        return img;
    }

    /** Horizontal slice at height y around (cx, cz). */
    static BufferedImage slice(NetherWorld w, int cx, int cz, int half, int y) {
        NetherPlanner planner = new NetherPlanner(w);
        NetherFiller filler = new NetherFiller(w);
        int size = 2 * half;
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
        int c0x = Math.floorDiv(cx - half, 16), c1x = Math.floorDiv(cx + half - 1, 16);
        int c0z = Math.floorDiv(cz - half, 16), c1z = Math.floorDiv(cz + half - 1, 16);
        int cy = y >> 4, ly = y & 15;
        IntStream.rangeClosed(c0x, c1x)
            .parallel()
            .forEach(chx -> {
                for (int chz = c0z; chz <= c1z; chz++) {
                    NetherPlan p = planner.plan(chx, chz);
                    byte[] m = filler.fill(p, chx, cy, chz);
                    for (int lz = 0; lz < 16; lz++) for (int lx = 0; lx < 16; lx++) {
                        int px = chx * 16 + lx - (cx - half), pz = chz * 16 + lz - (cz - half);
                        if (px < 0 || px >= size || pz < 0 || pz >= size) continue;
                        img.setRGB(
                            px,
                            pz,
                            m == null ? color(NetherMat.AIR.id()) : color(m[(ly << 8) | (lz << 4) | lx]));
                    }
                }
            });
        return img;
    }
}
