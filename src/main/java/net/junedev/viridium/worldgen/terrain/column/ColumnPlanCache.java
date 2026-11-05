package net.junedev.viridium.worldgen.terrain.column;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicLong;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;

/**
 * Bounded cache of finished plans plus a map of in-flight computations, so concurrent requests for the same column
 * (server thread + background preload threads) share one computation. Eviction never changes the output.
 */
public final class ColumnPlanCache {

    /** A 32-chunk view covers 65x65 ≈ 4200 columns; keep a typical view resident. */
    public static final int DEFAULT_MAXIMUM_SIZE = 4096;

    private final ColumnPlanner planner;
    private final Cache<Long, ColumnPlan> completed;
    private final ConcurrentHashMap<Long, FutureTask<ColumnPlan>> inProgress = new ConcurrentHashMap<>();
    private final AtomicLong computed = new AtomicLong(), requests = new AtomicLong();

    public ColumnPlanCache(ColumnPlanner planner) {
        this(planner, DEFAULT_MAXIMUM_SIZE);
    }

    public ColumnPlanCache(ColumnPlanner planner, int maximumSize) {
        this.planner = planner;
        this.completed = CacheBuilder.newBuilder()
            .maximumSize(maximumSize)
            .concurrencyLevel(4)
            .build();
    }

    public ColumnPlanner planner() {
        return planner;
    }

    public ColumnPlan get(int chunkX, int chunkZ) {
        requests.incrementAndGet();
        long key = key(chunkX, chunkZ);
        ColumnPlan cached = completed.getIfPresent(key);
        if (cached != null) return cached;

        FutureTask<ColumnPlan> created = new FutureTask<>(() -> {
            computed.incrementAndGet();
            return planner.plan(chunkX, chunkZ);
        });
        FutureTask<ColumnPlan> task = inProgress.putIfAbsent(key, created);
        if (task == null) {
            task = created;
            task.run();
        }
        try {
            ColumnPlan result = task.get();
            completed.put(key, result);
            return result;
        } catch (InterruptedException e) {
            Thread.currentThread()
                .interrupt();
            throw new IllegalStateException("Interrupted while planning column " + chunkX + "," + chunkZ, e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException re) throw re;
            if (cause instanceof Error err) throw err;
            throw new IllegalStateException("Could not plan column " + chunkX + "," + chunkZ, cause);
        } finally {
            if (task.isDone()) inProgress.remove(key, task);
        }
    }

    /** Warms the cache; safe to call from any thread. */
    public void precalculate(int chunkX, int chunkZ) {
        get(chunkX, chunkZ);
    }

    public long computedCount() {
        return computed.get();
    }

    public long requestCount() {
        return requests.get();
    }

    private static long key(int chunkX, int chunkZ) {
        return (chunkX & 0xffffffffL) | ((long) chunkZ << 32);
    }
}
