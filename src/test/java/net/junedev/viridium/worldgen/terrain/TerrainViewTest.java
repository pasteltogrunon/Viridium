package net.junedev.viridium.worldgen.terrain;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.stream.IntStream;

import javax.imageio.ImageIO;

import net.junedev.viridium.worldgen.terrain.column.BiomeMapper;
import net.junedev.viridium.worldgen.terrain.column.ColumnPlan;
import net.junedev.viridium.worldgen.terrain.column.ColumnPlanner;
import net.junedev.viridium.worldgen.terrain.core.GenParams;
import net.junedev.viridium.worldgen.terrain.stages.Biome;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Headless pictures of the final terrain (column plans, 1 block per sample): a shaded top view and a perspective view,
 * both coloured roughly as the surface rules paint the blocks (bare rock on steep ground, snow, sand, water, biome
 * colour elsewhere). Written to build/reports/view/.
 *
 * <p>
 * {@code VIRIDIUM_VIEW="x,z,half[,azimuth[,height]];..."}: region centred on (x, z), {@code 2·half} blocks wide; the
 * camera looks along {@code azimuth} degrees (0 = +x, 90 = +z) from outside the region, {@code height} blocks above
 * the highest ground (default 60).
 */
class TerrainViewTest {

    @Test
    @EnabledIfEnvironmentVariable(named = "VIRIDIUM_VIEW", matches = ".+")
    void views() throws IOException {
        WorldGen gen = new WorldGen(params());
        ColumnPlanner planner = new ColumnPlanner(gen, BiomeMapper.FROM_STAGE);
        for (String spec : System.getenv("VIRIDIUM_VIEW")
            .split(";")) {
            String[] a = spec.trim()
                .split(",");
            int cx = Integer.parseInt(a[0]), cz = Integer.parseInt(a[1]), half = Integer.parseInt(a[2]);
            double az = a.length > 3 ? Double.parseDouble(a[3]) : 45,
                above = a.length > 4 ? Double.parseDouble(a[4]) : 60;
            long t0 = System.nanoTime();
            Region r = Region.sample(planner, cx, cz, half);
            System.out.printf("view %d,%d ±%d: sampled in %.1f s%n", cx, cz, half, (System.nanoTime() - t0) / 1e9);
            File dir = new File("build/reports/view");
            dir.mkdirs();
            String tag = cx + "_" + cz + "_" + half;
            ImageIO.write(r.top(), "png", new File(dir, "top_" + tag + ".png"));
            ImageIO.write(r.perspective(az, above), "png", new File(dir, "view_" + tag + "_" + (int) az + ".png"));
            System.out.println("  → " + new File(dir, "view_" + tag + "_" + (int) az + ".png").getAbsolutePath());
        }
    }

    /** Default parameters with the overrides of {@code VIRIDIUM_PARAMS="name=value,..."} (any double field). */
    static GenParams params() {
        GenParams p = new GenParams();
        String o = System.getenv("VIRIDIUM_PARAMS");
        if (o != null && !o.isEmpty()) for (String kv : o.split(",")) {
            String[] e = kv.split("=");
            try {
                if (e[0].trim()
                    .equals("seed")) { // a Minecraft world seed (64 bits), folded as the game does
                    p.seed = GenParams.foldSeed(Long.parseLong(e[1].trim()));
                    continue;
                }
                GenParams.class.getField(e[0].trim())
                    .setDouble(p, Double.parseDouble(e[1]));
            } catch (ReflectiveOperationException ex) {
                throw new IllegalArgumentException(kv, ex);
            }
        }
        return p;
    }

