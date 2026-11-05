package net.junedev.viridium.worldgen.terrain.cube;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import net.junedev.viridium.Viridium;
import net.junedev.viridium.worldgen.terrain.GenStats;
import net.junedev.viridium.worldgen.terrain.WorldGen;
import net.junedev.viridium.worldgen.terrain.column.ColumnPlanCache;
import net.junedev.viridium.worldgen.terrain.pipeline.GenPoint;
import net.junedev.viridium.worldgen.terrain.pipeline.Stage;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.world.WorldEvent;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/**
 * Computes column plans (and the river networks they need) on background threads <b>before</b> Cubic Chunks asks for
 * them, so the server thread finds them ready. Every few ticks it walks the columns around each player, nearest first,
 * up to the view distance plus a margin, and queues the missing ones. It also builds the river networks of plates a
 * player is approaching. Output never depends on this: it only warms deterministic caches.
 */
public final class PlanPrefetcher {

    /** Columns beyond the server view distance that are prefetched too. */
    private static final int MARGIN = 3;
    private static final int TICK_INTERVAL = 5;
    private static final int MAX_QUEUED = 1024;
    /** Distances (blocks) at which neighbouring plates are probed for their river networks. */
    private static final int[] NET_PROBE = { 1500, 3000 };

    private static final Map<World, PlanPrefetcher> BY_WORLD = new WeakHashMap<>();
    private static boolean handlersRegistered;

    private final WorldServer world;
    private final ColumnPlanCache plans;
    private final WorldGen gen;
    private final ThreadPoolExecutor pool;
    private final Set<Long> pending = ConcurrentHashMap.newKeySet();
    private final Set<Long> pendingNets = ConcurrentHashMap.newKeySet();
    private final GenPoint probe = new GenPoint();
    private int ticks;

    private PlanPrefetcher(WorldServer world, ColumnPlanCache plans, WorldGen gen) {
        this.world = world;
        this.plans = plans;
        this.gen = gen;
        int threads = Math.max(1, Math.min(8, Runtime.getRuntime().availableProcessors() / 3));
        AtomicInteger n = new AtomicInteger();
        this.pool = new ThreadPoolExecutor(threads, threads, 30, TimeUnit.SECONDS, new LinkedBlockingQueue<>(), r -> {
            Thread t = new Thread(r, "Viridium worldgen prefetch " + n.incrementAndGet());
            t.setDaemon(true);
            t.setPriority(Thread.NORM_PRIORITY - 1);
            return t;
        });
        pool.allowCoreThreadTimeOut(true);
        Viridium.LOGGER.info("Viridium plan prefetcher: {} threads", threads);
    }

    /** Starts prefetching for a world (called by the generator). */
    public static synchronized PlanPrefetcher start(WorldServer world, ColumnPlanCache plans, WorldGen gen) {
        registerHandlers();
        PlanPrefetcher p = new PlanPrefetcher(world, plans, gen);
        PlanPrefetcher old = BY_WORLD.put(world, p);
        if (old != null) old.pool.shutdownNow();
        return p;
    }

    private static void registerHandlers() {
        if (handlersRegistered) return;
        handlersRegistered = true;
        Handlers h = new Handlers();
        FMLCommonHandler.instance()
            .bus()
            .register(h);
        MinecraftForge.EVENT_BUS.register(h);
    }

    public static final class Handlers {

        @SubscribeEvent
        public void onServerTick(TickEvent.ServerTickEvent e) {
            if (e.phase != TickEvent.Phase.END) return;
            List<PlanPrefetcher> all;
            synchronized (PlanPrefetcher.class) {
                all = new ArrayList<>(BY_WORLD.values());
            }
            for (PlanPrefetcher p : all) p.tick();
        }

        @SubscribeEvent
        public void onWorldUnload(WorldEvent.Unload e) {
            if (e.world.isRemote) return;
            PlanPrefetcher p;
            synchronized (PlanPrefetcher.class) {
                p = BY_WORLD.remove(e.world);
            }
            if (p != null) p.pool.shutdownNow();
        }
    }

    private static long key(int x, int z) {
        return ((long) x << 32) | (z & 0xffffffffL);
    }

    private void tick() {
        if (++ticks % TICK_INTERVAL != 0 || world.playerEntities.isEmpty()) return;
        MinecraftServer server = MinecraftServer.getServer();
        int view = server == null ? 10 : server.getConfigurationManager()
            .getViewDistance();
        int radius = view + MARGIN;
        for (Object o : world.playerEntities) {
            EntityPlayer player = (EntityPlayer) o;
            int pcx = (int) Math.floor(player.posX) >> 4, pcz = (int) Math.floor(player.posZ) >> 4;
            // look a bit ahead in the direction the player moves
            pcx += (int) Math.round(clampAhead(player.motionX));
            pcz += (int) Math.round(clampAhead(player.motionZ));
            queueColumns(pcx, pcz, radius);
            queueNets(player.posX, player.posZ);
        }
    }

    private static double clampAhead(double motion) {
        return Math.max(-4, Math.min(4, motion * 8));
    }

    /** Queues the columns around a column (nearest first). */
    public void prefetchAround(int cx, int cz, int radius) {
        queueColumns(cx, cz, radius);
    }

    /** Square rings around the centre, nearest first. */
    private void queueColumns(int cx, int cz, int radius) {
        for (int r = 0; r <= radius; r++) {
            for (int dz = -r; dz <= r; dz++) {
                boolean edgeRow = dz == -r || dz == r;
                for (int dx = -r; dx <= r; dx += edgeRow ? 1 : 2 * r) {
                    if (pool.getQueue()
                        .size() >= MAX_QUEUED) return;
                    submitColumn(cx + dx, cz + dz);
                    if (r == 0) break;
                }
            }
        }
    }

    private void submitColumn(int x, int z) {
        if (plans.isCached(x, z) || plans.isInProgress(x, z)) return;
        long k = key(x, z);
        if (!pending.add(k)) return;
        pool.execute(() -> {
            try {
                long t0 = System.nanoTime();
                plans.precalculate(x, z);
                GenStats.PREFETCH.add(System.nanoTime() - t0);
            } catch (Throwable t) {
                Viridium.LOGGER.warn("Prefetch of column {},{} failed", x, z, t);
            } finally {
                pending.remove(k);
            }
        });
    }

    /** Builds the river networks of the drainage basins around the player before any column needs them. */
    private void queueNets(double px, double pz) {
        queueNetsAt(px, pz);
        for (int d : NET_PROBE) {
            for (int a = 0; a < 8; a++) {
                double ang = a * Math.PI / 4;
                queueNetsAt(px + Math.cos(ang) * d, pz + Math.sin(ang) * d);
            }
        }
    }

    private void queueNetsAt(double x, double z) {
        for (long k : gen.rivers.basinsAt(x, z)) {
            int ci = net.junedev.viridium.worldgen.terrain.stages.BasinGraph.keyA(k),
                cj = net.junedev.viridium.worldgen.terrain.stages.BasinGraph.keyB(k);
            if (!gen.rivers.basins.cell(ci, cj).ocean) submitNet(ci, cj);
        }
    }

    private void submitNet(int pi, int pj) {
        if (gen.rivers.isCached(pi, pj)) return;
        long k = key(pi, pj);
        if (!pendingNets.add(k)) return;
        pool.execute(() -> {
            try {
                gen.rivers.net(pi, pj);
            } catch (Throwable t) {
                Viridium.LOGGER.warn("Prefetch of river network {},{} failed", pi, pj, t);
            } finally {
                pendingNets.remove(k);
            }
        });
    }
}
