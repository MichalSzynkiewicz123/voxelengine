package com.example.voxelrt;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Keeps track of chunks that have been generated and loaded into memory.
 * <p>
 * The manager owns both the persistent edits that the player has made and an in-memory LRU cache of
 * chunk data. When sampling blocks the cache is consulted first and, if a chunk is missing, it will
 * be generated on demand via the {@link WorldGenerator}.
 */
public class ChunkManager {
    private final WorldGenerator gen;
    private final Map<ChunkPos, Chunk> map = new HashMap<>();
    private final LinkedHashMap<ChunkPos, Chunk> lru = new LinkedHashMap<>(64, 0.75f, true);
    private final int maxLoaded;
    private final Map<Long, Integer> edits = new HashMap<>();
    private final Object lock = new Object();
    private final Object editLock = new Object();
    private final ExecutorService executor;
    private final ConcurrentHashMap<ChunkPos, CompletableFuture<Chunk>> pending = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<ChunkLoadResult> completed = new ConcurrentLinkedQueue<>();

    public ChunkManager(WorldGenerator g, int maxLoaded) {
        this.gen = g;
        this.maxLoaded = java.lang.Math.max(64, maxLoaded);
        int threads = java.lang.Math.max(1, Runtime.getRuntime().availableProcessors() - 1);
        AtomicInteger ctr = new AtomicInteger();
        ThreadFactory factory = r -> {
            Thread t = new Thread(r, "ChunkGen-" + ctr.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
        this.executor = Executors.newFixedThreadPool(threads, factory);
    }

    /**
     * Packs a world-space coordinate triple into a single long for use in hash-based collections.
     * <p>
     * The bit allocation mirrors the maximum dimensions supported by the chunk system. X and Z are
     * stored in 21-bit two's-complement form while Y is stored as an unsigned 10-bit integer which is
     * sufficient for the 0-255 vertical range of a chunk column.
     */
    public static long key(int x, int y, int z) {
        return ((long) (x & 0x1FFFFF) << 42) | ((long) (y & 0x3FF) << 32) | (long) (z & 0x1FFFFF);
    }

    public Integer getEdit(int x, int y, int z) {
        synchronized (editLock) {
            return edits.get(key(x, y, z));
        }
    }

    /**
     * Records a player edit and eagerly applies the change if the target chunk is currently loaded.
     */
    public void setEdit(int x, int y, int z, int b) {
        long k = key(x, y, z);
        synchronized (editLock) {
            edits.put(k, b);
        }
        Chunk c = getIfLoaded(new ChunkPos(java.lang.Math.floorDiv(x, Chunk.SX), java.lang.Math.floorDiv(z, Chunk.SZ)));
        if (c != null) {
            c.set(java.lang.Math.floorMod(x, Chunk.SX), y, java.lang.Math.floorMod(z, Chunk.SZ), b);
        }
    }

    /**
     * Retrieves a chunk either from the cache or by generating it on demand.
     * <p>
     * Any known edits within the chunk bounds are baked in after generation. The chunk is also marked
     * as most recently used to maintain the LRU eviction order.
     */
    public Chunk getOrLoad(ChunkPos p) {
        update();
        synchronized (lock) {
            Chunk cached = map.get(p);
            if (cached != null) {
                lru.put(p, cached);
                return cached;
            }
        }

        CompletableFuture<Chunk> future = ensureTask(p);
        Chunk chunk;
        try {
            chunk = future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Chunk generation interrupted for " + p, e);
        } catch (ExecutionException e) {
            throw new RuntimeException("Chunk generation failed for " + p, e.getCause());
        }

        update();
        synchronized (lock) {
            Chunk loaded = map.get(p);
            if (loaded != null) {
                lru.put(p, loaded);
                return loaded;
            }
            applyEdits(chunk);
            map.put(p, chunk);
            lru.put(p, chunk);
            trimToMaxLocked();
            return chunk;
        }
    }

    /**
     * Samples a block from world space coordinates, taking player edits into account.
     */
    public int sample(int x, int y, int z) {
        update();
        Integer e = getEdit(x, y, z);
        if (e != null) return e;
        ChunkPos p = new ChunkPos(java.lang.Math.floorDiv(x, Chunk.SX), java.lang.Math.floorDiv(z, Chunk.SZ));
        Chunk c = getOrLoad(p);
        return c.get(java.lang.Math.floorMod(x, Chunk.SX), y, java.lang.Math.floorMod(z, Chunk.SZ));
    }

    public Chunk getIfLoaded(ChunkPos pos) {
        update();
        synchronized (lock) {
            Chunk chunk = map.get(pos);
            if (chunk != null) {
                lru.put(pos, chunk);
            }
            return chunk;
        }
    }

    public void requestChunk(ChunkPos pos) {
        update();
        boolean alreadyLoaded;
        synchronized (lock) {
            alreadyLoaded = map.containsKey(pos);
            if (alreadyLoaded) {
                lru.put(pos, map.get(pos));
            }
        }
        if (!alreadyLoaded) {
            ensureTask(pos);
        }
    }

    public void update() {
        ChunkLoadResult result;
        while ((result = completed.poll()) != null) {
            applyEdits(result.chunk);
            synchronized (lock) {
                map.put(result.pos, result.chunk);
                lru.put(result.pos, result.chunk);
                trimToMaxLocked();
            }
        }
    }

    public void shutdown() {
        executor.shutdownNow();
    }

    private void trimToMaxLocked() {
        while (lru.size() > maxLoaded) {
            Iterator<ChunkPos> it = lru.keySet().iterator();
            if (!it.hasNext()) break;
            ChunkPos oldest = it.next();
            it.remove();
            map.remove(oldest);
        }
    }

    private CompletableFuture<Chunk> ensureTask(ChunkPos pos) {
        return pending.computeIfAbsent(pos, p -> {
            CompletableFuture<Chunk> future = new CompletableFuture<>();
            future.whenComplete((chunk, throwable) -> {
                pending.remove(p, future);
                if (throwable == null) {
                    completed.add(new ChunkLoadResult(p, chunk));
                } else {
                    System.err.println("[ChunkManager] Failed to generate chunk " + p + ": " + throwable);
                }
            });
            executor.submit(() -> {
                try {
                    Chunk chunk = new Chunk(p);
                    chunk.fill(gen);
                    future.complete(chunk);
                } catch (Throwable t) {
                    future.completeExceptionally(t);
                }
            });
            return future;
        });
    }

    private void applyEdits(Chunk chunk) {
        ChunkPos pos = chunk.pos;
        int wx0 = pos.cx() * Chunk.SX;
        int wz0 = pos.cz() * Chunk.SZ;
        synchronized (editLock) {
            for (var e : edits.entrySet()) {
                long k = e.getKey();
                int x = (int) ((k >> 42) & 0x1FFFFF);
                int y = (int) ((k >> 32) & 0x3FF);
                int z = (int) (k & 0x1FFFFF);
                if (x >= 0x100000) x -= 0x200000;
                if (z >= 0x100000) z -= 0x200000;
                if (x >= wx0 && x < wx0 + Chunk.SX && z >= wz0 && z < wz0 + Chunk.SZ) {
                    chunk.set(x - wx0, y, z - wz0, e.getValue());
                }
            }
        }
    }

    private static final class ChunkLoadResult {
        final ChunkPos pos;
        final Chunk chunk;

        ChunkLoadResult(ChunkPos pos, Chunk chunk) {
            this.pos = pos;
            this.chunk = chunk;
        }
    }
}