    /**
     * Prints places worth a view ({@code VIRIDIUM_FIND="half,step"}): the highest mountains, by climate (arid, humid,
     * cold), from a grid scan up to erosion (no rivers, fast).
     */
    @Test
    @EnabledIfEnvironmentVariable(named = "VIRIDIUM_FIND", matches = ".+")
    void find() {
        WorldGen gen = new WorldGen(params());
        String[] a = System.getenv("VIRIDIUM_FIND")
            .split(",");
        int half = Integer.parseInt(a[0]), step = Integer.parseInt(a[1]);
        int m = 2 * half / step + 1;
        double[][] res = new double[m * m][];
        IntStream.range(0, m * m)
            .parallel()
            .forEach(k -> {
                double x = -half + (k % m) * step, z = -half + (k / m) * step;
                var p = gen.evaluate(
                    x,
                    z,
                    net.junedev.viridium.worldgen.terrain.pipeline.Stage.EROSION,
                    new net.junedev.viridium.worldgen.terrain.pipeline.GenPoint());
                boolean mtn = p.scarp < 0.02 && p.mtnMask > 0.4 && p.slope < 1.2 && p.plat < 0.05;
                res[k] = new double[] { x, z, p.h, p.T, p.P, p.L, p.cliff, p.plat, p.erod, mtn ? p.slope : 0, p.Tb,
                    p.surf };
            });
        String[] kinds = { "mountain arid (P<450)", "mountain humid (P>1300)", "mountain cold (T<-2)",
            "mountain temperate", "sea cliff", "low coast warm (Tb>22)", "low coast temperate", "plateau",
            "barrier sand", "reef", "sea stack" };
        for (int kind = 0; kind < kinds.length; kind++) {
            final int kd = kind;
            java.util.List<double[]> l = new java.util.ArrayList<>();
            for (double[] r : res) {
                boolean ok = switch (kd) {
                    case 0 -> r[2] > 60 && r[2] < 220 && r[4] < 450 && r[3] > 0 && r[9] > 0.3;
                    case 1 -> r[2] > 60 && r[2] < 220 && r[4] > 1300 && r[3] > 8 && r[9] > 0.3;
                    case 2 -> r[2] > 60 && r[2] < 220 && r[3] < -2 && r[9] > 0.3;
                    case 3 -> r[2] > 60 && r[2] < 220 && r[3] > 2 && r[4] > 600 && r[4] < 1300 && r[9] > 0.3;
                    case 4 -> r[6] > 0.6;
                    case 5 -> Math.abs(r[5]) < 0.01 && r[6] < 0.05 && r[10] > 22;
                    case 6 -> Math.abs(r[5]) < 0.01 && r[6] < 0.05 && r[10] > 8 && r[10] < 18;
                    case 7 -> r[7] > 0.8;
                    case 8 -> r[11] == 4 && r[5] < 0 && r[2] > 0.5 && r[2] < 8;
                    case 9 -> r[11] == 5 && r[2] < 0;
                    default -> r[6] >= 0.5 && r[5] < 0 && r[2] > 4;
                };
                if (ok) l.add(r);
            }
            l.sort((p, q) -> Double.compare(q[kd < 4 ? 9 : 2], p[kd < 4 ? 9 : 2]));
            System.out.println(kinds[kind] + ": " + l.size());
            for (int i = 0; i < Math.min(6, l.size()); i++) {
                double[] r = l.get(i);
                System.out.printf(
                    "   %7.0f,%7.0f h %5.0f T %5.1f P %5.0f erod %.2f slope %.2f%n",
                    r[0],
                    r[1],
                    r[2],
                    r[3],
                    r[4],
                    r[8],
                    r[9]);
            }
        }
    }

