package com.finndog.structure_editor;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.WorldChunk;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;

// Chunk load-on-demand + LRU cap for region ops.
// Region ops previously loaded every chunk in the selection inside a single server
// tick, holding strong refs to all of them at once. For big regions that pinned a
// lot of memory and blocked the main thread. ChunkCache spreads the iteration
// across ticks (maxPerTick chunks per tick) and holds at most maxLoaded strong
// refs at any time, letting MC's own unload path reclaim the rest between ticks.
public class ChunkCache {

    private static final int DEFAULT_MAX_PER_TICK = 64;
    private static final int DEFAULT_MAX_LOADED = 64;

    private static final Deque<Job<?>> jobs = new ArrayDeque<>();
    private static boolean initialized = false;

    public static void init() {
        if (initialized) return;
        initialized = true;
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            Job<?> current;
            synchronized (jobs) {
                current = jobs.peek();
            }
            if (current == null) return;
            current.tick();
            if (current.isDone()) {
                synchronized (jobs) {
                    jobs.poll();
                }
            }
        });
    }

    public static int defaultMaxPerTick() { return DEFAULT_MAX_PER_TICK; }
    public static int defaultMaxLoaded() { return DEFAULT_MAX_LOADED; }

    // Runs perChunk against every chunk in the rectangle (minCx..maxCx, minCz..maxCz).
    // The callback runs on the main server thread with an already-loaded WorldChunk.
    // Return non-null from perChunk to collect a partial result; return null to skip.
    // The returned future completes on the main thread once every chunk has been visited.
    public static <R> CompletableFuture<List<R>> runOverRange(
        ServerWorld world,
        int minCx, int maxCx, int minCz, int maxCz,
        BiFunction<WorldChunk, ChunkPos, R> perChunk
    ) {
        return runOverRange(world, minCx, maxCx, minCz, maxCz, DEFAULT_MAX_PER_TICK, DEFAULT_MAX_LOADED, perChunk);
    }

    public static <R> CompletableFuture<List<R>> runOverRange(
        ServerWorld world,
        int minCx, int maxCx, int minCz, int maxCz,
        int maxPerTick, int maxLoaded,
        BiFunction<WorldChunk, ChunkPos, R> perChunk
    ) {
        Job<R> job = new Job<>(world, minCx, maxCx, minCz, maxCz, maxPerTick, maxLoaded, perChunk);
        synchronized (jobs) {
            jobs.offer(job);
        }
        return job.future;
    }

    // Sync single-chunk helper for point ops. Loads the chunk on-demand and returns it.
    // No LRU bookkeeping — point ops touch one chunk and let MC handle the ref lifetime.
    public static WorldChunk acquire(ServerWorld world, int cx, int cz) {
        return world.getChunk(cx, cz);
    }

    private static final class Job<R> {
        final ServerWorld world;
        final Deque<long[]> pending = new ArrayDeque<>();
        final int maxPerTick;
        final int maxLoaded;
        final BiFunction<WorldChunk, ChunkPos, R> perChunk;
        final List<R> results = new ArrayList<>();
        final LinkedHashMap<Long, WorldChunk> lru;
        final CompletableFuture<List<R>> future = new CompletableFuture<>();

        Job(ServerWorld world, int minCx, int maxCx, int minCz, int maxCz,
            int maxPerTick, int maxLoaded, BiFunction<WorldChunk, ChunkPos, R> perChunk) {
            this.world = world;
            this.maxPerTick = Math.max(1, maxPerTick);
            this.maxLoaded = Math.max(1, maxLoaded);
            this.perChunk = perChunk;
            this.lru = new LinkedHashMap<>(this.maxLoaded, 0.75f, true);
            for (int cx = minCx; cx <= maxCx; cx++) {
                for (int cz = minCz; cz <= maxCz; cz++) {
                    pending.offer(new long[]{cx, cz});
                }
            }
        }

        void tick() {
            int processed = 0;
            while (processed < maxPerTick && !pending.isEmpty()) {
                long[] pos = pending.poll();
                int cx = (int) pos[0];
                int cz = (int) pos[1];
                long key = ChunkPos.toLong(cx, cz);

                WorldChunk chunk = lru.get(key);
                if (chunk == null) {
                    try {
                        chunk = world.getChunk(cx, cz);
                    } catch (Exception e) {
                        future.completeExceptionally(e);
                        pending.clear();
                        lru.clear();
                        return;
                    }
                    if (lru.size() >= maxLoaded) {
                        Iterator<Map.Entry<Long, WorldChunk>> it = lru.entrySet().iterator();
                        it.next();
                        it.remove();
                    }
                    lru.put(key, chunk);
                }

                try {
                    R r = perChunk.apply(chunk, new ChunkPos(cx, cz));
                    if (r != null) results.add(r);
                } catch (Exception e) {
                    future.completeExceptionally(e);
                    pending.clear();
                    lru.clear();
                    return;
                }
                processed++;
            }

            if (pending.isEmpty()) {
                lru.clear();
                future.complete(results);
            }
        }

        boolean isDone() {
            return pending.isEmpty() || future.isDone();
        }
    }
}
