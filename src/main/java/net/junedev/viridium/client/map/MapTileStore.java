package net.junedev.viridium.client.map;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URL;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import net.junedev.viridium.Viridium;
import net.junedev.viridium.worldgen.terrain.WorldGen;
import net.junedev.viridium.worldgen.terrain.map.MapTileRenderer;

/**
 * Finished map tiles that outlive the map screen: the last {@link #MEM_TILES} in memory (reopening the map costs
 * nothing) and all of them on disk under {@code <minecraft>/viridium/mapcache/<fingerprint>/}. The fingerprint covers
 * the generator parameters (seed included) and the generator code, so a changed generator never shows stale tiles.
 * Thread-safe.
 */
final class MapTileStore {

    private static final int MEM_TILES = 512;
    private static final int MAGIC = 0x56544d31; // "VTM1"
    private static final long MAX_AGE_MS = 14L * 24 * 3600 * 1000;

    private static final Map<String, int[]> MEM = new LinkedHashMap<>(256, 0.75f, true) {

        @Override
        protected boolean removeEldestEntry(Map.Entry<String, int[]> e) {
            return size() > MEM_TILES;
        }
    };

    private static String codeStamp;

    private final String fingerprint;
    private final File dir;

    MapTileStore(WorldGen gen, File baseDir) {
        fingerprint = Long.toHexString(hash(MapTileRenderer.paramsKey(gen.params) + "|" + codeStamp()));
        File root = new File(baseDir, "viridium/mapcache");
        dir = new File(root, fingerprint);
        if (!dir.isDirectory() && !dir.mkdirs()) Viridium.LOGGER.warn("Cannot create the map cache {}", dir);
        pruneOld(root);
    }

    private static String id(MapTileCache.Key k) {
        return k.layer() + "_" + (k.borders() ? 1 : 0) + "_L" + k.level() + "_" + k.tx() + "_" + k.tz();
    }

    /** The tile's pixels if stored (memory or disk), else null. */
    int[] load(MapTileCache.Key k) {
        String id = id(k);
        synchronized (MEM) {
            int[] px = MEM.get(fingerprint + id);
            if (px != null) return px;
        }
        File f = new File(dir, id + ".bin");
        if (!f.isFile()) return null;
        try (DataInputStream in = new DataInputStream(
            new BufferedInputStream(new GZIPInputStream(new FileInputStream(f))))) {
            if (in.readInt() != MAGIC) return null;
            int n = in.readInt();
            if (n != MapTileCache.TILE * MapTileCache.TILE) return null;
            int[] px = new int[n];
            for (int i = 0; i < n; i++) px[i] = in.readInt();
            remember(id, px);
            return px;
        } catch (IOException e) {
            f.delete();
            return null;
        }
    }

    void save(MapTileCache.Key k, int[] px) {
        String id = id(k);
        remember(id, px);
        File f = new File(dir, id + ".bin"), tmp = new File(dir, id + ".tmp");
        try (DataOutputStream out = new DataOutputStream(
            new BufferedOutputStream(new GZIPOutputStream(new FileOutputStream(tmp))))) {
            out.writeInt(MAGIC);
            out.writeInt(px.length);
            for (int v : px) out.writeInt(v);
        } catch (IOException e) {
            tmp.delete();
            return;
        }
        if (!tmp.renameTo(f)) {
            f.delete();
            if (!tmp.renameTo(f)) tmp.delete();
        }
    }

    private void remember(String id, int[] px) {
        synchronized (MEM) {
            MEM.put(fingerprint + id, px);
        }
    }

    /** Removes caches of other generator versions that nobody has used for a while. */
    private void pruneOld(File root) {
        File[] dirs = root.listFiles(File::isDirectory);
        if (dirs == null) return;
        long now = System.currentTimeMillis();
        for (File d : dirs) {
            if (d.equals(dir) || now - d.lastModified() < MAX_AGE_MS) continue;
            File[] fs = d.listFiles();
            if (fs != null) for (File f : fs) f.delete();
            d.delete();
        }
        dir.setLastModified(now);
    }

    /** Identifies the generator code: the jar (size, date), or in a dev workspace the newest compiled terrain class. */
    private static synchronized String codeStamp() {
        if (codeStamp != null) return codeStamp;
        String stamp = "?";
        try {
            URL u = WorldGen.class.getProtectionDomain()
                .getCodeSource()
                .getLocation();
            File src = new File(u.toURI());
            if (src.isFile()) stamp = src.length() + "@" + src.lastModified();
            else {
                long[] acc = { 0, 0 };
                walk(new File(src, "net/junedev/viridium/worldgen/terrain"), acc);
                stamp = acc[0] + "#" + acc[1];
            }
        } catch (Exception e) {
            stamp = String.valueOf(System.nanoTime()); // unknown code: never reuse disk tiles
        }
        return codeStamp = stamp;
    }

    private static void walk(File d, long[] acc) {
        File[] fs = d.listFiles();
        if (fs == null) return;
        for (File f : fs) {
            if (f.isDirectory()) walk(f, acc);
            else {
                acc[0] = Math.max(acc[0], f.lastModified());
                acc[1] = acc[1] * 31 + f.length();
            }
        }
    }

    private static long hash(String s) {
        long h = 0xcbf29ce484222325L;
        for (int i = 0; i < s.length(); i++) {
            h ^= s.charAt(i);
            h *= 0x100000001b3L;
        }
        return h;
    }
}