    /**
     * Prints river landform sites ({@code VIRIDIUM_FIND_RIVERS="x,z,half,step"}): terraces, oxbows, fans, braids,
     * incised
     * reaches, from a grid scan with the rivers (builds the networks of the area).
     */
    @Test
    @EnabledIfEnvironmentVariable(named = "VIRIDIUM_FIND_RIVERS", matches = ".+")
    void findRivers() {
        WorldGen gen = new WorldGen(params());
        String[] a = System.getenv("VIRIDIUM_FIND_RIVERS")
            .split(",");
        int cx = Integer.parseInt(a[0]), cz = Integer.parseInt(a[1]), half = Integer.parseInt(a[2]),
            step = Integer.parseInt(a[3]);
        int m = 2 * half / step + 1;
        int[] cnt = new int[8];
        java.util.List<String>[] where = new java.util.List[8];
        for (int k = 0; k < 8; k++) where[k] = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        IntStream.range(0, m * m)
            .parallel()
            .forEach(k -> {
                double x = cx - half + (k % m) * step, z = cz - half + (k / m) * step;
                var p = gen.evaluate(
                    x,
                    z,
                    net.junedev.viridium.worldgen.terrain.pipeline.Stage.RIVERS,
                    new net.junedev.viridium.worldgen.terrain.pipeline.GenPoint());
                int[] kinds = { p.terrace > 0.5 ? 1 : 0, p.surf == 2 ? 1 : 0, p.surf == 3 ? 1 : 0,
                    p.lake == 1 && p.riverD < 120 && p.valley > 0.5 ? 1 : 0,
                    p.river == 1 && gen.rivers.incision(p) > 0.5 ? 1 : 0,
                    p.h < -2 && p.L > 0.01 && p.valley > 0.3 && p.glac < 0.1 ? 1 : 0 };
                for (int q = 0; q < kinds.length; q++) if (kinds[q] != 0) {
                    synchronized (cnt) {
                        cnt[q]++;
                    }
                    if (where[q].size() < 8) where[q].add((int) x + "," + (int) z);
                }
            });
        java.util.Set<Long> seen = new java.util.HashSet<>();
        int ox = 0, fa = 0, nets = 0, rias = 0;
        for (int k = 0; k < m * m; k++) {
            double x = cx - half + (k % m) * step, z = cz - half + (k / m) * step;
            for (long key : gen.rivers.basinsAt(x, z)) {
                if (!seen.add(key)) continue;
                var c = gen.rivers.basins.cell(key);
                var nt = c.ocean ? null : gen.rivers.cachedNet(c.ci, c.cj);
                if (nt == null) continue;
                nets++;
                ox += nt.nOx;
                fa += nt.nFan;
                if (nt.vq != null)
                    for (int cc = 0; cc < nt.vq.length; cc++) if ((nt.vq[cc] & 255) > 200 && nt.vd[cc] < 40) {
                        rias++;
                        if (rias % 25 == 1) System.out.printf(
                            "  ria cell at %.0f,%.0f%n",
                            nt.x0 + (cc % nt.nx + 0.5) * nt.g,
                            nt.y0 + (cc / nt.nx + 0.5) * nt.g);
                    }
                for (int q = 0; q < nt.nOx && q < 3; q++) System.out
                    .printf("  oxbow at %.0f,%.0f r %.0f%n", nt.ox[q * 7], nt.ox[q * 7 + 1], nt.ox[q * 7 + 2]);
            }
        }
        System.out.println("networks " + nets + ", oxbows " + ox + ", fans " + fa + ", ria cells " + rias);
        String[] names = { "terraces", "gravel", "fans", "oxbow", "incised river", "ria" };
        for (int q = 0; q < names.length; q++) System.out.println(names[q] + ": " + cnt[q] + " " + where[q]);
    }

    /** Heights, water and colours of a square region. */
    static final class Region {

        final int n, x0, z0;
        final float[] h; // top surface (terrain or water/ice) relative to sea level
        final int[] col; // unshaded colour
        final boolean[] wet;

        Region(int n, int x0, int z0) {
            this.n = n;
            this.x0 = x0;
            this.z0 = z0;
            h = new float[n * n];
            col = new int[n * n];
            wet = new boolean[n * n];
        }

