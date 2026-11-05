package net.junedev.viridium.client.map;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import net.junedev.viridium.Viridium;
import net.junedev.viridium.worldgen.terrain.map.MapLayer;
import net.junedev.viridium.worldgen.terrain.map.MapTileRenderer;
import net.minecraft.client.renderer.texture.DynamicTexture;

/**
 * Map tiles: computed on worker threads by {@link MapTileRenderer}, uploaded to GL textures on the render thread (a
 * few per frame), kept in an LRU. Must be {@link #dispose disposed} on the render thread.
 */
public final class MapTileCache {

    public static final int TILE = 128;
    private static final int MAX_TEXTURES = 384;
    private static final int UPLOADS_PER_FRAME = 12;

    private static final ExecutorService POOL;
    private static final int THREADS = Math.max(1, Runtime.getRuntime().availableProcessors() / 2);

    static {
        AtomicInteger n = new AtomicInteger();
        POOL = Executors.newFixedThreadPool(THREADS, r -> {
            Thread t = new Thread(r, "Viridium map worker " + n.incrementAndGet());
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY);
            return t;
        });
    }

    /** Identifies a tile image. */
    public record Key(String layer, boolean borders, int level, int tx, int tz) {}

    private record Ready(Key key, int[] pixels) {}

    private final MapTileRenderer renderer;
    private final MapTileStore store;
    private final LinkedHashMap<Key, DynamicTexture> textures = new LinkedHashMap<>(256, 0.75f, true);
    private final Set<Key> pending = ConcurrentHashMap.newKeySet();
    private final ConcurrentLinkedQueue<Ready> ready = new ConcurrentLinkedQueue<>();
    private volatile Set<Key> wanted = ConcurrentHashMap.newKeySet();
    private volatile boolean disposed;

    /** @param storeDir where tiles are kept on disk (null = memory only) */
    public MapTileCache(MapTileRenderer renderer, java.io.File storeDir) {
        this.renderer = renderer;
        MapTileStore st = null;
        try {
            if (storeDir != null) st = new MapTileStore(renderer.gen(), storeDir);
        } catch (RuntimeException e) {
            Viridium.LOGGER.warn("Map tile store unavailable", e);
        }
        this.store = st;
    }

    public MapTileRenderer renderer() {
        return renderer;
    }

    /** Texture of a finished tile, or null. Marks it recently used. */
    public DynamicTexture get(Key key) {
        return textures.get(key);
    }

    /** Peek without touching LRU order. */
    public boolean has(Key key) {
        return textures.containsKey(key);
    }

    /**
     * Declares the tiles the view needs, nearest first. Requests the missing ones, drops queued work that is no longer
     * wanted.
     */
    public void request(Collection<Key> nearestFirst) {
        Set<Key> w = ConcurrentHashMap.newKeySet();
        w.addAll(nearestFirst);
        wanted = w;
        int budget = THREADS * 3 - pending.size();
        for (Key k : nearestFirst) {
            if (budget <= 0) break;
            if (textures.containsKey(k) || pending.contains(k)) continue;
            pending.add(k);
            budget--;
            MapLayer layer = MapLayer.byId(k.layer());
            POOL.execute(() -> {
                try {
                    if (disposed || !wanted.contains(k)) return;
                    int[] px = store == null ? null : store.load(k);
                    if (px == null) {
                        px = renderer.render(layer, k.level(), k.tx(), k.tz(), TILE, k.borders());
                        if (store != null) store.save(k, px);
                    }
                    if (!disposed) ready.add(new Ready(k, px));
                } catch (Throwable t) {
                    Viridium.LOGGER.warn("Map tile {} failed", k, t);
                } finally {
                    pending.remove(k);
                }
            });
        }
    }

    /** Uploads finished tiles. Render thread only. */
    public void upload() {
        for (int i = 0; i < UPLOADS_PER_FRAME; i++) {
            Ready r = ready.poll();
            if (r == null) break;
            DynamicTexture tex = new DynamicTexture(TILE, TILE);
            System.arraycopy(r.pixels(), 0, tex.getTextureData(), 0, r.pixels().length);
            tex.updateDynamicTexture();
            DynamicTexture old = textures.put(r.key(), tex);
            if (old != null) old.deleteGlTexture();
        }
        if (textures.size() > MAX_TEXTURES) {
            Iterator<Map.Entry<Key, DynamicTexture>> it = textures.entrySet()
                .iterator();
            int excess = textures.size() - MAX_TEXTURES;
            while (excess-- > 0 && it.hasNext()) {
                Map.Entry<Key, DynamicTexture> e = it.next();
                if (wanted.contains(e.getKey())) continue;
                e.getValue()
                    .deleteGlTexture();
                it.remove();
            }
        }
    }

    /** Runs background work on the map worker pool. */
    public static void submit(Runnable r) {
        POOL.execute(r);
    }

    public int pendingCount() {
        return pending.size() + ready.size();
    }

    /** Frees every texture. Render thread only. */
    public void dispose() {
        disposed = true;
        for (DynamicTexture t : new ArrayList<>(textures.values())) t.deleteGlTexture();
        textures.clear();
        ready.clear();
    }
}
