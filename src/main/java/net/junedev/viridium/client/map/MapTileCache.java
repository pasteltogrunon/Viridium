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
 * few per frame), kept in an LRU. Must be {@link #dispose disposed} on the render thread. A tile whose river networks
 * are not built yet is first shown without them (provisional, never stored) and replaced by a complete render queued
 * behind the first paints.
 */
public final class MapTileCache {

    public static final int TILE = 128;
    private static final int MAX_TEXTURES = 384;
    private static final int UPLOADS_PER_FRAME = 12;

    private static final ExecutorService POOL;
    // few, low-priority threads: the map loads a bit slower but leaves the game and the server their cores
    private static final int THREADS = Math.max(1, Math.min(2, Runtime.getRuntime().availableProcessors() / 4));

    static {
        AtomicInteger n = new AtomicInteger();
        POOL = Executors.newFixedThreadPool(THREADS, r -> {
            Thread t = new Thread(() -> {
                // river networks the map needs are built on this thread, not spread over every core
                net.junedev.viridium.worldgen.terrain.stages.RiverStage.buildSeriallyOnThisThread();
                r.run();
            }, "Viridium map worker " + n.incrementAndGet());
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY);
            return t;
        });
    }

    /**
     * Work for the pool: first paints before completions. Every task added to either queue submits one
     * {@link #RUN_NEXT}, which runs the most urgent task waiting.
     */
    private static final ConcurrentLinkedQueue<Runnable> URGENT = new ConcurrentLinkedQueue<>(),
        BACKGROUND = new ConcurrentLinkedQueue<>();
    private static final Runnable RUN_NEXT = () -> {
        Runnable r = URGENT.poll();
        if (r == null) r = BACKGROUND.poll();
        if (r != null) r.run();
    };

    private static void urgent(Runnable r) {
        URGENT.add(r);
        POOL.execute(RUN_NEXT);
    }

    private static void background(Runnable r) {
        BACKGROUND.add(r);
        POOL.execute(RUN_NEXT);
    }

    /** Identifies a tile image. */
    public record Key(String layer, boolean borders, int level, int tx, int tz) {}

    /** complete = false: a first paint without the river networks not built yet; a complete one replaces it. */
    private record Ready(Key key, int[] pixels, boolean complete) {}

    private final MapTileRenderer renderer;
    private final MapTileStore store;
    private final LinkedHashMap<Key, DynamicTexture> textures = new LinkedHashMap<>(256, 0.75f, true);
    private final Set<Key> pending = ConcurrentHashMap.newKeySet();
    /** Tiles shown as a provisional first paint (render thread). */
    private final Set<Key> provisional = new java.util.HashSet<>();
    /** Tiles whose complete render is queued or running, or done and not uploaded yet. */
    private final Set<Key> completing = ConcurrentHashMap.newKeySet();
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
     * wanted. Render thread only.
     * <p>
     * A missing tile is first painted from the river networks already built (fast); when it needed others, it is shown
     * as it is but not stored, and a complete render (which builds the networks, on the worker's own thread) is queued
     * behind every first paint and replaces it.
     */
    public void request(Collection<Key> nearestFirst) {
        Set<Key> w = ConcurrentHashMap.newKeySet();
        w.addAll(nearestFirst);
        wanted = w;
        int budget = THREADS * 3 - pending.size();
        for (Key k : nearestFirst) {
            if (textures.containsKey(k)) {
                if (provisional.contains(k)) complete(k); // e.g. its completion was dropped while out of view
                continue;
            }
            if (budget <= 0 || pending.contains(k)) continue;
            pending.add(k);
            budget--;
            MapLayer layer = MapLayer.byId(k.layer());
            urgent(() -> {
                try {
                    if (disposed || !wanted.contains(k)) return;
                    int[] px = store == null ? null : store.load(k);
                    if (px != null) {
                        ready.add(new Ready(k, px, true));
                        return;
                    }
                    MapTileRenderer.Tile t = renderer.render(layer, k.level(), k.tx(), k.tz(), TILE, k.borders(), true);
                    if (t.complete && store != null) store.save(k, t.pixels);
                    if (disposed) return;
                    ready.add(new Ready(k, t.pixels, t.complete));
                    if (!t.complete) complete(k);
                } catch (Throwable t) {
                    Viridium.LOGGER.warn("Map tile {} failed", k, t);
                } finally {
                    pending.remove(k);
                }
            });
        }
    }

    /** Queues the complete render of a provisional tile (once). */
    private void complete(Key k) {
        if (!completing.add(k)) return;
        MapLayer layer = MapLayer.byId(k.layer());
        background(() -> {
            boolean queued = false;
            try {
                if (disposed || !wanted.contains(k)) return; // requested again when back in view
                int[] px = renderer.render(layer, k.level(), k.tx(), k.tz(), TILE, k.borders());
                if (store != null) store.save(k, px);
                if (disposed) return;
                ready.add(new Ready(k, px, true));
                queued = true;
            } catch (Throwable t) {
                Viridium.LOGGER.warn("Map tile {} failed", k, t);
            } finally {
                if (!queued) completing.remove(k);
            }
        });
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
            if (r.complete()) {
                provisional.remove(r.key());
                completing.remove(r.key());
            } else provisional.add(r.key());
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
                provisional.remove(e.getKey());
                it.remove();
            }
        }
    }

    /** Runs background work on the map worker pool. */
    public static void submit(Runnable r) {
        urgent(r);
    }

    public int pendingCount() {
        return pending.size() + ready.size() + completing.size();
    }

    /** Frees every texture. Render thread only. */
    public void dispose() {
        disposed = true;
        for (DynamicTexture t : new ArrayList<>(textures.values())) t.deleteGlTexture();
        textures.clear();
        ready.clear();
        provisional.clear();
        completing.clear();
    }
}
