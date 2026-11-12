package net.junedev.viridium.client.map;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import net.junedev.viridium.Viridium;
import net.junedev.viridium.worldgen.terrain.WorldGen;
import net.junedev.viridium.worldgen.terrain.core.GenFingerprint;

/**
 * Finished map tiles that outlive the map screen: the last {@link #MEM_TILES} in memory (reopening the map costs
 * nothing) and all of them on disk under {@code <minecraft>/viridium/mapcache/<fingerprint>/}. The fingerprint covers
 * the generator parameters (seed included) and the generator code, so a changed generator never shows stale tiles.
 * Thread-safe.
 */
final class MapTileStore {

    private static final int MEM_TILES = 512;
    private static final int MAGIC = 0x56544d31; // "VTM1"

    private static final Map<String, int[]> MEM = new LinkedHashMap<>(256, 0.75f, true) {

        @Override
        protected boolean removeEldestEntry(Map.Entry<String, int[]> e) {
            return size() > MEM_TILES;
        }
    };

    private final String fingerprint;
    private final File dir;

    MapTileStore(WorldGen gen, File baseDir) {
        fingerprint = GenFingerprint.of(gen.params);
        dir = GenFingerprint.cacheDir(new File(baseDir, "viridium/mapcache"), gen.params);
        if (!dir.isDirectory()) Viridium.LOGGER.warn("Cannot create the map cache {}", dir);
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
}