        static Region sample(ColumnPlanner planner, int cx, int cz, int half) {
            int c0x = Math.floorDiv(cx - half, 16), c0z = Math.floorDiv(cz - half, 16);
            int nc = (2 * half + 15) / 16 + 1;
            Region r = new Region(nc * 16, c0x * 16, c0z * 16);
            ColumnPlan[] plans = new ColumnPlan[nc * nc];
            IntStream.range(0, nc * nc)
                .parallel()
                .forEach(k -> plans[k] = planner.plan(c0x + k % nc, c0z + k / nc));
            float[] ground = new float[r.n * r.n];
            int[] flags = new int[r.n * r.n];
            byte[] surf = new byte[r.n * r.n];
            float[] temp = new float[r.n * r.n];
            byte[] biome = new byte[r.n * r.n];
            for (int k = 0; k < nc * nc; k++) {
                ColumnPlan p = plans[k];
                int ox = (k % nc) * 16, oz = (k / nc) * 16;
                for (int i = 0; i < 256; i++) {
                    int idx = (oz + i / 16) * r.n + ox + i % 16;
                    ground[idx] = p.height[i];
                    flags[idx] = p.flags[i] & 0xff;
                    temp[idx] = p.temperature[i];
                    biome[idx] = p.biome[i];
                    surf[idx] = p.surf[i];
                    int top = p.surfaceY[i];
                    if (p.waterY[i] != ColumnPlan.NONE && p.waterY[i] > top) {
                        r.wet[idx] = true;
                        top = p.waterY[i];
                    }
                    if (p.iceTop[i] != ColumnPlan.NONE) top = Math.max(top, p.iceTop[i]);
                    r.h[idx] = r.wet[idx] || p.iceTop[i] != ColumnPlan.NONE ? top + 1 - 63 : p.height[i];
                }
            }
            for (int j = 0; j < r.n; j++) for (int i = 0; i < r.n; i++) {
                int idx = j * r.n + i;
                int il = Math.max(i - 1, 0), ir = Math.min(i + 1, r.n - 1), jl = Math.max(j - 1, 0),
                    jr = Math.min(j + 1, r.n - 1);
                double gx = (ground[j * r.n + ir] - ground[j * r.n + il]) / (ir - il),
                    gz = (ground[jr * r.n + i] - ground[jl * r.n + i]) / (jr - jl);
                double slope = Math.sqrt(gx * gx + gz * gz);
                Biome b = Biome.VALUES[biome[idx]];
                int c;
                if (r.wet[idx]) {
                    double depth = r.h[idx] - ground[idx];
                    c = depth < 2 ? 0x4a90d8 : depth < 6 ? 0x3070c0 : 0x1e4c9a;
                    if ((flags[idx] & ColumnPlan.F_FROZEN) != 0) c = 0xc8e0f0;
                } else if ((flags[idx] & ColumnPlan.F_SALT) != 0) c = 0xf0ece0;
                else if (surf[idx] == 1 && slope > 0.8) c = 0x8a6440; // terrace riser: alluvium
                else if (surf[idx] == 2) c = 0x9a968e; // gravel
                else if (surf[idx] == 3) c = 0xc9b58c; // fan deposits
                else if (surf[idx] == 4) c = 0xead8a0; // sand
                else if (slope > 1.1 && b != Biome.BEACH && b != Biome.DESERT) c = 0x85827c;
                else if (temp[idx] < -0.5) c = 0xf4f6fa;
                else if (b == Biome.BEACH || b == Biome.DESERT) c = 0xe6d496;
                else {
                    c = (b.r << 16) | (b.g << 8) | b.b;
                    if (slope > 0.7) c = mix(c, 0x85827c, 0.35); // thin soil
                }
                r.col[idx] = c;
            }
            return r;
        }

        static int mix(int a, int b, double t) {
            int ra = a >> 16 & 255, ga = a >> 8 & 255, ba = a & 255, rb = b >> 16 & 255, gb = b >> 8 & 255,
                bb = b & 255;
            return ((int) (ra + (rb - ra) * t) << 16) | ((int) (ga + (gb - ga) * t) << 8) | (int) (ba + (bb - ba) * t);
        }

