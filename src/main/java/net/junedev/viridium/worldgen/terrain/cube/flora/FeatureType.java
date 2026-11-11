package net.junedev.viridium.worldgen.terrain.cube.flora;

import java.util.Random;

import net.junedev.viridium.ViriBlocks;
import net.junedev.viridium.blocks.sets.large_tree.LargeTreeBlockSet;
import net.minecraft.block.Block;
import net.minecraft.init.Blocks;

/**
 * Shapes of planned vegetation. Every shape is drawn from its feature's seed only, through a {@link BlockSink} that
 * clips to the cube being generated, so the same tree comes out identical whichever cube draws which part. Leaves stay
 * within 4 blocks of a log so they decay normally when the tree is cut.
 */
public enum FeatureType {

    OAK(2, 9) {

        @Override
        void draw(FloraSink s, int x, int y, int z, Random r) {
            blob(s, x, y, z, r, 4 + r.nextInt(3), Blocks.log, 0, Blocks.leaves, 0, 2);
        }
    },
    BIRCH(2, 10) {

        @Override
        void draw(FloraSink s, int x, int y, int z, Random r) {
            blob(s, x, y, z, r, 5 + r.nextInt(3), Blocks.log, 2, Blocks.leaves, 2, 2);
        }
    },
    SWAMP_OAK(3, 9) {

        @Override
        void draw(FloraSink s, int x, int y, int z, Random r) {
            blob(s, x, y, z, r, 5 + r.nextInt(2), Blocks.log, 0, Blocks.leaves, 0, 3);
        }
    },
    SPRUCE(3, 13) {

        @Override
        void draw(FloraSink s, int x, int y, int z, Random r) {
            int h = 7 + r.nextInt(5);
            for (int i = 0; i < h; i++) s.log(x, y + i, z, Blocks.log, 1);
            int rad = 0, max = 1 + r.nextInt(2);
            for (int i = h; i >= 2 + r.nextInt(2); i--) { // cone from the top down, rings growing and resetting
                disc(s, x, y + i, z, rad, Blocks.leaves, 1, r, false);
                rad = rad >= max ? 1 : rad + 1;
                if (rad == 1 && max < 3) max++;
            }
            s.leaves(x, y + h, z, Blocks.leaves, 1);
        }
    },
    PINE(2, 22) {

        @Override
        void draw(FloraSink s, int x, int y, int z, Random r) {
            int h = 13 + r.nextInt(7);
            for (int i = 0; i < h; i++) s.log(x, y + i, z, Blocks.log, 1);
            int crown = 4 + r.nextInt(3);
            for (int i = h - crown; i <= h; i++)
                disc(s, x, y + i, z, i >= h - 1 ? 1 : 2 - (i - (h - crown)) % 2, Blocks.leaves, 1, r, false);
            s.leaves(x, y + h, z, Blocks.leaves, 1);
        }
    },
    JUNGLE(2, 14) {

        @Override
        void draw(FloraSink s, int x, int y, int z, Random r) {
            blob(s, x, y, z, r, 6 + r.nextInt(6), Blocks.log, 3, Blocks.leaves, 3, 2);
        }
    },
    MEGA_JUNGLE(6, 30) {

        @Override
        void draw(FloraSink s, int x, int y, int z, Random r) {
            int h = 16 + r.nextInt(10);
            for (int i = 0; i < h; i++)
                for (int d = 0; d < 4; d++) s.log(x + (d & 1), y + i, z + (d >> 1), Blocks.log, 3);
            // crown around the 2x2 trunk top (centre at +0.5)
            for (int dy = -2; dy <= 1; dy++) {
                double rad = dy <= -1 ? 3.6 : dy == 0 ? 3.0 : 1.8;
                ellipseLayer(s, x, y + h + dy, z, rad, Blocks.leaves, 3, r, true);
            }
            // two side branches with small crowns
            for (int b = 0; b < 2; b++) {
                int by = y + h / 2 + r.nextInt(h / 3);
                int dir = r.nextInt(4), dx = dir == 0 ? 1 : dir == 1 ? -1 : 0, dz = dir == 2 ? 1 : dir == 3 ? -1 : 0;
                int bx = x + (dx > 0 ? 1 : 0), bz = z + (dz > 0 ? 1 : 0);
                for (int k = 1; k <= 2; k++) s.log(bx + dx * k, by + k / 2, bz + dz * k, Blocks.log, 3);
                ellipseLayer(s, bx + dx * 2, by + 1, bz + dz * 2, 2.2, Blocks.leaves, 3, r, false);
                ellipseLayer(s, bx + dx * 2, by + 2, bz + dz * 2, 1.3, Blocks.leaves, 3, r, false);
            }
        }
    },
    ACACIA(7, 10) {

        @Override
        void draw(FloraSink s, int x, int y, int z, Random r) {
            int h = 4 + r.nextInt(2), dir = r.nextInt(4);
            int dx = dir == 0 ? 1 : dir == 1 ? -1 : 0, dz = dir == 2 ? 1 : dir == 3 ? -1 : 0;
            int cx = x, cz = z, top = y;
            for (int i = 0; i < h; i++) s.log(cx, top++, cz, Blocks.log2, 0);
            for (int k = 0; k < 2 + r.nextInt(2); k++) { // the leaning part
                cx += dx;
                cz += dz;
                s.log(cx, top++, cz, Blocks.log2, 0);
            }
            ellipseLayer(s, cx, top, cz, 3.2, Blocks.leaves2, 0, r, false);
            ellipseLayer(s, cx, top + 1, cz, 1.7, Blocks.leaves2, 0, r, false);
        }
    },
    /** Viridium pedunculate oak: broad crown on a stout trunk. */
    PEDUNCULATE_OAK(4, 13) {

        @Override
        void draw(FloraSink s, int x, int y, int z, Random r) {
            LargeTreeBlockSet t = ViriBlocks.pedunculate_oak;
            broadleaf(s, x, y, z, r, 6 + r.nextInt(3), t.log, t.leaves);
        }
    },
    /** Viridium American chestnut: taller broadleaf. */
    CHESTNUT(4, 15) {

        @Override
        void draw(FloraSink s, int x, int y, int z, Random r) {
            LargeTreeBlockSet t = ViriBlocks.american_chestnut;
            broadleaf(s, x, y, z, r, 8 + r.nextInt(3), t.log, t.leaves);
        }
    },
    /** Viridium coconut palm: curved trunk and a drooping star of fronds. */
    PALM(6, 13) {

        @Override
        void draw(FloraSink s, int x, int y, int z, Random r) {
            LargeTreeBlockSet t = ViriBlocks.coconut_palm;
            int h = 6 + r.nextInt(4);
            double ang = r.nextDouble() * Math.PI * 2, lean = 1.5 + r.nextDouble() * 1.5;
            int tx = x, tz = z;
            for (int i = 0; i < h; i++) {
                double f = (double) i / h;
                tx = x + (int) Math.round(Math.cos(ang) * lean * f * f);
                tz = z + (int) Math.round(Math.sin(ang) * lean * f * f);
                s.log(tx, y + i, tz, t.log, 0);
            }
            int ty = y + h;
            s.leaves(tx, ty, tz, t.leaves, 0);
            for (int a = 0; a < 8; a++) { // fronds
                int fx = a == 0 || a == 1 || a == 7 ? 1 : a == 3 || a == 4 || a == 5 ? -1 : 0;
                int fz = a == 1 || a == 2 || a == 3 ? 1 : a == 5 || a == 6 || a == 7 ? -1 : 0;
                int len = (fx != 0 && fz != 0) ? 2 : 3;
                for (int k = 1; k <= len; k++) s.leaves(tx + fx * k, ty - (k == len ? 1 : 0), tz + fz * k, t.leaves, 0);
            }
        }
    },
    TALL_GRASS(0, 1) {

        @Override
        void draw(FloraSink s, int x, int y, int z, Random r) {
            s.plant(x, y, z, Blocks.tallgrass, 1);
        }
    },
    FERN(0, 1) {

        @Override
        void draw(FloraSink s, int x, int y, int z, Random r) {
            s.plant(x, y, z, Blocks.tallgrass, 2);
        }
    },
    FLOWER(0, 1) {

        @Override
        void draw(FloraSink s, int x, int y, int z, Random r) {
            if (r.nextInt(3) == 0) s.plant(x, y, z, Blocks.yellow_flower, 0);
            else s.plant(x, y, z, Blocks.red_flower, r.nextInt(9));
        }
    },
    DEAD_BUSH(0, 1) {

        @Override
        void draw(FloraSink s, int x, int y, int z, Random r) {
            s.plant(x, y, z, Blocks.deadbush, 0);
        }
    },
    CACTUS(0, 3) {

        @Override
        void draw(FloraSink s, int x, int y, int z, Random r) {
            int h = 1 + r.nextInt(3);
            for (int i = 0; i < h; i++) s.plant(x, y + i, z, Blocks.cactus, 0);
        }
    },
    /** Fallen block at the foot of a cliff: a rough ellipsoid of the local rock, half buried. */
    BOULDER(2, 3) {

        @Override
        void draw(FloraSink s, int x, int y, int z, Random r) {}

        @Override
        public void draw(FloraSink s, Feature f) {
            Random r = new Random(f.seed);
            double rx = 0.8 + r.nextDouble() * 1.4, rz = 0.8 + r.nextDouble() * 1.4, ry = 0.7 + r.nextDouble() * 0.9;
            net.minecraft.block.Block b = f.block != null ? f.block : Blocks.cobblestone;
            for (int dy = -2; dy <= 2; dy++) for (int dz = -2; dz <= 2; dz++) for (int dx = -2; dx <= 2; dx++) {
                double q = (dx / rx) * (dx / rx) + (dy / ry) * (dy / ry) + (dz / rz) * (dz / rz);
                if (q > 1.05 || (q > 0.6 && r.nextInt(4) == 0)) continue;
                s.rock(f.x + dx, f.y + dy, f.z + dz, b, 0);
            }
        }
    };

