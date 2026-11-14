package net.junedev.viridium.worldgen.nether;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;

import net.junedev.viridium.worldgen.terrain.core.GenMath;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;

/**
 * Bounded cache of Nether plans with de-duplication of concurrent computations (same pattern as the overworld's
 * {@code ColumnPlanCache}). A plan is ~70 KiB, so 1024 plans ≈ 70 MiB at most.
 */
public final class NetherPlanCache {

    private final NetherPlanner planner;
    private final Cache<Long, NetherPlan> completed;
    private final ConcurrentHashMap<Long, FutureTask<NetherPlan>> inProgress = new ConcurrentHashMap<>();

    public NetherPlanCache(NetherPlanner planner, int maximumSize) {
        this.planner = planner;
        this.completed = CacheBuilder.newBuilder()
            .maximumSize(maximumSize)
            .concurrencyLevel(4)
            .build();
    }

    public NetherPlanner planner() {
        return planner;
    }

    public NetherPlan get(int chunkX, int chunkZ) {
        long key = GenMath.mixKey((chunkX & 0xffffffffL) | ((long) chunkZ << 32));
        NetherPlan cached = completed.getIfPresent(key);
        if (cached != null) return cached;
        FutureTask<NetherPlan> created = new FutureTask<>(() -> planner.plan(chunkX, chunkZ));
        FutureTask<NetherPlan> task = inProgress.putIfAbsent(key, created);
        if (task == null) {
            task = created;
            task.run();
        }
        try {
            NetherPlan result = task.get();
            completed.put(key, result);
            return result;
        } catch (InterruptedException e) {
            Thread.currentThread()
                .interrupt();
            throw new IllegalStateException("Interrupted while planning nether column " + chunkX + "," + chunkZ, e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException re) throw re;
            if (cause instanceof Error err) throw err;
            throw new IllegalStateException("Could not plan nether column " + chunkX + "," + chunkZ, cause);
        } finally {
            if (task.isDone()) inProgress.remove(key, task);
        }
    }

    public long size() {
        return completed.size();
    }
}