        static int shade(int c, double f) {
            int rr = (int) Math.min(255, (c >> 16 & 255) * f), gg = (int) Math.min(255, (c >> 8 & 255) * f),
                bb = (int) Math.min(255, (c & 255) * f);
            return (rr << 16) | (gg << 8) | bb;
        }

        double light(int i, int j) {
            int il = Math.max(i - 1, 0), ir = Math.min(i + 1, n - 1), jl = Math.max(j - 1, 0),
                jr = Math.min(j + 1, n - 1);
            double nx = -(h[j * n + ir] - h[j * n + il]) / (ir - il), nz = -(h[jr * n + i] - h[jl * n + i]) / (jr - jl),
                ny = 1;
            double l = Math.sqrt(nx * nx + ny * ny + nz * nz);
            double sx = -0.5, sy = 0.75, sz = -0.42, sl = Math.sqrt(sx * sx + sy * sy + sz * sz);
            double lam = (nx * sx + ny * sy + nz * sz) / (l * sl);
            return 0.35 + 0.8 * Math.max(0, lam);
        }

        BufferedImage top() {
            BufferedImage img = new BufferedImage(n, n, BufferedImage.TYPE_INT_RGB);
            for (int j = 0; j < n; j++)
                for (int i = 0; i < n; i++) img.setRGB(i, j, shade(col[j * n + i], light(i, j)));
            return img;
        }

        double meanHeight() {
            double s = 0;
            for (float v : h) s += v;
            return s / h.length;
        }

        /** Voxel-space perspective view looking along azimuth {@code az} (degrees), front to back with occlusion. */
        BufferedImage perspective(double az, double above) {
            final int W = 1600, H = 900;
            BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
            for (int y = 0; y < H; y++) {
                int sky = mix(0x8fb4e8, 0xd6e4f2, y / (double) H);
                for (int x = 0; x < W; x++) img.setRGB(x, y, sky);
            }
            double ar = Math.toRadians(az), dx = Math.cos(ar), dz = Math.sin(ar);
            float max = -1e9f;
            for (float v : h) max = Math.max(max, v);
            double cxw = n / 2.0, czw = n / 2.0;
            double camX = cxw - dx * n * 0.5, camZ = czw - dz * n * 0.5, camY = max + above;
            double fov = Math.toRadians(62), focal = W / 2.0 / Math.tan(fov / 2);
            // pitch: the horizon is raised until the far half of the region fills the lower 3/4 of the picture
            double horizon = H * 0.28 - (camY - meanHeight()) * focal / (n * 0.95);
            double far = n * 1.6;
            int[] ybuf = new int[W];
            java.util.Arrays.fill(ybuf, H);
            for (int sx = 0; sx < W; sx++) {
                double off = (sx - W / 2.0) / focal, ang = Math.atan(off);
                double rx = Math.cos(ar + ang), rz = Math.sin(ar + ang), cosA = Math.cos(ang);
                for (double d = 2; d < far; d += 0.25 + d * 0.0025) {
                    double px = camX + rx * d, pz = camZ + rz * d;
                    int i = (int) Math.floor(px), j = (int) Math.floor(pz);
                    if (i < 1 || j < 1 || i >= n - 1 || j >= n - 1) continue;
                    double hh = h[j * n + i];
                    double depth = d * cosA;
                    int y = (int) (horizon + (camY - hh) * focal / depth);
                    if (y < 0) y = 0;
                    if (y >= ybuf[sx]) continue;
                    double fog = Math.min(1, Math.max(0, (d - n * 0.5) / (n * 1.3)));
                    int c = mix(shade(col[j * n + i], light(i, j)), 0xc8d8ec, fog * 0.7);
                    for (int yy = y; yy < ybuf[sx]; yy++) img.setRGB(sx, yy, c);
                    ybuf[sx] = y;
                }
            }
            return img;
        }
    }
}