    /** Horizontal reach from the base and height above it (conservative). */
    public final int radius, height;

    FeatureType(int radius, int height) {
        this.radius = radius;
        this.height = height;
    }

    abstract void draw(FloraSink s, int x, int y, int z, Random r);

    public void draw(FloraSink s, Feature f) {
        draw(s, f.x, f.y, f.z, new Random(f.seed));
    }

    // ------------------------------------------------------------------ shape helpers

    /** Vanilla-like tree: straight trunk, 4 layers of leaves (two of radius rad, two of radius rad-1). */
    static void blob(FloraSink s, int x, int y, int z, Random r, int h, Block log, int lm, Block leaves, int fm,
        int rad) {
        for (int ly = y + h - 3; ly <= y + h; ly++) {
            int rr = ly >= y + h - 1 ? rad - 1 : rad;
            disc(s, x, ly, z, rr, leaves, fm, r, true);
        }
        for (int i = 0; i < h; i++) s.log(x, y + i, z, log, lm);
    }

    /** Broad crown: ellipsoid of leaves around the trunk top, held by four short branches. */
    static void broadleaf(FloraSink s, int x, int y, int z, Random r, int h, Block log, Block leaves) {
        for (int i = 0; i < h; i++) s.log(x, y + i, z, log, 0);
        int top = y + h;
        for (int b = 0; b < 4; b++) { // branches
            int dx = b == 0 ? 1 : b == 1 ? -1 : 0, dz = b == 2 ? 1 : b == 3 ? -1 : 0;
            int by = top - 2 - r.nextInt(2);
            s.log(x + dx, by, z + dz, log, 0);
            s.log(x + 2 * dx, by + 1, z + 2 * dz, log, 0);
        }
        for (int dy = -3; dy <= 2; dy++) {
            double t = dy / 2.8, rad = 3.6 * Math.sqrt(Math.max(0, 1 - t * t));
            ellipseLayer(s, x, top + dy, z, rad, leaves, 0, r, false);
        }
    }

    /** Square of leaves of the given radius; random corners skipped when {@code ragged}. */
    static void disc(FloraSink s, int x, int y, int z, int rad, Block leaves, int meta, Random r, boolean ragged) {
        for (int dz = -rad; dz <= rad; dz++) for (int dx = -rad; dx <= rad; dx++) {
            boolean corner = Math.abs(dx) == rad && Math.abs(dz) == rad && rad > 0;
            if (corner && (!ragged || r.nextBoolean())) continue;
            s.leaves(x + dx, y, z + dz, leaves, meta);
        }
    }

    /** Disc of leaves of real radius rad (optionally centred on a 2x2 trunk), with a ragged edge. */
    static void ellipseLayer(FloraSink s, int x, int y, int z, double rad, Block leaves, int meta, Random r,
        boolean twoByTwo) {
        int R = (int) Math.ceil(rad);
        double c = twoByTwo ? 0.5 : 0;
        for (int dz = -R; dz <= R + (twoByTwo ? 1 : 0); dz++) for (int dx = -R; dx <= R + (twoByTwo ? 1 : 0); dx++) {
            double d = Math.sqrt((dx - c) * (dx - c) + (dz - c) * (dz - c));
            if (d > rad + 0.3 || (d > rad - 0.6 && r.nextInt(3) == 0)) continue;
            s.leaves(x + dx, y, z + dz, leaves, meta);
        }
    }
}
